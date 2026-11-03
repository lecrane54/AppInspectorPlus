package io.github.networkrules

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement

/** What a Retrofit interface method tells us about its endpoint. */
class RetrofitEndpoint(
    val method: String,
    val path: String,
    /** The response type after unwrapping Call/Response/Single/…; null means no body (Unit, Completable…). */
    val responseType: PsiType?,
    val methodName: String,
    /** The Retrofit interface the method belongs to. */
    val service: PsiClass?,
)

/** Reads Retrofit annotations with UAST, so Kotlin and Java interfaces both work. Call inside a read action. */
object RetrofitEndpoints {
    /** The Retrofit endpoint around [element] (e.g. the caret), or null. */
    fun at(element: PsiElement?): RetrofitEndpoint? {
        val method = element?.getUastParentOfType(UMethod::class.java) ?: return null
        return of(method)
    }

    fun of(method: UMethod): RetrofitEndpoint? {
        var httpMethod: String? = null
        var path: String? = null
        for ((fqn, verb) in EndpointLogic.METHOD_ANNOTATIONS) {
            val a = method.findAnnotation(fqn) ?: continue
            httpMethod = verb
            path = a.findAttributeValue("value")?.evaluate() as? String ?: ""
            break
        }
        if (httpMethod == null) {
            val a = method.findAnnotation(EndpointLogic.HTTP_ANNOTATION) ?: return null
            httpMethod = (a.findAttributeValue("method")?.evaluate() as? String)?.ifBlank { null } ?: return null
            path = a.findAttributeValue("path")?.evaluate() as? String ?: ""
        }
        return RetrofitEndpoint(httpMethod, path.orEmpty(), responseType(method), method.name, method.javaPsi.containingClass)
    }

    /** The declared response type, with suspend and Retrofit/Rx/coroutine wrappers removed. */
    private fun responseType(method: UMethod): PsiType? {
        val psi = method.javaPsi
        // A Kotlin suspend fun compiles to `Object f(…, Continuation<? super T>)`.
        val last = psi.parameterList.parameters.lastOrNull()?.type as? PsiClassType
        var type: PsiType? = if (last?.resolve()?.qualifiedName == "kotlin.coroutines.Continuation") {
            last.parameters.firstOrNull()?.let(::bound)
        } else {
            psi.returnType
        }
        repeat(MAX_UNWRAP) {
            val ct = type as? PsiClassType ?: return@repeat
            val fqn = ct.resolve()?.qualifiedName ?: return@repeat
            if (fqn in NO_BODY) return null
            if (fqn in WRAPPERS) type = ct.parameters.firstOrNull()?.let(::bound) ?: return null
        }
        return type?.takeUnless { it == PsiTypes.voidType() || (it as? PsiClassType)?.resolve()?.qualifiedName in NO_BODY }
    }

    private fun bound(t: PsiType): PsiType = (t as? PsiWildcardType)?.bound ?: t

    private const val MAX_UNWRAP = 5

    private val WRAPPERS = setOf(
        "retrofit2.Call", "retrofit2.Response",
        "retrofit2.adapter.rxjava.Result", "retrofit2.adapter.rxjava2.Result", "retrofit2.adapter.rxjava3.Result",
        "io.reactivex.Single", "io.reactivex.Observable", "io.reactivex.Maybe", "io.reactivex.Flowable",
        "io.reactivex.rxjava3.core.Single", "io.reactivex.rxjava3.core.Observable",
        "io.reactivex.rxjava3.core.Maybe", "io.reactivex.rxjava3.core.Flowable",
        "rx.Single", "rx.Observable",
        "kotlinx.coroutines.Deferred", "kotlinx.coroutines.flow.Flow",
        "java.util.concurrent.CompletableFuture", "com.google.common.util.concurrent.ListenableFuture",
        "androidx.lifecycle.LiveData",
    )

    private val NO_BODY = setOf(
        "kotlin.Unit", "java.lang.Void", "okhttp3.ResponseBody",
        "io.reactivex.Completable", "io.reactivex.rxjava3.core.Completable", "rx.Completable",
    )
}

/**
 * Builds placeholder JSON for a type, field by field, using the names Gson, Moshi, kotlinx.serialization
 * or Jackson would write. Strings get their field name as a value so the sample is easy to read.
 */
object SampleJson {
    private const val MAX_DEPTH = 6

    fun of(type: PsiType?): JsonElement = sample(type, 0, emptySet(), null)

    private fun sample(type: PsiType?, depth: Int, visiting: Set<String>, key: String?): JsonElement = when (type) {
        null -> JsonNull.INSTANCE
        is PsiPrimitiveType -> primitive(type.name, key)
        is PsiArrayType -> JsonArray().apply { add(sample(type.componentType, depth + 1, visiting, key)) }
        is PsiWildcardType -> sample(type.bound, depth, visiting, key)
        is PsiClassType -> classSample(type, depth, visiting, key)
        else -> JsonNull.INSTANCE
    }

