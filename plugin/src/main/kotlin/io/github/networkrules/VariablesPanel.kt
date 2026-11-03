package io.github.networkrules

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Every rule variable in one table: shared ones (committed in team files), local ones (only on this
 * machine, possibly declared in a team file) and missing ones (used or declared, but no value here).
 * "Your value" is editable and never committed. Shown as a "Variables" tab next to Network
 * Inspector's Rules tab, or in a dialog.
 */
class VariablesPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val service = SharedRulesService.getInstance(project)
    private var rows: List<VariableLogic.Row> = emptyList()

    private val model = object : AbstractTableModel() {
        val columns = listOf("Name", "Where", "Shared value (committed)", "Your value (only on this machine)", "Description", "Used by")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun isCellEditable(row: Int, column: Int) =
            column == YOUR_VALUE || (column == DESCRIPTION && rows[row].let { it.files.isNotEmpty() || it.declaredIn.isNotEmpty() })

        override fun getValueAt(row: Int, column: Int): Any? {
            val r = rows[row]
            return when (column) {
                0 -> r.name
                1 -> where(r)
                2 -> r.sharedValue.orEmpty()
                YOUR_VALUE -> r.yourValue.orEmpty()
                DESCRIPTION -> r.description.orEmpty()
                else -> if (r.usedBy == 1) "1 rule" else "${r.usedBy} rules"
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val r = rows.getOrNull(row) ?: return
            val text = (value as? String).orEmpty()
            // Applying re-sends rules and reloads this table, so let the edit finish first.
            when (column) {
                YOUR_VALUE -> if (text != r.yourValue.orEmpty()) SwingUtilities.invokeLater { service.setVariableValue(r.name, text) }
                DESCRIPTION -> if (text.trim() != r.description.orEmpty()) SwingUtilities.invokeLater { service.setVariableDescription(r.name, text.trim()) }
            }
        }
    }

    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        putClientProperty("terminateEditOnFocusLost", true)
        emptyText.text = "No variables yet"
        emptyText.appendSecondaryText("Add one with +, then use it in a rule as \${NAME}", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        setDefaultRenderer(Any::class.java, RowRenderer())
    }

    init {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { add() }
            .setAddActionName("Add Variable")
            .setRemoveAction { remove() }
            .setRemoveActionName("Remove Variable")
            .setRemoveActionUpdater { selected()?.let { it.scope != VariableLogic.Scope.MISSING || it.declaredIn.isNotEmpty() } == true }
            .disableUpDownActions()
            .addExtraAction(ShareAction())
            .addExtraAction(MakeLocalAction())
            .addExtraAction(CopyAdbArgsAction())
            .createPanel()
        add(decorated, BorderLayout.CENTER)
        add(
            JBLabel(
                "<html>Use a variable in a rule as <code>\${NAME}</code>. <b>Local</b> variables and <b>your values</b> stay on this machine and are never committed; " +
                    "only <b>shared</b> values are saved in team files. Put emails, accounts and tokens in local variables.</html>",
            ).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(6, 8)
            },
            BorderLayout.SOUTH,
        )
        reload()
    }

    fun reload() {
        if (table.isEditing) return
        val selectedName = selected()?.name
        rows = service.variableRows()
        model.fireTableDataChanged()
        rows.indexOfFirst { it.name == selectedName }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun selected(): VariableLogic.Row? = table.selectedRow.takeIf { it >= 0 }?.let { rows.getOrNull(table.convertRowIndexToModel(it)) }

    private fun where(r: VariableLogic.Row): String = when (r.scope) {
        VariableLogic.Scope.SHARED -> "Shared · " + r.files.first() + if (r.files.size > 1) " (+${r.files.size - 1})" else ""
        VariableLogic.Scope.LOCAL -> if (r.declaredIn.isEmpty()) "Local" else "Local · declared in ${r.declaredIn.first()}"
        VariableLogic.Scope.MISSING -> "Missing: set your value"
    }

    private fun add() {
        val dialog = AddVariableDialog(project, service, rows.map { it.name }.toSet())
        if (!dialog.showAndGet()) return
        runAndReport {
            when (val scope = dialog.scope()) {
                AddVariableDialog.Choice.LOCAL -> service.addLocalVariable(dialog.name(), dialog.value())
                AddVariableDialog.Choice.DECLARED -> service.addLocalVariable(dialog.name(), dialog.value(), dialog.target(), dialog.description())
                AddVariableDialog.Choice.SHARED -> service.addSharedVariable(dialog.name(), dialog.value(), dialog.target()!!, dialog.description())
                else -> error("Unknown choice $scope")
            }
        }
    }

    private fun remove() {
        val r = selected() ?: return
        val inFiles = (r.files + r.declaredIn).distinct()
        if (inFiles.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                "Remove ${r.name} from ${inFiles.joinToString()}" + (if (r.yourValue != null) " and from this machine" else "") +
                    "? Rules that use it will send \${${r.name}} as text until it's defined again.",
                "Remove Variable?", "Remove", "Cancel", Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        } else if (r.usedBy > 0) {
            val answer = Messages.showYesNoDialog(
                project, "${r.usedBy} rule(s) use ${r.name}. Remove it anyway?", "Remove Variable?",
                "Remove", "Cancel", Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        runAndReport { service.removeVariable(r.name) }
    }

    private fun runAndReport(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "Variables")
        }
        reload()
    }

    private inner class ShareAction : DumbAwareAction("Share in Team File…", "Save this variable in a team file: its value, or just a declaration so teammates set their own", AllIcons.Actions.Upload) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.scope == VariableLogic.Scope.LOCAL
        }

        override fun actionPerformed(e: AnActionEvent) {
            val r = selected() ?: return
            val dialog = ShareVariableDialog(project, service, r)
            if (dialog.showAndGet()) runAndReport { service.shareVariable(r.name, dialog.target()!!, dialog.declareOnly(), dialog.description()) }
        }
    }

    private inner class MakeLocalAction : DumbAwareAction("Make Local", "Remove the value from the team files (they keep a declaration) and keep it only on this machine", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.scope == VariableLogic.Scope.SHARED
        }

        override fun actionPerformed(e: AnActionEvent) {
            val r = selected() ?: return
            val answer = Messages.showYesNoDialog(
                project,
                "Remove ${r.name}'s value from ${r.files.joinToString()} and keep it only on this machine? " +
                    "The files keep a declaration, so teammates are asked for their own value.",
                "Make Variable Local?", "Make Local", "Cancel", Messages.getQuestionIcon(),
            )
            if (answer == Messages.YES) runAndReport { service.makeVariableLocal(r.name) }
        }
    }

    /** For Appium runs: the interceptor on the device can't see Studio's values, so pass them with adb. */
    private inner class CopyAdbArgsAction : DumbAwareAction("Copy adb Arguments for Appium", "Copy your values as --es var arguments for the Appium mocks broadcast", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = rows.any { it.yourValue != null }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val (args, skipped) = VariableLogic.adbVarArgs(rows)
            if (args == null) {
                Messages.showInfoMessage(project, "None of your values can be passed: they contain commas or quotes.", "Nothing to Copy")
                return
            }
            CopyPasteManager.getInstance().setContents(StringSelection(args))
            NotificationGroupManager.getInstance().getNotificationGroup("Network Rules Sync").createNotification(
                "Copied adb arguments",
                "Add them to the Appium mocks broadcast, e.g. <code>am broadcast -a com.example.mockrules.CONTROL -p &lt;package&gt; -f 0x20 " +
                    "${StringUtil.escapeXmlEntities(args)}</code>. They include your local values, so keep them out of committed test code." +
                    (if (skipped.isEmpty()) "" else "<br>Skipped (commas or quotes in the value): ${StringUtil.escapeXmlEntities(skipped.joinToString())}."),
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    private inner class RowRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val r = rows.getOrNull(table.convertRowIndexToModel(row)) ?: return this
            if (!isSelected) {
                foreground = when {
                    r.scope == VariableLogic.Scope.MISSING && column != DESCRIPTION -> JBColor.RED
                    column == 2 && r.yourValue != null -> NamedColorUtil.getInactiveTextColor() // your value replaces it
                    column == DESCRIPTION || (column == 5 && r.usedBy == 0) -> NamedColorUtil.getInactiveTextColor()
                    else -> table.foreground
                }
            }
            toolTipText = when {
                column == 2 && r.yourValue != null -> "Not used on this machine: your value replaces it"
                column == 1 && (r.files + r.declaredIn).isNotEmpty() -> (r.files + r.declaredIn).distinct().joinToString("<br>", "<html>", "</html>")
                column == YOUR_VALUE && r.scope == VariableLogic.Scope.SHARED -> "Leave empty to use the shared value"
                column == YOUR_VALUE && r.scope == VariableLogic.Scope.MISSING -> r.description ?: "Type a value; it stays on this machine"
                else -> null
            }
            return this
        }
    }

    companion object {
        private const val YOUR_VALUE = 3
        private const val DESCRIPTION = 4
        private const val TAB_KEY = "io.github.networkrules.variablesTab"
        const val TAB_TITLE = "Variables"

        /** Reloads every Variables panel showing for [project]. */
        fun reloadAll(project: Project) {
            for (panel in panelsOnScreen(project)) panel.reload()
        }

        private fun panelsOnScreen(project: Project): List<VariablesPanel> {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(NetworkInspectorBridge.APP_INSPECTION_TOOL_WINDOW) ?: return emptyList()
            return tw.contentManager.contents.flatMap { UIUtil.findComponentsOfType(it.component, VariablesPanel::class.java) }
        }

        /** Adds a "Variables" tab right after the Rules tab that holds [rulesTable], once per session. */
        fun ensureTab(project: Project, rulesTable: JComponent) {
            val tabs = generateSequence(rulesTable.parent) { it.parent }.filterIsInstance<JTabbedPane>().firstOrNull() ?: return
            if (tabs.getClientProperty(TAB_KEY) == true) return
            val rulesIndex = (0 until tabs.tabCount).firstOrNull { SwingUtilities.isDescendingFrom(rulesTable, tabs.getComponentAt(it)) } ?: return
            val panel = VariablesPanel(project)
            tabs.insertTab(TAB_TITLE, null, panel, "Rule variables: shared, local and missing", rulesIndex + 1)
            tabs.addChangeListener { if (tabs.selectedComponent === panel) panel.reload() }
            tabs.putClientProperty(TAB_KEY, true)
            // A new session: rules are about to be sent, so say now if some have no values.
            SharedRulesService.getInstance(project).checkMissingVariables()
        }

        /** Selects the Variables tab if one is showing; false when there's no live session. */
        fun selectTab(project: Project): Boolean {
            val panel = panelsOnScreen(project).firstOrNull() ?: return false
            val tabs = generateSequence(panel.parent) { it.parent }.filterIsInstance<JTabbedPane>().firstOrNull() ?: return false
            tabs.selectedComponent = panel
            ToolWindowManager.getInstance(project).getToolWindow(NetworkInspectorBridge.APP_INSPECTION_TOOL_WINDOW)?.show()
            return true
        }

        /** The same table in a dialog, for when no inspector session is showing. */
        fun showDialog(project: Project) {
            object : DialogWrapper(project) {
                private val content = VariablesPanel(project)

                init {
                    title = "Rule Variables"
                    setCancelButtonText("Close")
                    init()
                }

                override fun createActions() = arrayOf(cancelAction)
                override fun createCenterPanel(): JComponent = content.apply { preferredSize = JBUI.size(900, 400) }
            }.show()
        }
    }
}

