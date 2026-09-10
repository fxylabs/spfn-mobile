// SPFN Mobile — the paged state machine, cell for cell.
//
// Counterpart of android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/PagedTest.kt,
// cell for cell. Every test below is named for a cell of the approved table (P1–P9) so a
// disagreement between the two platforms is one line on each side with the same name.
//
// The expectations were written from the rule and typed out, not printed from the
// implementation (docs/IMPLEMENTATION-PITFALLS.md P10). Rows are strings because what a row
// IS does not enter the arithmetic; what does is how many there are and which of the two
// states each transition leaves behind.
//
// No SwiftUI. `SPFNUI`'s pure half compiles and runs on the Linux host, and this suite is
// part of what makes that claim testable.

import SPFNCore
import XCTest
@testable import SPFNUI

private let envelope = SPFNErrorEnvelope(code: "CONFLICT", message: "server text", requestID: "req-1")
private let other = SPFNErrorEnvelope(code: "UNAVAILABLE", message: "server text", requestID: "req-2")

final class PagedTests: XCTestCase
{
    // P1 — loading → firstPage(3, next) → ready(3), hasMore, more idle.
    func testP1FirstPageWithACursorIsReadyAndHasMore()
    {
        let state = Paged<String>.loading.firstPage(["a", "b", "c"], next: "cursor-1")

        XCTAssertEqual(state.page, .ready(["a", "b", "c"]))
        XCTAssertTrue(state.hasMore)
        XCTAssertEqual(state.more, .idle)
        XCTAssertTrue(state.canLoadMore)
    }

    // P2 — loading → firstPage(0, nil) → empty, hasMore false.
    //
    // The second vector is the server bug the type refuses to carry: no rows AND a cursor.
    // There is nothing on screen for a further page to be appended to, so the cursor is
    // dropped rather than offered.
    func testP2AnEmptyFirstPageIsEmptyAndHasNoMore()
    {
        let state = Paged<String>.loading.firstPage([], next: nil)

        XCTAssertEqual(state.page, .empty)
        XCTAssertFalse(state.hasMore)
        XCTAssertEqual(state.more, .idle)
        XCTAssertFalse(state.canLoadMore)

        let withCursor = Paged<String>.loading.firstPage([], next: "cursor-1")

        XCTAssertEqual(withCursor.page, .empty)
        XCTAssertFalse(withCursor.hasMore)
        XCTAssertFalse(withCursor.canLoadMore)
    }

    // P3 — loading → firstPageFailed → error, more idle.
    func testP3AFailedFirstPageIsAnErrorWithNothingInFlight()
    {
        let state = Paged<String>.loading.firstPageFailed(envelope)

        XCTAssertEqual(state.page, .error(envelope))
        XCTAssertEqual(state.more, .idle)
        XCTAssertFalse(state.hasMore)
        XCTAssertFalse(state.canLoadMore)
    }

    // P4 — ready(3)+hasMore → appending → appended(2, nil) → ready(5), hasMore false, idle.
    func testP4AnAppendedLastPageLeavesFiveRowsAndNothingMore()
    {
        let asking = threeReadyWithMore.appending()

        XCTAssertEqual(asking.more, .busy)
        XCTAssertEqual(asking.page, .ready(["a", "b", "c"]))
        XCTAssertFalse(asking.canLoadMore)

        let state = asking.appended(["d", "e"], next: nil)

        XCTAssertEqual(state.page, .ready(["a", "b", "c", "d", "e"]))
        XCTAssertFalse(state.hasMore)
        XCTAssertEqual(state.more, .idle)
    }

    // P5 — ready(3)+hasMore → appending → appendFailed → ready(3) kept, more error.
    func testP5AFailedAppendKeepsTheRowsAndFailsOnlyTheFooter()
    {
        let state = threeReadyWithMore.appending().appendFailed(envelope)

        XCTAssertEqual(state.page, .ready(["a", "b", "c"]))
        XCTAssertEqual(state.more, .error(envelope))
        XCTAssertTrue(state.hasMore)
        XCTAssertTrue(state.canLoadMore)
    }

    // P6 — more=busy → appending → the same value.
    func testP6AppendingWhileAPageIsInFlightIsIgnored()
    {
        let inFlight = Paged(page: Loadable.ready(["a", "b", "c"]), more: .busy, hasMore: true)

        XCTAssertFalse(inFlight.canLoadMore)
        XCTAssertEqual(inFlight.appending(), inFlight)
    }

    // P7 — hasMore=false → appending → the same value, canLoadMore false.
    func testP7AppendingWithNothingMoreToReadIsIgnored()
    {
        let complete = Paged(page: Loadable.ready(["a", "b", "c"]), more: Busy.idle, hasMore: false)

        XCTAssertFalse(complete.canLoadMore)
        XCTAssertEqual(complete.appending(), complete)
    }

    // P8 — more=error → appending → appended → ready(5), more idle.
    //
    // The retry path: a failed append is exactly the state the footer's control is drawn in,
    // so asking again from it has to be a legal move.
    func testP8AppendingAfterAFailedAppendIsAllowedAndSucceeds()
    {
        let failed = threeReadyWithMore.appending().appendFailed(envelope)
        let asking = failed.appending()

        XCTAssertEqual(asking.more, .busy)

        let state = asking.appended(["d", "e"], next: nil)

        XCTAssertEqual(state.page, .ready(["a", "b", "c", "d", "e"]))
        XCTAssertEqual(state.more, .idle)
        XCTAssertFalse(state.hasMore)
    }

    // P9 — ready → Paged.loading → the initial value, written out here rather than read
    // back off the implementation.
    func testP9TheInitialValueIsLoadingIdleAndNoMore()
    {
        let ready = threeReadyWithMore

        XCTAssertEqual(
            Paged<String>.loading,
            Paged(page: Loadable<[String]>.loading, more: Busy.idle, hasMore: false)
        )
        XCTAssertNotEqual(Paged<String>.loading, ready)
        XCTAssertFalse(Paged<String>.loading.canLoadMore)
    }

    // The envelope is part of the value: two appends that failed differently are two states.
    func testTwoFailuresWithDifferentEnvelopesAreDifferentStates()
    {
        let one = threeReadyWithMore.appending().appendFailed(envelope)
        let two = threeReadyWithMore.appending().appendFailed(other)

        XCTAssertNotEqual(one, two)
    }

    /// Three rows read, a cursor for the next page, nothing in flight — the state four of
    /// the cells above start from.
    private var threeReadyWithMore: Paged<String>
    {
        Paged<String>.loading.firstPage(["a", "b", "c"], next: "cursor-1")
    }
}
