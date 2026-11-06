package octopusmerge.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import octopusmerge.core.MergeCancelledException
import octopusmerge.core.MergeException
import octopusmerge.core.MergeOutcome
import octopusmerge.core.MergeRequest
import octopusmerge.core.MergeStatus
import octopusmerge.core.OctopusMerger
import octopusmerge.core.StashStatus

internal class OctopusMergeTask(
    project: Project,
    private val repository: GitRepository,
    private val request: MergeRequest,
) : Task.Backgroundable(project, "Octopus merge into ${request.target}", true) {

    override fun run(indicator: ProgressIndicator) {
        val merger = merger(project, repository, indicator)
        try {
            report(merger.merge(request))
        } catch (e: ProcessCanceledException) {
            notify("Octopus merge cancelled", "${request.target} is back where it was.", NotificationType.WARNING)
        } catch (e: MergeCancelledException) {
            notify("Octopus merge cancelled", html(e.message), NotificationType.WARNING)
        } catch (e: MergeException) {
            notify("Octopus merge failed", html(e.message), NotificationType.ERROR)
        }
    }

    private fun report(outcome: MergeOutcome) {
        val lines = mutableListOf<String>()
        when (outcome.status) {
            MergeStatus.NOTHING_TO_MERGE -> {
                notify("Nothing to merge", "${request.target} already contains ${outcome.skipped.joinToString()}.", NotificationType.INFORMATION)
                return
            }
            MergeStatus.COMMITTED -> lines += "${outcome.merged.size} branches merged into <b>${outcome.target}</b> as ${outcome.commit!!.take(8)}"
            MergeStatus.PREPARED -> lines += "Merge of ${outcome.merged.joinToString()} is staged. Commit to finish it."
        }
        if (outcome.conflictedBranches.isNotEmpty()) lines += "Conflicts resolved in ${outcome.conflictedBranches.joinToString()}"
        if (outcome.skipped.isNotEmpty()) lines += "Already merged, skipped: ${outcome.skipped.joinToString()}"
        if (outcome.deletedBranches.isNotEmpty()) lines += "Deleted: ${outcome.deletedBranches.keys.joinToString()}"
        if (outcome.notDeletedBranches.isNotEmpty()) lines += "Not deleted: ${outcome.notDeletedBranches.joinToString()}"
        val type = if (outcome.stash == StashStatus.KEPT) {
            lines += if (outcome.status == MergeStatus.PREPARED) "Your local changes are in the stash: unstash them after committing."
            else "Your local changes could not be restored cleanly and are kept in the stash."
            NotificationType.WARNING
        } else NotificationType.INFORMATION

        val title = if (outcome.status == MergeStatus.COMMITTED) "Octopus merge done" else "Octopus merge prepared"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, lines.joinToString("<br>"), type)
        if (outcome.status == MergeStatus.PREPARED) {
            notification.addAction(NotificationAction.createSimpleExpiring("Commit…") {
                ActionManager.getInstance().getAction("CheckinProject")?.let {
                    ActionManager.getInstance().tryToExecute(it, null, null, ActionPlaces.NOTIFICATION, true)
                }
            })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(if (outcome.status == MergeStatus.PREPARED) "Abort" else "Undo") {
            UndoTask(project, repository, outcome).queue()
        })
        notification.notify(project)
    }

    private fun notify(title: String, content: String, type: NotificationType) = notify(project, title, content, type)

    private class UndoTask(project: Project, private val repository: GitRepository, private val outcome: MergeOutcome) :
        Task.Backgroundable(project, "Undoing octopus merge", false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                merger(project, repository, indicator).undo(outcome)
                notify(project, "Octopus merge undone", "${outcome.target} is back at ${outcome.originalHead.take(8)}.", NotificationType.INFORMATION)
            } catch (e: MergeException) {
                notify(project, "Cannot undo octopus merge", html(e.message), NotificationType.ERROR)
            }
        }
    }

    companion object {
        const val GROUP = "Octopus Merge"

        private fun merger(project: Project, repository: GitRepository, indicator: ProgressIndicator) = OctopusMerger(
            IdeGitExecutor(project, repository.root),
            IdeConflictResolver(project, repository.root),
            IdeMergeProgress(project, repository, indicator),
        )

        private fun html(text: String?) = text.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")

        private fun notify(project: Project, title: String, content: String, type: NotificationType) {
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(title, content, type).notify(project)
        }
    }
}
