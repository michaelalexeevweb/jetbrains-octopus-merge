package octopusmerge.core

import java.io.File

/** Repository queries shared by the merger and the analyzer. */
class GitRepo(val git: GitExecutor) {

    fun resolveCommit(revision: String): String? =
        git.run("rev-parse", "--verify", "--quiet", "$revision^{commit}").takeIf { it.success }?.output

    fun requireCommit(revision: String): String =
        resolveCommit(revision) ?: throw MergeException("Unknown branch or revision: $revision")

    fun head(): String = requireCommit("HEAD")

    /** Current branch name, or null on a detached HEAD. */
    fun currentBranch(): String? = git.output("rev-parse", "--abbrev-ref", "HEAD").takeIf { it != "HEAD" }

    fun isLocalBranch(name: String): Boolean =
        git.run("rev-parse", "--verify", "--quiet", "refs/heads/$name").success

    fun isAncestor(ancestor: String, descendant: String): Boolean =
        git.run("merge-base", "--is-ancestor", ancestor, descendant).exitCode == 0

    /**
     * Splits branches into those to merge and those to skip, like git's own head reduction:
     * a branch is skipped when the target or another selected branch already contains it.
     * Order is kept; of two branches pointing at the same commit the first one wins.
     */
    fun reduceHeads(target: String, branches: List<Pair<String, String>>): Pair<List<Pair<String, String>>, List<String>> {
        val candidates = branches.filter { (_, hash) -> !isAncestor(hash, target) }
        val independent = if (candidates.isEmpty()) emptySet() else
            git.output(listOf("merge-base", "--independent") + candidates.map { it.second }.distinct()).lines().toSet()
        val seen = mutableSetOf<String>()
        val toMerge = candidates.filter { (_, hash) -> hash in independent && seen.add(hash) }
        val skipped = branches.map { it.first } - toMerge.map { it.first }.toSet()
        return toMerge to skipped
    }

    fun hasTrackedChanges(): Boolean = git.output("status", "--porcelain", "--untracked-files=no").isNotEmpty()

    fun unmergedFiles(): List<String> =
        git.output("diff", "--name-only", "--diff-filter=U").lines().filter { it.isNotBlank() }.distinct()

    fun commitCount(from: String, to: String): Int = git.output("rev-list", "--count", "$from..$to").toInt()

    fun subjects(from: String, to: String): List<String> =
        git.output("log", "--no-merges", "--format=%s", "$from..$to").lines().filter { it.isNotBlank() }

    fun gitPath(name: String): File {
        val path = File(git.output("rev-parse", "--git-path", name))
        return if (path.isAbsolute) path else File(git.root, path.path)
    }

    /** Name of a merge, rebase, cherry-pick, revert or bisect in progress, or null. */
    fun operationInProgress(): String? = mapOf(
        "MERGE_HEAD" to "merge",
        "rebase-merge" to "rebase",
        "rebase-apply" to "rebase",
        "CHERRY_PICK_HEAD" to "cherry-pick",
        "REVERT_HEAD" to "revert",
        "BISECT_LOG" to "bisect",
    ).entries.firstOrNull { gitPath(it.key).exists() }?.value

    /** `Name <email>` of the committer, for the Signed-off-by trailer. */
    fun committerIdent(): String {
        val name = git.run("config", "user.name").output
        val email = git.run("config", "user.email").output
        if (name.isEmpty() || email.isEmpty()) throw MergeException("Set user.name and user.email to sign off the merge.")
        return "$name <$email>"
    }

    fun version(): GitVersion? = GitVersion.parse(git.run("version").output)
}

data class GitVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<GitVersion> {
    override fun compareTo(other: GitVersion): Int =
        compareValuesBy(this, other, GitVersion::major, GitVersion::minor, GitVersion::patch)

    companion object {
        /** `git merge-tree --write-tree`, used for the conflict preview. */
        val MERGE_TREE_WRITE_TREE = GitVersion(2, 38, 0)

        /** `git hook run`, used to run hooks around the final commit. */
        val HOOK_RUN = GitVersion(2, 36, 0)

        fun parse(text: String): GitVersion? {
            val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(text) ?: return null
            val (major, minor, patch) = match.destructured
            return GitVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
        }
    }
}
