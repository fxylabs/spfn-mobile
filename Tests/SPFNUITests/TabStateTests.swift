// SPFN Mobile — the tab state's table, one test per row that is pure state.
//
// Counterpart of android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/TabStateTest.kt,
// case for case and name for name. The rows are docs/architecture/tab-host-design.md §4 and
// §9-1, written out here from that table rather than read off this implementation: the
// selection a press makes, the pop and the scroll it asks for, the back a tab's root takes,
// and the refusals. Each test names the design's cell it stands for.
//
// | selected | depth | act           | result                                  | cell  |
// | start    | 0     | select(other) | other selected, .switched               | C-1   |
// | start    | 2     | select(start) | .popToRoot, nothing selected anew        | C-13  |
// | start    | 0     | select(start) | .scrollToTop, count 0 → 1               | C-14  |
// | start    | any   | select(nope)  | .ignored, nothing changes               | —     |
// | start    | 1     | show(other)   | other selected, no pop, no scroll       | C-15  |
// | other    | 0     | back          | start selected, consumed                | C-9   |
// | start    | 0     | back          | not consumed (the platform leaves)      | C-10  |
// | any      | 1     | back          | not consumed (the tab's navigator pops) | C-7   |
//
// The `test_` prefix is XCTest's discovery rule; the Kotlin half does not need one.

import XCTest
@testable import SPFNUI

/// Runs `body` on the main actor, for the reason `FlowTests.swift` states at length: an
/// isolated XCTest method cannot be discovered on Linux.
private func onMainActor(_ body: @MainActor @Sendable () throws -> Void) async throws
{
    try await MainActor.run(body: body)
}

private let home = "home"
private let account = "account"
private let settings = "settings"

final class TabStateTests: XCTestCase
{
    func test_init_empty_refused() async throws
    {
        try await onMainActor
        {
            XCTAssertThrowsError(try TabState(tabs: []))
            { error in
                XCTAssertEqual(error as? SPFNUIError, .noTabs)
            }
        }
    }

    func test_init_duplicate_refused() async throws
    {
        try await onMainActor
        {
            XCTAssertThrowsError(try TabState(tabs: [home, account, home]))
            { error in
                XCTAssertEqual(error as? SPFNUIError, .duplicateTab(home))
            }
        }
    }

    func test_init_unknownSelected_refused() async throws
    {
        try await onMainActor
        {
            XCTAssertThrowsError(try TabState(tabs: [home, account], selected: settings))
            { error in
                XCTAssertEqual(error as? SPFNUIError, .unknownTab(settings))
            }
        }
    }

    func test_init_firstIsStartAndSelected() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.tabs, [home, account])
            XCTAssertEqual(state.start, home)
            XCTAssertEqual(state.selected, home)

            // A launch that restores another tab still has the first as its start.
            let restored = try TabState(tabs: [home, account], selected: account)
            XCTAssertEqual(restored.start, home)
            XCTAssertEqual(restored.selected, account)
        }
    }

    func test_select_other_switches() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.select(account, depth: 0), .switched)
            XCTAssertEqual(state.selected, account)
            // A switch is not a press on the tab it left, so neither tab's count moved.
            XCTAssertEqual(state.scrollToTop(for: home), 0)
            XCTAssertEqual(state.scrollToTop(for: account), 0)
        }
    }

    func test_select_other_atDepth_switchesWithoutPopping() async throws
    {
        try await onMainActor
        {
            // The depth is the SELECTED tab's, and a switch leaves that stack where it is:
            // coming back finds the detail as it was left (decision Q-D).
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.select(account, depth: 2), .switched)
            XCTAssertEqual(state.selected, account)
        }
    }

    func test_select_current_atDepth_popsToRoot() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.select(home, depth: 2), .popToRoot)
            XCTAssertEqual(state.selected, home)
            // The pop is the host's; asking for it is not a scroll.
            XCTAssertEqual(state.scrollToTop(for: home), 0)
        }
    }

    func test_select_current_atRoot_scrollsToTop_countsUp() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.select(home, depth: 0), .scrollToTop)
            XCTAssertEqual(state.scrollToTop(for: home), 1)
            XCTAssertEqual(state.select(home, depth: 0), .scrollToTop)
            XCTAssertEqual(state.scrollToTop(for: home), 2)
            // One tab's count is that tab's alone.
            XCTAssertEqual(state.scrollToTop(for: account), 0)
            XCTAssertEqual(state.selected, home)
        }
    }

    func test_select_unknown_ignored() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertEqual(state.select(settings, depth: 0), .ignored)
            XCTAssertEqual(state.select(settings, depth: 3), .ignored)
            XCTAssertEqual(state.selected, home)
            XCTAssertEqual(state.scrollToTop(for: settings), 0)
        }
    }

    func test_show_never_popsOrScrolls() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            state.show(account)
            XCTAssertEqual(state.selected, account)
            // The tab already selected: nothing, and in particular no scroll request.
            state.show(account)
            XCTAssertEqual(state.selected, account)
            XCTAssertEqual(state.scrollToTop(for: account), 0)
            // Not a tab: nothing.
            state.show(settings)
            XCTAssertEqual(state.selected, account)
            // And back again, as C-15 does.
            state.show(home)
            XCTAssertEqual(state.selected, home)
            XCTAssertEqual(state.scrollToTop(for: home), 0)
        }
    }

    func test_back_nonStartRoot_selectsStart() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account, settings], selected: settings)
            XCTAssertTrue(state.back(depth: 0))
            // The START tab, not the one visited before: tab history is not retraced (Q-B).
            XCTAssertEqual(state.selected, home)
        }
    }

    func test_back_startRoot_notHandled() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account])
            XCTAssertFalse(state.back(depth: 0))
            XCTAssertEqual(state.selected, home)
        }
    }

    func test_back_atDepth_notHandled() async throws
    {
        try await onMainActor
        {
            let state = try TabState(tabs: [home, account], selected: account)
            XCTAssertFalse(state.back(depth: 1))
            XCTAssertEqual(state.selected, account)
            // C-33: two backs above the root are the tab's own; the third is this state's.
            XCTAssertFalse(state.back(depth: 2))
            XCTAssertTrue(state.back(depth: 0))
            XCTAssertEqual(state.selected, home)
        }
    }

    func test_handlesBack_matches_back() async throws
    {
        try await onMainActor
        {
            for selected in [home, account]
            {
                for depth in 0 ... 2
                {
                    let asked = try TabState(tabs: [home, account], selected: selected)
                    let acted = try TabState(tabs: [home, account], selected: selected)
                    XCTAssertEqual(
                        asked.handlesBack(depth: depth),
                        acted.back(depth: depth),
                        "selected \(selected), depth \(depth)"
                    )
                }
            }
        }
    }

    func test_owners_firstTabKeepsAFlow() async throws
    {
        try await onMainActor
        {
            final class Owner {}
            let flow = ObjectIdentifier(Owner.self)
            let other = ObjectIdentifier(TabStateTests.self)
            let owners = TabOwners()

            XCTAssertTrue(owners.claim(flow, for: home))
            // The same tab registering again — every re-render does — is not a second claim.
            XCTAssertTrue(owners.claim(flow, for: home))
            // Another tab asking for the same flow is refused, and the first keeps it.
            XCTAssertFalse(owners.claim(flow, for: account))
            XCTAssertTrue(owners.claim(flow, for: home))
            // A different flow is free for any tab.
            XCTAssertTrue(owners.claim(other, for: account))
        }
    }
}
