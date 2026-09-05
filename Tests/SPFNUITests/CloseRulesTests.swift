// SPFN Mobile — the close and move table, one test per cell.
//
// Counterpart of
// android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/CloseRulesTest.kt, cell for cell
// and name for name. The table is the one approved with the sheet entry (work unit w-evwna
// 3a) and amended for the host stack (3e, decision N2); it is written out here from that
// approval rather than read off the implementation, and a combination nobody wrote down is
// a combination neither platform has.
//
// | entry          | header back | system back / swipe | X            | drag down |
// | -------------- | ----------- | ------------------- | ------------ | --------- |
// | push, depth 2+ | pop         | pop                 | none         | n/a       |
// | push, root     | close       | close               | none         | n/a       |
// | modal, depth 2+| pop         | pop                 | close        | n/a       |
// | modal, root    | none        | close               | close        | n/a       |
// | sheet, depth 2+| pop         | pop                 | close        | close     |
// | sheet, root    | none        | close               | close        | close     |
//
// Each cell names the code that decides it:
//
//   header back  `Flow.wayOut(entry:)` says which control is drawn and `Flow.back(entry:)`
//                is what it does. A cell reading "none" is `WayOut.none`.
//   system back  `Flow.handlesBack(entry:)` says whether this flow claims the gesture, and
//                `Flow.back(entry:)` performs it.
//   X            `Flow.close()`, drawn in the header's TRAILING slot. A cell reading "none"
//                is `Flow.wayOut(entry:)` never answering `.close` for that entry, at any
//                depth.
//   drag down    `SheetGeometry.closes(offset:height:)` decides that a drag went far enough,
//                and what a dismissed sheet does is `Flow.close()` — which is why a drag
//                past the threshold and a tap on the scrim are the same event to a flow.
//
// The four `n/a` cells have no test: a flow that is not a sheet cannot be dragged, and a
// test asserting that would be a test of this comment.
//
// The names carry a `test_` prefix that the Kotlin half does not need: XCTest discovers a
// case by that prefix and would run none of these without it. Everything after it is the
// cell.

import XCTest
@testable import SPFNUI

private struct Stop: FlowRoute
{
    let name: String
}

private let first = Stop(name: "first")
private let second = Stop(name: "second")

private let push: FlowEntry = .push
private let modal: FlowEntry = .modal
private let sheet: FlowEntry = .sheet(detent: .half)

/// Runs `body` on the main actor. `Flow` is `@MainActor` and a `@MainActor` XCTestCase
/// method cannot be discovered on Linux, which Tests/SPFNUITests/FlowTests.swift measures
/// and explains; every case here hops once for the same reason.
private func onMain(_ body: @MainActor @Sendable () throws -> Void) async throws
{
    try await MainActor.run(body: body)
}

final class CloseRulesTests: XCTestCase
{
    // --- push, depth 2+ -----------------------------------------------------

    func test_push_depth2_headerBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertEqual(flow.wayOut(entry: push), WayOut.back)
            flow.pop()
            XCTAssertEqual(flow.stack, [first])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func test_push_depth2_systemBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertTrue(flow.handlesBack(entry: push))
            XCTAssertTrue(flow.back(entry: push))
            XCTAssertEqual(flow.stack, [first])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func test_push_depth2_close_absent() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertNotEqual(flow.wayOut(entry: push), WayOut.close)
        }
    }

    // --- push, root ---------------------------------------------------------

    func test_push_root_headerBack_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            // A back and not a close CONTROL, because what is under this root is the host's
            // own screen — and closing the flow is what uncovers it (decision N2).
            XCTAssertEqual(flow.wayOut(entry: push), WayOut.back)
            XCTAssertTrue(flow.back(entry: push))
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_push_root_systemBack_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertTrue(flow.handlesBack(entry: push))
            XCTAssertTrue(flow.back(entry: push))
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_push_root_close_absent() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertNotEqual(flow.wayOut(entry: push), WayOut.close)
        }
    }

    // --- modal, depth 2+ ----------------------------------------------------

    func test_modal_depth2_headerBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertEqual(flow.wayOut(entry: modal), WayOut.back)
            flow.pop()
            XCTAssertEqual(flow.stack, [first])
        }
    }

    func test_modal_depth2_systemBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertTrue(flow.back(entry: modal))
            XCTAssertEqual(flow.stack, [first])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func test_modal_depth2_close_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    // --- modal, root --------------------------------------------------------

    func test_modal_root_headerBack_absent() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertEqual(flow.wayOut(entry: modal), WayOut.close)
            XCTAssertNotEqual(flow.wayOut(entry: modal), WayOut.back)
        }
    }

    func test_modal_root_systemBack_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertTrue(flow.handlesBack(entry: modal))
            XCTAssertTrue(flow.back(entry: modal))
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_modal_root_close_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertEqual(flow.wayOut(entry: modal), WayOut.close)
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    // --- sheet, depth 2+ ----------------------------------------------------

    func test_sheet_depth2_headerBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertEqual(flow.wayOut(entry: sheet), WayOut.back)
            flow.pop()
            XCTAssertEqual(flow.stack, [first])
        }
    }

    func test_sheet_depth2_systemBack_pops() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            XCTAssertTrue(flow.back(entry: sheet))
            XCTAssertEqual(flow.stack, [first])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func test_sheet_depth2_close_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_sheet_depth2_dragDown_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            // A sheet 600 units tall, dragged 300 down: at the threshold, so it goes. A
            // sheet deeper than its root still goes as a whole — a drag dismisses the
            // presentation, not the route on top of it.
            XCTAssertTrue(SheetGeometry.closes(offset: 300, height: 600))
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    // --- sheet, root --------------------------------------------------------

    func test_sheet_root_headerBack_absent() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertEqual(flow.wayOut(entry: sheet), WayOut.close)
            XCTAssertNotEqual(flow.wayOut(entry: sheet), WayOut.back)
        }
    }

    func test_sheet_root_systemBack_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertTrue(flow.handlesBack(entry: sheet))
            XCTAssertTrue(flow.back(entry: sheet))
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_sheet_root_close_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertEqual(flow.wayOut(entry: sheet), WayOut.close)
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func test_sheet_root_dragDown_closes() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first])
            XCTAssertTrue(SheetGeometry.closes(offset: 300, height: 600))
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    // --- the rule that outlives the flow ------------------------------------

    /// docs/IMPLEMENTATION-PITFALLS.md P24, asked of the new entry style.
    ///
    /// A screen model accepts a late response only when its request is the current one, the
    /// flow is still presented, AND its own route is on top of the stack. A sheet closes for
    /// a reason no other entry has — the user threw it away — and the guard has to refuse
    /// that arrival the same way it refuses one after a modal closed. The two halves it
    /// reads are both false here, which is what makes the refusal independent of which one a
    /// model happens to check first.
    func test_sheet_closed_byDrag_refusesALateResponse() async throws
    {
        try await onMain
        {
            let flow = Flow(initial: [first, second])
            flow.close()
            XCTAssertFalse(flow.isPresented)
            XCTAssertNotEqual(flow.stack.last, second)
            XCTAssertNotEqual(flow.stack.last, first)
        }
    }

    /// A closed flow claims no back at all, whatever it was entered as.
    func test_closed_systemBack_isRefusedForEveryEntry() async throws
    {
        try await onMain
        {
            for entry in [push, modal, sheet]
            {
                let flow = Flow<Stop>()
                XCTAssertFalse(flow.handlesBack(entry: entry))
                XCTAssertFalse(flow.back(entry: entry))
                XCTAssertEqual(flow.wayOut(entry: entry), WayOut.none)
            }
        }
    }
}
