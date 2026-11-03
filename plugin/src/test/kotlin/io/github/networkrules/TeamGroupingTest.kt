package io.github.networkrules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamGroupingTest {
    @Test fun `no shared rules means no groups and Studio's order`() {
        val l = TeamGrouping.layout(listOf("a", "b"), emptySet())
        assertFalse(l.isGrouped)
        assertTrue(l.isIdentity)
        assertArrayEquals(intArrayOf(0, 1), l.viewToModel)
    }

    @Test fun `teams are sorted by name, local rules last, model order kept inside a group`() {
        val names = listOf("[login] A", "Mine", "[checkout] B", "[login] C", "[Checkout] D")
        val l = TeamGrouping.layout(names, emptySet())
        // Case-insensitive by name; "Checkout" sorts before "checkout" on the exact-name tie break.
        assertArrayEquals(intArrayOf(4, 2, 0, 3, 1), l.viewToModel)
        assertEquals(listOf("Checkout", "checkout", "login", TeamGrouping.LOCAL), l.headers.values.map { it.key })
        assertEquals(setOf(0, 1, 2, 4), l.headers.keys)
        assertEquals(2, l.modelToView[0])
        assertEquals(4, l.modelToView[1])
        assertFalse(l.isIdentity)
    }

    @Test fun `a collapsed group shows only its first rule, as the header`() {
        val names = listOf("[login] A", "[login] B", "[login] C", "Mine")
        val l = TeamGrouping.layout(names, setOf("login"))
        assertArrayEquals(intArrayOf(0, 3), l.viewToModel)
        assertTrue(l.headers.getValue(0).collapsed)
        assertEquals(listOf(0, 1, 2), l.headers.getValue(0).modelRows)
        assertEquals(-1, l.modelToView[1])
        assertEquals(-1, l.modelToView[2])
        assertFalse(l.isIdentity)
    }

    @Test fun `the local group can be collapsed too`() {
        val l = TeamGrouping.layout(listOf("[login] A", "x", "y"), setOf(TeamGrouping.LOCAL))
        assertArrayEquals(intArrayOf(0, 1), l.viewToModel)
    }

    @Test fun `moves are allowed only inside a group and only in model order`() {
        val l = TeamGrouping.layout(listOf("[a] 1", "[a] 2", "[b] 3", "x"), emptySet())
        assertTrue(l.isIdentity)
        assertFalse(l.canMove(0, up = true))
        assertTrue(l.canMove(0, up = false))
        assertTrue(l.canMove(1, up = true))
        assertFalse(l.canMove(1, up = false)) // would cross into team b
        assertFalse(l.canMove(2, up = true))
        assertFalse(l.canMove(3, up = true)) // first local rule

        val shuffled = TeamGrouping.layout(listOf("[b] 1", "[a] 2", "[a] 3"), emptySet())
        assertFalse(shuffled.isIdentity)
        assertFalse(shuffled.canMove(2, up = true))
    }

    @Test fun `sameOrderAs notices a collapse even when rows don't move`() {
        val names = listOf("[a] 1", "x")
        val open = TeamGrouping.layout(names, emptySet())
        val closed = TeamGrouping.layout(names, setOf("a"))
        assertArrayEquals(open.viewToModel, closed.viewToModel)
        assertFalse(open.sameOrderAs(closed))
        assertTrue(open.sameOrderAs(TeamGrouping.layout(names, emptySet())))
    }
}
