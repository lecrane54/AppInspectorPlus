package io.github.networkrules

import io.github.networkrules.VariableLogic.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariableLogicTest {
    private fun file(vararg vars: Pair<String, String>) =
        SharedRulesFile(1, vars.map { SharedVariable(it.first, it.second) }.toMutableList(), mutableListOf())

    @Test fun `shared definitions take the first file's value and list every file`() {
        val defs = VariableLogic.sharedDefinitions(
            linkedMapOf("login/a.json" to file("HOST" to "a"), "checkout/b.json" to file("HOST" to "b", "ID" to "1")),
        )
        assertEquals("a", defs.getValue("HOST").value)
        assertEquals(listOf("login/a.json", "checkout/b.json"), defs.getValue("HOST").files)
        assertEquals(listOf("checkout/b.json"), defs.getValue("ID").files)
    }

    @Test fun `rows classify shared, local, studio-made and missing variables`() {
        val shared = VariableLogic.sharedDefinitions(mapOf("login/a.json" to file("HOST" to "api.example.com")))
        val rows = VariableLogic.rows(
            shared = shared,
            local = mapOf("HOST" to "my-laptop", "EMAIL" to "me@example.com"),
            studio = mapOf("TOKEN" to "abc"),
            used = mapOf("HOST" to 2, "USER_ID" to 1),
        ).associateBy { it.name }

        assertEquals(listOf("EMAIL", "HOST", "TOKEN", "USER_ID"), rows.keys.toList())
        rows.getValue("HOST").let {
            assertEquals(Scope.SHARED, it.scope)
            assertEquals("my-laptop", it.effective)
            assertEquals(2, it.usedBy)
        }
        assertEquals(Scope.LOCAL, rows.getValue("EMAIL").scope)
        assertEquals("abc", rows.getValue("TOKEN").yourValue) // made in Studio's own dialog: local
        assertEquals(Scope.LOCAL, rows.getValue("TOKEN").scope)
        rows.getValue("USER_ID").let {
            assertEquals(Scope.MISSING, it.scope)
            assertNull(it.effective)
        }
    }

    @Test fun `an empty override means the shared value`() {
        val shared = VariableLogic.sharedDefinitions(mapOf("a/r.json" to file("HOST" to "shared")))
        val row = VariableLogic.rows(shared, mapOf("HOST" to ""), emptyMap(), emptyMap()).single()
        assertNull(row.yourValue)
        assertEquals("shared", row.effective)
        assertEquals(mapOf("HOST" to "shared"), VariableLogic.studioValues(shared, mapOf("HOST" to "")))
    }

    @Test fun `Studio gets shared values with overrides plus local variables`() {
        val shared = VariableLogic.sharedDefinitions(mapOf("a/r.json" to file("HOST" to "shared", "ID" to "1")))
        assertEquals(
            mapOf("HOST" to "mine", "ID" to "1", "EMAIL" to "me@example.com"),
            VariableLogic.studioValues(shared, mapOf("HOST" to "mine", "EMAIL" to "me@example.com")),
        )
    }

    @Test fun `variables are added, updated and removed in a file`() {
        val f = file("A" to "1")
        assertEquals(listOf("A" to "1", "B" to "2"), VariableLogic.withVariable(f, "B", "2").variables!!.map { it.name to it.value })
        assertEquals(listOf("A" to "9"), VariableLogic.withVariable(f, "A", "9").variables!!.map { it.name to it.value })
        assertEquals(emptyList<SharedVariable>(), VariableLogic.withoutVariable(f, "A").variables)
    }

    @Test fun `names are letters, digits and underscores`() {
        assertEquals(true, VariableLogic.VALID_NAME.matches("API_HOST_2"))
        assertEquals(false, VariableLogic.VALID_NAME.matches("api-host"))
        assertEquals(false, VariableLogic.VALID_NAME.matches(""))
    }

    @Test fun `emails and tokens are flagged before sharing`() {
        assertEquals(true, sensitiveWarning("qa.user@example.com")?.contains("email"))
        assertEquals(true, sensitiveWarning("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")?.contains("token"))
        assertNull(sensitiveWarning("api.example.com"))
        assertNull(sensitiveWarning("12345"))
    }

    @Test fun `declared local variables are missing until you set a value, with their description`() {
        val files = mapOf(
            "login/a.json" to SharedRulesFile(1, mutableListOf(VariableLogic.declarationOf("EMAIL", "Your QA account email"), SharedVariable("HOST", "h")), mutableListOf()),
        )
        val shared = VariableLogic.sharedDefinitions(files)
        val declared = VariableLogic.declarations(files)
        assertEquals(setOf("HOST"), shared.keys)
        assertEquals(listOf("login/a.json"), declared.getValue("EMAIL").files)

        val before = VariableLogic.rows(shared, emptyMap(), emptyMap(), mapOf("EMAIL" to 1), declared).associateBy { it.name }
        assertEquals(Scope.MISSING, before.getValue("EMAIL").scope)
        assertEquals("Your QA account email", before.getValue("EMAIL").description)
        assertEquals(listOf("EMAIL"), VariableLogic.missing(before.values.toList()).map { it.name })

        val after = VariableLogic.rows(shared, mapOf("EMAIL" to "me@example.com"), emptyMap(), mapOf("EMAIL" to 1), declared).associateBy { it.name }
        assertEquals(Scope.LOCAL, after.getValue("EMAIL").scope)
        assertEquals(listOf("login/a.json"), after.getValue("EMAIL").declaredIn)
        assertEquals(emptyList<VariableLogic.Row>(), VariableLogic.missing(after.values.toList()))
        // Declarations never give Studio a value of their own.
        assertEquals(mapOf("HOST" to "h"), VariableLogic.studioValues(shared, emptyMap()))
    }

    @Test fun `making a variable local replaces its value with a declaration and keeps the description`() {
        val f = SharedRulesFile(1, mutableListOf(SharedVariable("EMAIL", "me@example.com", description = "QA email")), mutableListOf())
        val v = VariableLogic.withDeclaration(f, "EMAIL", null).variables!!.single()
        assertEquals(null, v.value)
        assertEquals(true, v.local)
        assertEquals("QA email", v.description)
        // A declaration is written without a value field at all.
        assertEquals(false, RulesLogic.gson.toJson(v).contains("value"))
    }

    @Test fun `export carries declarations, never local values`() {
        val out = RulesLogic.exportVariables(
            existing = emptyList(),
            studio = mapOf("EMAIL" to "me@example.com"),
            used = setOf("EMAIL"),
            overridden = setOf("EMAIL"),
            sharedDefaults = emptyMap(),
            declaredLocal = mapOf("EMAIL" to VariableLogic.declarationOf("EMAIL", "QA email")),
        ).single()
        assertEquals("EMAIL", out.name)
        assertEquals(null, out.value)
        assertEquals(true, out.local)
    }

    @Test fun `adb arguments carry your values and skip ones the receiver can't parse`() {
        val rows = listOf(
            VariableLogic.Row("EMAIL", Scope.LOCAL, null, emptyList(), "qa@example.com", 1),
            VariableLogic.Row("HOST", Scope.SHARED, "h", listOf("a/r.json"), null, 1),
            VariableLogic.Row("LIST", Scope.LOCAL, null, emptyList(), "a,b", 1),
        )
        val (args, skipped) = VariableLogic.adbVarArgs(rows)
        assertEquals("--es var \"EMAIL=qa@example.com\"", args)
        assertEquals(listOf("LIST"), skipped)
    }
}
