package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.android.tools.idea.appinspection.inspectors.network.view.details.RuleDetailsView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A Team dropdown and a File dropdown (editable, for a new file name), shared by the
 * "Create Network Rule from Endpoint" dialog and the bar in Studio's rule details.
 */
class TeamFilePicker(private val service: SharedRulesService, private val localLabel: String) {
    val teamCombo = ComboBox<String>()
    val fileCombo = ComboBox<String>().apply { isEditable = true }
    private var updating = false

    init {
        reloadTeams()
        teamCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED && !updating) reloadFiles(preferred = null) }
    }

    /** Re-reads the team folders, keeping the current choice when it still exists. */
    fun reloadTeams() {
        val current = team()
        updating = true
        try {
            teamCombo.removeAllItems()
            teamCombo.addItem(localLabel)
            service.allTeams().forEach(teamCombo::addItem)
            teamCombo.selectedItem = current?.takeIf { it in service.allTeams() } ?: localLabel
        } finally {
            updating = false
        }
        reloadFiles(preferred = null)
    }

    /** Selects [team] (null = local only) and [file], or the team's usual file. */
    fun select(team: String?, file: String?) {
        updating = true
        try {
            teamCombo.selectedItem = team?.takeIf { it in service.allTeams() } ?: localLabel
        } finally {
            updating = false
        }
        reloadFiles(preferred = file)
    }

    /** Files of the chosen team; defaults to rules.json, or the team's first file. */
    private fun reloadFiles(preferred: String?) {
        val team = team()
        fileCombo.removeAllItems()
        fileCombo.isEnabled = team != null
        if (team == null) return
        val files = service.teamFiles(team)
        files.forEach(fileCombo::addItem)
        val choice = preferred ?: SharedRulesService.STARTER_FILE.takeIf { it in files } ?: files.firstOrNull() ?: SharedRulesService.STARTER_FILE
        if (choice !in files) fileCombo.addItem(choice)
        fileCombo.selectedItem = choice
    }

    fun addListener(listener: () -> Unit) {
        teamCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED && !updating) listener() }
    }

    /** The chosen team, or null for "only my Rules tab". */
    fun team(): String? = (teamCombo.selectedItem as? String)?.takeIf { it != localLabel }

    /** team/file.json, or null for "only my Rules tab". A missing .json is added. */
    fun target(): String? {
        val team = team() ?: return null
        val raw = ((fileCombo.editor.item as? String) ?: fileCombo.selectedItem as? String).orEmpty().trim()
        val file = if (raw.endsWith(".json", ignoreCase = true)) raw else "$raw.json"
        return "$team/$file"
    }

    /** A problem with the chosen file name, or null. */
    fun fileProblem(): String? {
        val target = target() ?: return null
        return if (SharedRulesService.VALID_TARGET.matches(target)) null
        else "Use a file name like rules.json (letters, digits, spaces, _ - .)"
    }
}

/**
 * Sits above Studio's rule details (as the scroll pane's column header, so it survives Studio
 * rebuilding the details for each rule) and shows which team the selected rule belongs to.
 * Picking a team and pressing Save writes the rule to that team's file and renames it `[team] name`.
 */
class RuleTeamBar private constructor(private val project: Project, private val view: RuleDetailsView) : JPanel() {
    private val service = SharedRulesService.getInstance(project)
    private val picker = TeamFilePicker(service, LOCAL_LABEL)
    private val saveButton = JButton("Save to Team")
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
    private var shownRule: RuleData? = null
    private var shownName: String? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()), JBUI.Borders.empty(6, 8))
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel("Team:"))
            add(picker.teamCombo)
            add(JBLabel("File:"))
            add(picker.fileCombo.apply { prototypeDisplayValue = "payment-declined.json" })
            add(saveButton)
        }
        status.alignmentX = LEFT_ALIGNMENT
        status.border = JBUI.Borders.emptyTop(4)
        add(row)
        add(status)
        picker.addListener(::updateState)
        saveButton.addActionListener { save() }
    }

    /** Shows [rule]'s team. Cheap when nothing changed, since the viewport calls this on every scroll. */
    fun update(force: Boolean = false) {
        // Studio's selectedRule is lateinit: it throws until a rule has been selected once.
        val rule: RuleData? = runCatching { view.selectedRule }.getOrNull()
        isVisible = rule != null
        if (rule == null) return
        if (!force && rule === shownRule) {
            // Same rule: keep the developer's picks, only the texts follow a renamed rule.
            if (rule.name != shownName) {
                shownName = rule.name
                updateState()
            }
            return
        }
        shownRule = rule
        shownName = rule.name
        picker.reloadTeams()
        val origin = service.originOf(rule.name)
        if (origin != null) {
            picker.select(origin.substringBefore('/'), origin.substringAfter('/'))
        } else {
            picker.select(RulesLogic.teamOf(rule.name)?.takeIf { it in service.allTeams() }, null)
        }
        updateState()
    }

    private fun updateState() {
        val rule = shownRule ?: return
        val origin = service.originOf(rule.name)
        val target = picker.target()
        saveButton.isEnabled = target != null
        saveButton.text = if (origin != null && origin == target) "Save Changes" else "Save to Team"
        status.text = when {
            target == null && origin != null ->
                "Shared from $origin. Pick a team to move it; your Rules tab edits are saved with Save Changes or Export."
            target == null -> "Only in your Rules tab. Pick a team and Save to share it as [team] ${RulesLogic.baseName(rule.name)}."
            origin == target -> "Shared from $origin. Save Changes writes your edits to that file."
            origin != null -> "Moves it from $origin to $target as [${picker.team()}] ${RulesLogic.baseName(rule.name)}."
            else -> "Saves it to $target as [${picker.team()}] ${RulesLogic.baseName(rule.name)}."
        }
    }

    private fun save() {
        val rule = shownRule ?: return
        val target = picker.target() ?: return
        picker.fileProblem()?.let { Messages.showErrorDialog(project, it, "Can't Save Rule"); return }
        service.checkSaveToTeam(rule, target)?.let { Messages.showErrorDialog(project, it, "Can't Save Rule"); return }
        val base = RulesLogic.baseName(rule.name).trim()
        if (service.originOf(rule.name) != target && service.fileHasRule(target, base)) {
            val answer = Messages.showYesNoDialog(
                project, "$target already has a rule named \"$base\". Replace it?", "Replace Rule?",
                "Replace", "Cancel", Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        try {
            service.saveRuleToTeam(rule, target)
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "Can't Save Rule")
        }
        SwingUtilities.invokeLater { update(force = true) }
    }

    companion object {
        const val LOCAL_LABEL = "None (only my Rules tab)"

        /** Adds the bar to [view] once; later calls just refresh it. */
        fun ensure(project: Project, view: RuleDetailsView) {
            val scrollPane = view.components.filterIsInstance<JScrollPane>().firstOrNull() ?: return
            val existing = scrollPane.columnHeader?.view as? RuleTeamBar
            if (existing != null) {
                existing.update()
                return
            }
            val bar = RuleTeamBar(project, view)
            scrollPane.setColumnHeaderView(bar)
            // Studio swaps the viewport's view each time a different rule is selected.
            scrollPane.viewport.addChangeListener { SwingUtilities.invokeLater { bar.update() } }
            bar.update(force = true)
        }
    }
}
