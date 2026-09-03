// SPFN Mobile — which seeding a cell runs under, and which flow it opens on.
//
// One `when` over cell ids, and the only thing that decides what this app does at launch. A
// launch that names a cell this app knows opens that cell's flow on that cell's seeding; a
// launch that names none opens the MENU, on the same fake nothing else can reach.
//
// The menu is on a fake and not on a server, and that is not the fail-closed rule bending.
// This app has no enrolment path of its own — enrolment is what tools/harness exists to
// drive — so a client it built against a configured server would refuse every call for want
// of a key, and the screen a person got for pressing a menu button would be a refusal that
// says nothing about the screens. There is no real-server path here at all: the app declares
// no INTERNET permission and sends nothing, whichever way it was launched.
//
// The mapping is not free-hand: `examples/ui-spec/generated/device-approval.cases.json`
// records a fixture name per cell, and `FixtureTableTest` fails if the two disagree. That
// is the cheap half of P10 — the table and the app are written separately, so somebody has
// to compare them.

package xyz.superfunction.spfn.example

import kotlinx.coroutines.delay
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute

/** One seeded run: which flow it opens, what the reads answer, and how long they take. */
class Fixture(
    /** The seeding's own name, as `device-approval.cases.json` records it. */
    val name: String,
    /**
     * The flow this launch opens, by the name the spec gives it, or null for the menu.
     *
     * The container opens every flow it holds, so something has to say which one is on show
     * — see `Flows.openOnly`. Null is the menu: every flow closed, and a person choosing.
     */
    val flow: String?,
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

    /** The flow the case table's own cells are about. */
    const val APPROVE_DEVICE: String = "approveDevice";

    /** How long a `slow` call waits, so a person can watch an in-flight state. */
    private const val PAUSE_MILLIS: Long = 1_500;

    /**
     * The flows the showcase adds, which every cell of theirs runs `ready` against.
     *
     * Written out rather than derived from a cell id alone. A showcase cell is named
     * `<flow>-<what>` by the generator, so the flow IS readable off the id — but reading it
     * off an id nobody checked would open a flow for `nonsense-cell` and report the app as
     * configured. The id gives the name and this set is what says the name is real.
     */
    private val SHOWCASE_FLOWS: Set<String> = setOf(
        "pushTour",
        "modalTour",
        "sheetFit",
        "sheetHalf",
        "sheetFull",
        "sheetNav",
        "keyboardForm",
        "longScroll"
    );

    /**
     * The fixture a cell runs under, or null when the launch named no cell this app knows.
     *
     * Null is the menu and it is not an error: a person who launches this app from the
     * launcher passes no argument at all, and gets the list of flows.
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
        else -> showcase(cell)
    }

    /** The menu: the same fake every cell gets, and no flow opened. */
    fun menu(): Fixture = Fixture("ready", null, listOf(Answer.OK), listOf(Answer.OK), 0, null)

    /**
     * A showcase cell's fixture, read off its own `<flow>-<what>` id, or null.
     *
     * Every showcase cell answers `ready`: those flows read and write nothing, and the one
     * that does — the form — is there for its keyboard and not for its refusals.
     */
    private fun showcase(cell: String): Fixture?
    {
        val flow = cell.substringBefore('-');
        if (flow == cell || flow !in SHOWCASE_FLOWS)
        {
            return null;
        }
        return Fixture("ready", flow, listOf(Answer.OK), listOf(Answer.OK), 0, null);
    }

    /** Every read and every write answers. */
    fun ready(): Fixture = Fixture("ready", APPROVE_DEVICE, listOf(Answer.OK), listOf(Answer.OK), 0, null)

    /** Every call waits before answering, so an in-flight state can be observed. */
    fun slow(): Fixture =
        Fixture("slow", APPROVE_DEVICE, listOf(Answer.OK), listOf(Answer.OK), PAUSE_MILLIS, null)

    /**
     * Every read answers and every write refuses.
     *
     * It is what makes the late-answer rule observable: a write that succeeded after its
     * flow closed changes nothing whether the guard is there or not, because closing a
     * closed flow is a no-op. A write that failed would write an error into a screen
     * nobody is looking at.
     */
    fun writeRefused(): Fixture =
        Fixture("writeRefused", APPROVE_DEVICE, listOf(Answer.OK), listOf(Answer.REFUSE), 0, null)

    /** Every read refuses, so the entry screen's own call never gets as far as pushing. */
    fun refused(): Fixture =
        Fixture("refused", APPROVE_DEVICE, listOf(Answer.REFUSE), listOf(Answer.OK), 0, null)

    /** The first read answers and every later one refuses: a detail screen standing in error. */
    fun sourceRefused(): Fixture =
        Fixture("sourceRefused", APPROVE_DEVICE, listOf(Answer.OK, Answer.REFUSE), listOf(Answer.OK), 0, null)

    /** The first answers, the second refuses, the third answers: a retry that recovers. */
    fun sourceRefusedOnce(): Fixture = Fixture(
        "sourceRefusedOnce",
        APPROVE_DEVICE,
        listOf(Answer.OK, Answer.REFUSE, Answer.OK),
        listOf(Answer.OK),
        0,
        null
    )

    /** Every read answers, and the flow is opened at a whole stack rather than pushed onto. */
    fun deepReady(): Fixture = Fixture(
        "deepReady",
        APPROVE_DEVICE,
        listOf(Answer.OK),
        listOf(Answer.OK),
        0,
        listOf(ApproveDeviceRoute.EnterCode, ApproveDeviceRoute.ReviewDevice(userCode = USER_CODE))
    )
}
