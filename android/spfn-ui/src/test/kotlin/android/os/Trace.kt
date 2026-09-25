// SPFN Mobile — a JVM stand-in for the one framework class a composition cannot run without.
//
// Compose's runtime wraps every composition and every apply in `android.os.Trace`
// sections, unconditionally (`androidx.compose.runtime.internal.Trace`). On the JVM
// unit-test runner the framework is the mockable `android.jar`, whose methods throw
// "not mocked", so `Composition.setContent` fails before it reaches the content — and
// the runtime's attempt to report that through `android.util.Log.e` throws the same way,
// hiding the first error behind the second.
//
// Test classes precede `android.jar` on the unit-test classpath, so this class is the
// `android.os.Trace` the runtime links against here, and only here: it is not in any
// artifact. It defines exactly the two methods the runtime calls, as no-ops, which is what
// tracing is when no tracer is attached. It is narrower on purpose than
// `unitTests.isReturnDefaultValues`: every other framework call still fails loudly.

package android.os

object Trace
{
    @JvmStatic
    fun beginSection(sectionName: String) = Unit;

    @JvmStatic
    fun endSection() = Unit;
}
