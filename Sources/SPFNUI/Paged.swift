// SPFN Mobile — the state of one read that arrives a page at a time.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Paged.kt.
//
// A list that grows has two states at once and they are not the same state. The FIRST page
// is a read: it is in flight, or it produced rows, or it produced none, or it failed —
// which is exactly ``Loadable``. Every page after it is a write-shaped thing: it is in
// flight or it is not, and if it failed the rows already on screen are still there —
// which is exactly ``Busy``. A single enum over both would have to name `readyButFailing`
// and `emptyAndLoadingMore`, and a screen would forget one of them.
//
// So this type is the two vocabularies side by side plus the one fact neither carries:
// whether the server said there is anything after what has been read.
//
// ---------------------------------------------------------------------------
// The cursor is not here, on purpose
// ---------------------------------------------------------------------------
//
// `firstPage` and `appended` are told what the next cursor is and keep only whether there
// WAS one. The value itself belongs to the screen model, privately, next to the service it
// will hand it back to. This type holds what a screen shows, and a screen never shows a
// cursor: putting it here would make it part of the vocabulary two platforms have to spell
// the same way, part of what a test has to state, and part of what a view could
// accidentally draw — for a value whose only reader is the model that owns it.
//
// ---------------------------------------------------------------------------
// The transitions are pure, and two of them can refuse
// ---------------------------------------------------------------------------
//
// Every function below returns a new value and calls nothing. What runs the read is the
// generated screen model (2b); what this file owns is the ARITHMETIC of the state, so that
// both platforms can be held to the same table of cells by two suites that need no
// scheduler, no service and no view.
//
// `appending()` returns `self` when there is nothing to append — no page yet, no more to
// fetch, or a fetch already in flight. That is the whole of the double-fire defence on the
// model's side: an infinite-scroll view fires from a row appearing, a row appears whenever
// the list is laid out again, and a view that fired twice would otherwise start two reads.
// The view asks ``canLoadMore`` first and this ignores it anyway, which is the belt and
// the braces — a state machine that can only be driven forwards by a legal move is cheaper
// to reason about than a view that promises never to make an illegal one.

import SPFNCore

/// What one paged read can be: the first page, whether a further page is in flight, and
/// whether there is one to ask for.
public struct Paged<Item: Sendable>: Sendable
{
    /// The first page's read. `ready` carries every row read so far, appended pages
    /// included, so a view draws this and nothing else.
    public let page: Loadable<[Item]>

    /// Whether a FURTHER page is in flight, and whether the last attempt at one failed.
    /// Never the first page's state — that is ``page``.
    public let more: Busy

    /// Whether the server said there is anything after what has been read.
    public let hasMore: Bool

    public init(page: Loadable<[Item]>, more: Busy, hasMore: Bool)
    {
        self.page = page
        self.more = more
        self.hasMore = hasMore
    }

    /// Nothing has been read yet: the value a screen model starts at.
    ///
    /// Computed rather than stored, because Swift has no stored static in a generic type.
    /// Its Kotlin twin is a stored `Paged<Nothing>`, which is the same value said in the
    /// language that can say it.
    public static var loading: Paged<Item>
    {
        Paged(page: .loading, more: .idle, hasMore: false)
    }

    /// The rows read so far, or nil while the first page is anything but `ready`.
    ///
    /// Private: "is there a list yet" is what ``canLoadMore`` answers and what the two
    /// appending transitions need, and a public accessor would be a second way to ask
    /// ``page`` the question it already answers.
    private var loaded: [Item]?
    {
        if case .ready(let rows) = page
        {
            return rows
        }
        return nil
    }

    /// Whether a view may ask for another page: there are rows, the server said there are
    /// more, and no request for them is in flight.
    ///
    /// A failed append is NOT excluded — `more == .error` is the state the footer's retry
    /// button is drawn in, and the whole point of that button is to ask again.
    public var canLoadMore: Bool
    {
        loaded != nil && hasMore && more != .busy
    }

    /// The first page arrived.
    ///
    /// Empty rows are `empty` rather than `ready([])`, for the reason ``Loadable`` splits
    /// the two at all — and an empty first page has NO more, whatever cursor came with it.
    /// A server that answers "no rows, and here is where to continue" is answering two
    /// things that cannot both be true, and the honest reading of it is the rows: there is
    /// nothing on screen for a further page to be appended to, and a footer offering one
    /// would be the only thing a person could see.
    public func firstPage(_ items: [Item], next: String?) -> Paged<Item>
    {
        Paged(
            page: items.isEmpty ? .empty : .ready(items),
            more: .idle,
            hasMore: !items.isEmpty && next != nil
        )
    }

    /// The first page failed. Nothing is on screen, so nothing can be appended to it.
    public func firstPageFailed(_ envelope: SPFNErrorEnvelope) -> Paged<Item>
    {
        Paged(page: .error(envelope), more: .idle, hasMore: false)
    }

    /// A further page was asked for — or the ask was ignored.
    ///
    /// Returning `self` is the ignore, and it is a value rather than a flag on purpose:
    /// the caller assigns the result unconditionally and an illegal move leaves the screen
    /// exactly as it was.
    public func appending() -> Paged<Item>
    {
        guard canLoadMore else { return self }
        return Paged(page: page, more: .busy, hasMore: hasMore)
    }

    /// A further page arrived and is appended to the rows already read.
    public func appended(_ items: [Item], next: String?) -> Paged<Item>
    {
        Paged(page: .ready((loaded ?? []) + items), more: .idle, hasMore: next != nil)
    }

    /// A further page failed. The rows already read stay exactly where they are; only the
    /// footer changes, which is what makes this ``Busy`` and not a second ``Loadable``.
    public func appendFailed(_ envelope: SPFNErrorEnvelope) -> Paged<Item>
    {
        Paged(page: page, more: .error(envelope), hasMore: hasMore)
    }
}

// Conditional for the reason ``Loadable``'s is: an `Item` that is not comparable makes the
// whole value incomparable, and an unconditional `==` here would be one this type cannot
// honestly write.
extension Paged: Equatable where Item: Equatable {}
