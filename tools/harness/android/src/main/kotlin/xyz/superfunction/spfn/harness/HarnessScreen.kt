package xyz.superfunction.spfn.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.superfunction.spfn.harness.generated.flows.ApproveDeviceFlowHost
import xyz.superfunction.spfn.ui.Busy

/**
 * The harness screen.
 *
 * Readouts, then the half the flows drive, then the half a person drives. This is not a
 * sample app and not a design: every control exists because a flow needs to tap it or a
 * person needs to read it.
 *
 * That order is a rule and not a preference. **What a runner taps is inside the first
 * viewport.** Compose's [verticalScroll] puts only the nodes that overlap the viewport
 * into the accessibility tree, so a control below the fold is not merely out of sight —
 * it does not exist for uiautomator, and Maestro's `tapOn` does not scroll to look for
 * it (docs/IMPLEMENTATION-PITFALLS.md P25). The view `ScrollView` this screen replaced
 * published every child whether it was on screen or not, which is why keeping "the old
 * screen's order" survived one rewrite and then failed every case at `btn_wipe`.
 *
 * So the eleven controls a flow taps are a grid directly under the readouts, two to a
 * row, and the device-mode half a person drives is below them where scrolling is what a
 * person does anyway. Every tag and every title is what it always was: a flow finds them
 * by those strings, and a rearranged screen must not be a renamed one.
 *
 * How a flow finds them is split, and the split is forced by the platforms rather than
 * chosen. A flow's `id:` matches an accessibility identifier on iOS and a RESOURCE id on
 * Android, so a control, whose identity never changes, is found by id on both, while a
 * readout, whose whole point is that its value changes, is found by its text. The label
 * rides in that text (`state=unenrolled`) so the match names which readout it means.
 *
 * On this platform the id half now costs one line: [testTagsAsResourceId] on the root
 * publishes every [testTag] below it as the resource id a runner selects on. Before that
 * the ids were declared in `res/values/ids.xml`, because a resource id could only be
 * referenced from code and never created there. The first run on a real phone is what
 * settled the rule: all nine cases failed with "Element not found: Id matching regex:
 * wipe" while the screen was on and correct, because content descriptions are not
 * resource ids.
 *
 * Nothing here draws above foundation. Material would add a design this repository has
 * not chosen, and a hundred artifacts to pin, for a screen that is a column of text.
 *
 * One thing on this screen is not written here at all. `open-approve` builds the graph
 * tools/ui-codegen emits into `harness/generated/` and opens its flow, which is drawn as a
 * cover over everything above — the harness is the SECOND consumer of the one screen spec,
 * and it drives those screens against a live reference server rather than against a
 * fixture. The `stack=` readout is that flow's own depth, spelled the way the generated
 * screens spell it, so a flow that is open draws the number twice and both agree.
 *
 * tools/harness/ios/Sources/HarnessView.swift is the same screen in SwiftUI, with the
 * same control ids and the same readout text.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HarnessScreen(screen: HarnessScreenState, actions: HarnessActions)
{
    ExpiryTicker(screen);
    // A Box, and the flow host is its LAST child. A modal `FlowHost` draws a cover that
    // fills its PARENT, so one placed inside the scrolling column below would cover a row
    // of that column and nothing else — the one demand `FlowHost` makes of a host app.
    //
    // `testTagsAsResourceId` sits here rather than on the column, so it is on the root the
    // generated screens are also under: the switch is resolved by walking semantics
    // parents, and every `tapOn: id:` in the d-cells depends on the walk reaching it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .semantics { testTagsAsResourceId = true }
    )
    {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The system bars' insets become padding. Without it the screen is
                // edge-to-edge — which every app targeting API 35 and later now is, whether
                // it asks or not — and on a real phone on 2026-09-01 the last button of the
                // column sat under the navigation bar, reachable only by scrolling past the
                // content.
                .windowInsetsPadding(WindowInsets.systemBars)
                // The column is longer than a phone, and what hangs off the bottom of it
                // is out of the accessibility tree rather than merely out of sight (P25).
                // Everything a flow touches is measured to land above the fold — see
                // [RunnerBlock] for the arithmetic — and what is below it is the
                // device-mode half, which a person scrolls to on purpose.
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
        {
            Readouts(screen);
            RunnerBlock(screen, actions);
            DeviceMode(screen, actions);
            DeviceCodeMode(actions);
        }

        val approval = screen.approval;
        if (approval != null)
        {
            ApproveDeviceFlowHost(approval);
        }
    }
}

/**
 * Redraws the countdown once a second while a code is on screen, and nothing else.
 *
 * Keyed on the expiry, which is the fact it is counting to: a code arriving starts the
 * loop, and the wait ending — however it ends — clears the expiry, restarts the effect,
 * and the loop stops on its own condition. The ticker is the only timer on this screen.
 */
