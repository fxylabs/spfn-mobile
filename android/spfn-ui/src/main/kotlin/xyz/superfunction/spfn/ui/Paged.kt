// SPFN Mobile — the state of one read that arrives a page at a time.
//
// Counterpart of Sources/SPFNUI/Paged.swift.
//
// A list that grows has two states at once and they are not the same state. The FIRST page
// is a read: it is in flight, or it produced rows, or it produced none, or it failed —
// which is exactly [Loadable]. Every page after it is a write-shaped thing: it is in flight
// or it is not, and if it failed the rows already on screen are still there — which is
// exactly [Busy]. A single sealed interface over both would have to name `ReadyButFailing`
// and `EmptyAndLoadingMore`, and a screen would forget one of them.
//
// So this type is the two vocabularies side by side plus the one fact neither carries:
// whether the server said there is anything after what has been read.
//
// ---------------------------------------------------------------------------
// The cursor is not here, on purpose
// ---------------------------------------------------------------------------
//
// [firstPage] and [appended] are told what the next cursor is and keep only whether there
// WAS one. The value itself belongs to the screen model, privately, next to the service it
// will hand it back to. This type holds what a screen shows, and a screen never shows a
// cursor: putting it here would make it part of the vocabulary two platforms have to spell
// the same way, part of what a test has to state, and part of what a view could
// accidentally draw — for a value whose only reader is the model that owns it.
//
// ---------------------------------------------------------------------------
// The transitions are pure, and one of them can refuse
// ---------------------------------------------------------------------------
//
// Every function below returns a new value and calls nothing. What runs the read is the
// generated screen model (2b); what this file owns is the ARITHMETIC of the state, so that
// both platforms can be held to the same table of cells by two suites that need no
// scheduler, no service and no view.
//
// [appending] returns `this` when there is nothing to append — no page yet, no more to
// fetch, or a fetch already in flight. That is the whole of the double-fire defence on the
// model's side: an infinite-scroll view fires from a row appearing, a row appears whenever
// the list is laid out again, and a view that fired twice would otherwise start two reads.
// The view asks [canLoadMore] first and this ignores it anyway, which is the belt and the
// braces — a state machine that can only be driven forwards by a legal move is cheaper to
// reason about than a view that promises never to make an illegal one.
//
// ---------------------------------------------------------------------------
// `out V`, and what it costs
// ---------------------------------------------------------------------------
//
// The variance is what lets [Companion.loading] be ONE value — a `Paged<Nothing>` a model
// of any row type can start at — exactly as `Loadable.Loading` is. Its price is that a
// member function cannot take a `List<V>` as a parameter, and two of the transitions do.
// `@UnsafeVariance` is how the standard library says the same thing (`List<out E>` declares
// `contains(element: @UnsafeVariance E)`), and it is sound here for the reason it is sound
// there: these functions read the list and return a NEW value, so nothing of the caller's
// type is ever written into a receiver that had a narrower one.

package xyz.superfunction.spfn.ui

import xyz.superfunction.spfn.core.SpfnErrorEnvelope

/**
 * What one paged read can be: the first page, whether a further page is in flight, and
 * whether there is one to ask for.
 *
 * @param page the first page's read. [Loadable.Ready] carries every row read so far,
 *   appended pages included, so a view draws this and nothing else.
 * @param more whether a FURTHER page is in flight, and whether the last attempt at one
 *   failed. Never the first page's state — that is [page].
 * @param hasMore whether the server said there is anything after what has been read.
 */
public data class Paged<out V>(
    public val page: Loadable<List<V>>,
    public val more: Busy,
    public val hasMore: Boolean
)
{
    /**
     * The rows read so far, or null while the first page is anything but [Loadable.Ready].
     *
     * Private: "is there a list yet" is what [canLoadMore] answers and what the two
     * appending transitions need, and a public accessor would be a second way to ask [page]
     * the question it already answers.
     */
    private val loaded: List<V>?
        get() = if (page is Loadable.Ready) page.value else null;

    /**
     * Whether a view may ask for another page: there are rows, the server said there are
     * more, and no request for them is in flight.
     *
     * A failed append is NOT excluded — `more == Busy.Error` is the state the footer's retry
     * button is drawn in, and the whole point of that button is to ask again.
     */
    public val canLoadMore: Boolean
        get() = loaded != null && hasMore && more != Busy.Busy;

    /**
     * The first page arrived.
     *
     * No rows is [Loadable.Empty] rather than `Ready(emptyList())`, for the reason [Loadable]
     * splits the two at all — and an empty first page has NO more, whatever cursor came with
     * it. A server that answers "no rows, and here is where to continue" is answering two
     * things that cannot both be true, and the honest reading of it is the rows: there is
     * nothing on screen for a further page to be appended to, and a footer offering one would
     * be the only thing a person could see.
     */
    public fun firstPage(items: List<@UnsafeVariance V>, next: String?): Paged<V> = Paged(
        page = if (items.isEmpty()) Loadable.Empty else Loadable.Ready(items),
        more = Busy.Idle,
        hasMore = items.isNotEmpty() && next != null
    );

    /** The first page failed. Nothing is on screen, so nothing can be appended to it. */
    public fun firstPageFailed(error: SpfnErrorEnvelope): Paged<V> =
        Paged(page = Loadable.Error(error), more = Busy.Idle, hasMore = false);

    /**
     * A further page was asked for — or the ask was ignored.
     *
     * Returning `this` is the ignore, and it is a value rather than a flag on purpose: the
     * caller assigns the result unconditionally and an illegal move leaves the screen exactly
     * as it was.
     */
    public fun appending(): Paged<V> =
        if (canLoadMore) Paged(page = page, more = Busy.Busy, hasMore = hasMore) else this;

    /** A further page arrived and is appended to the rows already read. */
    public fun appended(items: List<@UnsafeVariance V>, next: String?): Paged<V> = Paged(
        page = Loadable.Ready((loaded ?: emptyList()) + items),
        more = Busy.Idle,
        hasMore = next != null
    );

    /**
     * A further page failed. The rows already read stay exactly where they are; only the
     * footer changes, which is what makes this a [Busy] and not a second [Loadable].
     */
    public fun appendFailed(error: SpfnErrorEnvelope): Paged<V> =
        Paged(page = page, more = Busy.Error(error), hasMore = hasMore);

    public companion object
    {
        /**
         * Nothing has been read yet: the value a screen model starts at.
         *
         * `Paged<Nothing>`, so one value serves every row type — the variance on the class
         * is what makes that legal, and this is what the variance is for.
         *
         * It is assigned to a DECLARED `Paged<Row>` rather than transitioned from directly:
         * `firstPage` on a `Paged<Nothing>` would take a `List<Nothing>`, and the model's
         * own property type is what says which rows this page is of.
         */
        public val loading: Paged<Nothing> =
            Paged(page = Loadable.Loading, more = Busy.Idle, hasMore = false);
    }
}
