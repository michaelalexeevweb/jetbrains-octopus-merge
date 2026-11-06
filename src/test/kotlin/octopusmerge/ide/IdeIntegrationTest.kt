package octopusmerge.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import octopusmerge.core.BranchState
import octopusmerge.core.ConflictResolver
import octopusmerge.core.ConflictStrategy
import octopusmerge.core.GitExecutor
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.MergeOptions
import octopusmerge.core.MergeRequest
import octopusmerge.core.MergeStatus
import octopusmerge.core.OctopusMerger
import octopusmerge.core.TestRepo
import octopusmerge.core.output

/** Runs inside a headless IDE with the bundled Git plugin. */
class IdeIntegrationTest : BasePlatformTestCase() {

    fun `test action is registered in the git menus`() {
        val manager = ActionManager.getInstance()
        assertInstanceOf(manager.getAction(ACTION_ID), OctopusMergeAction::class.java)
        for (groupId in listOf("Git.MainMenu", "Git.Branch", "Git.Log.ContextMenu")) {
            val group = manager.getAction(groupId)
            assertNotNull("group $groupId exists", group)
            val children = (group as DefaultActionGroup).childActionsOrStubs.map { manager.getId(it) }
            assertTrue("$ACTION_ID in $groupId: $children", ACTION_ID in children)
        }
    }

    fun `test octopus merge sits right after the regular merge`() {
        val manager = ActionManager.getInstance()
        val children = (manager.getAction("Git.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { manager.getId(it) }
        assertEquals(children.indexOf("Git.Merge") + 1, children.indexOf(ACTION_ID))
    }

    fun `test notification group is registered`() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(OctopusMergeTask.GROUP))
    }

    fun `test the git plugin knows every command the merger runs`() {
        val missing = GitExecutor.COMMANDS_USED - IdeGitExecutor.supportedCommands()
        assertTrue("unsupported by the IDE: $missing", missing.isEmpty())
    }

    fun `test settings round trip`() {
        val options = MergeOptions(
            noVerify = true, noCommit = true, conflictStrategy = ConflictStrategy.PREFER_BRANCHES,
            allowUnrelatedHistories = true, signoff = true, gpgSign = true, appendLog = true,
            autoStash = false, deleteMergedBranches = true,
        )
        val before = OctopusMergeSettings.load()
        try {
            OctopusMergeSettings.save(options)
            assertEquals(options, OctopusMergeSettings.load())
        } finally {
            OctopusMergeSettings.save(before)
        }
    }

    fun `test merge and preview through the IDE git executor`() {
        TestRepo().use { repo ->
            val start = repo.head()
            val b1 = repo.branch("b1")
            val b2 = repo.branch("b2", files = mapOf("file.txt" to "from b2\n"))
            val b3 = repo.branch("b3", files = mapOf("file.txt" to "from b3\n"))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repo.dir)!!
            val git = IdeGitExecutor(project, root)

            val preview = MergeAnalyzer(IdeGitExecutor(project, root, quiet = true)).analyze("main", listOf("b1", "b2", "b3"))
            assertEquals(listOf(BranchState.CLEAN, BranchState.CLEAN, BranchState.CONFLICT), preview.map { it.state })

            val outcome = OctopusMerger(git, ConflictResolver { step ->
                step.files.forEach { repo.write(it, "resolved\n") }
                repo.git.output(listOf("add") + step.files)
                true
            }).merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(signoff = true, appendLog = true)))

            assertEquals(MergeStatus.COMMITTED, outcome.status)
            assertEquals(listOf(start, b1, b2, b3), repo.parents())
            assertEquals(listOf("b3"), outcome.conflictedBranches)
            assertTrue(repo.message().endsWith("Signed-off-by: Test User <test@example.com>"))
            assertEquals("", repo.status())
        }
    }

    private companion object {
        const val ACTION_ID = "OctopusMerge.Merge"
    }
}
