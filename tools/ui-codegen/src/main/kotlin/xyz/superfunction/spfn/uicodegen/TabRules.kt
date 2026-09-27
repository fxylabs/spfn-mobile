// The tab host's cells, from the design's case table.
//
// docs/architecture/tab-host-design.md §4 is the table: 34 rows over which tab is selected,
// how deep its stack stands, what is presented and whether the keyboard is up. Every cell
// below is one of those rows, named `tabs-c<n>` after it, and its expectation is the row's
// — written from the table, never read off `TabHost` (P10). A row that is not a cell here is
// one the table itself says cannot be one: C-4 (the bar is not on screen to be pressed) and
// C-18 (the example's detail screens open no second flow).
//
// Everything is derived from the spec's SHAPE, the way `Rules` derives the showcase: the
// first tab is the start tab S, the second is O, and the flows the rows need are found by
// what they are — a push flow on each tab, a modal and a sheet on O. A spec with a bar that
// cannot carry the table is refused by name rather than given a table with holes in it (P7).
//
// Four runners, as everywhere in the table:
//
//   maestro  the row is a tap, a back and a readout, and a runner can hold it;
//   unit     C-13 and C-15: the bar is not on screen at depth > 0 and `show` is code, so the
//            row is `TabState`'s and is proven on the JVM against the example's container;
//   manual   a gesture held part-way, a first frame, a keyboard that must not carry the bar
//            up, a process death, a rotation — what a runner reports success for whether or
//            not the platform did it (P22);
//   and the platform splits, `Step.On`, for the rows where Android and iOS answer
//   differently by design (Q-B: a back on a tab's root is Android's; C-12: iOS has nothing
//   under a tab root to swipe back to).
//
// The readouts are the example's (§3-7): `tab=<id>` and `stack=<n>` on each tab root, and
// `scrollToTop=<n>`, which is the root's count of C-14's signal.

package xyz.superfunction.spfn.uicodegen

/** What the tab table needs of a spec's bar, named by role. */
private class Bar(spec: Spec)
{
    val start: TabDefinition;
    val other: TabDefinition;

    /** S's push flow: what a row on the start tab pushes. */
    val startPush: Tour;

    /** O's push flow, at least two deep: C-33 walks back down it. */
    val otherPush: Tour;

    /** O's modal and sheet flows, each walked as a tour so its closing control is known. */
    val modal: Tour;
    val sheet: Tour;

    init
    {
        if (spec.tabs.size < 2)
        {
            throw SpecException(
                "the tab cells need two tabs, a start tab and another; this spec's bar has ${spec.tabs.size}"
            );
        }
        start = spec.tabs[0];
        other = spec.tabs[1];
        startPush = Tour(spec, needed(spec, start, "a push flow") { it.entry == "push" });
        otherPush = Tour(spec, needed(spec, other, "a push flow two screens deep") {
            it.entry == "push" && Tour(spec, it).chain.size >= 2
        });
        modal = Tour(spec, needed(spec, other, "a modal flow") { it.entry == "modal" });
        sheet = Tour(spec, needed(spec, other, "a sheet flow") { it.entry == "sheet" });
    }

    private fun needed(spec: Spec, tab: TabDefinition, what: String, match: (FlowDefinition) -> Boolean): FlowDefinition =
        spec.flowsOf(tab).firstOrNull(match)
            ?: throw SpecException(
                "the tab cells need ${what} on tab '${tab.id}', and its flows are: " + tab.flows.joinToString(", ")
            )
}

object TabRules
{
    /** Every tab cell in the design's order, or none for a spec with no bar. */
    fun cells(spec: Spec): List<Cell>
    {
        if (spec.tabs.isEmpty())
        {
            return emptyList();
        }
        val bar = Bar(spec);
        return switching(bar) + pushing(bar) + backs(bar) + reselecting(bar) + presenting(bar) +
            keyboard(bar) + lifecycle(bar) + deepLinks(bar);
    }

    // ---- C-1, C-2: a press on another tab ----------------------------------

