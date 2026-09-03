// SPFN Mobile — what this module's API looks like from Java.
//
// docs/IMPLEMENTATION-PITFALLS.md P15: a rule enforced by the Kotlin compiler is not
// enforced for a Java caller of the same AAR, so every "an app cannot do this" claim gets
// checked once per consuming language. There are two such claims here, and they are the same
// claim twice: `FlowHost` and `Screen` are `@Composable`, which the Compose compiler refuses
// to call outside a composition — for Kotlin. From Java each is a static method taking a
// `Composer`, and passing null crashes at runtime rather than failing to compile.
// `@JvmSynthetic` is what removes them from Java's view, and ACC_SYNTHETIC on the class file
// is the only place that is observable.
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
                "back", "close", "getStack", "handlesBack", "isPresented", "leading",
                "open", "pop", "push", "replace"
            ),
            callable
        );
    }
}
