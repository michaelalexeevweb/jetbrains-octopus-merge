package octopusmerge.ide.dialog

import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import octopusmerge.core.BranchState
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.MergeOptions
import octopusmerge.core.SelectionProblem
import octopusmerge.core.TestRepo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class MergeDialogControllerTest {

    private val repo = TestRepo()

    @AfterEach
    fun tearDown() = repo.close()

    private fun choice(name: String, local: List<String>, current: String? = "main") =
        RepositoryChoice(name, local, emptyList(), current) { MergeAnalyzer(repo.git) }

    private fun CoroutineScope.controller(vararg choices: RepositoryChoice, preselected: List<String> = emptyList()) =
        MergeDialogController(choices.toList(), choices.first().name, preselected, MergeOptions(), this, debounce = Duration.ZERO)

    @Test
    fun `intents change the selection state`() = timeoutRunBlocking {
        val controller = controller(choice("app", listOf("main", "a", "b", "c")), preselected = listOf("a"))
        assertEquals(listOf("a"), controller.state.value.order)
        assertEquals(SelectionProblem.TOO_FEW_BRANCHES, controller.state.value.problem)

        controller.setChecked("b", true)
        controller.setChecked("c", true)
        controller.move("c", up = true)
        assertEquals(listOf("a", "c", "b"), controller.state.value.order)
        assertEquals(null, controller.state.value.problem)

        controller.chooseTarget("a")
        assertEquals("a", controller.state.value.target)
        assertEquals(listOf("c", "b"), controller.state.value.order, "the new target leaves the merge order")
        assertEquals("Merge branches 'c' and 'b' into a", controller.state.value.defaultMessage)

        controller.uncheck(listOf("c"))
        assertEquals(listOf("b"), controller.state.value.order)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `switching repository keeps checked branches that exist there`() = timeoutRunBlocking {
        val controller = controller(
            choice("app", listOf("main", "a", "b")),
            choice("lib", listOf("develop", "b"), current = "develop"),
            preselected = listOf("a", "b"),
        )
        controller.chooseRepository("lib")

        assertEquals("lib", controller.repositoryName)
        assertEquals("develop", controller.state.value.target)
        assertEquals(listOf("b"), controller.state.value.order)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `preview predicts conflicts and counts resolutions`() = timeoutRunBlocking {
        repo.branch("b1")
        repo.branch("b2", files = mapOf("file.txt" to "from b2\n"))
        repo.branch("b3", files = mapOf("file.txt" to "from b3\n"))
        val controller = controller(choice("app", listOf("main", "b1", "b2", "b3")), preselected = listOf("b1", "b2", "b3"))
        controller.start()

        val ready = controller.preview.first { it is PreviewState.Ready }
        assertIs<PreviewState.Ready>(ready)
        assertEquals(BranchState.CONFLICT, ready.branches.getValue("b3").state)

        controller.resolutions.put(ready.branches.getValue("b3").conflicts.single(), "both\n".toByteArray())
        controller.resolutionsChanged()
        val resolved = controller.preview.first { it is PreviewState.Ready && it.branches.getValue("b3").state == BranchState.RESOLVED }
        assertIs<PreviewState.Ready>(resolved)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `nothing to preview without branches`() = timeoutRunBlocking {
        val controller = controller(choice("app", listOf("main", "a")))
        controller.start()
        assertEquals(PreviewState.Idle, controller.preview.first())
        coroutineContext.cancelChildren()
    }
}
