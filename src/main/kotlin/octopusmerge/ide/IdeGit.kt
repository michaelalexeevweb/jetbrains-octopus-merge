package octopusmerge.ide

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.config.GitExecutableManager
import git4idea.merge.GitConflictResolver
import git4idea.repo.GitRepository
import octopusmerge.core.ConflictResolver
import octopusmerge.core.ConflictStep
import octopusmerge.core.GitExecutor
import octopusmerge.core.GitResult
import octopusmerge.core.MergeException
import octopusmerge.core.MergeProblem
import octopusmerge.core.MergeProgress
import octopusmerge.core.MergeStep
import octopusmerge.core.ProcessGitExecutor
import octopusmerge.ide.OctopusMergeBundle.message
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
        LOG.debug { "git ${args.joinToString(" ")} -> ${result.exitCode}" }
        return GitResult(result.exitCode, result.output, result.errorOutput)
    }

    /**
     * Plumbing the Git plugin does not wrap (`read-tree`, `write-tree`, stdin, private index): runs the
     * git executable configured in the IDE directly. Only read-only or object-writing commands go here.
     */
    override fun runRaw(args: List<String>, input: ByteArray?, env: Map<String, String>): GitResult {
        val executable = GitExecutableManager.getInstance().getExecutable(project)
        if (!executable.isLocal) throw MergeException(MergeProblem.ExecutableNotLocal(executable.exePath))
        val result = ProcessGitExecutor(root, executable.exePath, executable.getLocaleEnv()).runRaw(args, input, env)
        LOG.debug { "git ${args.joinToString(" ")} (raw) -> ${result.exitCode}" }
        return result
    }

    companion object {
        private val COMMANDS: Map<String, GitCommand> = GitCommand::class.java.fields
            .filter { it.type == GitCommand::class.java }
            .map { it.get(null) as GitCommand }
            .associateBy { it.name() }

        /** Git subcommands the IDE Git plugin can run. */
        fun supportedCommands(): Set<String> = COMMANDS.keys

        /** Queries that would only clutter the Git console. */
        private val READ_ONLY = setOf("rev-parse", "merge-base", "status", "diff", "rev-list", "log", "config", "version", "commit-tree")
    }
}

/** Resolves conflicts in the standard IDE "Conflicts" dialog. */
internal class IdeConflictResolver(private val project: Project, private val root: VirtualFile) : ConflictResolver {

    override fun resolve(step: ConflictStep): Boolean {
        val params = GitConflictResolver.Params(project)
            .setMergeDescription(message("conflicts.description", step.target, step.branch, step.index, step.total))
            .setErrorNotificationTitle(message("conflicts.error.title", step.branch))
            .setMergeDialogCustomizer(Customizer(step))
        val resolver = object : GitConflictResolver(project, listOf(root), params) {
            // The merger rolls back on unresolved conflicts, so the stock "resolve later" hint would mislead.
            override fun notifyUnresolvedRemain() = Unit
        }
        return resolver.merge()
    }

    private class Customizer(private val step: ConflictStep) : MergeDialogCustomizer() {
        override fun getMultipleFileDialogTitle(): String = message("conflicts.dialog.title", step.branch)

        override fun getLeftPanelTitle(file: VirtualFile): String = MergePanelTitles.left(step.target, step.index)

        override fun getRightPanelTitle(file: VirtualFile, revisionNumber: VcsRevisionNumber?): String = MergePanelTitles.right(step.branch)
    }
}

/** Progress text, cancellation and keeping the IDE in sync with what git did on disk. */
internal class IdeMergeProgress(
    private val project: Project,
    private val repository: GitRepository,
    private val indicator: ProgressIndicator,
) : MergeProgress {
    override fun step(step: MergeStep) {
        LOG.info("Octopus merge step: $step")
        indicator.text = CoreTexts.step(step)
    }

    override fun checkCanceled() = indicator.checkCanceled()

    override val isCanceled: Boolean get() = indicator.isCanceled

    /** Git commands of the IDE stop on a cancelled indicator; a non-cancelable section lets the rollback finish. */
    override fun <T> nonCancellable(block: () -> T): T =
        ProgressManager.getInstance().computeInNonCancelableSection<T, Exception> { block() }

    override fun filesChanged() {
        VfsUtil.markDirtyAndRefresh(false, true, false, repository.root)
        repository.update()
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    }
}

private val LOG = logger<IdeGitExecutor>()
