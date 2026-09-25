// Where the case table's expectations come from.
//
// This file is the RULE table, and it is deliberately not the emitters' input. The models
// are generated from the spec; the cells below are written from the rules a screen model
// must obey, expressed against the spec's SHAPE — which screen a flow starts on, which
// action pushes, which one closes. So the unit suite that drives the generated models
// against these cells compares two independent derivations of the same behaviour rather
// than comparing the generator with itself (docs/IMPLEMENTATION-PITFALLS.md P10).
//
// The rules themselves, stated once:
//
//   R1  a write refuses an empty required input and makes no call;
//   R2  a write already in flight ignores a second press;
//   R3  an action that needs a value the screen has not read yet is ignored;
//   R4  a response that arrives after its flow closed changes nothing;
//   R5  `then` is applied only after the call succeeded — close empties the stack, pop
//       drops one route and is a no-op on the last, push adds one;
//   R6  a screen with a source loads it once when it appears, however it appeared —
//       and what that says about a SECOND appearance is open decision D27: Swift's
//       `.task` reads again on every appearance, Kotlin's `LaunchedEffect(model)`
//       reads once per model, and no cell below tells the two apart;
//   R7  a failed call leaves the screen in its error state and the stack where it was;
//   R8  the system back gesture is the flow's own pop, and on a modal flow's last route
//       it is the flow's close;
//   R9  a response for a screen no longer on show changes nothing.
//
// And the keyboard contract, which is about the COMPONENTS rather than about the models and
// is therefore proven on a device and nowhere else:
//
//   K1  a screen's body gets out of the keyboard's way, so a control below the field is
//       still reachable while the keyboard is up;
//   K2  a tap outside the field puts the keyboard away and changes nothing else;
//   K3  `autofocus` means the field already holds the focus when the screen appears;
//   K4  `submitOnReturn` means the return key performs the screen's action;
//   K5  the return key still performs it after the keyboard was put away and the field
//       refocused;
//   K6  editing the field clears the refusal drawn under it;
//   K7  a refused input draws its refusal UNDER the field, in the SDK's own words.
//
// and the screen frame, which is the close table of 3a asserted on a device rather than on
// the JVM:
//
//   S1  the root of a flow presented over something draws a close, and it closes the flow;
//   S2  a route above the root draws a back, and it pops.
//
// and the platform headers (docs/architecture/screen-header-design.md §4), whose case ids are
// the design's C1–C12. Most of them land on a cell that already existed: C1 and C9 are u7b and
// u10b (the edge swipe on iOS, the system back on Android), C3 is `<flow>-rootSystemBack`, C4 is
// s2, C5 is s1 and C12 is k2. The rest are new: C2 `<flow>-contentSwipe`, C6 `<flow>-trailing`,
// C10 `<flow>-wayOutBack` and `<flow>-noHeaderSystemBack`, C11 `<flow>-wayOutClose`, and the two
// a person looks at, C7 `<flow>-barSpace` and C8 `<flow>-barTheme`. On iOS the header's back
// is the system navigation bar's button, found by its accessibility label (`Step.HeaderBack`),
// because the SDK no longer draws one that carries `screen.back`.
//
// R9 is R4's other half and not a restatement of it. R4 is about the whole flow going
// away, which a screen model sees as `isPresented`; R9 is about ONE screen ceasing to be
// the one on show under an in-flight call, which leaves the flow presented and — when the
// route went away under the system's back gesture rather than the screen's own action —
// leaves the generation where it was too. Both guards are needed and neither implies the
// other.
//
// ON SHOW, not on the stack. A screen stops being on show two ways: its route is dropped,
// or another route is put over it — and `Flow` accepts any nonempty order, so the route
// put over it may be a second copy of the screen's own. A rule written as membership would
// accept a response for a screen buried under that copy and run its `then` over the screen
// the person is standing on, which is u8e.
//
// The cell ids are this repository's: u1–u14 for the base table, u7b/u10b for the system
// back variants of the two back-button cells, u8c/u9c for the late-response variants of
// the two closing writes, and u1c/u8d/u8e for the three late responses that arrive to a
// stack that has moved under them. k1–k7 and s1–s2 are the keyboard contract and the screen
// frame, both of them device-only.
//
// ---------------------------------------------------------------------------
// The showcase flows, and the fourth runner
// ---------------------------------------------------------------------------
//
// Everything above is about ONE flow — the one that reads on a screen and writes on the
// next — and the spec now carries seven more that read and write nothing at all. Those exist
// so the three presentations, a stack inside a sheet, a keyboard and a body that does not fit
// can be looked at; their ids are `<flow>-<what>`, because a terse letter per flow would be a
// table with a key nobody could keep.
//
// Two things bound them. Each RULE row gets one cell on one representative flow, and each
// flow gets one representative of its own — the way out, which is the only thing all of them
// have and the one thing that has to work for any of them to leave a receipt. So a two-deep
// stack earns a cell inside a sheet and not inside a modal, because u1 already stands two
// deep in a modal, and a drag row is written once rather than once per sheet.
//
// The fourth runner is `manual`, and it exists because of what P22 is really about. A device
// runner can be told to swipe; what it cannot do is make the platform READ that swipe as the
// gesture it meant, and a command that completes without doing anything reports success. So
// every gesture whose subject is the gesture — an edge swipe, a predictive back held at the
// edge, a drag released either side of a dismissal threshold, a detent that snaps, a keyboard
// that has to feel like it got out of the way, a header that must not scroll — is a cell with
// no runner and a person's name on it. It goes in the case table like every other cell, in a
// section of its own, and `examples/ui-spec/receipts/manual/` is where the answers go.
//
// Three of the k cells are CONDITIONAL, and that is the point of deriving them rather than
// listing them: k3 exists only where the spec says `autofocus`, and k4 and k5 only where it
// says `submitOnReturn`. A cell asserting that the return key submits, on a screen whose spec
// turned that off, would be a table claiming behaviour nobody asked for — and it would fail,
// which is worse than absent because it reads as a defect in the component.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

/** One thing a runner does to the app. */
sealed interface Step
{
    /** Types into the control with the id `<screen>.<field>`. */
    data class Type(val id: String, val value: String) : Step

    /** Presses the control with the id `<screen>.<action>`. */
    data class Tap(val id: String) : Step

    /** The platform's own back gesture, which is not a control this app draws. */
    data object SystemBack : Step

    /**
     * The header's back, pressed. Android's is the SDK's own control, found by `screen.back`;
     * iOS's is the system navigation bar's back button, which carries no identifier of the
     * SDK's and is found by its accessibility LABEL instead: the title of the screen it goes
     * back to, or the platform's own "Back" when that screen has none.
     */
    data class HeaderBack(val label: String) : Step

    /**
     * A back gesture that starts in the CONTENT rather than at the edge. iOS 26's own second
     * back gesture; Android has no such gesture, and its half is the system back.
     */
    data object ContentSwipe : Step

    /** Waits for a readout to reach a value before going on. */
    data class Await(val readout: String) : Step

    /**
     * Types into whatever holds the focus, without tapping anything first.
     *
     * The one step that asserts something by NOT doing something: it is how `autofocus` is
     * observed at all, because a step that tapped the field first would pass whether the
     * field took focus by itself or not.
     */
    data class TypeFocused(val value: String) : Step

    /** Presses the keyboard's return key. */
    data object Return : Step

    /** Puts the keyboard away, the way a tap outside the field does. */
    data object HideKeyboard : Step

    /** Asserts that the control with this id is on screen. */
    data class SeeId(val id: String) : Step

    /**
     * Scrolls until the control with this id is on screen.
     *
     * A no-op when it is already there, which is why it is emitted for every screen that
     * declares a body rather than for the one body that happens to be long today: a body
     * that grew past the fold would otherwise turn a passing cell into a failing one for a
     * reason that is not a defect.
     */
    data class ScrollTo(val id: String) : Step

    /**
     * Scrolls the rows a screenful, which is what asks a paged screen for its next page.
     *
     * A paged screen has no "load more" control to press. `PagedView` fires `onLoadMore` when
     * the end of the rows is laid out, on both platforms, so a runner asks for a page the way
     * a person does — and a cell that pressed a button instead would be proving something no
     * finger ever does.
     *
     * A screenful rather than `scrollUntilVisible` on the last row, because which rows a
     * fixture answers with is the fixture's business and what a row is CALLED — the readout
     * `item=<name>` — is the contract document's. This table names `count=` and nothing else
     * about the rows.
     */
    data object ScrollRows : Step

    /**
     * Scrolls until a READOUT is on screen, wherever the view put it.
     *
     * By text and not by id, because that is how a readout is found on both platforms
     * (SCHEMA.md, the selector rules): an id is fixed at build time and a readout's whole
     * point is that its value moves. A no-op when the readout is already visible, which is
     * why every paged cell carries one rather than the ones whose list happens to be long.
     */
    data class ScrollToReadout(val pattern: String) : Step

