package com.example.mockrules

import org.json.JSONArray
import org.json.JSONObject

/*
 * Reads the shared rules files (network-inspector-rules/<team>/<file>.json), the same format the
 * Android Studio plugin writes for Network Inspector → Rules. No Android or OkHttp types here.
 */

data class MockCriteria(
    val protocol: String = "HTTPS",
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val query: String = "",
    val method: String = "ANY",
)

data class MockStatus(val active: Boolean, val findCode: String, val newCode: String)

sealed class MockHeaderOp {
    data class Add(val name: String, val value: String) : MockHeaderOp()
    data class Replace(
        val findName: String, val findNameRegex: Boolean,
        val findValue: String, val findValueRegex: Boolean,
        val newName: String, val newValue: String,
    ) : MockHeaderOp()
}

sealed class MockBodyOp {
    data class Replace(val body: String) : MockBodyOp()
    data class Modify(val targetText: String, val regex: Boolean, val newText: String) : MockBodyOp()
}

data class MockRule(
    val team: String,
    val fileName: String,
    val name: String,
    val criteria: MockCriteria,
    val status: MockStatus?,
    val headers: List<MockHeaderOp>,
    val body: List<MockBodyOp>,
) {
    /** `team/file.json` */
    val filePath: String get() = "$team/$fileName"

    /** `[team] name`, the same name the rule has in Studio's Rules tab. */
    val id: String get() = "[$team] $name"

    /** A rule that replaces the whole body can answer without calling the server. */
    val replacesBody: Boolean get() = body.any { it is MockBodyOp.Replace }
}

class MockRulesFile(val rules: List<MockRule>, val variables: Map<String, String>)

object MockRulesFormat {
    /** Parses one file. [relPath] is `team/file.json`. Throws org.json.JSONException on bad JSON. */
    fun parse(relPath: String, json: String): MockRulesFile {
        val team = relPath.substringBefore('/')
        val fileName = relPath.substringAfter('/')
        val root = JSONObject(json)

        val vars = linkedMapOf<String, String>()
        root.optJSONArray("variables")?.objects()?.forEach { v ->
            val name = v.optString("name")
            // "local": true only declares the variable; each run supplies the value (--es var).
            if (name.isNotEmpty() && !v.optBoolean("local", false)) vars[name] = v.optString("value")
        }

        val rules = root.optJSONArray("rules")?.objects().orEmpty().mapNotNull { r ->
            val name = r.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val c = r.optJSONObject("criteria") ?: JSONObject()
            MockRule(
                team = team,
                fileName = fileName,
                name = name,
                criteria = MockCriteria(
                    protocol = c.optString("protocol", "HTTPS"),
                    host = c.optString("host"),
                    port = c.optString("port"),
                    path = c.optString("path"),
                    query = c.optString("query"),
                    method = c.optString("method", "ANY"),
                ),
                status = r.optJSONObject("statusCode")?.let {
                    MockStatus(it.optBoolean("active", true), it.optString("findCode"), it.optString("newCode"))
                },
                headers = r.optJSONArray("headers")?.objects().orEmpty().mapNotNull(::header),
                body = r.optJSONArray("body")?.objects().orEmpty().mapNotNull(::body),
            )
        }
        return MockRulesFile(rules, vars)
    }

    private fun header(h: JSONObject): MockHeaderOp? = when (h.optString("type", "add")) {
        "add" -> MockHeaderOp.Add(h.optString("name"), h.optString("value"))
        "replace" -> MockHeaderOp.Replace(
            h.optString("findName"), h.optBoolean("findNameRegex"),
            h.optString("findValue"), h.optBoolean("findValueRegex"),
            h.optString("newName"), h.optString("newValue"),
        )
        else -> null
    }

    private fun body(b: JSONObject): MockBodyOp? = when (b.optString("type", "replace")) {
        // A JSON body is stored as real JSON; a string body as a string.
        "replace" -> MockBodyOp.Replace(
            when (val v = b.opt("body")) {
                null, JSONObject.NULL -> ""
                is String -> v
                else -> v.toString()
            },
        )
        "modify" -> MockBodyOp.Modify(b.optString("targetText"), b.optBoolean("regex"), b.optString("newText"))
        else -> null
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
