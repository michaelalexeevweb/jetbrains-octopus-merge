package octopusmerge.ide.dialog

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.repo.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import octopusmerge.core.BranchAnalysis
import octopusmerge.core.BranchState
import octopusmerge.core.ConflictStrategy
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.MergeOptions
import octopusmerge.core.RepositoryNames
import octopusmerge.core.Resolutions
import octopusmerge.core.SelectionProblem
import octopusmerge.ide.AdvanceResolver
import octopusmerge.ide.IdeGitExecutor
import octopusmerge.ide.OctopusMergeBundle.message
import octopusmerge.ide.OctopusMergeScope
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

/**
 * The Octopus Merge dialog. Owns the widgets and renders the state of [MergeDialogController];
 * user actions go to the controller as intents.
 */
internal class OctopusMergeDialog(
    private val project: Project,
    repositories: List<GitRepository>,
    initialRepository: GitRepository,
    preselected: Collection<String>,
    initialOptions: MergeOptions,
) : DialogWrapper(project) {

    /** Repositories by the name shown and typed in the combo. */
    private val repositoriesByName: Map<String, GitRepository> =
        RepositoryNames.shortNames(repositories.map { it.root.path }).zip(repositories).toMap()

    private val scope = OctopusMergeScope.of(project).childScope("Octopus Merge dialog")

    private val controller = MergeDialogController(
        repositories = repositoriesByName.map { (name, repository) -> choiceOf(name, repository) },
        initialRepository = repositoriesByName.entries.first { it.value == initialRepository }.key,
        preselected = preselected,
        initialOptions = initialOptions,
        scope = scope,
    )

    private val repositoryCombo = SearchComboBox(project, message("dialog.placeholder.repository"))
    private val targetCombo = SearchComboBox(project, message("dialog.placeholder.target"))
    private val filterField = SearchTextField(false)
    private val branchList = CheckBoxList<String>()
    private val orderModel = DefaultListModel<String>()
    private val orderList = JBList(orderModel)
    private val previewLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val messageArea = JBTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val strategyCombo = ComboBox(ConflictStrategy.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let(::strategyText).orEmpty() }
        selectedItem = initialOptions.conflictStrategy
    }
    private val autoStash = JBCheckBox(message("dialog.option.auto.stash"), initialOptions.autoStash)
    private val noCommit = JBCheckBox(message("dialog.option.no.commit"), initialOptions.noCommit)
    private val noVerify = JBCheckBox(message("dialog.option.no.verify"), initialOptions.noVerify)
    private val appendLog = JBCheckBox(message("dialog.option.log"), initialOptions.appendLog)
    private val signoff = JBCheckBox(message("dialog.option.signoff"), initialOptions.signoff)
    private val gpgSign = JBCheckBox(message("dialog.option.gpg.sign"), initialOptions.gpgSign)
    private val allowUnrelated = JBCheckBox(message("dialog.option.allow.unrelated"), initialOptions.allowUnrelatedHistories)
    private val deleteMerged = JBCheckBox(message("dialog.option.delete.merged"), initialOptions.deleteMergedBranches)

    /** Set while widgets are filled from the state, so their listeners do not echo it back as user intents. */
    private var rendering = false

    /** Set once the user types their own message; until then it follows the selection. */
    private var messageEdited = false

    /** Last preview result, read by the merge order renderer. */
    private var analysis: Map<String, BranchAnalysis> = emptyMap()

    init {
        Disposer.register(disposable) { scope.cancel() }
        title = message("dialog.title")
        setOKButtonText(message("dialog.button.merge"))

        repositoryCombo.setNames(repositoriesByName.keys.toList())
        installListeners()
        render(controller.state.value)
        init()
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                // Captured here, inside the shown dialog: updates rendered with it are not deferred until the dialog closes.
                startRendering(ModalityState.current())
            }
        })
    }

    val repository: GitRepository get() = repositoriesByName.getValue(controller.repositoryName)
    val target: String get() = controller.state.value.target!!
    val branches: List<String> get() = controller.state.value.order
    val message: String? get() = messageArea.text.trim().takeIf { messageEdited && it.isNotEmpty() }
    val resolutions: Resolutions get() = controller.resolutions

    val options: MergeOptions get() = controller.options

    /** The options as the widgets show them; every change goes to the controller. */
    private fun widgetOptions() = MergeOptions(
        noVerify = noVerify.isSelected,
        noCommit = noCommit.isSelected,
        conflictStrategy = strategyCombo.item,
        allowUnrelatedHistories = allowUnrelated.isSelected,
        signoff = signoff.isSelected,
        gpgSign = gpgSign.isSelected,
        appendLog = appendLog.isSelected,
        autoStash = autoStash.isSelected,
        deleteMergedBranches = deleteMerged.isSelected,
    )

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(filterField, BorderLayout.NORTH)
            add(JBScrollPane(branchList), BorderLayout.CENTER)
        }
        val orderPanel = ToolbarDecorator.createDecorator(orderList)
            .disableAddAction()
            .setRemoveAction { controller.uncheck(orderList.selectedValuesList) }
            .setMoveUpAction { moveSelected(up = true) }
            .setMoveDownAction { moveSelected(up = false) }
            .createPanel()
        val right = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel(message("dialog.label.merge.order")), BorderLayout.NORTH)
            add(orderPanel, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.45f).apply {
            firstComponent = left
            secondComponent = right
            preferredSize = JBUI.size(760, 320)
        }

        return panel {
            if (repositoriesByName.size > 1) {
                row(message("dialog.label.repository")) { cell(repositoryCombo).align(AlignX.FILL) }
            }
            row(message("dialog.label.target")) { cell(targetCombo).align(AlignX.FILL) }
            row { cell(splitter).align(Align.FILL) }.resizableRow()
            row { cell(previewLabel).align(AlignX.FILL) }
            row(message("dialog.label.message")) { scrollCell(messageArea).align(AlignX.FILL) }
            collapsibleGroup(message("dialog.group.options")) {
                row(message("dialog.label.on.conflict")) { cell(strategyCombo) }
                for (option in optionBoxes()) {
                    row { cell(option) }
                }
            }
        }
    }

    private fun optionBoxes() = listOf(autoStash, noCommit, appendLog, signoff, gpgSign, noVerify, allowUnrelated, deleteMerged)

    override fun getPreferredFocusedComponent(): JComponent = filterField.textEditor

    override fun getDimensionServiceKey(): String = "OctopusMerge.Dialog"

    override fun doValidate(): ValidationInfo? {
        val typedTarget = targetCombo.text.trim()
        if (typedTarget.isNotEmpty() && targetCombo.resolvedName == null) {
            return ValidationInfo(message("dialog.error.no.branch", typedTarget), targetCombo)
        }
        val typedRepository = repositoryCombo.text.trim()
        if (repositoriesByName.size > 1 && typedRepository.isNotEmpty() && repositoryCombo.resolvedName == null) {
            return ValidationInfo(message("dialog.error.no.repository", typedRepository), repositoryCombo)
        }
        val problem = controller.state.value.problem ?: return null
        return ValidationInfo(problemText(problem), branchList)
    }

    private fun installListeners() {
        repositoryCombo.addActionListener { repositoryChosen() }
        repositoryCombo.addTextListener { repositoryChosen() }
        targetCombo.addActionListener { targetChosen() }
        targetCombo.addTextListener { targetChosen() }
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = renderBranches(controller.state.value)
        })
        branchList.setCheckBoxListListener { index, checked ->
            if (!rendering) branchList.getItemAt(index)?.let { controller.setChecked(it, checked) }
        }
        orderList.cellRenderer = MergeOrderRenderer { analysis }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                resolveSelectedInAdvance()
                return true
            }
        }.installOn(orderList)
        orderList.registerKeyboardAction({
            resolveSelectedInAdvance()
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        messageArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!rendering) messageEdited = messageArea.text.isNotBlank()
            }
        })
        strategyCombo.addActionListener { controller.optionsChanged(widgetOptions()) }
        for (option in optionBoxes()) option.addActionListener { controller.optionsChanged(widgetOptions()) }
    }

    private fun startRendering(modality: ModalityState) {
        val context = Dispatchers.EDT + modality.asContextElement()
        scope.launch(context) { controller.state.collect(::render) }
        scope.launch(context) { controller.preview.collect(::renderPreview) }
        controller.start()
    }

    private fun repositoryChosen() {
        if (rendering) return
        (repositoryCombo.selectedItem as? String)?.let(controller::chooseRepository)
    }

    private fun targetChosen() {
        if (rendering) return
        (targetCombo.selectedItem as? String)?.let(controller::chooseTarget)
    }

    private fun moveSelected(up: Boolean) {
        orderList.selectedValue?.let { controller.move(it, up) }
    }

    private fun resolveSelectedInAdvance() {
        val branch = orderList.selectedValue ?: return
        val info = analysis[branch]?.takeIf { it.state == BranchState.CONFLICT || it.state == BranchState.RESOLVED } ?: return
        val state = controller.state.value
        val target = state.target ?: return
        val position = state.order.indexOf(branch) + 1
        if (AdvanceResolver(project, repository, controller.resolutions).resolve(target, position, info, orderList)) {
            controller.resolutionsChanged()
        }
    }

    private fun render(state: SelectionState) {
        rendering = true
        try {
            if (repositoryCombo.resolvedName != state.repository) repositoryCombo.selectedItem = state.repository
            if (targetCombo.itemCount != state.localBranches.size ||
                (0 until targetCombo.itemCount).any { targetCombo.getItemAt(it) != state.localBranches[it] }
            ) {
                targetCombo.setNames(state.localBranches)
            }
            if (targetCombo.resolvedName != state.target) targetCombo.selectedItem = state.target
            renderBranches(state)
            renderOrder(state.order)
            if (!messageEdited) messageArea.text = state.defaultMessage
        } finally {
            rendering = false
        }
    }

    /** The branch list follows the filter; when the same branches stay listed only their check marks change. */
    private fun renderBranches(state: SelectionState) {
        val visible = controller.visibleBranches(filterField.text, SearchComboBox::fixLayout)
        val listed = (0 until branchList.itemsCount).map { branchList.getItemAt(it) }
        val wasRendering = rendering
        rendering = true
        try {
            if (visible != listed) {
                branchList.clear()
                visible.forEach { branchList.addItem(it, it, it in state.order) }
            } else {
                visible.forEach { branchList.setItemSelected(it, it in state.order) }
                branchList.repaint()
            }
        } finally {
            rendering = wasRendering
        }
    }

    private fun renderOrder(order: List<String>) {
        if (orderModel.elements().toList() == order) return
        val selectedBefore = orderList.selectedValue
        orderModel.clear()
        order.forEach(orderModel::addElement)
        if (selectedBefore != null) orderList.setSelectedValue(selectedBefore, true)
    }

    private fun renderPreview(preview: PreviewState) {
        analysis = (preview as? PreviewState.Ready)?.branches.orEmpty()
        when (preview) {
            PreviewState.Idle -> {
                previewLabel.icon = null
                previewLabel.text = ""
            }

            PreviewState.Checking -> {
                previewLabel.icon = AllIcons.Process.Step_1
                previewLabel.text = message("preview.checking")
            }

            PreviewState.Unsupported -> {
                previewLabel.icon = AllIcons.General.Information
                previewLabel.text = message("preview.unsupported")
            }

            is PreviewState.Failed -> {
                previewLabel.icon = AllIcons.General.Error
                previewLabel.text = message("preview.failed", preview.message)
            }

            is PreviewState.Ready -> {
                previewLabel.icon = PreviewText.summaryIcon(preview.branches.values)
                previewLabel.text = PreviewText.summary(preview.branches.values)
            }
        }
        orderList.repaint()
    }

    private companion object {
        fun choiceOf(name: String, repository: GitRepository) = RepositoryChoice(
            name = name,
            localBranches = repository.branches.localBranches.map { it.name },
            remoteBranches = repository.branches.remoteBranches.map { it.name },
            currentBranch = repository.currentBranchName,
            analyzer = { MergeAnalyzer(IdeGitExecutor(repository.project, repository.root, quiet = true)) },
        )

        fun strategyText(strategy: ConflictStrategy): String = when (strategy) {
            ConflictStrategy.RESOLVE_MANUALLY -> message("dialog.strategy.manual")
            ConflictStrategy.PREFER_TARGET -> message("dialog.strategy.ours")
            ConflictStrategy.PREFER_BRANCHES -> message("dialog.strategy.theirs")
        }

        fun problemText(problem: SelectionProblem): String = when (problem) {
            SelectionProblem.NO_TARGET -> message("dialog.error.no.target")
            SelectionProblem.TOO_FEW_BRANCHES -> message("dialog.error.too.few.branches")
        }
    }
}