    /**
     * What a person does, in words, for a cell no runner drives.
     *
     * The one step with no command behind it. A gesture is the class of thing a device
     * runner cannot hold still — an edge swipe that has to be recognised as an edge swipe, a
     * drag released at a threshold, a detent that snaps — and a cell that claimed a runner
     * for one would be a green row proving that a command completed (P22).
     */
    data class ByHand(val description: String) : Step
}

/**
 * One cell of the table: a screen, the state it is in, the act, and what must then be
 * true. `expect` is written in readouts only — `state=…` and `stack=…` — because those
 * are the two things both runners can read and neither can guess.
 */
data class Cell(
    val id: String,
    val screen: String,
    val state: String,
    val action: String,
    val rule: String,
    /** `unit`, `maestro`, `both`, or `manual`. */
    val runner: String,
    val fixture: String,
    val steps: List<Step>,
    val expect: List<String>,
    /**
     * How a runner leaves the app once the assertions have been made: the flow's own
     * controls, pressed until the stack is empty. It is derived from the spec rather
     * than written per cell — the entry screen's closing action and the detail screen's
     * popping action are the only two ways this flow can be left — and it exists because
     * the receipt is written from the app's root, which a modal flow covers on iOS.
     */
    val teardown: List<Step> = emptyList()
)
{
    val runsOnMaestro: Boolean get() = runner == "maestro" || runner == "both";

    val runsAsUnitTest: Boolean get() = runner == "unit" || runner == "both";

    /** Whether this cell is a person's to check, and therefore has no runner at all. */
    val runsByHand: Boolean get() = runner == "manual";
}

/**
 * The seedings a fixture can install, named by what the source read does under each. The
 * example apps hold the seeding itself; this is only the vocabulary the table uses.
 *
 * The distinction that matters is WHICH read refuses. `refused` refuses every read, so the
 * entry screen's own call never gets as far as pushing; `sourceRefused` lets that first
 * read through and refuses the pushed screen's, which is the only way to reach a detail
 * screen standing in its error state.
 */
object Fixtures
{
    /** Every read and every write answers. */
    const val READY: String = "ready";

    /** Every call waits before answering, so an in-flight state can be observed. */
    const val SLOW: String = "slow";

    /**
     * Every read answers and every write refuses.
     *
     * It is what makes R4 observable at all. A write that SUCCEEDS after its flow closed
     * changes nothing whether the guard is there or not — the `then` is `close`, and
     * closing a closed flow is a no-op — so a cell built on one would pass with the guard
     * removed. A write that FAILS after its flow closed would write an error into a screen
     * nobody is looking at, and that is the thing the guard prevents.
     */
    const val WRITE_REFUSED: String = "writeRefused";

    /** Every read refuses. */
    const val REFUSED: String = "refused";

    /** The first read answers and every later one refuses. */
    const val SOURCE_REFUSED: String = "sourceRefused";

    /** The first read answers, the second refuses, and the third answers again. */
    const val SOURCE_REFUSED_ONCE: String = "sourceRefusedOnce";

    /** Every read answers, and the flow is opened at a whole stack rather than pushed onto. */
    const val DEEP_READY: String = "deepReady";

    // ---- what a paged read answers, page by page ---------------------------
    //
    // A paged fixture is named for the SHAPE of its answers and not for its rows: three rows
    // then two is `pagesTwo` on every run, so a cell asserting `count=5` is asserting the
    // fixture's arithmetic rather than a list somebody may lengthen. The example app writes
    // these by hand (2c); this is the vocabulary the table asks it for.

    /** Two pages: three rows and a cursor, then two rows and no cursor after them. */
    const val PAGES_TWO: String = "pagesTwo";

    /** One page: three rows and no cursor, so the server said there is nothing after it. */
    const val PAGES_ONE: String = "pagesOne";

    /** A first page with no rows and no cursor. */
    const val PAGES_NONE: String = "pagesNone";

    /** The first page refuses, so nothing ever reaches the screen. */
    const val FIRST_REFUSED: String = "firstRefused";

    /** The first page answers three rows and a cursor; every page after it refuses. */
    const val SECOND_REFUSED: String = "secondRefused";

    /** The same, except that the SECOND ask for the second page answers its two rows. */
    const val SECOND_REFUSED_ONCE: String = "secondRefusedOnce";

    /** The first page answers at once and the second waits, so an append is visible in flight. */
    const val SLOW_SECOND: String = "slowSecond";

    /** The code a fixture answers for. One value, so every cell types the same thing. */
    const val USER_CODE: String = "ABCD-1234";
}

/**
 * The spec's shape, named by role rather than by screen name.
 *
 * The rules below are about a flow that reads on one screen and writes on the next, which
 * is the only shape 1단계 covers. A spec of another shape is refused here rather than
 * producing a table with holes in it: a case table that quietly skipped a screen would
 * report full coverage of a flow nobody exercised (P7).
 */
private class Roles(spec: Spec, val flow: FlowDefinition)
{
    val entry: ScreenDefinition = spec.screenNamed(flow.start);
    val submit: ActionDefinition = one(entry, "calls a service and pushes") { it.call != null && it.then is Navigation.Push };
    val cancel: ActionDefinition = one(entry, "only navigates") { it.call == null };
    val detail: ScreenDefinition = spec.screenNamed((submit.then as Navigation.Push).screen);

    /** The detail screen's re-read of its own source, whatever the spec called it. */
    val retry: ActionDefinition = one(detail, "re-reads its own source") { it.call?.reference == detail.source?.reference };

    /** Its writes: every call that is not that re-read. */
    val commits: List<ActionDefinition> =
        detail.actions.filter { it.call != null && it.call.reference != detail.source?.reference };

    val back: ActionDefinition = one(detail, "only navigates") { it.call == null };
}

/**
 * The one action of [screen] that [role] describes, or a refusal that names what is there.
 *
 * `single {}` is what these were, and a spec with two matching actions is what it has
 * nothing to say about: it throws `IllegalArgumentException("Collection contains more than
 * one matching element")`, which reaches an author as a sentence with no screen in it, no
 * action, and no file to look in — from a generator whose every other refusal names the path
 * the spec can be searched for. The zero case was no better: `NoSuchElementException`.
 *
 * So both are a refusal of this file's own, and it prints the three things a person needs:
 * which screen, how many actions matched, and which ones.
 */
private fun one(
    screen: ScreenDefinition,
    role: String,
    match: (ActionDefinition) -> Boolean
): ActionDefinition
{
    val matching = screen.actions.filter(match);
    return matching.singleOrNull() ?: throw SpecException(
        "the case rules need exactly one action on '${screen.name}' that $role, and it declares " +
            "${matching.size}" +
            (if (matching.isEmpty()) "" else ": " + matching.joinToString(", ") { it.name }) +
            "; the screen's actions are: " + screen.actions.joinToString(", ") { it.name }
    );
}

/**
 * A showcase flow's screens in the order a person walks them.
 *
 * These flows read nothing and write nothing — the presentation IS the subject — so their
 * shape is one chain: a start screen, whatever it pushes, whatever that pushes. Everything
 * the rules below need is a position in that chain and the action at it.
 *
 * A flow with no way out is refused here rather than producing a cell that leaves the app
 * covered: every showcase flow's deepest screen has to carry an action that closes, because
 * that is the one thing a runner must be able to do before it writes a receipt on the root.
 */
private class Tour(spec: Spec, val flow: FlowDefinition)
{
    val chain: List<ScreenDefinition> = build(spec, flow);

    /** The screen the chain ends on, which is where every cell of this flow finishes. */
    val deepest: ScreenDefinition get() = chain.last();

    /** The action on [deepest] that empties the stack. */
    val closing: ActionDefinition = deepest.actions.firstOrNull { it.then == Navigation.Close }
        ?: throw SpecException(
            "the case rules need a way out of '${flow.name}': '${deepest.name}' is the screen " +
                "it ends on and no action of it closes the flow"
        );

    /** The action that moves on from the screen at [depth], counted from one. */
    fun pushing(depth: Int): ActionDefinition
    {
        val screen = chain[depth - 1];
        return screen.actions.firstOrNull { it.then is Navigation.Push }
            ?: throw SpecException(
                "the case rules walk '${flow.name}' to depth $depth and no action of '${screen.name}' " +
                    "pushes; the screen's actions are: " +
                    screen.actions.joinToString(", ") { it.name }
            );
    }

