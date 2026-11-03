package io.github.networkrules

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * Before rules are written to a team file: lists emails and tokens typed straight into them and
 * offers to replace each with a local variable, so the value isn't committed.
 */
object SensitiveCheck {
    /**
     * Reviews [rules] (label → rule). Returns the replacements to make (literal → variable name),
     * empty when there's nothing to replace or the developer keeps the values, or null to cancel.
     */
    fun review(project: Project, service: SharedRulesService, rules: Map<String, SharedRule>): Map<String, String>? {
        val findings = SensitiveData.find(rules)
        if (findings.isEmpty()) return emptyMap()
        val rows = service.variableRows()
        val existing = rows.filter { it.effective != null }.associate { it.name to it.effective!! }
        val suggested = SensitiveData.suggestNames(findings, existing, rows.map { it.name }.toSet())
        val dialog = SensitiveDataDialog(project, findings, suggested)
        dialog.show()
        return when (dialog.exitCode) {
            DialogWrapper.OK_EXIT_CODE -> dialog.replacements()
            SensitiveDataDialog.KEEP_EXIT_CODE -> emptyMap()
            else -> null
        }
    }
}

private class SensitiveDataDialog(
    project: Project,
    findings: List<SensitiveData.Finding>,
    suggested: Map<String, String>,
) : DialogWrapper(project) {
    private class Row(val literal: String, val kind: SensitiveData.Kind, val where: List<String>, var replace: Boolean, var name: String)

    private val rows = findings.groupBy { it.literal }.map { (literal, fs) ->
        Row(literal, fs.first().kind, fs.map { "${it.rule}: ${it.where}" }.distinct(), true, suggested.getValue(literal))
    }

    private val model = object : AbstractTableModel() {
        val columns = listOf("Replace", "Value", "Looks like", "Found in", "Local variable")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int) = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0 || column == 4
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> it.replace
                1 -> it.literal
                2 -> it.kind.label
                3 -> it.where.joinToString("; ")
                else -> "\${${it.name}}"
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (column) {
                0 -> rows[row].replace = value as Boolean
                4 -> rows[row].name = (value as? String).orEmpty().trim().removePrefix("\${").removeSuffix("}").trim()
            }
            fireTableRowsUpdated(row, row)
        }
    }

    private val table = JBTable(model).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        columnModel.getColumn(0).maxWidth = JBUI.scale(70)
    }

    private val keepAction = object : AbstractAction("Keep Values") {
        override fun actionPerformed(e: ActionEvent?) = close(KEEP_EXIT_CODE)
    }

    init {
        title = "Personal Data in Shared Rules"
        setOKButtonText("Replace and Continue")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, keepAction, cancelAction)

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(
                "<html>These rules are about to be written to a team file and <b>committed</b>, and they contain values that look personal or secret.<br>" +
                    "<b>Replace</b> swaps each value for a <b>local variable</b>: the rule keeps working for you, and teammates set their own value.</html>",
            )
        }
        row { cell(JBScrollPane(table)).align(Align.FILL) }.resizableRow()
    }.apply { preferredSize = JBUI.size(820, 300) }

    override fun doValidateAll(): List<ValidationInfo> {
        table.cellEditor?.stopCellEditing()
        val chosen = rows.filter { it.replace }
        chosen.firstOrNull { !VariableLogic.VALID_NAME.matches(it.name) }?.let {
            return listOf(ValidationInfo("Variable names use letters, digits and _ only (${it.name.ifEmpty { "empty" }})", table))
        }
        val dupes = chosen.groupBy { it.name }.filter { it.value.size > 1 }.keys
        if (dupes.isNotEmpty()) return listOf(ValidationInfo("Two values can't use the same variable: ${dupes.joinToString()}", table))
        return emptyList()
    }

    fun replacements(): Map<String, String> = rows.filter { it.replace }.associate { it.literal to it.name }

    companion object {
        const val KEEP_EXIT_CODE = NEXT_USER_EXIT_CODE
    }
}
