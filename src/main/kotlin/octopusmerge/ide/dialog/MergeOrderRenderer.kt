package octopusmerge.ide.dialog

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import octopusmerge.core.BranchAnalysis
import octopusmerge.core.BranchState
import octopusmerge.ide.OctopusMergeBundle.message
import javax.swing.Icon
import javax.swing.JList

/** Texts and icons of the conflict preview. */
internal object PreviewText {
    private const val FILES_SHOWN = 3

    fun summary(result: Collection<BranchAnalysis>): String {
        val errors = result.filter { it.state == BranchState.ERROR }
        val conflicts = result.filter { it.state == BranchState.CONFLICT }
        val text = when {
            errors.isNotEmpty() -> message("preview.cannot.merge", errors.joinToString { it.branch }, errors.first().error.orEmpty())
            conflicts.isNotEmpty() -> message("preview.conflicts", conflicts.joinToString { it.branch })
            result.any { it.state == BranchState.RESOLVED } -> message("preview.all.resolved")
            else -> message("preview.clean")
        }
        val skipped = result.count { it.state == BranchState.ALREADY_MERGED }
        return if (skipped > 0) message("preview.skipped", text, skipped) else text
    }

    fun summaryIcon(result: Collection<BranchAnalysis>): Icon = when {
        result.any { it.state == BranchState.ERROR } -> AllIcons.General.Error
        result.any { it.state == BranchState.CONFLICT } -> AllIcons.General.Warning
        result.isEmpty() -> AllIcons.General.Information
        else -> AllIcons.General.InspectionsOK
    }

    fun icon(state: BranchState): Icon = when (state) {
        BranchState.CLEAN -> AllIcons.General.InspectionsOK
        BranchState.CONFLICT -> AllIcons.General.Warning
        BranchState.RESOLVED -> AllIcons.Actions.Checked
        BranchState.ALREADY_MERGED -> AllIcons.General.Information
        BranchState.ERROR -> AllIcons.General.Error
    }

    fun details(info: BranchAnalysis): String {
        val commits = message("order.commits", info.commits)
        val files = info.conflictFiles.take(FILES_SHOWN).joinToString()
        return when (info.state) {
            BranchState.CLEAN -> commits

            BranchState.CONFLICT -> {
                var text = message("order.conflicts", commits, files)
                if (info.conflictFiles.size > FILES_SHOWN) text = message("order.more.files", text, info.conflictFiles.size - FILES_SHOWN)
                if (info.resolvedCount > 0) text = message("order.resolved.count", text, info.resolvedCount, info.conflicts.size)
                message("order.double.click", text)
            }

            BranchState.RESOLVED -> message("order.resolved.in.advance", commits, files)

            BranchState.ALREADY_MERGED -> message("order.already.merged")

            BranchState.ERROR -> info.error.orEmpty()
        }
    }
}

/** A row of the merge order: position, branch and what the preview predicts for it. */
internal class MergeOrderRenderer(private val analysis: () -> Map<String, BranchAnalysis>) : ColoredListCellRenderer<String>() {
    override fun customizeCellRenderer(list: JList<out String>, value: String, index: Int, selected: Boolean, hasFocus: Boolean) {
        append("${index + 1}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(value)
        val info = analysis()[value] ?: return
        icon = PreviewText.icon(info.state)
        append("  ${PreviewText.details(info)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = info.conflictFiles.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>")
    }
}
