// SPFN Mobile — a screen that draws a PagedView owns no scroll of its own.
//
// `PagedView` on Android is a `LazyColumn`, and a lazy list inside `Screen(scroll = true)`'s
// `verticalScroll` is measured against an infinite height: Compose throws an
// IllegalStateException out of the layout pass on the frame the screen first appears. Nothing
// compiles it away and no JVM test composes a screen. So the call is read where it is written:
// a `PagedView` drawn inside a `Screen`'s content must be inside one that passes
// `scroll = false`, as a constant. A `Screen` that leaves `scroll` out scrolls by default.
//
// What this cannot see is a `PagedView` drawn by a composable that some OTHER function calls
// inside a scrolling `Screen`; it reads the scopes one function writes down. And an app reads
// both composables compiled, where `@JvmSynthetic` leaves no method to resolve to, so there a
// call is known by its name and imports and `scroll` by its name: `scroll = false`, written
// as a named argument, which is how the SDK's own documentation spells it.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement

class PagedViewScrollDetector : Detector(), SourceCodeScanner
{
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java);

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler()
    {
        override fun visitCallExpression(node: UCallExpression)
        {
            if (node.callsTopLevel(PAGED_VIEW_FACADE, PAGED_VIEW) && drawnInScrollingScreen(context, node))
            {
                context.report(
                    ISSUE,
                    node,
                    context.getNameLocation(node),
                    "PagedView is drawn inside a Screen that scrolls; pass scroll = false to that Screen"
                );
            }
        }
    };

    /** Whether the nearest `Screen` [node] is drawn in leaves `scroll` anything but a constant false. */
    private fun drawnInScrollingScreen(context: JavaContext, node: UCallExpression): Boolean
    {
        val screen = enclosingCalls(node).firstOrNull { it.callsTopLevel(SCREEN_FACADE, SCREEN) } ?: return false;
        val scroll = screen.resolvedTopLevel(SCREEN_FACADE, SCREEN)?.let { argumentFor(context, screen, it, SCROLL) }
            ?: namedArgument(screen, SCROLL);
        return scroll == null || ConstantEvaluator.evaluate(context, scroll) != false;
    }

    companion object
    {
        private const val PAGED_VIEW = "PagedView";

        private const val PAGED_VIEW_FACADE = "xyz.superfunction.spfn.ui.components.PagedViewKt";

        private const val SCREEN = "Screen";

        private const val SCREEN_FACADE = "xyz.superfunction.spfn.ui.components.ScreenKt";

        private const val SCROLL = "scroll";

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "SpfnPagedViewInScrollingScreen",
            briefDescription = "PagedView inside a scrolling Screen",
            explanation = """
                PagedView brings a LazyColumn, and a lazy list measured inside \
                Screen(scroll = true)'s verticalScroll is measured against an infinite height \
                and throws IllegalStateException on the frame the screen appears. A screen that \
                draws a PagedView passes scroll = false; the list is the body's scroll.
                """,
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(PagedViewScrollDetector::class.java, Scope.JAVA_FILE_SCOPE)
        );
    }
}
