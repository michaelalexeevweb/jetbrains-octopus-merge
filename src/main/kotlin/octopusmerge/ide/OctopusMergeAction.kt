package octopusmerge.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import octopusmerge.core.MergeRequest

internal class OctopusMergeAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = GitRepositoryManager.getInstance(project)
        val repositories = manager.repositories.sortedBy { it.root.path }
        if (repositories.isEmpty()) return

        val (preselectedRepository, preselectedBranches) = preselection(e, manager)
        val initialRepository = preselectedRepository
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(manager::getRepositoryForFileQuick)
            ?: repositories.first()

        val dialog = OctopusMergeDialog(project, repositories, initialRepository, preselectedBranches, OctopusMergeSettings.load())
        if (!dialog.showAndGet()) return

        val options = dialog.options
        OctopusMergeSettings.save(options)
        FileDocumentManager.getInstance().saveAllDocuments()
        val request = MergeRequest(dialog.target, dialog.branches, dialog.message, options, dialog.resolutions)
        OctopusMergeTask(project, dialog.repository, request).queue()
    }

    /** Branches selected in the Git tool window: in the branches tree, or as commits in the log. */
    private fun preselection(e: AnActionEvent, manager: GitRepositoryManager): Pair<GitRepository?, List<String>> {
        e.getData(BRANCHES_TREE_SELECTION)?.let { selection ->
            val tree = readBranchesTreeSelection(selection)
            if (tree != null && tree.second.isNotEmpty()) return tree
        }

        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits.orEmpty()
        if (commits.isNotEmpty()) {
            val repository = commits.map { it.root }.distinct().singleOrNull()?.let(manager::getRepositoryForRootQuick)
            if (repository != null) {
                val branches = commits.mapNotNull { commit ->
                    val collection = repository.branches
                    (collection.findLocalBranchesByHash(commit.hash).firstOrNull()
                        ?: collection.findRemoteBranchesByHash(commit.hash).firstOrNull())?.name
                }
                return repository to branches
            }
        }
        return null to emptyList()
    }

    /**
     * The branches tree selection type is Kotlin-internal in the Git plugin, so it is read
     * through its public JVM getters. If a future IDE changes it, the dialog just opens unchecked.
     */
    private fun readBranchesTreeSelection(selection: Any): Pair<GitRepository?, List<String>>? = runCatching {
        val branches = (selection.javaClass.getMethod("getSelectedBranches").invoke(selection) as List<*>)
            .mapNotNull { it?.javaClass?.getMethod("getBranchName")?.invoke(it) as? String }
        val repositories = selection.javaClass.getMethod("getRepositoriesOfSelectedBranches").invoke(selection) as Set<*>
        (repositories.singleOrNull() as? GitRepository) to branches
    }.getOrNull()

    private companion object {
        /** Same key as the Git plugin's GIT_BRANCHES_TREE_SELECTION: data keys are interned by name. */
        val BRANCHES_TREE_SELECTION: DataKey<Any> = DataKey.create("GitBranchesTreeSelection")
    }
}
