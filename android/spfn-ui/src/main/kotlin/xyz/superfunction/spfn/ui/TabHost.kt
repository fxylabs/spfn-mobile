// SPFN Mobile — the bottom tab container: one NavigationHost per tab, and a bar the SDK draws.
//
// Counterpart of Sources/SPFNUI/TabHost.swift: same names, same bar, same rules. The design
// is docs/architecture/tab-host-design.md, and the three decisions this file is made of are
// stated there at length; the short form is here so the code can be read against it.
//
// ---------------------------------------------------------------------------
// The bar is part of each tab's ROOT screen
// ---------------------------------------------------------------------------
//
// Each tab is a `NavigationHost` whose root is the app's root content with the bar laid out
// under it. A pushed route is another entry of that host's `NavDisplay` — a sibling of the
// root, not a child — so it slides in over the root AND its bar, the held predictive back
// previews the root with its bar, and nothing anywhere hides or shows a bar. "No bar above
// depth zero" is the structure, not a rule some code keeps (§2-2).
//
// ---------------------------------------------------------------------------
// Only the selected tab is composed; its store is not
// ---------------------------------------------------------------------------
//
// A tab that is not selected leaves the composition: a hidden tab left composed keeps its
// nodes in the semantics tree, and a runner would find controls nobody can see (P25 turned
// around). What must NOT leave with it is its `HostStackStore` — the stack, the flows'
// registrations and the collectors that follow them. So this composable remembers one store
// per tab id in its own scope and hands it to `NavigationHost`'s internal overload, and a
// `SaveableStateHolder` keeps each tab's `rememberSaveable` state (a list's scroll position,
// the navigator's per-entry state) under the tab's id while it is away (§2-4).
//
// ---------------------------------------------------------------------------
// A modal or a sheet opened inside a tab covers the bar
// ---------------------------------------------------------------------------
//
// A cover fills its parent, and a tab root's parent stops at the bar. So a `FlowHost` of a
// modal or a sheet finds `LocalTabOverlays` and registers its presentation here, and this
// composable draws every registered presentation in a layer over the tabs and the bar — the
// same registration shape a pushed flow has with its `NavigationHost` (§2-3). The layer is a
// `Box` sibling and not a `Dialog`, for the reason `FlowHost.kt` gives: a second window would
// take every control inside it out of reach of `testTagsAsResourceId`.
//
// ---------------------------------------------------------------------------
// The system back
// ---------------------------------------------------------------------------
//
// Above a tab's root the tab's own `NavDisplay` takes it. On the root of a tab that is not
// the start tab this composable's `BackHandler` takes it and selects the start tab; on the
// start tab's root nothing does and the activity finishes (decision Q-B). The handler is
// enabled by `TabState.handlesBack` — by the stack's state, never by a screen's lifecycle
// (P32) — and it is off while a registered cover or sheet is up, which takes its own back.
//
// `testTagsAsResourceId`, and anything else meant for the whole app, goes OUTSIDE this
// composable: a pushed route and the overlay layer are not children of a tab's root (P33).

package xyz.superfunction.spfn.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.ui.components.Metrics
import xyz.superfunction.spfn.ui.tokens.LocalSpfnTheme
import xyz.superfunction.spfn.ui.tokens.spfnPalette

/**
 * One tab, declared as data: its id, what the bar shows for it, and its root content.
 *
 * @param id the tab's identity: [TabState]'s id, the key its saved state is kept under, and
 *   the bar item's test tag `tab.<id>`. lowerCamelCase, as a spec name is.
 * @param title the bar item's label.
 * @param icon the bar item's mark, tinted with the theme's colours.
 * @param selectedIcon the mark while the tab is selected. Left out, [icon] is drawn in both.
 * @param accessibilityLabel what TalkBack reads for the item. Left out, [title].
 * @param root the tab's root content — a `Screen`, usually, with the `FlowHost`s of the flows
 *   this tab opens inside it. The bar is laid out under it by [TabHost].
 */
public class TabItem(
    public val id: String,
    public val title: String,
    public val icon: Painter,
    public val selectedIcon: Painter? = null,
    public val accessibilityLabel: String? = null,
    public val root: @Composable () -> Unit
)

/**
 * How many times this tab's root has been asked to scroll to its top — a press on the tab
 * that is already selected, standing on its root.
 *
 * The SDK does not scroll: the root's list is the app's, and so is its state (decision Q-C).
 * A list follows the count instead:
 *
 * ```
 * val scrollToTop = TabScrollToTop.current;
 * LaunchedEffect(scrollToTop) { if (scrollToTop.count > 0) listState.animateScrollToItem(0); };
 * ```
 */
