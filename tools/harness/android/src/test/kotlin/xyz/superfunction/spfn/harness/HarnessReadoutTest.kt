package xyz.superfunction.spfn.harness

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.Busy

/**
 * Every line the screen shows a flow, spelled out.
 *
 * A readout is this app's wire protocol with tools/harness/flows/: thirteen flow files
 * match these strings as regexes, and a screen that draws `busy=idle` or `network=off`
 * compiles, installs, launches, and then fails every case with an
 * `extendedWaitUntil` that times out twenty seconds later — pointing at the flow rather
 * than at the word. The screen itself is proved on a phone; this is the part of it that a
 * JVM can hold, and it is here so that a build says so in ten milliseconds
 * (docs/IMPLEMENTATION-PITFALLS.md P7).
 *
 * Every expected value below is written by hand from tools/harness/README.md and from the
 * flow files, and NONE of it is read back out of [HarnessReadout]. A table copied from its
 * own subject asserts only that the subject is self-consistent (P10). The flows are the
 * definition and they are not edited to agree with this screen.
 */
class HarnessReadoutTest
{
    /** The three states, in the spelling both platforms report. */
    @Test
    fun stateIsTheSdkStateBehindItsLabel()
    {
        assertEquals("state=unenrolled", HarnessReadout.state("unenrolled"));
        assertEquals("state=enrolled", HarnessReadout.state("enrolled"));
        assertEquals("state=rotationPending", HarnessReadout.state("rotationPending"));
    }

    /** `outcome=ok:rotated.*` and `outcome=err:notEnrolled` are flow selectors verbatim. */
    @Test
    fun outcomeIsTheLastActionBehindItsLabel()
    {
        assertEquals("outcome=idle", HarnessReadout.outcome("idle"));
        assertEquals("outcome=ok:rotated:key-1", HarnessReadout.outcome("ok:rotated:key-1"));
        assertEquals("outcome=err:notEnrolled", HarnessReadout.outcome("err:notEnrolled"));
    }

    /**
     * The two words every flow synchronises on.
     *
     * `busy=ready` is what all thirteen flows wait for after a tap, in place of sleeping
     * for a guessed number of seconds. It is the single most load-bearing string here.
     */
    @Test
    fun busyIsReadyOrBusyAndNothingElse()
    {
        assertEquals("busy=ready", HarnessReadout.busy(Busy.Idle));
        assertEquals("busy=busy", HarnessReadout.busy(Busy.Busy));
    }

    /** A failed write is over, so it reads as ready. The failure is reported by `outcome=`. */
    @Test
    fun busyReadsAsReadyWhenTheLastWriteFailed()
    {
        val failed = Busy.Error(SpfnErrorEnvelope("CONTRACT_UNSUPPORTED", "unsupported", "req-1"));
        assertEquals("busy=ready", HarnessReadout.busy(failed));
    }

    /** Permanent, and reading `open` when nothing has been blocked is half of the point. */
    @Test
    fun networkIsOpenOrBlockedAtAllTimes()
    {
        assertEquals("network=open", HarnessReadout.network(blocked = false));
        assertEquals("network=blocked", HarnessReadout.network(blocked = true));
    }

    /** `unread` until a probe runs, then the wire name of the custody it found. */
    @Test
    fun custodyIsWhatAProbeFound()
    {
        assertEquals("custody=unread", HarnessReadout.custody("unread"));
        assertEquals("custody=strongBox", HarnessReadout.custody("strongBox"));
    }

    /**
     * The case, in the shared spec's wire names.
     *
     * Not `case-first-enroll` and not `[first-enroll]`: a device run once spent a round
     * trip working out that three spellings were one case.
     */
    @Test
    fun caseIsTheSpecsWireName()
    {
        assertEquals("case=first-enroll", HarnessReadout.case(HarnessSocialCase.FIRST_ENROLL));
        assertEquals("case=re-login", HarnessReadout.case(HarnessSocialCase.RE_LOGIN));
        assertEquals("case=user-cancel", HarnessReadout.case(HarnessSocialCase.USER_CANCEL));
        assertEquals("case=network-failure", HarnessReadout.case(HarnessSocialCase.NETWORK_FAILURE));
        assertEquals("case=server-reject", HarnessReadout.case(HarnessSocialCase.SERVER_REJECT));
    }

    /** The word only. A client id and a server address belong on no screen. */
    @Test
    fun socialIsTheConfigurationWordAndNeverAValue()
    {
        assertEquals("social=configured", HarnessReadout.social("configured"));
        assertEquals("social=not-configured", HarnessReadout.social("not-configured"));
    }

    /** The receipt's file name, or `none` when this tap wrote no file. */
    @Test
    fun receiptIsTheFileNameOrNone()
    {
        assertEquals("receipt=none", HarnessReadout.receipt("none"));
        assertEquals(
            "receipt=receipt-google-user-cancel-1788220800000.json",
            HarnessReadout.receipt("receipt-google-user-cancel-1788220800000.json")
        );
    }

    /** The code somebody is walking to another phone with, or `none`. */
    @Test
    fun deviceCodeIsWhatTheServerSpelledOrNone()
    {
        assertEquals("device-code=none", HarnessReadout.deviceCode("none"));
        assertEquals("device-code=WXYZ-2345", HarnessReadout.deviceCode("WXYZ-2345"));
    }

    /** Whole seconds, truncated: 1500ms left is one second left, not two. */
    @Test
    fun expiresInCountsWholeSecondsDown()
    {
        assertEquals("expires-in=90s", HarnessReadout.expiresIn(90_000L, 0L));
        assertEquals("expires-in=1s", HarnessReadout.expiresIn(1_500L, 0L));
    }

    /**
     * The two ends of the countdown, which are different sentences.
     *
     * No code showing is `-`; a code whose time ran out is `expired`, and it stays on
     * screen saying so rather than reverting to `-` — a person reading a code aloud has to
     * be told the code is dead, not that there never was one.
     */
    @Test
    fun expiresInSeparatesNoCodeFromASpentOne()
    {
        assertEquals("expires-in=-", HarnessReadout.expiresIn(null, 1_788_220_800_000L));
        assertEquals("expires-in=expired", HarnessReadout.expiresIn(1_000L, 1_000L));
        assertEquals("expires-in=expired", HarnessReadout.expiresIn(1_000L, 9_000L));
    }
}
