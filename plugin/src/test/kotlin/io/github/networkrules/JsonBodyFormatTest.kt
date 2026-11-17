package io.github.networkrules

import io.github.networkrules.JsonBodyFormat.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonBodyFormatTest {
    @Test fun `objects and arrays are pretty-printed`() {
        assertEquals(Result.Formatted("{\n  \"a\": [\n    1,\n    2\n  ]\n}"), JsonBodyFormat.format("""  {"a":[1,2]}  """))
        assertEquals(Result.Formatted("[]"), JsonBodyFormat.format("[]"))
    }

    @Test fun `values are kept exactly`() {
        val formatted = JsonBodyFormat.formatOrKeep("""{"big":12345678901234567890,"f":1.50,"html":"<b>&</b>","v":"${'$'}{EMAIL}"}""")
        assertTrue(formatted, "12345678901234567890" in formatted)
        assertTrue(formatted, "1.50" in formatted)
        assertTrue(formatted, "\"<b>&</b>\"" in formatted)
        assertTrue(formatted, "\"${'$'}{EMAIL}\"" in formatted)
    }

    @Test fun `text that isn't JSON is left alone`() {
        assertEquals(Result.NotJson, JsonBodyFormat.format("<html/>"))
        assertEquals(Result.NotJson, JsonBodyFormat.format(""))
        assertEquals("plain", JsonBodyFormat.formatOrKeep("plain"))
    }

    @Test fun `lenient JSON is reported, not rewritten`() {
        assertTrue(JsonBodyFormat.format("{a: 1}") is Result.Invalid)
        assertTrue(JsonBodyFormat.format("{'a': 1}") is Result.Invalid)
        assertTrue(JsonBodyFormat.format("""{"id": ${'$'}{ID}}""") is Result.Invalid)
        assertTrue(JsonBodyFormat.format("""{"a": 1} trailing""") is Result.Invalid)
        assertTrue(JsonBodyFormat.format("{broken") is Result.Invalid)
        assertEquals("{a: 1}", JsonBodyFormat.formatOrKeep("{a: 1}"))
    }
}
