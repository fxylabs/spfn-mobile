package xyz.superfunction.spfn.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
 * Readouts, then the half a person drives, then the half the flows drive. This is not a
 * sample app and not a design: every control exists because a flow needs to tap it or a
 * person needs to read it, and the order is what a device run bought. The device-mode
 * controls sit on top because that is what a phone is for here, and the ten lifecycle
 * buttons sit under a divider that says so — with every tag and every title they always
 * had, because a flow finds them by those strings and a rearranged screen must not be a
 * renamed one.
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
                // The column is longer than a phone. A runner taps what the hierarchy
                // reports, and what it reports is what was laid out — so the order is the
                // old screen's, unchanged, and nothing below the fold moved above it.
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
        {
            Readouts(screen);
            DeviceMode(screen, actions);
            DeviceCodeMode(screen, actions);
            LifecycleDivider();
            for (action in actions.lifecycle)
            {
                ActionRow(action = action, enabled = true);
            }
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
 * The half of the screen a person drives and no flow does, above the half that no person
 * drives.
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
 * Signing this device in with a code, and approving another device that shows one.
 *
 * Two halves of one flow on one screen, because a harness has one phone in front of it at
 * a time and either half has to be reachable.
 *
 * The approving half is ONE control now. It used to be a code field and three buttons
 * wiring up `info`, `approve` and `deny` by hand — a second implementation of screens the
 * generator already emits. `open-approve` builds the generated graph and opens its flow
 * over this screen, and everything a person types or taps after that is the generator's.
 *
 * It is disabled without an active key rather than hidden: the approval calls are proven
 * ones, so a build with nothing enrolled has nothing to sign them with, and a control
 * that vanished would read as a harness that lost a feature where a dimmed one beside
 * `state=unenrolled` reads as the truth.
 */
@Composable
private fun DeviceCodeMode(screen: HarnessScreenState, actions: HarnessActions)
{
    Heading(text = "device code");
    ActionRow(action = actions.deviceSignIn, enabled = true);
    BasicText(
        text = "approve a device",
        style = Caption,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    );
    ActionRow(action = actions.openApprove, enabled = screen.hasActiveKey);
}

/** The rule and the caption that say which half of the screen is below them. */
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
