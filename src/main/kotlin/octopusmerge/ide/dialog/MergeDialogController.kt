package octopusmerge.ide.dialog

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import octopusmerge.core.BranchAnalysis
import octopusmerge.core.BranchSelection
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.MergeOptions
import octopusmerge.core.Resolutions
import octopusmerge.core.SelectionProblem
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A repository as the dialog offers it. */
internal class RepositoryChoice(
    val name: String,
    val localBranches: List<String>,
    val remoteBranches: List<String>,
    val currentBranch: String?,
    /** Creates an analyzer for the conflict preview; called off the EDT. */
    val analyzer: () -> MergeAnalyzer,
)

/** What the dialog shows about the chosen branches. */
internal data class SelectionState(
    val repository: String,
    val localBranches: List<String>,
    val target: String?,
    /** Checked branches in merge order. */
    val order: List<String>,
    val defaultMessage: String,
    val problem: SelectionProblem?,
)

internal sealed interface PreviewState {
    data object Idle : PreviewState

    data object Checking : PreviewState

    data object Unsupported : PreviewState

    data class Failed(val message: String) : PreviewState

    data class Ready(val branches: Map<String, BranchAnalysis>) : PreviewState
}

/**
 * Owns the dialog's state: repository, target, checked branches in order, options that affect the preview,
 * and the conflict preview itself. Intents are called on the EDT; state comes back through [state] and [preview].
 * The preview runs in [scope], which the dialog cancels when it closes.
 */
internal class MergeDialogController(
    private val repositories: List<RepositoryChoice>,
    initialRepository: String,
    preselected: Collection<String>,
    initialOptions: MergeOptions,
    private val scope: CoroutineScope,
    private val debounce: Duration = 300.milliseconds,
) {
    /** Conflicts resolved in advance from the preview; handed to the merge. */
    val resolutions = Resolutions()

    /** Every merge option as last set in the dialog; the merge runs with these. */
    var options: MergeOptions = initialOptions
        private set

    private var repository: RepositoryChoice = choice(initialRepository)
    private var selection: BranchSelection = select(repository, preselected)
    private var resolutionsVersion = 0
    private val previewSupported = ConcurrentHashMap<String, Boolean>()

    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<SelectionState> = mutableState.asStateFlow()

    private val mutablePreview = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val preview: StateFlow<PreviewState> = mutablePreview.asStateFlow()

    private val previewRequests = MutableStateFlow(previewRequest())

    val repositoryName: String get() = repository.name

    /** Starts the preview. Call once the dialog is ready to render it. */
    fun start() {
        scope.launch { previewRequests.collectLatest(::runPreview) }
    }

    fun visibleBranches(filter: String, layout: (Char) -> Char?): List<String> = selection.visibleBranches(filter, layout)

    fun chooseRepository(name: String) {
        if (name == repository.name) return
        val previous = selection.selected
        repository = choice(name)
        selection = select(repository, previous)
        changed()
    }

    fun chooseTarget(name: String) {
        if (name == selection.target || name !in selection.localBranches) return
        selection.target = name
        changed()
    }

    fun setChecked(branch: String, checked: Boolean) {
        selection.setSelected(branch, checked)
        changed()
    }

    fun uncheck(branches: Collection<String>) {
        branches.forEach { selection.setSelected(it, false) }
        changed()
    }

    fun move(branch: String, up: Boolean) {
        if (up) selection.moveUp(branch) else selection.moveDown(branch)
        changed()
    }

    fun optionsChanged(newOptions: MergeOptions) {
        options = newOptions
        // Only the options the preview depends on restart it; previewRequest() leaves the rest out.
        previewRequests.value = previewRequest()
    }

    /** Call after [resolutions] changed, so the preview counts them. */
    fun resolutionsChanged() {
        resolutionsVersion++
        changed()
    }

    private fun changed() {
        mutableState.value = snapshot()
        previewRequests.value = previewRequest()
    }

    private fun snapshot() = SelectionState(
        repository = repository.name,
        localBranches = selection.localBranches,
        target = selection.target,
        order = selection.selected,
        defaultMessage = selection.defaultMessage(),
        problem = selection.validate(),
    )

    private fun previewRequest() = PreviewRequest(repository, selection.target, selection.selected, previewOptions(), resolutionsVersion)

    /** The options that change predicted conflicts. */
    private fun previewOptions() =
        MergeOptions(conflictStrategy = options.conflictStrategy, allowUnrelatedHistories = options.allowUnrelatedHistories)

    private suspend fun runPreview(request: PreviewRequest) {
        val target = request.target
        if (target == null || request.branches.isEmpty()) {
            mutablePreview.value = PreviewState.Idle
            return
        }
        mutablePreview.value = PreviewState.Checking
        delay(debounce)
        mutablePreview.value = runInterruptible(Dispatchers.IO) {
            val analyzer = request.repository.analyzer()
            if (!previewSupported.getOrPut(request.repository.name, analyzer::isSupported)) {
                PreviewState.Unsupported
            } else {
                try {
                    PreviewState.Ready(analyzer.analyze(target, request.branches, request.options, resolutions).associateBy { it.branch })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    // A newer request cancelled this one: runInterruptible interrupts git and turns this into cancellation.
                    throw e
                } catch (e: Exception) {
                    LOG.info("Conflict preview failed", e)
                    PreviewState.Failed(e.message.orEmpty())
                }
            }
        }
    }

    private fun choice(name: String): RepositoryChoice = repositories.first { it.name == name }

    private fun select(choice: RepositoryChoice, checked: Collection<String>) =
        BranchSelection(choice.localBranches, choice.remoteBranches, choice.currentBranch).apply { selectAll(checked) }

    /** Everything the preview depends on; a new value restarts it. */
    private data class PreviewRequest(
        val repository: RepositoryChoice,
        val target: String?,
        val branches: List<String>,
        val options: MergeOptions,
        val resolutionsVersion: Int,
    )
}

private val LOG = logger<MergeDialogController>()
