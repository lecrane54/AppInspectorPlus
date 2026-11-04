package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleDataListener
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refresh updates shared rules in place with RuleMapper.copyInto. In a live session every change
 * notification re-sends the rule to the device, so an unchanged rule must cause none.
 */
class RuleMapperCopyTest {
    private class Counter : RuleDataListener {
        var changes = 0
        override fun onRuleNameChanged(ruleData: RuleData) = Unit
        override fun onRuleIsActiveChanged(ruleData: RuleData) = Unit
        override fun onRuleDataChanged(ruleData: RuleData) { changes++ }
    }

    private fun shared(path: String = "/v1/profile", body: String = """{"premium":true}""") = SharedRule(
        name = "[login] Profile",
        criteria = SharedCriteria(protocol = "HTTPS", host = "api.example.com", path = path, method = "GET"),
        statusCode = SharedStatusCode(true, "", "500"),
        headers = mutableListOf(SharedHeaderRule(type = "add", name = "X-Mock", value = "1")),
        body = mutableListOf(SharedBodyRule(type = "replace", body = JsonParser.parseString(body))),
    )

    private fun live(s: SharedRule): Pair<RuleData, Counter> {
        val rd = RuleMapper.toRuleData(s, RuleData.newId(), true)
        val counter = Counter()
        rd.ruleDataListener = counter
        return rd to counter
    }

    @Test fun `an unchanged rule isn't touched, so nothing is re-sent`() {
        val (rd, counter) = live(shared())
        assertFalse(RuleMapper.copyInto(rd, shared()))
        assertEquals(0, counter.changes)
    }

    @Test fun `a file that omits defaults still counts as unchanged`() {
        // Files may leave out port, query, etc.; Studio fills them in.
        val sparse = shared().copy(criteria = SharedCriteria(protocol = "HTTPS", host = "api.example.com", port = null, path = "/v1/profile", query = null, method = "GET"))
        val (rd, counter) = live(shared())
        assertFalse(RuleMapper.copyInto(rd, sparse))
        assertEquals(0, counter.changes)
    }

    @Test fun `a changed rule is updated in place and keeps its id, name and on-off state`() {
        val (rd, counter) = live(shared())
        val id = rd.id
        assertTrue(RuleMapper.copyInto(rd, shared(path = "/v2/profile", body = """{"premium":false}""")))
        assertTrue(counter.changes > 0)
        assertEquals(id, rd.id)
        assertEquals("[login] Profile", rd.name)
        assertTrue(rd.isActive)
        assertEquals(RuleMapper.normalized(shared(path = "/v2/profile", body = """{"premium":false}""")).copy(name = rd.name), RuleMapper.toShared(rd))
    }

    @Test fun `only the changed field is set`() {
        val (rd, counter) = live(shared())
        RuleMapper.copyInto(rd, shared(path = "/v2/profile"))
        assertEquals(1, counter.changes)
    }
}
