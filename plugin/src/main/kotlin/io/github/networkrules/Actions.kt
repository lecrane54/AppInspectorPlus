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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

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
        if (!confirmNotStale(project, service)) return
        val dialog = ExportDialog(project, service.studioRules().map { ExportRow(it.name, service.originOf(it.name)) }, service.allFilePaths())
        if (!dialog.showAndGet()) return
        val selected = dialog.selected()
        // Emails and tokens typed into the rules: offer to turn them into local variables first.
        val toWrite = service.studioRules().filter { it.name in selected }.associate { it.name to RuleMapper.toShared(it) }
        val replacements = SensitiveCheck.review(project, service, toWrite) ?: return
        service.replaceInStudioRules(selected, replacements)
        service.export(selected, dialog.target())
    }
}

/**
 * Warns when shared files changed since the last refresh (e.g. a git pull during a live session).
 * Returns true to go on with the export, after refreshing if the developer chose to.
 */
private fun confirmNotStale(project: Project, service: SharedRulesService): Boolean {
    val stale = service.staleFiles()
    if (stale.isEmpty()) return true
    val choice = Messages.showDialog(
        project,
        "These files changed on disk since your last refresh, for example by a git pull:\n\n" +
            stale.joinToString("\n") { "•  $it" } +
            "\n\nExporting now would overwrite them with your Studio version and drop any rules you haven't loaded yet.\n\n" +
            "Refresh loads the new versions first. Edits you made in Studio to rules from these files and haven't exported are replaced.",
        "Shared Rules Changed Since Last Refresh",
        arrayOf("Refresh, Then Export", "Export Anyway", "Cancel"),
        0,
        Messages.getWarningIcon(),
    )
    return when (choice) {
        0 -> { service.refresh(notifyResult = false); true }
        1 -> true
        else -> false
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
                parsed += ImportRow(RulesLogic.sanitizeFileName(f.name), service.parseImport(f))
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
            TeamNameValidator(existing),
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

/** Accepts a new, valid team folder name. */
private class TeamNameValidator(private val existing: Set<String>) : InputValidatorEx {
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
}

/** Asks which team to act on; null if there are none or the developer cancels. */
private fun chooseTeam(project: Project, service: SharedRulesService, title: String, prompt: String): String? {
    val teams = service.allTeams()
    if (teams.isEmpty()) {
        Messages.showInfoMessage(project, "No team folders yet. Use Create Team… to make one.", "No Teams")
        return null
    }
    if (teams.size == 1) return teams.single()
    val i = Messages.showChooseDialog(project, prompt, title, null, teams.toTypedArray(), teams.first())
    return teams.getOrNull(i)
}

/** Rename a team folder. Its rules keep each developer's on/off choice. */
class RenameTeamAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val old = chooseTeam(project, service, "Rename Team", "Team to rename:") ?: return
        val new = Messages.showInputDialog(
            project,
            "New name for \"$old\". Its rules show up as [new name] in the Rules tab.",
            "Rename Team",
            null,
            old,
            TeamNameValidator(service.allTeams().toSet()),
        )?.trim() ?: return
        try {
            service.renameTeam(old, new)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, ex.message ?: ex.javaClass.simpleName, "Can't Rename Team")
        }
    }
}

/** Delete a team folder and its rules, after a confirmation. */
class DeleteTeamAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val team = chooseTeam(project, service, "Delete Team", "Team to delete:") ?: return
        val (files, rules) = service.teamSummary(team)
        val answer = Messages.showYesNoDialog(
            project,
            "Delete ${SharedRulesService.ROOT_DIR}/$team/ with its $files file(s) and $rules rule(s)?\n\n" +
                "This removes the team for everyone once you commit. Until then, git or Local History can bring it back.",
            "Delete Team?",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        try {
            service.deleteTeam(team)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, ex.message ?: ex.javaClass.simpleName, "Can't Delete Team")
        }
    }
}

/** Turn every rule of a file (a scenario such as checkout/payment-declined.json) on or off at once. */
class ToggleRulesByFileAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val files = service.rulesByFile().map { (path, rules) ->
            RulesLogic.FileActivity(path, rules.count { it.isActive }, rules.size)
        }.filter { it.total > 0 }
        if (files.isEmpty()) {
            Messages.showInfoMessage(project, "No shared rules are loaded. Refresh, or use Create Team… or Import first.", "No Shared Rules")
            return
        }
        val dialog = ToggleByFileDialog(project, files)
        if (dialog.showAndGet()) {
            service.applyActivity(RulesLogic.activityPlan(files, dialog.checked(), dialog.exclusive()))
        }
    }
}

private class ToggleByFileDialog(project: Project, private val files: List<RulesLogic.FileActivity>) : DialogWrapper(project) {
    private val list = CheckBoxList<RulesLogic.FileActivity>().apply {
        for (f in files) {
            val state = when {
                f.allOn -> "all ${f.total} on"
                f.on == 0 -> "${f.total} off"
                else -> "${f.on} of ${f.total} on"
            }
            addItem(f, "${f.path}   ($state)", f.allOn)
        }
    }
    private val exclusiveBox = JBCheckBox("Turn off every other shared rule")

    init {
        title = "Turn Shared Rules On or Off"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Checked files have all their rules turned on. Unchecking a file that's all on turns it off.") }
        row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
        row { cell(exclusiveBox).comment("Use this to test one scenario at a time. Your own (non-shared) rules aren't affected.") }
        row { comment("This only changes your Rules tab. Nothing is written to the shared files.") }
    }.apply { preferredSize = JBUI.size(520, 360) }

    fun checked(): Set<String> = files.filterIndexed { i, _ -> list.isItemSelected(i) }.map { it.path }.toSet()
    fun exclusive(): Boolean = exclusiveBox.isSelected
}

/** Set personal values for shared variables, e.g. your own host or test account. Never exported. */
/**
 * Opens the Variables tab next to Network Inspector's Rules tab, or the same table in a dialog when
 * no inspector session is showing.
 */
class PersonalVariablesAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        SharedRulesService.getInstance(e.project ?: return).openVariables()
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
