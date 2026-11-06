package octopusmerge.core

class MergeCancelledException(message: String) : MergeException(message)

/**
 * Merges several branches into a target with one commit whose parents are the target
 * and every merged branch.
 *
 * 1. Native `git merge` with all branches at once (octopus strategy). It succeeds when nothing conflicts.
 * 2. Otherwise the branches are merged one by one; each conflict goes to the [ConflictResolver]
 *    (or is settled by `-X ours/theirs`). The temporary step commits are then replaced by one commit
 *    built with `git commit-tree`: the resolved tree with every branch as a parent, exactly what an
 *    octopus merge would have produced had it been able to stop for conflicts.
 *
 * Any failure or cancel puts everything back: the target ref, the checked-out branch and stashed changes.
 */
class OctopusMerger(
    private val git: GitExecutor,
    private val resolver: ConflictResolver,
    private val progress: MergeProgress = MergeProgress.None,
) {
    private val repo = GitRepo(git)

    fun merge(request: MergeRequest): MergeOutcome {
        val options = request.options
        repo.operationInProgress()?.let { throw MergeException("A $it is in progress. Finish or abort it first.") }
        if (!repo.isLocalBranch(request.target)) throw MergeException("Target must be a local branch: ${request.target}")

        val requested = request.branches.distinct().filter { it != request.target }
        if (requested.isEmpty()) throw MergeException("Select branches to merge.")
        val resolved = requested.map { it to repo.requireCommit(it) }

        val startBranch = repo.currentBranch()
        val startHead = repo.head()
        var stashed = false
        if (repo.hasTrackedChanges()) {
            if (!options.autoStash) {
                throw MergeException("There are uncommitted changes. Commit or stash them, or enable auto-stash.")
            }
            progress.step("Stashing local changes")
            git.output("stash", "push", "-m", "octopus-merge: autostash before merging into ${request.target}")
            stashed = true
            progress.filesChanged()
        }

        var switched = false
        val rollbackNotes = mutableListOf<String>()
        try {
            if (startBranch != request.target) {
                progress.step("Checking out ${request.target}")
                git.output("checkout", "--quiet", request.target)
                switched = true
                progress.filesChanged()
            }

            val originalHead = repo.head()
            val (toMerge, skipped) = repo.reduceHeads(originalHead, resolved)

            if (toMerge.isEmpty()) {
                if (switched) git.output("checkout", "--quiet", startBranch ?: startHead)
                val stash = if (stashed) popStash() else StashStatus.NOT_USED
                return MergeOutcome(
                    MergeStatus.NOTHING_TO_MERGE, request.target, originalHead, null,
                    emptyList(), skipped, emptyList(), emptyList(), stash, emptyMap(), emptyList(),
                )
            }

            val message = buildMessage(request, toMerge, originalHead)
            val merged = try {
                performMerge(request, toMerge, originalHead, message)
            } catch (e: Throwable) {
                progress.nonCancellable { runCatching { restoreHead(originalHead) } }
                    .onFailure { rollbackNotes += "Could not reset ${request.target} to ${originalHead.take(SHORT_HASH)}: ${it.message}" }
                throw e
            }
            progress.filesChanged()

            val (deleted, notDeleted) = if (merged.status == MergeStatus.COMMITTED && options.deleteMergedBranches) {
                deleteBranches(request.target, toMerge, merged.commit!!)
            } else {
                emptyMap<String, String>() to emptyList()
            }

            val stash = when {
                !stashed -> StashStatus.NOT_USED
                merged.status == MergeStatus.PREPARED -> StashStatus.KEPT
                else -> popStash()
            }
            progress.filesChanged()

            return MergeOutcome(
                merged.status, request.target, originalHead, merged.commit,
                toMerge.map { it.first }, skipped, merged.conflicted, merged.preResolved, stash, deleted, notDeleted,
            )
        } catch (e: Throwable) {
            progress.nonCancellable {
                if (switched) {
                    runCatching { git.output("checkout", "--quiet", startBranch ?: startHead) }
                        .onFailure { rollbackNotes += "Could not switch back to ${startBranch ?: startHead}: ${it.message}" }
                }
                if (stashed && runCatching { popStash() }.getOrDefault(StashStatus.KEPT) == StashStatus.KEPT) {
                    rollbackNotes += "Your local changes are kept in the stash (git stash list)."
                }
                runCatching { progress.filesChanged() }
            }
            throw rollbackError(e, rollbackNotes)
        }
    }

    /** Adds what the rollback could not restore to the error, so the user is never told everything is back. */
    private fun rollbackError(e: Throwable, notes: List<String>): Throwable {
        if (notes.isEmpty()) return e
        fun text(first: String?) = (listOf(first.orEmpty()) + notes).joinToString("\n")
        return when {
            e is MergeCancelledException -> MergeCancelledException(text(e.message))
            e is MergeException -> MergeException(text(e.message))
            progress.isCanceled -> MergeCancelledException(text("The merge was cancelled."))
            else -> e.apply { addSuppressed(MergeException(notes.joinToString("\n"))) }
        }
    }

    /** Resets the target to the commit it had before [merge], restoring deleted branches. */
    fun undo(outcome: MergeOutcome) {
        when (outcome.status) {
            MergeStatus.COMMITTED -> {
                if (repo.currentBranch() != outcome.target) throw MergeException("Check out ${outcome.target} to undo the merge.")
                if (repo.head() != outcome.commit) throw MergeException("${outcome.target} has moved since the merge; undo it manually.")
                if (repo.hasTrackedChanges()) throw MergeException("There are uncommitted changes. Commit or stash them first.")
                git.output("reset", "--keep", outcome.originalHead)
                outcome.deletedBranches.forEach { (name, hash) ->
                    if (!repo.isLocalBranch(name)) git.output("branch", name, hash)
                }
            }

            MergeStatus.PREPARED -> {
                if (!repo.gitPath("MERGE_HEAD").exists()) throw MergeException("The prepared merge is no longer in progress.")
                git.output("reset", "--merge")
                if (outcome.stash == StashStatus.KEPT) popStash()
            }

            MergeStatus.NOTHING_TO_MERGE -> Unit
        }
        progress.filesChanged()
    }

    private class Merged(
        val status: MergeStatus,
        val commit: String?,
        val conflicted: List<String> = emptyList(),
        val preResolved: List<String> = emptyList(),
    )

    private class Steps(val conflicted: List<String>, val preResolved: List<String>)

    private fun performMerge(request: MergeRequest, toMerge: List<Pair<String, String>>, originalHead: String, message: String): Merged {
        val options = request.options
        val names = toMerge.map { it.first }
        progress.checkCanceled()
        progress.step("Merging ${names.joinToString(", ")}")

        val native = git.run(
            buildList {
                addAll(listOf("merge", "--no-ff", "--no-log"))
                if (options.noVerify) add("--no-verify")
                if (options.noCommit) add("--no-commit")
                if (options.allowUnrelatedHistories) add("--allow-unrelated-histories")
                if (options.gpgSign) add("-S")
                // The octopus strategy ignores -X; it only matters for a single branch.
                if (toMerge.size == 1) {
                    options.conflictStrategy.gitOption?.let {
                        add("-X")
                        add(it)
                    }
                }
                add("-m")
                add(message)
                addAll(names)
            },
        )
        if (native.success) {
            return if (options.noCommit) {
                Merged(MergeStatus.PREPARED, null)
            } else {
                Merged(MergeStatus.COMMITTED, repo.head())
            }
        }

        restoreHead(originalHead)
        val steps = mergeOneByOne(request, toMerge)

        progress.step("Creating merge commit")
        val tree = git.output("rev-parse", "HEAD^{tree}")
        val stepHead = repo.head()
        val hashes = toMerge.map { it.second }

        if (options.noCommit) {
            git.output("reset", "--soft", originalHead)
            repo.gitPath("MERGE_HEAD").writeText(hashes.joinToString("\n", postfix = "\n"))
            repo.gitPath("MERGE_MSG").writeText(message + "\n")
            repo.gitPath("MERGE_MODE").writeText("no-ff")
            return Merged(MergeStatus.PREPARED, null, steps.conflicted, steps.preResolved)
        }

        val hooks = !options.noVerify && (repo.version() ?: GitVersion(0, 0, 0)) >= GitVersion.HOOK_RUN
        if (hooks) runHook("pre-merge-commit")
        val finalMessage = if (hooks) commitMsgHook(message) else message

        val parents = (listOf(originalHead) + hashes).flatMap { listOf("-p", it) }
        val commit = git.output(
            buildList {
                add("commit-tree")
                add(tree)
                addAll(parents)
                if (options.gpgSign) add("-S")
                add("-m")
                add(finalMessage)
            },
        )
        git.output("update-ref", "-m", "merge ${names.joinToString(" ")}: Merge made by octopus-merge", "HEAD", commit, stepHead)
        if (hooks) git.run("hook", "run", "--ignore-missing", "post-merge", "--", "0")
        return Merged(MergeStatus.COMMITTED, commit, steps.conflicted, steps.preResolved)
    }

    private fun mergeOneByOne(request: MergeRequest, toMerge: List<Pair<String, String>>): Steps {
        val options = request.options
        val conflicted = mutableListOf<String>()
        val preResolved = mutableListOf<String>()
        toMerge.forEachIndexed { index, (name, hash) ->
            progress.checkCanceled()
            progress.step("Merging $name (${index + 1}/${toMerge.size})")
            val step = git.run(
                buildList {
                    addAll(listOf("merge", "--no-ff", "--no-log", "--no-verify", "--no-gpg-sign"))
                    if (options.allowUnrelatedHistories) add("--allow-unrelated-histories")
                    options.conflictStrategy.gitOption?.let {
                        add("-X")
                        add(it)
                    }
                    add("-m")
                    add("octopus-merge step: $name")
                    add(hash)
                },
            )
            if (step.success) return@forEachIndexed

            if (repo.unmergedFiles().isEmpty()) throw MergeException("Merging $name failed:\n${step.errorText}")

            applyResolutions(request.resolutions)
            val files = repo.unmergedFiles()
            if (files.isEmpty()) {
                git.output("commit", "--no-verify", "--no-gpg-sign", "--no-edit")
                preResolved += name
                return@forEachIndexed
            }

            progress.filesChanged()
            val resolvedAll = resolver.resolve(ConflictStep(name, request.target, index + 1, toMerge.size, files))
            progress.checkCanceled()
            if (!resolvedAll || repo.unmergedFiles().isNotEmpty()) {
                throw MergeCancelledException("Conflicts in $name were not resolved. The merge was rolled back.")
            }
            git.output("commit", "--no-verify", "--no-gpg-sign", "--no-edit")
            conflicted += name
        }
        return Steps(conflicted, preResolved)
    }

    /** Writes and stages every unmerged file whose exact conflict was resolved in the preview. */
    private fun applyResolutions(resolutions: Resolutions) {
        if (resolutions.isEmpty()) return
        val applicable = repo.unmergedConflicts().filter { it.isContentConflict && resolutions.has(it) }
        if (applicable.isEmpty()) return
        for (conflict in applicable) {
            java.io.File(git.root, conflict.path).writeBytes(resolutions.get(conflict)!!)
        }
        git.output(listOf("add", "--") + applicable.map { it.path })
    }

    private fun runHook(name: String, vararg args: String) {
        val result = git.run(listOf("hook", "run", "--ignore-missing", name) + if (args.isEmpty()) emptyList() else listOf("--") + args)
        if (!result.success) throw MergeException("The $name hook failed:\n${result.errorText}")
    }

    private fun commitMsgHook(message: String): String {
        val file = repo.gitPath("OCTOPUS_MERGE_MSG")
        file.writeText(message + "\n")
        try {
            runHook("commit-msg", file.absolutePath)
            return file.readText().trimEnd()
        } finally {
            file.delete()
        }
    }

    private fun buildMessage(request: MergeRequest, toMerge: List<Pair<String, String>>, originalHead: String): String {
        val options = request.options
        val subject = request.message?.takeIf { it.isNotBlank() } ?: MergeMessage.subject(request.target, toMerge.map { it.first })
        val log = if (options.appendLog) {
            MergeMessage.logSection(toMerge.associate { (name, hash) -> name to repo.subjects(originalHead, hash) })
        } else {
            null
        }
        val signoff = if (options.signoff) repo.committerIdent() else null
        return MergeMessage.build(subject, log, signoff)
    }

    private fun restoreHead(originalHead: String) {
        git.run("reset", "--merge")
        if (repo.head() != originalHead || repo.hasTrackedChanges()) {
            git.output("reset", "--hard", originalHead)
        }
        progress.filesChanged()
    }

    private fun deleteBranches(
        target: String,
        toMerge: List<Pair<String, String>>,
        commit: String,
    ): Pair<Map<String, String>, List<String>> {
        val deleted = linkedMapOf<String, String>()
        val notDeleted = mutableListOf<String>()
        for ((name, hash) in toMerge) {
            if (name == target || !repo.isLocalBranch(name)) continue
            val safe = repo.isAncestor(hash, commit)
            if (safe && git.run("branch", "-D", name).success) deleted[name] = hash else notDeleted += name
        }
        return deleted to notDeleted
    }

    private fun popStash(): StashStatus {
        progress.step("Restoring local changes")
        return if (git.run("stash", "pop").success) StashStatus.RESTORED else StashStatus.KEPT
    }

    private companion object {
        const val SHORT_HASH = 8
    }
}
