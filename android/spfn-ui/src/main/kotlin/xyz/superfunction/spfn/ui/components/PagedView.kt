// SPFN Mobile — one paged read's states, drawn, and the scroll that asks for the next page.
//
// Counterpart of Sources/SPFNUI/Components/PagedView.swift.
//
// The same shape as `LoadableView` because the first page IS a [Loadable]: this composable
// calls that one for `Loading`, `Empty` and `Error` rather than restating them, and adds the
// two things a growing list has that a single read does not — the rows, and a footer for the
// page after the first one.
//
// ---------------------------------------------------------------------------
// Who owns the scroll (rule S2)
// ---------------------------------------------------------------------------
//
// This composable does, and that is the opposite of the Swift half's answer. `LazyColumn` is
// the only lazy list this toolkit has and it brings its own scroll; nesting one inside
// `Screen(scroll = true)`'s `verticalScroll` is not merely two scrollers but a measurement
// with no answer — the outer one offers infinite height and the inner one asks for all of
// it. So a screen that draws a `PagedView` declares `Screen(scroll = false)`, and this list
// is the body's scroll.
//
// The rule S2 states — a fixed header over a moving body, and a way out that never scrolls
// away — holds either way: the header is outside the body on both platforms. Which object
// provides the movement is what differs, and it differs because the two toolkits do.
//
// ---------------------------------------------------------------------------
// How the end is detected, and why it cannot fire twice
// ---------------------------------------------------------------------------
//
// The index of the last item the layout reports as visible, read through `derivedStateOf` so
// that a scroll which does not change that index recomposes nothing, and acted on in a
// `LaunchedEffect` keyed by it so the ask happens once per change rather than once per frame.
// Three things stand between that and two requests:
//
//   1. this composable asks [Paged.canLoadMore] first, which is false while a page is in
//      flight;
//   2. the model's [Paged.appending] returns the same value when it is not a legal move, so
//      a second call changes nothing;
//   3. the state that comes back from (2) is what the next composition reads.
//
// (1) alone would be a view promising to behave. (2) is what makes the promise unnecessary.
//
// The footer is an item of the same list rather than something pinned under it: pinned
// chrome over a list is a second thing competing for the bottom inset, and a footer that
// scrolls with the rows is where a person's eye already is when they reach the end.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Paged
import xyz.superfunction.spfn.ui.SpfnStrings
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * Draws a paged read: the first page's four states, the rows, and a footer for the page after
 * them.
 *
 * @param state the paged read's state.
 * @param retryId the test tag of the control the FIRST page's error slot draws.
 * @param moreRetryId the test tag of the control the FOOTER's error slot draws. A second tag
 *   and not the same one: a runner that pressed "try again" has to be able to say which of
 *   the two it meant, and both can be on screen at once.
 * @param onRetry what the first page's control does. Null draws no control, exactly as in
 *   `LoadableView`.
 * @param onLoadMore asked for the next page — by the end of the list coming into view, and by
 *   the footer's own control after a failed append.
 * @param message how an envelope becomes a sentence, for both slots. The default is the least
 *   this SDK can honestly say; the generated scaffold passes its own `ScreenFailure.message`.
 * @param row the only slot a caller has to write.
 */
@JvmSynthetic
@Composable
public fun <V> PagedView(
    state: Paged<V>,
    modifier: Modifier = Modifier,
    retryId: String = "",
    moreRetryId: String = "",
    onRetry: (() -> Unit)? = null,
    onLoadMore: () -> Unit = {},
    message: (SpfnErrorEnvelope) -> String = { SpfnStrings.errorUnexpected },
    row: @Composable (V) -> Unit
)
{
    LoadableView(
        state = state.page,
        modifier = modifier,
        retryId = retryId,
        onRetry = onRetry,
        message = message
    )
    { rows ->
        val listState = rememberLazyListState();
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth())
        {
            items(rows.size) { index -> row(rows[index]); };
            item { PagedFooter(more = state.more, moreRetryId = moreRetryId, message = message, onLoadMore = onLoadMore); };
        }
        // `visibleItemsInfo` changes on every scrolled pixel and its LAST INDEX does not, so
        // the derived state is what keeps this to one recomposition per row crossed rather
        // than one per frame.
        val lastVisible = remember(listState) {
            derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 };
        };
        LaunchedEffect(lastVisible.value, state.canLoadMore)
        {
            if (state.canLoadMore && lastVisible.value >= rows.size - 1)
            {
                onLoadMore();
            }
        }
    }
}

/**
 * The bottom line: in flight, failed, or nothing at all.
 *
 * `Idle` draws nothing whether or not there is more to read — a list that has more is a list
 * a person reaches the end of and it loads, and a line saying so before it happens is chrome
 * nobody asked for. What a RUNNER reads is a readout the screen writes, never this composable
 * (E10).
 */
@Composable
private fun PagedFooter(
    more: Busy,
    moreRetryId: String,
    message: (SpfnErrorEnvelope) -> String,
    onLoadMore: () -> Unit
)
{
    when (more)
    {
        is Busy.Idle -> Unit
        is Busy.Busy -> LoadingLine(modifier = Modifier.padding(top = SpfnTokens.space3))
        is Busy.Error -> FailureLine(
            text = message(more.error),
            retryId = moreRetryId,
            onRetry = onLoadMore,
            modifier = Modifier.padding(top = SpfnTokens.space3)
        )
    }
}
