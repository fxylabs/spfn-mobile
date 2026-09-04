// SPFN Mobile — the container a host app gives a pushed flow to stand in.
//
// Counterpart of Sources/SPFNUI/NavigationHost.swift: same name, same one job, same
// `HostStack` underneath.
//
// ---------------------------------------------------------------------------
// What this is for
// ---------------------------------------------------------------------------
//
// A `FlowHost(Push)` used to build a `NavDisplay` of its own and draw it over whatever the
// host app was showing. Nothing said it was wrong until a person opened one on a phone: the
// flow's first screen APPEARED rather than sliding in, and its header had no way back to the
// menu that opened it, because there was no entry under it to go back TO
// (docs/IMPLEMENTATION-PITFALLS.md P31).
//
// Decision N1 is this composable. The host app wraps whatever it draws in one
// `NavigationHost { ... }`; a `FlowHost(Push)` inside it APPENDS its routes to this back
// stack instead of building a second one, so the person gets a right-to-left push into the
// flow, the predictive back out of it, and the activity's own back at the root.
//
// ---------------------------------------------------------------------------
// A registration outlives the composition that made it, and has to
// ---------------------------------------------------------------------------
//
// `NavDisplay` composes the entries of the scene on show and no others: push a route over
// the host's root and the root's own composition — the app's menu, and every `FlowHost` in
// it — is disposed until it comes back. That is what makes a `DisposableEffect` the wrong
// place to keep this registry current, and not by a little: unregistering on dispose would
// take the flow's own entries off the stack the instant its first route covered the root,
// the stack would pop back to the root, and the two would fight for as long as anybody
// watched.
//
// So a flow registers ONCE and the store follows its stack from a coroutine of its own,
// living on this composable's scope rather than on the root's. A `Flow` publishes a
// `StateFlow`; collecting it is how the host learns about a push that happened while the
// host's root was not composed, which is every push after the first.
//
// The same sentence with the same consequence is why nothing is unregistered on dispose on
// the SwiftUI half either.
//
// ---------------------------------------------------------------------------
// What a host app puts on its own root does NOT reach a pushed flow
// ---------------------------------------------------------------------------
//
// The `root` this takes is one entry of this NavDisplay, and a pushed flow's routes are
// other entries of it. They are SIBLINGS of the root, not children of it, so a `Modifier`,
// a `CompositionLocalProvider` or a `semantics` block an app wraps its own content in is
// simply not above them.
//
// It costs a runner its selectors, silently. `testTagsAsResourceId` — the switch that turns
// a Compose test tag into the Android resource id a Maestro `id:` selector matches — is
// resolved by walking semantics PARENTS, so an app that sets it inside this host keeps it
// for its own screens and loses it for every screen of every pushed flow: the text on those
// screens still matches and every control stops being findable
// (docs/IMPLEMENTATION-PITFALLS.md P33).
//
// So anything an app means for the whole of its navigation goes OUTSIDE this composable.
// What belongs inside is what belongs to the app's own screen and not to the flows: its own
// insets, for one, because a pushed `Screen` spends the status bar inset on its own header
// where nothing above it has consumed one (docs/IMPLEMENTATION-PITFALLS.md P25).

package xyz.superfunction.spfn.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The host app's navigation, and the back stack a pushed flow appends to.
 *
 * One per app — or one per independently navigating region — wrapped around whatever the app
 * draws:
 *
 * ```
 * NavigationHost {
 *     Box(modifier = Modifier.fillMaxSize()) {
 *         Menu();
 *         SomePushFlowHost(container);
 *     }
 * }
 * ```
 *
 * A `FlowHost` for a modal or a sheet needs nothing from this and behaves the same inside it
 * or outside it: both are presentations OVER the navigation rather than entries in it.
 *
 * `@JvmSynthetic` for the reason [FlowHost] carries it: a `@Composable` function is a rule
 * the Compose compiler enforces for Kotlin callers and for nobody else
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun NavigationHost(root: @Composable () -> Unit)
{
    val scope = rememberCoroutineScope();
    val host = remember(scope) { HostStackStore(scope) };
    val entries = host.stack.collectAsState().value.entries;

    CompositionLocalProvider(LocalNavigationHost provides host) {
        NavDisplay(
            backStack = listOf<Any>(HostRoot) + entries,
            // Only ever the top flow's, and only while there is one: on the host's own root
            // the back stack is one entry long, NavDisplay disables its own handling, and
            // the activity is what the system back closes — which is what a back on an app's
            // first screen has always meant.
            onBack = { host.back() },
            // Stated rather than left to the navigator's default, because the default is the
            // library's opinion and this one is the platform's: a push comes in from the
            // right and leaves to the right, on both platforms, and a person who does not
            // see it move does not know a screen arrived.
            transitionSpec = {
                slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width / SHIFT }
            },
            popTransitionSpec = {
                slideInHorizontally { width -> -width / SHIFT } togetherWith slideOutHorizontally { width -> width }
            },
            predictivePopTransitionSpec = { _ ->
                slideInHorizontally { width -> -width / SHIFT } togetherWith slideOutHorizontally { width -> width }
            },
            entryProvider = { key ->
                if (key is HostEntry) NavEntry(key) { host.Screen(key) } else NavEntry(key) { root() }
            }
        );
    };
}

