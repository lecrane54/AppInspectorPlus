package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RulesTableModel
import com.intellij.ui.TableUtil
import com.intellij.ui.table.TableView
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.Random
import javax.swing.SwingUtilities

/**
 * Drives Studio's own table model through the operations Studio and the plugin perform (add with
 * Studio's "select rowCount - 1", remove selected, refresh-style bulk replace, rename, toggle,
 * collapse, move up/down) with team groups installed, painting after each step.
 */
class RulesTableGroupsStressTest {
    private class State : CollapsedTeams {
        override val collapsed = mutableSetOf<String>()
        override fun isCollapsed(key: String) = key in collapsed
        override fun setCollapsed(key: String, collapsed: Boolean) {
            if (collapsed) this.collapsed += key else this.collapsed -= key
        }
    }

    private val teams = listOf("login", "checkout", "search")

    private fun name(r: Random): String = when (r.nextInt(4)) {
        0 -> "Local ${r.nextInt(1000)}"
        else -> "[${teams[r.nextInt(teams.size)]}] Rule ${r.nextInt(1000)}"
    }

    private fun pump() {
        // Runs whatever the code under test queued with invokeLater.
        SwingUtilities.invokeAndWait { }
        SwingUtilities.invokeAndWait { }
    }

    @Test fun `random operations never throw`() {
        for (seed in 1..40) runSeed(seed)
    }

    private fun runSeed(seed: Int) {
        val r = Random(seed.toLong())
        val state = State()
        lateinit var table: TableView<RuleData>
        lateinit var model: RulesTableModel
        SwingUtilities.invokeAndWait {
            model = RulesTableModel(ArrayList((0 until 6).map { RuleData(RuleData.newId(), name(r), r.nextBoolean()) }))
            table = TableView(model)
            table.setSize(600, 2000)
            RulesTableGroups.ensure(table, state)
        }
        pump()
        val image = BufferedImage(600, 2000, BufferedImage.TYPE_INT_ARGB)
        repeat(300) { step ->
            val op = r.nextInt(9)
            var added: RuleData? = null
            try {
                SwingUtilities.invokeAndWait {
                    when (op) {
                        0 -> { // Studio "+": addRow, then select rowCount - 1 as a view row
                            added = RuleData(RuleData.newId(), name(r), false)
                            model.addRow(added)
                            table.selectionModel.setSelectionInterval(model.rowCount - 1, model.rowCount - 1)
                            table.selectedObject
                        }
                        1 -> if (table.rowCount > 0) { // select a row and remove it like Studio's "−"
                            val v = r.nextInt(table.rowCount)
                            table.selectionModel.setSelectionInterval(v, v)
                            TableUtil.doRemoveSelectedItems(table, model, null)
                        }
                        2 -> { // plugin refresh: remove some rules by model index, re-add them
                            table.clearSelection()
                            RulesTableGroups.bulkUpdate = true
                            val n = r.nextInt(model.rowCount + 1)
                            repeat(n) { if (model.rowCount > 0) model.removeRow(r.nextInt(model.rowCount)) }
                            repeat(n) {
                                model.addRow(RuleData(RuleData.newId(), name(r), false))
                                table.selectionModel.setSelectionInterval(model.rowCount - 1, model.rowCount - 1)
                            }
                            RulesTableGroups.bulkUpdate = false
                            table.clearSelection()
                        }
                        3 -> if (model.rowCount > 0) { // rename (team can change)
                            val m = r.nextInt(model.rowCount)
                            model.getItem(m).name = name(r)
                            model.fireTableRowsUpdated(m, m)
                        }
                        4 -> if (model.rowCount > 0) { // toggle on/off through the table
                            val v = r.nextInt(table.rowCount)
                            table.setValueAt(r.nextBoolean(), v, 0)
                        }
                        5 -> { // collapse/expand a group
                            val key = (teams + TeamGrouping.LOCAL)[r.nextInt(teams.size + 1)]
                            state.setCollapsed(key, !state.isCollapsed(key))
                            RulesTableGroups.ensure(table, state)
                            relayout(table)
                        }
                        6 -> if (table.rowCount > 1) { // select a row, maybe move it (only when allowed)
                            val v = r.nextInt(table.rowCount)
                            table.selectionModel.setSelectionInterval(v, v)
                            table.selectedObject
                        }
                        7 -> model.items = ArrayList(model.items.shuffled(r)) // allRowsChanged
                        8 -> RulesTableGroups.ensure(table, state) // the 2-second tick
                    }
                    table.selectedRows
                    table.selectedObjects
                }
                pump()
                SwingUtilities.invokeAndWait {
                    checkInvariants(table, model, state)
                    // After Studio's "+", the new rule ends up selected and visible.
                    added?.let { check(table.selectedObject === it) { "new rule not selected: ${table.selectedObject?.name}" } }
                    val g = image.createGraphics()
                    table.paint(g)
                    g.dispose()
                }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                throw AssertionError("seed $seed step $step op $op: $cause", cause)
            }
        }
    }

    private fun checkInvariants(table: TableView<RuleData>, model: RulesTableModel, state: State) {
        val names = model.items.map { it.name }
        val expected = TeamGrouping.layout(names, state.collapsed)
        check(table.rowCount == expected.viewToModel.size) { "rowCount ${table.rowCount} != ${expected.viewToModel.size}" }
        for (v in 0 until table.rowCount) {
            check(table.convertRowIndexToModel(v) == expected.viewToModel[v]) { "view $v maps wrong" }
            val header = expected.headers[v]
            val base = table.rowHeight
            val h = table.getRowHeight(v)
            check(if (header == null) h == base else h > base || header.collapsed) { "row $v height $h" }
        }
        for (m in 0 until model.rowCount) check(table.convertRowIndexToView(m) == expected.modelToView[m]) { "model $m maps wrong" }
    }

    /** Forces a relayout the way a click on a header does. */
    private fun relayout(table: TableView<*>) {
        val sorter = table.rowSorter ?: return
        sorter.allRowsChanged()
    }
}
