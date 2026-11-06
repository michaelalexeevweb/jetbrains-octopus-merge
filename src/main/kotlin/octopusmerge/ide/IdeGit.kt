package octopusmerge.ide

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.merge.GitConflictResolver
import git4idea.repo.GitRepository
import octopusmerge.core.ConflictResolver
import octopusmerge.core.ConflictStep
import octopusmerge.core.GitExecutor
import octopusmerge.core.GitResult
import octopusmerge.core.MergeProgress
import java.io.File

/** Runs git through the IDE Git plugin: configured executable, auth helpers, Git console. */
internal class IdeGitExecutor(
    private val project: Project,
    private val gitRoot: VirtualFile,
    /** Silences every command, e.g. for the background preview. */
    private val quiet: Boolean = false,
) : GitExecutor {

    override val root: File = File(gitRoot.path)

    override fun run(args: List<String>, config: List<String>): GitResult {
        val name = args.first()
        val command = COMMANDS[name] ?: throw IllegalArgumentException("Unsupported git command: $name")
        val handler = GitLineHandler(project, gitRoot, command)
        if (config.isNotEmpty()) handler.addConfigParameters(config)
        handler.addParameters(args.drop(1))
        handler.setSilent(quiet || name in READ_ONLY)
        val result = Git.getInstance().runCommand(handler)
        return GitResult(result.exitCode, result.output, result.errorOutput)
    }

    companion object {
        private val COMMANDS: Map<String, GitCommand> = GitCommand::class.java.fields
            .filter { it.type == GitCommand::class.java }
            .map { it.get(null) as GitCommand }
            .associateBy { it.name() }

        /** Git subcommands the IDE Git plugin can run. */
        fun supportedCommands(): Set<String> = COMMANDS.keys

        /** Queries that would only clutter the Git console. */
        private val READ_ONLY = setOf("rev-parse", "merge-base", "status", "diff", "rev-list", "log", "config", "version", "merge-tree", "commit-tree")
    }
}

/** Resolves conflicts in the standard IDE "Conflicts" dialog. */
internal class IdeConflictResolver(
    private val project: Project,
    private val root: VirtualFile,
) : ConflictResolver {

    override fun resolve(step: ConflictStep): Boolean {
        val params = GitConflictResolver.Params(project)
            .setMergeDescription(
                "Octopus merge into <b>${step.target}</b>: merging <b>${step.branch}</b> (${step.index} of ${step.total}).<br>" +
                    "Resolve the conflicts to continue; cancelling rolls the whole merge back."
            )
            .setErrorNotificationTitle("Octopus merge: conflicts in ${step.branch}")
            .setMergeDialogCustomizer(Customizer(step))
        val resolver = object : GitConflictResolver(project, listOf(root), params) {
            // The merger rolls back on unresolved conflicts, so the stock "resolve later" hint would mislead.
            override fun notifyUnresolvedRemain() = Unit
        }
        return resolver.merge()
    }

    private class Customizer(private val step: ConflictStep) : MergeDialogCustomizer() {
        override fun getMultipleFileDialogTitle(): String = "Octopus Merge: Conflicts in ${step.branch}"
        override fun getLeftPanelTitle(file: VirtualFile): String =
            if (step.index == 1) "${step.target} (target)" else "${step.target} with ${step.index - 1} branch(es) merged"
        override fun getRightPanelTitle(file: VirtualFile, revisionNumber: VcsRevisionNumber?): String = "${step.branch} (merging)"
    }
}

/** Progress text, cancellation and keeping the IDE in sync with what git did on disk. */
internal class IdeMergeProgress(
    private val project: Project,
    private val repository: GitRepository,
    private val indicator: ProgressIndicator,
) : MergeProgress {
    override fun step(text: String) {
        indicator.text = text
    }

    override fun checkCanceled() = indicator.checkCanceled()

    override fun filesChanged() {
        VfsUtil.markDirtyAndRefresh(false, true, false, repository.root)
        repository.update()
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    }
}
