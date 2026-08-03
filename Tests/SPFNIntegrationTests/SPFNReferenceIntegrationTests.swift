// SPFN Mobile — the Swift SDK against a real socket.
//
// The same five cases the Android suite runs, against the same reference server, from a
// different process and a different codebase. That is what makes the pair evidence: two
// independent implementations agreeing with one server about one contract.
//
//   (a) three operations answer their declared types with the values sent
//   (b) an expired session costs exactly one re-handshake and then succeeds
//   (c) a revocation the client cannot fix surfaces as an auth failure
//   (d) a request replayed byte for byte is refused
//   (e) a timeout and a cancellation both work while a real server is holding the call
//
// Every case records a receipt. `sh tools/reference-server/run-integration.sh` checks the
// receipts afterwards, so this suite skipping — which XCTest reports as success — is
// turned into a failure by the runner rather than being read as a pass.

import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated
import XCTest

final class SPFNReferenceIntegrationTests: XCTestCase
{
    // TEST KEYPAIR ONLY — NOT A SECRET. The synthetic identity the reference server,
    // the primitives dev server and Contracts/fixtures/proof/proof-input.json all
    // pre-register the public half of; the private half is published on purpose and
    // authenticates nothing. Restated from the fixture's testKeyPair because this
    // suite runs from its own process and keeps no fixture loader.
    private static let clientID = "client-test-0001"
    private static let keyID = "key-test-0001"
    private static let privateKeyPkcs8B64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgMv3D4UvmGKjFeG3m"
        + "yLLfwlcOAQ9n8qoFmwrgGWBErsShRANCAARLvGS2Mr58zJ1PtRlx+5b+/NT2tT/5"
        + "E9VVAoqDGsHWx31uHo3VuqIHBT/O7D38tpD3WVY95ZI306VPw6UNhchi"

    /// Long enough that neither the timeout nor the cancellation can be a coincidence.
    private static let holdMillis: Int64 = 3_000

    // MARK: - (a)

    func testCaseAHandshakeEchoAndItemsListRoundTrip() async throws
    {
        let fixture = try await Fixture.start()

        let echoed = try await fixture.client.execute(
            Calls.echo,
            request: SPFNEchoRequest(message: "over the wire", sequence: 42)
        )
        XCTAssertEqual(echoed.message, "over the wire")
        XCTAssertEqual(echoed.sequence, 42)
        XCTAssertGreaterThan(echoed.serverTimeMillis, 0)

        let first = try await fixture.client.execute(Calls.listItems, request: SPFNListItemsRequest(limit: 2))
        XCTAssertEqual(first.items.map(\.id), ["item-0001", "item-0002"])
        XCTAssertEqual(first.items.map(\.name), ["alpha", "bravo"])
        XCTAssertEqual(first.nextCursor, "item-0002")

        let rest = try await fixture.client.execute(
            Calls.listItems,
            request: SPFNListItemsRequest(limit: 10, cursor: "item-0002")
        )
        XCTAssertEqual(rest.items.map(\.id), ["item-0003", "item-0004", "item-0005"])
        XCTAssertNil(rest.nextCursor)

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.handshakeCount, 1)
        XCTAssertEqual(stats.echoCount, 1)
        XCTAssertEqual(stats.itemsListCount, 2)
        XCTAssertEqual(stats.refusalCount, 0)

