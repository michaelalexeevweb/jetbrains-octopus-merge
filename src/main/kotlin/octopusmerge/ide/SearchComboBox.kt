// Based on git4idea.ui.ComboBoxWithAutoCompletion from intellij-community (Apache 2.0, Copyright 2000-2025 JetBrains s.r.o.).
package octopusmerge.ide

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.codeStyle.PlatformKeyboardLayoutConverter
import com.intellij.ui.ComboBoxCompositeEditor
import com.intellij.ui.EditorTextField
import com.intellij.ui.LanguageTextField
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import octopusmerge.core.NameMatcher
import javax.swing.JLabel

/**
 * Editable combo box of names with completion while typing. Every typed word is looked up anywhere in a name,
 * ignoring case and the keyboard layout (see [NameMatcher]); the arrow lists all names.
 */
internal class SearchComboBox(private val project: Project, placeholder: String) :
    ComboBox<String>(MutableCollectionComboBoxModel<String>()) {

    private val names = model as MutableCollectionComboBoxModel<String>
    private val completionProvider = NameCompletionProvider()
    private lateinit var editorEx: EditorEx

    // Nullable: JComboBox's constructor calls getSelectedItem before this class is initialized.
    private val editableComponent: EditorTextField? = object : LanguageTextField(PlainTextLanguage.INSTANCE, project, "") {
        override fun createEditor(): EditorEx = super.createEditor().apply {
            setShowPlaceholderWhenFocused(true)
            setPlaceholder(placeholder)
            SpellCheckingEditorCustomizationProvider.getInstance().disabledCustomization?.customize(this)
            editorEx = this
        }
    }
    private var selectingItem = false

    init {
        val editableComponent = editableComponent!!
        isEditable = true
        editableComponent.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun documentChanged(e: DocumentEvent) {
                if (!selectingItem && !e.isWholeTextReplaced && text.isNotEmpty() && text !in names.items) showCompletion()
            }
        })
        editor = ComboBoxCompositeEditor<String, EditorTextField>(editableComponent, JLabel())
        TextFieldWithAutoCompletion.installCompletion(editableComponent.document, project, completionProvider, true)
        KeymapManager.getInstance().activeKeymap.getShortcuts(IdeActions.ACTION_CODE_COMPLETION).firstOrNull()?.let {
            DumbAwareAction.create { showCompletion() }.registerCustomShortcutSet(CustomShortcutSet(it), editableComponent)
        }
        // The editor is assigned after the UI delegate was installed; reinstall the UI so it picks the editor up.
        updateUI()
    }

    /** Typed text as is. */
    val text: String get() = editableComponent?.text.orEmpty()

    /** The name the typed text stands for, see [NameMatcher.resolve]. */
    val resolvedName: String? get() = NameMatcher(text, ::fixLayout).resolve(names.items)

    /** Runs [listener] whenever the typed text changes, including when an item is picked. */
    fun addTextListener(listener: () -> Unit) {
        editableComponent?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = listener()
        })
    }

    fun setNames(items: List<String>) {
        names.update(items)
        completionProvider.setItems(items)
    }

    override fun setSelectedItem(anObject: Any?) {
        selectingItem = true
        super.setSelectedItem(anObject)
        if (editableComponent != null) editor.item = anObject
        selectingItem = false
    }

    override fun getSelectedItem(): Any? =
        if (editableComponent == null || selectingItem || text.isEmpty()) super.getSelectedItem() else resolvedName

    override fun requestFocus() {
        editableComponent?.requestFocus() ?: super.requestFocus()
    }

    private fun showCompletion() {
        if (!::editorEx.isInitialized) return
        hidePopup()
        AutoPopupController.getInstance(project).scheduleAutoPopup(editorEx)
    }

    /** Completes on the whole typed text, matched by [NameMatcher] rather than by prefix. */
    private class NameCompletionProvider : TextFieldWithAutoCompletionListProvider<String>(emptyList()) {
        override fun getLookupString(item: String): String = item
        override fun getPrefix(text: String, offset: Int): String = text.substring(0, offset)
        override fun createPrefixMatcher(prefix: String): PrefixMatcher = NamePrefixMatcher(prefix)
    }

    class NamePrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
        private val matcher = NameMatcher(prefix, ::fixLayout)
        override fun prefixMatches(name: String): Boolean = matcher.matches(name)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = NamePrefixMatcher(prefix)
    }

    companion object {
        /** ASCII character on the same key, as the IDE learned it from typing (plus a built-in Russian layout). */
        fun fixLayout(c: Char): Char? = PlatformKeyboardLayoutConverter.convert(c)
    }
}
