// SPFN Mobile — every navigator is handed the same three transitions.
//
// `NavDisplay` takes a forward, a pop and a predictive-pop transition spec and defaults all
// three when they are not given — a fade forward and a scale-down back (navigation3-ui 1.1.7,
// read with javap). A module that states them at one call site and not at another ships one
// app with two opinions about what a screen arriving means: on a phone, `next` inside a modal
// faded while the same tap in a pushed flow slid in (docs/IMPLEMENTATION-PITFALLS.md P37).
//
// `NavDisplay`'s arguments are not readable from outside a composition, so no JVM test can see
// what a stack was handed. The call site can: each of the three parameters must be passed, and
// passed something that reads `FlowTransitions`.

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

class NavDisplayTransitionsDetector : Detector(), SourceCodeScanner
{
    override fun getApplicableMethodNames(): List<String> = listOf(NAV_DISPLAY);

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod)
    {
        if (method.containingClass?.qualifiedName?.startsWith(NAVIGATION3_UI) != true)
        {
            return;
        }
        val missing = TRANSITIONS.filterNot { parameter ->
            argumentFor(context, node, method, parameter)?.sourcePsi?.text?.contains(SOURCE) == true
        };
        if (missing.isNotEmpty())
        {
            context.report(
                ISSUE,
                node,
                context.getNameLocation(node),
                "NavDisplay leaves ${missing.joinToString(", ")} to the library's default; " +
                    "pass all three from FlowTransitions"
            );
        }
    }

    companion object
    {
        private const val NAV_DISPLAY = "NavDisplay";

        private const val NAVIGATION3_UI = "androidx.navigation3.ui.";

        private const val SOURCE = "FlowTransitions";

        private val TRANSITIONS = listOf("transitionSpec", "popTransitionSpec", "predictivePopTransitionSpec");

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "SpfnNavDisplayTransitions",
            briefDescription = "NavDisplay is not handed the three FlowTransitions",
            explanation = """
                Every NavDisplay in the UI module states its forward, pop and predictive-pop \
                transitions from FlowTransitions. Left out, the library's defaults apply — a \
                fade forward and a scale-down back — and a modal moves unlike a push in the \
                same app (docs/IMPLEMENTATION-PITFALLS.md P37).
                """,
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(NavDisplayTransitionsDetector::class.java, Scope.JAVA_FILE_SCOPE)
        );
    }
}
