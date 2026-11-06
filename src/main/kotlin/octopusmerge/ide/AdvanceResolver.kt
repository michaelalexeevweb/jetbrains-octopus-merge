package octopusmerge.ide

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
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
import octopusmerge.core.MergeException
import octopusmerge.core.Resolutions
import octopusmerge.ide.OctopusMergeBundle.message
import javax.swing.JComponent

/**
 * Resolves the predicted conflicts of one branch before the merge, in the regular IDE merge window,
 * on in-memory copies. Nothing on disk changes; the result goes to [resolutions] and is applied
 * when the real merge hits the same conflict.
 */
internal class AdvanceResolver(private val project: Project, private val repository: GitRepository, private val resolutions: Resolutions) {
    private class Versions(val conflict: FileConflict, val base: ByteArray?, val ours: ByteArray, val theirs: ByteArray)

    /** Returns true when at least one resolution was added or removed. Call on the EDT. */
    fun resolve(target: String, position: Int, info: BranchAnalysis, parent: JComponent): Boolean {
        val content = info.conflicts.filter { it.isContentConflict }
        val structural = info.conflicts.filterNot { it.isContentConflict }
        var changed = false

        if (info.state == BranchState.RESOLVED) {
            val again = Messages.showYesNoDialog(
                parent,
                message("advance.resolve.again", info.branch),
                message("advance.title"),
                Messages.getQuestionIcon(),
            )
            if (again != Messages.YES) return false
            content.forEach(resolutions::remove)
            changed = true
        }

        val todo = content.filterNot(resolutions::has)
        if (todo.isEmpty()) {
            if (structural.isNotEmpty()) {
                Messages.showInfoMessage(parent, structuralNote(structural), message("advance.title"))
            }
            return changed
        }

        val analyzer = MergeAnalyzer(IdeGitExecutor(project, repository.root, quiet = true))
        val versions = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<Versions>, Exception> {
                    todo.map { Versions(it, it.base?.let(analyzer::blob), analyzer.blob(it.ours!!), analyzer.blob(it.theirs!!)) }
                },
                message("advance.loading"),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            // The user cancelled loading: nothing to resolve.
            return changed
        } catch (e: MergeException) {
            Messages.showErrorDialog(parent, message("advance.loading.failed", e.message.orEmpty()), message("advance.title"))
            return changed
        }

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
            if (skippedBinary.isNotEmpty()) add(message("advance.binary.skipped", skippedBinary.joinToString()))
            if (structural.isNotEmpty()) add(structuralNote(structural))
        }
        if (notes.isNotEmpty()) Messages.showInfoMessage(parent, notes.joinToString("\n\n"), message("advance.title"))
        return changed
    }

    /** Shows one file in the merge window. Returns the result bytes, or null when cancelled. */
    private fun showMerge(target: String, position: Int, branch: String, file: Versions): ByteArray? {
        val ours = String(file.ours, Charsets.UTF_8)
        val separator = if (ours.contains("\r\n")) "\r\n" else "\n"
        val texts = listOf(ours, String(file.base ?: ByteArray(0), Charsets.UTF_8), String(file.theirs, Charsets.UTF_8))
            .map { StringUtil.convertLineSeparators(it) }
        val document = EditorFactory.getInstance().createDocument(texts[0])
        val leftTitle = if (position <=
            1
        ) {
            message("advance.left.target", target)
        } else {
            message("advance.left.merged.before", target, position - 1)
        }

        var outcome = MergeResult.CANCEL
        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project,
            FileTypeManager.getInstance().getFileTypeByFileName(file.conflict.path),
            document,
            texts,
            message("advance.window.title", file.conflict.path, branch, target),
            listOf(leftTitle, message("advance.base"), message("advance.right", branch)),
            Consumer { outcome = it },
        )
        DiffManager.getInstance().showMerge(project, request)
        if (outcome == MergeResult.CANCEL) return null
        return StringUtil.convertLineSeparators(document.text, separator).toByteArray(Charsets.UTF_8)
    }

    private fun structuralNote(conflicts: List<FileConflict>): String = message("advance.structural", conflicts.joinToString { it.path })
}