@Composable
private fun ExpiryTicker(screen: HarnessScreenState)
{
    LaunchedEffect(screen.deviceCodeExpiresAtMillis)
    {
        while (screen.deviceCodeExpiresAtMillis != null)
        {
            delay(1_000);
            screen.nowMillis = System.currentTimeMillis();
        }
    }
}

/** The machine-readable truth, written for a flow. Every line of it is [HarnessReadout]. */
@Composable
private fun Readouts(screen: HarnessScreenState)
{
    BasicText(text = HarnessReadout.state(screen.state), style = Body);
    BasicText(text = HarnessReadout.outcome(screen.outcome), style = Body);
    BasicText(text = HarnessReadout.busy(screen.busy), style = Body);
    BasicText(text = HarnessReadout.network(screen.networkBlocked), style = Body);
    BasicText(text = HarnessReadout.custody(screen.custody), style = Body);
    BasicText(text = HarnessReadout.case(screen.socialCase), style = Body);
    BasicText(text = HarnessReadout.social(screen.social), style = Body);
    BasicText(text = HarnessReadout.receipt(screen.receipt), style = Body);
    BasicText(text = HarnessReadout.deviceCode(screen.deviceCode), style = Body);
    BasicText(
        text = HarnessReadout.expiresIn(screen.deviceCodeExpiresAtMillis, screen.nowMillis),
        style = Body
    );
    ApprovalReadouts(screen);
}

/**
 * The two lines the generated approval flow adds: how deep it stands, and what the wire
 * last answered.
 *
 * The depth is COLLECTED off the flow rather than copied into [HarnessScreenState]: the
 * generated screens navigate it, so it changes with no tap on this screen to copy it, and
 * the flow is its own single source of truth. Zero is what a closed flow reads and what a
 * screen with no graph yet reads — those are the same number and deliberately not two
 * different words, because `stack=0` is the assertion a d-cell makes after the flow closes
 * and it must not depend on which of the two the app happens to be in.
 */
@Composable
private fun ApprovalReadouts(screen: HarnessScreenState)
{
    val approval = screen.approval;
    val depth = if (approval == null) 0 else approval.approveDeviceFlow.stack.collectAsState().value.size;
    BasicText(text = HarnessReadout.stack(depth), style = Body);
    BasicText(text = HarnessReadout.http(screen.httpStatus), style = Body);
}

/**
 * Every control a flow taps, and nothing else, in the first viewport.
 *
 * The eleven are the ten lifecycle buttons and `open-approve`, laid out two to a row
 * because the height is the whole point. A runner cannot tap what
 * [androidx.compose.foundation.verticalScroll] left out of the accessibility tree, and it
 * leaves out everything the viewport does not overlap (P25), so this block has to end
 * above the fold on the smallest phone the harness is pointed at.
 *
 * The arithmetic, at this screen's own constants:
 *
 * - twelve readouts at 16sp, whose line box is the font's own — Roboto gives about 19dp,
 *   so 228dp. The size is not a lever: these lines ARE the protocol and a flow reads them.
 * - the divider block: 8dp above the rule, the 2dp rule, and a 16sp heading padded 8dp
 *   top and bottom — 45dp.
 * - this grid: six rows of [TouchTarget] with [GridGap] between them — 6 × 48 + 5 × 8,
 *   or 328dp. Neither number is a lever either: 48dp is what keeps a reported centre
 *   inside its own control ([TouchTarget], P21).
 * - the column's own 16dp of top padding.
 *
 * That is 617dp to the bottom row. The emulator the flows run on — Pixel 3a API 34, 393
 * × 786dp — leaves 714dp between the status and navigation bars, so the block ends with
 * about 97dp to spare. A 360 × 640dp phone leaves 568dp behind a three-button bar and the
 * last row would sit below the fold; the lever there is a third column, which fits at
 * that width only if no title wraps (`note-revoked` is the longest, at about 100dp).
 *
 * Whether it actually fits is not a thing a JVM can answer, and no test here claims to:
 * a device run does. `HarnessRunnerBlockTest` answers the other half — that the set of
 * ids the flows tap is exactly this block's.
 */