    private companion object
    {
        fun build(spec: Spec, flow: FlowDefinition): List<ScreenDefinition>
        {
            val chain = mutableListOf(spec.screenNamed(flow.start));
            while (true)
            {
                val next = chain.last().actions.firstOrNull { it.then is Navigation.Push } ?: return chain;
                val target = spec.screenNamed((next.then as Navigation.Push).screen);
                // A spec can only push within its own flow (refusal 3), so the only way a
                // chain can fail to end is a cycle back onto a screen it already holds.
                if (chain.any { it.name == target.name })
                {
                    throw SpecException(
                        "the case rules walk '${flow.name}' from its start and '${target.name}' " +
                            "is pushed twice; a showcase flow is a chain and not a loop"
                    );
                }
                chain += target;
            }
        }
    }
}

object Rules
{
    /**
     * The whole table, in cell order: the flow that reads and writes, then the showcase.
     *
     * The split is by SHAPE and not by name. Exactly one flow in this spec has a screen with
     * a source, and everything from u1 to s2 is written about that shape — an entry screen
     * that submits into a detail screen that reads. The rest read nothing: they exist so the
     * three presentations, the two stacks and the two bodies can be looked at, and what is
     * worth asserting about one of those is a different, much shorter list.
     */
    fun cells(spec: Spec, bundle: Bundle): List<Cell>
    {
        val approval = approvalCells(spec, bundle);
        val paged = pagedCells(spec, bundle);
        val forms = formCells(spec, bundle);
        if (approval.isEmpty() && paged.isEmpty() && forms.isEmpty())
        {
            throw SpecException(
                "the case rules cover a flow that reads, a screen that reads a page at a time and a " +
                    "screen that collects a form; this spec declares none of the three"
            );
        }
        return approval + paged + forms;
    }

    /**
     * u1–s2 and the showcase: everything written about the shape 1단계 started from.
     *
     * Empty rather than a refusal when no flow reads, because a spec can now be nothing but a
     * list and a form — those two screens are covered by tables of their own, and a spec that
     * carries neither this shape nor one of them is what [cells] refuses.
     */
    private fun approvalCells(spec: Spec, bundle: Bundle): List<Cell>
    {
        val reading = spec.flows.filter { f -> spec.screensOf(f).any { it.isLoadable } };
        if (reading.isEmpty())
        {
            return emptyList();
        }
        val flow = reading.singleOrNull()
            ?: throw SpecException(
                "the case rules cover exactly one flow that reads; this spec declares ${reading.size}"
            );
        // `Roles` refuses by name when the shape is not there: which screen, how many actions
        // matched the role, and which ones. It used to be wrapped in a catch that turned a
        // collection exception into one sentence about the whole shape.
        val roles = Roles(spec, flow);
        if (roles.entry.isLoadable || !roles.detail.isLoadable)
        {
            throw SpecException(
                "the case rules cover a flow that reads on the screen it pushes; '${flow.start}' and " +
                    "'${roles.detail.name}' are the wrong way round"
            );
        }
        if (roles.commits.size != 2)
        {
            throw SpecException(
                "the case rules expect two writes on '${roles.detail.name}'; the spec declares " +
                    "${roles.commits.size}"
            );
        }
        // Refuses a source request this generator cannot carry on a route before any cell
        // claims to cover a screen that cannot be generated.
        RouteParameters.of(roles.detail, bundle);

        val input = RouteParameters.inputs(roles.entry, roles.submit, bundle).singleOrNull()
            ?: throw SpecException(
                "the case rules expect '${roles.entry.name}.${roles.submit.name}' to take exactly one " +
                    "typed input; the contract gives it another number"
            );
        val inputId = "${roles.entry.name}.${input.name}";

        val cells = entryCells(roles, inputId) + detailCells(roles, inputId) + deepEntryCell(roles) +
            keyboardCells(spec, roles, input.name, inputId) + frameCells(flow, spec, roles, inputId);
        // A flow whose screens are a list or a form is not a showcase flow. Its cells are the
        // P and F tables below, which say what a paged read and a checked form do; a way-out
        // cell on top of them would be a third table asserting what the first two already
        // stand on.
        val showcaseFlows = spec.flows.filter { it.name != flow.name && !drawnByHand(spec, it, bundle) };
        // One flow speaks for the push entry, the way one flow speaks for each rule row: what
        // a pushed root's way out does is the same rule on all three of them, and three
        // copies of it would be three chances to check one thing and no chance to check
        // another.
        //
        // The one it is is the pushed flow with the longest chain, because on a flow whose
        // root is its only screen "the root's way out" and "the flow's only way out" are the
        // same sentence and the cell stops being about the root. Ties go to the first, which
        // is the order the spec is read in.
        val representative = showcaseFlows
            .filter { it.entry == "push" }
            .maxByOrNull { Tour(spec, it).chain.size }
            ?.name;
        return cells.map { it.copy(teardown = teardown(roles, depthOf(it))) } +
            showcaseFlows.flatMap { showcase(spec, it, bundle, representsPush = it.name == representative) };
    }

    /** Whether this flow holds a screen whose views a person writes, which is the P/F tables'. */
    private fun drawnByHand(spec: Spec, flow: FlowDefinition, bundle: Bundle): Boolean =
        spec.screensOf(flow).any { it.isPaged || ScreenShape.isForm(it, bundle) }

    /**
     * One showcase flow's cells: what a runner can prove, then what only a person can.
     *
     * Two automatic cells at most, and that is the ceiling being spent deliberately. Eight
     * flows against the rule table would be dozens of cells, so each RULE row gets one cell
     * on one representative flow and each flow gets one representative of its own — the way
     * out, which is the only thing every one of them has. A depth this table already asserts
     * on another presentation is not worth a second row, which is why a two-deep stack earns
     * a cell inside a sheet and not inside a modal: `u1` already stands two deep in a modal.
     */
    private fun showcase(spec: Spec, flow: FlowDefinition, bundle: Bundle, representsPush: Boolean): List<Cell>
    {
        val tour = Tour(spec, flow);
        val cells = mutableListOf<Cell>();
        if (tour.chain.size > 2 || (tour.chain.size > 1 && flow.entry == "sheet"))
        {
            cells += reachCell(tour);
        }
        cells += closeCell(tour, bundle);
        if (representsPush)
        {
            cells += rootCells(tour);
            cells += contentSwipeCell(tour);
        }
        cells += headerCells(tour);
        return cells + byHandCells(tour, bundle);
    }

    /**
     * What a pushed flow's ROOT does, which is the row decision N2 changed.
     *
     * Both cells stand on the flow's first screen and both end with the flow closed and the
     * host's own menu back on show, because that is the claim: a pushed flow is appended to
     * the host's stack, so the way out of its first screen is a way back to the host rather
     * than a control that is not drawn at all. The old table had nothing to assert here —
     * "the host app's back" was whatever the app did — and that is exactly why a first screen
     * with no way off it reached a phone (docs/IMPLEMENTATION-PITFALLS.md P31).
     *
     * `menu.<flow>` is asserted as well as `stack=0`, and it is the half that says WHERE the
     * flow went. `stack=0` is true of a flow that closed onto a blank screen too.
     */
    private fun rootCells(tour: Tour): List<Cell>
    {
        val start = tour.chain.first();
        val menu = Step.SeeId("menu.${tour.flow.name}");
        return listOf(
            Cell(
                "${tour.flow.name}-rootBack", start.name, "idle", "headerBack",
                "N2 — the back on a pushed flow's root closes the flow, which is what hands " +
                    "the person back to the host's own screen",
                "maestro", Fixtures.READY,
                listOf(Step.HeaderBack(HOST_BACK_LABEL), menu),
                expect(0, "idle")
            ),
            Cell(
                "${tour.flow.name}-rootSystemBack", start.name, "idle", "systemBack",
                "N2 and R8 — the system back on a pushed flow's root is the same act as the " +
                    "header's, so the flow closes and the host is underneath",
                "maestro", Fixtures.READY,
                listOf(Step.SystemBack, menu),
                expect(0, "idle")
            )
        );
    }

    /**
     * C2 — the second of iOS's two back gestures, the one that starts in the content.
     *
     * Stood at depth two and not on the root, because on the root the gesture has the host's
     * screen under it and that is C3's subject (`rootSystemBack`). Android has no content
     * gesture, so its half of the same flow file is the system back, which is C9 again from a
     * different flow.
     */
    private fun contentSwipeCell(tour: Tour): List<Cell>
    {
        if (tour.chain.size < 2)
        {
            return emptyList();
        }
        return listOf(
            Cell(
                "${tour.flow.name}-contentSwipe", tour.chain[1].name, "idle", "contentSwipe",
                "C2 — on iOS a back swipe that starts in the content rather than at the edge is " +
                    "the system's own second back gesture, and it pops one route; on Android the " +
                    "system back does the same",
                "maestro", Fixtures.READY,
                walk(tour).take(1) + Step.ContentSwipe,
                expect(1, "idle"),
                teardown = unwind(tour, 1)
            )
        );
    }

