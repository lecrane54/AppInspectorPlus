package io.github.networkrules

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * The Scenario dropdown in the Rules tab toolbar: shows the scenario that's on, and lists every
 * scenario in scenarios.json to switch to, plus turning it off, saving, editing and copying the
 * matching Appium command.
 */
class ScenarioComboAction : ComboBoxAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        if (project == null) return
        val active = SharedRulesService.getInstance(project).activeScenario
        e.presentation.text = active ?: "No scenario"
        e.presentation.description = if (active == null) "Turn on a named set of shared rules" else "Scenario \"$active\" is on"
        e.presentation.icon = AllIcons.Actions.Lightning
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val project = dataContext.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT) ?: return group
        val service = SharedRulesService.getInstance(project)
        val scenarios = service.scenarios()
        if (scenarios.isEmpty()) {
            group.add(DumbAwareAction.create("No scenarios yet: turn rules on, then Save Enabled Rules as Scenario…") {}.also { it.templatePresentation.isEnabled = false })
        }
        for (s in scenarios) group.add(ApplyScenarioAction(s))
        group.add(Separator.getInstance())
        group.add(TurnOffScenarioAction())
        group.add(SaveScenarioAction())
        group.add(DeleteScenarioAction())
        group.add(EditScenariosFileAction())
        group.add(CopyScenarioCommandAction())
        return group
    }

    override fun shouldShowDisabledActions() = true
}

private abstract class ScenarioAction(text: String, description: String? = null, icon: javax.swing.Icon? = null) : DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun run(project: Project, block: (SharedRulesService) -> Unit) {
        try {
            block(SharedRulesService.getInstance(project))
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "Scenarios")
        }
    }
}

private class ApplyScenarioAction(private val scenario: SharedScenario) :
    DumbAwareToggleAction(scenario.name, scenario.description, null) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { SharedRulesService.getInstance(it).activeScenario == scenario.name } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        try {
            if (state) service.applyScenario(scenario.name!!) else service.turnOffScenario()
        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(project, ex.message, "Scenarios")
        }
    }
}

private class TurnOffScenarioAction : ScenarioAction("Turn Off Scenario", "Turn the scenario's rules off and drop its variable values") {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SharedRulesService.getInstance(it).activeScenario } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        run(e.project ?: return) { it.turnOffScenario() }
    }
}

private class SaveScenarioAction : ScenarioAction("Save Enabled Rules as Scenario…", "Save the shared rules that are on now as a named scenario", AllIcons.Actions.MenuSaveall) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val selectors = service.enabledSelectors()
        if (selectors.isEmpty()) {
            Messages.showInfoMessage(project, "Turn on the shared rules for the scenario in the Rules tab first.", "No Shared Rules On")
            return
        }
        val existing = service.activeScenario?.let { name -> service.scenarios().firstOrNull { it.name == name } }
        val dialog = SaveScenarioDialog(project, existing, selectors, service.scenarios().mapNotNull { it.name }.toSet())
        if (!dialog.showAndGet()) return
        run(project) { it.saveScenario(dialog.scenario()) }
    }
}

private class DeleteScenarioAction : ScenarioAction("Delete Scenario…", "Remove a scenario from scenarios.json", AllIcons.Actions.GC) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SharedRulesService.getInstance(it).scenarios().isNotEmpty() } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = SharedRulesService.getInstance(project)
        val names = service.scenarios().mapNotNull { it.name }
        val choice = Messages.showEditableChooseDialog(
            "Delete which scenario? Rules aren't deleted, only the scenario.", "Delete Scenario", Messages.getWarningIcon(),
            names.toTypedArray(), service.activeScenario ?: names.first(), null,
        ) ?: return
        if (choice in names) run(project) { it.deleteScenario(choice) }
    }
}

private class EditScenariosFileAction : ScenarioAction("Edit scenarios.json", "Open the scenarios file, e.g. to add variable values", AllIcons.Actions.Edit) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = SharedRulesService.getInstance(project).scenariosFile()
        if (file == null) {
            Messages.showInfoMessage(project, "There's no ${ScenarioLogic.FILE} yet. Use Save Enabled Rules as Scenario… to create it.", "No Scenarios File")
            return
        }
        OpenFileDescriptor(project, file).navigate(true)
    }
}

private class CopyScenarioCommandAction : ScenarioAction("Copy adb Command for Appium", "Copy the broadcast that applies this scenario in the Appium mocks", AllIcons.Actions.Copy) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SharedRulesService.getInstance(it).activeScenario } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val name = e.project?.let { SharedRulesService.getInstance(it).activeScenario } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(ScenarioLogic.adbCommand(name)))
    }
}

/** Name, description, and the rules (selectors) to save; prefilled from the rules that are on. */
private class SaveScenarioDialog(
    project: Project,
    existing: SharedScenario?,
    selectors: List<String>,
    private val taken: Set<String>,
) : DialogWrapper(project) {
    private val nameField = JBTextField(existing?.name.orEmpty(), 24)
    private val descriptionField = JBTextField(existing?.description.orEmpty(), 40)
    private val selectorsArea = JBTextArea(selectors.joinToString("\n"), 8, 50)
    private val variables = existing?.variables

    init {
        title = "Save Scenario"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).comment("Used in Appium tests as <code>--es scenario \"name\"</code>.") }
        row("Description:") { cell(descriptionField).align(AlignX.FILL) }
        row("Rules to turn on:") {}
        row {
            cell(JBScrollPane(selectorsArea)).align(Align.FILL).comment(
                "One per line: <code>team</code>, <code>team/file.json</code>, <code>[team] rule name</code>, or <code>*</code>. " +
                    "Every other shared rule is turned off. Add variable values by editing scenarios.json.",
            )
        }.resizableRow()
    }.apply { preferredSize = JBUI.size(560, 360) }

    override fun getPreferredFocusedComponent() = nameField

    override fun doValidateAll(): List<ValidationInfo> {
        val name = nameField.text.trim()
        val problems = mutableListOf<ValidationInfo>()
        when {
            name.isEmpty() -> problems += ValidationInfo("Enter a name", nameField)
            !ScenarioLogic.VALID_NAME.matches(name) -> problems += ValidationInfo("Use letters, digits, spaces, _ - .", nameField)
            name in taken -> problems += ValidationInfo("Replaces the existing scenario \"$name\"", nameField).asWarning().withOKEnabled()
        }
        if (selectors().isEmpty()) problems += ValidationInfo("List at least one rule", selectorsArea)
        return problems
    }

    private fun selectors() = selectorsArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun scenario() = SharedScenario(
        name = nameField.text.trim(),
        description = descriptionField.text.trim().ifEmpty { null },
        enable = selectors().toMutableList(),
        variables = variables,
    )
}
