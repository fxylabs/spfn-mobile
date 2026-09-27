// SPFN Mobile — the event stream's configuration, backoff and invariants
// (docs/architecture/event-stream-design.md §9-1, "추가로").
//
// The invariants run a thousand random inputs through the machine under a fixed seed, so a
// failure replays exactly. The Kotlin suite runs the same properties under the same seed;
// the two generators are not the same sequence, the properties are.

import XCTest
@testable import SPFNClient

/// SplitMix64: a seeded generator, because `SystemRandomNumberGenerator` cannot replay.
struct SeededGenerator: RandomNumberGenerator
{
    private var state: UInt64

    init(seed: UInt64)
    {
        state = seed
    }

    mutating func next() -> UInt64
    {
        state &+= 0x9E37_79B9_7F4A_7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
        value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
        return value ^ (value >> 31)
    }
}

final class SPFNEventStreamPropertyTests: XCTestCase
{
    static let seed: UInt64 = 20_260_927
    static let token = SPFNEventStreamToken(String(repeating: "t", count: 64))

    static let tokenFailures: [SPFNTokenFailure] = [
        .unauthorized, .forbidden, .serverError(status: 503, retryAfterMillis: nil),
        .serverError(status: 429, retryAfterMillis: 5_000), .network, .unreadable,
    ]

    static let streamAnswers: [SPFNStreamAnswer] = [
        .notEventStream, .tokenRejected, .invalidEvents(invalid: ["sessionUnread"], valid: ["sessionActivity"]),
        .badRequest, .forbidden, .serverError(status: 502, retryAfterMillis: nil),
    ]

    func test_backoff_sequence()
    {
        let backoff = SPFNEventStreamBackoff.standard
        XCTAssertEqual((1 ... 7).map { backoff.delayMillis(failure: $0, jitter: 1.0) }, [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000])
        XCTAssertEqual((1 ... 6).map { backoff.delayMillis(failure: $0, jitter: 0.5) }, [500, 1_000, 2_000, 4_000, 8_000, 15_000])
        XCTAssertEqual(backoff.delayMillis(failure: 1, jitter: 0.0), 500)
    }

    func test_tokenPath_derived() throws
    {
        XCTAssertEqual(try SPFNEventStreamConfiguration(events: ["a"]).tokenPath, "/events/token")
        XCTAssertEqual(try SPFNEventStreamConfiguration(events: ["a"], streamPath: "/sse").tokenPath, "/token")
        XCTAssertEqual(try SPFNEventStreamConfiguration(events: ["a"], streamPath: "/a/b/c").tokenPath, "/a/b/token")
        XCTAssertEqual(try SPFNEventStreamConfiguration(events: ["a"], tokenPath: "/custom/mint").tokenPath, "/custom/mint")
        for path in ["/events/stream?x=1", "events/stream", "/events#frag"]
        {
            XCTAssertThrowsError(try SPFNEventStreamConfiguration(events: ["a"], streamPath: path))
            {
                XCTAssertEqual($0 as? SPFNEventStreamError, .invalidConfiguration(field: "streamPath"))
            }
        }
        XCTAssertThrowsError(try SPFNEventStreamConfiguration(events: ["a"], tokenPath: "/t?x"))
    }

    func test_configuration_rejectsEmptyEvents() throws
    {
        for events in [[], [""], ["a,b"], ["ok", ""]]
        {
            XCTAssertThrowsError(try SPFNEventStreamConfiguration(events: events))
            {
                XCTAssertEqual($0 as? SPFNEventStreamError, .invalidConfiguration(field: "events"))
            }
        }
        let deduplicated = try SPFNEventStreamConfiguration(events: ["sessionUnread", "sessionActivity", "sessionUnread"])
        XCTAssertEqual(deduplicated.events, ["sessionActivity", "sessionUnread"])
        XCTAssertEqual(deduplicated.silenceMillis, 25_000)
    }

    func test_staleResult_ignored() throws
    {
        let machine = SPFNEventStreamMachine(configuration: try SPFNEventStreamConfiguration(events: ["sessionActivity"]), jitter: { 1.0 })
        var state = machine.initial()
        for input: SPFNEventInput in [.setSignedIn("a"), .setForeground(true)]
        {
            state = machine.step(state, input).state
        }
        let issued = state.generation
        state = machine.step(state, .tokenFailed(generation: issued, failure: .network)).state
        let late: [SPFNEventInput] = [
            .tokenMinted(generation: issued, token: Self.token),
            .streamAnswered(generation: issued, answer: .eventStream),
            .streamFailed(generation: issued),
            .silenceElapsed(generation: issued),
            .retryElapsed(generation: issued),
            .stableElapsed(generation: issued),
        ]
        for input in late
        {
            let step = machine.step(state, input)
            XCTAssertEqual(step.state, state)
            XCTAssertEqual(step.effects, [])
        }
    }

