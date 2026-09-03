// SPFN Mobile — one test per cell of the device-approval case table.
//
// Cells covered here, which is every cell whose runner is `unit` or `both`:
//
//   u1  u1c  u2  u3  u4  u5  u6  u7  u7b  u8  u8c  u8d  u8e  u9  u9c  u10  u10b  u11
//   u12  u13  u14
//
// The expectations are read out of the table (CaseTableReader), never written here. The
// models are generated from the spec. The suite is where the two derivations meet, which
// is the whole reason the generator keeps its rule table separate from its emitters (P10).
//
// It is a JVM suite with no Robolectric, because every rule under test is a rule about a
// screen model and a list of routes: `Flow` is free of Compose on purpose, the models are
// free of it by construction, and nothing here needs a device. The same reason
// android/spfn-ui's own Flow suite is a JVM one.
//
// The two system-back cells drive `flow.pop()` directly, and so does u8d. That is not a
// shortcut around the gesture — it is the claim being tested: `FlowHost` binds Navigation
// 3's `onBack` to exactly `flow.pop()`, and above a modal flow's last route that is what
// the gesture means. It matters most in u8d: the screen's OWN back action bumps the
// generation on its way out, so only the gesture can leave a call in flight behind a
// popped route.
//
// u8e drives `flow.push` directly for the same kind of reason. Nothing this spec declares
// puts a second copy of the entry route over the detail screen, but `Flow` accepts it from
// any caller, and the guard is written against what the runtime admits rather than against
// what this one spec happens to do.

package xyz.superfunction.spfn.example

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.example.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.Loadable

// `runCurrent` is what lets a test start a call, look at the screen while it is in
// flight, and then let it finish, with no timing anywhere. It is the only experimental
// API this suite uses.
@OptIn(ExperimentalCoroutinesApi::class)
class CellTest
{
    private val code = Fixtures.USER_CODE;

    // ---- the entry screen: u1–u6 -------------------------------------------

    @Test
    fun `u1 submits, pushes, and the pushed screen reads its source`() = runTest {
        val run = Run(Fixtures.ready());
        run.entry.submit(code);
        val review = run.review();
        review.load();
        run.assertCell("u1", loadable(review));
    }

