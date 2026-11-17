package io.github.networkrules

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import javax.swing.AbstractButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Adds "Format JSON" (and the Reformat Code shortcut, ⌥⌘L / Ctrl+Alt+L) to response-body text areas:
 * Studio's own Body Rule dialog when "Replace entire body" is checked, and the plugin's Create Rule dialog.
 */
object JsonBodyFormatter {
    private val LOG = logger<JsonBodyFormatter>()
    private const val ATTACHED_KEY = "io.github.networkrules.jsonFormatter"
    private const val STUDIO_BODY_DIALOG = "com.android.tools.idea.appinspection.inspectors.network.view.details.BodyRuleDialog"

    private var installs = 0
    private val windowListener = AWTEventListener { event ->
        if (event.id == WindowEvent.WINDOW_OPENED) {
            runCatching { onWindowOpened(event as WindowEvent) }.onFailure { LOG.debug("JSON formatting skipped", it) }
        }
    }

    /** Watches for Studio's Body Rule dialog while [parent] lives. Safe to call once per project. */
    fun install(parent: Disposable) {
        if (installs++ == 0) Toolkit.getDefaultToolkit().addAWTEventListener(windowListener, AWTEvent.WINDOW_EVENT_MASK)
        Disposer.register(parent) {
            if (--installs == 0) Toolkit.getDefaultToolkit().removeAWTEventListener(windowListener)
        }
    }

    private fun onWindowOpened(event: WindowEvent) {
        val root = (event.window as? RootPaneContainer)?.rootPane ?: return
        val dialog = DialogWrapper.findInstance(root) ?: return
        if (dialog.javaClass.name != STUDIO_BODY_DIALOG) return
        // Public getters on Studio's dialog, reached by name so a Studio change only disables this.
        val area = dialog.javaClass.getMethod("getReplaceTextArea").invoke(dialog) as? JTextArea ?: return
        val entireBody = dialog.javaClass.getMethod("getReplaceEntireBodyCheckBox").invoke(dialog) as? AbstractButton ?: return
        val header = attach(area, formatNow = entireBody.isSelected) ?: return
        header.isVisible = entireBody.isSelected
        entireBody.addItemListener {
            header.isVisible = it.stateChange == ItemEvent.SELECTED
            header.revalidate()
        }
    }

    /**
     * Puts a "Format JSON" row above [area] (which must be in a scroll pane) and binds the reformat
     * shortcut. With [formatNow], valid JSON is pretty-printed straight away. Returns the row.
     */
    fun attach(area: JTextArea, formatNow: Boolean): JPanel? {
        if (area.getClientProperty(ATTACHED_KEY) == true) return null
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, area) as? JScrollPane ?: return null
        if ((area.parent as? JViewport)?.parent !== scroll) return null
        area.putClientProperty(ATTACHED_KEY, true)

        val status = JBLabel().apply { foreground = JBColor.RED; font = JBUI.Fonts.smallFont() }
        val format = { formatInto(area, status) }
        val shortcuts = reformatShortcuts()
        val link = ActionLink("Format JSON") { format() }.apply {
            toolTipText = "Pretty-print the JSON body (${KeymapUtil.getFirstKeyboardShortcutText(shortcuts)})"
        }
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = format()
        }.registerCustomShortcutSet(shortcuts, area)
        // Typing again clears an old "doesn't parse" message.
        area.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = clear()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = clear()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = Unit
            private fun clear() { if (status.text.isNotEmpty()) status.text = "" }
        })

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            background = UIUtil.getPanelBackground()
            add(status, BorderLayout.CENTER)
            add(link, BorderLayout.EAST)
        }
        scroll.setColumnHeaderView(header)
        if (formatNow) (JsonBodyFormat.format(area.text) as? JsonBodyFormat.Result.Formatted)?.let { setText(area, it.text) }
        return header
    }

    private fun formatInto(area: JTextArea, status: JBLabel) {
        when (val result = JsonBodyFormat.format(area.text)) {
            is JsonBodyFormat.Result.Formatted -> {
                if (result.text != area.text) setText(area, result.text)
                status.text = ""
            }
            JsonBodyFormat.Result.NotJson -> status.text = "Not JSON: the body should start with { or ["
            is JsonBodyFormat.Result.Invalid -> status.text = "Can't format: ${result.message}"
        }
        status.toolTipText = status.text.ifEmpty { null }
    }

    private fun setText(area: JTextArea, text: String) {
        area.text = text
        area.caretPosition = 0
    }

    /** The keymap's Reformat Code shortcut, or ⌥⌘L / Ctrl+Alt+L. */
    private fun reformatShortcuts() =
        ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_REFORMAT)?.shortcutSet?.takeIf { it.shortcuts.isNotEmpty() }
            ?: CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.ALT_DOWN_MASK or (if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK)))
}
