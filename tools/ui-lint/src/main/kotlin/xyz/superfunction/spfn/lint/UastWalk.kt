// SPFN Mobile — the walks over a syntax tree the checks here share.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.detector.api.JavaContext
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUFile
import org.jetbrains.uast.toUElementOfType

/** Whether [method] is the top-level function [name] declared in the Kotlin file facade [facade]. */
private fun PsiMethod.isTopLevel(facade: String, name: String): Boolean =
    this.name == name && containingClass?.qualifiedName == facade;

/**
 * The top-level function [name] of the Kotlin file facade [facade], when that is what this
 * call resolves to.
 */
internal fun UCallExpression.resolvedTopLevel(facade: String, name: String): PsiMethod? =
    resolve()?.takeIf { it.isTopLevel(facade, name) };

/**
 * Whether this call calls the top-level function [name] of the Kotlin file facade [facade].
 *
 * A call that does not resolve to it is also judged by the name it is written with and by
 * what its file can see: the package it is in, or an import of the function or of its
 * package. That is not a fallback for broken code. SPFNUI's composables are `@JvmSynthetic`,
 * so a module that reads them compiled — every app — has no Java-visible method for the
 * call to resolve to, and lint then resolves it to nothing or to a synthetic neighbour. An
 * unresolved call under an import alias is not recognised; nothing here imports one so.
 */
internal fun UCallExpression.callsTopLevel(facade: String, name: String): Boolean =
    resolvedTopLevel(facade, name) != null ||
        (calleeName() == name && seesTopLevel(facade.substringBeforeLast('.'), name));

/** The name a call is written with, read off the source rather than off a resolution. */
private fun UCallExpression.calleeName(): String? =
    (sourcePsi as? KtCallElement)?.calleeExpression?.text ?: methodIdentifier?.name ?: methodName;

private fun UElement.seesTopLevel(packageName: String, name: String): Boolean
{
    val file = getContainingUFile() ?: return false;
    return file.packageName == packageName || file.imports.any { import ->
        val reference = import.importReference?.asRenderString();
        reference == "$packageName.$name" || (import.isOnDemand && reference == packageName)
    };
}

/**
 * The calls whose trailing lambdas [node] sits inside, innermost first, up to the function
 * that declares it. A call to a composable that takes content is the scope that content is
 * drawn in, so this is the chain of scopes a composable call is drawn inside.
 */
internal fun enclosingCalls(node: UElement): Sequence<UCallExpression> =
    generateSequence(node.uastParent) { it.uastParent }
        .takeWhile { it !is UMethod }
        .filterIsInstance<ULambdaExpression>()
        .mapNotNull { it.uastParent as? UCallExpression };

/** The argument [call] passes for the parameter named [parameter], or null when it passes none. */
internal fun argumentFor(context: JavaContext, call: UCallExpression, method: PsiMethod, parameter: String): UExpression? =
    context.evaluator.computeArgumentMapping(call, method).entries
        .firstOrNull { it.value.name == parameter }?.key;

/**
 * The argument [call] passes BY NAME for [parameter], read off the Kotlin source. What a call
 * that does not resolve can still say: a positional argument needs the declaration to be
 * matched to a parameter, a named one names it.
 */
internal fun namedArgument(call: UCallExpression, parameter: String): UExpression? =
    (call.sourcePsi as? KtCallElement)?.valueArguments
        ?.firstOrNull { it.getArgumentName()?.asName?.asString() == parameter }
        ?.getArgumentExpression()
        ?.toUElementOfType<UExpression>();
