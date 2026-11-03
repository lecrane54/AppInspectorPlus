package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.NetworkInspectorClient
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleDataListener
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleVariable
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleVariablesStateComponent
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RulesPersistentStateComponent
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RulesTableModel
import com.android.tools.idea.appinspection.inspectors.network.view.rules.RulesTableView
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.network.inspection.NetworkInspectorProtocol.InterceptCommand
import javax.swing.JComponent
import javax.swing.event.TableModelListener

/**
 * Everything that touches Android Studio's internal Network Inspector code lives here, so a Studio
 * update that breaks something only needs fixes in this file. Verified against Studio 2025.3 (build 253).
 *
 * Studio's design, which this relies on:
 *  - RulesPersistentStateComponent ("NetworkInspectorRules" in .idea/workspace.xml) holds the rule list.
 *  - When a Network Inspector session starts, RulesTableView wraps that same list in its table model,
 *    attaches a RuleDataListener to each rule, and sends every rule to the device.
 *  - RulesTableView.addRule(rule) (private) adds a row and sends InterceptRuleAdded; removing a rule
 *    sends InterceptRuleRemoved. We drive those same paths so the device stays in sync.
 */
object NetworkInspectorBridge {
    private val LOG = logger<NetworkInspectorBridge>()
    const val APP_INSPECTION_TOOL_WINDOW = "App Inspection"

    fun persistedRules(project: Project): MutableList<RuleData> {
        val state = project.getService(RulesPersistentStateComponent::class.java).state
        val list = state.rulesList
        if (list is java.util.ArrayList<*>) {
            @Suppress("UNCHECKED_CAST")
            return list as MutableList<RuleData>
        }
        return ArrayList(list).also { state.rulesList = it }
    }

    fun variables(project: Project): MutableList<RuleVariable> {
        val state = RuleVariablesStateComponent.getInstance(project).state
        val list = state.ruleVariables
        if (list is java.util.ArrayList<*>) {
            @Suppress("UNCHECKED_CAST")
            return list as MutableList<RuleVariable>
        }
        return ArrayList(list).also { state.ruleVariables = it }
    }

    /** The Rules table of a connected Network Inspector session, or null when none is running. */
    fun findLiveTable(project: Project): LiveRulesTable? {
        // 1) Every persisted rule's listener is an inner class of the RulesTableView that owns it.
        persistedRules(project).asSequence()
            .mapNotNull { it.ruleDataListener }
            .mapNotNull { ownerView(it) }
            .firstOrNull(::isAlive)
            ?.let { return LiveRulesTable(it) }
        // 2) The list is empty, so look for the table on screen and read its owner from the listeners.
        for (table in rulesTablesOnScreen(project)) {
            val candidates = (table.model as RulesTableModel).getListeners(TableModelListener::class.java).toList() +
                (table.selectionModel as? javax.swing.DefaultListSelectionModel)?.listSelectionListeners.orEmpty()
            candidates.firstNotNullOfOrNull { ownerView(it) }?.takeIf(::isAlive)?.let { return LiveRulesTable(it) }
        }
        return null
    }

    /** Studio's Rules tables currently in the App Inspection tool window's component tree. */
    fun rulesTablesOnScreen(project: Project): List<TableView<*>> {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(APP_INSPECTION_TOOL_WINDOW) ?: return emptyList()
        return tw.contentManager.contents.flatMap { content ->
            UIUtil.findComponentsOfType(content.component, TableView::class.java)
        }.filter { it.model is RulesTableModel }
    }

    private fun isAlive(view: RulesTableView): Boolean =
        runCatching { (field(view, "scope") as CoroutineScope).isActive }.getOrDefault(false)

    /** Finds a captured RulesTableView in an inner class or lambda (this$0 / arg$1 fields). */
    private fun ownerView(obj: Any): RulesTableView? = obj.javaClass.declaredFields
        .firstOrNull { RulesTableView::class.java.isAssignableFrom(it.type) }
        ?.let { f -> runCatching { f.isAccessible = true; f.get(obj) as? RulesTableView }.getOrNull() }

    internal fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    class LiveRulesTable(val view: RulesTableView) {
        val rules: List<RuleData> get() = view.tableModel.items
        private val client = field(view, "client") as NetworkInspectorClient
        private val scope = field(view, "scope") as CoroutineScope

        fun add(rule: RuleData) {
            val listener = RulesTableView::class.java.getDeclaredMethod("createNewRuleDataListener")
                .apply { isAccessible = true }.invoke(view) as RuleDataListener
            rule.ruleDataListener = listener
            RulesTableView::class.java.getDeclaredMethod("addRule", RuleData::class.java)
                .apply { isAccessible = true }.invoke(view, rule)
        }

        fun remove(rule: RuleData) {
            val index = view.tableModel.indexOf(rule)
            if (index < 0) return
            view.table.clearSelection()
            view.tableModel.removeRow(index)
            val command = InterceptCommand.newBuilder()
                .apply { interceptRuleRemovedBuilder.setRuleId(rule.id) }
                .build()
            scope.launch {
                try {
                    client.interceptResponse(command)
                } catch (e: Exception) {
                    LOG.warn("Failed to remove rule ${rule.id} on device", e)
                }
            }
        }

        val component: JComponent get() = view.component
    }
}
