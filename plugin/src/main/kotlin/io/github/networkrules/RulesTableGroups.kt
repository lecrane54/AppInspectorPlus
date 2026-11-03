package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.ui.AnActionButtonUpdater
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.JBColor
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SwingUtilities
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel

/** Which groups this developer has collapsed. Stored per developer, never committed. */
interface CollapsedTeams {
    fun isCollapsed(key: String): Boolean
    fun setCollapsed(key: String, collapsed: Boolean)
    val collapsed: Set<String>
}

/**
 * Collapsible team groups inside Studio's own Rules table.
 *
 * Studio's table is flat and every model row is a real rule sent to the device, so no header rows
 * can be added to the model. Instead:
 *  - a [RowSorter] shows the rows grouped by team and hides the rows of collapsed teams, without
 *    touching the model (so the device's rule order never changes);
 *  - the first row of each group is made taller, and its renderers draw a header band on top;
 *    a collapsed group keeps only that band;
 *  - a click on a band toggles the group and is consumed before Studio's own mouse handling.
 *
 * Studio converts view rows to model rows for delete, clone and the detail panel, so those keep
 * working. Its up/down buttons don't, so they're only enabled when the move stays inside a group
 * and every rule is visible in model order. Everything here is checked again on every toolbar
 * tick, so a table Studio rebuilds is picked up again; if anything fails, the table is left as is.
 */
class RulesTableGroups private constructor(private val table: TableView<*>, private val state: CollapsedTeams) {
    private val model = table.model as ListTableModel<*>
    private val sorter = GroupSorter()
    private val bandHeight get() = JBUI.scale(22)

    private fun ensure() {
        if (table.rowSorter !== sorter) table.rowSorter = sorter
        for (column in table.columnModel.columns.toList()) {
            val viewIndex = table.convertColumnIndexToView(column.modelIndex).takeIf { it >= 0 } ?: continue
            val columnClass = table.getColumnClass(viewIndex)
            if (column.cellRenderer !is BandRenderer) {
                column.cellRenderer = BandRenderer(column.cellRenderer ?: table.getDefaultRenderer(columnClass))
            }
            if (column.cellEditor !is BandEditor) {
                (column.cellEditor ?: table.getDefaultEditor(columnClass))?.let { column.cellEditor = BandEditor(it) }
            }
        }
        installMoveGuards()
        applyRowHeights()
    }

    // ---- view order ---------------------------------------------------------------------------

    private fun names(): List<String?> = (0 until model.rowCount).map { (model.getItem(it) as? RuleData)?.name }

    private inner class GroupSorter : RowSorter<TableModel>() {
        var layout: TeamGrouping.Layout = TeamGrouping.layout(names(), state.collapsed)
            private set

        /** Recomputes the view; tells the table only when what it shows has moved, otherwise just repaints. */
        fun relayout(force: Boolean = false) {
            val old = layout
            layout = TeamGrouping.layout(names(), state.collapsed)
            if (force || !layout.sameOrderAs(old)) fireRowSorterChanged(old.viewToModel) else table.repaint()
            SwingUtilities.invokeLater(::applyRowHeights)
        }

        override fun getModel(): TableModel = model
        override fun toggleSortOrder(column: Int) = Unit
        override fun setSortKeys(keys: List<SortKey>?) = Unit
        override fun getSortKeys(): List<SortKey> = emptyList()
        override fun getViewRowCount() = layout.viewToModel.size
        override fun getModelRowCount() = model.rowCount

        override fun convertRowIndexToModel(index: Int): Int = when {
            index in layout.viewToModel.indices -> layout.viewToModel[index]
            // Studio's "+" selects row "rowCount - 1" as a view row right after adding; when groups
            // are collapsed that row may not exist in the view yet, and as a model row it's the new rule.
            index in 0 until model.rowCount -> index
            else -> throw IndexOutOfBoundsException("Invalid view row $index")
        }

        override fun convertRowIndexToView(index: Int): Int = layout.modelToView.getOrElse(index) { -1 }

        override fun modelStructureChanged() = relayout(force = true)
        override fun allRowsChanged() = relayout(force = true)
        override fun rowsInserted(firstRow: Int, endRow: Int) {
            relayout(force = true)
            if (endRow == model.rowCount - 1) fixSelectionAfterAdd(endRow)
        }
        override fun rowsDeleted(firstRow: Int, endRow: Int) = relayout(force = true)
        override fun rowsUpdated(firstRow: Int, endRow: Int) = relayout()
        override fun rowsUpdated(firstRow: Int, endRow: Int, column: Int) = relayout()
    }

