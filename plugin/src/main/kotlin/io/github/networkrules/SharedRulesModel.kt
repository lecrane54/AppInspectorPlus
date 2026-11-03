package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.Method
import com.android.tools.idea.appinspection.inspectors.network.model.rules.Protocol
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData.BodyModifiedRuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData.BodyReplacedRuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData.HeaderAddedRuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData.HeaderReplacedRuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData.TransformationRuleData
import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleVariable
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.logger

/*
 * The committed file format: network-inspector-rules/<team>/<file>.json. It mirrors the fields of the Network
 * Inspector's Rules tab one to one, but in readable JSON: replacement bodies that are JSON are
 * stored as real JSON, not as escaped strings.
 *
 * All fields are nullable because Gson ignores Kotlin nullability.
 */

data class SharedRulesFile(
    var version: Int? = 1,
    var variables: MutableList<SharedVariable>? = mutableListOf(),
    var rules: MutableList<SharedRule>? = mutableListOf(),
)

data class SharedVariable(var name: String? = null, var value: String? = null)

data class SharedRule(
    var name: String? = null,
    var criteria: SharedCriteria? = SharedCriteria(),
    var statusCode: SharedStatusCode? = null,
    var headers: MutableList<SharedHeaderRule>? = mutableListOf(),
    var body: MutableList<SharedBodyRule>? = mutableListOf(),
)

data class SharedCriteria(
    var protocol: String? = "HTTPS",
    var host: String? = "",
    var port: String? = "",
    var path: String? = "",
    var query: String? = "",
    var method: String? = "ANY",
)

data class SharedStatusCode(
    var active: Boolean? = true,
    /** Only rewrite responses with this code; empty means any code. */
    var findCode: String? = "",
    var newCode: String? = "",
)

/** type = "add" (name, value) or "replace" (findName, findNameRegex, findValue, findValueRegex, newName, newValue). */
data class SharedHeaderRule(
    var type: String? = "add",
    var name: String? = null,
    var value: String? = null,
    var findName: String? = null,
    var findNameRegex: Boolean? = null,
    var findValue: String? = null,
    var findValueRegex: Boolean? = null,
    var newName: String? = null,
    var newValue: String? = null,
)

/** type = "replace" (body: a JSON value or a string) or "modify" (targetText, regex, newText). */
data class SharedBodyRule(
    var type: String? = "replace",
    var body: JsonElement? = null,
    var targetText: String? = null,
    var regex: Boolean? = null,
    var newText: String? = null,
)

object RuleMapper {
    private val LOG = logger<RuleMapper>()
    val gson get() = RulesLogic.gson

    fun toShared(rd: RuleData): SharedRule {
        val c = rd.criteria
        val sc = rd.statusCodeRuleData
        return SharedRule(
            name = rd.name,
            criteria = SharedCriteria(c.protocol.name, c.host, c.port, c.path, c.query, c.method.name),
            statusCode = if (sc.isActive || sc.newCode.isNotEmpty() || sc.findCode.isNotEmpty()) {
                SharedStatusCode(sc.isActive, sc.findCode, sc.newCode)
            } else null,
            headers = rd.headerRuleTableModel.items.mapNotNull(::headerToShared).toMutableList(),
            body = rd.bodyRuleTableModel.items.mapNotNull(::bodyToShared).toMutableList(),
        )
    }

    /** Builds a Studio rule. It has no listener yet; the caller attaches it to the live table. */
    fun toRuleData(s: SharedRule, id: Int, active: Boolean): RuleData {
        val rd = RuleData(id, s.name ?: "Shared rule $id", active)
        val sc = s.criteria ?: SharedCriteria()
        rd.criteria.apply {
            protocol = enumOr(sc.protocol, Protocol.HTTPS)
            host = sc.host.orEmpty()
            port = sc.port.orEmpty()
            path = sc.path.orEmpty()
            query = sc.query.orEmpty()
            method = enumOr(sc.method, Method.ANY)
        }
        s.statusCode?.let { code ->
            rd.statusCodeRuleData.apply {
                findCode = code.findCode.orEmpty()
                newCode = code.newCode.orEmpty()
                isActive = code.active ?: true
            }
        }
        rd.headerRuleTableModel.items = s.headers.orEmpty().mapNotNull(::headerFromShared).toMutableList()
        rd.bodyRuleTableModel.items = s.body.orEmpty().mapNotNull(::bodyFromShared).toMutableList()
        return rd
    }

    fun toShared(v: RuleVariable) = SharedVariable(v.name, v.value)
    fun toRuleVariable(v: SharedVariable) = RuleVariable(v.name.orEmpty(), v.value.orEmpty())

    private fun headerToShared(t: TransformationRuleData): SharedHeaderRule? = when (t) {
        is HeaderAddedRuleData -> SharedHeaderRule(type = "add", name = t.name, value = t.value)
        is HeaderReplacedRuleData -> SharedHeaderRule(
            type = "replace",
            findName = t.findName, findNameRegex = t.isFindNameRegex,
            findValue = t.findValue, findValueRegex = t.isFindValueRegex,
            newName = t.newName, newValue = t.newValue,
        )
        else -> null.also { LOG.warn("Skipping unknown header rule type ${t.javaClass.name}") }
    }

    private fun headerFromShared(h: SharedHeaderRule): TransformationRuleData? = when (h.type) {
        "add" -> HeaderAddedRuleData(h.name.orEmpty(), h.value.orEmpty())
        "replace" -> HeaderReplacedRuleData("", false, "", false, "", "").apply {
            // Set through setters, so we never depend on the constructor's parameter order.
            findName = h.findName.orEmpty()
            isFindNameRegex = h.findNameRegex ?: false
            findValue = h.findValue.orEmpty()
            isFindValueRegex = h.findValueRegex ?: false
            newName = h.newName.orEmpty()
            newValue = h.newValue.orEmpty()
        }
        else -> null.also { LOG.warn("Skipping header rule with unknown type '${h.type}'") }
    }

    private fun bodyToShared(t: TransformationRuleData): SharedBodyRule? = when (t) {
        is BodyReplacedRuleData -> SharedBodyRule(type = "replace", body = bodyTextToJson(t.body))
        is BodyModifiedRuleData -> SharedBodyRule(type = "modify", targetText = t.targetText, regex = t.isRegex, newText = t.newText)
        else -> null.also { LOG.warn("Skipping unknown body rule type ${t.javaClass.name}") }
    }

    private fun bodyFromShared(b: SharedBodyRule): TransformationRuleData? = when (b.type) {
        "replace" -> BodyReplacedRuleData(bodyJsonToText(b.body))
        "modify" -> BodyModifiedRuleData("", false, "").apply {
            targetText = b.targetText.orEmpty()
            isRegex = b.regex ?: false
            newText = b.newText.orEmpty()
        }
        else -> null.also { LOG.warn("Skipping body rule with unknown type '${b.type}'") }
    }

    /** JSON bodies are stored as real JSON for readable diffs; anything else is stored as a string. */
    fun bodyTextToJson(text: String): JsonElement {
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching { return JsonParser.parseString(t) }
        }
        return JsonPrimitive(text)
    }

    private fun bodyJsonToText(body: JsonElement?): String = when {
        body == null || body.isJsonNull -> ""
        body is JsonPrimitive && body.isString -> body.asString
        else -> gson.toJson(body)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name.equals(n, ignoreCase = true) } } ?: default
}
