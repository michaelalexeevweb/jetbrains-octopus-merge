package octopusmerge.core

enum class BranchState {
    /** Merges without conflicts at its position in the merge order. */
    CLEAN,

    /** Conflicts with the target or with a branch merged before it. */
    CONFLICT,

    /** Conflicts, but every conflicted file already has a resolution made in the preview. */
    RESOLVED,

    /** Already contained in the target or in an earlier branch: it will be skipped. */
    ALREADY_MERGED,

    /** Cannot be merged as is: unknown ref, unrelated history, etc. See [BranchAnalysis.error]. */
    ERROR,
}

data class BranchAnalysis(
    val branch: String,
    val state: BranchState,
    /** Commits the branch brings into the target. */
    val commits: Int = 0,
    /** Conflicted files with their versions; filled for [BranchState.CONFLICT] and [BranchState.RESOLVED]. */
    val conflicts: List<FileConflict> = emptyList(),
    val error: String? = null,
    /** How many of [conflicts] have a resolution made in the preview. */
    val resolvedCount: Int = 0,
) {
    val conflictFiles: List<String> get() = conflicts.map { it.path }
}

/**
 * Predicts the outcome of a merge without touching the working tree or any ref.
 *
 * The branches are merged in order with `git merge-tree --write-tree`, each one on top of the
 * previous simulated result, so a conflict between two of the selected branches shows up too,
 * on the later one. Simulated commits are dangling objects that `git gc` removes.
 *
 * When every conflict of a branch has a resolution in [Resolutions], the resolved content goes into
 * the simulated result, so later branches are predicted against what the real merge will contain.
 */
class MergeAnalyzer(private val git: GitExecutor) {
    private val repo = GitRepo(git)

    fun isSupported(): Boolean = (repo.version() ?: return false) >= GitVersion.MERGE_TREE_WRITE_TREE

    /** Content of one version of a conflicted file. */
    fun blob(side: ConflictSide): ByteArray = repo.blob(side.blob)

    fun analyze(
        target: String,
        branches: List<String>,
        options: MergeOptions = MergeOptions(),
        resolutions: Resolutions = Resolutions(),
    ): List<BranchAnalysis> {
        val targetHash = repo.resolveCommit(target) ?: return branches.map { BranchAnalysis(it, BranchState.ERROR, error = "Unknown target: $target") }
        val hashes = branches.associateWith { repo.resolveCommit(it) }
        val (toMerge, _) = repo.reduceHeads(targetHash, hashes.mapNotNull { (name, hash) -> hash?.let { name to it } })
        val mergedNames = toMerge.map { it.first }.toSet()
        var current = targetHash

        return branches.map { branch ->
            val hash = hashes[branch]
                ?: return@map BranchAnalysis(branch, BranchState.ERROR, error = "Unknown branch or revision")
            if (branch !in mergedNames) {
                return@map BranchAnalysis(branch, BranchState.ALREADY_MERGED)
            }
            val commits = repo.commitCount(targetHash, hash)

            val result = git.runRaw(buildList {
                addAll(listOf("merge-tree", "--write-tree", "-z", "--no-messages"))
                if (options.allowUnrelatedHistories) add("--allow-unrelated-histories")
                options.conflictStrategy.gitOption?.let { add("-X"); add(it) }
                add(current); add(hash)
            })
            val records = result.records
            val tree = records.firstOrNull()?.trim()
            if (result.exitCode !in 0..1 || tree.isNullOrEmpty()) {
                return@map BranchAnalysis(branch, BranchState.ERROR, commits, error = result.errorText.ifEmpty { "git merge-tree failed" })
            }

            val conflicts = if (result.exitCode == 1) FileConflict.parse(records.drop(1)) else emptyList()
            val resolved = conflicts.filter { it.isContentConflict && resolutions.has(it) }
            val fullyResolved = conflicts.isNotEmpty() && resolved.size == conflicts.size
            val resultTree = if (fullyResolved) {
                repo.treeWithFiles(tree, resolved.associate { it.path to (it.ours!!.mode to resolutions.get(it)!!) })
            } else tree

            current = git.output(
                listOf("commit-tree", resultTree, "-p", current, "-p", hash, "-m", "octopus-merge preview"),
                config = PREVIEW_IDENTITY,
            )
            when {
                result.exitCode == 0 -> BranchAnalysis(branch, BranchState.CLEAN, commits)
                fullyResolved -> BranchAnalysis(branch, BranchState.RESOLVED, commits, conflicts, resolvedCount = resolved.size)
                else -> BranchAnalysis(branch, BranchState.CONFLICT, commits, conflicts, resolvedCount = resolved.size)
            }
        }
    }

    private companion object {
        /** Simulated commits need an identity even where none is configured. */
        val PREVIEW_IDENTITY = listOf(
            "user.name=octopus-merge",
            "user.email=octopus-merge@localhost",
            "commit.gpgSign=false",
        )
    }
}
