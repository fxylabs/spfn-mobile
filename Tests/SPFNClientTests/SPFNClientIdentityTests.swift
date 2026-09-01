// SPFN Mobile — the client says who it is, and reads what the server says back.
//
// Ten cells, closed before the code was written and named here one to one: three for the
// paths a request leaves by, seven for what a response's announcement earns. The request
// cells exist because there are three exits and only two of them go through
// `SPFNClient.execute` — a change that covers execute alone opens every session
// anonymously, and S3 is the cell that fails when it does.
//
// SpfnClientIdentityTest.kt is the counterpart and uses the same cell names.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNClientIdentityTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    // MARK: - S: what a request says about itself

    /// S1. The unproven class — enrolment and login — carries the identity. The contract
    /// applies it to every operation "proven or not", and names this path as the one
    /// where a stale client is met first.
    func testS1UnprovenRequestCarriesTheIdentity() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, ExecuteFixtures.registerResponseBody))])
        let client = try makeClient(transport)

        _ = try await client.execute(ExecuteCalls.register, request: ExecuteFixtures.registerRequest) as SPFNRegisterResponse

        try assertCarriesIdentity(await transport.received.first)
    }

    /// S2. A proven operation carries it alongside the proof headers.
    func testS2ProvenRequestCarriesTheIdentity() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport, nonces: ["nonce-open", "nonce-echo"])

        _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) as SPFNEchoResponse

        let recorded = await transport.received
        try assertCarriesIdentity(recorded.last)
    }

    /// S3. The handshake leaves through the session, not through `execute`. This is the
    /// cell that fails if the identity is added only where execute sends.
    func testS3HandshakeCarriesTheIdentity() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
            .success(.json(200, ExecuteFixtures.echoResponseBody)),
        ])
        let client = try makeClient(transport, nonces: ["nonce-open", "nonce-echo"])

        _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) as SPFNEchoResponse

        let recorded = await transport.received
        XCTAssertEqual(
            recorded.first?.url,
            baseURL + SPFNGeneratedOperations.authClientProofHandshake.path,
            "the first request is the handshake"
        )
        try assertCarriesIdentity(recorded.first)
    }

    // MARK: - R: what a response's announcement earns

    /// R1. No announcement is refused. Contract 0.8.0 puts it on every response, so its
    /// absence is a server older than the mechanism or something that removed it.
    func testR1AnUnannouncedResponseIsRefused() async throws
    {
        let thrown = try await registerFailing(
            with: answer(200, ExecuteFixtures.registerResponseBody, announcing: [])
        )

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .contract(SPFNContractMismatch(
                reason: .unannounced,
                serverVersion: nil,
                admittedRange: SPFNGeneratedContract.binding.admittedRange
            ))
        )
    }

    /// R2. The version this build was generated from is inside the window, and the call
    /// returns its answer.
    func testR2AnAnnouncementInsideTheWindowIsRead() async throws
    {
        let transport = ScriptedTransport([
            .success(answer(
                200,
                ExecuteFixtures.registerResponseBody,
                announcing: announcement(SPFNGeneratedContract.binding.importedVersion)
            )),
        ])
        let client = try makeClient(transport)

        let registered: SPFNRegisterResponse = try await client.execute(
            ExecuteCalls.register,
            request: ExecuteFixtures.registerRequest
        )

        XCTAssertEqual(registered, ExecuteFixtures.registerResponse)
    }

    /// R3. A server ahead of this build. Its version is carried, because it parsed.
    func testR3AServerAheadOfThisBuildIsRefusedAndNamed() async throws
    {
        try await assertOutsideWindow(announced: Self.aheadOfTheWindow)
    }

    /// R4. A server behind this build. The case only this side can catch: the server's
    /// own gate arrived in 0.8.0, so a server below that has no gate to refuse with.
    func testR4AServerBehindThisBuildIsRefusedAndNamed() async throws
    {
        try await assertOutsideWindow(announced: "0.7.0")
    }

    /// R5. An announcement that is not a version carries nothing into the failure. It is
    /// text the responder chose, and an error value reaches logs and crash reports.
    func testR5AnUnreadableAnnouncementIsRefusedAndCarriesNothing() async throws
    {
        let thrown = try await registerFailing(
            with: answer(
                200,
                ExecuteFixtures.registerResponseBody,
                announcing: announcement("0.8; DROP TABLE users")
            )
        )

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .contract(SPFNContractMismatch(
                reason: .unreadable,
                serverVersion: nil,
                admittedRange: SPFNGeneratedContract.binding.admittedRange
            ))
        )
    }

    /// R6. The server refuses on contract grounds and announces its version on that
    /// refusal. Classifying the refusal first would keep `.server(.contractUnsupported)`
    /// and throw away the two numbers that say which end is stale.
    func testR6AContractRefusalIsReportedAsAVersionGapNotAsAServerFailure() async throws
    {
        let thrown = try await registerFailing(
            with: answer(
                409,
                ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"),
                announcing: announcement(Self.aheadOfTheWindow)
            )
        )

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .contract(SPFNContractMismatch(
                reason: .outsideAdmittedRange,
                serverVersion: Self.aheadOfTheWindow,
                admittedRange: SPFNGeneratedContract.binding.admittedRange
            ))
        )
    }

    /// R7. The handshake is the second place a response is read, and it is read by the
    /// session rather than by `execute`. It refuses with the same type.
    func testR7AnUnannouncedHandshakeResponseIsRefused() async throws
    {
        let transport = ScriptedTransport([
            .success(answer(200, SessionFixtureValues.handshakeResponseBody, announcing: [])),
        ])
        let client = try makeClient(transport, nonces: ["nonce-open"])

        do
        {
            _ = try await client.execute(ExecuteCalls.echo, request: ExecuteFixtures.echoRequest) as SPFNEchoResponse
            XCTFail("an unannounced handshake response was accepted")
        }
        catch
        {
            XCTAssertEqual(
                error as? SPFNClientError,
                .contract(SPFNContractMismatch(
                    reason: .unannounced,
                    serverVersion: nil,
                    admittedRange: SPFNGeneratedContract.binding.admittedRange
                ))
            )
        }
    }

    // MARK: - Reading the identity itself

    /// The kind and the contract version are what the server's gate judges, so neither is
    /// allowed to go missing however the app version turns out.
    func testTheIdentityAlwaysStatesAKindAndAContractVersion()
    {
        let byName = Dictionary(uniqueKeysWithValues: SPFNClientIdentity.headers)

        XCTAssertEqual(byName[SPFNWireHeaders.clientKind], "ios")
        XCTAssertEqual(
            byName[SPFNWireHeaders.clientContractVersion],
            SPFNGeneratedContract.binding.importedVersion
        )
        XCTAssertEqual(
            byName[SPFNWireHeaders.clientVersion],
            SPFNClientIdentity.appVersion,
            "the app version header is present exactly when the platform answered"
        )
    }

    // MARK: - Helpers

    /// The first version above the admitted window, computed from the pin rather than
    /// written down. The rule is the contract's own — `upstream.lock.json`'s `rangeRule`:
    /// on a `0.x` line the breaking axis is the minor, above it the major — so this is the
    /// smallest version the window cannot admit.
    ///
    /// A literal here rots silently: these cells were written at the `0.4.1` pin, where
    /// `0.9.0` meant "ahead"; at the `0.9.0` pin the same literal sits inside the window
    /// and the cell asserts nothing. Derived, it moves with the pin.
    static var aheadOfTheWindow: String
    {
        let binding = SPFNGeneratedContract.binding
        return binding.supportedMajor == 0
            ? "0.\(binding.supportedMinor + 1).0"
            : "\(binding.supportedMajor + 1).0.0"
    }

    private func announcement(_ version: String) -> [(String, String)]
    {
        [
            (SPFNWireHeaders.serverContractVersion, version),
            (SPFNWireHeaders.supportedContractRange, SPFNGeneratedContract.binding.supportedRange),
        ]
    }

    /// A response built here rather than through `.json`, which always announces.
    private func answer(
        _ statusCode: Int,
        _ body: String,
        announcing: [(String, String)]
    ) -> SPFNTransportResponse
    {
        SPFNTransportResponse(
            statusCode: statusCode,
            headers: [(SPFNWireHeaders.contentType, SPFNWireHeaders.requestContentType)] + announcing,
            body: Array(body.utf8)
        )
    }

    private func assertOutsideWindow(
        announced: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        let thrown = try await registerFailing(
            with: answer(200, ExecuteFixtures.registerResponseBody, announcing: announcement(announced))
        )

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .contract(SPFNContractMismatch(
                reason: .outsideAdmittedRange,
                serverVersion: announced,
                admittedRange: SPFNGeneratedContract.binding.admittedRange
            )),
            file: file,
            line: line
        )
    }

    /// The unproven path is used for the response cells because it sends exactly one
    /// request, so what comes back is judged without a handshake in the way.
    private func registerFailing(with response: SPFNTransportResponse) async throws -> (any Error)?
    {
        let transport = ScriptedTransport([.success(response)])
        let client = try makeClient(transport)

        do
        {
            _ = try await client.execute(
                ExecuteCalls.register,
                request: ExecuteFixtures.registerRequest
            ) as SPFNRegisterResponse
            XCTFail("the response was accepted")
            return nil
        }
        catch
        {
            return error
        }
    }

    private func assertCarriesIdentity(
        _ sent: SPFNTransportRequest?,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws
    {
        let request = try XCTUnwrap(sent, file: file, line: line)
        let byName = Dictionary(uniqueKeysWithValues: request.headers)

        XCTAssertEqual(byName[SPFNWireHeaders.clientKind], "ios", file: file, line: line)
        XCTAssertEqual(
            byName[SPFNWireHeaders.clientContractVersion],
            SPFNGeneratedContract.binding.importedVersion,
            file: file,
            line: line
        )
        XCTAssertEqual(
            byName[SPFNWireHeaders.clientVersion],
            SPFNClientIdentity.appVersion,
            file: file,
            line: line
        )
    }

    private func makeClient(
        _ transport: any SPFNTransport,
        nonces: [String] = []
    ) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: SPFNSession(
                transport: transport,
                keyProvider: try ExecuteFixtures.syntheticProvider(clientID: SessionFixtureValues.clientID),
                baseURL: baseURL,
                clock: FakeClock(SessionFixtureValues.issuedAtMillis),
                nonceGenerator: ScriptedNonceGenerator(nonces)
            ),
            timeoutMillis: 15_000
        )
    }
}
