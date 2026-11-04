package com.example.mockrules

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MockRulesEngineTest {
    private val server = MockWebServer()
    private val engine = MockRulesEngine()
    private val client = OkHttpClient.Builder().addInterceptor(MockRulesInterceptor(engine)).build()

    @Before fun start() {
        server.start()
        // Rules use ${API_HOST}; point it at the local test server, as a test on a device would.
        engine.variableOverrides = mapOf("API_HOST" to server.hostName)
    }

    @After fun stop() = server.shutdown()

    private fun load(vararg files: Pair<String, String>) = engine.load(files.toMap())

    private fun get(path: String, method: String = "GET"): okhttp3.Response {
        val body = if (method == "GET") null else "{}".toRequestBody()
        return client.newCall(Request.Builder().url(server.url(path)).method(method, body).build()).execute()
    }

    private fun rule(name: String, criteria: String, extra: String = "") =
        """{ "name": "$name", "criteria": { "protocol": "HTTP", "host": "${'$'}{API_HOST}", $criteria } $extra }"""

    private fun file(vararg rules: String) = """{ "version": 1, "rules": [ ${rules.joinToString()} ] }"""

    private val profileReplaced = file(
        rule(
            "Profile error", """ "path": "/v1/profile", "method": "GET" """,
            """, "statusCode": { "active": true, "findCode": "", "newCode": "500" },
                 "body": [ { "type": "replace", "body": { "error": "internal_error" } } ] """,
        ),
    )

    @Test fun `nothing enabled means every request goes to the server`() {
        load("login/profile.json" to profileReplaced)
        server.enqueue(MockResponse().setBody("real"))
        get("/v1/profile").use {
            assertEquals(200, it.code)
            assertEquals("real", it.body!!.string())
            assertNull(it.header(MockRulesEngine.HEADER))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `an enabled body replacement answers without calling the server`() {
        load("login/profile.json" to profileReplaced)
        engine.enabled = setOf("login/profile.json")
        get("/v1/profile").use {
            assertEquals(500, it.code)
            assertEquals("""{"error":"internal_error"}""", it.body!!.string())
            assertEquals("[login] Profile error", it.header(MockRulesEngine.HEADER))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `alwaysCallServer rewrites the real response like Studio does`() {
        load("login/profile.json" to profileReplaced)
        engine.enabled = setOf("login")
        engine.alwaysCallServer = true
        server.enqueue(MockResponse().setBody("real"))
        get("/v1/profile").use {
            assertEquals(500, it.code)
            assertEquals("""{"error":"internal_error"}""", it.body!!.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `status, header and body-modify rules rewrite the real response`() {
        load(
            "checkout/flags.json" to file(
                rule(
                    "Premium", """ "path": "/v1/me" """,
                    """, "headers": [ { "type": "add", "name": "X-Test", "value": "yes" },
                                      { "type": "replace", "findName": "cache-control", "findValue": ".*", "findValueRegex": true,
                                        "newName": "Cache-Control", "newValue": "no-store" } ],
                         "body": [ { "type": "modify", "targetText": "\"premium\":false", "regex": false, "newText": "\"premium\":true" } ] """,
                ),
            ),
        )
        engine.enabled = setOf("[checkout] Premium")
        server.enqueue(MockResponse().setBody("""{"premium":false}""").setHeader("Cache-Control", "max-age=60"))
        get("/v1/me").use {
            assertEquals(200, it.code)
            assertEquals("yes", it.header("X-Test"))
            assertEquals("no-store", it.header("Cache-Control"))
            assertEquals("""{"premium":true}""", it.body!!.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `wildcards match path parameters and queries`() {
        load(
            "login/users.json" to file(
                rule("User", """ "path": "/v2/users/*", "query": "*type=admin*" """, """, "body": [ { "type": "replace", "body": "mock" } ]"""),
            ),
        )
        engine.enabled = setOf("*")
        get("/v2/users/42?page=1&type=admin").use { assertEquals("mock", it.body!!.string()) }

        server.enqueue(MockResponse().setBody("real"))
        get("/v2/users/42?type=member").use { assertEquals("real", it.body!!.string()) }

        server.enqueue(MockResponse().setBody("real"))
        get("/v2/users").use { assertEquals("real", it.body!!.string()) }
    }

    @Test fun `method and protocol must match`() {
        load("login/profile.json" to profileReplaced)
        engine.enabled = setOf("login/profile.json")
        server.enqueue(MockResponse().setBody("real"))
        get("/v1/profile", method = "POST").use { assertEquals("real", it.body!!.string()) }
    }

    @Test fun `a status rule with findCode only rewrites that code`() {
        load(
            "login/codes.json" to file(
                rule("Not found to gone", """ "path": "/x" """, """, "statusCode": { "active": true, "findCode": "404", "newCode": "410" }"""),
            ),
        )
        engine.enabled = setOf("login")
        server.enqueue(MockResponse().setResponseCode(404))
        get("/x").use { assertEquals(410, it.code) }
        server.enqueue(MockResponse().setResponseCode(200))
        get("/x").use { assertEquals(200, it.code) }
    }

    @Test fun `selectors pick files, teams, single rules, or everything`() {
        load(
            "login/a.json" to file(rule("A", """ "path": "/a" """)),
            "checkout/b.json" to file(rule("B", """ "path": "/b" """), rule("C", """ "path": "/c" """)),
        )
        fun active(vararg s: String): List<String> {
            engine.enabled = s.toSet()
            return engine.activeRules().map { it.id }
        }
        assertEquals(listOf("[checkout] B", "[checkout] C"), active("checkout/b.json"))
        assertEquals(listOf("[login] A"), active("login"))
        assertEquals(listOf("[checkout] C"), active("[checkout] C"))
        assertEquals(3, active("*").size)
        assertTrue(active().isEmpty())
    }

    @Test fun `bad files are reported and the rest still load`() {
        val errors = load("login/bad.json" to "{ nope", "login/good.json" to profileReplaced)
        assertEquals(setOf("login/bad.json"), errors.keys)
        assertEquals(1, engine.allRules.size)
    }

    @Test fun `file variables are used unless overridden`() {
        val f = """{ "variables": [ { "name": "API_HOST", "value": "api.example.com" } ], "rules": [] }"""
        engine.variableOverrides = emptyMap()
        load("login/vars.json" to f)
        assertEquals("api.example.com", engine.variables()["API_HOST"])
        engine.variableOverrides = mapOf("API_HOST" to "10.0.0.5")
        assertEquals("10.0.0.5", engine.variables()["API_HOST"])
    }

    @Test fun `declared local variables have no file value and come from overrides`() {
        val f = """{ "variables": [ { "name": "EMAIL", "local": true, "description": "QA account" } ], "rules": [] }"""
        engine.variableOverrides = emptyMap()
        load("login/vars.json" to f)
        assertEquals(null, engine.variables()["EMAIL"])
        engine.variableOverrides = mapOf("EMAIL" to "qa@example.com")
        assertEquals("qa@example.com", engine.variables()["EMAIL"])
    }

    @Test fun `scenarios parse with their rules and variables`() {
        val json = """{ "version": 1, "scenarios": [
            { "name": "checkout-declined", "enable": ["checkout/payment-declined.json", "[login] Session OK"],
              "variables": { "API_HOST": "staging.example.com" } },
            { "name": "", "enable": ["login"] } ] }"""
        val s = MockRulesFormat.parseScenarios(json)
        assertEquals(listOf("checkout-declined"), s.keys.toList())
        assertEquals(listOf("checkout/payment-declined.json", "[login] Session OK"), s.getValue("checkout-declined").enable)
        assertEquals(mapOf("API_HOST" to "staging.example.com"), s.getValue("checkout-declined").variables)
    }

    @Test fun `loader runs once, on the first request`() {
        var loads = 0
        engine.loader = { loads++; load("login/profile.json" to profileReplaced); engine.enabled = setOf("login") }
        get("/v1/profile").use { assertEquals(500, it.code) }
        get("/v1/profile").use { assertEquals(500, it.code) }
        assertEquals(1, loads)
    }

    @Test fun `wildcard matching follows Studio`() {
        assertTrue(MockMatching.wildcard("", "anything"))
        assertTrue(MockMatching.wildcard("/users/*", "/users/42"))
        assertTrue(MockMatching.wildcard("/v?/x", "/v2/x"))
        assertFalse(MockMatching.wildcard("/users/*", "/accounts/42"))
        assertTrue("regex characters are literal", MockMatching.wildcard("/a.b+c", "/a.b+c"))
        assertFalse(MockMatching.wildcard("/a.b", "/aXb"))
        assertFalse("whole value must match", MockMatching.wildcard("/users", "/users/42"))
    }

    @Test fun `the repo's example rules files parse`() {
        val root = File("../examples/network-inspector-rules")
        val files = root.walk().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        assertTrue(files.isNotEmpty())
        assertTrue(engine.load(files).isEmpty())
        assertTrue(engine.allRules.isNotEmpty())
    }
}
