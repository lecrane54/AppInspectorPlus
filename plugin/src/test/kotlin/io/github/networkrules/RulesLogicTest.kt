package io.github.networkrules

import io.github.networkrules.RulesLogic.FileActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesLogicTest {
    private fun rule(name: String, path: String = "/$name") = SharedRule(name = name, criteria = SharedCriteria(path = path))
    private fun file(vararg rules: SharedRule, vars: List<SharedVariable> = emptyList()) =
        SharedRulesFile(1, vars.toMutableList(), rules.toMutableList())

    // ---- names ----

    @Test fun `studio names round trip`() {
        val n = RulesLogic.studioName("login", "Session expired")
        assertEquals("[login] Session expired", n)
        assertEquals("Session expired", RulesLogic.baseName(n))
        assertEquals("login", RulesLogic.teamOf(n))
    }

    @Test fun `names without a prefix are left alone`() {
        assertEquals("My rule", RulesLogic.baseName("My rule"))
        assertNull(RulesLogic.teamOf("My rule"))
    }

    @Test fun `sanitizeFileName keeps safe characters`() {
        assertEquals("payment-declined.json", RulesLogic.sanitizeFileName("payment-declined.json"))
        assertEquals("a-b-c.json", RulesLogic.sanitizeFileName("a/b:c.JSON"))
        assertEquals("imported.json", RulesLogic.sanitizeFileName("...json"))
    }

    @Test fun `team and target validation`() {
        assertTrue(RulesLogic.VALID_TEAM.matches("checkout"))
        assertTrue(RulesLogic.VALID_TEAM.matches("my team_2.0"))
        assertFalse(RulesLogic.VALID_TEAM.matches(".hidden"))
        assertFalse(RulesLogic.VALID_TEAM.matches("a/b"))
        assertTrue(RulesLogic.VALID_TARGET.matches("login/rules.json"))
        assertFalse(RulesLogic.VALID_TARGET.matches("login/rules.txt"))
        assertFalse(RulesLogic.VALID_TARGET.matches("a/b/c.json"))
    }

    // ---- import ----

    @Test fun `import replaces same-named rules and appends new ones`() {
        val existing = file(rule("A", "/old"), rule("B"), vars = listOf(SharedVariable("HOST", "old")))
        val incoming = file(rule("A", "/new"), rule("C"), vars = listOf(SharedVariable("HOST", "new"), SharedVariable("ID", "1")))

        val merged = RulesLogic.mergeImport(existing, incoming)

        assertEquals(listOf("A", "B", "C"), merged.rules!!.map { it.name })
        assertEquals("/new", merged.rules!![0].criteria!!.path)
        assertEquals(mapOf("HOST" to "new", "ID" to "1"), merged.variables!!.associate { it.name to it.value })
    }

    @Test fun `import into a missing file creates it`() {
        val merged = RulesLogic.mergeImport(null, file(rule("A")))
        assertEquals(listOf("A"), merged.rules!!.map { it.name })
    }

    @Test fun `import collisions list only same-named rules`() {
        assertEquals(listOf("A"), RulesLogic.importCollisions(file(rule("A"), rule("B")), file(rule("A"), rule("C"))))
        assertEquals(emptyList<String>(), RulesLogic.importCollisions(null, file(rule("A"))))
    }

    // ---- export ----

    @Test fun `export keeps file order, drops unchecked rules, and takes Studio's version`() {
        val fileRules = listOf(rule("A", "/a"), rule("B", "/b"), rule("C", "/c"))
        val selected = setOf("[login] A", "[login] C")
        val studio = mapOf("[login] C" to rule("[login] C", "/c-edited"))

        val out = RulesLogic.exportedFileRules("login", fileRules, selected, studio)

        assertEquals(listOf("A", "C"), out.map { it.name })
        assertEquals("/a", out[0].criteria!!.path)       // not in Studio: file version kept
        assertEquals("/c-edited", out[1].criteria!!.path) // Studio wins, name loses its prefix
    }

    @Test fun `export does not mix up teams with the same rule name`() {
        val out = RulesLogic.exportedFileRules("login", listOf(rule("A")), setOf("[checkout] A"), emptyMap())
        assertTrue(out.isEmpty())
    }

    @Test fun `upsert replaces by name`() {
        val list = mutableListOf(rule("A", "/1"), rule("B"))
        RulesLogic.upsert(list, rule("A", "/2"))
        RulesLogic.upsert(list, rule("C"))
        assertEquals(listOf("A", "B", "C"), list.map { it.name })
        assertEquals("/2", list[0].criteria!!.path)
    }

    @Test fun `usedVariables finds references anywhere in a rule`() {
        val r = SharedRule(
            name = "x",
            criteria = SharedCriteria(host = "\${API_HOST}"),
            headers = mutableListOf(SharedHeaderRule(type = "add", name = "X-User", value = "\${USER_ID}")),
        )
        assertEquals(setOf("API_HOST", "USER_ID"), RulesLogic.usedVariables(listOf(r)))
    }

    @Test fun `export writes Studio's value for used variables`() {
        val out = RulesLogic.exportVariables(
            existing = listOf(SharedVariable("KEEP", "k")),
            studio = mapOf("HOST" to "studio-host", "UNUSED" to "u"),
            used = setOf("HOST"),
            overridden = emptySet(),
            sharedDefaults = emptyMap(),
        )
        assertEquals(mapOf("KEEP" to "k", "HOST" to "studio-host"), out.associate { it.name to it.value })
    }

    @Test fun `export never writes a personal override`() {
        // Studio holds the developer's own value; the file keeps the shared one.
        val kept = RulesLogic.exportVariables(
            existing = listOf(SharedVariable("HOST", "shared-host")),
            studio = mapOf("HOST" to "my-laptop"),
            used = setOf("HOST"),
            overridden = setOf("HOST"),
            sharedDefaults = mapOf("HOST" to "shared-host"),
        )
        assertEquals(mapOf("HOST" to "shared-host"), kept.associate { it.name to it.value })

        // A new file gets the shared value from another file, not the override.
        val fresh = RulesLogic.exportVariables(emptyList(), mapOf("HOST" to "my-laptop"), setOf("HOST"), setOf("HOST"), mapOf("HOST" to "shared-host"))
        assertEquals(mapOf("HOST" to "shared-host"), fresh.associate { it.name to it.value })
    }

    // ---- variables ----

    @Test fun `effective variables use the first shared value, then personal overrides`() {
        val files = listOf(
            file(vars = listOf(SharedVariable("HOST", "a"), SharedVariable("ID", "1"))),
            file(vars = listOf(SharedVariable("HOST", "b"))),
        )
        assertEquals(mapOf("HOST" to "a", "ID" to "1"), RulesLogic.effectiveVariables(files, emptyMap()))
        assertEquals(mapOf("HOST" to "mine", "ID" to "1"), RulesLogic.effectiveVariables(files, mapOf("HOST" to "mine", "NOT_SHARED" to "x")))
    }

    // ---- change detection ----

    @Test fun `stale files are the ones changed or not seen since the last load`() {
        val recorded = mapOf("login/a.json" to RulesLogic.hash("1"), "login/b.json" to RulesLogic.hash("2"))
        val current = mapOf(
            "login/a.json" to RulesLogic.hash("1"),      // unchanged
            "login/b.json" to RulesLogic.hash("2 + pull"), // changed
            "login/c.json" to RulesLogic.hash("new"),    // pulled in, never loaded
        )
        assertEquals(listOf("login/b.json", "login/c.json"), RulesLogic.staleFiles(recorded, current))
    }

    @Test fun `hash is stable and content sensitive`() {
        assertEquals(RulesLogic.hash("abc"), RulesLogic.hash("abc"))
        assertFalse(RulesLogic.hash("abc") == RulesLogic.hash("abd"))
    }

    // ---- on/off by file ----

    private val allOn = FileActivity("login/a.json", 2, 2)
    private val partly = FileActivity("login/b.json", 1, 3)
    private val off = FileActivity("checkout/c.json", 0, 2)

    @Test fun `checking a file turns it on, unchecking an all-on file turns it off`() {
        val plan = RulesLogic.activityPlan(listOf(allOn, partly, off), checked = setOf("checkout/c.json"), exclusive = false)
        assertEquals(mapOf("login/a.json" to false, "checkout/c.json" to true), plan)
    }

    @Test fun `untouched files are left alone`() {
        val plan = RulesLogic.activityPlan(listOf(allOn, partly, off), checked = setOf("login/a.json"), exclusive = false)
        assertTrue(plan.isEmpty())
    }

    @Test fun `exclusive turns off everything not checked`() {
        val plan = RulesLogic.activityPlan(listOf(allOn, partly, off), checked = setOf("checkout/c.json"), exclusive = true)
        assertEquals(mapOf("login/a.json" to false, "login/b.json" to false, "checkout/c.json" to true), plan)
    }

    @Test fun `checking a partly-on file turns all of it on`() {
        assertEquals(mapOf("login/b.json" to true), RulesLogic.activityPlan(listOf(partly), setOf("login/b.json"), false))
    }

    // ---- renaming teams ----

    @Test fun `renaming a team renames only its rules and paths`() {
        assertEquals("[auth] Expired", RulesLogic.renameStudioName("[login] Expired", "login", "auth"))
        assertEquals("[checkout] Expired", RulesLogic.renameStudioName("[checkout] Expired", "login", "auth"))
        assertEquals("Local", RulesLogic.renameStudioName("Local", "login", "auth"))
        assertEquals("auth/a.json", RulesLogic.renamePath("login/a.json", "login", "auth"))
        assertEquals("login2/a.json", RulesLogic.renamePath("login2/a.json", "login", "auth"))
    }

    // ---- errors ----

    @Test fun `parse error position is zero based`() {
        assertEquals(4 to 11, RulesLogic.errorPosition("Unterminated object at line 5 column 12 path \$.rules[0]"))
        assertNull(RulesLogic.errorPosition("something else"))
        assertNull(RulesLogic.errorPosition(null))
    }

    // ---- file format ----

    @Test fun `rules files round trip through JSON`() {
        val f = file(rule("A"), vars = listOf(SharedVariable("HOST", "h")))
        val back = RulesLogic.gson.fromJson(RulesLogic.gson.toJson(f), SharedRulesFile::class.java)
        assertEquals(f, back)
    }
}
