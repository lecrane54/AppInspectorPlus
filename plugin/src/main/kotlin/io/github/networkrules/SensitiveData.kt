package io.github.networkrules

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Finds personal or secret values typed straight into rules (an email in a body, a token in a
 * header) before they're written to a team file, and swaps them for `${NAME}` variables.
 * Kept free of Studio types so it can be unit tested.
 */
object SensitiveData {
    enum class Kind(val label: String, val baseName: String) {
        EMAIL("Email address", "EMAIL"),
        TOKEN("Token", "TOKEN"),
        SECRET_HEADER("Secret header value", "AUTH"),
    }

    /** [literal] was found in rule [rule] at [where] (e.g. `headers[0].value`, `body[0].body.user.email`). */
    data class Finding(val rule: String, val where: String, val literal: String, val kind: Kind)

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*""")
    private val BEARER = Regex("""(?i)\b(?:bearer|token|basic)\s+([A-Za-z0-9._~+/=-]{16,})""")
    private val SECRET_HEADER = Regex("""(?i)^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|apikey|x-auth-token|x-access-token|.*secret.*|.*password.*|.*token.*)$""")

    /** Top-level rule fields that are never sent anywhere and aren't worth flagging. */
    private val SKIPPED = setOf("name", "protocol", "method", "type", "port", "statusCode")

    fun find(rules: Map<String, SharedRule>): List<Finding> =
        rules.flatMap { (label, rule) -> find(label, rule) }

    fun find(label: String, rule: SharedRule): List<Finding> {
        val out = linkedSetOf<Finding>()
        val tree = RulesLogic.gson.toJsonTree(rule).asJsonObject
        for ((key, value) in tree.entrySet()) {
            if (key in SKIPPED) continue
            walk(value, key) { where, text -> scan(label, where, text, out) }
        }
        // A secret header's whole value, even when it doesn't look like a token.
        rule.headers.orEmpty().forEachIndexed { i, h ->
            val (name, value, field) = if (h.type == "replace") Triple(h.newName ?: h.findName, h.newValue, "newValue") else Triple(h.name, h.value, "value")
            if (name != null && value != null && SECRET_HEADER.matches(name.trim()) && value.isNotBlank() && "\${" !in value) {
                val literal = BEARER.find(value)?.groupValues?.get(1) ?: value.trim()
                if (out.none { it.literal == literal }) out += Finding(label, "headers[$i].$field ($name)", literal, Kind.SECRET_HEADER)
            }
        }
        return out.toList()
    }

    private fun walk(e: JsonElement, path: String, visit: (String, String) -> Unit) {
        when {
            e.isJsonPrimitive && e.asJsonPrimitive.isString -> visit(path, e.asString)
            e is JsonObject -> for ((k, v) in e.entrySet()) walk(v, "$path.$k", visit)
            e is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]", visit) }
        }
    }

    private fun scan(label: String, where: String, text: String, out: MutableSet<Finding>) {
        // A body stored as a string may itself be JSON; scanning the raw text covers it either way.
        for (m in EMAIL.findAll(text)) out += Finding(label, where, m.value, Kind.EMAIL)
        for (m in JWT.findAll(text)) out += Finding(label, where, m.value, Kind.TOKEN)
        for (m in BEARER.findAll(text)) if (!JWT.matches(m.groupValues[1])) out += Finding(label, where, m.groupValues[1], Kind.TOKEN)
    }

    /**
     * A variable name for each distinct literal: an existing variable that already holds that exact
     * value, otherwise EMAIL, EMAIL_2, TOKEN… not clashing with [taken] names.
     */
    fun suggestNames(findings: List<Finding>, existing: Map<String, String>, taken: Set<String>): Map<String, String> {
        val used = taken.toMutableSet()
        val out = linkedMapOf<String, String>()
        for (f in findings) {
            if (f.literal in out) continue
            val reuse = existing.entries.firstOrNull { it.value == f.literal }?.key
            val name = reuse ?: generateSequence(1) { it + 1 }
                .map { if (it == 1) f.kind.baseName else "${f.kind.baseName}_$it" }
                .first { it !in used }
            used += name
            out[f.literal] = name
        }
        return out
    }

    /** [rule] with each literal replaced by `${NAME}`. The rule's name is left as is. */
    fun replace(rule: SharedRule, replacements: Map<String, String>): SharedRule {
        if (replacements.isEmpty()) return rule
        var json = RulesLogic.gson.toJson(rule)
        // Longest first, so a literal that contains another is replaced whole.
        for ((literal, name) in replacements.entries.sortedByDescending { it.key.length }) {
            val escaped = RulesLogic.gson.toJson(literal).removeSurrounding("\"")
            json = json.replace(escaped, "\${$name}")
        }
        return RulesLogic.gson.fromJson(json, SharedRule::class.java).copy(name = rule.name)
    }
}
