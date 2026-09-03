// SPFN Mobile — what a generated screen model does with what it did not expect.
//
// CellTest covers the case table, and the table cannot cover this: every fixture it names
// throws `SpfnClientError`, because that is the taxonomy a server's answers are read into.
// The SDK throws outside it too — `SpfnClockSynchronizationException` is an
// `IllegalStateException`, raised before any request leaves — and until 2f the generated
// `catch` named only `SpfnClientError`, so those went through the model and out of the
// process. Three device-mode cells crashed on an emulator on 2026-09-03 and no fixture in
// this repository could have predicted it (docs/IMPLEMENTATION-PITFALLS.md P26).
//
// Two claims, and they pull in opposite directions. Anything the screen can show has to
// become a state rather than an escape; cancellation has to escape rather than become a
// state, because a coroutine is cancelled BY an exception and a model that swallowed it
// would report a failure to a screen while reporting nothing to the scope that cancelled
// it (P16).
//
// The expected classification is written here by hand rather than read back out of
// `ScreenFailure`, which would agree with itself whatever it said (P10).

package xyz.superfunction.spfn.example

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Loadable

class UnexpectedFailureTest
{
    /** The entry screen over a service whose every call answers [answer]. */
    private fun entry(answer: Answer): EnterCodeModel =
        AppContainer(FakeDeviceApprovalService(listOf(answer))).enterCodeModel()

    @Test
    fun `a failure outside the client's own taxonomy becomes an error state`() = runTest {
        val entry = entry(Answer.CRASH);

        entry.submit(Fixtures.USER_CODE);

        val state = entry.state.value;
        assertTrue("submit left the screen at $state", state is Busy.Error);
        val envelope = (state as Busy.Error).error;
        assertEquals("SPFN_UI_CALL_FAILED", envelope.code);
        assertEquals("IllegalStateException", envelope.message);
        assertEquals("", envelope.requestId);
    }

    /**
     * The same failure raised by a write rather than by the read, which is a different
     * `catch` in the generated file and was as narrow as the other one.
     */
    @Test
    fun `a write's unexpected failure becomes an error state too`() = runTest {
        val container = AppContainer(
            FakeDeviceApprovalService(listOf(Answer.OK), listOf(Answer.CRASH))
        );
        // The detail screen is reached the way every cell reaches it, because its own
        // guard asks whether its route is the one on top of the flow's stack.
        container.enterCodeModel().submit(Fixtures.USER_CODE);
        val review = container.reviewDeviceModel(Fixtures.USER_CODE);
        review.load();

        review.approve();

        val state = review.state.value;
        assertTrue("approve left the screen at $state", state is Loadable.Error);
        assertEquals("SPFN_UI_CALL_FAILED", (state as Loadable.Error).error.code);
        assertEquals("IllegalStateException", state.error.message);
    }

    @Test
    fun `a cancellation is not a failure and leaves the model for the caller`() = runTest {
        val entry = entry(Answer.CANCEL);

        try
        {
            entry.submit(Fixtures.USER_CODE);
            fail("submit swallowed the cancellation and answered ${entry.state.value}");
        }
        catch (cancelled: CancellationException)
        {
            assertEquals("the fixture was told to cancel", cancelled.message);
        }

        // Not `Busy.Error`: a cancelled call is a call nobody is waiting on the answer to,
        // and the screen it was started from is going away with the scope that cancelled it.
        assertEquals(Busy.Busy, entry.state.value);
    }
}
