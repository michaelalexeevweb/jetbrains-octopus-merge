package octopusmerge.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
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

    /** What the rollback after a cancel from the progress could not restore; shown by [onCancel]. */
    @Volatile
    private var rollbackProblem: String? = null

    override fun run(indicator: ProgressIndicator) {
        LOG.info("Octopus merge of ${request.branches.size} branches into ${request.target}: ${request.options}")
        val merger = merger(project, repository, indicator)
        try {
            val outcome = merger.merge(request)
            LOG.info("Octopus merge into ${request.target}: ${outcome.status}, skipped ${outcome.skipped.size}, stash ${outcome.stash}")
            report(outcome)
        } catch (e: MergeCancelledException) {
            LOG.info("Octopus merge into ${request.target} cancelled: ${e.message}")
            if (indicator.isCanceled) {
                rollbackProblem = CoreTexts.error(e)
            } else {
                notify(project, message("notification.cancelled.title"), html(CoreTexts.error(e)), WARNING)
            }
        } catch (e: MergeException) {
            LOG.warn("Octopus merge into ${request.target} failed", e)
            val title = message("notification.failed.title")
            notifyFailure(project, title, e, message("report.failed.title", CoreTexts.problem(e.problem).lineSequence().first()))
        }
    }

    /** The user cancelled the progress; the merger has already rolled back what it could. */
    override fun onCancel() {
        LOG.info("Octopus merge into ${request.target} cancelled from the progress")
        val content = rollbackProblem?.let(::html) ?: message("notification.cancelled.restored", html(request.target))
        notify(project, message("notification.cancelled.title"), content, WARNING)
    }

    private fun report(outcome: MergeOutcome) {
        if (outcome.status == MergeStatus.NOTHING_TO_MERGE) {
            val content = message("notification.nothing.content", html(request.target), names(outcome.skipped))
            notify(project, message("notification.nothing.title"), content, NotificationType.INFORMATION)
            return
        }
        val prepared = outcome.status == MergeStatus.PREPARED
        val lines = mutableListOf(
            if (prepared) {
                message("notification.staged", names(outcome.merged))
            } else {
                message("notification.committed", outcome.merged.size, html(outcome.target), outcome.commit!!.take(SHORT_HASH))
            },
        )
        listOf(
            "notification.conflicts.resolved" to outcome.conflictedBranches,
            "notification.resolved.in.advance" to outcome.preResolvedBranches,
            "notification.skipped" to outcome.skipped,
            "notification.deleted" to outcome.deletedBranches.keys,
            "notification.not.deleted" to outcome.notDeletedBranches,
        ).forEach { (key, branches) -> if (branches.isNotEmpty()) lines += message(key, names(branches)) }
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
                val content = message("notification.undone.content", html(outcome.target), outcome.originalHead.take(SHORT_HASH))
                notify(project, message("notification.undone.title"), content, NotificationType.INFORMATION)
            } catch (e: MergeException) {
                LOG.warn("Undo of octopus merge into ${outcome.target} failed", e)
                val title = message("notification.undo.failed.title")
                notifyFailure(project, title, e, message("report.undo.failed.title", CoreTexts.problem(e.problem).lineSequence().first()))
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

        /** Notifications render HTML; branch names and git output may contain `<`, `>` and `&`. */
        private fun html(text: String?) = StringUtil.escapeXmlEntities(text.orEmpty()).replace("\n", "<br>")

        private fun names(branches: Collection<String>) = html(branches.joinToString())

        private fun notificationGroup() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)

        private val LOG = logger<OctopusMergeTask>()

        private fun notify(project: Project, title: String, content: String, type: NotificationType) {
            notificationGroup().createNotification(title, content, type).notify(project)
        }

        /** An error notification with a link that opens a prefilled GitHub issue. */
        private fun notifyFailure(project: Project, title: String, error: MergeException, issueTitle: String) {
            val text = CoreTexts.error(error)
            notificationGroup().createNotification(title, html(text), NotificationType.ERROR)
                .addAction(
                    NotificationAction.createSimple(message("notification.action.report")) {
                        IssueReport.open(issueTitle, text, error.stackTraceToString())
                    },
                )
                .notify(project)
        }
    }
}
