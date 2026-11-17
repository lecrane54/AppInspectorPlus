package com.example.mockrules

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockRulesRecorderTest {
    private val server = MockWebServer()
    private val engine = MockRulesEngine()
    private val client = OkHttpClient.Builder().addInterceptor(MockRulesInterceptor(engine)).build()
    private var written: String? = null

    @Before fun start() {
        server.start()
        engine.variableOverrides = mapOf("API_HOST" to server.hostName)
        engine.recorder = MockRulesRecorder("checkout/flow.json", { engine.variables() }, { written = it })
    }

    @After fun stop() = server.shutdown()

    private fun call(path: String, method: String = "GET"): okhttp3.Response {
        val body = if (method == "GET") null else "{}".toRequestBody()
        return client.newCall(Request.Builder().url(server.url(path)).method(method, body).build()).execute()
    }

    private fun rules() = JSONObject(written!!).getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `a server response becomes a rule in the shared format, and the app still gets the body`() {
        server.enqueue(MockResponse().setBody("""{"id":42,"status":"pending"}""").setResponseCode(201).setHeader("Content-Type", "application/json"))
        call("/v1/orders/42", "POST").use { assertEquals("""{"id":42,"status":"pending"}""", it.body!!.string()) }

        val file = JSONObject(written!!)
        assertEquals(1, file.getInt("version"))
        val r = rules().single()
        assertEquals("POST /v1/orders/*", r.getString("name"))
        val c = r.getJSONObject("criteria")
        assertEquals("HTTP", c.getString("protocol"))
        assertEquals("\${API_HOST}", c.getString("host"))
        assertEquals(server.port.toString(), c.getString("port"))
        assertEquals("/v1/orders/*", c.getString("path"))
        assertEquals("", c.getString("query"))
        assertEquals("POST", c.getString("method"))
        assertEquals("201", r.getJSONObject("statusCode").getString("newCode"))
        assertEquals(0, r.getJSONArray("headers").length())
        val body = r.getJSONArray("body").getJSONObject(0)
        assertEquals("replace", body.getString("type"))
        assertEquals(42, body.getJSONObject("body").getInt("id"))

        // What was recorded loads back as a rule that answers on its own.
        val replay = MockRulesEngine().apply { variableOverrides = mapOf("API_HOST" to server.hostName); enabled = setOf("*") }
        assertTrue(replay.load(mapOf("checkout/flow.json" to written!!)).isEmpty())
        assertEquals(1, replay.allRules.size)
        assertTrue(replay.allRules.single().replacesBody)
    }

    @Test fun `repeat calls to the same endpoint are numbered, and the recording survives a restart`() {
        server.enqueue(MockResponse().setBody("""{"state":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"done"}"""))
        call("/v1/jobs/7").close()
        call("/v1/jobs/8").close()
        assertEquals(listOf("GET /v1/jobs/*", "GET /v1/jobs/* (2)"), rules().map { it.getString("name") })

        // The app restarts: a new recorder continues the same file.
        engine.recorder = MockRulesRecorder("checkout/flow.json", { engine.variables() }, { written = it }, existingJson = written)
        assertEquals(2, engine.recorder!!.count)
        server.enqueue(MockResponse().setBody("""{"state":"failed"}"""))
        call("/v1/jobs/9").close()
        assertEquals(listOf("GET /v1/jobs/*", "GET /v1/jobs/* (2)", "GET /v1/jobs/* (3)"), rules().map { it.getString("name") })
    }

    @Test fun `responses the mocks answered are not recorded, rewritten real ones are`() {
        engine.load(
            mapOf("login/a.json" to """{ "version": 1, "rules": [
                { "name": "Local", "criteria": { "protocol": "HTTP", "host": "${'$'}{API_HOST}", "path": "/local" },
                  "body": [ { "type": "replace", "body": "mock" } ] },
                { "name": "Code only", "criteria": { "protocol": "HTTP", "host": "${'$'}{API_HOST}", "path": "/real" },
                  "statusCode": { "active": true, "findCode": "", "newCode": "500" } } ] }"""),
        )
        engine.enabled = setOf("*")
        call("/local").close()
        assertNull(written)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        call("/real").use { assertEquals(500, it.code) }
        val r = rules().single()
        assertEquals("200", r.getJSONObject("statusCode").getString("newCode"))
        assertEquals(true, r.getJSONArray("body").getJSONObject(0).getJSONObject("body").getBoolean("ok"))
    }

    @Test fun `text, empty and binary bodies`() {
        server.enqueue(MockResponse().setBody("plain text").setHeader("Content-Type", "text/plain"))
        call("/text").close()
        assertEquals("plain text", rules().last().getJSONArray("body").getJSONObject(0).getString("body"))

        server.enqueue(MockResponse().setResponseCode(204))
        call("/empty").close()
        assertEquals("", rules().last().getJSONArray("body").getJSONObject(0).getString("body"))
        assertEquals("204", rules().last().getJSONObject("statusCode").getString("newCode"))

        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, 1, 2, 3))).setHeader("Content-Type", "image/png"))
        call("/image").close()
        assertEquals(2, rules().size)

        server.enqueue(MockResponse().setBody("x".repeat(MockRulesRecorder.MAX_BODY + 1)).setHeader("Content-Type", "text/plain"))
        call("/huge").close()
        assertEquals(2, rules().size)
    }

    @Test fun `a plain host is kept when no variable has that value`() {
        engine.variableOverrides = emptyMap()
        server.enqueue(MockResponse().setBody("{}"))
        call("/x").close()
        assertEquals(server.hostName, rules().single().getJSONObject("criteria").getString("host"))
    }

    @Test fun `targets are normalized`() {
        assertEquals("checkout/flow.json", MockRulesRecorder.normalizeTarget(" checkout/flow.json "))
        assertEquals("checkout/flow.json", MockRulesRecorder.normalizeTarget("checkout/flow"))
        assertEquals("recorded/flow.json", MockRulesRecorder.normalizeTarget("flow"))
        assertNull(MockRulesRecorder.normalizeTarget(""))
        assertNull(MockRulesRecorder.normalizeTarget("../x.json"))
        assertNull(MockRulesRecorder.normalizeTarget("a/b/c.json"))
        assertEquals("/users/*/orders/*", MockRulesRecorder.wildcardIds("/users/123/orders/9f8e7d6c5b4a39281716151413121110"))
    }
}
