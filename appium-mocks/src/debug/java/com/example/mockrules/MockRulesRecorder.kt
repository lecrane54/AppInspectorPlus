package com.example.mockrules

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Record mode: every response that came from the server becomes a candidate rule in a file with
 * the shared rules format, so one run through a flow (by hand or by a test) gives fixtures to pull
 * off the device, review and commit. Responses the mocks answered themselves aren't recorded.
 *
 * Each rule gets the request's protocol, host (as `${VARIABLE}` when a variable has that value),
 * explicit port, method, and path with IDs (numbers, UUIDs, long hex) as `*`, like
 * **Create Network Rule from Response…** in Studio; the status code; and the body, as real JSON
 * when it is JSON. Headers are not recorded (they're where tokens live). A second response for the
 * same method and path gets ` (2)`, ` (3)`… so a polling flow keeps every state.
 *
 * No Android types: [sink] receives the whole file text after each recorded response.
 */
class MockRulesRecorder(
    /** `team/file.json`, where the pulled file belongs in `network-inspector-rules/`. */
    val target: String,
    private val variables: () -> Map<String, String>,
    private val sink: (String) -> Unit,
    /** The file's current text, when a recording continues after the app restarted. */
    existingJson: String? = null,
) {
    private val rules = mutableListOf<JSONObject>()

    init {
        existingJson?.let { json ->
            runCatching { JSONObject(json).optJSONArray("rules") }.getOrNull()?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { rules += it }
            }
        }
    }

    val count: Int get() = synchronized(rules) { rules.size }

    /** Adds a rule for [response] (the server's answer to [request]) and writes the file. */
    fun record(request: Request, response: Response) {
        val body = body(response) ?: return
        val rule = rule(request, response.code, body)
        val text = synchronized(rules) {
            rule.put("name", uniqueName(rule.getString("name")))
            rules += rule
            text()
        }
        sink(text)
    }

    /** The file's text: `{ "version": 1, "rules": [...] }`, indented. */
    fun text(): String = synchronized(rules) {
        JSONObject().put("version", 1).put("rules", JSONArray(rules)).toString(2)
    }

    private fun uniqueName(base: String): String {
        val taken = rules.map { it.optString("name") }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    private fun rule(request: Request, code: Int, body: Any): JSONObject {
        val url = request.url
        val path = wildcardIds(url.encodedPath)
        val criteria = JSONObject()
            .put("protocol", url.scheme.uppercase())
            .put("host", hostPattern(url))
            .put("port", if (url.port == HttpUrl.defaultPort(url.scheme)) "" else url.port.toString())
            .put("path", path)
            .put("query", "")
            .put("method", request.method.uppercase())
        return JSONObject()
            .put("name", "${request.method.uppercase()} $path")
            .put("criteria", criteria)
            .put("statusCode", JSONObject().put("active", true).put("findCode", "").put("newCode", code.toString()))
            .put("headers", JSONArray())
            .put("body", JSONArray().put(JSONObject().put("type", "replace").put("body", body)))
    }

    /** The host, or `${NAME}` when a variable (file or override) holds exactly this host. */
    private fun hostPattern(url: HttpUrl): String {
        val host = url.host
        val name = variables().entries.firstOrNull { it.value.equals(host, ignoreCase = true) }?.key
        return if (name != null) "\${$name}" else host
    }

    /** The body as a JSONObject/JSONArray when it's JSON, as a string otherwise; null when it can't be recorded. */
    private fun body(response: Response): Any? {
        val raw = response.body ?: return ""
        val type = raw.contentType()
        if (type != null && !isText(type.type, type.subtype)) return null
        val peek = response.peekBody((MAX_BODY + 1).toLong())
        val bytes = peek.bytes()
        if (bytes.size > MAX_BODY) return null
        if (type == null && bytes.any { it in 0..8 || it in 14..31 }) return null
        val text = String(bytes, type?.charset() ?: Charsets.UTF_8)
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrDefault(text)
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrDefault(text)
            else -> text
        }
    }

    companion object {
        /** Bigger bodies (images, downloads) are skipped. */
        const val MAX_BODY = 1024 * 1024

        private val UUID = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
        private val NUMBER = Regex("""^\d+$""")
        private val LONG_HEX = Regex("""^[0-9a-fA-F]{16,}$""")

        fun isId(segment: String): Boolean = NUMBER.matches(segment) || UUID.matches(segment) || LONG_HEX.matches(segment)

        /** `/users/123/orders/550e8400-…` → `/users/STAR/orders/STAR` (with `*`). */
        fun wildcardIds(path: String): String = path.split('/').joinToString("/") { if (isId(it)) "*" else it }

        private fun isText(type: String, subtype: String): Boolean =
            type == "text" || subtype.contains("json") || subtype.contains("xml") ||
                subtype.contains("javascript") || subtype.contains("x-www-form-urlencoded") || subtype.endsWith("+json")

        /** `team/file.json` from what a test passed: adds `.json`, puts a bare name under `recorded/`. */
        fun normalizeTarget(raw: String): String? {
            var t = raw.trim().trim('/')
            if (t.isEmpty() || ".." in t || t.count { it == '/' } > 1) return null
            if ('/' !in t) t = "recorded/$t"
            if (!t.endsWith(".json", ignoreCase = true)) t += ".json"
            return t
        }
    }
}
