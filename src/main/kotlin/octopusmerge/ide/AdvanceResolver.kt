package octopusmerge.ide

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.Consumer
import git4idea.repo.GitRepository
import octopusmerge.core.BranchAnalysis
import octopusmerge.core.BranchState
import octopusmerge.core.FileConflict
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.Resolutions
import javax.swing.JComponent

/**
 * Resolves the predicted conflicts of one branch before the merge, in the regular IDE merge window,
 * on in-memory copies. Nothing on disk changes; the result goes to [resolutions] and is applied
 * when the real merge hits the same conflict.
 */
internal class AdvanceResolver(
    private val project: Project,
    private val repository: GitRepository,
    private val resolutions: Resolutions,
) {
    private class Versions(val conflict: FileConflict, val base: ByteArray?, val ours: ByteArray, val theirs: ByteArray)

    /** Returns true when at least one resolution was added or removed. Call on the EDT. */
    fun resolve(target: String, position: Int, info: BranchAnalysis, parent: JComponent): Boolean {
        val content = info.conflicts.filter { it.isContentConflict }
        val structural = info.conflicts.filterNot { it.isContentConflict }
        var changed = false

        if (info.state == BranchState.RESOLVED) {
            val again = Messages.showYesNoDialog(
                parent,
                "Conflicts of ${info.branch} are already resolved. Resolve them again?",
                "Octopus Merge",
                Messages.getQuestionIcon(),
            )
            if (again != Messages.YES) return false
            content.forEach(resolutions::remove)
            changed = true
        }

        val todo = content.filterNot(resolutions::has)
        if (todo.isEmpty()) {
            if (structural.isNotEmpty()) {
                Messages.showInfoMessage(parent, structuralNote(structural), "Octopus Merge")
            }
            return changed
        }

        val analyzer = MergeAnalyzer(IdeGitExecutor(project, repository.root, quiet = true))
        val versions = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<Versions>, Exception> {
                todo.map { Versions(it, it.base?.let(analyzer::blob), analyzer.blob(it.ours!!), analyzer.blob(it.theirs!!)) }
            },
            "Loading Conflicting Versions",
            true,
            project,
        )

        val skippedBinary = mutableListOf<String>()
        for (file in versions) {
            if (listOfNotNull(file.base, file.ours, file.theirs).any { bytes -> bytes.contains(0) }) {
                skippedBinary += file.conflict.path
                continue
            }
            val result = showMerge(target, position, info.branch, file) ?: break
            resolutions.put(file.conflict, result)
            changed = true
        }

        val notes = buildList {
            if (skippedBinary.isNotEmpty()) add("Binary files are resolved during the merge: ${skippedBinary.joinToString()}")
            if (structural.isNotEmpty()) add(structuralNote(structural))
        }
        if (notes.isNotEmpty()) Messages.showInfoMessage(parent, notes.joinToString("\n\n"), "Octopus Merge")
        return changed
    }

    /** Shows one file in the merge window. Returns the result bytes, or null when cancelled. */
    private fun showMerge(target: String, position: Int, branch: String, file: Versions): ByteArray? {
        val ours = String(file.ours, Charsets.UTF_8)
        val separator = if (ours.contains("\r\n")) "\r\n" else "\n"
        val texts = listOf(ours, String(file.base ?: ByteArray(0), Charsets.UTF_8), String(file.theirs, Charsets.UTF_8))
            .map { StringUtil.convertLineSeparators(it) }
        val document = EditorFactory.getInstance().createDocument(texts[0])
        val leftTitle = if (position <= 1) "$target (target)" else "$target with ${position - 1} branch(es) merged before"

        var outcome = MergeResult.CANCEL
        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project,
            FileTypeManager.getInstance().getFileTypeByFileName(file.conflict.path),
            document,
            texts,
            "Resolve in Advance: ${file.conflict.path} ($branch into $target)",
            listOf(leftTitle, "Base", "$branch (merging)"),
            Consumer { outcome = it },
        )
        DiffManager.getInstance().showMerge(project, request)
        if (outcome == MergeResult.CANCEL) return null
        return StringUtil.convertLineSeparators(document.text, separator).toByteArray(Charsets.UTF_8)
    }

    private fun structuralNote(conflicts: List<FileConflict>): String =
        "Added, deleted or renamed files are resolved during the merge: ${conflicts.joinToString { it.path }}"
}
