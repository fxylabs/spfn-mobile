// SPFN Mobile — which tab is selected, and what a press on the bar or a system back means.
//
// Counterpart of Sources/SPFNUI/TabState.swift. Free of Compose, for the reason `Flow` is:
// every rule here is a rule about a list of ids and one selected id, so the whole case table
// of docs/architecture/tab-host-design.md §4 that is pure state runs as a plain JVM suite.
// `TabHost.kt` is what binds it to a composition.
//
// ---------------------------------------------------------------------------
// Why the depth is an argument
// ---------------------------------------------------------------------------
//
// A press on the current tab pops to its root when the tab stands on something, and asks for
// a scroll to the top when it does not. A system back on a tab's root goes to the start tab.
// Both answers depend on how deep the SELECTED tab's stack stands, and that stack is its
// `NavigationHost`'s — which this type does not hold and must not: one tab per
// `NavigationHost` is the design (§2-3), and a second copy of a host's depth kept here would
// be a second writer of a stack that has one on purpose. So the host passes the depth in and
// this type answers, the way `Flow.back` is told the presentation and answers. The decision
// is a pure function; the host performs it.

package xyz.superfunction.spfn.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a press on a tab bar item asked for, which the host then performs. */
public enum class TabSelection
{
    /** Another tab was pressed and is now selected. Nothing moved in either tab's stack. */
    Switched,

    /** The selected tab was pressed while it stood above its root: the host pops it there. */
    PopToRoot,

    /** The selected tab was pressed on its root: its scroll-to-top count went up by one. */
    ScrollToTop,

    /** The id is not one of this state's tabs. Nothing changed. */
    Ignored
}

/**
 * The tabs a [TabHost] shows, which one is selected, and what a press or a back means.
 *
 * The ORDER of [tabs] is the bar's order, and the first is the start tab: the one a launch
 * selects and the one a system back on another tab's root returns to. There is no argument
 * that names a start tab of its own, because a second way to say it is a second answer.
 *
 * ```
 * val tabs = TabState(listOf("home", "account"));
 * tabs.show("account");                    // a deep link, or the app switching tabs itself
 * ```
 *
 * Callers mutate it from the main dispatcher, as they do a [Flow]: Kotlin has no equivalent
 * of the `@MainActor` the Swift half declares.
 *
 * @throws IllegalArgumentException for an empty list, an id written twice, or a [selected]
 *   that is not in the list — the refusal `Flow.open` makes for an empty stack.
 */
public class TabState(tabs: List<String>, selected: String? = null)
{
    /** The tab ids, in the bar's order. */
    public val tabs: List<String> = tabs.toList();

    /** The first tab, which a launch selects and a back on another tab's root returns to. */
    public val start: String;

    private val mutableSelected: MutableStateFlow<String>;

    /** The selected tab's id. */
    public val selected: StateFlow<String>;

    private val mutableScrollCounts: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap());

    /** How many times each tab's root was asked to scroll to its top; [TabHost] reads it. */
    internal val scrollCounts: StateFlow<Map<String, Int>> = mutableScrollCounts.asStateFlow();

    init
    {
        require(tabs.isNotEmpty()) { "a tab state needs at least one tab" };
        val twice = tabs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull();
        require(twice == null) { "tab '$twice' is declared twice" };
        require(selected == null || selected in tabs) { "tab '$selected' is not one of $tabs" };
        start = tabs.first();
        mutableSelected = MutableStateFlow(selected ?: start);
        this.selected = mutableSelected.asStateFlow();
    }

    /**
     * A press on the bar item [id], while the selected tab's stack stands [depth] above its
     * root. What the bar calls, and only the bar.
     *
     * @return what the host has to do about it. [TabSelection.PopToRoot] is the host's to
     *   perform — this type does not hold the stack.
     */
    public fun select(id: String, depth: Int): TabSelection
    {
        if (id !in tabs)
        {
            return TabSelection.Ignored;
        }
        if (id != mutableSelected.value)
        {
            mutableSelected.value = id;
            return TabSelection.Switched;
        }
        if (depth > 0)
        {
            return TabSelection.PopToRoot;
        }
        val counts = mutableScrollCounts.value;
        mutableScrollCounts.value = counts + (id to (counts[id] ?: 0) + 1);
        return TabSelection.ScrollToTop;
    }

    /**
     * Selects [id] the way an app does — a deep link, a switch in code. Never pops and never
     * asks for a scroll, and does nothing for an id that is already selected or not a tab.
     *
     * Not [select] with a depth of zero, and the difference is the point: a press on the
     * selected tab MEANS something, and an app that shows the tab it is already on means
     * nothing by it. A deep link that popped the stack it arrived in would undo itself.
     */
    public fun show(id: String)
    {
        if (id in tabs && id != mutableSelected.value)
        {
            mutableSelected.value = id;
        }
    }

    /**
     * A system back, while the selected tab's stack stands [depth] above its root.
     *
     * On the root of a tab that is not the start tab, the start tab is selected and the back
     * is consumed. Everywhere else it is not: above a root the tab's own `NavDisplay` pops,
     * and on the start tab's root the activity finishes (decision Q-B).
     *
     * @return whether this state consumed the back.
     */
    public fun back(depth: Int): Boolean
    {
        if (!handlesBack(depth))
        {
            return false;
        }
        mutableSelected.value = start;
        return true;
    }

    /**
     * Whether [back] would consume a back, asked BEFORE the gesture is claimed: a
     * `BackHandler` takes its `enabled` flag ahead of the event, as [Flow.handlesBack] says.
     */
    public fun handlesBack(depth: Int): Boolean = depth == 0 && mutableSelected.value != start;

    /**
     * How many times the root of tab [id] has been asked to scroll to its top. An app's list
     * scrolls when this changes; the SDK does not hold the list's scroll state and does not
     * scroll it (decision Q-C).
     */
    public fun scrollToTop(id: String): Int = mutableScrollCounts.value[id] ?: 0
}

/**
 * Which tab each flow belongs to, so one flow is never on two tabs' stacks.
 *
 * A flow's `FlowHost(Push)` registers with the nearest `NavigationHost`, and inside a
 * [TabHost] that is its own tab's: another tab's host is not in its composition, so it
 * cannot register there by mistake. What CAN happen is an app putting the same flow's host in
 * two tab roots, and then two hosts would follow one stack and the detail would stand on both
 * tabs at once. The first tab to claim a flow keeps it; the second claim is refused, which a
 * debuggable app turns into a stop (docs/architecture/tab-host-design.md §2-3).
 */
internal class TabOwners
{
    private val tabs = mutableMapOf<Any, String>();

    /** Whether [tab] may register [owner]: it may if nobody has, or if it already did. */
    fun claim(owner: Any, tab: String): Boolean
    {
        val holder = tabs.getOrPut(owner) { tab };
        return holder == tab;
    }
}