    private fun classSample(type: PsiClassType, depth: Int, visiting: Set<String>, key: String?): JsonElement {
        val resolved = type.resolveGenerics()
        val cls = resolved.element ?: return JsonObject()
        if (cls is PsiTypeParameter) return JsonObject() // an unresolved generic
        val fqn = cls.qualifiedName.orEmpty()

        SCALARS[fqn]?.let { return it(key) }
        if (cls.isEnum) return enumSample(cls)
        if (InheritanceUtil.isInheritor(cls, "java.util.Map")) {
            val value = PsiUtil.substituteTypeParameter(type, "java.util.Map", 1, false)
            return JsonObject().apply { add("key", sample(value, depth + 1, visiting, "value")) }
        }
        if (InheritanceUtil.isInheritor(cls, "java.lang.Iterable")) {
            val item = PsiUtil.extractIterableTypeParameter(type, false)
            return JsonArray().apply { add(sample(item, depth + 1, visiting, key)) }
        }
        if (fqn in OPAQUE || cls.isInterface || depth >= MAX_DEPTH) return JsonObject()
        if (fqn in visiting) return JsonNull.INSTANCE // recursive type, e.g. a tree node

        val obj = JsonObject()
        val hierarchy = generateSequence(cls) { it.superClass }
            .takeWhile { it.qualifiedName !in STOP_CLASSES }
            .toList().asReversed()
        for (c in hierarchy) for (f in c.fields) {
            if (!isSerialized(f)) continue
            val name = jsonName(f)
            if (obj.has(name)) continue
            obj.add(name, sample(resolved.substitutor.substitute(f.type), depth + 1, visiting + fqn, name))
        }
        return obj
    }

    private fun isSerialized(f: PsiField): Boolean =
        f !is PsiEnumConstant &&
            !f.hasModifierProperty(PsiModifier.STATIC) &&
            !f.hasModifierProperty(PsiModifier.TRANSIENT) &&
            '$' !in f.name &&
            annotations(f).none { it.first == "kotlinx.serialization.Transient" || it.first == "com.fasterxml.jackson.annotation.JsonIgnore" }

    /** The JSON key: from @SerializedName, @Json, @SerialName or @JsonProperty, else the field name. */
    private fun jsonName(f: PsiField): String {
        for ((fqn, value) in annotations(f)) {
            if (fqn in NAME_ANNOTATIONS && !value.isNullOrBlank()) return value
        }
        return f.name
    }

    private fun enumSample(cls: PsiClass): JsonElement {
        val first = cls.fields.filterIsInstance<PsiEnumConstant>().firstOrNull() ?: return JsonPrimitive("")
        return JsonPrimitive(jsonName(first))
    }

    /**
     * (qualified name, name value) for annotations on a field. For Kotlin, property annotations such as
     * @SerialName live on the source property or constructor parameter, not the generated field.
     */
    private fun annotations(f: PsiField): List<Pair<String, String?>> {
        val elements = listOfNotNull(f.toUElement(), f.navigationElement.takeIf { it !== f }?.toUElement())
        return elements.filterIsInstance<UAnnotated>().flatMap { it.uAnnotations }.mapNotNull { a ->
            val fqn = a.qualifiedName ?: return@mapNotNull null
            val attr = if (fqn == "com.squareup.moshi.Json") "name" else "value"
            fqn to (runCatching { a.findAttributeValue(attr)?.evaluate() as? String }.getOrNull())
        }
    }

    private fun primitive(name: String, key: String?): JsonElement = when (name) {
        "boolean" -> JsonPrimitive(false)
        "char" -> JsonPrimitive("a")
        "float", "double" -> JsonPrimitive(0.0)
        "int", "long", "short", "byte" -> JsonPrimitive(0)
        else -> JsonPrimitive(key ?: "value")
    }

    private val NAME_ANNOTATIONS = setOf(
        "com.google.gson.annotations.SerializedName",
        "com.squareup.moshi.Json",
        "kotlinx.serialization.SerialName",
        "com.fasterxml.jackson.annotation.JsonProperty",
    )

    private val STOP_CLASSES = setOf("java.lang.Object", "java.lang.Enum", "java.lang.Record", null)

    /** Types serialized as free-form JSON. */
    private val OPAQUE = setOf(
        "java.lang.Object", "com.google.gson.JsonElement", "com.google.gson.JsonObject",
        "kotlinx.serialization.json.JsonElement", "kotlinx.serialization.json.JsonObject",
        "org.json.JSONObject", "com.fasterxml.jackson.databind.JsonNode",
    )

    private val SCALARS: Map<String, (String?) -> JsonElement> = buildMap {
        val text: (String?) -> JsonElement = { JsonPrimitive(it ?: "string") }
        val zero: (String?) -> JsonElement = { JsonPrimitive(0) }
        val decimal: (String?) -> JsonElement = { JsonPrimitive(0.0) }
        for (t in listOf("java.lang.String", "java.lang.CharSequence")) put(t, text)
        for (t in listOf("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger")) put(t, zero)
        for (t in listOf("java.lang.Double", "java.lang.Float", "java.lang.Number", "java.math.BigDecimal")) put(t, decimal)
        put("java.lang.Boolean") { JsonPrimitive(false) }
        put("java.lang.Character") { JsonPrimitive("a") }
        put("java.util.UUID") { JsonPrimitive("00000000-0000-0000-0000-000000000000") }
        for (t in listOf("java.util.Date", "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "kotlinx.datetime.Instant")) {
            put(t) { JsonPrimitive("2025-01-01T00:00:00Z") }
        }
        put("java.time.LocalDateTime") { JsonPrimitive("2025-01-01T00:00:00") }
        for (t in listOf("java.time.LocalDate", "kotlinx.datetime.LocalDate")) put(t) { JsonPrimitive("2025-01-01") }
    }
}
