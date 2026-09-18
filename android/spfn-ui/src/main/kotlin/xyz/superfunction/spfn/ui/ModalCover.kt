// SPFN Mobile — the surface a modal flow is drawn on, and the colour it takes.
//
// There is no counterpart file on iOS, and that is the point: a modal flow there is a
// `fullScreenCover`, which the system draws, hit-tests and colours out of the presentation
// it already owns. Android has no such presentation that keeps one window — a `Dialog` or a
// `Popup` is a second semantics owner and would cost every control in the flow its resource
// id (`FlowHost.kt`'s header says why) — so the cover is a modifier this module writes.
//
// It was inside `FlowHost.kt` until now. What is here is not navigation: it is a fill, a
// hit-test node and a theme attribute read off the host activity, and none of the three has
// an opinion about a stack. `ModalCover` itself stays with `SheetCover` in `FlowHost.kt`,
// because those two ARE navigation — each is a flow's stack inside an `AnimatedVisibility`
// that outlives the stack emptying — and splitting one of the pair away from the other would
// hide that they are the same shape.

package xyz.superfunction.spfn.ui

import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * What makes a modal flow a cover: the whole parent, opaque, and a hit-test node.
 *
 * All three are load-bearing. A cover that does not FILL leaves the host visible beside
 * it; one that is not OPAQUE leaves the host legible through it; and one that holds no
 * POINTER INPUT NODE is a picture of a cover — Compose hit-tests siblings back to front
 * and stops at the topmost one that holds such a node, so without this modifier an
 * unclaimed tap inside the cover would reach the host's own controls underneath it.
 *
 * Existing is the whole job. This loop reads every event and consumes NOTHING, and that
 * emptiness is the fix rather than an oversight: a node that never claims a change still
 * wins the hit test, because hit testing asks which node is THERE and not what it did
 * with what it got.
 *
 * The version that consumed every change on the Main pass cancelled a real finger's press
 * on the controls inside the flow. The reasoning it carried — children see Main first, so
 * a tap a control claimed is already consumed by the time this sees it — is true of the
 * DOWN and false of every event after it. A press is not decided on the down: `clickable`
 * keeps it open and re-reads it on the FINAL pass, which runs the other way, parent before
 * child (androidx.compose.foundation 1.11.4's ClickableNode.onPointerEvent, checked with
 * javap: `pass == Main` handles down and up, `pass == Final` calls `checkForCancellation`,
 * which cancels the press when any change other than its own down reports `isConsumed`).
 * A parent that consumed on Main is precisely what that Final check reads as a cancel.
 *
 * A finger always produces MOVE events — a few pixels of tremor is a MOVE — so nothing
 * inside a modal flow could be tapped by hand, while `adb shell input tap` and Maestro,
 * which synthesise a DOWN and an UP and no MOVE between them, drove the same screen green
 * (docs/IMPLEMENTATION-PITFALLS.md P36).
 */
@Composable
internal fun cover(): Modifier = Modifier
    .fillMaxSize()
    .background(windowBackground())
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true)
            {
                awaitPointerEvent();
            }
        }
    }

/**
 * The colour a cover fills with: the host activity's own window background.
 *
 * A module that carries no theme has no business choosing white. `colorBackground` is the
 * one colour attribute every Android theme defines, it is what the host's own window is
 * already painted with, and it follows the host into dark mode without this module
 * knowing that dark mode exists.
 *
 * A theme that resolves it to something that is not a colour gets black. Black is wrong
 * to look at and impossible to miss; the alternative, a transparent cover, is the exact
 * defect this exists to prevent and would look like nothing at all.
 */
@Composable
private fun windowBackground(): Color
{
    val context = LocalContext.current;
    return remember(context) {
        val value = TypedValue();
        val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, value, true);
        val isColor = value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT;
        if (resolved && isColor) Color(value.data) else Color.Black
    };
}
