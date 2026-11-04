package io.github.networkrules

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLogicTest {
    @Test fun `criteria come from the captured URL with IDs as wildcards`() {
        val c = TrafficLogic.criteria("https://api.example.com/v1/users/123/orders/550e8400-e29b-41d4-a716-446655440000?page=2")
        assertEquals("HTTPS", c.protocol)
        assertEquals("api.example.com", c.host)
        assertEquals("", c.port)
        assertEquals("/v1/users/*/orders/*", c.path)
        assertEquals("", c.query) // empty matches any query
    }

    @Test fun `explicit ports and http are kept, and IDs can be left as they are`() {
        val c = TrafficLogic.criteria("http://10.0.2.2:8080/items/42", wildcardIds = false)
        assertEquals("HTTP", c.protocol)
        assertEquals("8080", c.port)
        assertEquals("/items/42", c.path)
    }

    @Test fun `only id-like segments become wildcards`() {
        assertEquals("/v2/*/profile/abc", TrafficLogic.wildcardIds("/v2/0123456789abcdef0123/profile/abc"))
        assertEquals("/", TrafficLogic.wildcardIds("/"))
    }

    @Test fun `JSON bodies are pretty-printed, other text is kept`() {
        assertEquals("{\n  \"a\": 1\n}", TrafficLogic.editableBody("""{"a":1}"""))
        assertEquals("<html/>", TrafficLogic.editableBody("<html/>"))
        assertEquals("{broken", TrafficLogic.editableBody("{broken"))
    }

    @Test fun `rule names read like the request`() {
        assertEquals("GET /v1/users/*", TrafficLogic.ruleName("get", "/v1/users/*"))
    }
}
