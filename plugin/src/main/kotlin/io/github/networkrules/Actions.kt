package io.github.networkrules

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

abstract class ProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/** Files → Studio: reload the visible teams' rules into the Rules tab and the connected device. */
class RefreshSharedRulesAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        SharedRulesService.getInstance(e.project ?: return).refresh()
    }
}

/** Studio → files: choose which rules to share, and where new ones go. */
class ExportSharedRulesAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val rows = service.studioRules().map { rule ->
            ExportRow(rule.name, service.originOf(rule.name))
        }
        if (rows.isEmpty()) {
            Messages.showInfoMessage(project, "Create rules in Network Inspector → Rules first.", "Nothing to Export")
            return
        }
        val dialog = ExportDialog(project, rows, service.allFilePaths())
        if (dialog.showAndGet()) service.export(dialog.selected(), dialog.target())
    }
}

/** Choose which team folders this developer loads. Hidden teams' rules are removed from the Rules tab. */
class ChooseTeamsAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val teams = service.allTeams()
        if (teams.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No team folders yet. Export a rule and enter a destination like login/session-expired.json to create one.",
                "No Teams",
            )
            return
        }
        val dialog = TeamsDialog(project, teams, service.hiddenTeams)
        if (dialog.showAndGet()) service.setHiddenTeams(teams.toSet() - dialog.shown())
    }
}

class OpenSharedRulesFolderAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = SharedRulesService.getInstance(project).rootDir()
        if (dir == null) {
            Messages.showInfoMessage(project, "${SharedRulesService.ROOT_DIR}/ doesn't exist yet. Use Export to create it.", "No Shared Rules Folder")
            return
        }
        // Select it in the Project view if possible, otherwise reveal it in Finder.
        val pv = com.intellij.ide.projectView.ProjectView.getInstance(project)
        if (pv.currentProjectViewPane != null) pv.select(null, dir, true) else RevealFileAction.openDirectory(VfsUtil.virtualToIoFile(dir))
    }
}

class ExportRow(val studioName: String, val origin: String?)

private class ExportDialog(
    project: Project,
    private val rows: List<ExportRow>,
    existingFiles: List<String>,
) : DialogWrapper(project) {
    private val list = CheckBoxList<ExportRow>().apply {
        for (r in rows) {
            val where = r.origin?.let { "→ $it" } ?: "→ new (choose below)"
            // Shared rules start checked; local rules are opt-in, so scratch rules don't leak.
            addItem(r, "${r.studioName}   $where", r.origin != null)
        }
    }
    private val targetCombo = ComboBox(existingFiles.ifEmpty { listOf("login/rules.json") }.toTypedArray()).apply {
        isEditable = true
    }

    init {
        title = "Export Network Inspector Rules"
        setOKButtonText("Export")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(
                "<html>Checked rules are saved under <b>${SharedRulesService.ROOT_DIR}/</b>. Shared rules go back to their file; " +
                    "unchecking one removes it from that file.</html>"
            )
        }
        row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
        row("Save new rules to:") {
            cell(targetCombo).align(AlignX.FILL)
                .comment("team/file.json. A new team or file name creates it, e.g. <code>checkout/payment-declined.json</code>")
        }
    }.apply { preferredSize = JBUI.size(560, 400) }

    private fun selectedRows() = rows.filterIndexed { i, _ -> list.isItemSelected(i) }

    override fun doValidate(): ValidationInfo? {
        if (selectedRows().any { it.origin == null }) {
            val t = target()
            if (t == null || !SharedRulesService.VALID_TARGET.matches(t)) {
                return ValidationInfo("Enter a destination like login/rules.json", targetCombo)
            }
        }
        return null
    }

    fun selected(): Set<String> = selectedRows().map { it.studioName }.toSet()
    fun target(): String? = (targetCombo.editor.item as? String)?.trim()?.removeSuffix("/")?.ifEmpty { null }
}

private class TeamsDialog(project: Project, private val teams: List<String>, hidden: Set<String>) : DialogWrapper(project) {
    private val list = CheckBoxList<String>().apply { teams.forEach { addItem(it, it, it !in hidden) } }

    init {
        title = "Network Rules: Teams"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBLabel("Load shared rules from these team folders:")) }
        row { cell(JBScrollPane(list).apply { preferredSize = JBUI.size(320, 200) }).align(Align.FILL) }.resizableRow()
        row { comment("This choice is just for you; it isn't committed.") }
    }

    fun shown(): Set<String> = teams.filterIndexed { i, _ -> list.isItemSelected(i) }.toSet()
}
