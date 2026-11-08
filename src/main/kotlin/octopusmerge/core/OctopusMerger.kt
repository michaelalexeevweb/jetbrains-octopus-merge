package octopusmerge.core

/** The merge was cancelled: by the user, or because conflicts were left unresolved. */
class MergeCancelledException(problem: MergeProblem, rollbackNotes: List<RollbackNote> = emptyList()) :
    MergeException(problem, rollbackNotes)

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
        repo.operationInProgress()?.let { throw MergeException(MergeProblem.OperationInProgress(it)) }
        if (!repo.isLocalBranch(request.target)) throw MergeException(MergeProblem.NotLocalBranch(request.target))

        val requested = request.branches.distinct().filter { it != request.target }
        if (requested.isEmpty()) throw MergeException(MergeProblem.NoBranches)
        val resolved = requested.map { it to repo.requireCommit(it) }

        val startBranch = repo.currentBranch()
        val startHead = repo.head()
        var stashed = false
        if (repo.hasTrackedChanges()) {
            if (!options.autoStash) {
                throw MergeException(MergeProblem.UncommittedChanges)
            }
            progress.step(MergeStep.Stashing)
            git.output("stash", "push", "-m", "octopus-merge: autostash before merging into ${request.target}")
            stashed = true
            progress.filesChanged()
        }

        var switched = false
        val rollbackNotes = mutableListOf<RollbackNote>()
        try {
            if (startBranch != request.target) {
                progress.step(MergeStep.CheckingOut(request.target))
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
                    .onFailure { rollbackNotes += RollbackNote.ResetFailed(request.target, originalHead, it.message) }
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
                        .onFailure { rollbackNotes += RollbackNote.SwitchBackFailed(startBranch ?: startHead, it.message) }
                }
                if (stashed && runCatching { popStash() }.getOrDefault(StashStatus.KEPT) == StashStatus.KEPT) {
                    rollbackNotes += RollbackNote.StashKept
                }
                runCatching { progress.filesChanged() }
            }
            throw rollbackError(e, rollbackNotes)
        }
    }

    /** Adds what the rollback could not restore to the error, so the user is never told everything is back. */
    private fun rollbackError(e: Throwable, notes: List<RollbackNote>): Throwable {
        if (notes.isEmpty()) return e
        // The last case is a bug, not a merge problem: the notes only go to the log with it.
        return when {
            e is MergeCancelledException -> MergeCancelledException(e.problem, e.rollbackNotes + notes)
            e is MergeException -> MergeException(e.problem, e.rollbackNotes + notes)
            progress.isCanceled -> MergeCancelledException(MergeProblem.Cancelled, notes)
            else -> e.apply { addSuppressed(IllegalStateException("Rollback incomplete: $notes")) }
        }
    }

    /** Resets the target to the commit it had before [merge], restoring deleted branches. */
    fun undo(outcome: MergeOutcome) {
        when (outcome.status) {
            MergeStatus.COMMITTED -> {
                if (repo.currentBranch() != outcome.target) throw MergeException(MergeProblem.UndoNeedsCheckout(outcome.target))
                if (repo.head() != outcome.commit) throw MergeException(MergeProblem.TargetMoved(outcome.target))
                if (repo.hasTrackedChanges()) throw MergeException(MergeProblem.UndoUncommittedChanges)
                git.output("reset", "--keep", outcome.originalHead)
                outcome.deletedBranches.forEach { (name, hash) ->
                    if (!repo.isLocalBranch(name)) git.output("branch", name, hash)
                }
            }

            MergeStatus.PREPARED -> {
                if (!repo.gitPath("MERGE_HEAD").exists()) throw MergeException(MergeProblem.PreparedMergeGone)
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
        progress.step(MergeStep.MergingAll(names))

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

        progress.step(MergeStep.CreatingCommit)
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
            progress.step(MergeStep.MergingOne(name, index + 1, toMerge.size))
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

            if (repo.unmergedFiles().isEmpty()) throw MergeException(MergeProblem.StepFailed(name, step.errorText))

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
                throw MergeCancelledException(MergeProblem.ConflictsNotResolved(name))
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
        if (!result.success) throw MergeException(MergeProblem.HookFailed(name, result.errorText))
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
        progress.step(MergeStep.RestoringStash)
        return if (git.run("stash", "pop").success) StashStatus.RESTORED else StashStatus.KEPT
    }
}
