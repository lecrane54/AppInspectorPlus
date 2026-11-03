package io.github.networkrules

/**
 * How the Rules tab groups rules by team, kept free of Swing and Studio types so it can be unit tested.
 *
 * The table's model is Studio's rule list, in the order the device applies the rules. This only
 * decides the order the rows are *shown* in:
 *  - Rules are grouped by their `[team]` prefix. Teams are sorted by name, and rules without a
 *    prefix (your local rules) come last, so a rule Studio's "+" adds stays at the bottom.
 *  - Inside a group, rules keep their model order.
 *  - The first row of each group carries the group's header. A collapsed group shows only that
 *    row, drawn as a header alone.
 *  - With no shared rules at all, nothing is grouped and the table looks as Studio made it.
 */
object TeamGrouping {
    /** Group key for rules without a `[team]` prefix. A team folder can't have an empty name. */
    const val LOCAL = ""

    class Group(val key: String, val modelRows: List<Int>, val collapsed: Boolean) {
        val firstModelRow: Int get() = modelRows.first()
    }

    class Layout(
        val viewToModel: IntArray,
        /** -1 for rows hidden in a collapsed group. */
        val modelToView: IntArray,
        /** View row → the group whose header is drawn on top of it. */
        val headers: Map<Int, Group>,
        /** View row → its group, or null when nothing is grouped. */
        val groupOfView: List<Group?>,
    ) {
        val isGrouped: Boolean get() = headers.isNotEmpty()

        /** Every rule visible, in model order: the only state where moving rows by view index is safe. */
        val isIdentity: Boolean =
            viewToModel.size == modelToView.size && viewToModel.withIndex().all { (i, m) -> i == m }

        fun sameOrderAs(other: Layout): Boolean =
            viewToModel.contentEquals(other.viewToModel) &&
                headers.keys == other.headers.keys &&
                headers.all { (row, g) -> other.headers[row]?.let { it.key == g.key && it.collapsed == g.collapsed } == true }

        /** Whether moving the rule at [viewRow] up (or down) keeps it inside its own group. */
        fun canMove(viewRow: Int, up: Boolean): Boolean {
            if (!isIdentity) return false
            val target = if (up) viewRow - 1 else viewRow + 1
            if (target !in viewToModel.indices || viewRow !in viewToModel.indices) return false
            return groupOfView[viewRow]?.key == groupOfView[target]?.key
        }
    }

    fun groupKey(studioName: String?): String = studioName?.let(RulesLogic::teamOf) ?: LOCAL

    fun layout(names: List<String?>, collapsed: Set<String>): Layout {
        val keys = names.map(::groupKey)
        if (keys.all { it == LOCAL }) {
            val identity = IntArray(names.size) { it }
            return Layout(identity, identity.copyOf(), emptyMap(), List(names.size) { null })
        }
        val rowsByKey = keys.indices.groupBy { keys[it] }
        val order = rowsByKey.keys.sortedWith(compareBy<String>({ it == LOCAL }, { it.lowercase() }, { it }))

        val viewToModel = ArrayList<Int>(names.size)
        val modelToView = IntArray(names.size) { -1 }
        val headers = linkedMapOf<Int, Group>()
        val groupOfView = ArrayList<Group?>(names.size)
        for (key in order) {
            val group = Group(key, rowsByKey.getValue(key), key in collapsed)
            headers[viewToModel.size] = group
            val shown = if (group.collapsed) group.modelRows.take(1) else group.modelRows
            for (m in shown) {
                modelToView[m] = viewToModel.size
                viewToModel += m
                groupOfView += group
            }
        }
        return Layout(viewToModel.toIntArray(), modelToView, headers, groupOfView)
    }
}
