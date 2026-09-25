// SPFN Mobile — the blanket-consumption check refuses what it must, and spares what it must.

package xyz.superfunction.spfn.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class BlanketPointerConsumptionDetectorTest
{
    private val pointer = kotlin(
        """
        package androidx.compose.ui.input.pointer

        class PointerInputChange(val pressed: Boolean)
        {
            fun consume() {}
        }

        class PointerEvent(val changes: List<PointerInputChange>)
        """
    ).indented()

    private fun check(source: String) =
        lint().files(pointer, kotlin(source).indented())
            .issues(BlanketPointerConsumptionDetector.ISSUE)
            .allowMissingSdk()
            .run()

    @Test
    fun `a loop that consumes every change is refused`()
    {
        check(
            """
            package test

            import androidx.compose.ui.input.pointer.PointerEvent

            fun cover(event: PointerEvent)
            {
                event.changes.forEach { it.consume() }
            }
            """
        ).expectErrorCount(1);
    }

    @Test
    fun `the same loop spelled with a reference, or over several lines, is refused`()
    {
        check(
            """
            package test

            import androidx.compose.ui.input.pointer.PointerEvent
            import androidx.compose.ui.input.pointer.PointerInputChange

            fun reference(event: PointerEvent)
            {
                event.changes.forEach(PointerInputChange::consume)
            }

            fun spread(event: PointerEvent)
            {
                event.changes.forEach { change ->
                    val seen = change.pressed
                    change.consume()
                }
            }
            """
        ).expectErrorCount(2);
    }

    @Test
    fun `a change consumed after a decision about it, or on its own, is spared`()
    {
        check(
            """
            package test

            import androidx.compose.ui.input.pointer.PointerEvent
            import androidx.compose.ui.input.pointer.PointerInputChange

            fun claimed(event: PointerEvent)
            {
                event.changes.forEach { if (it.pressed) it.consume() }
            }

            fun recognised(change: PointerInputChange)
            {
                change.consume()
            }

            fun unrelated(names: List<String>)
            {
                names.forEach { it.length }
            }
            """
        ).expectClean();
    }
}
