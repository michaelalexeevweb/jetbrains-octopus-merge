package octopusmerge.core

enum class BranchState {
    /** Merges without conflicts at its position in the merge order. */
    CLEAN,

    /** Conflicts with the target or with a branch merged before it. */
    CONFLICT,

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
    val conflictFiles: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Predicts the outcome of a merge without touching the working tree or any ref.
 *
 * The branches are merged in order with `git merge-tree --write-tree`, each one on top of the
 * previous simulated result, so a conflict between two of the selected branches shows up too,
 * on the later one. Simulated commits are dangling objects that `git gc` removes.
 */
class MergeAnalyzer(private val git: GitExecutor) {
    private val repo = GitRepo(git)

    fun isSupported(): Boolean = (repo.version() ?: return false) >= GitVersion.MERGE_TREE_WRITE_TREE

    fun analyze(target: String, branches: List<String>, options: MergeOptions = MergeOptions()): List<BranchAnalysis> {
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

            val result = git.run(buildList {
                addAll(listOf("merge-tree", "--write-tree", "--name-only", "--no-messages"))
                if (options.allowUnrelatedHistories) add("--allow-unrelated-histories")
                options.conflictStrategy.gitOption?.let { add("-X"); add(it) }
                add(current); add(hash)
            })
            val tree = result.stdout.firstOrNull()?.trim()
            if (result.exitCode !in 0..1 || tree.isNullOrEmpty()) {
                return@map BranchAnalysis(branch, BranchState.ERROR, commits, error = result.errorText.ifEmpty { "git merge-tree failed" })
            }

            current = git.output(
                listOf("commit-tree", tree, "-p", current, "-p", hash, "-m", "octopus-merge preview"),
                config = PREVIEW_IDENTITY,
            )
            if (result.exitCode == 0) {
                BranchAnalysis(branch, BranchState.CLEAN, commits)
            } else {
                val files = result.stdout.drop(1).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                BranchAnalysis(branch, BranchState.CONFLICT, commits, files)
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
