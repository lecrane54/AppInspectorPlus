package io.github.networkrules

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.networkrules.EndpointLogic.UrlPart
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** A service's base URL as written in code, and where it was found. */
class ServiceBase(val parts: List<UrlPart>, val location: String)

/**
 * Finds the base URL a Retrofit service is built with, by looking where the interface is created:
 * `create(Api::class.java)`, `create(Api.class)`, `create<Api>()`, or a custom builder such as
 * `NetworkBuild(BaseUrl(context).secured + "v1.0/drivewise/api").create(Api::class.java)`.
 *
 * It looks at the create call's receiver first, then at the declarations that receiver refers to
 * (a property, a local, a provider method in the same file), then at the rest of the enclosing
 * method. The URL expression is split into fixed text (literals, constants) and runtime parts
 * (anything else, e.g. `BaseUrl(context).secured`). Runs in a read action.
 */
object ServiceBaseUrlResolver {
    private const val MAX_USAGES = 50
    private const val MAX_HOPS = 2
    private const val MAX_FOLLOW = 3

    fun find(service: PsiClass): ServiceBase? {
        val project = service.project
        var found: ServiceBase? = null
        var seen = 0
        ReferencesSearch.search(service, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
            val el = ref.element
            if (service.isAncestorOf(el)) return@Processor true
            if (++seen > MAX_USAGES) return@Processor false
            found = fromUsage(el)
            found == null
        })
        return found
    }

    private fun PsiClass.isAncestorOf(e: PsiElement) = com.intellij.psi.util.PsiTreeUtil.isAncestor(this, e, false)

    /** A class-literal or `create<…>()` usage → the base URL it's built with. */
    private fun fromUsage(el: PsiElement): ServiceBase? {
        val call = el.getUastParentOfType(UCallExpression::class.java, false)
        val isLiteral = el.getUastParentOfType(UClassLiteralExpression::class.java, false) != null
        val isCreate = call?.methodName?.contains("create", ignoreCase = true) == true
        if (!isLiteral && !isCreate) return null

        val scope: UElement = el.getUastParentOfType(UMethod::class.java, false)
            ?: el.getUastParentOfType(UVariable::class.java, false) ?: return null

        // 1) The create call's receiver: NetworkBuild(<url>).create(…), retrofit.create(…).
        val receiver = call?.receiver
        receiver?.let { best(it, scope) }?.let { return it }
        // 2) What the receiver refers to: a property, a local, a parameter provided in the same file.
        receiver?.let { hop(it, scope, 0) }?.let { return it }
        // 3) Anywhere in the enclosing method or property.
        return best(scope, scope)
    }

    private fun hop(from: UElement, scope: UElement, depth: Int): ServiceBase? {
        if (depth >= MAX_HOPS) return null
        for (target in referencedDeclarations(from)) {
            val bodies: List<UElement> = when (target) {
                is UMethod -> listOfNotNull(target.uastBody)
                is UParameter -> providersOf(target, scope)
                is UVariable -> listOfNotNull(target.uastInitializer)
                else -> emptyList()
            }
            for (b in bodies) {
                best(b, b)?.let { return it }
                hop(b, b, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** For an injected parameter (e.g. a Dagger-provided Retrofit), the same file's methods returning its type. */
    private fun providersOf(p: UParameter, scope: UElement): List<UElement> {
        val type = p.type.canonicalText
        val file = scope.sourcePsi?.containingFile ?: return emptyList()
        val methods = mutableListOf<UElement>()
        file.toUElement()?.accept(object : AbstractUastVisitor() {
            override fun visitMethod(node: UMethod): Boolean {
                if (node.returnType?.canonicalText == type && node.sourcePsi != scope.sourcePsi) node.uastBody?.let(methods::add)
                return false
            }
        })
        return methods
    }

    private fun referencedDeclarations(from: UElement): List<UElement> {
        val out = linkedSetOf<UElement>()
        from.accept(object : AbstractUastVisitor() {
            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                node.resolve()?.toUElement()?.let(out::add)
                return false
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                node.resolve()?.toUElement()?.let { if (it is UMethod) out += it }
                return false
            }
        })
        return out.toList()
    }

    // ---- choosing the URL expression ------------------------------------------------------------

    private class Candidate(val expr: UExpression, val score: Int)

    /** The most URL-like string expression under [root]. */
    private fun best(root: UElement, scope: UElement): ServiceBase? {
        val candidates = mutableListOf<Candidate>()
        root.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val params = node.resolve()?.parameterList?.parameters.orEmpty()
                val isBaseUrlCall = node.methodName.equals("baseUrl", ignoreCase = true)
                for ((i, p) in params.withIndex()) {
                    val arg = node.getArgumentForParameter(i) ?: continue
                    if (isBaseUrlCall || p.name.contains("url", ignoreCase = true)) {
                        candidates += Candidate(arg, score(arg, scope) + 5)
                    }
                }
                return false
            }

            // Java's `a + b` is a binary expression; `a + b + c` and Kotlin templates are polyadic.
            override fun visitBinaryExpression(node: UBinaryExpression): Boolean = visitPolyadicExpression(node)

            override fun visitPolyadicExpression(node: UPolyadicExpression): Boolean {
                if (node.operator == UastBinaryOperator.PLUS && (node.uastParent as? UPolyadicExpression)?.operator != UastBinaryOperator.PLUS) {
                    candidates += Candidate(node, score(node, scope))
                }
                return false
            }
        })
        val pick = candidates.filter { it.score > 0 }.maxByOrNull { it.score } ?: return null
        val parts = EndpointLogic.normalize(parts(pick.expr, scope, 0))
        if (parts.isEmpty()) return null
        return ServiceBase(parts, location(pick.expr))
    }

    private fun score(e: UExpression, scope: UElement): Int {
        val parts = EndpointLogic.normalize(parts(e, scope, 0))
        val text = parts.filterIsInstance<UrlPart.Text>().joinToString("") { it.value }
        val source = e.sourcePsi?.text.orEmpty()
        var s = 0
        if (text.startsWith("http", ignoreCase = true)) s += 4
        if ('/' in text) s += 2
        if (source.contains("url", ignoreCase = true)) s += 2
        if (parts.any { it is UrlPart.Dynamic } && parts.any { it is UrlPart.Text }) s += 1
        // Not a URL at all, e.g. "Bearer " + token.
        if ('/' !in text && !source.contains("url", ignoreCase = true)) s -= 10
        return s
    }

    /** Splits a string expression into fixed text and runtime parts. */
    private fun parts(e: UExpression?, scope: UElement, depth: Int): List<UrlPart> {
        if (e == null) return emptyList()
        (runCatching { e.evaluate() }.getOrNull() as? String)?.let { return listOf(UrlPart.Text(it)) }
        return when {
            e is UParenthesizedExpression -> parts(e.expression, scope, depth)
            e is UPolyadicExpression && e.operator == UastBinaryOperator.PLUS -> e.operands.flatMap { parts(it, scope, depth) }
            // a.plus("x") in Kotlin
            e is UQualifiedReferenceExpression && (e.selector as? UCallExpression)?.methodName == "plus" ->
                parts(e.receiver, scope, depth) + parts((e.selector as UCallExpression).valueArguments.firstOrNull(), scope, depth)
            depth < MAX_FOLLOW && e is UResolvable -> {
                // Follow a local, or a property in the same file: val base = BaseUrl(context).secured + "v1.0/…"
                val target = e.resolve()?.toUElement()
                val init = when (target) {
                    is ULocalVariable -> target.uastInitializer
                    is UField -> target.uastInitializer?.takeIf { sameFile(target, scope) }
                    else -> null
                }
                if (init != null) parts(init, scope, depth + 1) else listOf(UrlPart.Dynamic(source(e)))
            }
            else -> listOf(UrlPart.Dynamic(source(e)))
        }
    }

    private fun sameFile(a: UElement, b: UElement): Boolean {
        val fa = a.sourcePsi?.containingFile ?: return false
        return fa == b.sourcePsi?.containingFile
    }

    private fun source(e: UExpression) = e.sourcePsi?.text?.replace(Regex("\\s+"), " ") ?: e.asSourceString()

    private fun location(e: UExpression): String {
        val cls = e.getContainingUClass()?.name
        val method = e.getParentOfType<UMethod>(UMethod::class.java)?.name
        val file = e.sourcePsi?.containingFile?.name
        return listOfNotNull(listOfNotNull(cls, method).joinToString(".").ifEmpty { null }, file?.let { "($it)" }).joinToString(" ")
    }
}