    /**
     * Studio selects a rule it just added by its model index, as if it were a view row. Once the
     * add is done, select the rule's real row instead, opening its group if it was collapsed.
     * A refresh clears the selection after adding, which this leaves alone.
     */
    private fun fixSelectionAfterAdd(modelRow: Int) {
        SwingUtilities.invokeLater {
            val selection = table.selectionModel
            if (selection.minSelectionIndex != modelRow || selection.maxSelectionIndex != modelRow) return@invokeLater
            if (modelRow >= model.rowCount) return@invokeLater
            val key = TeamGrouping.groupKey((model.getItem(modelRow) as? RuleData)?.name)
            if (state.isCollapsed(key)) {
                state.setCollapsed(key, false)
                sorter.relayout()
            }
            val view = sorter.convertRowIndexToView(modelRow)
            if (view < 0) return@invokeLater
            if (view != modelRow) selection.setSelectionInterval(view, view)
            table.scrollRectToVisible(table.getCellRect(view, 0, true))
        }
    }

    private fun toggle(group: TeamGrouping.Group) {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        state.setCollapsed(group.key, !group.collapsed)
        sorter.relayout()
    }

    // ---- row heights --------------------------------------------------------------------------

    private fun applyRowHeights() {
        if (table.rowSorter !== sorter) return
        val base = table.rowHeight
        val layout = sorter.layout
        for (row in 0 until minOf(table.rowCount, layout.viewToModel.size)) {
            val group = layout.headers[row]
            val wanted = when {
                group == null -> base
                group.collapsed -> bandHeight
                else -> base + bandHeight
            }
            if (table.getRowHeight(row) != wanted) table.setRowHeight(row, wanted)
        }
    }

    // ---- header band --------------------------------------------------------------------------

    private fun bandAt(e: MouseEvent): TeamGrouping.Group? {
        val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return null
        val group = sorter.layout.headers[row] ?: return null
        return group.takeIf { e.y < table.getCellRect(row, 0, true).y + bandHeight }
    }

