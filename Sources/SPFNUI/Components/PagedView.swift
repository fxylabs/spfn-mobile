#if canImport(SwiftUI)
// SPFN Mobile — one paged read's states, drawn, and the scroll that asks for the next page.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/PagedView.kt.
//
// The same shape as ``LoadableView`` because the first page IS a ``Loadable``: this view
// calls that one for `loading`, `empty` and `error` rather than restating them, and adds
// the two things a growing list has that a single read does not — the rows, and a footer
// for the page after the first one.
//
// ---------------------------------------------------------------------------
// Who owns the scroll (rule S2)
// ---------------------------------------------------------------------------
//
// The ``Screen`` does, and this view does not take it away. What is drawn here is a bare
// `LazyVStack` — no `List`, no `ScrollView` of its own — because both of those bring a
// scroll view with them and a screen already has one: `Screen(scroll: true)` is what makes
// the header stay still while the body moves under it, and two nested scrollers is the
// defect that rule exists to prevent. A `List` would also bring separators, insets and a
// background that are its own rather than the SDK's tokens.
//
// The Compose half cannot make the same choice and says so in its own header: `LazyColumn`
// is the only lazy list that platform has and it owns its scroll, so a screen hosting one
// declares `Screen(scroll = false)`. The rule S2 states — a fixed header over a moving body
// — holds on both; which object provides the movement is what differs, and it differs
// because the two toolkits differ.
//
// ---------------------------------------------------------------------------
// How the end is detected, and why it cannot fire twice
// ---------------------------------------------------------------------------
//
// The last row's `onAppear`. It is not a promise that it fires once: SwiftUI calls it
// whenever that row is laid out again, and a list that is scrolled up and back down calls
// it again on purpose. Three things stand between that and two requests:
//
//   1. this view asks ``Paged/canLoadMore`` first, which is false while a page is in
//      flight;
//   2. the model's ``Paged/appending()`` returns the same value when it is not a legal
//      move, so a second call changes nothing;
//   3. the state that comes back from (2) is what the next layout reads.
//
// (1) alone would be a view promising to behave. (2) is what makes the promise unnecessary.
//
// The footer is a row of the same stack rather than something pinned under it: pinned
// chrome over a list is a second thing competing for the bottom inset, and a footer that
// scrolls with the rows is where a person's eye already is when they reach the end.
//
// Guarded whole, first line to last (docs/IMPLEMENTATION-PITFALLS.md P20).

import SPFNCore
import SwiftUI

/// Draws a paged read: the first page's four states, the rows, and a footer for the page
/// after them.
public struct PagedView<Item: Sendable, Row: View>: View
{
    private let state: Paged<Item>
    private let retryIdentifier: String
    private let moreRetryIdentifier: String
    private let onRetry: (() -> Void)?
    private let onLoadMore: () -> Void
    private let message: (SPFNErrorEnvelope) -> String
    private let row: (Item) -> Row

    /// - Parameters:
    ///   - state: the paged read's state.
    ///   - retryIdentifier: the accessibility id of the control the FIRST page's error slot
    ///     draws.
    ///   - moreRetryIdentifier: the accessibility id of the control the FOOTER's error slot
    ///     draws. A second id and not the same one: a runner that pressed "try again" has to
    ///     be able to say which of the two it meant, and both can be on screen at once.
    ///   - onRetry: what the first page's control does. Nil draws no control, exactly as in
    ///     ``LoadableView``.
    ///   - onLoadMore: asked for the next page — by the last row appearing, and by the
    ///     footer's own control after a failed append.
    ///   - message: how an envelope becomes a sentence, for both slots. The default is the
    ///     least this SDK can honestly say; the generated scaffold passes its own
    ///     `ScreenFailure.message`.
    ///   - row: the only slot a caller has to write.
    public init(
        _ state: Paged<Item>,
        retryIdentifier: String = "",
        moreRetryIdentifier: String = "",
        onRetry: (() -> Void)? = nil,
        onLoadMore: @escaping () -> Void = {},
        message: @escaping (SPFNErrorEnvelope) -> String = { _ in SPFNStrings.errorUnexpected },
        @ViewBuilder row: @escaping (Item) -> Row
    )
    {
        self.state = state
        self.retryIdentifier = retryIdentifier
        self.moreRetryIdentifier = moreRetryIdentifier
        self.onRetry = onRetry
        self.onLoadMore = onLoadMore
        self.message = message
        self.row = row
    }

    public var body: some View
    {
        LoadableView(
            state.page,
            retryIdentifier: retryIdentifier,
            onRetry: onRetry,
            message: message
        )
        { items in
            rows(items)
        }
    }

    /// The rows and the footer, in the one lazy stack the screen's scroll view holds.
    ///
    /// The identity is the row's POSITION, which is stable here in a way it is not in
    /// general: a page is only ever appended to the end, so a row that has been drawn keeps
    /// the offset it was drawn at for as long as the list lives. An `Item` this SDK could
    /// ask for an id from would be an `Identifiable` constraint on every row type a spec can
    /// produce, and the contract's rows are the generated response types.
    private func rows(_ items: [Item]) -> some View
    {
        LazyVStack(alignment: .leading, spacing: SPFNTokens.space3)
        {
            ForEach(Array(items.enumerated()), id: \.offset)
            { entry in
                row(entry.element)
                    .onAppear
                    {
                        loadMoreIfLast(entry.offset, of: items.count)
                    }
            }
            footer
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The bottom line: in flight, failed, or nothing at all.
    ///
    /// `idle` draws nothing whether or not there is more to read — a list that has more is
    /// a list a person reaches the end of and it loads, and a line saying so before it
    /// happens is chrome nobody asked for. What a RUNNER reads is a readout the screen
    /// writes, never this view (E10).
    @ViewBuilder
    private var footer: some View
    {
        switch state.more
        {
        case .idle:
            EmptyView()
        case .busy:
            LoadingLine()
        case .error(let envelope):
            FailureLine(
                text: message(envelope),
                retryIdentifier: moreRetryIdentifier,
                onRetry: onLoadMore
            )
        }
    }

    /// Asks for the next page when the row that just appeared is the last one, and only
    /// when asking is a legal move. See this file's header for the other two guards.
    private func loadMoreIfLast(_ offset: Int, of count: Int)
    {
        guard offset >= count - 1, state.canLoadMore else { return }
        onLoadMore()
    }
}
#endif
