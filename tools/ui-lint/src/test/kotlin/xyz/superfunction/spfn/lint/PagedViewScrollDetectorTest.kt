// SPFN Mobile — the PagedView check refuses a lazy list inside a scrolling Screen.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class PagedViewScrollDetectorTest
{
    private val screen = kotlin(
        "src/xyz/superfunction/spfn/ui/components/Screen.kt",
        """
        package xyz.superfunction.spfn.ui.components

        fun Screen(title: String, scroll: Boolean = true, content: () -> Unit) {}
        """
    ).indented()

    private val paged = kotlin(
        "src/xyz/superfunction/spfn/ui/components/PagedView.kt",
        """
        package xyz.superfunction.spfn.ui.components

        fun <V> PagedView(rows: List<V>, row: (V) -> Unit) {}

        fun Column(content: () -> Unit) {}
        """
    ).indented()

    private fun check(source: String) =
        lint().files(screen, paged, kotlin(source).indented())
            .issues(PagedViewScrollDetector.ISSUE)
            .allowMissingSdk()
            .run()

    @Test
    fun `a PagedView inside a Screen that says scroll = false is spared`()
    {
        check(
            """
            package test

            import xyz.superfunction.spfn.ui.components.Column
            import xyz.superfunction.spfn.ui.components.PagedView
            import xyz.superfunction.spfn.ui.components.Screen

            fun items(rows: List<String>)
            {
                Screen(title = "Items", scroll = false)
                {
                    Column { PagedView(rows) { } }
                }
            }
            """
        ).expectClean();
    }

    @Test
    fun `a PagedView inside a Screen that scrolls, by default or by saying so, is refused`()
    {
        check(
            """
            package test

            import xyz.superfunction.spfn.ui.components.PagedView
            import xyz.superfunction.spfn.ui.components.Screen

            fun defaulted(rows: List<String>)
            {
                Screen(title = "Items") { PagedView(rows) { } }
            }

            fun stated(rows: List<String>)
            {
                Screen(title = "Items", scroll = true) { PagedView(rows) { } }
            }
            """
        ).expectErrorCount(2);
    }

    /**
     * An app reads `Screen` and `PagedView` compiled, and `@JvmSynthetic` leaves them no method
     * to resolve to. Left without their declarations here, the calls do not resolve either,
     * and are judged by name and import — with `scroll` read as a named argument. The
     * import-alias mode is skipped: an unresolved call under an alias is outside what the
     * check claims to read.
     */
    @Test
    fun `calls that do not resolve, as in an app, are still judged`()
    {
        lint().files(
            kotlin(
                """
                package test

                import xyz.superfunction.spfn.ui.components.PagedView
                import xyz.superfunction.spfn.ui.components.Screen

                fun stated(rows: List<String>)
                {
                    Screen(title = "Items", scroll = false) { PagedView(rows) { } }
                }

                fun defaulted(rows: List<String>)
                {
                    Screen(title = "Items") { PagedView(rows) { } }
                }
                """
            ).indented()
        )
            .issues(PagedViewScrollDetector.ISSUE)
            .allowMissingSdk()
            .allowCompilationErrors()
            .skipTestModes(TestMode.IMPORT_ALIAS)
            .run()
            .expectErrorCount(1);
    }
}
