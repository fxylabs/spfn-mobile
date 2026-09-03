// SPFN Mobile — the flow transition table.
//
// Counterpart of android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/FlowTest.kt,
// case for case. The expected values are the table approved with the module (work unit
// w-w823n), written out here from that table rather than read off this implementation:
// every row states a start state, one operation and the result, and a row nobody wrote down
// is a row neither platform has.
//
// | start      | op         | result                |
// | closed []  | open(a,b)  | [a,b], presented      |
// | closed []  | open([])   | refused               |
// | closed []  | push(a)    | [a], presented        |
// | [a]        | push(b)    | [a,b]                 |
// | [a,b]      | pop()      | [a]                   |
// | [a]        | pop()      | [a] (no-op)           |
// | [a,b]      | replace(c) | [a,c]                 |
// | [a,b]      | close()    | [], not presented     |
// | [] closed  | close()    | no-op, no event       |
//
// No `import SwiftUI` here, and none in what it drives: `Flow` is a plain class, so this
// suite runs on every platform the package builds on rather than only where a UI toolkit
// exists.

import SPFNCore
import XCTest
@testable import SPFNUI

/// A route with a payload, so two routes of the same kind are still two routes.
private struct Step: FlowRoute
{
    let name: String
}

private let a = Step(name: "a")
private let b = Step(name: "b")
private let c = Step(name: "c")

/// Runs `body` on the main actor.
///
/// `Flow` and `FlowHost` are `@MainActor`, so a suite that drives them has to be too —
/// except that a `@MainActor` XCTestCase method cannot be DISCOVERED on Linux.
/// swift-corelibs-xctest builds its list by casting each method to a plain
/// `(Self) -> () throws -> ()`, an isolated method's type is not that, and the cast aborts
/// the whole binary before the first test runs. Measured on this host with Swift 6.2.1:
/// `Could not cast value of type '(SPFNUITests.FlowTests) -> @Swift.MainActor () throws ->
/// ()'`. So the suites stay non-isolated and every case hops once, which is also what the
/// Kotlin half does implicitly by having no isolation to declare.
private func onMainActor(_ body: @MainActor @Sendable () throws -> Void) async throws
{
    try await MainActor.run(body: body)
}

final class FlowTests: XCTestCase
{
    func testANewFlowIsClosedAndEmpty() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func testOpenOnAClosedFlowPresentsTheWholeStack() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            try flow.open(at: [a, b])
            XCTAssertEqual(flow.stack, [a, b])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testOpenRefusesAnEmptyStackAndChangesNothing() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            XCTAssertThrowsError(try flow.open(at: []))
            { error in
                XCTAssertEqual(error as? SPFNUIError, .emptyStack)
            }
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func testPushOnAClosedFlowOpensItOnThatRoute() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            flow.push(a)
            XCTAssertEqual(flow.stack, [a])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testPushOnAnOpenFlowAddsARouteOnTop() async throws
    {
        try await onMainActor
        {
            let flow = Flow(initial: [a])
            flow.push(b)
            XCTAssertEqual(flow.stack, [a, b])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testPopDropsTheTopRoute() async throws
    {
        try await onMainActor
        {
            let flow = Flow(initial: [a, b])
            flow.pop()
            XCTAssertEqual(flow.stack, [a])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testPopOnTheLastRouteIsANoOpAndNeverClosesTheFlow() async throws
    {
        try await onMainActor
        {
            let flow = Flow(initial: [a])
            flow.pop()
            XCTAssertEqual(flow.stack, [a])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testReplaceSwapsTheTopRouteAndLeavesEverythingUnderIt() async throws
    {
        try await onMainActor
        {
            let flow = Flow(initial: [a, b])
            flow.replace(c)
            XCTAssertEqual(flow.stack, [a, c])
            XCTAssertTrue(flow.isPresented)
        }
    }

    func testReplaceOnAClosedFlowIsANoOp() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            flow.replace(c)
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func testCloseEmptiesTheStackAndStopsPresenting() async throws
    {
        try await onMainActor
        {
            let flow = Flow(initial: [a, b])
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    func testCloseOnAClosedFlowChangesNothing() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            flow.close()
            XCTAssertEqual(flow.stack, [])
            XCTAssertFalse(flow.isPresented)
        }
    }

    /// The invariant both halves of the table rest on, checked after every operation rather
    /// than argued for once: a flow is presented exactly when it stands on something.
    func testIsPresentedIsExactlyANonEmptyStackAcrossTheWholeTable() async throws
    {
        try await onMainActor
        {
            let flow = Flow<Step>()
            XCTAssertEqual(flow.isPresented, !flow.stack.isEmpty)

            let operations: [() throws -> Void] = [
                { flow.push(a) },
                { flow.push(b) },
                { flow.pop() },
                { flow.pop() },
                { flow.replace(c) },
                { flow.close() },
                { try flow.open(at: [a, b]) },
                { flow.close() },
            ]
            for operation in operations
            {
                try operation()
                XCTAssertEqual(flow.isPresented, !flow.stack.isEmpty)
            }
    }
    }
}

final class LoadableAndBusyTests: XCTestCase
{
    /// The two state vocabularies carry core's envelope rather than a type this module
    /// invents, which is the whole of their dependency on core.
    func testTheErrorStatesCarryTheCoreEnvelope() async throws
    {
        try await onMainActor
        {
            let envelope = SPFNErrorEnvelope(code: "conflict", message: "m", requestID: "r")
            XCTAssertEqual(Loadable<Int>.error(envelope), .error(envelope))
            XCTAssertEqual(Busy.error(envelope), .error(envelope))
            XCTAssertNotEqual(Busy.error(envelope), .idle)
        }
    }

    func testReadyAndEmptyAreDifferentStates() async throws
    {
        try await onMainActor
        {
            XCTAssertNotEqual(Loadable.ready([Int]()), Loadable<[Int]>.empty)
        }
    }
}
