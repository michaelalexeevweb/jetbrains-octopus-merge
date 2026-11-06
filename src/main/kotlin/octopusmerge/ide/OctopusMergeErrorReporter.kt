package octopusmerge.ide

import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import octopusmerge.ide.OctopusMergeBundle.message
import java.awt.Component

/**
 * "Report to Octopus Merge" in the IDE error dialog: opens a GitHub issue with the stack trace.
 * The user submits it on GitHub; the plugin sends nothing by itself.
 */
internal class OctopusMergeErrorReporter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = message("report.action")

    override fun getPrivacyNoticeText(): String = message("report.privacy")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val event = events.firstOrNull() ?: return false
        val problem = additionalInfo?.takeIf { it.isNotBlank() } ?: message("report.problem.placeholder")
        val title = message("report.title", event.throwableText.lineSequence().firstOrNull().orEmpty().take(TITLE_LENGTH))
        IssueReport.open(title, problem, event.throwableText)
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    private companion object {
        const val TITLE_LENGTH = 120
    }
}
