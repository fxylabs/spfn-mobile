// SPFN Mobile — the outcome observer (spfn-mobile#97).
//
// One case per row of the issue's table, O1–O8; row L9, the lifecycle handing its
// observer to every client it builds, is in SPFNKeyLifecycleTests.swift beside the
// installs it needs. Every case asserts the whole history the observer was handed, not
// its last entry, because the defect this surface invites is an outcome reported once
// per attempt rather than once per call.
//
// SpfnClientOutcomeTest.kt is the counterpart and uses corresponding case names.

import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNClientOutcomeTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    /// O1. An answer the call accepted is a success, and the response is returned as before.
    func test_onOutcome_O1_anAcceptedAnswerReportsSuccess() async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let echoed = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
        XCTAssertEqual(recorder.reported, [nil])
    }

    /// O2. No connection is reported as the transport said it, and thrown the same.
    func test_onOutcome_O2_aConnectivityFailureReportsTheErrorItThrows() async throws
    {
        try await assertReportedAsThrown(.connectivity("offline"))
    }

    /// O3. No answer in time.
    func test_onOutcome_O3_aTimeoutReportsTheErrorItThrows() async throws
    {
        try await assertReportedAsThrown(.timedOut)
    }

    /// O4. A transport that observed the cancellation itself.
    func test_onOutcome_O4_aCancelledTransportCallReportsTheErrorItThrows() async throws
    {
        try await assertReportedAsThrown(.cancelled)
    }

    /// O4. The calling task cancelled between the two attempts: still reported, once, and
    /// still thrown — the observer is told before the cancellation leaves `execute`.
    func test_onOutcome_O4_aCancelledTaskReportsCancellationAndStillThrows() async throws
    {
        let recorder = OutcomeRecorder()
        let holder = TaskHolder()
        let transport = ScriptedTransport(
            [
                .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
                .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
            ],
            onCall: { call in
                if call == 2
                {
                    await holder.cancelWhenHeld()
                }
            }
        )
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let running = Task { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }
        await holder.hold(running)
        let thrown = await failure { try await running.value }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.cancelled))
        XCTAssertEqual(recorder.reported, [.transport(.cancelled)])
    }

    /// O5. A refusal the server decided on is reported as the envelope classified.
    func test_onOutcome_O5_aServerRefusalReportsTheErrorItThrows() async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(400, ExecuteFixtures.errorEnvelope(code: "PROFILE_REJECTED"))),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        guard case .server(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected a server failure, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .profileRejected)
        XCTAssertEqual(recorder.reported, [thrown as? SPFNClientError])
    }

    /// O5. Refused as expired, retried once, refused again: two attempts and one outcome,
    /// the one the second attempt ended with.
    func test_onOutcome_O5_aRefusalAfterTheOneRetryIsReportedOnce() async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_EXPIRED"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_EXPIRED"))),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        guard case .auth(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected an auth failure, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .proofExpired)
        XCTAssertEqual(recorder.reported, [thrown as? SPFNClientError], "one call, one outcome")
        let operations = await transport.received.filter { $0.url.hasSuffix(SPFNGeneratedOperations.echoSend.path) }.count
        XCTAssertEqual(operations, 2)
    }

    /// O6. The retry is the SDK's own handling: a call it rescued is a success, once.
    func test_onOutcome_O6_aProofRetryThatSucceedsReportsOneSuccess() async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_EXPIRED"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let echoed = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
        XCTAssertEqual(recorder.reported, [nil])
    }

    /// O7. An answer this SDK could not read.
    func test_onOutcome_O7_aDecodingFailureReportsTheErrorItThrows() async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, "{}")),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse, onSuccessStatus: true))
        XCTAssertEqual(recorder.reported, [.decoding(.notTheDeclaredResponse, onSuccessStatus: true)])
    }

    /// O8. Without an observer the call answers and fails exactly as it did before.
    func test_onOutcome_O8_noObserverLeavesTheCallUnchanged() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
            .failure(SPFNTransportError.timedOut),
        ])
        let client = try makeClient(transport, onOutcome: nil)

        let echoed = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)
        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 3)
    }

    // MARK: - Assembly

    /// Opens a session, then fails the one request with `error` in the transport.
    private func assertReportedAsThrown(
        _ error: SPFNTransportError,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        let recorder = OutcomeRecorder()
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .failure(error),
        ])
        let client = try makeClient(transport, onOutcome: recorder.observer)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(error), file: file, line: line)
        XCTAssertEqual(recorder.reported, [.transport(error)], file: file, line: line)
    }

    /// The clock re-anchors like the real one, so the expired-proof rows take the path
    /// they take in production.
    private func makeClient(_ transport: ScriptedTransport, onOutcome: SPFNClientOutcome?) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: try SPFNSession(
                transport: transport,
                keyProvider: try ExecuteFixtures.syntheticProvider(),
                baseURL: baseURL,
                clock: AnchoringProofClock(SessionFixtureValues.issuedAtMillis),
                nonceGenerator: ScriptedNonceGenerator([])
            ),
            onOutcome: onOutcome
        )
    }

    private func failure(
        _ body: () async throws -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async -> (any Error)?
    {
        do
        {
            try await body()
        }
        catch
        {
            return error
        }
        XCTFail("expected a failure", file: file, line: line)
        return nil
    }
}
