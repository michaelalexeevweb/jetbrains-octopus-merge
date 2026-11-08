package octopusmerge.core

/*
 * What the merge has to tell the user, as data. The core has no user-visible strings: the IDE turns
 * these into texts from its message bundle, so every one of them can be translated. Git's own output
 * (`gitOutput`) is passed through as git printed it.
 */

/** A git operation that blocks the merge while it is in progress. */
enum class GitOperation { MERGE, REBASE, CHERRY_PICK, REVERT, BISECT }

/** Why a merge or an undo stopped. */
sealed interface MergeProblem {
    data class OperationInProgress(val operation: GitOperation) : MergeProblem

    data class NotLocalBranch(val branch: String) : MergeProblem

    data object NoBranches : MergeProblem

    data class UnknownRevision(val revision: String) : MergeProblem

    /** Tracked changes and auto-stash is off. */
    data object UncommittedChanges : MergeProblem

    /** `--signoff` needs user.name and user.email. */
    data object MissingIdentity : MergeProblem

    /** A one-by-one merge step failed without leaving conflicts. */
    data class StepFailed(val branch: String, val gitOutput: String) : MergeProblem

    data class ConflictsNotResolved(val branch: String) : MergeProblem

    /** The user cancelled the progress. */
    data object Cancelled : MergeProblem

    data class HookFailed(val hook: String, val gitOutput: String) : MergeProblem

    data class GitFailed(val command: List<String>, val exitCode: Int, val gitOutput: String) : MergeProblem

    data class GitTimedOut(val command: List<String>, val minutes: Long) : MergeProblem

    /** The IDE is configured with a git executable it cannot run directly (e.g. WSL). */
    data class ExecutableNotLocal(val path: String) : MergeProblem

    data class UndoNeedsCheckout(val target: String) : MergeProblem

    data class TargetMoved(val target: String) : MergeProblem

    /** Tracked changes block an undo. */
    data object UndoUncommittedChanges : MergeProblem

    data object PreparedMergeGone : MergeProblem
}

/** Something the rollback after a failure could not put back. */
sealed interface RollbackNote {
    data class ResetFailed(val target: String, val commit: String, val cause: String?) : RollbackNote

    data class SwitchBackFailed(val ref: String, val cause: String?) : RollbackNote

    data object StashKept : RollbackNote
}

/** What the merge is doing, for the progress text. */
sealed interface MergeStep {
    data object Stashing : MergeStep

    data class CheckingOut(val target: String) : MergeStep

    data class MergingAll(val branches: List<String>) : MergeStep

    data class MergingOne(val branch: String, val index: Int, val total: Int) : MergeStep

    data object CreatingCommit : MergeStep

    data object RestoringStash : MergeStep
}

/** Why the preview cannot tell what merging a branch would do. */
sealed interface AnalysisError {
    data class UnknownTarget(val target: String) : AnalysisError

    data object UnknownRevision : AnalysisError

    data class MergeTreeFailed(val gitOutput: String) : AnalysisError
}
