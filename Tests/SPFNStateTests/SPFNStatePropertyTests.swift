// SPFN Mobile — the store's two invariants over many random walks (state-client-design.md §4).
//
// Fixed seed, 1 000 arrivals. Each test also asserts the walk REACHED what it claims to
// cover — equal `at`s and older arrivals; a revoked mark and its lift — so a generator that
// never produced the case cannot pass vacuously (docs/IMPLEMENTATION-PITFALLS.md P46).
// android/spfn-state/src/test/.../SpfnStatePropertyTest.kt is the counterpart.

import XCTest
@testable import SPFNState
import SPFNCore

final class SPFNStatePropertyTests: XCTestCase
{
    /// A linear congruential generator: the same walk on every run and both platforms.
    struct Seeded
    {
        var state: UInt64

        mutating func next(_ bound: Int) -> Int
        {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int((state >> 33) % UInt64(bound))
        }
    }

    static let slot = SPFNStateSlot(id: "consent", key: [:])
    static let ats = (0 ..< 6).map { "2026-10-17T09:00:00.00\($0)Z" }

    static func arrival(_ random: inout Seeded) -> SPFNStateArrival
    {
        let value = SPFNCanonicalValue.integer(Int64(random.next(3)))
        return SPFNStateArrival(at: ats[random.next(ats.count)], value: value, data: nil, typed: value)
    }

    func test_ordering_neverRegresses()
    {
        var random = Seeded(state: 20_261_017)
        var store = SPFNStateStore()
        var equal = 0
        var older = 0
        for _ in 0 ..< 1_000
        {
            let before = store.stamp(Self.slot)
            let arrival = Self.arrival(&random)
            equal += before == arrival.at ? 1 : 0
            older += before.map { SPFNStateOrder.isOlder(arrival.at, than: $0) } == true ? 1 : 0
            _ = random.next(2) == 0 ? store.apply(Self.slot, arrival) : store.serve(Self.slot, arrival, generation: random.next(4))
            let after = store.stamp(Self.slot)!
            XCTAssertFalse(before.map { SPFNStateOrder.isOlder(after, than: $0) } ?? false, "the stamp never goes back")
            XCTAssertFalse(SPFNStateOrder.isOlder(after, than: store.cells[Self.slot]!.frame!.at), "an entry is never newer than the stamp")
        }
        XCTAssertGreaterThan(equal, 0, "the walk reached an equal at")
        XCTAssertGreaterThan(older, 0, "the walk reached an older arrival")
    }

    func test_revokedLift_goesByConnection()
    {
        var random = Seeded(state: 1_017)
        var store = SPFNStateStore()
        var revoked = 0
        var lifted = 0
        for _ in 0 ..< 1_000
        {
            let mark = store.mark(Self.slot)
            let generation = random.next(5)
            var granted = false
            switch random.next(4)
            {
            case 0:
                _ = store.revoke(Self.slot, generation: generation)
            case 1:
                store.allow(Self.slot)
                granted = true
            case 2:
                _ = store.serve(Self.slot, Self.arrival(&random), generation: generation)
            default:
                _ = store.apply(Self.slot, Self.arrival(&random))
            }
            revoked += store.mark(Self.slot) != nil ? 1 : 0
            if let mark, store.mark(Self.slot) == nil
            {
                lifted += 1
                XCTAssertTrue(granted || generation > mark, "a mark is lifted by a grant or a newer connection only")
            }
        }
        XCTAssertGreaterThan(revoked, 0, "the walk reached revoked")
        XCTAssertGreaterThan(lifted, 0, "and lifted it")
    }
}