/**
 * How far the screen underneath moves while the one over it comes in.
 *
 * A fraction and not a full width: the platform's own push slides the outgoing screen a
 * short way and parallaxes it, and a screen that left at the same speed as the one arriving
 * reads as two screens passing rather than as one covering another.
 */
private const val SHIFT: Int = 4;

/**
 * The host's own root, as one key on the back stack it can never be popped past.
 *
 * A `data object` so that Navigation 3 identifies it by value the way it identifies every
 * other key, and it is deliberately not a [HostEntry]: an entry belongs to a flow and this
 * belongs to the app.
 */
private data object HostRoot

/** What one flow told the host: how to draw its routes, and what its back does. */
internal class HostRegistration(
    /**
     * Draws one of this flow's routes, wrapped in this flow's own chrome. The route arrives
     * erased, because one registry holds flows whose route types have nothing in common.
     */
    val screen: @Composable (Any) -> Unit,
    /** One back, spent through `Flow.back` like every other back in this module. */
    val back: () -> Unit
)

/**
 * The host's stack and its registry, as one object under the composition local.
 *
 * Not public: a host app builds a [NavigationHost] and a flow registers itself through
 * [FlowHost], and a third door onto this would be a second way to write a stack that has one
 * writer on purpose.
 */
internal class HostStackStore(private val scope: CoroutineScope)
{
    private val mutableStack = MutableStateFlow(HostStack());

    /** The one list, and the only thing [NavigationHost] draws from. */
    val stack: StateFlow<HostStack> = mutableStack.asStateFlow();

    private val registrations = mutableMapOf<Any, HostRegistration>();

    private val collectors = mutableMapOf<Any, Job>();

    /**
     * Says how a flow's routes are drawn and what its back does, and starts following its
     * stack.
     *
     * The registration is replaced on every call, so a host root that recomposed hands over
     * a closure that closes over what it now has. The COLLECTOR is started once: it is what
     * keeps the host's stack current while the root is not composed at all, which is the
     * whole time any of this flow's routes are on show.
     */
    fun register(owner: Any, routes: StateFlow<List<Any>>, registration: HostRegistration)
    {
        registrations[owner] = registration;
        if (collectors.containsKey(owner))
        {
            return;
        }
        collectors[owner] = scope.launch {
            routes.collect { stack -> sync(owner, stack) };
        };
    }

    /**
     * Takes one flow's stack as it now is. A closed flow syncs nothing, which is how its
     * entries leave the host's stack.
     */
    fun sync(owner: Any, routes: List<Any>)
    {
        mutableStack.value = mutableStack.value.sync(owner, routes);
    }

    /**
     * One system back, given to whoever is on top.
     *
     * Only the flow whose entry is on top can have meant it, and what it does with it is
     * `Flow.back` — the same door the header control goes through, so a gesture and a tap
     * cannot come to disagree.
     */
    fun back()
    {
        val owner = mutableStack.value.topOwner() ?: return;
        registrations[owner]?.back?.invoke();
    }

    /** Draws one entry, by asking whoever put it there. */
    @Composable
    fun Screen(entry: HostEntry)
    {
        val registration = registrations[entry.owner];
        if (registration != null)
        {
            registration.screen(entry.route);
        }
    }
}

/**
 * The host in scope, or null.
 *
 * A `FlowHost(Push)` that reads null draws its own inline stack, which is the compatibility
 * path its header describes.
 */
internal val LocalNavigationHost: ProvidableCompositionLocal<HostStackStore?> =
    staticCompositionLocalOf { null };
