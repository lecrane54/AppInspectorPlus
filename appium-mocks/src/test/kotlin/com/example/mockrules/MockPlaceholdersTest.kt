package com.example.mockrules

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockPlaceholdersTest {
    private val server = MockWebServer()
    private val engine = MockRulesEngine()
    private val client = OkHttpClient.Builder().addInterceptor(MockRulesInterceptor(engine)).build()

    @Before fun start() {
        server.start()
        engine.variableOverrides = mapOf("API_HOST" to server.hostName)
        engine.enabled = setOf("*")
    }

    @After fun stop() = server.shutdown()

    private fun post(path: String, json: String, vararg headers: Pair<String, String>): String {
        val req = Request.Builder().url(server.url(path)).post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> req.header(k, v) }
        return client.newCall(req.build()).execute().use { it.body!!.string() }
    }

    private fun placeholders(path: String, body: String? = null, vararg headers: Pair<String, String>): MockPlaceholders {
        val req = Request.Builder().url("https://api.example.com$path")
        if (body != null) req.post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> req.header(k, v) }
        return MockPlaceholders(req.build())
    }

    private fun rule(body: String) = """{ "version": 1, "rules": [ { "name": "Create order",
        "criteria": { "protocol": "HTTP", "host": "${'$'}{API_HOST}", "path": "/v1/orders", "method": "POST" },
        "statusCode": { "active": true, "findCode": "", "newCode": "201" },
        "body": [ { "type": "replace", "body": $body } ] } ] }"""

    @Test fun `a mocked create echoes what the app sent, with fresh ids and times`() {
        engine.load(mapOf("checkout/orders.json" to rule("""{ "id": "${'$'}{mock.uuid}", "sku": "${'$'}{request.body.items[0].sku}",
            "qty": "${'$'}{request.body.items[0].qty}", "items": "${'$'}{request.body.items}", "label": "Order ${'$'}{request.body.items[0].sku}",
            "ts": "${'$'}{mock.now.millis}", "createdAt": "${'$'}{mock.now}", "trace": "${'$'}{request.header.X-Trace-Id}" }""")))
        val text = post("/v1/orders", """{"items":[{"sku":"A-1","qty":2}]}""", "X-Trace-Id" to "t-9")
        val out = JSONObject(text)
        assertEquals(36, out.getString("id").length)
        assertEquals("A-1", out.getString("sku"))
        assertTrue(text, """"qty":2,""" in text.replace(" ", ""))
        assertEquals("A-1", out.getJSONArray("items").getJSONObject(0).getString("sku"))
        assertEquals(2, out.getJSONArray("items").getJSONObject(0).getInt("qty"))
        assertEquals("Order A-1", out.getString("label"))
        assertTrue(text, out.get("ts") is Number)
        assertTrue(out.getString("createdAt"), Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(out.getString("createdAt")))
        assertEquals("t-9", out.getString("trace"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `request parts are available`() {
        val p = placeholders("/v2/users/42/orders?page=3&sort=asc", headers = arrayOf("X-Env" to "qa"))
        assertEquals("GET", p.resolve("\${request.method}"))
        assertEquals("api.example.com", p.resolve("\${request.host}"))
        assertEquals("/v2/users/42/orders", p.resolve("\${request.path}"))
        assertEquals("42", p.resolve("\${request.path[2]}"))
        assertEquals("page=3&sort=asc", p.resolve("\${request.query}"))
        assertEquals("3", p.resolve("\${request.query.page}"))
        assertEquals("qa", p.resolve("\${request.header.X-Env}"))
        assertEquals("qa", p.resolve("\${request.header.x-env}"))
        assertEquals("https://api.example.com/v2/users/42/orders?page=3&sort=asc", p.resolve("\${request.url}"))
    }

    @Test fun `the request body is reachable whole, by path, and as nested JSON`() {
        val p = placeholders("/x", body = """{"user":{"name":"Ann","tags":["a","b"]},"n":5,"none":null}""")
        assertEquals("Ann", p.resolve("\${request.body.user.name}"))
        assertEquals("b", p.resolve("\${request.body.user.tags[1]}"))
        assertEquals("5", p.resolve("\${request.body.n}"))
        assertEquals("null", p.resolve("\${request.body.none}"))
        assertEquals("""["a","b"]""", p.resolve("\${request.body.user.tags}"))
        assertEquals("""{"t":["a","b"],"n":5}""", p.resolve("""{"t":"${'$'}{request.body.user.tags}","n":"${'$'}{request.body.n}"}"""))
        assertEquals("""{"user":{"name":"Ann","tags":["a","b"]},"n":5,"none":null}""".length, p.resolve("\${request.body}").length)
    }

    @Test fun `strings are escaped inside JSON but not elsewhere`() {
        val p = placeholders("/x", body = """{"name":"Ann \"The\" Lee"}""")
        assertEquals("""{"n":"Ann \"The\" Lee"}""", p.resolve("""{"n":"${'$'}{request.body.name}"}"""))
        assertEquals("""{"n":"Hi Ann \"The\" Lee"}""", p.resolve("""{"n":"Hi ${'$'}{request.body.name}"}"""))
        assertEquals("""Hello Ann "The" Lee""", p.resolve("Hello \${request.body.name}"))
        assertEquals("""{"n":"Ann \"The\" Lee"}""", p.resolve("""{"n":"${'$'}{request.body.name}"}""", json = true))
    }

    @Test fun `unknown or missing values are left alone`() {
        val p = placeholders("/x?a=1", body = "not json")
        assertEquals("\${request.query.b}", p.resolve("\${request.query.b}"))
        assertEquals("\${request.body.a}", p.resolve("\${request.body.a}"))
        assertEquals("\${request.path[9]}", p.resolve("\${request.path[9]}"))
        assertEquals("\${mock.nothing}", p.resolve("\${mock.nothing}"))
        assertEquals("\${API_HOST}", p.resolve("\${API_HOST}"))
        assertEquals("not json", p.resolve("\${request.body}"))
    }

    @Test fun `generated values`() {
        val p = placeholders("/x")
        assertNotEquals(p.resolve("\${mock.uuid}"), p.resolve("\${mock.uuid}"))
        assertTrue(p.resolve("\${mock.now.millis}").toLong() > 1_700_000_000_000L)
        assertTrue(p.resolve("\${mock.now.seconds}").toLong() in 1_700_000_000L..9_999_999_999L)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(p.resolve("\${mock.now:yyyy-MM-dd}")))
        repeat(20) { assertTrue(p.resolve("\${mock.random:1-6}").toInt() in 1..6) }
        assertTrue(p.resolve("\${mock.random}").toInt() in 100_000..999_999)
    }

    @Test fun `placeholders work in headers, status and modify ops`() {
        engine.load(
            mapOf("checkout/x.json" to """{ "version": 1, "rules": [ { "name": "Echo",
                "criteria": { "protocol": "HTTP", "host": "${'$'}{API_HOST}", "path": "/v1/echo/*", "method": "POST" },
                "statusCode": { "active": true, "findCode": "", "newCode": "${'$'}{request.header.X-Want}" },
                "headers": [ { "type": "add", "name": "X-Id", "value": "${'$'}{request.path[2]}" } ],
                "body": [ { "type": "modify", "targetText": "\"id\":0", "regex": false, "newText": "\"id\":\"${'$'}{request.path[2]}\"" } ] } ] }"""),
        )
        server.enqueue(MockResponse().setBody("""{"id":0}"""))
        client.newCall(
            Request.Builder().url(server.url("/v1/echo/77")).post("{}".toRequestBody()).header("X-Want", "202").build(),
        ).execute().use {
            assertEquals(202, it.code)
            assertEquals("77", it.header("X-Id"))
            assertEquals("""{"id":"77"}""", it.body!!.string())
        }
    }
}
