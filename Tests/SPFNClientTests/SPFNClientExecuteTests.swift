// SPFN Mobile — the execute contract.
//
// Every rule a caller is allowed to rely on is pinned here, and each is written so that
// removing the code that implements it fails a named case rather than degrading quietly:
//
//   - one path: every operation that carries a session goes through `execute`, and the
//     handshake is refused there rather than sent;
//   - the bytes a request puts on the wire are the ones Contracts/fixtures/request/wire.json
//     records, assembled by the same session the previous change set pinned;
//   - a refusal is classified on the code the contract declares, never on the status;
//   - an auth refusal buys exactly one re-handshake and one re-send, with a new nonce and
//     a new proof over the same body;
//   - nothing else buys a retry, and neither does a second auth refusal;
//   - concurrent calls meeting one revocation share one re-handshake;
//   - no default output path prints anything the server wrote.
//
// SpfnClientExecuteTest.kt is the counterpart and uses corresponding case names.

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNClientExecuteTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    // MARK: - One path

    func testEveryOperationWithASessionGoesThroughTheSamePath() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
            .success(.json(200, ExecuteFixtures.listResponseBody)),
        ])
        let client = try makeClient(transport)

        let echoed: SPFNEchoResponse = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)
        let listed: SPFNListItemsResponse = try await client.execute(ExecuteCalls.list, request: ExecuteFixtures.listRequest)

        XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
        XCTAssertEqual(listed, ExecuteFixtures.listResponse)

        let recorded = await transport.received
        XCTAssertEqual(recorded.map(\.url), [
            baseURL + SPFNGeneratedOperations.authClientProofHandshake.path,
            baseURL + SPFNGeneratedOperations.echoSend.path,
            baseURL + SPFNGeneratedOperations.itemsList.path,
        ])
    }

    /// The handshake is what opens the session every other operation presents, so sending
    /// it through here would send it without the bookkeeping that gives it its point.
    func testTheHandshakeOperationIsRefusedRatherThanSent() async throws
    {
        let transport = ScriptedTransport([])
        let client = try makeClient(transport)

        let thrown = await failure
        {
            _ = try await client.execute(
                ExecuteCalls.handshake,
                request: SPFNHandshakeRequest(
                    clientId: SessionFixtureValues.clientID,
                    keyId: SessionFixtureValues.keyID,
                    nonce: "nonce-000000000001",
                    issuedAtMillis: SessionFixtureValues.issuedAtMillis
                )
            )
        }

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .unsupportedOperation(SPFNGeneratedOperations.authClientProofHandshake.id)
        )
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a refused operation costs no network call")
    }

    // MARK: - The unproven class

    /// K1/K2. An unproven operation is sent with the content type alone: no proof, no
    /// identity, no nonce and no session header — and no handshake happens first,
    /// because the operation exists to run before any key does.
    func testAnUnprovenOperationCarriesNoProofIdentityNonceOrSessionHeader() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, ExecuteFixtures.registerResponseBody)),
        ])
        let client = try makeClient(transport)

        let registered = try await client.execute(ExecuteCalls.register, request: ExecuteFixtures.registerRequest)

        XCTAssertEqual(registered, ExecuteFixtures.registerResponse)
        let recorded = await transport.received
        XCTAssertEqual(recorded.count, 1, "no handshake preceded the unproven request")
        let sent = try XCTUnwrap(recorded.first)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.url, baseURL + SPFNGeneratedOperations.authEnrollRegister.path)
        XCTAssertEqual(
            sent.headers.map { "\($0.0): \($0.1)" },
            ["\(SPFNWireHeaders.contentType): \(SPFNWireHeaders.requestContentType)"],
            "the content type is the only header an unproven request carries"
        )
        XCTAssertEqual(
            sent.body ?? [],
            SPFNCanonicalJSON.encode(ExecuteFixtures.registerRequest.canonicalValue)
        )
    }

    /// K2. The session is not consulted, not opened and not stored around an unproven
    /// call — the request goes out immediately with no state on either side of it.
    func testAnUnprovenOperationTouchesNoSessionState() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, ExecuteFixtures.registerResponseBody)),
        ])
        let session = try makeSession(transport)
        let client = SPFNClient(transport: transport, session: session)

        _ = try await client.execute(ExecuteCalls.register, request: ExecuteFixtures.registerRequest)

        let state = await session.currentState
        XCTAssertNil(state, "an unproven call opened a session it has no use for")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
    }

    /// The unproven class owns no retry: the one retry `execute` has exists to replace
    /// a stale session, and an unproven request never presented one.
    func testAnUnprovenOperationIsNotRetriedOnAnAuthRefusal() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let client = try makeClient(transport)

        let thrown = await failure
        {
            _ = try await client.execute(ExecuteCalls.register, request: ExecuteFixtures.registerRequest)
        }

        guard case .auth(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected the refusal itself, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .proofInvalid)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "no re-handshake and no re-send for a session nobody presented")
    }

    /// K3. A proven operation that requires no session — the rotation operation — still
    /// carries every proof header, and never a session header or a handshake.
    func testAProvenSessionFreeOperationCarriesProofHeadersAndNoSession() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, ExecuteFixtures.rotateResponseBody)),
        ])
        let client = try makeClient(transport)

        let rotated = try await client.execute(ExecuteCalls.rotate, request: ExecuteFixtures.rotateRequest)

        XCTAssertEqual(rotated, ExecuteFixtures.rotateResponse)
        let recorded = await transport.received
        XCTAssertEqual(recorded.count, 1, "no handshake: the proof alone authenticates this operation")
        let sent = try XCTUnwrap(recorded.first)
        XCTAssertEqual(sent.url, baseURL + SPFNGeneratedOperations.authKeysRotate.path)
        XCTAssertEqual(sent.headers.map(\.0), [
            SPFNWireHeaders.contentType,
            SPFNWireHeaders.profile,
            SPFNWireHeaders.clientID,
            SPFNWireHeaders.keyID,
            SPFNWireHeaders.nonce,
            SPFNWireHeaders.issuedAtMillis,
            SPFNWireHeaders.proof,
        ])
    }

    /// K4. An operation naming an auth class outside the generated enum is refused
    /// before anything is sent. Fail-closed: unknown is never downgraded to unproven.
    func testAnOperationNamingAnUndeclaredAuthClassIsRefusedUnsent() async throws
    {
        let transport = ScriptedTransport([])
        let client = try makeClient(transport)

        let thrown = await failure
        {
            _ = try await client.execute(ExecuteCalls.undeclared, request: ExecuteFixtures.echoRequest)
        }

        XCTAssertEqual(thrown as? SPFNClientError, .undeclaredAuthClass("mysteryV9"))
        XCTAssertEqual(
            "\(SPFNClientError.undeclaredAuthClass("mysteryV9"))",
            "SPFNClientError.undeclaredAuthClass(mysteryV9)"
        )
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a refused auth class costs no network call")
    }

    // MARK: - The bytes on the wire

    func testAnExecutedRequestMatchesTheWireVector() async throws
    {
        let vector = try WireFixtures.vector("echo-with-session")
        let expected = try vector.headerPairs("headers")
        let issued = try issuedValues(expected)
        let openingNonce = try issuedValues(try WireFixtures.vector("handshake").headerPairs("headers")).nonce

        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(
            transport,
            clock: FakeClock(issued.millis),
            nonces: [openingNonce, issued.nonce]
        )

        _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.last)
        XCTAssertEqual(sent.method, try vector.text("method"))
        XCTAssertEqual(sent.url, baseURL + (try vector.text("path")))
        try assertHeadersMatchWireVector(sent.headers, expected: expected, vector: vector)
        XCTAssertEqual(String(decoding: sent.body ?? [], as: UTF8.self), try vector.text("canonicalBody"))
    }

    /// `items.list` has no recorded vector, so what is pinned for it is the rule rather
    /// than a byte string: the body is the canonical encoding of the request value and
    /// the headers are the ones the wire mapping names, in the order it fixes.
    func testAnOperationWithoutAVectorStillCarriesTheContractShape() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.listResponseBody)),
        ])
        let client = try makeClient(transport)

        _ = try await client.execute(ExecuteCalls.list, request: ExecuteFixtures.listRequest)

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.last)
        XCTAssertEqual(
            sent.body ?? [],
            SPFNCanonicalJSON.encode(ExecuteFixtures.listRequest.canonicalValue)
        )
        XCTAssertEqual(sent.headers.map(\.0), [
            SPFNWireHeaders.contentType,
            SPFNWireHeaders.profile,
            SPFNWireHeaders.clientID,
            SPFNWireHeaders.keyID,
            SPFNWireHeaders.nonce,
            SPFNWireHeaders.issuedAtMillis,
            SPFNWireHeaders.proof,
            SPFNWireHeaders.session,
        ])
    }

    // MARK: - Reading the answer

    func testA2xxThatIsNotTheDeclaredResponseIsADecodingFailure() async throws
    {
        let thrown = try await echoFailing(with: .json(200, "{\"message\":\"hello\"}"))
        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse))
    }

    func testABodyThatIsNotCanonicalJSONIsADecodingFailure() async throws
    {
        let thrown = try await echoFailing(with: .json(200, "not json at all"))
        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notCanonicalJSON))
    }

    func testAContractErrorCodeOutsideTheAuthFamilyIsAServerFailure() async throws
    {
        let thrown = try await echoFailing(with: .json(400, ExecuteFixtures.errorEnvelope(code: "PROFILE_REJECTED")))

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .server(SPFNServerFailure(
                code: .profileRejected,
                httpStatus: 400,
                envelope: SPFNErrorEnvelope(code: "PROFILE_REJECTED", message: "refused", requestID: "req-test-0001")
            ))
        )
    }

    func testAnUnknownErrorCodeIsADecodingFailure() async throws
    {
        let thrown = try await echoFailing(with: .json(409, ExecuteFixtures.errorEnvelope(code: "TEAPOT")))
        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.unknownErrorCode))
    }

    /// The classification is on the code, not the status. A 401 an intermediary wrote
    /// carries no envelope, and reading it as an auth failure would make the client
    /// re-handshake against something that never refused a proof.
    func testA401WithoutAnEnvelopeIsADecodingFailureAndNotAnAuthFailure() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, "{\"detail\":\"gateway says no\"}")),
        ])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notAnErrorEnvelope))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "a status alone never provokes a re-handshake")
    }

    func testEveryContractErrorCodeIsClassifiedOnPurpose() throws
    {
        let auth = SPFNGeneratedErrorCode.allCases.filter(\.isAuthFailure).map(\.rawValue)
        XCTAssertEqual(auth, ["PROOF_INVALID", "PROOF_REPLAYED", "PROOF_EXPIRED", "SESSION_REVOKED"])

        let server = SPFNGeneratedErrorCode.allCases.filter { !$0.isAuthFailure }.map(\.rawValue)
        XCTAssertEqual(
            server,
            [
                "PROFILE_REJECTED", "CONTRACT_UNSUPPORTED",
                "ValidationError", "NativeSignInUnsupportedError", "NonceKeyBindingError",
                "InvalidKeyFingerprintError", "UnverifiedEmailLinkError", "InvalidSocialTokenError",
                "AccountDisabledError", "AccountPendingDeletionError", "RegistrationRejectedError",
                "KeyIdAlreadyRegisteredError", "TooManyRequestsError", "Error",
            ]
        )
    }

    /// A re-handshake re-establishes a clientProofV1 session, and the /_auth operations
    /// carry no proof and open no session — so no code from that surface can be cleared
    /// by one, whatever the list above happens to say today.
    ///
    /// Stated against the surface rather than against a spelled-out list because the two
    /// fail differently: the list catches a code nobody classified, and this catches a
    /// code somebody classified wrongly. A rate limit routed into the re-handshake path
    /// would re-open a session, resend, and be rate-limited again.
    func testNoRestSurfaceCodeIsTreatedAsAnAuthFailure() throws
    {
        let rest = SPFNGeneratedErrorCode.allCases.filter { $0.surface == .rest }
        XCTAssertFalse(rest.isEmpty, "the contract declares a rest surface, so this must have something to check")

        for code in rest
        {
            XCTAssertFalse(
                code.isAuthFailure,
                "\(code.rawValue) is answered by the /_auth surface, and a re-handshake cannot clear one"
            )
        }
    }

    // MARK: - The one retry

    func testAnAuthRefusalReopensTheSessionAndResendsOnce() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport)

        let echoed = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
        let recorded = await transport.received
        XCTAssertEqual(recorded.map(\.url), [
            baseURL + SPFNGeneratedOperations.authClientProofHandshake.path,
            baseURL + SPFNGeneratedOperations.echoSend.path,
            baseURL + SPFNGeneratedOperations.authClientProofHandshake.path,
            baseURL + SPFNGeneratedOperations.echoSend.path,
        ])
    }

    /// The re-sent request is the same request, proved again. Same bytes, new nonce, new
    /// proof — a replayed nonce is one of the things the server refuses requests for.
    func testTheResentRequestCarriesTheSameBodyUnderAFreshProof() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_EXPIRED"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport)

        _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        let recorded = await transport.received
        let first = recorded[1]
        let second = recorded[3]

        XCTAssertEqual(first.body, second.body, "one encoding of the body, sent twice")
        XCTAssertNotEqual(header(SPFNWireHeaders.nonce, in: first), header(SPFNWireHeaders.nonce, in: second))
        XCTAssertNotEqual(header(SPFNWireHeaders.proof, in: first), header(SPFNWireHeaders.proof, in: second))
    }

    func testASecondAuthRefusalSurfacesInsteadOfRetryingAgain() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        guard case .auth(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected an auth failure, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .proofInvalid)
        XCTAssertEqual(refusal.httpStatus, 401)

        let calls = await transport.callCount
        XCTAssertEqual(calls, 4, "one re-handshake and one re-send, and then it stops")
    }

    /// A handshake that is itself refused is surfaced. Re-opening a session in answer to
    /// a refused attempt to open one is the loop this policy exists to not have.
    func testARefusedHandshakeIsSurfacedWithoutAnotherAttempt() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        guard case .auth(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected an auth failure, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .proofInvalid)

        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
    }

    func testAMalformedHandshakeAnswerIsADecodingFailure() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, "{\"sessionId\":\"only-half-of-it\"}"))])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse))
    }

    func testARefusedHandshakeWithoutAnEnvelopeIsADecodingFailure() async throws
    {
        let transport = ScriptedTransport([.success(.json(500, "{\"oops\":true}"))])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notAnErrorEnvelope))
    }

    // MARK: - Nothing else is retried

    func testATransportFailureIsNotRetried() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .failure(SPFNTransportError.timedOut),
        ])
        let client = try makeClient(transport)

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2)
    }

    func testAServerFailureIsNotRetried() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(409, ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"))),
        ])
        let client = try makeClient(transport)

        _ = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        let calls = await transport.callCount
        XCTAssertEqual(calls, 2)
    }

    func testADecodingFailureIsNotRetried() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, "{}")),
        ])
        let client = try makeClient(transport)

        _ = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        let calls = await transport.callCount
        XCTAssertEqual(calls, 2)
    }

    /// An error this path did not produce keeps its own type. A proof that could not be
    /// assembled is a client-side fault, and dressing it as one of the four would have it
    /// read as something the server did.
    func testAnErrorThePathDidNotProducePassesThroughUnchanged() async throws
    {
        let transport = ScriptedTransport([])
        let client = try makeClient(transport, clientID: "client\u{01}test")

        let thrown = await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }

        XCTAssertEqual(thrown as? SPFNAuthError, .controlCharacterInProofField("clientId"))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    // MARK: - Deadlines and cancellation

    func testEachAttemptCarriesItsOwnDeadline() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport, timeoutMillis: 1_234)

        _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest)

        let recorded = await transport.received
        XCTAssertEqual(recorded.map(\.timeoutMillis), [15_000, 1_234, 15_000, 1_234])
    }

    /// Cancellation that lands after the refusal and before the re-handshake costs no
    /// further request. A retry a caller already walked away from is a request nobody is
    /// waiting for and a nonce nobody will use.
    func testCancellationBetweenTheTwoAttemptsSendsNothingFurther() async throws
    {
        let holder = TaskHolder()
        let transport = ScriptedTransport(
            [
                .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
                .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
                .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
                .success(.json(200, ExecuteFixtures.echoResponseBody)),
            ],
            onCall: { call in
                if call == 2
                {
                    await holder.cancelWhenHeld()
                }
            }
        )
        let client = try makeClient(transport)

        let running = Task { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }
        await holder.hold(running)

        let thrown = await failure { try await running.value }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.cancelled))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "the re-handshake never happened")
    }

    // MARK: - Concurrency

    /// Every call refused on one session discards that session, and only that one. If they
    /// discarded whatever the session happened to be by then, the first re-opened session
    /// would be thrown away by the second call, which would open another, and so on.
    func testConcurrentCallsMeetingOneRevocationShareOneReHandshake() async throws
    {
        let server = RevokingServer(revoking: ["session-1"], holdingFirst: 3)
        let session = try makeSession(server)
        let client = SPFNClient(transport: server, session: session)

        _ = try await session.handshake()

        try await withThrowingTaskGroup(of: SPFNEchoResponse.self) { group in
            for _ in 0 ..< 3
            {
                group.addTask { try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }
            }
            for try await echoed in group
            {
                XCTAssertEqual(echoed, ExecuteFixtures.echoResponse)
            }
        }

        let handshakes = await server.handshakes
        XCTAssertEqual(handshakes, 2, "the opening one, and one shared by all three refusals")
        let calls = await server.callCount
        XCTAssertEqual(calls, 8, "two handshakes, three refused requests, three re-sent")
    }

    // MARK: - Redaction

    /// Same rule as the envelope itself: `description` alone is not enough, because
    /// `String(reflecting:)` and `dump` reach associated values through the mirror.
    func testNoDefaultOutputPathPrintsWhatTheServerWrote() throws
    {
        let envelope = SPFNErrorEnvelope(
            code: "SESSION_REVOKED",
            message: "session 0e33f belonging to client-9 was revoked",
            requestID: "req-0e33f"
        )
        let refusals: [SPFNClientError] = [
            .auth(SPFNAuthFailure(code: .sessionRevoked, httpStatus: 401, envelope: envelope)),
            .server(SPFNServerFailure(code: .profileRejected, httpStatus: 400, envelope: envelope)),
        ]

        for refusal in refusals
        {
            var dumped = ""
            dump(refusal, to: &dumped)

            for rendered in ["\(refusal)", String(reflecting: refusal), refusal.description, dumped]
            {
                XCTAssertFalse(rendered.contains("0e33f"), rendered)
                XCTAssertFalse(rendered.contains("client-9"), rendered)
                XCTAssertFalse(rendered.contains("revoked"), rendered)
            }
        }
    }

    /// Marker absence above would pass even if these types printed their envelope, because
    /// the envelope redacts itself. Fixing what each type's own rendering is makes the two
    /// layers provable separately rather than as one — the same split the session suite makes.
    func testARefusalStillNamesTheCodeAndTheStatus() throws
    {
        let envelope = SPFNErrorEnvelope(code: "SESSION_REVOKED", message: "gone", requestID: "req-1")

        XCTAssertEqual(
            "\(SPFNClientError.auth(SPFNAuthFailure(code: .sessionRevoked, httpStatus: 401, envelope: envelope)))",
            "SPFNClientError.auth(SPFNAuthFailure(code: SESSION_REVOKED, httpStatus: 401, envelope: redacted))"
        )
        XCTAssertEqual(
            "\(SPFNClientError.server(SPFNServerFailure(code: .profileRejected, httpStatus: 400, envelope: envelope)))",
            "SPFNClientError.server(SPFNServerFailure(code: PROFILE_REJECTED, httpStatus: 400, envelope: redacted))"
        )
        XCTAssertEqual(
            "\(SPFNClientError.decoding(.unknownErrorCode))",
            "SPFNClientError.decoding(unknownErrorCode)"
        )
    }

    // MARK: - Assembly

    private func makeSession(
        _ transport: any SPFNTransport,
        clock: FakeClock = FakeClock(SessionFixtureValues.issuedAtMillis),
        nonces: [String] = [],
        clientID: String = SessionFixtureValues.clientID
    ) throws -> SPFNSession
    {
        SPFNSession(
            transport: transport,
            keyProvider: try ExecuteFixtures.syntheticProvider(clientID: clientID),
            baseURL: baseURL,
            clock: clock,
            nonceGenerator: ScriptedNonceGenerator(nonces)
        )
    }

    private func makeClient(
        _ transport: any SPFNTransport,
        clock: FakeClock = FakeClock(SessionFixtureValues.issuedAtMillis),
        nonces: [String] = [],
        clientID: String = SessionFixtureValues.clientID,
        timeoutMillis: Int64 = 15_000
    ) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: try makeSession(transport, clock: clock, nonces: nonces, clientID: clientID),
            timeoutMillis: timeoutMillis
        )
    }

    /// Opens a session, then answers the one request with `response`.
    private func echoFailing(with response: SPFNTransportResponse) async throws -> (any Error)?
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(response),
        ])
        let client = try makeClient(transport)
        return await failure { _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) }
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

    private func header(_ name: String, in request: SPFNTransportRequest) -> String?
    {
        request.headers.first { $0.0 == name }?.1
    }

    private func issuedValues(_ headers: [(String, String)]) throws -> (nonce: String, millis: Int64)
    {
        let byName = Dictionary(uniqueKeysWithValues: headers)
        let nonce = try XCTUnwrap(byName[SPFNWireHeaders.nonce])
        let millis = try XCTUnwrap(Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis])))
        return (nonce, millis)
    }
}