    /**
     * What a screen's own header options do, from the spec's `header` keys.
     *
     * C6 on every screen whose header carries an app item: the item is the screen's own
     * action, so pressing it moves the flow the way that action says — and not one route back,
     * which is what a header control on that side of a back button could be mistaken for.
     *
     * C10 and C11 on every screen whose Android half draws no SDK header: the screen's own
     * `WayOutButton` is the flow's way out, and on a stacked screen the system back still is.
     * The iOS half of each is the system's bar, which such a screen keeps; that is the point
     * of running one flow file on both.
     */
    private fun headerCells(tour: Tour): List<Cell>
    {
        val cells = mutableListOf<Cell>();
        tour.chain.forEachIndexed { index, screen ->
            val depth = index + 1;
            val reach = walk(tour).take(index);
            val item = screen.trailingAction;
            if (item != null)
            {
                val id = "${screen.name}.${item.name}";
                cells += Cell(
                    "${tour.flow.name}-trailing", screen.name, "idle", item.name,
                    "C6 — the header's trailing item is the app's own action and not a way out: " +
                        "pressing it moves the flow the way the action says",
                    "maestro", Fixtures.READY,
                    reach + Step.SeeId(id) + Step.Tap(id),
                    expect(after(item.then, depth), "idle"),
                    teardown = unwind(tour, after(item.then, depth))
                );
            }
            if (screen.drawsOwnAndroidHeader && depth > 1)
            {
                cells += Cell(
                    "${tour.flow.name}-wayOutBack", screen.name, "idle", "headerBack",
                    "C10 — a screen whose Android half draws no SDK header offers the flow's back " +
                        "through its own WayOutButton, and it pops one route; iOS keeps the bar's",
                    "maestro", Fixtures.READY,
                    reach + Step.HeaderBack(backLabel(tour.chain[index - 1])),
                    expect(depth - 1, "idle"),
                    teardown = unwind(tour, depth - 1)
                );
                cells += Cell(
                    "${tour.flow.name}-noHeaderSystemBack", screen.name, "idle", "systemBack",
                    "C10 — with no SDK header the system back is still the flow's own pop",
                    "maestro", Fixtures.READY,
                    reach + Step.SystemBack,
                    expect(depth - 1, "idle"),
                    teardown = unwind(tour, depth - 1)
                );
            }
            if (screen.drawsOwnAndroidHeader && depth == 1 && tour.flow.presentedOver && screen.close)
            {
                cells += Cell(
                    "${tour.flow.name}-wayOutClose", screen.name, "idle", "screen.close",
                    "C11 — the root of a presented flow whose Android half draws no SDK header " +
                        "offers the flow's close through its own WayOutButton, and it closes the flow",
                    "maestro", Fixtures.READY,
                    listOf(Step.SeeId("screen.close"), Step.Tap("screen.close")),
                    expect(0, "idle")
                );
            }
        };
        return cells;
    }

    /**
     * How a cell that ends with a tour standing at [depth] leaves it closed: on down the chain
     * to the deepest screen, and that screen's close. Nothing at all at depth zero, where the
     * flow is closed already.
     */
    private fun unwind(tour: Tour, depth: Int): List<Step>
    {
        if (depth == 0)
        {
            return emptyList();
        }
        return walk(tour).drop(depth - 1) + Step.Tap("${tour.deepest.name}.${tour.closing.name}");
    }

    /**
     * What the iOS back button going back to [under] is labelled: that screen's title, or the
     * platform's "Back" — which UIKit also falls back to when a title is too long for the bar,
     * so both are accepted.
     */
    private fun backLabel(under: ScreenDefinition): String = "${literal(under.title)}|Back"

    /** [text] as a regular expression that matches exactly it: every metacharacter escaped. */
    private fun literal(text: String): String = text.map { character ->
        if (character in "\\.^$|?*+()[]{}") "\\$character" else "$character"
    }.joinToString("");

    /**
     * What the iOS back button on a pushed flow's ROOT is labelled: the host's own screen is
     * under it, and the example app's menu sets no title, so it is the platform's "Back".
     */
    private const val HOST_BACK_LABEL: String = "Back";

    /** Every tap that walks a tour from its start down to its deepest screen. */
    private fun walk(tour: Tour): List<Step> = (1 until tour.chain.size).map { depth ->
        Step.Tap("${tour.chain[depth - 1].name}.${tour.pushing(depth).name}")
    }

    /**
     * The flow stands where the pushes left it, which is what a chain of them is for.
     *
     * It ends with the stack still up, so it carries the teardown every such cell needs: the
     * receipt control is on the app's root and every presentation here covers it.
     */
    private fun reachCell(tour: Tour): Cell = Cell(
        "${tour.flow.name}-reach", tour.deepest.name, "idle", tour.pushing(tour.chain.size - 1).name,
        "R5 — every push adds one route, so the stack is as deep as the tour is long",
        "maestro", Fixtures.READY,
        walk(tour),
        expect(tour.chain.size, "idle"),
        teardown = listOf(Step.Tap("${tour.deepest.name}.${tour.closing.name}"))
    )

    /**
     * The way out, from the screen the tour ends on.
     *
     * Every showcase flow gets this one, and it is the cell that makes the rest of them
     * runnable at all: the receipt control is on the app's root, which every presentation
     * here covers, so a flow that could not be closed could not leave evidence.
     *
     * What comes before the tap is derived from the screen. A screen that collects types
     * into it first, because its closing action is the write that reads the field; a screen
     * that declares a body scrolls to the control first, because a body is exactly the thing
     * that puts one below the fold.
     */
    private fun closeCell(tour: Tour, bundle: Bundle): Cell
    {
        val screen = tour.deepest;
        val control = "${screen.name}.${tour.closing.name}";
        val typed = RouteParameters.inputs(screen, tour.closing, bundle).map { input ->
            Step.Type("${screen.name}.${input.name}", Fixtures.USER_CODE)
        };
        val reach = if (screen.bodyKey == null) emptyList()
        else listOf(Step.ScrollTo(control), Step.SeeId(control));
        return Cell(
            "${tour.flow.name}-close", screen.name, "idle", tour.closing.name,
            "R5 — close empties the stack whatever the depth and whatever presented it, so " +
                "the flow is no longer on show",
            "maestro", Fixtures.READY,
            walk(tour) + typed + reach + Step.Tap(control),
            expect(0, "idle")
        );
    }

