import Foundation
import XCTest
@testable import SPFNClient

final class SPFNProcessServerClockTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    func testUnsynchronizedFirstProofFetchesCoreTimeThenMintsTheProof() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, timeResponse(1_750_000_000_000))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
        ])
        let monotonic = FakeMonotonicClock(10)
        let clock = SPFNProcessServerClock(monotonicClock: monotonic)
        let session = SPFNSession(
            transport: transport,
            keyProvider: SPFNSoftwareKeyProvider(
                clientID: SessionFixtureValues.clientID,
                keyID: SessionFixtureValues.keyID
            ),
            baseURL: baseURL,
            clock: clock,
            nonceGenerator: ScriptedNonceGenerator(["nonce-000000000001"])
        )

        _ = try await session.handshake()

        let requests = await transport.received
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].method, "GET")
        XCTAssertEqual(requests[0].url, baseURL + "/_core/time")
        XCTAssertNil(requests[0].body)
        XCTAssertTrue(requests[0].headers.isEmpty)
        XCTAssertEqual(
            requests[1].headers.first { $0.0 == SPFNWireHeaders.issuedAtMillis }?.1,
            "1750000000000"
        )
    }

    func testUnsynchronizedConcurrentFirstReadersShareOneSynchronization() async throws
    {
        let transport = ScriptedTransport(
            [.success(.json(200, timeResponse(1_000)))],
            holdNanos: 50_000_000
        )
        let clock = SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(10))
        let url = baseURL

        async let first = clock.nowMillis(transport: transport, baseURL: url, timeoutMillis: 1_000)
        async let second = clock.nowMillis(transport: transport, baseURL: url, timeoutMillis: 1_000)
        let values = try await (first, second)
        let calls = await transport.callCount
        XCTAssertEqual([values.0, values.1], [1_000, 1_000])
        XCTAssertEqual(calls, 1)
    }

    func testUnsynchronizedRequestFailureIsExplicitAndNoProofIsSent() async throws
    {
        let transport = ScriptedTransport([
            .failure(SPFNTransportError.connectivity("offline")),
        ])
        let session = SPFNSession(
            transport: transport,
            keyProvider: SPFNSoftwareKeyProvider(
                clientID: SessionFixtureValues.clientID,
                keyID: SessionFixtureValues.keyID
            ),
            baseURL: baseURL,
            clock: SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(10)),
            nonceGenerator: ScriptedNonceGenerator(["nonce-never-used"])
        )

        do
        {
            _ = try await session.handshake()
            XCTFail("expected synchronization to fail")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNClockSynchronizationError, .requestFailed)
        }
        let requests = await transport.received
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].url, baseURL + "/_core/time")
    }

    func testUndecodableSynchronizationResponseFailsClosed() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, "{\"wrong\":1}"))])
        let clock = SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(10))

        do
        {
            _ = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
            XCTFail("expected invalid synchronization data")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNClockSynchronizationError, .invalidResponse)
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
    }

    func testSynchronizedProofTimeIsServerEpochPlusMonotonicElapsed() async throws
    {
        let monotonic = FakeMonotonicClock(100)
        let transport = ScriptedTransport([.success(.json(200, timeResponse(10_000)))])
        let clock = SPFNProcessServerClock(monotonicClock: monotonic)

        let anchored = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        monotonic.set(175)
        let advanced = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        let calls = await transport.callCount
        XCTAssertEqual(anchored, 10_000)
        XCTAssertEqual(advanced, 10_075)
        XCTAssertEqual(calls, 1)
    }

    func testSubMillisecondMonotonicPhaseDoesNotRoundElapsedUp() async throws
    {
        let monotonic = FakeMonotonicClock(rawNanos: 10_900_000)
        let transport = ScriptedTransport([.success(.json(200, timeResponse(10_000)))])
        let clock = SPFNProcessServerClock(monotonicClock: monotonic)

        _ = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        monotonic.set(rawNanos: 11_100_000)
        let derived = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)

        XCTAssertEqual(derived, 10_000, "0.2ms elapsed must not be rounded up to a future millisecond")
    }

    func testDeviceWallClockJumpDoesNotMoveSynchronizedProofTime() async throws
    {
        let deviceWallClock = FakeClock(1_000)
        let monotonic = FakeMonotonicClock(20)
        let transport = ScriptedTransport([.success(.json(200, timeResponse(50_000)))])
        let clock = SPFNProcessServerClock(monotonicClock: monotonic)

        let before = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        deviceWallClock.set(Int64.max)
        let after = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)

        XCTAssertEqual(before, 50_000)
        XCTAssertEqual(after, before)
        XCTAssertEqual(deviceWallClock.nowMillis(), Int64.max)
    }

    func testSynchronizedClockOverflowIsExplicit() async throws
    {
        let monotonic = FakeMonotonicClock(10)
        let transport = ScriptedTransport([.success(.json(200, timeResponse(Int64.max)))])
        let clock = SPFNProcessServerClock(monotonicClock: monotonic)

        _ = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        monotonic.set(11)
        do
        {
            _ = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
            XCTFail("expected signed 64-bit overflow")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNClockSynchronizationError, .clockOverflow)
        }
    }

    func testNewProcessClockHasNoPersistedAnchorAndSynchronizesAgain() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, timeResponse(1_000))),
            .success(.json(200, timeResponse(2_000))),
        ])

        let firstProcess = SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(10))
        let secondProcess = SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(20))
        let first = try await firstProcess.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        let second = try await secondProcess.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
        let calls = await transport.callCount
        XCTAssertEqual(first, 1_000)
        XCTAssertEqual(second, 2_000)
        XCTAssertEqual(calls, 2)
    }

    func testMissingContractOperationIsAnExplicitIncompatibility() async throws
    {
        let transport = ScriptedTransport([])
        let clock = SPFNProcessServerClock(
            monotonicClock: FakeMonotonicClock(10),
            operationResolver: { nil }
        )

        do
        {
            _ = try await clock.nowMillis(transport: transport, baseURL: baseURL, timeoutMillis: 1_000)
            XCTFail("expected a contract incompatibility")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNClockSynchronizationError, .contractIncompatible)
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    func testNonLoopbackCleartextIsRejectedBeforeTheNetwork() async throws
    {
        let transport = ScriptedTransport([])
        let clock = SPFNProcessServerClock(monotonicClock: FakeMonotonicClock(10))

        do
        {
            _ = try await clock.nowMillis(
                transport: transport,
                baseURL: "http://example.invalid",
                timeoutMillis: 1_000
            )
            XCTFail("expected an untrusted base URL")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNClockSynchronizationError, .untrustedBaseURL)
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    private func timeResponse(_ millis: Int64) -> String
    {
        "{\"serverTimeMillis\":\(millis)}"
    }
}

private final class FakeMonotonicClock: SPFNMonotonicClock, @unchecked Sendable
{
    private let lock = NSLock()
    private var nanos: UInt64

    init(_ millis: Int64)
    {
        nanos = UInt64(millis) * 1_000_000
    }

    init(rawNanos: UInt64)
    {
        nanos = rawNanos
    }

    func nowNanos() -> UInt64
    {
        lock.lock()
        defer { lock.unlock() }
        return nanos
    }

    func set(_ millis: Int64)
    {
        lock.lock()
        defer { lock.unlock() }
        nanos = UInt64(millis) * 1_000_000
    }

    func set(rawNanos: UInt64)
    {
        lock.lock()
        defer { lock.unlock() }
        nanos = rawNanos
    }
}
