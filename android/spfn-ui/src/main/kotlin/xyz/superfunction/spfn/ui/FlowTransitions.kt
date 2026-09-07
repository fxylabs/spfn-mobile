// SPFN Mobile — the three movements every stack in this module travels on, in one place.
//
// `NavDisplay` takes three transition specs and defaults all three when they are not given.
// This module draws FOUR of them — the host app's own navigation in `NavigationHost`, and
// the three `InlineStack` builds for a sheet's stack, a modal's cover and a pushed flow that
// found no host to append to — and for as long as only the first one stated its specs the
// other three took the library's.
//
// That is not a difference in polish, it is two different apps. A person tapped `next`
// inside a modal flow and the next screen FADED in where the same tap in a pushed flow slid
// it in from the right; a system back inside that modal SHRANK the screen away — scaled down
// and faded — where the same gesture in a pushed flow slid it back off to the right
// (docs/IMPLEMENTATION-PITFALLS.md P37).
//
// ---------------------------------------------------------------------------
// What the library's default actually is
// ---------------------------------------------------------------------------
//
// Read out of navigation3-ui 1.1.7 rather than assumed: `NavDisplayKt__NavDisplay_androidKt`
// holds `defaultTransitionSpec`, `defaultPopTransitionSpec` and
// `defaultPredictivePopTransitionSpec`, and javap on the three lambdas they return gives
//
//     forward        fadeIn(tween(700))  togetherWith  fadeOut(tween(700))
//     pop            fadeIn(tween(700))  togetherWith  fadeOut(tween(700))
//     predictive pop fadeIn(spring(dampingRatio = 1f, stiffness = 1600f))
//                                        togetherWith  scaleOut(targetScale = 0.7f)
//
// So the forward step and the pop are the same fade in both directions — a person cannot
// tell from the movement which way the stack went — and the predictive pop is the scale-down
// that was seen on a phone. Every one of them is a reasonable default for a navigator that
// does not know what it is drawing, and none of them is what a stack of screens does on
// either platform this SDK ships to.
//
// ---------------------------------------------------------------------------
// Why there is nothing to compare on the other side
// ---------------------------------------------------------------------------
//
// The SwiftUI half states none of this and is not missing it: a `.fullScreenCover` and a
// `NavigationStack` inside a sheet push and pop on the system's own slide, so the platform
// has already made this decision there and there is no name to hold the two halves to. This
// is an Android-only file on purpose, and the validator checks it as an Android-only rule.

package xyz.superfunction.spfn.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * The push, the pop and the predictive pop — one set, handed to every [androidx.navigation3.ui.NavDisplay]
 * this module builds.
 *
 * Stated rather than left to the navigator's default, because the default is the library's
 * opinion and this one is the platform's: a push comes in from the right and leaves to the
 * right, on both platforms, and a person who does not see it move does not know a screen
 * arrived.
 *
 * One place and not three, because the value of these three is that they are the SAME three.
 * A modal flow whose screens move differently from a pushed flow's teaches a person that the
 * two are different kinds of thing, which they are not: both are a stack of screens, and the
 * only thing that differs is what the stack stands on.
 *
 * Shared instances are safe here. A [ContentTransform] is only mutated by the `using` infix
 * that attaches a `SizeTransform` to it, nothing in navigation3-ui 1.1.7 calls it (checked
 * with javap across the module's classes), and the enter and exit transitions it holds are
 * immutable descriptions that Compose reads to build modifiers.
 */
internal object FlowTransitions
{
    /**
     * A screen arriving over another: in from the right, with the one underneath shifted a
     * short way to the left behind it.
     */
    val forward: ContentTransform =
        slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width / SHIFT };

    /** The same movement run the other way: the screen leaving goes back out to the right. */
    val pop: ContentTransform =
        slideInHorizontally { width -> -width / SHIFT } togetherWith slideOutHorizontally { width -> width };

    /**
     * What is drawn while a back gesture is being HELD, which is [pop] itself.
     *
     * The same value and not a copy of it, because a back that is being decided and a back
     * that has been decided are the same movement — what differs is the clock. A predictive
     * spec is SEEKED by the gesture's progress rather than run by an animation, so holding
     * halfway draws this halfway through and letting go from there finishes it from where
     * the finger left it.
     *
     * The spec `NavDisplay` asks for takes the gesture's edge as an argument, and this
     * ignores it: a back from the left edge and a back from the right edge are one back, and
     * a screen that slid off whichever way the thumb came from would be the SDK reading a
     * grip as an instruction.
     */
    val predictivePop: ContentTransform = pop;
}

/**
 * How far the screen underneath moves while the one over it comes in.
 *
 * A fraction and not a full width: the platform's own push slides the outgoing screen a
 * short way and parallaxes it, and a screen that left at the same speed as the one arriving
 * reads as two screens passing rather than as one covering another.
 */
private const val SHIFT: Int = 4;