public data class TabScrollToTop(public val count: Int)
{
    public companion object
    {
        /** Outside a [TabHost], and on a tab that has not been asked yet. */
        @JvmField
        public val None: TabScrollToTop = TabScrollToTop(0);

        /** The count of the tab this composition stands in. */
        @get:JvmSynthetic
        public val current: TabScrollToTop
            @Composable
            @ReadOnlyComposable
            get() = LocalTabScrollToTop.current;
    }
}

/**
 * The bottom tab container: [tabs] in a bar the SDK draws, one navigation stack per tab.
 *
 * The app's top level. There is no `NavigationHost` above it — each tab IS one, and a
 * navigator inside another would fight it for the back (§2-3). Theme it from outside, as a
 * `NavigationHost`: `SpfnTheme(brand) { TabHost(state, tabs) }`.
 *
 * ```
 * TabHost(
 *     state = container.tabs,
 *     tabs = listOf(
 *         TabItem("home", "Home", homeIcon) { HomeScreen(); ItemFlowHost(container); },
 *         TabItem("account", "Account", accountIcon) { AccountScreen(); }
 *     )
 * );
 * ```
 *
 * [tabs]' ids must be [state]'s, in its order. A debuggable app stops on a mismatch; a
 * release build draws the items [tabs] has.
 *
 * `@JvmSynthetic` for the reason [FlowHost] carries it (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun TabHost(state: TabState, tabs: List<TabItem>)
{
    val debuggable = debuggable();
    check(!debuggable || tabs.map { it.id } == state.tabs) {
        "TabHost was given tabs ${tabs.map { it.id }} for a TabState of ${state.tabs}"
    };
    val scope = rememberCoroutineScope();
    val stores = remember(scope) { TabStores(scope, debuggable) };
    val overlays = remember(scope) { TabOverlays(scope) };
    val holder = rememberSaveableStateHolder();
    val selected = state.selected.collectAsState().value;
    val shown = tabs.firstOrNull { it.id == selected } ?: tabs.firstOrNull() ?: return;
    val store = stores.of(shown.id);
    val depth = store.stack.collectAsState().value.entries.size;
    val presenting = overlays.presented.collectAsState().value.isNotEmpty();
    val counts = state.scrollCounts.collectAsState().value;

    RestoredSelection(state);
    BackHandler(enabled = !presenting && state.handlesBack(depth)) { state.back(depth) };

    CompositionLocalProvider(LocalTabOverlays provides overlays)
    {
        Box(modifier = Modifier.fillMaxSize())
        {
            key(shown.id)
            {
                holder.SaveableStateProvider(shown.id)
                {
                    CompositionLocalProvider(LocalTabScrollToTop provides TabScrollToTop(counts[shown.id] ?: 0))
                    {
                        NavigationHost(host = store)
                        {
                            TabRoot(item = shown, bar = { TabBar(state, tabs, selected, store) });
                        };
                    };
                };
            };
            overlays.Draw();
        };
    };
}

/**
 * The selected tab across a process death (C-28): saved with the activity's state, and
 * handed back to [state] once when the composition comes back.
 *
 * Only the selection. The flows' stacks are the app's objects and are not restored — the
 * same answer `NavigationHost` gives today (decision Q-F).
 */
@Composable
private fun RestoredSelection(state: TabState)
{
    var saved by rememberSaveable { mutableStateOf<String?>(null) };
    LaunchedEffect(state)
    {
        saved?.let { state.show(it) };
        state.selected.collect { saved = it };
    };
}

/**
 * A tab's root: the app's content, and the bar under it.
 *
 * The bar owns the bottom inset while it is shown (§6, "Screen owns the insets" updated):
 * the root is told the navigation bar AND the bar's own height are spent, so a `Screen` body
 * that avoids `ime ∪ navigationBars` pads nothing without a keyboard and exactly the part of
 * the keyboard that overlaps it with one. The bar itself does not avoid the keyboard — it
 * stays where it is and the keyboard covers it, so the root is not laid out again under it
 * (K1 holds for the body, C-23).
 */
@Composable
private fun TabRoot(item: TabItem, bar: @Composable () -> Unit)
{
    var barHeight by remember { mutableIntStateOf(0) };
    Column(modifier = Modifier.fillMaxSize())
    {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = barHeight))
                )
        )
        {
            item.root();
        }
        Box(modifier = Modifier.onSizeChanged { barHeight = it.height })
        {
            bar();
        }
    }
}

/**
 * The bar: one item per tab, each an equal share of the width.
 *
 * Drawn with foundation — this module does not depend on Material (§2-4). A `selectableGroup`
 * row of `Role.Tab` items is what TalkBack reads as a tab list with one selected (§6).
 *
 * The navigation bar inset is padded HERE, below the items, and consumed nowhere above: the
 * bar is the one that stands on it while it is shown (P25).
 */
