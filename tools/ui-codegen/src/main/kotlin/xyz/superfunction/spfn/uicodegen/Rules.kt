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
//   R6  a screen with a source loads it once when it appears, however it appeared;
//   R7  a failed call leaves the screen in its error state and the stack where it was;
//   R8  the system back gesture is the flow's own pop, and on a modal flow's last route
//       it is the flow's close.
//
// The cell ids are this repository's: u1–u14 for the base table, u7b/u10b for the system
// back variants of the two back-button cells, u8c/u9c for the late-response variants of
// the two closing writes.

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

    /** Waits for a readout to reach a value before going on. */
    data class Await(val readout: String) : Step
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
    /** `unit`, `maestro`, or `both`. */
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
    val submit: ActionDefinition = entry.actions.single { it.call != null && it.then is Navigation.Push };
    val cancel: ActionDefinition = entry.actions.single { it.call == null };
    val detail: ScreenDefinition = spec.screenNamed((submit.then as Navigation.Push).screen);

    /** The detail screen's re-read of its own source, whatever the spec called it. */
    val retry: ActionDefinition = detail.actions.single { it.call?.reference == detail.source?.reference };

    /** Its writes: every call that is not that re-read. */
    val commits: List<ActionDefinition> =
        detail.actions.filter { it.call != null && it.call.reference != detail.source?.reference };

    val back: ActionDefinition = detail.actions.single { it.call == null };
}

object Rules
{
    /** The whole table, in cell order, for the one flow the spec declares. */
    fun cells(spec: Spec, bundle: Bundle): List<Cell>
    {
        val flow = spec.flows.singleOrNull()
            ?: throw SpecException("the case rules cover one flow; this spec declares ${spec.flows.size}");
        val roles = roles(spec, flow);
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

        val cells = entryCells(roles, inputId) + detailCells(roles, inputId) + deepEntryCell(roles);
        return cells.map { it.copy(teardown = teardown(roles, depthOf(it))) };
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

    private fun roles(spec: Spec, flow: FlowDefinition): Roles = try
    {
        Roles(spec, flow);
    }
    catch (absent: NoSuchElementException)
    {
        throw SpecException(
            "the case rules cover a flow with a submitting entry screen, a cancel, and a detail screen " +
                "carrying two closing writes, a retry and a back: ${absent.message}"
        );
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
