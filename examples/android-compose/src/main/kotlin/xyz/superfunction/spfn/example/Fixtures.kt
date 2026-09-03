// SPFN Mobile — which seeding a cell runs under.
//
// One `when` over cell ids, and the only thing that installs a fake service. A launch that
// names no cell reaches `null` here, and MainActivity builds the live container instead —
// so the fixture code is inert in a build that was not asked for one rather than being
// switched off by a flag inside it.
//
// The mapping is not free-hand: `examples/ui-spec/generated/device-approval.cases.json`
// records a fixture name per cell, and `FixtureTableTest` fails if the two disagree. That
// is the cheap half of P10 — the table and the app are written separately, so somebody has
// to compare them.

package xyz.superfunction.spfn.example

import kotlinx.coroutines.delay
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute

/** One seeded run: what the reads answer, how long they take, and where the flow opens. */
class Fixture(
    /** The seeding's own name, as `device-approval.cases.json` records it. */
    val name: String,
    /** What each successive read does; the last entry repeats. */
    val answers: List<Answer>,
    /** What each successive write does; the last entry repeats. */
    val writeAnswers: List<Answer>,
    /** How long every call waits before answering. Zero for every fixture but `slow`. */
    val pauseMillis: Long,
    /**
     * The stack the flow is opened at, or null to leave it on the screen the spec named
     * as its start. Cell u14 is the one that names a whole stack.
     */
    val openAt: List<ApproveDeviceRoute>?
)
{
    /**
     * A fresh service for one run.
     *
     * `pause` is what a call waits on beyond this fixture's own delay. The app passes
     * nothing; the unit suite passes a gate it opens itself, so a test about an in-flight
     * state is a test about ordering rather than about timing.
     */
    fun service(pause: suspend () -> Unit = {}): FakeDeviceApprovalService =
        FakeDeviceApprovalService(answers, writeAnswers)
        {
            if (pauseMillis > 0)
            {
                delay(pauseMillis);
            }
            pause();
        }
}

object Fixtures
{
    /** The code every flow types, and therefore the code a deep entry arrives holding. */
    const val USER_CODE: String = "ABCD-1234";

    /** How long a `slow` call waits, so a person can watch an in-flight state. */
    private const val PAUSE_MILLIS: Long = 1_500;

    /**
     * The fixture a cell runs under, or null when the launch named no cell this app knows.
     *
     * Null is the fail-closed answer and it is not an error: a person who launches this
     * app from the launcher passes no argument at all, and gets the real thing.
     */
    fun forCell(cell: String): Fixture? = when (cell)
    {
        "u1", "u1c", "u2", "u5", "u6", "u7", "u7b", "u8", "u8d", "u8e", "u9" -> ready()
        // The keyboard contract and the screen frame, which are about components rather than
        // about a service: every one of them runs against a server that simply answers, and
        // what the cell is looking at is the field, the return key or the header control.
        "k1", "k2", "k3", "k4", "k5", "k6", "k7", "s1", "s2" -> ready()
        "u3", "u11" -> slow()
        "u8c", "u9c" -> writeRefused()
        "u4" -> refused()
        "u10", "u10b", "u13" -> sourceRefused()
        "u12" -> sourceRefusedOnce()
        "u14" -> deepReady()
        else -> null
    }

    /** Every read and every write answers. */
    fun ready(): Fixture = Fixture("ready", listOf(Answer.OK), listOf(Answer.OK), 0, null)

    /** Every call waits before answering, so an in-flight state can be observed. */
    fun slow(): Fixture = Fixture("slow", listOf(Answer.OK), listOf(Answer.OK), PAUSE_MILLIS, null)

    /**
     * Every read answers and every write refuses.
     *
     * It is what makes the late-answer rule observable: a write that succeeded after its
     * flow closed changes nothing whether the guard is there or not, because closing a
     * closed flow is a no-op. A write that failed would write an error into a screen
     * nobody is looking at.
     */
    fun writeRefused(): Fixture =
        Fixture("writeRefused", listOf(Answer.OK), listOf(Answer.REFUSE), 0, null)

    /** Every read refuses, so the entry screen's own call never gets as far as pushing. */
    fun refused(): Fixture = Fixture("refused", listOf(Answer.REFUSE), listOf(Answer.OK), 0, null)

    /** The first read answers and every later one refuses: a detail screen standing in error. */
    fun sourceRefused(): Fixture =
        Fixture("sourceRefused", listOf(Answer.OK, Answer.REFUSE), listOf(Answer.OK), 0, null)

    /** The first answers, the second refuses, the third answers: a retry that recovers. */
    fun sourceRefusedOnce(): Fixture =
        Fixture("sourceRefusedOnce", listOf(Answer.OK, Answer.REFUSE, Answer.OK), listOf(Answer.OK), 0, null)

    /** Every read answers, and the flow is opened at a whole stack rather than pushed onto. */
    fun deepReady(): Fixture = Fixture(
        "deepReady",
        listOf(Answer.OK),
        listOf(Answer.OK),
        0,
        listOf(ApproveDeviceRoute.EnterCode, ApproveDeviceRoute.ReviewDevice(userCode = USER_CODE))
    )
}
