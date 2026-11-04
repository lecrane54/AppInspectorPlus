package io.github.networkrules

import io.github.networkrules.ScenarioLogic.RuleRef
import org.junit.Assert.assertEquals
import org.junit.Test

class ScenarioLogicTest {
    private val refs = listOf(
        RuleRef("[login] Session expired", "login", "login/session.json"),
        RuleRef("[login] MFA", "login", "login/mfa.json"),
        RuleRef("[checkout] Declined", "checkout", "checkout/payment.json"),
        RuleRef("[checkout] Timeout", "checkout", "checkout/payment.json"),
        RuleRef("[checkout] Empty cart", "checkout", "checkout/cart.json"),
    )

    @Test fun `selectors pick teams, files, single rules or everything, like Appium`() {
        assertEquals(setOf("[login] Session expired", "[login] MFA"), ScenarioLogic.resolve(listOf("login"), refs).first)
        assertEquals(setOf("[checkout] Declined", "[checkout] Timeout"), ScenarioLogic.resolve(listOf("checkout/payment.json"), refs).first)
        assertEquals(setOf("[login] MFA"), ScenarioLogic.resolve(listOf("[login] MFA"), refs).first)
        assertEquals(5, ScenarioLogic.resolve(listOf("*"), refs).first.size)
    }

    @Test fun `selectors that match nothing are reported`() {
        val (on, notFound) = ScenarioLogic.resolve(listOf("login", "[search] Gone", " "), refs)
        assertEquals(2, on.size)
        assertEquals(listOf("[search] Gone"), notFound)
    }

    @Test fun `compact uses whole teams, then whole files, then single rules`() {
        val on = setOf("[login] Session expired", "[login] MFA", "[checkout] Declined", "[checkout] Timeout")
        assertEquals(listOf("checkout/payment.json", "login"), ScenarioLogic.compact(on, refs))
        assertEquals(listOf("[checkout] Declined"), ScenarioLogic.compact(setOf("[checkout] Declined"), refs))
        // Round trip: compact selectors resolve back to the same rules.
        assertEquals(on, ScenarioLogic.resolve(ScenarioLogic.compact(on, refs), refs).first)
    }

    @Test fun `upsert replaces in place and remove drops by name`() {
        val a = SharedScenario("a", enable = mutableListOf("login"))
        val b = SharedScenario("b", enable = mutableListOf("checkout"))
        val file = ScenarioLogic.upsert(ScenarioLogic.upsert(null, a), b)
        val replaced = ScenarioLogic.upsert(file, SharedScenario("a", enable = mutableListOf("*")))
        assertEquals(listOf("a" to listOf("*"), "b" to listOf("checkout")), replaced.scenarios!!.map { it.name to it.enable })
        assertEquals(listOf("b"), ScenarioLogic.remove(replaced, "a").scenarios!!.map { it.name })
    }

    @Test fun `scenario variables sit between the shared value and your own`() {
        val shared = VariableLogic.sharedDefinitions(mapOf("a/r.json" to SharedRulesFile(1, mutableListOf(SharedVariable("HOST", "prod"), SharedVariable("ID", "1")), mutableListOf())))
        val scenario = mapOf("HOST" to "staging", "ID" to "2", "FLAG" to "on")
        assertEquals(
            mapOf("HOST" to "mine", "ID" to "2", "FLAG" to "on"),
            VariableLogic.studioValues(shared, mapOf("HOST" to "mine"), scenario),
        )
        val rows = VariableLogic.rows(shared, emptyMap(), emptyMap(), emptyMap(), emptyMap(), scenario).associateBy { it.name }
        assertEquals("staging", rows.getValue("HOST").effective)
        assertEquals(VariableLogic.Scope.SCENARIO, rows.getValue("FLAG").scope)
    }
}
