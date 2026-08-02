// SPFN Mobile — the session contract.
//
// Every rule the layers above are allowed to rely on is pinned here: one encoding of
// the body, a fresh nonce per proof, one handshake for many concurrent callers, expiry
// judged against the injected clock at an exact instant, and errors that stay the type
// they were. SpfnSessionTest.kt is the counterpart and uses corresponding case names.

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

/// Header pairs in a form XCTest can compare.
func pairs(_ headers: [(String, String)]) -> [[String]]
{
    headers.map { [$0.0, $0.1] }
}

final class SPFNSessionTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    private func keyProvider(clientID: String = SessionFixtureValues.clientID) -> SPFNInMemoryKeyProvider
    {
        SPFNInMemoryKeyProvider(
            clientID: clientID,
            keyID: SessionFixtureValues.keyID,
            key: Array("spfn-test-key-not-a-secret-0001".utf8)
        )
    }

    private func session(
        transport: ScriptedTransport,
        clock: FakeClock,
        nonces: [String],
        clientID: String = SessionFixtureValues.clientID
    ) -> SPFNSession
    {
        SPFNSession(
            transport: transport,
            keyProvider: keyProvider(clientID: clientID),
            baseURL: baseURL,
            clock: clock,
            nonceGenerator: ScriptedNonceGenerator(nonces)
        )
    }

    private func handshakeAnswer(
        expiringAt millis: Int64 = SessionFixtureValues.expiresAtMillis
    ) -> Result<SPFNTransportResponse, any Error>
    {
        .success(.json(200, SessionFixtureValues.handshakeResponse(expiringAt: millis)))
    }

    // MARK: - The handshake request

    func testHandshakeSendsOneRequestToTheContractPath() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["nonce-000000000001"]
        )

        let opened = try await subject.handshake()

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.first)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.url, "https://example.invalid/v1/auth/client-proof/handshake")
        XCTAssertEqual(opened.sessionID, SessionFixtureValues.sessionID)
        XCTAssertEqual(opened.expiresAtMillis, SessionFixtureValues.expiresAtMillis)
    }

    func testHandshakeCarriesNoSessionHeader() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["nonce-000000000001"]
        )

        _ = try await subject.handshake()

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.first)
        XCTAssertFalse(sent.headers.contains { $0.0 == SPFNWireHeaders.session })
    }

    /// The value the proof was taken over and the value that was sent have to be the
    /// same bytes. Recomputing the proof from the body the transport actually received
    /// is the only check that catches a second, independent encoding.
    func testProofIsTakenOverTheExactBodyThatWasSent() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["nonce-000000000001"]
        )

        _ = try await subject.handshake()

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.first)
        let headers = Dictionary(uniqueKeysWithValues: sent.headers)
        let input = SPFNProofInput.forRequest(
            method: "POST",
            path: "/v1/auth/client-proof/handshake",
            clientID: SessionFixtureValues.clientID,
            keyID: SessionFixtureValues.keyID,
            nonce: "nonce-000000000001",
            issuedAtMillis: SessionFixtureValues.issuedAtMillis,
            canonicalBody: sent.body
        )
        let expected = try SPFNClientProof.proof(
            for: input,
            key: Array("spfn-test-key-not-a-secret-0001".utf8)
        )

        XCTAssertEqual(headers[SPFNWireHeaders.proof], expected)
    }

    func testEveryProofCarriesAFreshNonce() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: []
        )
        // No script: the generator falls back to distinct values, and a session that
        // cached one nonce would still repeat itself.
        let first = try await subject.proofHeaders(
            operation: SPFNGeneratedOperations.authClientProofHandshake,
            canonicalBody: nil
        )
        let second = try await subject.proofHeaders(
            operation: SPFNGeneratedOperations.authClientProofHandshake,
            canonicalBody: nil
        )

        let firstNonce = Dictionary(uniqueKeysWithValues: first)[SPFNWireHeaders.nonce]
        let secondNonce = Dictionary(uniqueKeysWithValues: second)[SPFNWireHeaders.nonce]
        XCTAssertNotNil(firstNonce)
        XCTAssertNotEqual(firstNonce, secondNonce)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "the handshake operation requires no session")
    }

    func testTheRealNonceGeneratorDoesNotRepeat() throws
    {
        let generator = SPFNRandomNonceGenerator()
        var seen: Set<String> = []
        for _ in 0 ..< 256
        {
            let nonce = generator.nextNonce()
            XCTAssertEqual(nonce.count, 32)
            XCTAssertTrue(nonce.allSatisfy { $0.isHexDigit && !$0.isUppercase })
            seen.insert(nonce)
        }
        XCTAssertEqual(seen.count, 256)
    }

    // MARK: - ensureSession

    func testValidSessionCostsNoNetworkCall() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        _ = try await subject.ensureSession()
        _ = try await subject.ensureSession()
        _ = try await subject.ensureSession()

        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
    }

    func testExpiredSessionIsReopenedExactlyOnce() async throws
    {
        let renewed = SessionFixtureValues.expiresAtMillis + 300_000
        let transport = ScriptedTransport([handshakeAnswer(), handshakeAnswer(expiringAt: renewed)])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        _ = try await subject.ensureSession()
        clock.set(SessionFixtureValues.expiresAtMillis)
        let reopened = try await subject.ensureSession()
        _ = try await subject.ensureSession()

        XCTAssertEqual(reopened.expiresAtMillis, renewed)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "one handshake to open, one to reopen, and no more")
    }

    /// The boundary itself, in both directions. A session is usable strictly before its
    /// expiry instant, so `now == expiresAtMillis` already counts as expired.
    func testExpiryBoundaryIsExclusive() async throws
    {
        let transport = ScriptedTransport([
            handshakeAnswer(),
            handshakeAnswer(expiringAt: SessionFixtureValues.expiresAtMillis + 300_000),
        ])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        _ = try await subject.ensureSession()

        clock.set(SessionFixtureValues.expiresAtMillis - 1)
        _ = try await subject.ensureSession()
        let beforeExpiry = await transport.callCount
        XCTAssertEqual(beforeExpiry, 1, "one millisecond before expiry the session is still usable")

        clock.set(SessionFixtureValues.expiresAtMillis)
        _ = try await subject.ensureSession()
        let atExpiry = await transport.callCount
        XCTAssertEqual(atExpiry, 2, "at the expiry instant the session is gone")
    }

    func testConcurrentCallersShareOneHandshake() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()], holdNanos: 40_000_000)
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: [])

        let states = try await withThrowingTaskGroup(of: SPFNSessionState.self) { group in
            for _ in 0 ..< 16
            {
                group.addTask { try await subject.ensureSession() }
            }
            var collected: [SPFNSessionState] = []
            for try await state in group
            {
                collected.append(state)
            }
            return collected
        }

        XCTAssertEqual(states.count, 16)
        XCTAssertEqual(Set(states.map(\.sessionID)).count, 1)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "sixteen callers must not open sixteen sessions")
    }

    func testInvalidateForcesTheNextCallToHandshakeAgain() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer(), handshakeAnswer()])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        _ = try await subject.ensureSession()
        await subject.invalidate()
        let after = await subject.currentState
        _ = try await subject.ensureSession()

        XCTAssertNil(after)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2)
    }

    // MARK: - Header assembly

    func testSessionOperationCarriesTheSessionHeaderLast() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        let headers = try await subject.proofHeaders(
            operation: SPFNGeneratedOperations.echoSend,
            canonicalBody: Array(#"{"message":"hello","sequence":7}"#.utf8)
        )

        XCTAssertEqual(headers.last?.0, SPFNWireHeaders.session)
        XCTAssertEqual(headers.last?.1, SessionFixtureValues.sessionID)
        XCTAssertEqual(headers.first?.0, SPFNWireHeaders.contentType)
    }

    func testABodylessRequestCarriesNoContentType() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1"])

        let headers = try await subject.proofHeaders(
            operation: SPFNGeneratedOperations.authClientProofHandshake,
            canonicalBody: nil
        )

        XCTAssertFalse(headers.contains { $0.0 == SPFNWireHeaders.contentType })
        XCTAssertEqual(headers.first?.0, SPFNWireHeaders.profile)
    }

    func testNoHeaderNameIsAssembledTwice() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let subject = session(transport: transport, clock: clock, nonces: ["n1", "n2"])

        let headers = try await subject.proofHeaders(
            operation: SPFNGeneratedOperations.itemsList,
            canonicalBody: Array(#"{"limit":25}"#.utf8)
        )

        let names = headers.map { $0.0.lowercased() }
        XCTAssertEqual(Set(names).count, names.count, "the transport refuses a repeated header name")
    }

    // MARK: - Failures keep their type

    func testARejectedHandshakeSurfacesTheServerEnvelope() async throws
    {
        let body = #"{"error":{"code":"PROOF_INVALID","message":"test vector for PROOF_INVALID","requestId":"req-proof-invalid"}}"#
        let transport = ScriptedTransport([.success(.json(401, body))])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["n1"]
        )

        do
        {
            _ = try await subject.handshake()
            XCTFail("expected a rejection")
        }
        catch
        {
            XCTAssertEqual(
                error as? SPFNSessionError,
                .handshakeRejected(SPFNErrorEnvelope(
                    code: "PROOF_INVALID",
                    message: "test vector for PROOF_INVALID",
                    requestID: "req-proof-invalid"
                ))
            )
        }
        let held = await subject.currentState
        XCTAssertNil(held)
    }

    func testASuccessBodyThatIsNotAHandshakeResponseIsMalformed() async throws
    {
        for body in [#"{"sessionId":"s"}"#, #"{"nope":1}"#, "not json at all", ""]
        {
            let transport = ScriptedTransport([.success(.json(200, body))])
            let subject = session(
                transport: transport,
                clock: FakeClock(SessionFixtureValues.issuedAtMillis),
                nonces: ["n1"]
            )

            do
            {
                _ = try await subject.handshake()
                XCTFail("expected \(body) to be refused")
            }
            catch
            {
                XCTAssertEqual(
                    error as? SPFNSessionError,
                    .malformedResponse("response body is not a HandshakeResponse")
                )
            }
        }
    }

    func testAFailureBodyThatIsNotAnEnvelopeIsMalformed() async throws
    {
        for body in [#"{"error":{"code":"X"}}"#, "<html>gateway</html>", ""]
        {
            let transport = ScriptedTransport([.success(.json(502, body))])
            let subject = session(
                transport: transport,
                clock: FakeClock(SessionFixtureValues.issuedAtMillis),
                nonces: ["n1"]
            )

            do
            {
                _ = try await subject.handshake()
                XCTFail("expected \(body) to be refused")
            }
            catch
            {
                XCTAssertEqual(
                    error as? SPFNSessionError,
                    .malformedResponse("response body is not an SPFN error envelope")
                )
            }
        }
    }

    /// The reason string is fixed, so a server that answers with a secret-bearing body
    /// cannot get that body copied into an error and from there into a log.
    func testAMalformedReasonQuotesNothingFromTheBody() async throws
    {
        let body = #"{"leak":"super-secret-value-9f2a"}"#
        let transport = ScriptedTransport([.success(.json(200, body))])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["n1"]
        )

        do
        {
            _ = try await subject.handshake()
            XCTFail("expected a refusal")
        }
        catch let failure as SPFNSessionError
        {
            XCTAssertFalse("\(failure)".contains("super-secret-value-9f2a"))
            XCTAssertFalse("\(failure)".contains("leak"))
        }
    }

    func testAControlCharacterInAProofFieldStaysAnAuthError() async throws
    {
        let transport = ScriptedTransport([handshakeAnswer()])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["n1"],
            clientID: "client\u{0A}-injected"
        )

        do
        {
            _ = try await subject.handshake()
            XCTFail("expected the proof input to be refused")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNAuthError, .controlCharacterInProofField("clientId"))
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "nothing may be sent once the proof input is invalid")
    }

    func testATransportFailureStaysATransportError() async throws
    {
        let transport = ScriptedTransport([.failure(SPFNTransportError.timedOut)])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["n1"]
        )

        do
        {
            _ = try await subject.ensureSession()
            XCTFail("expected the transport failure to reach the caller")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNTransportError, .timedOut)
        }
    }

    /// A failed handshake must not leave the in-flight slot occupied, or every later
    /// caller would wait forever on a result that never arrives.
    func testAFailedHandshakeDoesNotStrandLaterCallers() async throws
    {
        let transport = ScriptedTransport([
            .failure(SPFNTransportError.timedOut),
            handshakeAnswer(),
        ])
        let subject = session(
            transport: transport,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            nonces: ["n1", "n2"]
        )

        _ = try? await subject.ensureSession()
        let opened = try await subject.ensureSession()

        XCTAssertEqual(opened.sessionID, SessionFixtureValues.sessionID)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2)
    }

    // MARK: - Nothing secret reaches a description

    func testDescriptionsCarryNoKeyAndNoSessionIdentifier()
    {
        let provider = keyProvider()
        XCTAssertFalse("\(provider)".contains("spfn-test-key-not-a-secret-0001"))
        XCTAssertTrue("\(provider)".contains("redacted"))
        XCTAssertFalse(String(reflecting: provider).contains("spfn-test-key-not-a-secret-0001"))

        let state = SPFNSessionState(
            sessionID: SessionFixtureValues.sessionID,
            expiresAtMillis: SessionFixtureValues.expiresAtMillis
        )
        XCTAssertFalse("\(state)".contains(SessionFixtureValues.sessionID))
        XCTAssertFalse(String(reflecting: state).contains(SessionFixtureValues.sessionID))
    }
}
