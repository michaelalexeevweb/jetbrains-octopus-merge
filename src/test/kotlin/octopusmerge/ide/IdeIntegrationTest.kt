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
import octopusmerge.core.Resolutions
import octopusmerge.core.TestRepo
import octopusmerge.core.output

/** Runs inside a headless IDE with the bundled Git plugin. */
class IdeIntegrationTest : BasePlatformTestCase() {

    fun `test action is registered in the git menus`() {
        val manager = ActionManager.getInstance()
        assertInstanceOf(manager.getAction(ACTION_ID), OctopusMergeAction::class.java)
        for (groupId in listOf("Git.MainMenu", "Git.Branch", "Git.Log.ContextMenu", "MainToolbarRight", "Git.Log.Branches.Toolbar.Actions", "Git.Branches.List")) {
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

    fun `test icon is bundled`() {
        assertNotNull(OctopusMergeAction::class.java.getResource("/icons/octopusMerge.svg"))
        assertNotNull(OctopusMergeAction::class.java.getResource("/icons/octopusMerge_dark.svg"))
        assertNotNull(manager().getAction(ACTION_ID).templatePresentation.icon)
    }

    private fun manager() = ActionManager.getInstance()

    fun `test notification group is registered`() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(OctopusMergeTask.GROUP))
    }

    fun `test the git plugin knows every command the merger runs`() {
        val missing = GitExecutor.COMMANDS_USED - IdeGitExecutor.supportedCommands()
        assertTrue("unsupported by the IDE: $missing", missing.isEmpty())
    }

    fun `test resolutions made in the preview are used through the IDE executor`() {
        TestRepo().use { repo ->
            repo.branch("b1")
            repo.branch("b2", files = mapOf("file.txt" to "from b2\n"))
            repo.branch("b3", files = mapOf("file.txt" to "from b3\n"))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repo.dir)!!
            val analyzer = MergeAnalyzer(IdeGitExecutor(project, root, quiet = true))
            val resolutions = Resolutions()
            val order = listOf("b1", "b2", "b3")

            val conflict = analyzer.analyze("main", order)[2].conflicts.single()
            assertEquals("from b2\n", String(analyzer.blob(conflict.ours!!)))
            resolutions.put(conflict, "both\n".toByteArray())
            assertEquals(BranchState.RESOLVED, analyzer.analyze("main", order, resolutions = resolutions)[2].state)

            val outcome = OctopusMerger(IdeGitExecutor(project, root), ConflictResolver { fail("dialog must not open"); false })
                .merge(MergeRequest("main", order, resolutions = resolutions))

            assertEquals(listOf("b3"), outcome.preResolvedBranches)
            assertEquals("both\n", repo.read("file.txt"))
        }
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

    fun `test completion matches any part of a name, any case, any layout`() {
        val matcher = SearchComboBox.NamePrefixMatcher("FEAT login")
        assertTrue(matcher.prefixMatches("feature/login"))
        assertFalse(matcher.prefixMatches("feature/signup"))
        assertTrue(SearchComboBox.NamePrefixMatcher("ьфшт").prefixMatches("main"))
        assertTrue(matcher.cloneWithPrefix("ьфшт").prefixMatches("origin/main"))
    }

    fun `test combo resolves typed text and lists every name`() {
        val combo = SearchComboBox(project, "placeholder")
        combo.setNames(listOf("main", "develop"))
        assertEquals(2, combo.itemCount)
        combo.selectedItem = "develop"
        assertEquals("develop", combo.selectedItem)
        assertEquals("develop", combo.text)
    }

    private companion object {
        const val ACTION_ID = "OctopusMerge.Merge"
    }
}
