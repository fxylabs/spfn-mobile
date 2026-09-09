// SPFN Mobile — the three transitions are three, and two of them are one.
//
// What a JVM test can reach here is narrow and worth being honest about. `NavDisplay` is a
// composable and its arguments are not readable from outside a composition, so nothing here
// can assert that a stack was HANDED these three — that is what validate.sh's section 18
// checks, by reading the call sites, and what a person on a phone confirms by watching a
// screen move. What this file holds is the half a JVM can hold: the values exist, they are
// distinct where they must differ, and the predictive pop is the pop and not a second copy
// that could drift from it.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FlowTransitionsTest
{
    @Test
    fun `all three of the specs NavDisplay defaults are stated`()
    {
        assertNotNull("forward", FlowTransitions.forward);
        assertNotNull("pop", FlowTransitions.pop);
        assertNotNull("predictive pop", FlowTransitions.predictivePop);
    }

    @Test
    fun `a push and a pop are two movements, not one`()
    {
        assertNotSame(FlowTransitions.forward, FlowTransitions.pop);
    }

    @Test
    fun `a back being held draws what a back that finished draws`()
    {
        assertSame(FlowTransitions.pop, FlowTransitions.predictivePop);
    }
}
