package io.github.networkrules

import com.google.gson.JsonParser
import io.github.networkrules.SensitiveData.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataTest {
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl"

    private fun rule() = SharedRule(
        name = "Login as qa@example.com",
        criteria = SharedCriteria(host = "api.example.com", query = "token=$jwt"),
        headers = mutableListOf(
            SharedHeaderRule(type = "add", name = "Authorization", value = "Bearer abcdefghijklmnop1234"),
            SharedHeaderRule(type = "add", name = "X-Trace", value = "\${TRACE_ID}"),
        ),
        body = mutableListOf(
            SharedBodyRule(type = "replace", body = JsonParser.parseString("""{"user":{"email":"qa@example.com","id":"550e8400-e29b-41d4-a716-446655440000"}}""")),
        ),
    )

    @Test fun `finds emails, tokens and secret headers but not the rule name, ids or variables`() {
        val found = SensitiveData.find("[login] Login", rule())
        assertEquals(
            setOf("qa@example.com" to Kind.EMAIL, jwt to Kind.TOKEN, "abcdefghijklmnop1234" to Kind.TOKEN),
            found.map { it.literal to it.kind }.toSet(),
        )
        assertTrue(found.any { it.where == "body[0].body.user.email" })
        assertTrue(found.none { "550e8400" in it.literal })
    }

    @Test fun `a secret header's whole value is flagged even when it doesn't look like a token`() {
        val r = SharedRule(name = "x", headers = mutableListOf(SharedHeaderRule(type = "add", name = "Cookie", value = "session=abc")))
        assertEquals(listOf("session=abc" to Kind.SECRET_HEADER), SensitiveData.find("x", r).map { it.literal to it.kind })
    }

    @Test fun `replace swaps values for variables everywhere except the name`() {
        val replaced = SensitiveData.replace(rule(), mapOf("qa@example.com" to "EMAIL", jwt to "TOKEN"))
        assertEquals("Login as qa@example.com", replaced.name)
        assertEquals("token=\${TOKEN}", replaced.criteria!!.query)
        assertEquals("\${EMAIL}", replaced.body!![0].body!!.asJsonObject["user"].asJsonObject["email"].asString)
        assertTrue(SensitiveData.find("x", replaced).none { it.kind == Kind.EMAIL })
    }

    @Test fun `suggested names reuse a variable holding the same value and never clash`() {
        val findings = listOf(
            SensitiveData.Finding("r", "a", "qa@example.com", Kind.EMAIL),
            SensitiveData.Finding("r", "b", "other@example.com", Kind.EMAIL),
            SensitiveData.Finding("r", "c", "known@example.com", Kind.EMAIL),
            SensitiveData.Finding("r", "d", "qa@example.com", Kind.EMAIL),
        )
        val names = SensitiveData.suggestNames(findings, existing = mapOf("QA_USER" to "known@example.com"), taken = setOf("EMAIL", "QA_USER"))
        assertEquals(mapOf("qa@example.com" to "EMAIL_2", "other@example.com" to "EMAIL_3", "known@example.com" to "QA_USER"), names)
    }
}
