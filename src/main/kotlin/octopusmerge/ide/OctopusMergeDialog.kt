package octopusmerge.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
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
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.repo.GitRepository
import octopusmerge.core.BranchAnalysis
import octopusmerge.core.BranchSelection
import octopusmerge.core.BranchState
import octopusmerge.core.ConflictStrategy
import octopusmerge.core.MergeAnalyzer
import octopusmerge.core.MergeOptions
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class OctopusMergeDialog(
    private val project: Project,
    private val repositories: List<GitRepository>,
    initialRepository: GitRepository,
    private val preselected: Collection<String>,
    initialOptions: MergeOptions,
) : DialogWrapper(project) {

    private lateinit var selection: BranchSelection

    private val repositoryCombo = ComboBox(repositories.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.root?.name }
        selectedItem = initialRepository
    }
    private val targetCombo = ComboBox<String>()
    private val filterField = SearchTextField(false)
    private val branchList = CheckBoxList<String>()
    private val orderModel = DefaultListModel<String>()
    private val orderList = JBList(orderModel)
    private val previewLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val messageArea = JBTextArea(3, 60).apply { lineWrap = true; wrapStyleWord = true }

    private val strategyCombo = ComboBox(ConflictStrategy.entries.toTypedArray()).apply {
        renderer = textListCellRenderer {
            when (it) {
                ConflictStrategy.RESOLVE_MANUALLY -> "Resolve in the merge dialog"
                ConflictStrategy.PREFER_TARGET -> "Keep the target version (-X ours)"
                ConflictStrategy.PREFER_BRANCHES -> "Take the branch version (-X theirs)"
                null -> ""
            }
        }
        selectedItem = initialOptions.conflictStrategy
    }
    private val autoStash = JBCheckBox("Stash local changes and restore them after the merge", initialOptions.autoStash)
    private val noCommit = JBCheckBox("Do not commit (--no-commit): review the result, then commit", initialOptions.noCommit)
    private val noVerify = JBCheckBox("Skip hooks (--no-verify)", initialOptions.noVerify)
    private val appendLog = JBCheckBox("List merged commits in the message (--log)", initialOptions.appendLog)
    private val signoff = JBCheckBox("Add Signed-off-by (--signoff)", initialOptions.signoff)
    private val gpgSign = JBCheckBox("GPG-sign the commit (-S)", initialOptions.gpgSign)
    private val allowUnrelated = JBCheckBox("Allow unrelated histories (--allow-unrelated-histories)", initialOptions.allowUnrelatedHistories)
    private val deleteMerged = JBCheckBox("Delete merged local branches", initialOptions.deleteMergedBranches)

    /** Set once the user types their own message; until then it follows the selection. */
    private var messageEdited = false
    private var updatingMessage = false

    private val previewAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    private var previewGeneration = 0
    private var analysis: Map<String, BranchAnalysis> = emptyMap()
    private var previewSupported: Boolean? = null

    init {
        title = "Octopus Merge"
        setOKButtonText("Merge")

        repositoryCombo.addActionListener { loadRepository() }
        targetCombo.addActionListener {
            val target = targetCombo.item ?: return@addActionListener
            if (target != selection.target) {
                selection.target = target
                selectionChanged()
            }
        }
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildBranchList()
        })
        branchList.setCheckBoxListListener { index, value ->
            branchList.getItemAt(index)?.let { selection.setSelected(it, value) }
            selectionChanged(rebuildBranches = false)
        }
        orderList.cellRenderer = OrderRenderer()
        messageArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updatingMessage) messageEdited = messageArea.text.isNotBlank()
            }
        })
        listOf(strategyCombo).forEach { it.addActionListener { schedulePreview() } }
        allowUnrelated.addActionListener { schedulePreview() }

        loadRepository()
        init()
    }

    val repository: GitRepository get() = repositoryCombo.item
    val target: String get() = selection.target!!
    val branches: List<String> get() = selection.selected
    val message: String? get() = messageArea.text.trim().takeIf { messageEdited && it.isNotEmpty() }

    val options: MergeOptions
        get() = MergeOptions(
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
            .setRemoveAction { orderList.selectedValuesList.forEach { selection.setSelected(it, false) }; selectionChanged() }
            .setMoveUpAction { moveSelected(up = true) }
            .setMoveDownAction { moveSelected(up = false) }
            .createPanel()
        val right = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel("Merge order"), BorderLayout.NORTH)
            add(orderPanel, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.45f).apply {
            firstComponent = left
            secondComponent = right
            preferredSize = JBUI.size(760, 320)
        }

        return panel {
            if (repositories.size > 1) {
                row("Repository:") { cell(repositoryCombo).align(AlignX.FILL) }
            }
            row("Merge into:") { cell(targetCombo).align(AlignX.FILL) }
            row { cell(splitter).align(Align.FILL) }.resizableRow()
            row { cell(previewLabel).align(AlignX.FILL) }
            row("Message:") { cell(JBScrollPane(messageArea)).align(AlignX.FILL) }
            collapsibleGroup("Options") {
                row("On conflict:") { cell(strategyCombo) }
                row { cell(autoStash) }
                row { cell(noCommit) }
                row { cell(appendLog) }
                row { cell(signoff) }
                row { cell(gpgSign) }
                row { cell(noVerify) }
                row { cell(allowUnrelated) }
                row { cell(deleteMerged) }
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = filterField.textEditor

    override fun getDimensionServiceKey(): String = "OctopusMerge.Dialog"

    override fun doValidate(): ValidationInfo? = selection.validate()?.let { ValidationInfo(it, branchList) }

    private fun loadRepository() {
        val repo = repositoryCombo.item ?: return
        val previous = if (::selection.isInitialized) selection.selected else preselected
        selection = BranchSelection(
            repo.branches.localBranches.map { it.name },
            repo.branches.remoteBranches.map { it.name },
            repo.currentBranchName,
        )
        selection.selectAll(previous)

        targetCombo.removeAllItems()
        selection.localBranches.forEach(targetCombo::addItem)
        targetCombo.selectedItem = selection.target
        previewSupported = null
        selectionChanged()
    }

    private fun moveSelected(up: Boolean) {
        val branch = orderList.selectedValue ?: return
        if (up) selection.moveUp(branch) else selection.moveDown(branch)
        selectionChanged(rebuildBranches = false)
        orderList.setSelectedValue(branch, true)
    }

    private fun selectionChanged(rebuildBranches: Boolean = true) {
        if (rebuildBranches) rebuildBranchList()
        val selectedBefore = orderList.selectedValue
        orderModel.clear()
        selection.selected.forEach(orderModel::addElement)
        if (selectedBefore != null) orderList.setSelectedValue(selectedBefore, false)

        if (!messageEdited) {
            updatingMessage = true
            messageArea.text = selection.defaultMessage()
            updatingMessage = false
        }
        schedulePreview()
    }

    private fun rebuildBranchList() {
        branchList.clear()
        selection.visibleBranches(filterField.text).forEach { branchList.addItem(it, it, selection.isSelected(it)) }
    }

    private fun schedulePreview() {
        val generation = ++previewGeneration
        val repo = repositoryCombo.item ?: return
        val target = selection.target
        val branches = selection.selected
        val options = options
        previewAlarm.cancelAllRequests()
        if (target == null || branches.isEmpty()) {
            showPreview(generation, emptyMap(), "")
            return
        }
        previewLabel.icon = AllIcons.Process.Step_1
        previewLabel.text = "Checking for conflicts…"
        previewAlarm.addRequest({
            val analyzer = MergeAnalyzer(IdeGitExecutor(project, repo.root, quiet = true))
            val supported = previewSupported ?: analyzer.isSupported().also { previewSupported = it }
            if (!supported) {
                showPreview(generation, emptyMap(), "Conflict preview needs git 2.38 or newer")
                return@addRequest
            }
            val result = runCatching { analyzer.analyze(target, branches, options) }
            val byBranch = result.getOrNull()?.associateBy { it.branch }.orEmpty()
            showPreview(generation, byBranch, result.exceptionOrNull()?.let { "Preview failed: ${it.message}" } ?: summary(byBranch.values))
        }, 300)
    }

    private fun showPreview(generation: Int, result: Map<String, BranchAnalysis>, text: String) {
        ApplicationManager.getApplication().invokeLater({
            if (generation != previewGeneration) return@invokeLater
            analysis = result
            val conflicts = result.values.any { it.state == BranchState.CONFLICT }
            val errors = result.values.any { it.state == BranchState.ERROR }
            previewLabel.icon = when {
                text.isEmpty() -> null
                errors -> AllIcons.General.Error
                conflicts -> AllIcons.General.Warning
                result.isEmpty() -> AllIcons.General.Information
                else -> AllIcons.General.InspectionsOK
            }
            previewLabel.text = text
            orderList.repaint()
        }, ModalityState.any())
    }

    private fun summary(result: Collection<BranchAnalysis>): String {
        val conflicts = result.filter { it.state == BranchState.CONFLICT }
        val errors = result.filter { it.state == BranchState.ERROR }
        val skipped = result.count { it.state == BranchState.ALREADY_MERGED }
        val skippedNote = if (skipped > 0) "; $skipped already merged, will be skipped" else ""
        return when {
            errors.isNotEmpty() -> "Cannot merge ${errors.joinToString { it.branch }}: ${errors.first().error}"
            conflicts.isNotEmpty() -> "Conflicts expected in ${conflicts.joinToString { it.branch }}: you will resolve them in the merge dialog$skippedNote"
            else -> "No conflicts expected$skippedNote"
        }
    }

    private inner class OrderRenderer : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(list: JList<out String>, value: String, index: Int, selected: Boolean, hasFocus: Boolean) {
            append("${index + 1}. ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value)
            val info = analysis[value] ?: return
            icon = when (info.state) {
                BranchState.CLEAN -> AllIcons.General.InspectionsOK
                BranchState.CONFLICT -> AllIcons.General.Warning
                BranchState.ALREADY_MERGED -> AllIcons.General.Information
                BranchState.ERROR -> AllIcons.General.Error
            }
            val details = when (info.state) {
                BranchState.CLEAN -> commits(info.commits)
                BranchState.CONFLICT -> "${commits(info.commits)}, conflicts: ${info.conflictFiles.take(3).joinToString()}" +
                    if (info.conflictFiles.size > 3) " +${info.conflictFiles.size - 3}" else ""
                BranchState.ALREADY_MERGED -> "already merged, skipped"
                BranchState.ERROR -> info.error.orEmpty()
            }
            append("  $details", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            toolTipText = if (info.conflictFiles.isNotEmpty()) info.conflictFiles.joinToString("<br>", "<html>", "</html>") else null
        }

        private fun commits(count: Int) = if (count == 1) "1 commit" else "$count commits"
    }
}
