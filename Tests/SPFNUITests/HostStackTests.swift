// SPFN Mobile — the host stack's reconciliation, one test per rule.
//
// Counterpart of android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/HostStackTest.kt,
// case for case and name for name. `HostStack` is what makes decision N1 possible — a pushed
// flow appends to the host's stack instead of drawing its own over it — and everything it
// gets wrong is invisible on a device until two flows are on one stack at once. So the cases
// below are written about the LIST rather than about a navigator, which is why they run here
// at all: this type imports no toolkit and this file needs no simulator.
//
// The names carry a `test_` prefix that the Kotlin half does not need: XCTest discovers a
// case by that prefix and would run none of these without it.

import XCTest
@testable import SPFNUI

/// Two stand-ins for two flows. Identity is all `HostStack` reads of an owner, so a bare
/// class is the whole of what a test needs one to be.
private final class Owner {}

private struct Halt: Hashable
{
    let name: String
}

final class HostStackTests: XCTestCase
{
    func test_sync_twoFlowsPushingInTurn_interleavesThemInTheOrderTheyArrived()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1"), Halt(name: "b2")])

        // Four pushes in four turns, so four entries in those four turns: the stack is the
        // order a person pushed, not the owners gathered into two blocks. Grouping them
        // would have put a2 under b1 while a2 is what its own flow believes is on top.
        XCTAssertEqual(
            stack.entries.map { $0.route },
            [
                AnyHashable(Halt(name: "a1")),
                AnyHashable(Halt(name: "b1")),
                AnyHashable(Halt(name: "a2")),
                AnyHashable(Halt(name: "b2"))
            ]
        )
        XCTAssertEqual(stack.entries.map { $0.owner }, [firstId, secondId, firstId, secondId])
    }

    func test_sync_pushFromACoveredFlow_landsOnTop()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        // The first flow is covered by the second and pushes anyway. What the host draws has
        // to be what that flow now believes is its top, or the two disagree about the screen
        // in front of the person and a system back is spent on the wrong flow.
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])

        XCTAssertEqual(
            stack.entries.map { $0.route },
            [AnyHashable(Halt(name: "a1")), AnyHashable(Halt(name: "b1")), AnyHashable(Halt(name: "a2"))]
        )
        XCTAssertEqual(stack.topOwner(), firstId)
    }

    func test_sync_popFromACoveredFlow_removesItInPlace()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1")])

        // a2 left from under b1, and b1 did not move for it: nothing about the second flow
        // changed, so nothing about where it stands does either.
        XCTAssertEqual(
            stack.entries.map { $0.route },
            [AnyHashable(Halt(name: "a1")), AnyHashable(Halt(name: "b1"))]
        )
        XCTAssertEqual(stack.topOwner(), secondId)
    }

    func test_sync_replacingATail_keepsThePrefixInPlace()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a3")])

        // a1 is shared with what was there and stays where it was; a2 is gone and a3 is new,
        // so a3 goes on top — a route pushed now is above everything pushed before it.
        XCTAssertEqual(
            stack.entries.map { $0.route },
            [AnyHashable(Halt(name: "a1")), AnyHashable(Halt(name: "b1")), AnyHashable(Halt(name: "a3"))]
        )
        XCTAssertEqual(stack.entries.map { $0.owner }, [firstId, secondId, firstId])
    }

    func test_sync_emptyRoutes_removesEveryEntryOfThatOwner()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        // Interleaved, so the closing flow's entries are not one run to cut out.
        stack = stack.sync(owner: firstId, routes: [])

        XCTAssertEqual(stack.entries.map { $0.route }, [AnyHashable(Halt(name: "b1"))])
        XCTAssertEqual(stack.entries.map { $0.owner }, [secondId])
    }

    func test_sync_closingOneFlow_leavesTheOtherFlowsEntriesStanding()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1")])
        stack = stack.sync(owner: firstId, routes: [])

        XCTAssertEqual(stack.entries.map { $0.route }, [AnyHashable(Halt(name: "b1"))])
        XCTAssertEqual(stack.topOwner(), secondId)
    }

    func test_shortened_cuttingThreeFromTheTail_splitsThemTwoAndOne()
    {
        let first = Owner()
        let second = Owner()
        let firstId = ObjectIdentifier(first)
        let secondId = ObjectIdentifier(second)

        var stack = HostStack()
        stack = stack.sync(owner: firstId, routes: [Halt(name: "a1"), Halt(name: "a2")])
        stack = stack.sync(owner: secondId, routes: [Halt(name: "b1"), Halt(name: "b2")])

        // Four entries cut to one: the tail is a2, b1, b2 — one of the first owner's and
        // two of the second's. A count alone could not say that, which is the whole reason
        // this answers per owner.
        XCTAssertEqual(stack.shortened(to: 1), [firstId: 1, secondId: 2])
        XCTAssertEqual(stack.shortened(to: 2), [secondId: 2])
        // Not a shortening at all, and therefore nothing to report.
        XCTAssertEqual(stack.shortened(to: 4), [:])
        XCTAssertEqual(stack.shortened(to: 9), [:])
    }

    func test_anEmptyStack_dropsNothingAndHasNoTopOwner()
    {
        let stack = HostStack()
        XCTAssertEqual(stack.entries, [])
        XCTAssertEqual(stack.shortened(to: 0), [:])
        XCTAssertNil(stack.topOwner())
    }
}