/** "Add Variable": local by default; optionally declared in a team file, or shared with its value. */
private class AddVariableDialog(project: Project, service: SharedRulesService, private val taken: Set<String>) : DialogWrapper(project) {
    enum class Choice { LOCAL, DECLARED, SHARED }

    private val nameField = JBTextField(20)
    private val valueField = JBTextField(30)
    private val descriptionField = JBTextField(30)
    private val localRadio = JBRadioButton("Local: only on this machine, never committed", true)
    private val declaredRadio = JBRadioButton("Local, and declared in a team file: teammates are asked for their own value")
    private val sharedRadio = JBRadioButton("Shared: the value is saved in a team file and committed to git")
    private val picker = TeamFilePicker(service, "Pick a team")

    init {
        title = "Add Variable"
        ButtonGroup().apply { add(localRadio); add(declaredRadio); add(sharedRadio) }
        val update = {
            val inFile = !localRadio.isSelected
            picker.teamCombo.isEnabled = inFile
            picker.fileCombo.isEnabled = inFile && picker.team() != null
            descriptionField.isEnabled = inFile
        }
        listOf(localRadio, declaredRadio, sharedRadio).forEach { it.addActionListener { update() } }
        picker.addListener { update() }
        update()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).comment("Use it in a rule as <code>\${NAME}</code>. Letters, digits and _.") }
        row("Value:") { cell(valueField).align(AlignX.FILL) }
        buttonsGroup {
            row { cell(localRadio) }
            row { cell(declaredRadio) }
            row { cell(sharedRadio) }
            indent {
                row("Team:") {
                    cell(picker.teamCombo)
                    label("File:")
                    cell(picker.fileCombo)
                }
                row("Description:") { cell(descriptionField).align(AlignX.FILL).comment("Tells teammates what to put in it, e.g. \"Your QA account email\".") }
            }
        }
    }

    override fun getPreferredFocusedComponent() = nameField

    override fun doValidateAll(): List<ValidationInfo> {
        val problems = mutableListOf<ValidationInfo>()
        val name = name()
        when {
            name.isEmpty() -> problems += ValidationInfo("Enter a name", nameField)
            !VariableLogic.VALID_NAME.matches(name) -> problems += ValidationInfo("Use letters, digits and _ only", nameField)
            name in taken -> problems += ValidationInfo("$name already exists; edit it in the table", nameField)
        }
        if (!localRadio.isSelected) {
            if (picker.team() == null) problems += ValidationInfo("Pick a team", picker.teamCombo)
            picker.fileProblem()?.let { problems += ValidationInfo(it, picker.fileCombo) }
        }
        if (sharedRadio.isSelected) sensitiveWarning(value())?.let { problems += ValidationInfo(it, valueField).asWarning().withOKEnabled() }
        return problems
    }

    fun name() = nameField.text.trim()
    fun value() = valueField.text
    fun description() = descriptionField.text.trim().ifEmpty { null }
    fun scope() = when {
        sharedRadio.isSelected -> Choice.SHARED
        declaredRadio.isSelected -> Choice.DECLARED
        else -> Choice.LOCAL
    }
    fun target(): String? = if (localRadio.isSelected) null else picker.target()
}

