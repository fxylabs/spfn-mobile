// SPFN Mobile — the event module's rules that need no SwiftUI (design §9-1, §9-3).
//
// The attachment is thin: it maps a scene's phase and a network path's status onto the
// stream's inputs. The mapping targets and the scene count are plain Swift, held here on
// Linux and macOS. Registration and release of the observers are device cells (U-11–U-14).

import XCTest
@testable import SPFNEvents
import SPFNClient

final class SPFNForegroundTallyTests: XCTestCase
{
    func test_foregroundTally()
    {
        let tally = SPFNForegroundTally()
        let first = UUID()
        let second = UUID()

        tally.set(first, .background)
        XCTAssertTrue(tally.set(second, .inactive), "one background scene and one inactive scene is the foreground")
        XCTAssertFalse(tally.set(second, .background), "two background scenes are the background")
        XCTAssertTrue(tally.set(first, .active))
        XCTAssertFalse(tally.remove(first), "a scene that went away leaves the count")
        XCTAssertFalse(tally.isForeground)
    }

    func test_scenePresence_onlyBackgroundIsBackground()
    {
        XCTAssertTrue(SPFNScenePresence.active.isForeground)
        XCTAssertTrue(SPFNScenePresence.inactive.isForeground)
        XCTAssertFalse(SPFNScenePresence.background.isForeground)
    }

    func test_networkPath_onlySatisfiedIsTheNetwork()
    {
        XCTAssertTrue(SPFNNetworkPathStatus.satisfied.isAvailable)
        XCTAssertFalse(SPFNNetworkPathStatus.unsatisfied.isAvailable)
        XCTAssertFalse(SPFNNetworkPathStatus.requiresConnection.isAvailable)
    }

    func test_sharedTally_isOnePerStream_andLeavesWithItsLastScene() throws
    {
        let keyLifecycle = SPFNKeyLifecycle(transport: NoTransport(), store: NoKeys(), baseURL: "https://example.invalid")
        let configuration = try SPFNEventStreamConfiguration(events: ["a"])
        let one = SPFNEventStream(keyLifecycle: keyLifecycle, configuration: configuration)
        let other = SPFNEventStream(keyLifecycle: keyLifecycle, configuration: configuration)
        let scene = UUID()

        XCTAssertTrue(SPFNForegroundTally.shared(for: one) === SPFNForegroundTally.shared(for: one))
        XCTAssertFalse(SPFNForegroundTally.shared(for: one) === SPFNForegroundTally.shared(for: other))
        let tally = SPFNForegroundTally.shared(for: one)
        tally.set(scene, .active)
        tally.remove(scene)
        XCTAssertFalse(SPFNForegroundTally.shared(for: one) === tally, "an emptied tally is dropped, not kept for a stream that may be gone")
    }
}

private struct NoTransport: SPFNTransport
{
    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        throw SPFNTransportError.connectivity("no transport in this suite")
    }
}

/// A store with nothing in it: nobody is signed in, and nothing is kept.
private struct NoKeys: SPFNKeyStore
{
    func load(slot: String) throws -> SPFNStoredKey?
    {
        nil
    }

    func save(_ record: SPFNStoredKey, slot: String) throws {}

    func delete(slot: String) throws {}
}