    /**
     * R4, with the flow reopened before the answer lands.
     *
     * `isPresented` is true again by then and this screen's route is on the stack again,
     * so neither of those two guards is what drops the answer — the generation token is,
     * because `cancel` bumped it on its way out. The cell is here to keep it that way: a
     * guard rewritten around the route alone would push `reviewDevice` onto a flow the
     * person had already left and come back to.
     */
    @Test
    fun `u1c drops a lookup that answers after the flow closed and reopened`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.ready());
        run.hold(gate);
        val submit = launch { run.entry.submit(code) };
        runCurrent();
        run.entry.cancel();
        run.flow.open(listOf(ApproveDeviceRoute.EnterCode));
        gate.complete(Unit);
        submit.join();
        run.assertCell("u1c", busy(run.entry));
    }

    @Test
    fun `u2 refuses an empty code without sending anything`() = runTest {
        val run = Run(Fixtures.ready());
        run.entry.submit("");
        run.assertCell("u2", busy(run.entry));
        assertEquals("a refused input still reached the service", 0, run.service.lookupCount);
    }

    @Test
    fun `u3 ignores a second press while the first is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.ready());
        run.hold(gate);
        val first = launch { run.entry.submit(code) };
        runCurrent();
        run.entry.submit(code);
        assertEquals("the second press sent a second request", 1, run.service.lookupCount);
        run.assertCell("u3", busy(run.entry));
        gate.complete(Unit);
        first.join();
    }

    @Test
    fun `u4 carries the server's refusal and does not push`() = runTest {
        val run = Run(Fixtures.refused());
        run.entry.submit(code);
        run.assertCell("u4", busy(run.entry));
        assertEquals(1, run.service.lookupCount);
    }

    @Test
    fun `u5 closes the flow and empties its stack`() = runTest {
        val run = Run(Fixtures.ready());
        run.entry.cancel();
        run.assertCell("u5", busy(run.entry));
    }

    @Test
    fun `u6 proceeds on the press after a refused input`() = runTest {
        val run = Run(Fixtures.ready());
        run.entry.submit("");
        run.entry.submit(code);
        val review = run.review();
        review.load();
        run.assertCell("u6", loadable(review));
    }

    // ---- the detail screen: u7–u13 -----------------------------------------

    @Test
    fun `u7 pops back to an entry screen that is idle again`() = runTest {
        val run = Run(Fixtures.ready());
        val review = run.reach();
        review.back();
        run.assertCell("u7", busy(run.entry));
    }

    @Test
    fun `u7b treats the system back gesture as the flow's own pop`() = runTest {
        val run = Run(Fixtures.ready());
        run.reach();
        run.flow.pop();
        run.assertCell("u7b", busy(run.entry));
    }

    @Test
    fun `u8 approves and closes`() = runTest {
        val run = Run(Fixtures.ready());
        val review = run.reach();
        review.approve();
        run.assertCell("u8", loadable(review));
        assertEquals(1, run.service.approveCount);
    }

    @Test
    fun `u8c drops an approval that answers after the flow closed`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.writeRefused());
        val review = run.reach();
        run.hold(gate);
        val write = launch { review.approve() };
        runCurrent();
        run.flow.close();
        gate.complete(Unit);
        write.join();
        run.assertCell("u8c", loadable(review));
    }

    /**
     * R9: the route the write was sent from is gone before the answer arrives.
     *
     * The flow is still presented and the generation is still the write's own — the pop
     * was the system's gesture, not this model's `back` — so the two older guards both
     * say yes. Only the route check refuses, and without it `approve`'s `then: close`
     * would empty a stack the person is standing on.
     */
    @Test
    fun `u8d drops an approval whose route was popped while it was in flight`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.ready());
        val review = run.reach();
        run.hold(gate);
        val write = launch { review.approve() };
        runCurrent();
        run.flow.pop();
        gate.complete(Unit);
        write.join();
        run.assertCell("u8d", loadable(review));
        assertEquals("the write never reached the service", 1, run.service.approveCount);
    }

    /**
     * R9 again, with the screen buried rather than dropped.
     *
     * The route is still on the stack — the app pushed a second `enterCode` over it — so a
     * guard asking whether the stack CONTAINS this screen's route says yes, and `approve`'s
     * `then: close` would empty a stack whose top screen the person is standing on and
     * never asked to leave. On show means on top, which is what the guard asks.
     */
    @Test
    fun `u8e drops an approval buried under a second copy of its own route`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.ready());
        val review = run.reach();
        val before = review.state.value;
        run.hold(gate);
        val write = launch { review.approve() };
        runCurrent();
        run.flow.push(ApproveDeviceRoute.EnterCode);
        gate.complete(Unit);
        write.join();
        run.assertCell("u8e", loadable(review));
        assertEquals(
            "the buried answer moved the stack",
            listOf(
                ApproveDeviceRoute.EnterCode,
                ApproveDeviceRoute.ReviewDevice(userCode = code),
                ApproveDeviceRoute.EnterCode
            ),
            run.flow.stack.value
        );
        assertEquals("the buried screen's own state moved", before, review.state.value);
        assertTrue("the flow was closed under the person", run.flow.isPresented.value);
    }

    @Test
    fun `u9 denies and closes`() = runTest {
        val run = Run(Fixtures.ready());
        val review = run.reach();
        review.deny();
        run.assertCell("u9", loadable(review));
        assertEquals(1, run.service.denyCount);
    }

    @Test
    fun `u9c drops a denial that answers after the flow closed`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.writeRefused());
        val review = run.reach();
        run.hold(gate);
        val write = launch { review.deny() };
        runCurrent();
        run.flow.close();
        gate.complete(Unit);
        write.join();
        run.assertCell("u9c", loadable(review));
    }

    @Test
    fun `u10 pops from a detail screen standing in error`() = runTest {
        val run = Run(Fixtures.sourceRefused());
        val review = run.reach();
        review.back();
        run.assertCell("u10", busy(run.entry));
    }

    @Test
    fun `u10b treats the system back gesture the same way from error`() = runTest {
        val run = Run(Fixtures.sourceRefused());
        run.reach();
        run.flow.pop();
        run.assertCell("u10b", busy(run.entry));
    }

    @Test
    fun `u11 ignores a write over a value the screen has not read yet`() = runTest {
        val gate = CompletableDeferred<Unit>();
        val run = Run(Fixtures.ready());
        run.entry.submit(code);
        val review = run.review();
        run.hold(gate);
        val read = launch { review.load() };
        runCurrent();
        review.approve();
        assertEquals("a write ran over a screen that was still loading", 0, run.service.approveCount);
        run.assertCell("u11", loadable(review));
        gate.complete(Unit);
        read.join();
    }

    @Test
    fun `u12 re-reads the source without moving the stack`() = runTest {
        val run = Run(Fixtures.sourceRefusedOnce());
        val review = run.reach();
        review.retry();
        run.assertCell("u12", loadable(review));
    }

    @Test
    fun `u13 carries the source's refusal with the stack where it was`() = runTest {
        val run = Run(Fixtures.sourceRefused());
        val review = run.reach();
        run.assertCell("u13", loadable(review));
    }

    @Test
    fun `u14 opens on a whole stack and reads the source exactly once`() = runTest {
        val fixture = Fixtures.deepReady();
        val run = Run(fixture);
        run.flow.open(requireNotNull(fixture.openAt));
        val review = run.review();
        review.load();
        run.assertCell("u14", loadable(review));
        assertEquals("a deep entry read its source more than once", 1, run.service.lookupCount);
    }

    // ---- the harness this suite is -----------------------------------------

    /**
     * One run of the app's own graph.
     *
     * `AppContainer` rather than three hand-built objects, so the wiring under test is the
     * wiring the app uses: one flow, one service, and a model per screen appearance.
     */
    private class Run(fixture: Fixture)
    {
        private var gate: CompletableDeferred<Unit>? = null;

        val service: FakeDeviceApprovalService = fixture.service { gate?.await() };

        val container: AppContainer = AppContainer(service);

        val flow: Flow<ApproveDeviceRoute> = container.approveDeviceFlow;

        val entry: EnterCodeModel = container.enterCodeModel();

        /** Makes every later call wait on [gate], so an in-flight state can be inspected. */
        fun hold(gate: CompletableDeferred<Unit>)
        {
            this.gate = gate;
        }

        fun review(): ReviewDeviceModel = container.reviewDeviceModel(Fixtures.USER_CODE)

        /** Submits and reads, which is how every detail-screen cell is reached. */
        suspend fun reach(): ReviewDeviceModel
        {
            entry.submit(Fixtures.USER_CODE);
            val review = review();
            review.load();
            return review;
        }

        /**
         * The readouts a runner would see, compared against the table's own.
         *
         * One actual value per readout the TABLE names, so a cell that names a readout
         * this suite cannot produce fails rather than being quietly skipped.
         */
        fun assertCell(cell: String, state: String)
        {
            val expected = CaseTableReader.expect(cell);
            val actual = expected.map { readout ->
                when
                {
                    readout.startsWith("stack=") -> "stack=" + flow.stack.value.size
                    readout.startsWith("state=") -> "state=$state"
                    else -> error("cell $cell names a readout this suite cannot produce: $readout")
                }
            };
            assertEquals("cell $cell", expected, actual);
        }
    }

    private fun busy(model: EnterCodeModel): String = when (model.state.value)
    {
        is Busy.Idle -> "idle"
        is Busy.Busy -> "busy"
        is Busy.Error -> "error"
    }

    private fun loadable(model: ReviewDeviceModel): String = when (model.state.value)
    {
        is Loadable.Loading -> "loading"
        is Loadable.Ready -> "ready"
        is Loadable.Empty -> "empty"
        is Loadable.Error -> "error"
    }
}