@Composable
private fun TabBar(state: TabState, tabs: List<TabItem>, selected: String, store: HostStackStore)
{
    val palette = spfnPalette();
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
    )
    {
        Box(modifier = Modifier.fillMaxWidth().height(Metrics.BORDER_WIDTH).background(palette.handle));
        Row(modifier = Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically)
        {
            tabs.forEach { item ->
                TabBarItem(item = item, selected = item.id == selected)
                {
                    if (state.select(item.id, store.depth) == TabSelection.PopToRoot)
                    {
                        store.shorten(0);
                    }
                };
            };
        }
    }
}

/**
 * One item: the mark over the label, in the accent when selected and the secondary text
 * colour when not.
 *
 * At least the minimum touch target tall in its OWN layout (P21), and a whole share of the
 * bar wide, so every item reports its own rectangle. The label is one line and ellipsised;
 * it is in `sp`, so a large font scale makes the bar taller rather than cutting it off (U-8).
 * The mark carries no description: the item's label is what is read (§6).
 */
@Composable
private fun RowScope.TabBarItem(item: TabItem, selected: Boolean, onClick: () -> Unit)
{
    val palette = spfnPalette();
    val theme = LocalSpfnTheme.current;
    val tint = if (selected) palette.accent else palette.textSecondary;
    val label = item.accessibilityLabel;
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Metrics.TOUCH_TARGET)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .testTag("tab.${item.id}")
            .padding(vertical = theme.spacing.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    )
    {
        Image(
            painter = if (selected) item.selectedIcon ?: item.icon else item.icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(Metrics.ICON_SIZE)
        );
        BasicText(
            text = item.title,
            style = theme.typography.caption.copy(color = tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (label != null) Modifier.clearAndSetSemantics {} else Modifier
        );
    }
}

/**
 * Every tab's `HostStackStore`, made the first time a tab is shown and kept for as long as
 * the [TabHost] is.
 *
 * Each store is told which tab it is, so a flow registered with one tab is refused by
 * another ([TabOwners]). A debuggable app stops there; a release build keeps the first
 * tab's registration and ignores the second (§2-3).
 */
private class TabStores(private val scope: CoroutineScope, private val debuggable: Boolean)
{
    private val owners = TabOwners();

    private val stores = mutableMapOf<String, HostStackStore>();

    fun of(tab: String): HostStackStore = stores.getOrPut(tab) {
        HostStackStore(scope) { owner -> claim(owner, tab) }
    };

    private fun claim(owner: Any, tab: String): Boolean
    {
        val claimed = owners.claim(owner, tab);
        check(claimed || !debuggable) {
            "a flow registered with tab '$tab' is already on another tab's stack; one flow belongs to one tab"
        };
        return claimed;
    }
}

/**
 * The covers and sheets opened inside a [TabHost], drawn over its tabs and its bar.
 *
 * One registration per flow, in the order they were first made, so a cover opened from
 * inside another cover is drawn over it. Each is followed from this host's scope: [presented]
 * is every flow whose presentation is up, which is what turns the tab host's own back off.
 * Nothing is unregistered, for the reason `NavigationHost.kt` gives about its own registry: a
 * tab that is not selected is not composed, and its flows' covers have to outlive that.
 */
internal class TabOverlays(private val scope: CoroutineScope)
{
    private val order = mutableStateListOf<Any>();

    private val drawers = mutableStateMapOf<Any, @Composable () -> Unit>();

    private val collectors = mutableMapOf<Any, Job>();

    private val mutablePresented = MutableStateFlow<Set<Any>>(emptySet());

    /** The flows whose cover or sheet is up. */
    val presented: StateFlow<Set<Any>> = mutablePresented.asStateFlow();

    /** Says how [owner]'s presentation is drawn, and starts following whether it is up. */
    fun present(owner: Any, isPresented: StateFlow<Boolean>, draw: @Composable () -> Unit)
    {
        drawers[owner] = draw;
        if (collectors.containsKey(owner))
        {
            return;
        }
        order.add(owner);
        collectors[owner] = scope.launch {
            isPresented.collect { up ->
                mutablePresented.value = if (up) mutablePresented.value + owner else mutablePresented.value - owner;
            };
        };
    }

    /** Every registered presentation, oldest underneath. */
    @Composable
    fun Draw()
    {
        order.forEach { owner ->
            key(owner)
            {
                drawers[owner]?.invoke();
            };
        };
    }
}

/** Whether the app was built debuggable, which is when a misuse of [TabHost] stops it. */
@Composable
private fun debuggable(): Boolean
{
    val context = LocalContext.current;
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 };
}

/** The tab host in scope, or null: where a modal or a sheet `FlowHost` registers. */
internal val LocalTabOverlays: ProvidableCompositionLocal<TabOverlays?> = staticCompositionLocalOf { null };

/** The count [TabScrollToTop.current] reads. */
internal val LocalTabScrollToTop: ProvidableCompositionLocal<TabScrollToTop> =
    compositionLocalOf { TabScrollToTop.None };
