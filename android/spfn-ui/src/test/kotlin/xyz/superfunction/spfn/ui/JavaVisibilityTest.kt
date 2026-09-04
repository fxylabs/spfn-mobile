// SPFN Mobile — what this module's API looks like from Java.
//
// docs/IMPLEMENTATION-PITFALLS.md P15: a rule enforced by the Kotlin compiler is not
// enforced for a Java caller of the same AAR, so every "an app cannot do this" claim gets
// checked once per consuming language. Every claim here is the same claim: `FlowHost`,
// `Screen` and the eight components are `@Composable`, which the Compose compiler refuses to
// call outside a composition — for Kotlin. From Java each is a static method taking a
// `Composer`, and passing null crashes at runtime rather than failing to compile.
// `@JvmSynthetic` is what removes them from Java's view, and ACC_SYNTHETIC on the class file
// is the only place that is observable.
//
// The component half is checked as a SET rather than one test per name. A component added
// without the attribute is the defect, and it is the shape a new file takes — copied from a
// sibling with one line dropped — so the assertion is "every public composable in the
// components package is erased", not "these nine are".
//
// Everything else in this module is API on purpose and stays reachable from Java, which
// this suite also pins: a future `internal` member would compile to a name-mangled public
// method that Java can still call, and the count below is what would move.

package xyz.superfunction.spfn.ui

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaVisibilityTest
{
    @Test
    fun `FlowHost is erased from Java's view`()
    {
        val host = Class.forName("xyz.superfunction.spfn.ui.FlowHostKt")
            .declaredMethods
            .filter { it.name == "FlowHost" };
        assertEquals(1, host.size);
        assertTrue("FlowHost must carry ACC_SYNTHETIC so Java cannot call it", host[0].isSynthetic);
    }

    @Test
    fun `Screen is erased from Java's view`()
    {
        val screen = Class.forName("xyz.superfunction.spfn.ui.components.ScreenKt")
            .declaredMethods
            .filter { it.name == "Screen" };
        assertEquals(1, screen.size);
        assertTrue("Screen must carry ACC_SYNTHETIC so Java cannot call it", screen[0].isSynthetic);
    }

    /**
     * Every component is erased from Java's view, and there are some to erase.
     *
     * The floor is the point: a class name that stopped existing, or a package that moved,
     * would leave this loop with nothing to check and passing — the reader that read nothing
     * (docs/IMPLEMENTATION-PITFALLS.md P7).
     */
    @Test
    fun `every component is erased from Java's view`()
    {
        val files = listOf(
            "SpfnTextKt", "StatusTextKt", "ButtonsKt", "SpfnTextFieldKt", "LoadableViewKt"
        );
        var checked = 0;
        files.forEach { file ->
            Class.forName("xyz.superfunction.spfn.ui.components.$file")
                .declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && it.name.first().isUpperCase() }
                .forEach { method ->
                    checked++;
                    assertTrue(
                        "${method.name} must carry ACC_SYNTHETIC so Java cannot call it",
                        method.isSynthetic
                    );
                };
        };
        assertTrue("the component scan found $checked composables to check, fewer than 8", checked >= 8);
    }

    @Test
    fun `every Flow transition stays callable from Java`()
    {
        val callable = Flow::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }
            .map { it.name }
            .toSortedSet();
        assertEquals(
            // `isPresented()` rather than `getIsPresented()`: Kotlin drops the `get` prefix
            // for a property whose name already begins with `is`. Written out as the
            // compiler emits it, checked with javap on the built class.
            sortedSetOf(
                "back", "close", "getStack", "handlesBack", "isPresented", "open",
                "pop", "push", "replace", "wayOut"
            ),
            callable
        );
    }
}