    private fun switching(bar: Bar): List<Cell> = listOf(
        cell(
            "c1", bar.start.root, "idle", "tab.${bar.other.id}",
            "C-1 — pressing another tab selects it and shows its root; nothing moves in either tab's stack",
            steps = listOf(Step.SeeId(tabId(bar.other)), Step.Tap(tabId(bar.other))),
            expect = listOf(tab(bar.other), "stack=0")
        ),
        cell(
            "c2", bar.other.root, "idle", "tab.${bar.start.id}",
            "C-2 and Q-D — the start tab was left two deep; pressing it shows that detail as it was left, " +
                "with no bar on it",
            fixture = Fixtures.TAB_DEEP,
            steps = listOf(Step.Tap(tabId(bar.start)), Step.Await("stack=2"), Step.NotSeeId(tabId(bar.start))),
            expect = listOf("stack=2"),
            teardown = Rules.unwind(bar.startPush, 2)
        )
    )

    // ---- C-3, C-6, C-7, C-25's push: a detail covers the bar -----------------

    private fun pushing(bar: Bar): List<Cell> = listOf(
        cell(
            "c3", bar.start.root, "idle", open(bar.start, bar.startPush.flow),
            "C-3 — a pushed detail slides in over the tab's root and its bar together: the bar's items " +
                "are not on screen",
            steps = listOf(
                Step.Tap(open(bar.start, bar.startPush.flow)),
                Step.Await("stack=1"),
                Step.NotSeeId(tabId(bar.start)),
                Step.NotSeeId(tabId(bar.other))
            ),
            expect = listOf("stack=1"),
            teardown = Rules.unwind(bar.startPush, 1)
        ),
        cell(
            "c6", bar.startPush.chain.first().name, "idle", "headerBack",
            "C-6 — the back in the detail's header pops to the tab's root, and the bar is there again",
            steps = listOf(
                Step.Tap(open(bar.start, bar.startPush.flow)),
                Step.Await("stack=1"),
                Step.HeaderBack("${Rules.literal(bar.start.title)}|Back"),
                Step.SeeId(tabId(bar.start))
            ),
            expect = listOf(tab(bar.start), "stack=0")
        ),
        cell(
            "c7", bar.startPush.chain.first().name, "idle", "systemBack",
            "C-7 and C-5 — the system back (Android) and the edge swipe (iOS) pop the detail, and the " +
                "root comes back with its bar",
            steps = listOf(
                Step.Tap(open(bar.start, bar.startPush.flow)),
                Step.Await("stack=1"),
                Step.SystemBack,
                Step.SeeId(tabId(bar.start))
            ),
            expect = listOf(tab(bar.start), "stack=0")
        ),
        cell(
            "c34", bar.other.root, "idle", "tab.${bar.start.id}",
            "C-34 and N1 — each tab's stack moves alone: a push and a pop on one tab leave the start " +
                "tab's stack where it stood",
            steps = listOf(
                Step.Tap(tabId(bar.other)),
                Step.Tap(open(bar.other, bar.otherPush.flow)),
                Step.Await("stack=1"),
                Step.SystemBack,
                Step.SeeText(tab(bar.other)),
                Step.Tap(tabId(bar.start))
            ),
            expect = listOf(tab(bar.start), "stack=0")
        )
    )

    // ---- C-9 to C-12, C-33: the back on a tab's root -------------------------

