package octopusmerge.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
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
import octopusmerge.ide.OctopusMergeBundle.message

internal class OctopusMergeTask(project: Project, private val repository: GitRepository, private val request: MergeRequest) :
    Task.Backgroundable(project, message("task.merge.title", request.target), true) {

    override fun run(indicator: ProgressIndicator) {
        val merger = merger(project, repository, indicator)
        try {
            report(merger.merge(request))
        } catch (e: MergeCancelledException) {
            notify(project, message("notification.cancelled.title"), html(e.message), WARNING)
        } catch (e: MergeException) {
            notify(project, message("notification.failed.title"), html(e.message), NotificationType.ERROR)
        }
    }

    /** The user cancelled the progress; the merger has already rolled everything back. */
    override fun onCancel() {
        notify(project, message("notification.cancelled.title"), message("notification.cancelled.restored", request.target), WARNING)
    }

    private fun report(outcome: MergeOutcome) {
        if (outcome.status == MergeStatus.NOTHING_TO_MERGE) {
            val content = message("notification.nothing.content", request.target, outcome.skipped.joinToString())
            notify(project, message("notification.nothing.title"), content, NotificationType.INFORMATION)
            return
        }
        val prepared = outcome.status == MergeStatus.PREPARED
        val lines = mutableListOf(
            if (prepared) {
                message("notification.staged", outcome.merged.joinToString())
            } else {
                message("notification.committed", outcome.merged.size, outcome.target, outcome.commit!!.take(SHORT_HASH))
            },
        )
        listOf(
            "notification.conflicts.resolved" to outcome.conflictedBranches,
            "notification.resolved.in.advance" to outcome.preResolvedBranches,
            "notification.skipped" to outcome.skipped,
            "notification.deleted" to outcome.deletedBranches.keys,
            "notification.not.deleted" to outcome.notDeletedBranches,
        ).forEach { (key, branches) -> if (branches.isNotEmpty()) lines += message(key, branches.joinToString()) }
        val stashKept = outcome.stash == StashStatus.KEPT
        if (stashKept) {
            lines += message(if (prepared) "notification.stash.unstash.after.commit" else "notification.stash.kept")
        }

        val notification = notificationGroup().createNotification(
            message(if (prepared) "notification.prepared.title" else "notification.done.title"),
            lines.joinToString("<br>"),
            if (stashKept) WARNING else NotificationType.INFORMATION,
        )
        if (prepared) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(message("notification.action.commit")) {
                    val manager = ActionManager.getInstance()
                    manager.getAction(COMMIT_ACTION)?.let { manager.tryToExecute(it, null, null, ActionPlaces.NOTIFICATION, true) }
                },
            )
        }
        notification.addAction(
            NotificationAction.createSimpleExpiring(message(if (prepared) "notification.action.abort" else "notification.action.undo")) {
                UndoTask(project, repository, outcome).queue()
            },
        )
        notification.notify(project)
    }

    private class UndoTask(project: Project, private val repository: GitRepository, private val outcome: MergeOutcome) :
        Task.Backgroundable(project, message("task.undo.title"), false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                merger(project, repository, indicator).undo(outcome)
                val content = message("notification.undone.content", outcome.target, outcome.originalHead.take(SHORT_HASH))
                notify(project, message("notification.undone.title"), content, NotificationType.INFORMATION)
            } catch (e: MergeException) {
                notify(project, message("notification.undo.failed.title"), html(e.message), NotificationType.ERROR)
            }
        }
    }

    companion object {
        const val GROUP = "Octopus Merge"
        private const val SHORT_HASH = 8
        private const val COMMIT_ACTION = "CheckinProject"
        private val WARNING = NotificationType.WARNING

        private fun merger(project: Project, repository: GitRepository, indicator: ProgressIndicator) = OctopusMerger(
            IdeGitExecutor(project, repository.root),
            IdeConflictResolver(project, repository.root),
            IdeMergeProgress(project, repository, indicator),
        )

        private fun html(text: String?) = text.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")

        private fun notificationGroup() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)

        private fun notify(project: Project, title: String, content: String, type: NotificationType) {
            notificationGroup().createNotification(title, content, type).notify(project)
        }
    }
}