        try fixture.environment.record("swift-a")
    }

    // MARK: - (b)

    func testCaseBExpiredSessionCostsExactlyOneReHandshake() async throws
    {
        let fixture = try await Fixture.start()

        _ = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "before", sequence: 1))
        let opened = try await fixture.control.stats()
        XCTAssertEqual(opened.handshakeCount, 1)

        // The server drops the session without touching the expiry it advertised, so the
        // client goes on believing in it and presents it — which is the only way to reach
        // the refusal that a re-handshake exists to answer.
        try await fixture.control.expireSessions()

        let after = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "after", sequence: 2))
        XCTAssertEqual(after.message, "after")

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.handshakeCount, 2, "exactly one re-handshake")
        XCTAssertEqual(stats.echoCount, 2, "the refused attempt was not applied")
        XCTAssertEqual(stats.refusalCount, 1)

        try fixture.environment.record("swift-b")
    }

    // MARK: - (c)

    func testCaseCRevokedKeySurfacesAfterTheReHandshakeFailsToo() async throws
    {
        let fixture = try await Fixture.start()

        _ = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "before", sequence: 1))
        try await fixture.control.revokeKey(Self.keyID)

        do
        {
            _ = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "after", sequence: 2))
            XCTFail("a revoked key must not answer an operation")
        }
        catch SPFNClientError.auth(let failure)
        {
            XCTAssertEqual(failure.code, .sessionRevoked)
            XCTAssertEqual(failure.httpStatus, 401)
        }

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.refusalCount, 2, "the operation and the re-handshake were both refused")
        XCTAssertEqual(stats.handshakeCount, 1, "no second session was ever opened")

        try fixture.environment.record("swift-c")
    }

    // MARK: - (d)

    func testCaseDReplayedRequestIsRefused() async throws
    {
        let fixture = try await Fixture.start()

        let operation = SPFNGeneratedOperations.echoSend
        let body = SPFNCanonicalJSON.encode(SPFNEchoRequest(message: "replay me", sequence: 3).canonicalValue)

        // Assembled through the session, so the nonce, the timestamp and the proof are the
        // ones the SDK would really have sent. Replaying is then the SDK's own request sent
        // twice, not a hand-built approximation of one.
        let headers = try await fixture.session.proofHeaders(operation: operation, canonicalBody: body)
        let request = SPFNTransportRequest(
            method: operation.method,
            url: fixture.environment.baseURL + operation.path,
            headers: headers,
            body: body,
            timeoutMillis: 5_000
        )

        let first = try await fixture.transport.execute(request)
        XCTAssertEqual(first.statusCode, 200)

        let replayed = try await fixture.transport.execute(request)
        XCTAssertEqual(replayed.statusCode, 401)
        let envelope = try SPFNErrorEnvelope.decode(try SPFNCanonicalJSON.parse(replayed.body))
        XCTAssertEqual(envelope.code, "PROOF_REPLAYED")

        try fixture.environment.record("swift-d")
    }

    // MARK: - (e)

    func testCaseETimeoutAndCancellationAgainstAWaitingServer() async throws
    {
        let fixture = try await Fixture.start(timeoutMillis: 400)

        _ = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "warm up", sequence: 1))

        try await fixture.control.hold(
            path: SPFNGeneratedOperations.echoSend.path,
            millis: Self.holdMillis,
            count: 1
        )
        do
        {
            _ = try await fixture.client.execute(Calls.echo, request: SPFNEchoRequest(message: "too slow", sequence: 2))
            XCTFail("a held request must not answer inside the deadline")
        }
        catch SPFNClientError.transport(let error)
        {
            XCTAssertEqual(error, .timedOut)
        }

        try await fixture.control.hold(
            path: SPFNGeneratedOperations.echoSend.path,
            millis: Self.holdMillis,
            count: 1
        )
        try await assertCancellationStopsTheCall(fixture)

        try fixture.environment.record("swift-e")
    }

    private func assertCancellationStopsTheCall(_ fixture: Fixture) async throws
    {
        let client = fixture.client
        let startedAt = Date()
        let call = Task
        {
            try await client.execute(Calls.echo, request: SPFNEchoRequest(message: "give up", sequence: 3))
        }

        try await Task.sleep(nanoseconds: 200_000_000)
        call.cancel()

        do
        {
            _ = try await call.value
            XCTFail("a cancelled call must not answer")
        }
        catch
        {
            let elapsedMillis = Int64(Date().timeIntervalSince(startedAt) * 1000)
            XCTAssertLessThan(
                elapsedMillis,
                Self.holdMillis,
                "cancellation waited for the server instead of stopping the call"
            )
            let stopped = error is CancellationError ||
                (error as? SPFNClientError) == SPFNClientError.transport(.cancelled)
            XCTAssertTrue(stopped, "expected a cancellation, got \(error)")
        }
    }

    // MARK: - Fixture

    /// One SDK client pointed at the running reference server, with the server reset.
    private struct Fixture: Sendable
    {
        let environment: SPFNIntegrationEnvironment
        let control: SPFNReferenceControlClient
        let transport: any SPFNTransport
        let session: SPFNSession
        let client: SPFNClient

        static func start(timeoutMillis: Int64 = 5_000) async throws -> Fixture
        {
            let environment = try SPFNIntegrationEnvironment.current()
            let control = SPFNReferenceControlClient(environment: environment)

            // Reset first: a case that revoked a key must not decide the outcome of the
            // case that runs after it.
            try await control.reset()

            guard let privateKeyDer = Data(base64Encoded: SPFNReferenceIntegrationTests.privateKeyPkcs8B64)
            else
            {
                throw SPFNIntegrationFailure.control("the test private key constant is not base64")
            }

            let transport = SPFNURLSessionTransport()
            let session = SPFNSession(
                transport: transport,
                keyProvider: try SPFNSoftwareKeyProvider(
                    clientID: SPFNReferenceIntegrationTests.clientID,
                    keyID: SPFNReferenceIntegrationTests.keyID,
                    privateKeyDer: [UInt8](privateKeyDer)
                ),
                baseURL: environment.baseURL,
                timeoutMillis: timeoutMillis
            )
            return Fixture(
                environment: environment,
                control: control,
                transport: transport,
                session: session,
                client: SPFNClient(transport: transport, session: session, timeoutMillis: timeoutMillis)
            )
        }
    }

    /// The call descriptors the contract generator does not emit yet.
    private enum Calls
    {
        static let echo = SPFNCall<SPFNEchoRequest, SPFNEchoResponse>(
            operation: SPFNGeneratedOperations.echoSend,
            encode: { $0.canonicalValue },
            decode: { try SPFNEchoResponse(canonical: $0) }
        )

        static let listItems = SPFNCall<SPFNListItemsRequest, SPFNListItemsResponse>(
            operation: SPFNGeneratedOperations.itemsList,
            encode: { $0.canonicalValue },
            decode: { try SPFNListItemsResponse(canonical: $0) }
        )
    }
}
