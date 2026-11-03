package com.example.mockrules

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Applies the shared rules to OkHttp traffic. Nothing happens until rules are enabled; with nothing
 * enabled every request goes straight through.
 *
 * Matching mirrors Android Studio's Network Inspector: protocol and method must match; host, port,
 * path and query are wildcard patterns (`*` = anything, `?` = one character, empty = anything)
 * matched against the whole value. Every enabled rule that matches is applied, in file order.
 *
 * Unlike Studio, a matching rule that replaces the body answers on its own, without calling the
 * server, so tests don't depend on the server or the test account. Set [alwaysCallServer] to rewrite
 * the real response instead, the way Studio does.
 */
class MockRulesEngine {
    @Volatile private var rules: List<MockRule> = emptyList()
    @Volatile private var fileVariables: Map<String, String> = emptyMap()

    /** What's enabled: `team/file.json`, `team`, `[team] rule name`, or `*` for everything. */
    @Volatile var enabled: Set<String> = emptySet()

    /** Values that replace the files' variables, e.g. API_HOST for a particular device or server. */
    @Volatile var variableOverrides: Map<String, String> = emptyMap()

    @Volatile var alwaysCallServer: Boolean = false

    /** Runs once, on the first request (off the main thread), to load files and saved state. */
    @Volatile var loader: (() -> Unit)? = null
    private val loadLock = Any()

    /** Replaces the loaded rules. [files] maps `team/file.json` to its JSON. Returns files that failed to parse. */
    fun load(files: Map<String, String>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val all = mutableListOf<MockRule>()
        val vars = linkedMapOf<String, String>()
        for ((path, json) in files.toSortedMap()) {
            try {
                val f = MockRulesFormat.parse(path, json)
                all += f.rules
                for ((k, v) in f.variables) vars.putIfAbsent(k, v)
            } catch (e: Exception) {
                errors[path] = e.message ?: e.javaClass.simpleName
            }
        }
        rules = all
        fileVariables = vars
        return errors
    }

    val allRules: List<MockRule> get() = rules

    fun activeRules(): List<MockRule> {
        val on = enabled
        if (on.isEmpty()) return emptyList()
        return rules.filter { "*" in on || it.filePath in on || it.team in on || it.id in on }
    }

    fun variables(): Map<String, String> = fileVariables + variableOverrides

    /** The enabled rules matching [request], in order. */
    fun matching(request: Request): List<MockRule> {
        val active = activeRules()
        if (active.isEmpty()) return emptyList()
        val vars = variables()
        val url = request.url
        return active.filter { r ->
            val c = r.criteria
            c.protocol.equals(url.scheme, ignoreCase = true) &&
                (c.method.equals("ANY", ignoreCase = true) || c.method.equals(request.method, ignoreCase = true)) &&
                MockMatching.wildcard(resolve(c.host, vars), url.host) &&
                MockMatching.wildcard(resolve(c.port, vars), url.port.toString()) &&
                MockMatching.wildcard(resolve(c.path, vars), url.encodedPath) &&
                MockMatching.wildcard(resolve(c.query, vars), url.encodedQuery.orEmpty())
        }
    }

    fun intercept(chain: Interceptor.Chain): Response {
        ensureLoaded()
        val request = chain.request()
        val matches = matching(request)
        if (matches.isEmpty()) return chain.proceed(request)

        val vars = variables()
        var response = if (!alwaysCallServer && matches.any { it.replacesBody }) {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(JSON))
                .build()
        } else {
            chain.proceed(request)
        }
        for (rule in matches) response = apply(rule, response, vars)
        return response.newBuilder().header(HEADER, matches.joinToString(", ") { it.id }).build()
    }

    /** Runs [loader] if it hasn't run yet. Safe to call from any thread. */
    fun ensureLoaded() {
        if (loader == null) return
        synchronized(loadLock) {
            loader?.let {
                loader = null
                it()
            }
        }
    }

    private fun apply(rule: MockRule, response: Response, vars: Map<String, String>): Response {
        val b = response.newBuilder()

        rule.status?.let { s ->
            val newCode = resolve(s.newCode, vars).toIntOrNull()
            val find = resolve(s.findCode, vars)
            if (s.active && newCode != null && (find.isEmpty() || find == response.code.toString())) b.code(newCode)
        }

        // Header changes apply in order, each to the result of the one before.
        var headers = response.headers.toList()
        for (h in rule.headers) headers = when (h) {
            is MockHeaderOp.Add -> headers + (resolve(h.name, vars) to resolve(h.value, vars))
            is MockHeaderOp.Replace -> headers.map { (name, value) ->
                val nameOk = h.findName.isEmpty() || MockMatching.text(resolve(h.findName, vars), name, h.findNameRegex, ignoreCase = true)
                val valueOk = h.findValue.isEmpty() || MockMatching.text(resolve(h.findValue, vars), value, h.findValueRegex, ignoreCase = false)
                if (nameOk && valueOk) {
                    resolve(h.newName, vars).ifEmpty { name } to resolve(h.newValue, vars).ifEmpty { value }
                } else name to value
            }
        }
        if (rule.headers.isNotEmpty()) {
            b.headers(okhttp3.Headers.Builder().apply { headers.forEach { (n, v) -> addUnsafeNonAscii(n, v) } }.build())
        }

        if (rule.body.isNotEmpty()) {
            val original = response.body
            val contentType = original?.contentType() ?: JSON
            var text = original?.string().orEmpty()
            for (op in rule.body) text = when (op) {
                is MockBodyOp.Replace -> resolve(op.body, vars)
                is MockBodyOp.Modify -> {
                    val target = resolve(op.targetText, vars)
                    val replacement = resolve(op.newText, vars)
                    when {
                        target.isEmpty() -> text
                        op.regex -> Regex(target).replace(text, replacement)
                        else -> text.replace(target, replacement)
                    }
                }
            }
            b.body(text.toResponseBody(contentType))
            b.removeHeader("Content-Length")
        }
        return b.build()
    }

    private fun resolve(s: String, vars: Map<String, String>): String =
        if ('$' !in s) s else VAR.replace(s) { m -> vars[m.groupValues[1]] ?: m.value }

    companion object {
        /** Added to every mocked response, listing the rules applied. Handy when a test fails. */
        const val HEADER = "X-Mock-Rules"
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val VAR = Regex("""\$\{([A-Za-z0-9_]+)}""")
    }
}

/** The OkHttp side: add with `OkHttpClient.Builder().addInterceptor(MockRulesInterceptor(engine))`. */
class MockRulesInterceptor(private val engine: MockRulesEngine) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = engine.intercept(chain)
}

internal object MockMatching {
    /** Studio's wildcard match: `*` = any run of characters, `?` = one character, empty = anything. */
    fun wildcard(pattern: String, value: String): Boolean {
        if (pattern.isBlank()) return true
        val regex = buildString {
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) append(Regex.escape(literal.toString()))
                literal.clear()
            }
            for (ch in pattern) when (ch) {
                '*' -> { flush(); append(".*") }
                '?' -> { flush(); append(".") }
                else -> literal.append(ch)
            }
            flush()
        }
        return Regex(regex).matches(value)
    }

    fun text(pattern: String, value: String, regex: Boolean, ignoreCase: Boolean): Boolean =
        if (regex) {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).matches(value)
        } else {
            pattern.equals(value, ignoreCase)
        }
}
