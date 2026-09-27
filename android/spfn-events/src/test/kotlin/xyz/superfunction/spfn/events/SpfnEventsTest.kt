// SPFN Mobile — the event module's rules that need no device (design §9-3).
//
// The host itself is thin: it moves platform signals into the stream's three inputs. The
// translation is named functions, held here on the JVM the way spfn-ui's TabStateTest
// holds its rules. Registration and release of the observers are device cells (U-11–U-14).

package xyz.superfunction.spfn.events

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpfnEventsTest
{
    @Test
    fun lifecycle_processStartAndStop_areTheForeground()
    {
        assertEquals(true, spfnForegroundOf(Lifecycle.Event.ON_START));
        assertEquals(false, spfnForegroundOf(Lifecycle.Event.ON_STOP));
        for (event in listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY, Lifecycle.Event.ON_ANY))
        {
            assertNull("$event is not a foreground change", spfnForegroundOf(event));
        }
    }

    @Test
    fun network_onlyAnAvailableDefaultNetworkIsTheNetwork()
    {
        assertTrue(spfnNetworkAvailableOf(SpfnNetworkSignal.AVAILABLE));
        assertFalse(spfnNetworkAvailableOf(SpfnNetworkSignal.LOST));
        assertFalse(spfnNetworkAvailableOf(SpfnNetworkSignal.UNAVAILABLE));
    }

    /** L-8: a screen that listens without a host is refused, not left waiting. */
    @Test
    fun l8_effectWithoutHost_isProgrammerError()
    {
        val thrown = assertThrows(IllegalStateException::class.java) { missingEventStream() };
        assertTrue(thrown.message.orEmpty().contains("SpfnEventStreamHost"));
    }
}