/** "Share in Team File…": declare a local variable in a team file, or commit its value there. */
private class ShareVariableDialog(project: Project, service: SharedRulesService, private val row: VariableLogic.Row) : DialogWrapper(project) {
    private val picker = TeamFilePicker(service, "Pick a team")
    private val value = row.yourValue.orEmpty()
    private val declareRadio = JBRadioButton("Declare only: teammates are asked for their own value; yours stays on this machine", true)
    private val valueRadio = JBRadioButton("Share the value: ${StringUtil.first(value, 40, true)} is committed to git")
    private val descriptionField = JBTextField(row.description.orEmpty(), 30)

    init {
        title = "Share ${row.name}"
        setOKButtonText("Share")
        ButtonGroup().apply { add(declareRadio); add(valueRadio) }
        row.declaredIn.firstOrNull()?.let { picker.select(it.substringBefore('/'), it.substringAfter('/')) }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        buttonsGroup {
            row { cell(declareRadio) }
            row { cell(valueRadio) }
            sensitiveWarning(value)?.let { w ->
                indent { row { label("<html><b>Warning:</b> ${StringUtil.escapeXmlEntities(w)}</html>").applyToComponent { foreground = JBColor.RED } } }
            }
        }
        row("Team:") {
            cell(picker.teamCombo)
            label("File:")
            cell(picker.fileCombo)
        }
        row("Description:") { cell(descriptionField).align(AlignX.FILL).comment("Tells teammates what to put in it.") }
    }

    override fun doValidateAll(): List<ValidationInfo> = listOfNotNull(
        if (picker.team() == null) ValidationInfo("Pick a team", picker.teamCombo) else null,
        picker.fileProblem()?.let { ValidationInfo(it, picker.fileCombo) },
    )

    fun target(): String? = picker.target()
    fun declareOnly() = declareRadio.isSelected
    fun description() = descriptionField.text.trim().ifEmpty { null }
}

/** A warning when a value about to be committed looks personal or secret. */
internal fun sensitiveWarning(value: String): String? {
    val probe = SharedRule(name = "", criteria = SharedCriteria(path = value))
    val kind = SensitiveData.find("", probe).firstOrNull()?.kind
        ?: if (Regex("""(?i)^(bearer\s+)?[A-Za-z0-9_\-.]{24,}$""").matches(value.trim())) SensitiveData.Kind.TOKEN else null
    return when (kind) {
        SensitiveData.Kind.EMAIL -> "This looks like an email address. Shared values are committed to git; consider a local variable."
        null -> null
        else -> "This looks like a token or key. Shared values are committed to git; consider a local variable."
    }
}