    /**
     * What a person checks, because no runner can.
     *
     * Every one of these is a GESTURE or a resting height: an edge swipe that has to be
     * recognised as one, a drag released either side of a threshold, a detent that snaps, a
     * keyboard that has to feel like it got out of the way. A device runner can be told to
     * swipe, and it will report success whether or not the platform read the swipe as the
     * gesture it meant — which is P22 with a second name — so the honest runner here is a
     * person and the honest artefact is a checklist.
     *
     * Derived from the same shape the automatic cells are: a pushed chain has a back
     * gesture, a presentation over something has a predictive back that closes it, a sheet
     * has a height and a drag, a screen that collects has a keyboard, and a body that does
     * not fit has a header that must not go with it.
     *
     * One of them is here for a different reason. `<flow>-fingerTap` is not a gesture at
     * all — it is an ordinary tap on an ordinary control — and it is a person's cell because
     * of what the RUNNERS are. Maestro and `adb shell input tap` synthesise a DOWN and an UP
     * with nothing in between, while a finger produces MOVE events all the way through a
     * tap, and a parent that consumes those MOVEs cancels the child's press on the Final
     * pass. That is a screen no control on which can be touched, reported green by every
     * automatic cell standing on it (docs/IMPLEMENTATION-PITFALLS.md P36). A runner cannot
     * be asked to imitate a finger here: Maestro has no element-relative micro-swipe, so a
     * cell written that way would assert coordinates rather than a control.
     *
     * `<flow>-buttonEdge` is the second of those, and it is here for the WHERE rather than
     * the how. A runner's `tapOn` presses the CENTRE of the element it resolved, and the
     * centre of a button is its label — which on a plain-styled SwiftUI button is the one
     * part that was ever tappable, because the tap goes to the label's drawn pixels and the
     * fill around them was attached outside the `Button`
     * (docs/IMPLEMENTATION-PITFALLS.md P39). So a runner presses the working part of a
     * broken button and reports green, and the only tap that can tell the two apart is one
     * aimed AWAY from the words, which is a person's aim and not a runner's.
     */
    private fun byHandCells(tour: Tour, bundle: Bundle): List<Cell>
    {
        val flow = tour.flow;
        val start = tour.chain.first();
        val cells = mutableListOf<Cell>();

        if (flow.entry == "push" && tour.chain.size > 1)
        {
            cells += byHand(
                tour, "swipeBack", tour.chain[1].name, walk(tour).take(1),
                "swipe in from the left edge on iPhone, or use the system back gesture on Android",
                "S2 and R8 — the gesture is the flow's own pop, so one route drops and the " +
                    "screen under it is the one it was",
                listOf("stack=1")
            );
            cells += byHand(
                tour, "predictiveBack", tour.chain[1].name, walk(tour).take(1),
                "on Android, press and HOLD the back gesture at the edge without releasing it",
                "the screen underneath is drawn under the gesture while it is held, and " +
                    "releasing lands on it; letting go back at the edge cancels and changes nothing",
                listOf("stack=1")
            );
            // The bar's look, which is the injected theme's and nothing a runner reads: a
            // readout says what the flow did, not what font the title was drawn in.
            cells += byHand(
                tour, "barTheme", start.name, emptyList(),
                "on iPhone, look at the navigation bar over the flow's first screen, and then " +
                    "switch the phone between light and dark with the screen up",
                "C8 — the bar's title is drawn in the theme's title font and text colour, and the " +
                    "bar's background is the theme's background colour, in both appearances",
                listOf("stack=1")
            );
            // Where the content starts under the bar. Also nothing a runner reads: every
            // readout is on screen whether the bar left a gap above it or not.
            cells += byHand(
                tour, "barSpace", start.name, emptyList(),
                "on iPhone, look at where the flow's first line stands under the bar — and on the " +
                    "example app's own menu, which has neither a title nor an item in its bar",
                "C7 — the content starts right under the bar, with no second header's worth of " +
                    "space between them; under a bar with nothing in it, right under the status bar",
                listOf("stack=1")
            );
            // The one row here whose subject is WHERE the tap lands. A runner presses the
            // centre of the element it resolved, and the centre of a button is its label, so
            // a button whose fill takes no press is green in every automatic cell standing
            // on it. This asks for the other place.
            val opening = tour.pushing(1);
            cells += byHand(
                tour, "buttonEdge", start.name, emptyList(),
                "tap `${start.name}.${opening.name}` on the flow's first screen at the far " +
                    "EDGE of the button — the coloured part well away from the words — with a finger",
                "P39 — the stack moves, because the whole button is the tap target and not " +
                    "only the pixels its label happened to draw",
                listOf("stack=${after(opening.then, 1)}")
            );
        }
        if (flow.entry == "modal")
        {
            cells += byHand(
                tour, "predictiveBack", start.name, emptyList(),
                "on Android, use the system back gesture on the flow's FIRST screen",
                "R8 — a flow presented over something is closed by a back on its last route, " +
                    "so the whole flow goes rather than one route",
                listOf("stack=0")
            );
            // A person's cell because the subject is what it LOOKS like. A runner can assert
            // that `screen.close` exists and be told the truth by a header that drew the word
            // "Close" in body type on the left, which is the defect decision N3 is about.
            cells += byHand(
                tour, "closeOnRight", start.name, emptyList(),
                "look at the top of the flow's first screen, on both phones",
                "N3 — the way out is an X drawn as an icon in the TOP RIGHT corner — an item in " +
                    "the navigation bar on iPhone, the header's mark or the screen's own way-out " +
                    "row on Android — and it is not a word on the left",
                listOf("stack=1")
            );
            // The one cell here whose subject is the INPUT rather than the gesture, and the
            // only kind of cell that can see P36. A modal flow is drawn under a cover, and
            // a cover that consumed pointer changes cancelled the press of every control
            // beneath it — on the FINAL pass, which is where `clickable` re-reads a press it
            // has not finished. A finger produces MOVE events throughout a tap and a runner
            // produces none, so every automatic cell on that screen stayed green while
            // nothing on it could be tapped by hand. There is no runner tap to write here,
            // which is the whole point of the row.
            val moving = if (tour.chain.size > 1) tour.pushing(1) else tour.closing;
            cells += byHand(
                tour, "fingerTap", start.name, emptyList(),
                "tap `${start.name}.${moving.name}` on the flow's first screen WITH A FINGER " +
                    "— a real thumb on the glass, not a runner tap and not `adb shell input tap`",
                "P36 — the control responds and the stack moves, because nothing drawn over " +
                    "or around the screen consumed the small movements a finger makes inside a tap",
                listOf("stack=${after(moving.then, 1)}")
            );
        }
        // A sheet that stands alone is here to be LOOKED at — one row per height — and the
        // sheet with a stack in it is here to be DRAGGED, from a depth where a drag that
        // dropped one route instead of the presentation would be visible. Split that way
        // rather than every row on every sheet, because three identical drag rows would be
        // three chances to check the same thing and no chance to check anything else.
        if (flow.entry == "sheet" && tour.chain.size == 1)
        {
            cells += byHand(
                tour, "detent", start.name, emptyList(),
                "look at how tall the sheet stands, and compare the two platforms side by side",
                detentExpectation(requireNotNull(flow.detent)),
                listOf("stack=1")
            );
        }
        if (flow.entry == "sheet" && tour.chain.size > 1)
        {
            cells += byHand(
                tour, "snapBack", start.name, emptyList(),
                "drag the sheet's handle down a SHORT way — less than half its height — and let go",
                "the sheet returns to the height it was standing at and the flow is untouched",
                listOf("stack=1")
            );
            cells += byHand(
                tour, "dragAway", tour.deepest.name, walk(tour),
                "drag the sheet's handle down PAST half its height and let go",
                "the whole flow closes rather than one route — a sheet is a presentation and " +
                    "a drag dismisses the presentation, from whatever depth it started at",
                listOf("stack=0")
            );
        }
        if (start.actions.any { RouteParameters.inputs(start, it, bundle).isNotEmpty() })
        {
            cells += byHand(
                tour, "keyboard", start.name, emptyList(),
                "tap the field, and read the screen with the keyboard up",
                "K1 — the field stays visible and the control under it is still reachable; " +
                    "nothing jumps as the keyboard arrives and nothing is left scrolled out of place",
                listOf("stack=1")
            );
        }
        if (tour.chain.any { it.bodyScrolls })
        {
            cells += byHand(
                tour, "headerHolds", tour.chain.first { it.bodyScrolls }.name, emptyList(),
                "scroll the body from the top to the bottom and back",
                "S2's other half — the header and its title stay exactly where they are while " +
                    "the body moves under them, so the way out of the flow never scrolls away",
                listOf("stack=1")
            );
        }
        return cells;
    }

    private fun byHand(
        tour: Tour,
        name: String,
        screen: String,
        reach: List<Step>,
        gesture: String,
        rule: String,
        expect: List<String>
    ): Cell = Cell(
        "${tour.flow.name}-$name", screen, "idle", name, rule,
        "manual", Fixtures.READY,
        reach + Step.ByHand(gesture),
        expect
    )

    /** What a person is looking at when they look at a sheet of each height. */
    private fun detentExpectation(detent: String): String = when (detent)
    {
        "fit" -> "the sheet is as tall as its content and no taller, on both platforms, and " +
            "it does not grow to a fraction of the window it did not need"
        "half" -> "the sheet stands at about half the window on both platforms"
        else -> "the sheet stands nearly full height and stops short of the top, leaving the " +
            "screen under it visible above"
    }

    /**
     * Where a `then` leaves a stack that was `depth` deep.
     *
     * Every expectation about the stack goes through here rather than being written per
     * cell, so a spec that changes what an action does to the flow changes the table by
     * itself. `pop` on the first route is a no-op, which is `Flow`'s own rule.
     */
    private fun after(then: Navigation?, depth: Int): Int = when (then)
    {
        null -> depth
        Navigation.Close -> 0
        Navigation.Pop -> maxOf(depth - 1, 1)
        is Navigation.Push -> depth + 1
    }

    /**
     * The readouts a runner reads once an action has settled at `depth`.
     *
     * A closed flow shows no screen, so it has no state to read; at depth 1 the entry
     * screen is on show and at depth 2 the detail is.
     */
    private fun expect(depth: Int, state: String): List<String> =
        if (depth == 0) listOf("stack=0") else listOf("stack=$depth", "state=$state")

    /** The stack depth a cell asserts, which is what its teardown has to unwind. */
    private fun depthOf(cell: Cell): Int = cell.expect
        .first { it.startsWith("stack=") }
        .removePrefix("stack=")
        .toInt()

    private fun teardown(roles: Roles, depth: Int): List<Step> = when (depth)
    {
        0 -> emptyList()
        1 -> listOf(Step.Tap("${roles.entry.name}.${roles.cancel.name}"))
        else -> listOf(
            Step.Tap("${roles.detail.name}.${roles.back.name}"),
            Step.Tap("${roles.entry.name}.${roles.cancel.name}")
        )
    }