    func test_epoch_monotonic() throws
    {
        try walk
        {
            before, after, _ in
            XCTAssertGreaterThanOrEqual(after.epoch, before.epoch)
        }
    }

    func test_connection_iffForegroundAndSignedIn() throws
    {
        try walk
        {
            _, after, input in
            let idle: Bool
            if case .idle = after.phase
            {
                idle = true
            }
            else
            {
                idle = false
            }
            XCTAssertEqual(idle, !after.wantsConnection, "after \(input) the state was \(after.publicState)")
        }
    }

    func test_closed_leavesOnlyThroughCondition() throws
    {
        try walk
        {
            before, after, input in
            guard case .closed = before.phase
            else
            {
                return
            }
            if case .closed = after.phase
            {
                return
            }
            XCTAssertTrue(before.foreground != after.foreground || before.clientID != after.clientID, "closed was left by \(input)")
        }
    }

    /// A thousand inputs from the whole vocabulary, each asynchronous result carrying the
    /// current generation or a stale one. `check` sees every step.
    private func walk(_ check: (SPFNEventMachineState, SPFNEventMachineState, SPFNEventInput) -> Void) throws
    {
        let random = SeededGeneratorBox(seed: Self.seed)
        let machine = SPFNEventStreamMachine(
            configuration: try SPFNEventStreamConfiguration(events: ["sessionActivity", "sessionUnread"]),
            jitter: { random.double(in: 0.5 ... 1.0) }
        )
        var state = machine.initial()
        var closedSeen = false
        for _ in 0 ..< 1_000
        {
            let input = randomInput(random, state)
            let after = machine.step(state, input).state
            check(state, after, input)
            if case .closed = after.phase
            {
                closedSeen = true
            }
            state = after
        }
        XCTAssertTrue(closedSeen, "the walk never reached closed; widen the generator")
    }

    private func randomInput(_ random: SeededGeneratorBox, _ state: SPFNEventMachineState) -> SPFNEventInput
    {
        let generation = random.int(4) == 0 ? state.generation - 1 : state.generation
        switch random.int(16)
        {
        // Weighted toward the connection condition, or the walk spends itself in idle.
        case 0:
            return .setForeground(random.int(5) != 0)
        case 1:
            return .setSignedIn([nil, "a", "a", "b", "b"][random.int(5)])
        case 2:
            return .setNetworkAvailable(random.int(5) != 0)
        case 3:
            return .tokenMinted(generation: generation, token: Self.token)
        case 4:
            return .tokenFailed(generation: generation, failure: Self.tokenFailures[random.int(Self.tokenFailures.count)])
        case 5:
            return .streamAnswered(generation: generation, answer: Self.streamAnswers[random.int(Self.streamAnswers.count)])
        case 6, 7:
            return .streamAnswered(generation: generation, answer: .eventStream)
        case 8, 9:
            return .frameReceived(generation: generation, event: SPFNSSEEvent(name: "connected", data: "{}"))
        case 10:
            return .frameReceived(generation: generation, event: SPFNSSEEvent(name: "sessionActivity", data: "{}"))
        case 11:
            return .streamFailed(generation: generation)
        case 12:
            return .streamEnded(generation: generation)
        case 13:
            return .silenceElapsed(generation: generation)
        case 14:
            return .retryElapsed(generation: generation)
        default:
            return .stableElapsed(generation: generation)
        }
    }
}

/// The seeded generator behind a lock, so the machine's `@Sendable` jitter can draw from
/// the same sequence as the walk.
final class SeededGeneratorBox: @unchecked Sendable
{
    private let lock = NSLock()
    private var generator: SeededGenerator

    init(seed: UInt64)
    {
        generator = SeededGenerator(seed: seed)
    }

    func int(_ bound: Int) -> Int
    {
        lock.withLock { Int.random(in: 0 ..< bound, using: &generator) }
    }

    func double(in range: ClosedRange<Double>) -> Double
    {
        lock.withLock { Double.random(in: range, using: &generator) }
    }
}