@Composable
private fun RunnerBlock(screen: HarnessScreenState, actions: HarnessActions)
{
    // Once per set of actions rather than once per frame: what the two blocks draw is a
    // fact about how this file is written, and writing does not change between frames.
    val runner = remember(actions) { checkedRunnerActions(actions) };

    LifecycleDivider();
    Column(verticalArrangement = Arrangement.spacedBy(GridGap))
    {
        for (row in runner.chunked(2))
        {
            Row(horizontalArrangement = Arrangement.spacedBy(GridGap))
            {
                for (action in row)
                {
                    // `open-approve` opens a flow whose calls are proven ones, so a build
                    // with nothing enrolled has nothing to sign them with. Dimmed rather
                    // than absent — see [HarnessActions.openApprove].
                    val enabled = action !== actions.openApprove || screen.hasActiveKey;
                    ActionRow(action = action, enabled = enabled, modifier = Modifier.weight(1f));
                }
                // The eleventh control is alone on the last row. The hole beside it is
                // held open so that row's cell is the width of every other cell: a control
                // that stretched to fill it would report a rectangle reaching under where
                // a person expects its neighbour, which is [TouchTarget]'s problem in the
                // other axis (P21).
                if (row.size == 1)
                {
                    Spacer(modifier = Modifier.weight(1f));
                }
            }
        }
    }
}

/**
 * The runner's eleven, in the order they are drawn — and the one place where the two tag
 * lists below are held against what the screen was actually handed.
 *
 * Checked rather than trusted, because the lists are read by a test that never draws
 * anything. A table beside a screen asserts only that somebody wrote it
 * (docs/IMPLEMENTATION-PITFALLS.md P10); one the screen refuses to launch without is a
 * statement about what is drawn. So a control added to a block and forgotten in its list
 * does not leave `HarnessRunnerBlockTest` quietly asserting about a screen that no longer
 * exists — it stops the app on its first frame, in front of whoever added it.
 */
private fun checkedRunnerActions(actions: HarnessActions): List<HarnessAction>
{
    val runner = actions.lifecycle + actions.openApprove;
    check(runner.map { it.tag } == RunnerBlockTags)
    {
        "the runner block draws ${runner.map { it.tag }}; RunnerBlockTags names $RunnerBlockTags"
    };

    val human = HarnessSocialCase.entries.map { caseTag(it) } +
        actions.socialSignIn.tag +
        actions.deviceSignIn.tag;
    check(human == HumanBlockTags)
    {
        "the device-mode block draws $human; HumanBlockTags names $HumanBlockTags"
    };
    return runner;
}

/**
 * The ids a Maestro flow may tap on this screen, because they are the ids inside the
 * first viewport.
 *
 * Read by `HarnessRunnerBlockTest`, which holds it against the `id:` selectors in
 * tools/harness/flows/ — the flow files are the definition and this list is what has to
 * agree with them. `btn_custody_probe` is here and in no flow, which is the direction
 * that is allowed: the block may hold a control no flow taps yet, never the reverse.
 */
val RunnerBlockTags: List<String> = listOf(
    "btn_enroll",
    "btn_rotate",
    "btn_resume",
    "btn_revoke",
    "btn_proven_call",
    "btn_note_revoked",
    "btn_wipe",
    "btn_custody_probe",
    "btn_block_network",
    "btn_open_network",
    "btn_open_approve"
);

/**
 * The ids below the fold, which a person scrolls to and no flow may name.
 *
 * A device-mode attempt is a person picking an account out of a provider sheet, so no
 * flow drives one and none ever can. That is what makes this half safe to put where the
 * accessibility tree does not reach until it is scrolled to.
 */
val HumanBlockTags: List<String> = listOf(
    "btn_case_first_enroll",
    "btn_case_re_login",
    "btn_case_user_cancel",
    "btn_case_network_failure",
    "btn_case_server_reject",
    "btn_social_google",
    "btn_device_sign_in"
);