    /** u1–u6: the entry screen, whose state is a `Busy`. */
    private fun entryCells(roles: Roles, inputId: String): List<Cell>
    {
        val entry = roles.entry.name;
        val submit = "$entry.${roles.submit.name}";
        val typed = listOf(Step.Type(inputId, Fixtures.USER_CODE), Step.Tap(submit));

        return listOf(
            Cell(
                "u1", entry, "idle", roles.submit.name,
                "R5 — the call succeeds, so the then applies and the pushed screen loads (R6)",
                "both", Fixtures.READY,
                typed + Step.Await("state=ready"),
                expect(after(roles.submit.then, 1), "ready")
            ),
            Cell(
                "u1c", entry, "busy", roles.submit.name,
                "R4 — the flow is closed while the call is in flight and reopened at its start screen " +
                    "before the answer arrives, so that answer belongs to an appearance that is gone",
                "unit", Fixtures.READY,
                typed + Step.Tap("$entry.${roles.cancel.name}"),
                expect(1, "busy")
            ),
            Cell(
                "u2", entry, "idle", roles.submit.name,
                "R1 — an empty required input is refused before anything is sent",
                "both", Fixtures.READY,
                listOf(Step.Tap(submit)),
                listOf("stack=1", "state=error")
            ),
            Cell(
                "u3", entry, "busy", roles.submit.name,
                "R2 — the second press while the first is in flight is ignored",
                "unit", Fixtures.SLOW,
                typed + Step.Tap(submit),
                listOf("stack=1", "state=busy")
            ),
            Cell(
                "u4", entry, "idle", roles.submit.name,
                "R7 — the call fails, so no then applies and the screen carries the refusal",
                "both", Fixtures.REFUSED,
                typed + Step.Await("state=error"),
                listOf("stack=1", "state=error")
            ),
            Cell(
                "u5", entry, "idle", roles.cancel.name,
                "R5 — close empties the stack, so the flow is no longer presented",
                "both", Fixtures.READY,
                listOf(Step.Tap("$entry.${roles.cancel.name}")),
                expect(after(roles.cancel.then, 1), "idle")
            ),
            Cell(
                "u6", entry, "error", roles.submit.name,
                "R1 then R5 — a refused input leaves the screen usable, and the next press proceeds",
                "both", Fixtures.READY,
                listOf(Step.Tap(submit), Step.Await("state=error")) + typed + Step.Await("state=ready"),
                expect(after(roles.submit.then, 1), "ready")
            )
        );
    }

    /** u7–u13 and their variants: the detail screen, whose state is a `Loadable`. */
    private fun detailCells(roles: Roles, inputId: String): List<Cell>
    {
        val detail = roles.detail.name;
        val entry = roles.entry.name;
        val reach = listOf(
            Step.Type(inputId, Fixtures.USER_CODE),
            Step.Tap("$entry.${roles.submit.name}"),
            Step.Await("state=ready")
        );
        // The entry screen's own read answers and the pushed screen's refuses, which is the
        // only way to stand on a detail screen in its error state.
        val reachFailed = listOf(
            Step.Type(inputId, Fixtures.USER_CODE),
            Step.Tap("$entry.${roles.submit.name}"),
            Step.Await("state=error")
        );
        val back = "$detail.${roles.back.name}";
        val first = roles.commits[0];
        val second = roles.commits[1];

        return listOf(
            Cell(
                "u7", detail, "ready", roles.back.name,
                "R5 — pop drops the top route and the entry screen is idle again",
                "both", Fixtures.READY,
                reach + Step.Tap(back),
                expect(after(roles.back.then, 2), "idle")
            ),
            Cell(
                "u7b", detail, "ready", "systemBack",
                "R8 — the system back gesture above the last route is the flow's own pop",
                "both", Fixtures.READY,
                reach + Step.SystemBack,
                expect(after(roles.back.then, 2), "idle")
            ),
            Cell(
                "u8", detail, "ready", first.name,
                "R5 — the write succeeds and close empties the stack",
                "both", Fixtures.READY,
                reach + Step.Tap("$detail.${first.name}"),
                expect(after(first.then, 2), "ready")
            ),
            Cell(
                "u8c", detail, "ready", first.name,
                "R4 — the flow closes while the write is in flight, so its refusal changes nothing",
                "unit", Fixtures.WRITE_REFUSED,
                reach + Step.Tap("$detail.${first.name}"),
                listOf("stack=${after(first.then, 2)}", "state=ready")
            ),
            Cell(
                "u8d", detail, "ready", first.name,
                "R9 — the system back pops this route while the write is in flight, so its answer " +
                    "changes nothing and navigates nowhere",
                "unit", Fixtures.READY,
                reach + Step.Tap("$detail.${first.name}") + Step.SystemBack,
                expect(after(roles.back.then, 2), "ready")
            ),
            Cell(
                "u8e", detail, "ready", first.name,
                "R9 — a second copy of the entry route is pushed over this screen while the write is " +
                    "in flight, so the answer is for a screen that is no longer the one on show",
                "unit", Fixtures.READY,
                reach + Step.Tap("$detail.${first.name}"),
                // Three deep by the time the answer lands, and nothing applied to it: the
                // write's own `then` is refused, so the stack is where the extra push left it.
                expect(after(null, 3), "ready")
            ),
            Cell(
                "u9", detail, "ready", second.name,
                "R5 — the second write closes the same way the first does",
                "both", Fixtures.READY,
                reach + Step.Tap("$detail.${second.name}"),
                expect(after(second.then, 2), "ready")
            ),
            Cell(
                "u9c", detail, "ready", second.name,
                "R4 — the same late refusal, on the write that declares no response body",
                "unit", Fixtures.WRITE_REFUSED,
                reach + Step.Tap("$detail.${second.name}"),
                listOf("stack=${after(second.then, 2)}", "state=ready")
            ),
            Cell(
                "u10", detail, "error", roles.back.name,
                "R5 — pop drops a route in any state, and the screen under it is where it was left",
                "both", Fixtures.SOURCE_REFUSED,
                reachFailed + Step.Tap(back),
                expect(after(roles.back.then, 2), "idle")
            ),
            Cell(
                "u10b", detail, "error", "systemBack",
                "R8 — the system back gesture is the same pop from the same state",
                "both", Fixtures.SOURCE_REFUSED,
                reachFailed + Step.SystemBack,
                expect(after(roles.back.then, 2), "idle")
            ),
            Cell(
                "u11", detail, "loading", first.name,
                "R3 — a write over a value the screen has not read yet is ignored",
                "unit", Fixtures.SLOW,
                listOf(
                    Step.Type(inputId, Fixtures.USER_CODE),
                    Step.Tap("$entry.${roles.submit.name}"),
                    Step.Await("stack=2"),
                    Step.Tap("$detail.${first.name}")
                ),
                listOf("stack=2", "state=loading")
            ),
            Cell(
                "u12", detail, "error", roles.retry.name,
                "R5 — an action with no then leaves the stack alone and re-reads the source",
                "both", Fixtures.SOURCE_REFUSED_ONCE,
                reachFailed + Step.Tap("$detail.${roles.retry.name}") + Step.Await("state=ready"),
                expect(after(roles.retry.then, 2), "ready")
            ),
            Cell(
                "u13", detail, "loading", "load",
                "R7 — the source refuses, so the screen carries the refusal and the stack stands",
                "both", Fixtures.SOURCE_REFUSED,
                reachFailed,
                listOf("stack=2", "state=error")
            )
        );
    }

