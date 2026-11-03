package io.github.networkrules

import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
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

/** Outside file → team folder: copy rules files from anywhere on disk into a team, then refresh. */
class ImportSharedRulesAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withExtensionFilter("json")
            .withTitle("Import Network Rules")
            .withDescription("Choose rules files in the ${SharedRulesService.ROOT_DIR} JSON format.")
        val chosen = FileChooser.chooseFiles(descriptor, project, null)
        if (chosen.isEmpty()) return

        val parsed = mutableListOf<ImportRow>()
        val errors = mutableListOf<String>()
        for (f in chosen) {
            try {
                parsed += ImportRow(sanitizeFileName(f.name), service.parseImport(f))
            } catch (ex: IllegalArgumentException) {
                errors += "${f.name}: ${ex.message}"
            }
        }
        if (parsed.isEmpty()) {
            Messages.showErrorDialog(project, errors.joinToString("\n"), "Nothing to Import")
            return
        }

        val dialog = ImportDialog(project, parsed, errors, service.allTeams())
        if (!dialog.showAndGet()) return
        val team = dialog.team()

        val collisions = try {
            parsed.flatMap { row -> service.importCollisions(team, row.fileName, row.content).map { "$team/${row.fileName}: $it" } }
        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(project, ex.message, "Can't Import")
            return
        }
        if (collisions.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                "These rules already exist and will be replaced by the imported version:\n\n" + collisions.joinToString("\n"),
                "Replace Existing Rules?",
                "Replace",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        service.importRules(parsed.map { it.fileName to it.content }, team)
    }

    /** Keeps the picked file's name, but only with characters a team file path allows. */
    private fun sanitizeFileName(name: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "-").trim('-', '.', ' ').ifEmpty { "imported" }
        return "$base.json"
    }
}

class ImportRow(val fileName: String, val content: SharedRulesFile)

private class ImportDialog(
    project: Project,
    private val rows: List<ImportRow>,
    private val errors: List<String>,
    teams: List<String>,
) : DialogWrapper(project) {
    private val teamCombo = ComboBox(teams.ifEmpty { listOf("imported") }.toTypedArray()).apply { isEditable = true }

    init {
        title = "Import Network Inspector Rules"
        setOKButtonText("Import")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("<html>These files will be copied into the team folder you choose under <b>${SharedRulesService.ROOT_DIR}/</b>:</html>") }
        for (r in rows) {
            val n = r.content.rules.orEmpty().size
            row { label("•  ${r.fileName}   ($n rule${if (n == 1) "" else "s"})") }
        }
        if (errors.isNotEmpty()) {
            row { label("<html><b>Skipped:</b><br>${errors.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }}</html>") }
        }
        row("Team:") {
            cell(teamCombo).align(AlignX.FILL)
                .comment("Pick an existing team or type a new name to create its folder. A file with the same name is merged; same-named rules are replaced.")
        }
    }.apply { preferredSize = JBUI.size(520, 0) }

    override fun doValidate(): ValidationInfo? =
        if (!SharedRulesService.VALID_TEAM.matches(team())) ValidationInfo("Use letters, digits, spaces, '_', '-' or '.'", teamCombo) else null

    fun team(): String = (teamCombo.editor.item as? String)?.trim()?.trim('/').orEmpty()
}

/** Create an empty team folder (with a starter rules.json) without having to export a rule first. */
class CreateTeamAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val existing = service.allTeams().toSet()
        val team = Messages.showInputDialog(
            project,
            "Team folder name (created under ${SharedRulesService.ROOT_DIR}/):",
            "Create Team",
            null,
            "",
            object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? {
                    val name = inputString.trim()
                    return when {
                        name.isEmpty() -> null
                        !SharedRulesService.VALID_TEAM.matches(name) -> "Use letters, digits, spaces, '_', '-' or '.'"
                        name in existing -> "Team \"$name\" already exists"
                        else -> null
                    }
                }
                override fun checkInput(inputString: String) = inputString.isNotBlank() && getErrorText(inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
            },
        )?.trim() ?: return

        val relPath = try {
            service.createTeam(team)
        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(project, ex.message, "Can't Create Team")
            return
        }
        Messages.showInfoMessage(
            project,
            "Created ${SharedRulesService.ROOT_DIR}/$relPath.\n\nExport rules to \"$relPath\" to share them with this team.",
            "Team Created",
        )
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
                "No team folders yet. Use Create Team… to make one.",
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
            Messages.showInfoMessage(project, "${SharedRulesService.ROOT_DIR}/ doesn't exist yet. Use Create Team… to create it.", "No Shared Rules Folder")
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
        if (toolWindow == null) {
            RevealFileAction.openDirectory(VfsUtil.virtualToIoFile(dir))
            return
        }
        // Android Studio's default "Android" view only lists modules, so the root-level rules folder
        // isn't in it and select() silently does nothing. Switch to a file-based view first.
        val pv = ProjectView.getInstance(project)
        toolWindow.activate {
            val selectDir = { pv.select(PsiManager.getInstance(project).findDirectory(dir), dir, true) }
            if (pv.currentViewId in FILE_VIEW_IDS) selectDir()
            else pv.changeViewCB(ProjectViewPane.ID, null).doWhenProcessed(selectDir)
        }
    }

    private companion object {
        val FILE_VIEW_IDS = setOf(ProjectViewPane.ID, "ProjectFilesPane")
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
