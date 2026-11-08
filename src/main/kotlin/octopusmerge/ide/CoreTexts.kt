package octopusmerge.ide

import octopusmerge.core.AnalysisError
import octopusmerge.core.GitOperation
import octopusmerge.core.MergeException
import octopusmerge.core.MergeProblem
import octopusmerge.core.MergeStep
import octopusmerge.core.RollbackNote
import octopusmerge.ide.OctopusMergeBundle.message

/** Texts of what the core reports as data. Exhaustive `when`s: a new case does not compile until it has a text. */
internal object CoreTexts {
    private const val SHORT_HASH = 8

    /** The problem and, one per line, what the rollback could not restore. */
    fun error(e: MergeException): String = (listOf(problem(e.problem)) + e.rollbackNotes.map(::note)).joinToString("\n")

    fun problem(problem: MergeProblem): String = when (problem) {
        is MergeProblem.OperationInProgress -> message("problem.operation.in.progress", operation(problem.operation))
        is MergeProblem.NotLocalBranch -> message("problem.not.local.branch", problem.branch)
        MergeProblem.NoBranches -> message("problem.no.branches")
        is MergeProblem.UnknownRevision -> message("problem.unknown.revision", problem.revision)
        MergeProblem.UncommittedChanges -> message("problem.uncommitted.changes")
        MergeProblem.MissingIdentity -> message("problem.missing.identity")
        is MergeProblem.StepFailed -> message("problem.step.failed", problem.branch, problem.gitOutput)
        is MergeProblem.ConflictsNotResolved -> message("problem.conflicts.not.resolved", problem.branch)
        MergeProblem.Cancelled -> message("problem.cancelled")
        is MergeProblem.HookFailed -> message("problem.hook.failed", problem.hook, problem.gitOutput)
        is MergeProblem.GitFailed -> gitFailed(problem)
        is MergeProblem.GitTimedOut -> message("problem.git.timed.out", problem.command.joinToString(" "), problem.minutes)
        is MergeProblem.ExecutableNotLocal -> message("problem.executable.not.local", problem.path)
        is MergeProblem.UndoNeedsCheckout -> message("problem.undo.needs.checkout", problem.target)
        is MergeProblem.TargetMoved -> message("problem.target.moved", problem.target)
        MergeProblem.UndoUncommittedChanges -> message("problem.undo.uncommitted.changes")
        MergeProblem.PreparedMergeGone -> message("problem.prepared.merge.gone")
    }

    /** Git's own explanation says more than an exit code. */
    private fun gitFailed(problem: MergeProblem.GitFailed): String =
        problem.gitOutput.ifEmpty { message("problem.git.failed", problem.command.joinToString(" "), problem.exitCode) }

    fun note(note: RollbackNote): String = when (note) {
        is RollbackNote.ResetFailed -> message("rollback.reset.failed", note.target, note.commit.take(SHORT_HASH), note.cause.orEmpty())
        is RollbackNote.SwitchBackFailed -> message("rollback.switch.back.failed", note.ref, note.cause.orEmpty())
        RollbackNote.StashKept -> message("rollback.stash.kept")
    }

    fun step(step: MergeStep): String = when (step) {
        MergeStep.Stashing -> message("step.stashing")
        is MergeStep.CheckingOut -> message("step.checking.out", step.target)
        is MergeStep.MergingAll -> message("step.merging.all", step.branches.joinToString())
        is MergeStep.MergingOne -> message("step.merging.one", step.branch, step.index, step.total)
        MergeStep.CreatingCommit -> message("step.creating.commit")
        MergeStep.RestoringStash -> message("step.restoring.stash")
    }

    fun analysisError(error: AnalysisError): String = when (error) {
        is AnalysisError.UnknownTarget -> message("analysis.unknown.target", error.target)
        AnalysisError.UnknownRevision -> message("analysis.unknown.revision")
        is AnalysisError.MergeTreeFailed -> error.gitOutput.ifEmpty { message("analysis.merge.tree.failed") }
    }

    private fun operation(operation: GitOperation): String = when (operation) {
        GitOperation.MERGE -> message("operation.merge")
        GitOperation.REBASE -> message("operation.rebase")
        GitOperation.CHERRY_PICK -> message("operation.cherry.pick")
        GitOperation.REVERT -> message("operation.revert")
        GitOperation.BISECT -> message("operation.bisect")
    }
}