    /**
     * k1–k7: the keyboard contract, which only a device can hold still.
     *
     * Every one of them is a `maestro` cell and none is a `both`. There is no JVM half to
     * write: a keyboard is the platform's, `autofocus` is a focus request, and "the control
     * below the field is still reachable" is a question about layout under an inset. A cell
     * marked `both` here would demand a JUnit case that could only assert that the model it
     * does not touch is unchanged.
     *
     * k3, k4 and k5 are derived from what the SPEC says about the input rather than assumed:
     * a return key that submits is `submitOnReturn`, and a field that holds the focus is
     * `autofocus`. A screen that declares neither gets k1, k2, k6 and k7 and no others.
     */
    private fun keyboardCells(
        spec: Spec,
        roles: Roles,
        inputName: String,
        inputId: String
    ): List<Cell>
    {
        val entry = roles.entry.name;
        val submit = "$entry.${roles.submit.name}";
        val declared = spec.screenNamed(entry).inputNamed(inputName);
        val focus = Step.Tap(inputId);
        val type = Step.Type(inputId, Fixtures.USER_CODE);
        val cells = mutableListOf(
            Cell(
                "k1", entry, "idle", roles.submit.name,
                "K1 — the body gets out of the keyboard's way, so the control under the field is still " +
                    "on screen with the keyboard up and pressing it still submits",
                "maestro", Fixtures.READY,
                listOf(type, Step.SeeId(submit), Step.Tap(submit), Step.Await("state=ready")),
                expect(after(roles.submit.then, 1), "ready")
            ),
            Cell(
                "k2", entry, "idle", "hideKeyboard",
                "K2 — a tap outside the field puts the keyboard away and changes nothing else: the " +
                    "screen is where it was and the field is still there",
                "maestro", Fixtures.READY,
                listOf(type, Step.HideKeyboard, Step.SeeId(inputId)),
                expect(1, "idle")
            )
        );
        if (declared.autofocus)
        {
            cells += Cell(
                "k3", entry, "idle", roles.submit.name,
                "K3 — autofocus means the field already holds the focus, so text typed without tapping " +
                    "it first reaches the field and the write goes out with it",
                "maestro", Fixtures.READY,
                listOf(Step.TypeFocused(Fixtures.USER_CODE), Step.Tap(submit), Step.Await("state=ready")),
                expect(after(roles.submit.then, 1), "ready")
            );
        }
        if (declared.submitOnReturn)
        {
            cells += Cell(
                "k4", entry, "idle", "return",
                "K4 — submitOnReturn means the return key performs the screen's action, with no control " +
                    "pressed at all",
                "maestro", Fixtures.READY,
                listOf(focus, type, Step.Return, Step.Await("state=ready")),
                expect(after(roles.submit.then, 1), "ready")
            );
            cells += Cell(
                "k5", entry, "idle", "return",
                "K4 and K2 together — the return key still submits after the keyboard was put away and " +
                    "the field taken up again, which is the state a person is in after reading the screen",
                "maestro", Fixtures.READY,
                listOf(focus, type, Step.HideKeyboard, focus, Step.Return, Step.Await("state=ready")),
                expect(after(roles.submit.then, 1), "ready")
            );
        }
        cells += Cell(
            "k6", entry, "error", roles.submit.name,
            "K6 — editing the field clears the refusal under it, so the screen is usable again without " +
                "the person pressing anything",
            "maestro", Fixtures.READY,
            listOf(Step.Tap(submit), Step.Await("state=error"), focus, type),
            expect(1, "idle")
        );
        cells += Cell(
            "k7", entry, "error", roles.submit.name,
            "K7 and C7 — a refused input draws its refusal UNDER the field rather than somewhere on the " +
                "screen, and the line is drawn at all",
            "maestro", Fixtures.READY,
            listOf(Step.Tap(submit), Step.Await("state=error"), Step.SeeId("$inputId.error")),
            listOf("stack=1", "state=error")
        );
        return cells;
    }

    /**
     * s1–s2: the screen frame's own controls, on a device.
     *
     * `Flow.wayOut` decides these and both platforms' unit suites already check the table it
     * holds. What no suite checks is that the control the table names is DRAWN, reachable and
     * wired: a chrome that resolved correctly and rendered nothing would pass every JVM cell
     * in this repository.
     *
     * Derived from the flow's own entry rather than from the screen: a close on the root is
     * what a flow presented over something offers, and a pushed flow offers none — so a spec
     * whose flow is `push` gets s2 and not s1.
     */
    private fun frameCells(
        flow: FlowDefinition,
        spec: Spec,
        roles: Roles,
        inputId: String
    ): List<Cell>
    {
        val reach = listOf(
            Step.Type(inputId, Fixtures.USER_CODE),
            Step.Tap("${roles.entry.name}.${roles.submit.name}"),
            Step.Await("state=ready")
        );
        val cells = mutableListOf<Cell>();
        if (flow.presentedOver && spec.screenNamed(flow.start).close)
        {
            cells += Cell(
                "s1", roles.entry.name, "idle", "screen.close",
                "S1 and C5 — the root of a flow presented over something offers the flow's close at " +
                    "the header's trailing end, and pressing it closes the flow",
                "maestro", Fixtures.READY,
                listOf(Step.SeeId("screen.close"), Step.Tap("screen.close")),
                expect(0, "idle")
            );
        }
        cells += Cell(
            "s2", roles.detail.name, "ready", "screen.back",
            "S2 and C4 — a route above the root has the header's back, and pressing it pops one " +
                "route; on iOS that back is the system navigation bar's own button",
            "maestro", Fixtures.READY,
            reach + Step.HeaderBack(backLabel(roles.entry)),
            expect(after(Navigation.Pop, 2), "idle")
        );
        return cells;
    }


    // ---- P1–P9: the screen that reads a page at a time ----------------------

    /**
     * What a paged screen must do, in nine cells.
     *
     * The rules they stand on are `Paged`'s own arithmetic, driven by a model: a first page
     * that arrives, one that arrives empty, one that fails, an append, a failed append, an
     * append asked for twice, an append asked for where there is nothing to append, a retry
     * and a reload. Seven are a runner's, and the two that are not are the two whose subject
     * is a COUNT OF CALLS rather than anything on screen.
     *
     * `calls=` is that count, and it is the one expectation here that is not a screen readout.
     * A unit cell drives the model against the fake the fixture names and asks it how many
     * times it was called, which is the only way to state "the second ask was ignored": every
     * readout is the same whether the ask was ignored or answered twice with the same page.
     *
     * `load` is not a step. A screen reads its first page when it appears, however it appeared
     * (R6), so every cell below starts from a screen that has already asked.
     */
    private fun pagedCells(spec: Spec, bundle: Bundle): List<Cell>
    {
        val listed = spec.screens.filter { it.isPaged };
        if (listed.isEmpty())
        {
            return emptyList();
        }
        val screen = listed.singleOrNull()
            ?: throw SpecException(
                "the case rules cover one screen that reads a page at a time; this spec declares " +
                    listed.joinToString(", ") { it.name } + ", and P1–P9 are one screen's cells"
            );
        val name = screen.name;
        val more = Step.ScrollRows;
        val toCount = Step.ScrollToReadout("count=.*");
        val exit = wayOut(screen);

        return listOf(
            Cell(
                "P1", name, "loading", "load",
                "the first page arrived, so its rows are on screen and the cursor that came with " +
                    "them says there is another",
                "both", Fixtures.PAGES_TWO,
                listOf(toCount, Step.Await("count=3")),
                listOf("state=ready", "more=idle", "count=3", "hasMore=true"),
                teardown = exit
            ),
            Cell(
                "P2", name, "loading", "load",
                "a first page with no rows is empty and has no more, whatever cursor came with it",
                "both", Fixtures.PAGES_NONE,
                listOf(toCount, Step.Await("count=0")),
                listOf("state=empty", "count=0", "hasMore=false"),
                teardown = exit
            ),
            Cell(
                "P3", name, "loading", "load",
                "the first page failed, so nothing is on screen and there is nothing to append to",
                "both", Fixtures.FIRST_REFUSED,
                listOf(Step.Await("state=error")),
                listOf("state=error", "count=0", "hasMore=false"),
                teardown = exit
            ),
            Cell(
                "P4", name, "ready", "loadMore",
                "reaching the end of the rows asks for the next page, and it is appended to the " +
                    "rows already read",
                "both", Fixtures.PAGES_TWO,
                listOf(more, toCount, Step.Await("count=5")),
                listOf("state=ready", "more=idle", "count=5", "hasMore=false"),
                teardown = exit
            ),
            Cell(
                "P5", name, "ready", "loadMore",
                "a further page failed, so the rows already read stay exactly where they are and " +
                    "only the footer changes",
                "both", Fixtures.SECOND_REFUSED,
                listOf(more, Step.Await("more=error"), toCount),
                listOf("state=ready", "more=error", "count=3"),
                teardown = exit
            ),
            Cell(
                "P6", name, "ready", "loadMore",
                "a second ask while the first is in flight is ignored, so an end that comes into " +
                    "view twice reads one page",
                "unit", Fixtures.SLOW_SECOND,
                listOf(more, more),
                listOf("calls=2", "count=3", "more=busy"),
                teardown = exit
            ),
            Cell(
                "P7", name, "ready", "loadMore",
                "a list the server said has no more asks for nothing, however far it is scrolled",
                "unit", Fixtures.PAGES_ONE,
                listOf(more),
                listOf("calls=1", "count=3", "hasMore=false"),
                teardown = exit
            ),
            Cell(
                "P8", name, "ready", "retryMore",
                "the footer's own control asks again for the page that failed, and the rows it " +
                    "brings are appended to the ones that were already there",
                "both", Fixtures.SECOND_REFUSED_ONCE,
                listOf(more, Step.Await("more=error"), Step.Tap("$name.retryMore"), toCount, Step.Await("count=5")),
                listOf("state=ready", "more=idle", "count=5"),
                teardown = exit
            ),
            Cell(
                "P9", name, "ready", "reload",
                "a reload reads the first page again, cursor and all, so the list is one page long " +
                    "and the server says again that there is more",
                "both", Fixtures.PAGES_TWO,
                listOf(more, Step.Await("count=5"), Step.Tap("$name.reload"), toCount, Step.Await("count=3")),
                listOf("state=ready", "count=3", "hasMore=true"),
                teardown = exit
            )
        );
    }

    // ---- F1–F8: the screen that collects a form -----------------------------

