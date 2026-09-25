// SPFN Mobile — no pointer input consumes every change it is handed.
//
// A Compose modifier that answers `pointerInput` by consuming EVERY change it sees takes the
// press out of the controls under it — but only for a person. `clickable` cancels its press on
// the Final pass when any change other than its own down reports `isConsumed`, and Final runs
// parent before child, so a parent that consumed on Main arrives there as a cancel. A finger
// always produces MOVE events and every runner here synthesises a DOWN and an UP with nothing
// between them, so a modal whose cover did this was green in every device cell and dead under
// a thumb on a phone (docs/IMPLEMENTATION-PITFALLS.md P36).
//
// The rule is about BLANKET consumption. A gesture detector that claims the change it
// recognised is how Compose gestures work. What is refused is a loop over an event's changes
// that hands each one to `consume` with no decision in between — as a lambda, or as the
// `PointerInputChange::consume` reference.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.USwitchExpression

class BlanketPointerConsumptionDetector : Detector(), SourceCodeScanner
{
    override fun getApplicableMethodNames(): List<String> = ITERATIONS + CONSUME;

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod)
    {
        val loop = if (method.name == CONSUME) blanketLoopAround(node, method) else loopHandingOnConsume(node);
        if (loop != null)
        {
            context.report(
                ISSUE,
                loop,
                context.getLocation(loop),
                "Every pointer change is consumed with no decision about it; a control under this " +
                    "loses a finger's press on the Final pass"
            );
        }
    }

    /** The loop over pointer changes a `consume()` call is made in unconditionally, if it is. */
    private fun blanketLoopAround(node: UCallExpression, method: PsiMethod): UCallExpression?
    {
        if (method.containingClass?.qualifiedName != POINTER_INPUT_CHANGE)
        {
            return null;
        }
        val lambda = unconditionalLambda(node) ?: return null;
        val loop = lambda.uastParent as? UCallExpression ?: return null;
        return loop.takeIf { it.methodName in ITERATIONS && iteratesChanges(it) };
    }

    /** [node] itself, when it is a loop over pointer changes handed `PointerInputChange::consume`. */
    private fun loopHandingOnConsume(node: UCallExpression): UCallExpression? =
        node.takeIf { call ->
            iteratesChanges(call) &&
                call.valueArguments.any { (it as? UCallableReferenceExpression)?.callableName == CONSUME }
        };

    /** The lambda [node] is called in, unless an `if` or a `when` decides first. */
    private fun unconditionalLambda(node: UElement): ULambdaExpression?
    {
        var current = node.uastParent;
        while (current != null && current !is UMethod)
        {
            when (current)
            {
                is UIfExpression, is USwitchExpression -> return null
                is ULambdaExpression -> return current
            }
            current = current.uastParent;
        }
        return null;
    }

    private fun iteratesChanges(loop: UCallExpression): Boolean =
        loop.receiverType?.canonicalText?.contains(POINTER_INPUT_CHANGE) == true;

    companion object
    {
        private const val POINTER_INPUT_CHANGE = "androidx.compose.ui.input.pointer.PointerInputChange";

        private const val CONSUME = "consume";

        private val ITERATIONS = listOf("forEach", "fastForEach", "onEach", "map", "fastMap");

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "SpfnBlanketPointerConsumption",
            briefDescription = "Pointer input consumes every change it is handed",
            explanation = """
                A loop that consumes every change of a pointer event on the Main pass cancels \
                the press of every clickable under it on the Final pass. Injected taps are a \
                DOWN and an UP with no MOVE between them, so device runners stay green while a \
                finger's press is lost. Consume only the changes a gesture recognised, or none \
                (docs/IMPLEMENTATION-PITFALLS.md P36).
                """,
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(BlanketPointerConsumptionDetector::class.java, Scope.JAVA_FILE_SCOPE)
        );
    }
}
