package octopusmerge.core

/** What to do when a branch conflicts. */
enum class ConflictStrategy(val gitOption: String?) {
    /** Stop on each conflicting branch and let the user resolve it. */
    RESOLVE_MANUALLY(null),

    /** `-X ours`: conflicting hunks keep the target side. */
    PREFER_TARGET("ours"),

    /** `-X theirs`: conflicting hunks take the merged branch side. */
    PREFER_BRANCHES("theirs"),
}

data class MergeOptions(
    /** `--no-verify`: skip pre-merge-commit and commit-msg hooks. */
    val noVerify: Boolean = false,
    /** `--no-commit`: prepare the merge (MERGE_HEAD with every branch) and let the user commit. */
    val noCommit: Boolean = false,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.RESOLVE_MANUALLY,
    /** `--allow-unrelated-histories`. */
    val allowUnrelatedHistories: Boolean = false,
    /** `--signoff`: add a Signed-off-by trailer. */
    val signoff: Boolean = false,
    /** `-S`: GPG-sign the merge commit. */
    val gpgSign: Boolean = false,
    /** `--log`: list the merged commits in the message. */
    val appendLog: Boolean = false,
    /** Stash uncommitted changes before the merge and restore them afterwards. */
    val autoStash: Boolean = false,
    /** Delete merged local branches after a successful merge. */
    val deleteMergedBranches: Boolean = false,
)

data class MergeRequest(
    val target: String,
    val branches: List<String>,
    /** Null means the default "Merge branches ... into target" subject. */
    val message: String? = null,
    val options: MergeOptions = MergeOptions(),
    /** Conflicts resolved in the preview; applied automatically when the same conflict occurs. */
    val resolutions: Resolutions = Resolutions(),
)

enum class MergeStatus {
    /** The merge commit was created. */
    COMMITTED,

    /** `--no-commit`: index holds the result and MERGE_HEAD lists every branch; commit to finish. */
    PREPARED,

    /** Every selected branch was already contained in the target. */
    NOTHING_TO_MERGE,
}

enum class StashStatus {
    NOT_USED,
    RESTORED,

    /** Restoring conflicted, or the merge is waiting for a commit: changes are still in the stash. */
    KEPT,
}

data class MergeOutcome(
    val status: MergeStatus,
    val target: String,
    /** Target commit before the merge; Undo resets to it. */
    val originalHead: String,
    val commit: String?,
    val merged: List<String>,
    val skipped: List<String>,
    /** Branches whose step needed conflict resolution in the merge dialog. */
    val conflictedBranches: List<String>,
    /** Branches whose conflicts were all settled by resolutions made in the preview. */
    val preResolvedBranches: List<String>,
    val stash: StashStatus,
    /** Deleted local branches with the commit each pointed to, so Undo can recreate them. */
    val deletedBranches: Map<String, String>,
    val notDeletedBranches: List<String>,
)

/** Files of one conflicting step, handed to the user for resolution. */
data class ConflictStep(val branch: String, val target: String, val index: Int, val total: Int, val files: List<String>)

fun interface ConflictResolver {
    /** Returns true when every conflict is resolved and staged; false cancels the whole merge. */
    fun resolve(step: ConflictStep): Boolean
}

interface MergeProgress {
    fun step(step: MergeStep) {}

    /** Throws to cancel; the merge is rolled back. */
    fun checkCanceled() {}

    /** True once the user cancelled the merge. */
    val isCanceled: Boolean get() = false

    /** Runs [block] to the end even after a cancel: rollback must not stop halfway. */
    fun <T> nonCancellable(block: () -> T): T = block()

    /** Called after git changed files on disk, so the IDE can refresh before showing them. */
    fun filesChanged() {}

    object None : MergeProgress
}