    /**
     * What a checked form must do, in the eight cells `Form` itself is tested against.
     *
     * The ids are the vocabulary suite's (`FormTest.kt`, `FormTests.swift`) and that is the
     * point: those cells prove the ARITHMETIC on both platforms with no model, no service and
     * no view, and these prove that the generated model really drives it — the same eight
     * claims one layer up. A cell here that disagreed with its twin there would be a model
     * that checks something other than what the vocabulary checks.
     *
     * Three of the eight are conditional, for the reason k3–k5 are: F2 asserts a minimum and
     * exists only where a field declares one, and F3 and F8 assert a SHAPE and exist only
     * where a field's kind names one. A cell asserting a rule nobody wrote would fail, and
     * fail as though the model were broken.
     */
    private fun formCells(spec: Spec, bundle: Bundle): List<Cell>
    {
        val forms = spec.screens.filter { ScreenShape.isForm(it, bundle) };
        if (forms.isEmpty())
        {
            return emptyList();
        }
        val screen = forms.singleOrNull()
            ?: throw SpecException(
                "the case rules cover one screen that collects a form; this spec declares " +
                    forms.joinToString(", ") { it.name } + ", and F1–F8 are one screen's cells"
            );
        val fields = ScreenShape.inputs(screen, bundle);
        val submit = ScreenShape.submitAction(screen, bundle);
        val press = Step.Tap("${screen.name}.${submit.name}");
        val exit = wayOut(screen);
        val required = fields.filter { rulesOf(screen, it).required }.map { it.name }.sorted();
        val cells = mutableListOf(
            Cell(
                "F1", screen.name, "idle", submit.name,
                "F1 and F8 — an empty required field is refused before anything is sent, and every " +
                    "field is reported rather than the first",
                "both", Fixtures.READY,
                listOf(press, Step.Await("fields=" + refusals(required.map { it to "required" }))),
                listOf("state=idle", "fields=" + refusals(required.map { it to "required" })),
                teardown = exit
            )
        );
        shorterThanMinimum(screen, fields)?.let { field ->
            cells += Cell(
                "F2", screen.name, "idle", submit.name,
                "F2 — a field shorter than its minimum is refused by that rule and the others pass",
                "both", Fixtures.READY,
                typing(screen, fields, field.name to "a") + press,
                listOf("state=idle", "fields=" + refusals(listOf(field.name to "minLength"))),
                teardown = exit
            );
        };
        shaped(screen, fields)?.let { field ->
            cells += Cell(
                "F3", screen.name, "idle", submit.name,
                "F3 — a field that is not the shape its kind names is refused by the kind, and " +
                    "nothing is sent",
                "both", Fixtures.READY,
                typing(screen, fields, field.name to unacceptable(screen, field)) + press,
                listOf("state=idle", "fields=" + refusals(listOf(field.name to "kind"))),
                teardown = exit
            );
        };
        cells += Cell(
            "F4", screen.name, "idle", submit.name,
            "F4 — every field passes, so the write goes out and the flow does what the spec's " +
                "`then` says",
            "both", Fixtures.READY,
            typing(screen, fields) + press + Step.Await(settled(submit).first()),
            settled(submit),
            teardown = if (submit.then == Navigation.Close) emptyList() else exit
        );
        cells += Cell(
            "F5", screen.name, "idle", submit.name,
            "F5 — the write failed, so every field stays accepted and the form can be sent again",
            "both", Fixtures.REFUSED,
            typing(screen, fields) + press + Step.Await("state=error"),
            listOf("state=error", "fields=ok"),
            teardown = exit
        );
        cells += Cell(
            "F6", screen.name, "busy", submit.name,
            "F6 and R2 — a second press while the write is in flight is ignored, so one press is " +
                "one request",
            "unit", Fixtures.SLOW,
            typing(screen, fields) + press + press,
            listOf("calls=1", "state=busy"),
            teardown = exit
        );
        cells += Cell(
            "F7", screen.name, "error", submit.name,
            "F7 — editing a field clears that field's refusal and no other, so a person fixing one " +
                "thing is not told the others are fixed too",
            "both", Fixtures.READY,
            listOf(press, Step.Await("fields=" + refusals(required.map { it to "required" }))) +
                Step.Type("${screen.name}.${fields.first().name}", acceptable(screen, fields.first())),
            listOf(
                "state=idle",
                "fields=" + refusals(required.filter { it != fields.first().name }.map { it to "required" })
            ),
            teardown = exit
        );
        shaped(screen, fields)?.let { field ->
            cells += Cell(
                "F8", screen.name, "idle", submit.name,
                "F8 — two fields are wrong in two different ways and both are reported; the check " +
                    "does not stop at the first",
                "both", Fixtures.READY,
                listOf(Step.Type("${screen.name}.${field.name}", unacceptable(screen, field)), press),
                listOf(
                    "state=idle",
                    "fields=" + refusals(
                        required.map { it to if (it == field.name) "kind" else "required" }
                    )
                ),
                teardown = exit
            );
        };
        return cells;
    }

    /** What a field is checked against, defaulted the way the emitted rules table defaults it. */
    private fun rulesOf(screen: ScreenDefinition, field: RouteParameters.Parameter): RulesDefinition =
        screen.inputNamed(field.name).rules ?: RulesDefinition(true, null, null, null)

    /** The `fields=` readout's value: `name:rule` by field name, or `ok`. */
    private fun refusals(refused: List<Pair<String, String>>): String =
        if (refused.isEmpty()) "ok"
        else refused.sortedBy { it.first }.joinToString(",") { "${it.first}:${it.second}" }

    /** Typing every field its acceptable value, with [wrong] overriding one of them. */
    private fun typing(
        screen: ScreenDefinition,
        fields: List<RouteParameters.Parameter>,
        wrong: Pair<String, String>? = null
    ): List<Step> = fields.map { field ->
        val value = if (wrong != null && wrong.first == field.name) wrong.second
        else acceptable(screen, field);
        Step.Type("${screen.name}.${field.name}", value);
    }

    /**
     * A value this field accepts: the shape its kind names, long enough for its minimum.
     *
     * Derived rather than written per cell, because a cell that typed a constant would stop
     * being a cell about the rule the moment somebody raised the minimum.
     */
    private fun acceptable(screen: ScreenDefinition, field: RouteParameters.Parameter): String
    {
        val declared = screen.inputNamed(field.name);
        val minimum = (declared.rules?.minLength ?: 1L).toInt();
        return when (declared.kind)
        {
            "number" -> "12"
            "email" -> "someone@example.com"
            "code" -> Fixtures.USER_CODE
            else -> "ok".padEnd(maxOf(minimum, 2), 'a')
        };
    }

    /** A value this field's KIND refuses, which is a different value for each of the two. */
    private fun unacceptable(screen: ScreenDefinition, field: RouteParameters.Parameter): String =
        if (screen.inputNamed(field.name).kind == "email") "nope" else "x"

    /** The first field with a minimum worth breaking, or null: F2's subject. */
    private fun shorterThanMinimum(
        screen: ScreenDefinition,
        fields: List<RouteParameters.Parameter>
    ): RouteParameters.Parameter? =
        fields.firstOrNull { (screen.inputNamed(it.name).rules?.minLength ?: 0L) > 1L }

    /** The first field whose kind names a shape, or null: F3's and F8's subject. */
    private fun shaped(
        screen: ScreenDefinition,
        fields: List<RouteParameters.Parameter>
    ): RouteParameters.Parameter? =
        fields.firstOrNull { screen.inputNamed(it.name).kind in listOf("email", "number") }

    /** Where a successful submit leaves the screen, which is what its `then` says. */
    private fun settled(submit: ActionDefinition): List<String> =
        if (submit.then == Navigation.Close) listOf("stack=0") else listOf("state=idle", "fields=ok")

    /**
     * How a runner leaves a list or a form once its assertions are made.
     *
     * The screen's own way out, and only one that NEITHER calls nor depends on the state the
     * cell left the screen in: a submit that closes is no way out of a form whose fields were
     * just refused. A screen with no such action gets no teardown, and its flow's cells end
     * where they stand.
     */
    private fun wayOut(screen: ScreenDefinition): List<Step> =
        screen.actions.firstOrNull { it.call == null && it.then == Navigation.Close }
            ?.let { listOf(Step.Tap("${screen.name}.${it.name}")) } ?: emptyList()

    /** u14: the flow opened on a whole stack at once. */
    private fun deepEntryCell(roles: Roles): List<Cell> = listOf(
        Cell(
            "u14", roles.detail.name, "loading", "deepEntry",
            "R6 — a screen loads its source once however it appeared, including on a deep entry",
            "both", Fixtures.DEEP_READY,
            listOf(Step.Await("state=ready")),
            listOf("stack=2", "state=ready")
        )
    );
}
