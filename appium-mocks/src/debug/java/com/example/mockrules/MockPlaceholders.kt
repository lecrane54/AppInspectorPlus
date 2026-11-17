package com.example.mockrules

import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

/**
 * Dynamic values in a mocked response, so a fixture can answer with what the app sent or with a
 * fresh value per response:
 *
 *  - `${mock.uuid}`: a random UUID
 *  - `${mock.now}`: the time, ISO-8601 in UTC; `${mock.now.millis}` / `${mock.now.seconds}` since the
 *    epoch; `${mock.now:yyyy-MM-dd}` with a `SimpleDateFormat` pattern
 *  - `${mock.random}`: a random number, `${mock.random:1-6}` within a range (inclusive)
 *  - `${request.method}`, `${request.url}`, `${request.host}`, `${request.path}`, `${request.query}`
 *  - `${request.path[1]}`: one path segment (`/users/42` → `[0]` = `users`, `[1]` = `42`)
 *  - `${request.query.page}`: a query parameter; `${request.header.X-Trace-Id}`: a request header
 *  - `${request.body}`: the request body; `${request.body.items[0].sku}`: a value inside a JSON body
 *
 * Names contain a dot, so they never clash with `${VARIABLES}` and Studio's variable checks ignore
 * them. Studio sends them as plain text: they're resolved only by this interceptor. A placeholder
 * that can't be resolved (unknown name, missing query parameter) is left as it is.
 *
 * In a JSON body (text starting with `{` or `[`) a value keeps its type when a string holds just the
 * placeholder: `"qty": "${request.body.qty}"` gives `"qty": 2`, `"items": "${request.body.items}"`
 * echoes the whole list, and `"ts": "${mock.now.millis}"` is a number. A string value is escaped, so a
 * name with a quote in it doesn't break the body. Inside longer text (`"Order ${request.body.id}"`)
 * everything is inserted as text.
 */
class MockPlaceholders(private val request: Request) {
    private val body: String? by lazy {
        val b = request.body ?: return@lazy null
        if (b.isOneShot() || b.isDuplex()) return@lazy null
        runCatching { Buffer().also { b.writeTo(it) }.readUtf8() }.getOrNull()
    }
    private val bodyJson: Any? by lazy { body?.let { parseJson(it) } }

    /** [text] with every placeholder it contains filled in; [json] when [text] is JSON. */
    fun resolve(text: String, json: Boolean = looksLikeJson(text)): String {
        if ("\${" !in text) return text
        var out = text
        if (json) {
            // A string that is exactly one placeholder: the value replaces the whole literal, typed.
            out = QUOTED_REF.replace(out) { m ->
                val value = runCatching { value(m.groupValues[1], m.groupValues[2]) }.getOrNull() ?: return@replace m.value
                when (value) {
                    is JSONObject, is JSONArray, is Number, is Boolean -> value.toString()
                    else -> "\"" + jsonString(value.toString()) + "\""
                }
            }
        }
        return REF.replace(out) { m ->
            val value = runCatching { value(m.groupValues[1], m.groupValues[2]) }.getOrNull() ?: return@replace m.value
            if (json) jsonString(value.toString()) else value.toString()
        }
    }

    private fun value(kind: String, name: String): Any? = when (kind) {
        "mock" -> mock(name)
        "request" -> fromRequest(name)
        else -> null
    }

    private fun mock(name: String): Any? = when {
        name == "uuid" -> UUID.randomUUID().toString()
        name == "now" -> iso(Date())
        name == "now.millis" -> System.currentTimeMillis()
        name == "now.seconds" -> System.currentTimeMillis() / 1000
        name.startsWith("now:") -> SimpleDateFormat(name.removePrefix("now:"), Locale.US).apply { timeZone = UTC }.format(Date())
        name == "random" -> Random.nextInt(100_000, 1_000_000)
        name.startsWith("random:") -> {
            val (lo, hi) = name.removePrefix("random:").split('-', limit = 2).map { it.trim().toLong() }
            Random.nextLong(minOf(lo, hi), maxOf(lo, hi) + 1)
        }
        else -> null
    }

    private fun fromRequest(name: String): Any? {
        val url = request.url
        return when {
            name == "method" -> request.method
            name == "url" -> url.toString()
            name == "host" -> url.host
            name == "path" -> url.encodedPath
            name.startsWith("path[") -> {
                val index = name.removePrefix("path[").removeSuffix("]").trim().toInt()
                url.pathSegments.getOrNull(index)
            }
            name == "query" -> url.encodedQuery
            name.startsWith("query.") -> url.queryParameter(name.removePrefix("query."))
            name.startsWith("header.") -> request.header(name.removePrefix("header."))
            name == "body" -> body?.let { if (looksLikeJson(it)) bodyJson ?: it else it }
            name.startsWith("body.") || name.startsWith("body[") -> {
                val root = bodyJson ?: return null
                JsonPath.get(root, name.removePrefix("body").removePrefix("."))
            }
            else -> null
        }
    }

    companion object {
        private val REF = Regex("""\$\{(mock|request)\.([^}"]+)}""")
        private val QUOTED_REF = Regex(""""\$\{(mock|request)\.([^}"]+)}"""")
        private val UTC = TimeZone.getTimeZone("UTC")

        fun looksLikeJson(text: String): Boolean {
            val t = text.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }

        private fun iso(date: Date) =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = UTC }.format(date)

        /** [value] escaped as the inside of a JSON string literal. */
        private fun jsonString(value: String): String = JSONObject.quote(value).let { it.substring(1, it.length - 1) }

        private fun parseJson(text: String): Any? {
            val t = text.trim()
            return runCatching {
                when {
                    t.startsWith("{") -> JSONObject(t)
                    t.startsWith("[") -> JSONArray(t)
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** `items[0].sku` over org.json values. Only the names and indexes a rules file would need. */
internal object JsonPath {
    private val TOKEN = Regex("""([^.\[\]]+)|\[(\d+)]""")

    fun get(root: Any, path: String): Any? {
        var current: Any? = root
        for (m in TOKEN.findAll(path)) {
            val key = m.groupValues[1]
            val index = m.groupValues[2]
            current = when {
                index.isNotEmpty() -> (current as? JSONArray)?.opt(index.toInt())
                else -> (current as? JSONObject)?.opt(key)
            }
            if (current == null || current === JSONObject.NULL) return if (current === JSONObject.NULL) "null" else null
        }
        return current
    }
}