    private val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            val group = bandAt(e) ?: return
            e.consume()
            if (SwingUtilities.isLeftMouseButton(e)) toggle(group)
        }

        override fun mouseReleased(e: MouseEvent) = consumeOnBand(e)
        override fun mouseClicked(e: MouseEvent) = consumeOnBand(e)
        override fun mouseDragged(e: MouseEvent) = consumeOnBand(e)

        override fun mouseMoved(e: MouseEvent) {
            val wanted = if (bandAt(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
            if (table.cursor != wanted) table.cursor = wanted
        }

        override fun mouseExited(e: MouseEvent) {
            table.cursor = null
        }

        private fun consumeOnBand(e: MouseEvent) {
            if (bandAt(e) != null) e.consume()
        }
    }

    /** Our listener must run first, so a click on a band is consumed before the table selects or edits. */
    private fun installMouse() {
        val listeners = table.mouseListeners.toList()
        listeners.forEach(table::removeMouseListener)
        table.addMouseListener(mouse)
        listeners.forEach(table::addMouseListener)
        val motion = table.mouseMotionListeners.toList()
        motion.forEach(table::removeMouseMotionListener)
        table.addMouseMotionListener(mouse)
        motion.forEach(table::addMouseMotionListener)
    }

    private fun summary(group: TeamGrouping.Group): String {
        val total = group.modelRows.size
        val on = group.modelRows.count { (it < model.rowCount) && (model.getItem(it) as? RuleData)?.isActive == true }
        return "${if (total == 1) "1 rule" else "$total rules"} · $on on"
    }

    /** Paints this cell's slice of a band that runs across the whole row. */
    private inner class Band : JComponent() {
        var group: TeamGrouping.Group? = null
        var cellX = 0

        override fun getPreferredSize() = Dimension(0, bandHeight)

        override fun paintComponent(g: Graphics) {
            val group = group ?: return
            val g2 = g.create() as Graphics2D
            try {
                UISettings.setupAntialiasing(g2)
                g2.color = BAND_BACKGROUND
                g2.fillRect(0, 0, width, height)
                g2.color = JBColor.border()
                g2.drawLine(0, 0, width, 0)
                g2.drawLine(0, height - 1, width, height - 1)

                g2.translate(-cellX, 0)
                val icon = if (group.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
                var x = JBUI.scale(6)
                icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
                x += icon.iconWidth + JBUI.scale(4)

                val bold = table.font.deriveFont(java.awt.Font.BOLD)
                val title = if (group.key == TeamGrouping.LOCAL) "Local rules (not shared)" else group.key
                val baseline = (height + g2.getFontMetrics(bold).ascent - g2.getFontMetrics(bold).descent) / 2
                g2.font = bold
                g2.color = UIUtil.getLabelForeground()
                g2.drawString(title, x, baseline)
                x += g2.fontMetrics.stringWidth(title) + JBUI.scale(8)

                g2.font = table.font
                g2.color = NamedColorUtil.getInactiveTextColor()
                g2.drawString(summary(group), x, baseline)
            } finally {
                g2.dispose()
            }
        }
    }

    /** Puts the band above the cell's own component on a group's first row, or only the band when collapsed. */
    private fun withBand(band: Band, panel: JPanel, content: Component, row: Int, column: Int): Component {
        val group = sorter.layout.headers[row] ?: return content
        band.group = group
        band.cellX = table.getCellRect(row, column, false).x
        panel.removeAll()
        panel.add(band, BorderLayout.NORTH)
        if (!group.collapsed) panel.add(content, BorderLayout.CENTER)
        return panel
    }

    private inner class BandRenderer(val delegate: TableCellRenderer) : TableCellRenderer {
        private val band = Band()
        private val panel = JPanel(BorderLayout()).apply { isOpaque = false }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val content = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            return withBand(band, panel, content, row, column)
        }
    }

    private inner class BandEditor(val delegate: TableCellEditor) : TableCellEditor by delegate {
        private val band = Band()
        private val panel = JPanel(BorderLayout()).apply { isOpaque = false }

        override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
            val content = delegate.getTableCellEditorComponent(table, value, isSelected, row, column)
            return withBand(band, panel, content, row, column)
        }
    }

    // ---- Studio's up/down buttons ---------------------------------------------------------------

    /** Studio's up/down move rows by view index; only allow moves that mean the same thing in the model. */
    private fun installMoveGuards() {
        val decorator = generateSequence(table.parent as Container?) { it.parent }
            .firstOrNull { c -> c.components.any { it is CommonActionsPanel } } ?: return
        val panel = decorator.components.first { it is CommonActionsPanel } as CommonActionsPanel
        if (panel.getClientProperty(GUARDED_KEY) == true) return
        for ((button, up) in listOf(CommonActionsPanel.Buttons.UP to true, CommonActionsPanel.Buttons.DOWN to false)) {
            panel.getAnActionButton(button)?.addCustomUpdater(AnActionButtonUpdater {
                val rows = table.selectedRows
                rows.isNotEmpty() && rows.all { sorter.layout.canMove(it, up) }
            })
        }
        panel.putClientProperty(GUARDED_KEY, true)
    }

    companion object {
        private const val KEY = "io.github.networkrules.groups"
        private const val GUARDED_KEY = "io.github.networkrules.moveGuards"
        private val BAND_BACKGROUND = JBColor.namedColor("NetworkRulesSync.groupBackground", JBColor(0xEBECF0, 0x393B40))

        /** Adds team groups to [table], or re-applies them if Studio reset something. Safe to call repeatedly. */
        fun ensure(table: TableView<*>, state: CollapsedTeams) {
            if (table.model !is ListTableModel<*>) return
            val existing = table.getClientProperty(KEY) as? RulesTableGroups
            val groups = existing ?: RulesTableGroups(table, state).also {
                table.putClientProperty(KEY, it)
                it.installMouse()
            }
            groups.ensure()
        }
    }
}
