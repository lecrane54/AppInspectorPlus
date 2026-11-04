package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.connections.ConnectionData
import com.android.tools.idea.appinspection.inspectors.network.model.connections.HttpData
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.UIUtil
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.JTable

/**
 * "Create Network Rule from Response…": a rule pre-filled from a request captured in Network
 * Inspector's Connection View (method, URL with IDs as `*`, status code and the real response body),
 * to edit into the case you want to test.
 *
 * It's on the request's right-click menu, which Studio builds in code, so the plugin wraps Studio's
 * menu listener to add the item after Studio's own. The same action is under Tools and ⋯, for the
 * request selected in the Connection View.
 */
object TrafficRules {
    private val LOG = logger<TrafficRules>()
    private const val WRAPPED_KEY = "io.github.networkrules.connectionsMenu"
    private const val VIEW_PACKAGE = "com.android.tools.idea.appinspection.inspectors.network.view.connectionsview"
    private const val STUDIO_LISTENER_PREFIX = "$VIEW_PACKAGE.ConnectionsView"
    private const val TABLE_MODEL = "$VIEW_PACKAGE.ConnectionsTableModel"

    // Studio declares these `internal`, so they're reached by name.
    private fun connectionAt(table: JTable, viewRow: Int): ConnectionData? = runCatching {
        table.model.javaClass.getMethod("getConnectionData", Int::class.javaPrimitiveType)
            .invoke(table.model, table.convertRowIndexToModel(viewRow)) as ConnectionData
    }.onFailure { LOG.debug("Can't read the connection at row $viewRow", it) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun studioActions(connection: ConnectionData): List<AnAction> = runCatching {
        Class.forName("$VIEW_PACKAGE.ConnectionsViewKt", true, ConnectionData::class.java.classLoader)
            .getMethod("access\$getActions", ConnectionData::class.java).invoke(null, connection) as List<AnAction>
    }.onFailure { LOG.debug("Studio's connection actions unavailable", it) }.getOrDefault(emptyList())

    /** The decoded (e.g. un-gzipped) response body, when Studio has one. */
    private fun readableBody(connection: ConnectionData): String? = runCatching {
        val bytes = connection.javaClass.getMethod("getReadableResponsePayload").invoke(connection) ?: return null
        bytes.javaClass.getMethod("toStringUtf8").invoke(bytes) as String
    }.getOrNull()

    fun createFrom(project: Project, connection: ConnectionData) {
        val service = SharedRulesService.getInstance(project)
        val criteria = TrafficLogic.criteria(connection.url)
        val status = (connection as? HttpData)?.responseCode?.takeIf { it > 0 }?.toString() ?: connection.status.filter(Char::isDigit)
        val dialog = CreateRuleDialog(
            project,
            name = TrafficLogic.ruleName(connection.method, criteria.path),
            method = EndpointLogic.studioMethod(connection.method),
            criteria = criteria,
            body = responseBody(connection),
            hostSet = true,
            service = service,
            turnOn = EndpointSettings.getInstance(project).turnOnNewRules,
            dialogTitle = "Create Network Rule from Response",
            statusCode = status,
            pathComment = "IDs in the captured path are <code>*</code>, so the rule matches any ID. <code>*</code> matches anything.",
            bodyComment = "The captured response. Edit it into the case you want to test (an error, an empty list…). Empty keeps the server's body.",
        )
        if (dialog.showAndGet()) saveNewRule(project, service, dialog)
    }

    /** The response as text when it is text (JSON, XML, HTML, plain); binary bodies aren't copied. */
    private fun responseBody(connection: ConnectionData): String {
        val type = connection.responseType.lowercase()
        val textual = type.isEmpty() || listOf("json", "text", "xml", "html", "javascript", "x-www-form-urlencoded").any { it in type }
        if (!textual) return ""
        val text = readableBody(connection) ?: runCatching { connection.responsePayloadText }.getOrNull().orEmpty()
        return TrafficLogic.editableBody(text)
    }

    /** Studio's Connection View tables currently on screen. */
    private fun connectionTables(project: Project): List<JTable> {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(NetworkInspectorBridge.APP_INSPECTION_TOOL_WINDOW) ?: return emptyList()
        return tw.contentManager.contents.flatMap { UIUtil.findComponentsOfType(it.component, JTable::class.java) }
            .filter { it.model.javaClass.name == TABLE_MODEL }
    }

    /** The request selected in a Connection View, if any. */
    fun selectedConnection(project: Project): ConnectionData? = connectionTables(project).firstNotNullOfOrNull { table ->
        table.selectedRow.takeIf { it >= 0 }?.let { connectionAt(table, it) }
    }

    /** Adds our item to the right-click menu of every Connection View table, once each. */
    fun ensureConnectionsMenu(project: Project) {
        for (table in connectionTables(project)) {
            if (table.getClientProperty(WRAPPED_KEY) == true) continue
            val studio = table.mouseListeners.firstOrNull { it.javaClass.name.startsWith(STUDIO_LISTENER_PREFIX) } ?: continue
            table.removeMouseListener(studio)
            table.addMouseListener(MenuListener(project, table, studio))
            table.putClientProperty(WRAPPED_KEY, true)
        }
    }

    /** Passes everything to Studio's listener except the right-click, where it shows Studio's items plus ours. */
    private class MenuListener(private val project: Project, private val table: JTable, private val studio: MouseListener) : MouseListener by studio {
        override fun mousePressed(e: MouseEvent) = if (e.isPopupTrigger) showMenu(e) else studio.mousePressed(e)
        override fun mouseReleased(e: MouseEvent) = if (e.isPopupTrigger) showMenu(e) else studio.mouseReleased(e)

        private fun showMenu(e: MouseEvent) {
            val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
            // If the connection can't be read, let Studio show its own menu as before.
            val connection = connectionAt(table, row) ?: return if (e.id == MouseEvent.MOUSE_PRESSED) studio.mousePressed(e) else studio.mouseReleased(e)
            val studioActions = studioActions(connection)
            val ours = object : DumbAwareAction("Create Network Rule from Response…", null, AllIcons.Nodes.Plugin) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = createFrom(project, connection)
            }
            val group = DefaultActionGroup(studioActions + listOfNotNull(Separator.getInstance().takeIf { studioActions.isNotEmpty() }, ours))
            JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(table), false, null, -1)
                .show(RelativePoint(e))
        }
    }
}

/** Tools / ⋯ entry: the same, for the request selected in the Connection View. */
class CreateRuleFromSelectedRequestAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connection = TrafficRules.selectedConnection(project)
        if (connection == null) {
            Messages.showInfoMessage(
                project,
                "Select a request in Network Inspector's Connection View first, or right-click one there.",
                "No Request Selected",
            )
            return
        }
        TrafficRules.createFrom(project, connection)
    }
}
