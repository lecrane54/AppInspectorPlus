package io.github.networkrules

/**
 * Turns a Retrofit endpoint annotation into Network Inspector rule criteria.
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
     * Rule criteria for an annotation path. The path starts with `*`, so it matches whatever base URL
     * the service uses (including environment prefixes) without knowing it. Protocol, host and port
     * come from [base] (the Settings base URL) when set; an empty host matches any host.
     * A full-URL annotation is used as is.
     */
    fun resolve(base: BaseUrl?, annotationPath: String): Endpoint {
        val raw = annotationPath.trim()
        URL.find(raw)?.let { m ->
            return Endpoint(
                m.groupValues[1].uppercase(), m.groupValues[2], m.groupValues[3],
                wildcards(m.groupValues[4].ifEmpty { "/" }), queryCriteria(m.groupValues[5]),
            )
        }
        val relPath = raw.substringBefore('?').trimStart('/')
        val query = if ('?' in raw) raw.substringAfter('?') else ""
        val path = if (relPath.isEmpty()) "*" else "*/" + wildcards(relPath)
        return Endpoint(base?.protocol ?: "HTTPS", base?.host.orEmpty(), base?.port.orEmpty(), path, queryCriteria(query))
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
