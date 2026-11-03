package io.github.networkrules

/**
 * Turns a Retrofit endpoint (annotation + base URL) into Network Inspector rule criteria.
 * No IDE types, so it can be unit tested.
 *
 * Studio matches host, port, path and query with wildcards (`*` = anything, `?` = one character,
 * empty = anything) against the request URL, so path parameters become `*`.
 */
object EndpointLogic {
    /** Retrofit's HTTP method annotations. `@HTTP(method = "…", path = "…")` is handled separately. */
    val METHOD_ANNOTATIONS = linkedMapOf(
        "retrofit2.http.GET" to "GET",
        "retrofit2.http.POST" to "POST",
        "retrofit2.http.PUT" to "PUT",
        "retrofit2.http.DELETE" to "DELETE",
        "retrofit2.http.PATCH" to "PATCH",
        "retrofit2.http.HEAD" to "HEAD",
        "retrofit2.http.OPTIONS" to "OPTIONS",
    )
    const val HTTP_ANNOTATION = "retrofit2.http.HTTP"

    /** Methods Studio's rules can match; anything else falls back to ANY. */
    val STUDIO_METHODS = listOf("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "TRACE", "CONNECT", "OPTIONS")

    data class BaseUrl(val protocol: String, val host: String, val port: String, val path: String)

    data class Endpoint(val protocol: String, val host: String, val port: String, val path: String, val query: String)

    // Host may be a rule variable such as ${API_HOST}.
    private val URL = Regex("""^(https?)://([^/:?#]+)(?::([0-9]+|\$\{[A-Za-z0-9_]+}))?([^?#]*)(?:\?([^#]*))?""", RegexOption.IGNORE_CASE)
    private val PATH_PARAM = Regex("""\{[^}/]+}""")

    /** Parses a Retrofit base URL such as `https://api.example.com/v2/`. Null if it isn't http(s). */
    fun parseBaseUrl(url: String): BaseUrl? {
        val m = URL.find(url.trim()) ?: return null
        val path = m.groupValues[4].ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
        return BaseUrl(m.groupValues[1].uppercase(), m.groupValues[2], m.groupValues[3], path)
    }

    /**
     * Resolves an annotation path the way Retrofit does: a full URL replaces the base URL, a path
     * starting with `/` replaces the base path, and anything else is appended to the base path.
     * An empty path (an `@Url` parameter) matches anything under the base path.
     */
    fun resolve(base: BaseUrl?, annotationPath: String): Endpoint {
        val raw = annotationPath.trim()
        URL.find(raw)?.let { m ->
            return Endpoint(
                m.groupValues[1].uppercase(), m.groupValues[2], m.groupValues[3],
                wildcards(m.groupValues[4].ifEmpty { "/" }), queryCriteria(m.groupValues[5]),
            )
        }
        val relPath = raw.substringBefore('?')
        val query = if ('?' in raw) raw.substringAfter('?') else ""
        val basePath = base?.path ?: "/"
        val path = when {
            raw.isEmpty() -> "$basePath*"
            relPath.startsWith("/") -> relPath
            else -> basePath + relPath
        }
        return Endpoint(base?.protocol ?: "HTTPS", base?.host.orEmpty(), base?.port.orEmpty(), wildcards(path), queryCriteria(query))
    }

    /** A piece of a base URL expression found in code: fixed text, or something only known at runtime. */
    sealed class UrlPart {
        data class Text(val value: String) : UrlPart()

        /** e.g. `BaseUrl(context).secured`: environment dependent, so it can't be read from code. */
        data class Dynamic(val source: String) : UrlPart()
    }

    /** Joins adjacent text parts and drops empty ones. */
    fun normalize(parts: List<UrlPart>): List<UrlPart> {
        val out = mutableListOf<UrlPart>()
        for (p in parts) {
            val last = out.lastOrNull()
            when {
                p is UrlPart.Text && p.value.isEmpty() -> Unit
                p is UrlPart.Text && last is UrlPart.Text -> out[out.lastIndex] = UrlPart.Text(last.value + p.value)
                else -> out += p
            }
        }
        return out
    }

    /** For the dialog: `BaseUrl(context).secured + "v1.0/drivewise/api"`. */
    fun describe(parts: List<UrlPart>): String = normalize(parts).joinToString(" + ") {
        when (it) {
            is UrlPart.Text -> "\"${it.value}\""
            is UrlPart.Dynamic -> it.source
        }
    }

    /**
     * Builds the rule criteria for an endpoint whose service base URL was found in code.
     *
     * The base expression's leading runtime part (e.g. `BaseUrl(context).secured`) is the environment:
     * it becomes [environment] (the Settings base URL) when one is set, and a `*` wildcard otherwise,
     * so the rule matches every environment. Later runtime parts become `*` too. Fixed text from code
     * is kept, and the annotation path is joined after it with one `/`. A base that's fully constant
     * in code (`"https://api.example.com/v1/"`) is used as is, and a full-URL annotation wins over everything.
     */
    fun resolveService(environment: BaseUrl?, service: List<UrlPart>, annotationPath: String): Endpoint {
        val raw = annotationPath.trim()
        if (URL.find(raw) != null) return resolve(null, raw)

        val parts = normalize(service)
        val head = parts.firstOrNull()
        val rendered = { ps: List<UrlPart> -> ps.joinToString("") { if (it is UrlPart.Text) it.value else "*" } }

        val relPath = raw.substringBefore('?')
        val query = if ('?' in raw) raw.substringAfter('?') else ""

        // 1) The code has the whole URL, scheme and host included.
        if (head is UrlPart.Text && head.value.startsWith("http", ignoreCase = true)) {
            val m = URL.find(rendered(parts))
            if (m != null) {
                val path = join(m.groupValues[4], relPath)
                return Endpoint(m.groupValues[1].uppercase(), m.groupValues[2], m.groupValues[3], path, queryCriteria(query))
            }
        }

        // 2) The code adds a path after an environment-dependent base.
        val rest = if (head is UrlPart.Dynamic) parts.drop(1) else parts
        val envPath = environment?.path?.takeIf { it != "/" }
        // No environment path known: `*` matches whatever prefix the environment adds (or none).
        val basePath = join(envPath ?: "*", rendered(rest))
        return Endpoint(
            environment?.protocol ?: "HTTPS",
            environment?.host.orEmpty(),
            environment?.port.orEmpty(),
            join(basePath, relPath),
            queryCriteria(query),
        )
    }

    /** `a/` + `/b` → `a/b`, with `{param}` → `*` and repeated slashes collapsed. */
    private fun join(base: String, path: String): String {
        val joined = when {
            base.isEmpty() -> path
            path.isEmpty() -> base
            else -> base.trimEnd('/') + "/" + path.trimStart('/')
        }
        val collapsed = wildcards(joined).replace(Regex("/{2,}"), "/")
        return if (collapsed.startsWith("/") || collapsed.startsWith("*")) collapsed else "/$collapsed"
    }

    /** `{id}` → `*`. */
    fun wildcards(path: String): String = PATH_PARAM.replace(path, "*")

    /** A fixed query from the annotation; `*` around it so extra `@Query` parameters still match. */
    fun queryCriteria(query: String): String = if (query.isBlank()) "" else "*$query*"

    fun studioMethod(method: String): String = method.uppercase().takeIf { it in STUDIO_METHODS } ?: "ANY"

    /** e.g. `GET /v1/users/{id}`. */
    fun ruleName(method: String, annotationPath: String): String =
        "${method.uppercase()} ${annotationPath.trim().ifEmpty { "(dynamic URL)" }}"

    /** [name], or `name (2)`, `name (3)`… if it's taken. */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { it !in taken }
    }
}