/**
 * The half of the screen a person drives and no flow does, below the half that no person
 * drives.
 *
 * Below, because it is the half that may be scrolled to. An attempt here is somebody
 * picking an account out of a provider sheet, so no flow drives one and none can — and a
 * control no flow taps is a control that is allowed to start outside the accessibility
 * tree (P25).
 *
 * Two things to tap and one thing to choose. The five cases used to be buttons in the same
 * column as the sign-in and the ten lifecycle actions — seventeen controls that looked
 * alike, of which two did anything — and the operator was expected to remember a wipe
 * before each attempt as well.
 *
 * A sign-in and a case change are both refused while an attempt is in flight. A second tap
 * would start a second sheet over the first, and the first attempt's receipt would be
 * written about a run that no longer describes the screen.
 */
@Composable
private fun DeviceMode(screen: HarnessScreenState, actions: HarnessActions)
{
    val available = screen.socialConfigured && screen.busy !is Busy.Busy;

    Heading(text = "device verification");
    CaseSelector(screen = screen, enabled = available, onSelect = actions.selectCase);
    BasicText(
        text = screen.socialCase.precondition,
        style = Caption,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    );
    SignInRow(screen = screen, action = actions.socialSignIn, enabled = available);
}

/**
 * The one action button and the marker that says it is still running.
 *
 * The marker's slot is there whether or not it is filled, so showing it moves nothing: a
 * control that changes the layout of the screen while an attempt runs is a control that
 * can move another one out from under a finger.
 */
@Composable
private fun SignInRow(screen: HarnessScreenState, action: HarnessAction, enabled: Boolean)
{
    Row(verticalAlignment = Alignment.CenterVertically)
    {
        ActionRow(action = action, enabled = enabled, modifier = Modifier.weight(1f));
        Box(modifier = Modifier.width(RunningMarker), contentAlignment = Alignment.Center)
        {
            if (screen.attemptRunning)
            {
                BasicText(text = "running", style = Caption);
            }
        }
    }
}

/**
 * The five cases as one single-choice control, boxed so it cannot be mistaken for a stack
 * of actions.
 *
 * Exactly one is always selected and `first-enroll` is selected at launch. The invariant
 * is the state's rather than the screen's: one field holds one case, so there is no
 * arrangement of taps that selects two or none. That is what the platform's `RadioGroup`
 * was doing here before, and it is the only thing it was doing that mattered.
 */
@Composable
private fun CaseSelector(
    screen: HarnessScreenState,
    enabled: Boolean,
    onSelect: (HarnessSocialCase) -> Unit
)
{
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Rule, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
    {
        BasicText(text = "case (pick one)", style = Caption);
        for (case in HarnessSocialCase.entries)
        {
            CaseRow(
                case = case,
                selected = case == screen.socialCase,
                enabled = enabled,
                onSelect = onSelect
            );
        }
    }
}

/**
 * One case, spelled exactly as the shared spec spells it and as iOS spells it.
 *
 * The label is the wire name and nothing else. It used to read `case-first-enroll` here
 * against `[first-enroll]` on iOS, and a device run spent a round trip working out that
 * the three spellings were one case. Selection rides in the mark, where a selection
 * belongs, and never in the text — a flow reads this row's text as well.
 */
@Composable
private fun CaseRow(
    case: HarnessSocialCase,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (HarnessSocialCase) -> Unit
)
{
    Tappable(tag = caseTag(case), enabled = enabled, onTap = { onSelect(case) })
    {
        SelectionMark(selected = selected);
        Spacer(modifier = Modifier.width(12.dp));
        BasicText(text = case.wireName, style = Body);
    }
}

/** The tag a flow finds one case row by. The five names `ids.xml` declared, unchanged. */
private fun caseTag(case: HarnessSocialCase): String = when (case)
{
    HarnessSocialCase.FIRST_ENROLL -> "btn_case_first_enroll"
    HarnessSocialCase.RE_LOGIN -> "btn_case_re_login"
    HarnessSocialCase.USER_CANCEL -> "btn_case_user_cancel"
    HarnessSocialCase.NETWORK_FAILURE -> "btn_case_network_failure"
    HarnessSocialCase.SERVER_REJECT -> "btn_case_server_reject"
};

/** A ring, filled when this is the chosen case. Drawn rather than written, so it is not text. */
@Composable
private fun SelectionMark(selected: Boolean)
{
    Box(
        modifier = Modifier.size(20.dp).border(2.dp, Rule, CircleShape),
        contentAlignment = Alignment.Center
    )
    {
        if (selected)
        {
            Box(modifier = Modifier.size(10.dp).background(Ink, CircleShape));
        }
    }
}