    private fun backs(bar: Bar): List<Cell> = listOf(
        cell(
            "c9", bar.other.root, "idle", "systemBack",
            "C-9 and Q-B — on Android the system back on another tab's root selects the start tab; on " +
                "iOS there is no such back and the tab stays (C-12)",
            steps = listOf(
                Step.Tap(tabId(bar.other)),
                Step.SeeText(tab(bar.other)),
                Step.On("Android", listOf(Step.SystemBack, Step.SeeText(tab(bar.start)))),
                Step.On("iOS", listOf(Step.SeeText(tab(bar.other))))
            ),
            expect = listOf("stack=0")
        ),
        cell(
            "c12", bar.other.root, "idle", "systemBack",
            "C-12 — on iOS an edge swipe on a tab's root does nothing: there is no route under it",
            steps = listOf(
                Step.Tap(tabId(bar.other)),
                Step.On("iOS", listOf(Step.SystemBack))
            ),
            expect = listOf(tab(bar.other), "stack=0")
        ),
        cell(
            "c33", bar.otherPush.chain[1].name, "idle", "systemBack",
            "C-33 — backs go down the selected tab's own stack first, 2 to 1 to its root; only then, on " +
                "Android, one more back selects the start tab",
            fixture = Fixtures.TAB_DEEP,
            steps = listOf(
                Step.Await("stack=2"),
                Step.SystemBack,
                Step.Await("stack=1"),
                Step.SystemBack,
                Step.SeeText(tab(bar.other)),
                Step.On("Android", listOf(Step.SystemBack, Step.SeeText(tab(bar.start))))
            ),
            expect = listOf("stack=0")
        ),
        manual(
            "c10", bar.start.root, "systemBack",
            "C-10 — the system back on the start tab's root leaves the app; the tab host does not take it",
            "Android: on the start tab's root, press the system back. The app goes to the background " +
                "(a runner cannot assert on what is outside the app)",
            listOf("stack=0")
        ),
        manual(
            "c11", bar.other.root, "predictiveBack",
            "C-11 — a predictive back held on another tab's root shows no preview (the tab host took the " +
                "gesture); released, the start tab is selected; cancelled, nothing changes",
            "Android, gesture navigation: select '${bar.other.id}', hold a back swipe at the edge, then " +
                "release it; again, and cancel it",
            listOf(tab(bar.start))
        ),
        manual(
            "c5", bar.startPush.chain.first().name, "edgeSwipe",
            "C-5 — during an edge swipe back the root shows WITH its bar, sliding in together; released, " +
                "no bar appears or blinks on its own",
            "iOS: open '${bar.startPush.flow.name}' from '${bar.start.root}', swipe back from the left edge " +
                "slowly, half way, then all the way",
            listOf("stack=0")
        ),
        manual(
            "c8", bar.startPush.chain.first().name, "predictiveBack",
            "C-8 — a predictive back held on a detail previews the root WITH its bar; released pops, " +
                "cancelled stays",
            "Android, gesture navigation: open '${bar.startPush.flow.name}' from '${bar.start.root}', hold a " +
                "back swipe at the edge, then release it; again, and cancel it",
            listOf("stack=0")
        )
    )

    // ---- C-13, C-14, C-15: the selected tab pressed, and `show` --------------

    private fun reselecting(bar: Bar): List<Cell> = listOf(
        Cell(
            "tabs-c13", bar.startPush.chain[1].name, "idle", "tab.${bar.start.id}",
            "C-13 — the selected tab pressed while it stands above its root asks for a pop to the root; " +
                "the bar is not on screen then, so this row is TabState's",
            "unit", Fixtures.READY,
            listOf(Step.Tap(tabId(bar.start))),
            listOf("stack=0")
        ),
        cell(
            "c14", bar.start.root, "idle", "tab.${bar.start.id}",
            "C-14 and Q-C — the selected tab pressed on its root leaves the stack alone and counts one " +
                "scroll-to-top, which the app's list follows",
            steps = listOf(Step.SeeText(tab(bar.start)), Step.Tap(tabId(bar.start))),
            expect = listOf(tab(bar.start), "stack=0", "scrollToTop=1")
        ),
        Cell(
            "tabs-c15", bar.startPush.chain.first().name, "idle", "show",
            "C-15 — the app's own `show` selects another tab without popping the one it leaves; " +
                "`show` back returns to the detail as it was",
            "unit", Fixtures.READY,
            listOf(Step.Tap(open(bar.start, bar.startPush.flow))),
            listOf("stack=1")
        )
    )

    // ---- C-16, C-17, C-19 to C-22: a modal or a sheet inside a tab ------------

