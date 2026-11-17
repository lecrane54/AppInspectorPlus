package io.github.networkrules

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * Pretty-printing for response bodies typed into rule dialogs. Kept free of IDE types so it can be
 * unit tested; [JsonBodyFormatter] wires it into the dialogs.
 */
object JsonBodyFormat {
    sealed interface Result {
        data class Formatted(val text: String) : Result
        /** Not JSON at all (plain text, XML, empty…): left alone. */
        data object NotJson : Result
        /** Looks like JSON but doesn't parse; [message] says where. */
        data class Invalid(val message: String) : Result
    }

    /**
     * [text] pretty-printed with two-space indents, when it's a JSON object or array. Parsing is
     * strict, so text Gson would otherwise "fix" (unquoted keys, single quotes, an unquoted
     * `${VAR}`) is reported rather than silently rewritten.
     */
    fun format(text: String): Result {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return Result.NotJson
        return try {
            val reader = JsonReader(StringReader(trimmed)).apply { isLenient = false }
            val element = RulesLogic.gson.getAdapter(JsonElement::class.java).read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonParseException("Unexpected text after the JSON at ${reader.path}")
            Result.Formatted(RulesLogic.gson.toJson(element))
        } catch (e: Exception) {
            Result.Invalid(e.message?.substringBefore("\nSee ")?.trim().orEmpty().ifEmpty { "Not valid JSON" })
        }
    }

    /** [text] formatted when it's valid JSON, otherwise unchanged. */
    fun formatOrKeep(text: String): String = (format(text) as? Result.Formatted)?.text ?: text
}
