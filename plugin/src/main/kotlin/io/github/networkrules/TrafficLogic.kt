package io.github.networkrules

import com.google.gson.JsonParser
import java.net.URI

/**
 * Turns a request captured in Network Inspector into rule criteria and a response body.
 * No IDE types, so it can be unit tested.
 */
object TrafficLogic {
    private val UUID = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    private val NUMBER = Regex("""^\d+$""")
    private val LONG_HEX = Regex("""^[0-9a-fA-F]{16,}$""")

    /** A path segment that's an ID (number, UUID, long hex) and differs between requests. */
    fun isId(segment: String): Boolean = NUMBER.matches(segment) || UUID.matches(segment) || LONG_HEX.matches(segment)

    /** `/users/123/orders/550e8400-…` → `/users/STAR/orders/STAR` (with `*`). */
    fun wildcardIds(path: String): String = path.split('/').joinToString("/") { if (isId(it)) "*" else it }

    /**
     * Criteria matching [url]: its protocol, host and explicit port, the path with IDs as `*` when
     * [wildcardIds], and any query (empty matches any, so extra parameters don't matter).
     */
    fun criteria(url: String, wildcardIds: Boolean = true): EndpointLogic.Endpoint {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val scheme = uri?.scheme?.uppercase()?.takeIf { it == "HTTP" || it == "HTTPS" } ?: "HTTPS"
        val path = uri?.rawPath?.ifEmpty { "/" } ?: "/"
        return EndpointLogic.Endpoint(
            protocol = scheme,
            host = uri?.host.orEmpty(),
            port = uri?.port?.takeIf { it > 0 }?.toString().orEmpty(),
            path = if (wildcardIds) wildcardIds(path) else path,
            query = "",
        )
    }

    /** e.g. `GET /users/STAR` (with `*`), from the criteria path. */
    fun ruleName(method: String, criteriaPath: String): String = "${method.uppercase()} $criteriaPath"

    /** The captured body, pretty-printed when it's JSON so it's easy to edit. */
    fun editableBody(text: String): String {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text
        return runCatching { RulesLogic.gson.toJson(JsonParser.parseString(trimmed)) }.getOrDefault(text)
    }
}