/**
 * Signing THIS device in with a code somebody approves elsewhere.
 *
 * The other half of that flow — `open-approve`, which approves a device that is showing
 * one — used to sit here beside it, because the two are halves of one feature and a
 * harness has one phone in front of it at a time. It is in [RunnerBlock] now, and the
 * reason is not that it stopped being that: cells d1 to d3 tap it, so it is a runner's
 * control and it has to be where a runner can see it (P25). What is left here is the
 * half no flow can drive, since a code is walked to another phone by a person.
 */
@Composable
private fun DeviceCodeMode(actions: HarnessActions)
{
    Heading(text = "device code");
    ActionRow(action = actions.deviceSignIn, enabled = true);
}

/** The rule and the caption that say which half of the screen is below them — the flows'. */
@Composable
private fun LifecycleDivider()
{
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(Rule)
    );
    Heading(text = "sdk lifecycle (flows)");
}

@Composable
private fun Heading(text: String)
{
    BasicText(text = text, style = HeadingStyle, modifier = Modifier.padding(vertical = 8.dp));
}

/** One action: the title a person reads, tapped at the tag a flow names. */
@Composable
private fun ActionRow(action: HarnessAction, enabled: Boolean, modifier: Modifier = Modifier)
{
    Tappable(tag = action.tag, enabled = enabled, modifier = modifier, onTap = action.run)
    {
        BasicText(text = action.title, style = Body);
    }
}

/**
 * What a control does under a finger, given to every button and every case row here.
 *
 * Not decoration and not a theme. Foundation's own answer to a press is whatever
 * `LocalIndication` happens to be, which without Material is nothing at all, and on a
 * phone held at arm's length across a desk the honest answer to "did that tap land?" was
 * often nothing. Alpha and scale are visible on any background and need no colour chosen.
 *
 * A disabled control is dimmed by the same layer and takes no clicks: `sign-in-google` on
 * an unconfigured build is a state to be read, not a tap to be swallowed.
 *
 * [TouchTarget] is what keeps a runner's tap on the control it aimed at
 * (docs/IMPLEMENTATION-PITFALLS.md P21).
 */
@Composable
private fun Tappable(
    tag: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    content: @Composable RowScope.() -> Unit
)
{
    val interaction = remember { MutableInteractionSource() };
    val pressed by interaction.collectIsPressedAsState();
    val scale = if (pressed) 0.97f else 1f;

    Row(
        modifier = modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .graphicsLayer(alpha = alphaOf(enabled, pressed), scaleX = scale, scaleY = scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onTap
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    );
}

/** Dimmed while unavailable, dimmer still under a finger, opaque otherwise. */
private fun alphaOf(enabled: Boolean, pressed: Boolean): Float = when
{
    !enabled -> 0.4f
    pressed -> 0.55f
    else -> 1f
};

/**
 * The platform's minimum touch target, given to every control and field.
 *
 * Compose expands a control smaller than this past its layout bounds for touch, and in a
 * column of one-line controls those expansions overlap: the bounds reported for one
 * control then sit on a neighbour's, and a runner tapping the reported centre taps the
 * neighbour (docs/IMPLEMENTATION-PITFALLS.md P21). Sized here, nothing is expanded.
 */
private val TouchTarget: Dp = 48.dp;

/** The slot beside `sign-in-google`, held open whether or not the marker is in it. */
private val RunningMarker: Dp = 72.dp;

/**
 * What separates two cells of the runner grid, in both axes.
 *
 * Every cell is already [TouchTarget] tall and takes an equal share of the width, so this
 * is not what keeps the taps apart — [Tappable] is. It is here so that two controls
 * touching along an edge still read as two, and it is small because the grid's height is
 * what put the grid there (see [RunnerBlock]).
 */
private val GridGap: Dp = 8.dp;

// The screen's three colours, chosen as a set rather than taken from a theme. The view
// tree this replaced took the platform's theme and chose nothing, which was right for a
// TextView and is not available here: foundation has no theme, and BasicText's default
// colour is black whatever the device's is. Black on white is the pair that makes that
// default legible, and it is the same pair a screenshot of a receipt run needs.
private val Ink: Color = Color.Black;
private val Paper: Color = Color.White;
private val Rule: Color = Color.Gray;

private val Body: TextStyle = TextStyle(color = Ink, fontSize = 16.sp);
private val Caption: TextStyle = TextStyle(color = Ink, fontSize = 14.sp);
private val HeadingStyle: TextStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold);