    private fun presenting(bar: Bar): List<Cell>
    {
        val openModal = listOf(Step.Tap(tabId(bar.other)), Step.Tap(open(bar.other, bar.modal.flow)), Step.Await("stack=1"));
        val openSheet = listOf(Step.Tap(tabId(bar.other)), Step.Tap(open(bar.other, bar.sheet.flow)), Step.Await("stack=1"));
        val closeModal = closing(bar.modal);
        val closeSheet = closing(bar.sheet);
        return listOf(
            cell(
                "c16", bar.modal.flow.start, "idle", open(bar.other, bar.modal.flow),
                "C-16 — a modal opened inside a tab covers the whole screen, the bar included",
                steps = openModal,
                expect = listOf("stack=1"),
                teardown = listOf(closeModal)
            ),
            cell(
                "c17", bar.sheet.flow.start, "idle", open(bar.other, bar.sheet.flow),
                "C-17 — a sheet opened inside a tab stands over the bar, and its scrim covers it",
                steps = openSheet,
                expect = listOf("stack=1"),
                teardown = listOf(closeSheet)
            ),
            cell(
                "c19", bar.modal.flow.start, "idle", closeModal.id,
                "C-19 — closing the modal uncovers the tab's root and its bar, on the same tab",
                steps = openModal + closeModal,
                expect = listOf(tab(bar.other), "stack=0")
            ),
            cell(
                "c20", bar.modal.flow.start, "idle", "systemBack",
                "C-20 — on Android the system back on the modal's root closes the flow and is not the " +
                    "tab host's; iOS closes it with its close",
                steps = openModal + Step.On("Android", listOf(Step.SystemBack)) + Step.On("iOS", listOf(closeModal)),
                expect = listOf(tab(bar.other), "stack=0")
            ),
            cell(
                "c21", bar.sheet.flow.start, "idle", "systemBack",
                "C-21 — on Android the system back closes the sheet and the tab stays where it was; iOS " +
                    "closes it with its own control",
                steps = openSheet + Step.On("Android", listOf(Step.SystemBack)) + Step.On("iOS", listOf(closeSheet)),
                expect = listOf(tab(bar.other), "stack=0")
            ),
            manual(
                "c22", bar.sheet.flow.start, "drag",
                "C-22 — a sheet dragged down closes its flow, and the tab's root and bar are where they were",
                "iOS: select '${bar.other.id}', open '${bar.sheet.flow.name}', drag the sheet down past its threshold",
                listOf(tab(bar.other), "stack=0")
            ),
            manual(
                "c16b", bar.modal.flow.start, "tapBar",
                "C-16 and P36 — with a modal or a sheet up, a FINGER on the bar's place changes no tab",
                "Android: open '${bar.modal.flow.name}' and then '${bar.sheet.flow.name}' on '${bar.other.id}', and tap " +
                    "with a finger where the bar was",
                listOf(tab(bar.other), "stack=1")
            )
        );
    }

    // ---- C-23 to C-26: the keyboard -----------------------------------------

    private fun keyboard(bar: Bar): List<Cell>
    {
        val field = "${bar.start.root}.note";
        return listOf(
            manual(
                "c23", bar.start.root, "focus",
                "C-23 and K1 — with the keyboard up the root's body gets out of its way, and the bar does " +
                    "NOT ride up on it: the keyboard covers the bar",
                "Tap '$field' on '${bar.start.id}' and look at the bar and the body with the keyboard up",
                listOf(tab(bar.start), "stack=0")
            ),
            cell(
                "c24", bar.start.root, "idle", "hideKeyboard",
                "C-24 and K2 — putting the keyboard away shows the bar again, on the same tab",
                steps = listOf(Step.Tap(field), Step.HideKeyboard, Step.SeeId(tabId(bar.start))),
                expect = listOf(tab(bar.start), "stack=0")
            ),
            cell(
                "c25", bar.start.root, "idle", open(bar.start, bar.startPush.flow),
                "C-25 — a push from a root with the keyboard up puts the keyboard away and pushes",
                steps = listOf(Step.Tap(field), Step.Type(field, "note"), Step.Tap(open(bar.start, bar.startPush.flow))),
                expect = listOf("stack=1"),
                teardown = Rules.unwind(bar.startPush, 1)
            ),
            cell(
                "c26", bar.start.root, "idle", "systemBack",
                "C-26 — on Android the system back with the keyboard up only puts the keyboard away; the " +
                    "tab and its stack stay",
                steps = listOf(Step.Tap(field), Step.On("Android", listOf(Step.SystemBack))),
                expect = listOf(tab(bar.start), "stack=0")
            )
        );
    }

    // ---- C-27 to C-29: the app's life ---------------------------------------

