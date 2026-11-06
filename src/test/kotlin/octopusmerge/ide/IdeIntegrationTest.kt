package octopusmerge.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.Dispatchers
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
import octopusmerge.ide.dialog.SearchComboBox
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs inside a headless IDE with the bundled Git plugin. */
@TestApplication
class IdeIntegrationTest {

    private val project get() = projectFixture.get()

    @Test
    fun `action is registered in the git menus`() {
        val manager = ActionManager.getInstance()
        assertIs<OctopusMergeAction>(manager.getAction(ACTION_ID))
        for (groupId in GROUPS) {
            val group = assertIs<DefaultActionGroup>(manager.getAction(groupId), "group $groupId exists")
            val children = group.childActionsOrStubs.map { manager.getId(it) }
            assertTrue(ACTION_ID in children, "$ACTION_ID in $groupId: $children")
        }
    }

    @Test
    fun `octopus merge sits right after the regular merge`() {
        val manager = ActionManager.getInstance()
        val children = (manager.getAction("Git.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { manager.getId(it) }
        assertEquals(children.indexOf("Git.Merge") + 1, children.indexOf(ACTION_ID))
    }

    @Test
    fun `action text comes from the bundle`() {
        val presentation = ActionManager.getInstance().getAction(ACTION_ID).templatePresentation
        assertEquals(OctopusMergeBundle.message("action.OctopusMerge.Merge.text"), presentation.text)
        assertEquals(OctopusMergeBundle.message("action.OctopusMerge.Merge.description"), presentation.description)
    }

    @Test
    fun `icon is bundled`() {
        assertNotNull(OctopusMergeAction::class.java.getResource("/icons/octopusMerge.svg"))
        assertNotNull(OctopusMergeAction::class.java.getResource("/icons/octopusMerge_dark.svg"))
        assertNotNull(ActionManager.getInstance().getAction(ACTION_ID).templatePresentation.icon)
    }

    @Test
    fun `errors in the plugin can be reported to GitHub`() {
        val reporters = ExtensionPointName.create<ErrorReportSubmitter>("com.intellij.errorHandler").extensionList
        assertTrue(reporters.any { it is OctopusMergeErrorReporter }, "registered: $reporters")
    }

    @Test
    fun `issue link carries the environment and fits a long stack trace`() {
        val trace = (1..2000).joinToString("\n") { "    at octopusmerge.Frame$it.call(Frame.kt:$it)" }
        val url = IssueReport.url("Error: boom & bust", "It broke", "java.lang.IllegalStateException: boom\n$trace")
        assertTrue(url.length <= 7000, "url length ${url.length}")
        assertTrue(
            url.startsWith(
                "https://github.com/michaelalexeevweb/jetbrains-octopus-merge/issues/new?title=Error%3A%20boom%20%26%20bust&body=",
            ),
        )
        val body = URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8)
        assertTrue("It broke" in body)
        assertTrue(Regex("- Octopus Merge \\d+\\.\\d+\\.\\d+").containsMatchIn(body), body)
        assertTrue("java.lang.IllegalStateException: boom" in body)
        assertTrue(body.trimEnd().endsWith("…\n```"), body.takeLast(80))
    }

    @Test
    fun `notification group is registered`() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(OctopusMergeTask.GROUP))
    }

    @Test
    fun `the git plugin knows every command the merger runs`() {
        val missing = GitExecutor.COMMANDS_USED - IdeGitExecutor.supportedCommands()
        assertTrue(missing.isEmpty(), "unsupported by the IDE: $missing")
    }

    @Test
    fun `completion matches any part of a name, any case, any layout`() {
        val matcher = SearchComboBox.NamePrefixMatcher("FEAT login")
        assertTrue(matcher.prefixMatches("feature/login"))
        assertFalse(matcher.prefixMatches("feature/signup"))
        assertTrue(SearchComboBox.NamePrefixMatcher("ьфшт").prefixMatches("main"))
        assertTrue(matcher.cloneWithPrefix("ьфшт").prefixMatches("origin/main"))
    }

    @Test
    fun `combo resolves typed text and lists every name`() = timeoutRunBlocking(context = Dispatchers.EDT) {
        val combo = SearchComboBox(project, "placeholder")
        combo.setNames(listOf("main", "develop"))
        assertEquals(2, combo.itemCount)
        combo.selectedItem = "develop"
        assertEquals("develop", combo.selectedItem)
        assertEquals("develop", combo.text)
    }

    @Test
    fun `resolutions made in the preview are used through the IDE executor`() {
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

            val merger = OctopusMerger(
                IdeGitExecutor(project, root),
                ConflictResolver { fail("dialog must not open") },
            )
            val outcome = merger.merge(MergeRequest("main", order, resolutions = resolutions))

            assertEquals(listOf("b3"), outcome.preResolvedBranches)
            assertEquals("both\n", repo.read("file.txt"))
        }
    }

    @Test
    fun `settings round trip`() {
        val options = MergeOptions(
            noVerify = true,
            noCommit = true,
            conflictStrategy = ConflictStrategy.PREFER_BRANCHES,
            allowUnrelatedHistories = true,
            signoff = true,
            gpgSign = true,
            appendLog = true,
            autoStash = false,
            deleteMergedBranches = true,
        )
        val before = OctopusMergeSettings.load()
        try {
            OctopusMergeSettings.save(options)
            assertEquals(options, OctopusMergeSettings.load())
        } finally {
            OctopusMergeSettings.save(before)
        }
    }

    @Test
    fun `merge and preview through the IDE git executor`() {
        TestRepo().use { repo ->
            val start = repo.head()
            val b1 = repo.branch("b1")
            val b2 = repo.branch("b2", files = mapOf("file.txt" to "from b2\n"))
            val b3 = repo.branch("b3", files = mapOf("file.txt" to "from b3\n"))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repo.dir)!!

            val preview = MergeAnalyzer(IdeGitExecutor(project, root, quiet = true)).analyze("main", listOf("b1", "b2", "b3"))
            assertEquals(listOf(BranchState.CLEAN, BranchState.CLEAN, BranchState.CONFLICT), preview.map { it.state })

            val merger = OctopusMerger(
                IdeGitExecutor(project, root),
                ConflictResolver { step ->
                    step.files.forEach { repo.write(it, "resolved\n") }
                    repo.git.output(listOf("add") + step.files)
                    true
                },
            )
            val request = MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(signoff = true, appendLog = true))
            val outcome = merger.merge(request)

            assertEquals(MergeStatus.COMMITTED, outcome.status)
            assertEquals(listOf(start, b1, b2, b3), repo.parents())
            assertEquals(listOf("b3"), outcome.conflictedBranches)
            assertTrue(repo.message().endsWith("Signed-off-by: Test User <test@example.com>"))
            assertEquals("", repo.status())
        }
    }

    private companion object {
        const val ACTION_ID = "OctopusMerge.Merge"
        val GROUPS = listOf(
            "Git.MainMenu",
            "Git.Branch",
            "Git.Log.ContextMenu",
            "MainToolbarRight",
            "Git.Log.Branches.Toolbar.Actions",
            "Git.Branches.List",
        )
        val projectFixture = projectFixture()
    }
}
