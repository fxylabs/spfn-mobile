// SPFN Mobile — the transition check refuses a NavDisplay left to the library's defaults.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class NavDisplayTransitionsDetectorTest
{
    private val navigation = kotlin(
        """
        package androidx.navigation3.ui

        fun <T : Any> NavDisplay(
            backStack: List<T>,
            onBack: () -> Unit = {},
            transitionSpec: () -> String = { "fade" },
            popTransitionSpec: () -> String = { "fade" },
            predictivePopTransitionSpec: (Int) -> String = { "scale" },
            entryProvider: (T) -> Unit
        ) {}
        """
    ).indented()

    private val transitions = kotlin(
        """
        package xyz.superfunction.spfn.ui

        object FlowTransitions
        {
            val forward: String = "slide"
            val pop: String = "slide back"
            val predictivePop: String = pop
        }
        """
    ).indented()

    private fun check(source: String) =
        lint().files(navigation, transitions, kotlin(source).indented())
            .issues(NavDisplayTransitionsDetector.ISSUE)
            .allowMissingSdk()
            .run()

    @Test
    fun `a NavDisplay handed all three FlowTransitions is spared`()
    {
        check(
            """
            package xyz.superfunction.spfn.ui

            import androidx.navigation3.ui.NavDisplay

            fun host(routes: List<String>)
            {
                NavDisplay(
                    backStack = routes,
                    transitionSpec = { FlowTransitions.forward },
                    popTransitionSpec = { FlowTransitions.pop },
                    predictivePopTransitionSpec = { _ -> FlowTransitions.predictivePop },
                    entryProvider = { }
                )
            }
            """
        ).expectClean();
    }

    @Test
    fun `a NavDisplay handed none, or one of its own, is refused`()
    {
        check(
            """
            package xyz.superfunction.spfn.ui

            import androidx.navigation3.ui.NavDisplay

            fun bare(routes: List<String>)
            {
                NavDisplay(backStack = routes, entryProvider = { })
            }

            fun partial(routes: List<String>)
            {
                NavDisplay(
                    backStack = routes,
                    transitionSpec = { FlowTransitions.forward },
                    popTransitionSpec = { "a pop of its own" },
                    predictivePopTransitionSpec = { _ -> FlowTransitions.predictivePop },
                    entryProvider = { }
                )
            }
            """
        ).expectErrorCount(2);
    }
}