    private fun lifecycle(bar: Bar): List<Cell> = listOf(
        manual(
            "c27", bar.otherPush.chain.first().name, "background",
            "C-27 — sent to the background and brought back, everything is where it was: the tab, its " +
                "depth, and the other tab's state",
            "Select '${bar.other.id}', open '${bar.otherPush.flow.name}', go to the home screen, come back",
            listOf("stack=1")
        ),
        manual(
            "c28", bar.other.root, "processDeath",
            "C-28 and Q-F — after a process death the selected tab comes back; the flows' stacks do not, " +
                "so both tabs stand on their roots",
            "Android, developer option 'Don't keep activities': select '${bar.other.id}', open " +
                "'${bar.otherPush.flow.name}', go to the home screen, come back",
            listOf(tab(bar.other), "stack=0")
        ),
        manual(
            "c29", bar.otherPush.chain.first().name, "rotate",
            "C-29 — a rotation or a window resize keeps the tab, its depth and the other tab's state; the " +
                "bar stays at the bottom, on the navigation bar's inset and not twice over it",
            "Select '${bar.other.id}', open '${bar.otherPush.flow.name}', rotate to landscape and back " +
                "(on Android also with three-button navigation)",
            listOf("stack=1")
        )
    )

    // ---- C-30 to C-32: a deep link ------------------------------------------

    private fun deepLinks(bar: Bar): List<Cell> = listOf(
        cell(
            "c30", bar.otherPush.chain.first().name, "idle", "systemBack",
            "C-30 and Q-E — a deep link shows another tab and opens its flow at a detail; a back from the " +
                "detail goes to THAT tab's root, not to the tab the app was on",
            fixture = Fixtures.TAB_DEEP,
            steps = listOf(Step.Await("stack=1"), Step.SystemBack),
            expect = listOf(tab(bar.other), "stack=0")
        ),
        manual(
            "c31", bar.otherPush.chain.first().name, "deepLink",
            "C-31 and U-9 — a deep link into a tab never opened before: whether the first frame shows the " +
                "root and then the push, or the detail at once",
            "Launch with SPFN_UI_FIXTURE=tabs-c30 and watch the first frame",
            listOf("stack=1")
        ),
        manual(
            "c32", bar.modal.flow.start, "deepLink",
            "C-32 and Q-E — a deep link does not close a modal that is up; closing it first is the app's " +
                "deep-link handler's job",
            "Open '${bar.modal.flow.name}' on '${bar.other.id}', then deliver a deep link to '${bar.start.id}'",
            listOf("stack=1")
        )
    );

    // ---- the pieces ---------------------------------------------------------

    /** One automated cell. Every one runs on both platforms' runner, split inside by `Step.On`. */
    private fun cell(
        row: String,
        screen: String,
        state: String,
        action: String,
        rule: String,
        fixture: String = Fixtures.READY,
        steps: List<Step>,
        expect: List<String>,
        teardown: List<Step> = emptyList()
    ): Cell = Cell("tabs-$row", screen, state, action, rule, "maestro", fixture, steps, expect, teardown)

    /** One cell a person checks, for the reason the file header gives. */
    private fun manual(row: String, screen: String, action: String, rule: String, doIt: String, expect: List<String>): Cell =
        Cell("tabs-$row", screen, "idle", action, rule, "manual", Fixtures.READY, listOf(Step.ByHand(doIt)), expect)

    private fun tabId(tab: TabDefinition): String = "tab.${tab.id}"

    private fun tab(tab: TabDefinition): String = "tab=${tab.id}"

    /** The control on [tab]'s root that opens [flow]. */
    private fun open(tab: TabDefinition, flow: FlowDefinition): String = "${tab.root}.${flow.name}"

    /** The press that closes a presented flow standing on its first screen. */
    private fun closing(tour: Tour): Step.Tap
    {
        if (tour.chain.size != 1)
        {
            throw SpecException(
                "the tab cells close '${tour.flow.name}' from its first screen, and it is ${tour.chain.size} " +
                    "screens deep; a presented flow in a tab's cells is one screen"
            );
        }
        return Step.Tap("${tour.deepest.name}.${tour.closing.name}");
    }
}
