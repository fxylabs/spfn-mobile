// SPFN Mobile — an operation that declares no request body.
//
// The pinned bundle states the rule in `clientProofV1.proofInput.bodySha256`: "the literal
// string of 64 zero characters when an operation has no body", and an operation has no body
// when it names no requestType — `core.time` and `auth.mfa.status`, both GET. The expected
// digest is read from Contracts/fixtures/proof/proof-input.json (`handshake-no-body`), the
// vector the outside implementation derived, rather than restated here.
//
// Five cells. Three send no body: a proven GET, an unproven GET and a DELETE. One is the
// regression guard — a declared body that happens to be empty is still `{}` and is still
// digested. The last maps a GET onto the URLRequest URLSession would send.
//
// SpfnBodylessRequestTest.kt is the counterpart and uses the same cell names. Its B5 sends
// through OkHttp to a local server; URLSession hands a stub protocol a stream rather than
// the body, so the mapping is where this platform can assert it.

import Foundation
import XCTest
import SPFNAuth
@testable import SPFNClient
import SPFNCore
import SPFNGenerated

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class SPFNBodylessRequestTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    /// B1. A proven GET: no bytes, no `content-type`, and a proof over the absent digest.
    func testB1ProvenGetSendsNoBodyAndSignsTheAbsentDigest() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, Self.mfaStatusBody))])

        _ = try await makeClient(transport).execute(SPFNGeneratedCalls.authMfaStatus, request: ())

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.method, "GET")
        assertNoBody(sent)
        try assertProofSigns(sent, SPFNGeneratedOperations.authMfaStatus, bodySha256: try Self.absentBodyDigest())
    }

    /// B2. An unproven GET carries no proof, and no body or `content-type` either.
    func testB2UnprovenGetSendsNoBody() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, "{\"serverTimeMillis\":1750000000000}"))])

        _ = try await makeClient(transport).execute(SPFNGeneratedCalls.coreTime, request: ())

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.method, "GET")
        assertNoBody(sent)
    }

    /// B3. The rule is the operation's missing request type, not the method: DELETE too.
    func testB3DeleteWithoutARequestTypeSendsNoBody() async throws
    {
        let transport = ScriptedTransport([.success(.json(204, ""))])

        _ = try await makeClient(transport).execute(Self.bodylessDelete, request: ())

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        assertNoBody(sent)
        try assertProofSigns(sent, Self.bodylessDelete.operation, bodySha256: try Self.absentBodyDigest())
    }

    /// B4. A declared body that happens to be empty is not "no body": `{}` goes out and is digested.
    func testB4DeclaredEmptyBodyStillSendsAnEmptyObject() async throws
    {
        let transport = ScriptedTransport([.success(.json(204, ""))])

        _ = try await makeClient(transport).execute(Self.emptyObjectPost, request: ())

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.body, Array("{}".utf8))
        XCTAssertEqual(header(sent, SPFNWireHeaders.contentType), SPFNWireHeaders.requestContentType)
        try assertProofSigns(sent, Self.emptyObjectPost.operation, bodySha256: SPFNDigest.sha256Hex("{}"))
    }

    /// B5. The GET as URLSession would send it: no `httpBody` and no `Content-Type`.
    func testB5GetMapsToAURLRequestWithoutABody() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, Self.mfaStatusBody))])

        _ = try await makeClient(transport).execute(SPFNGeneratedCalls.authMfaStatus, request: ())

        let received = await transport.received
        let mapped = try SPFNURLSessionTransport.urlRequest(from: try XCTUnwrap(received.first))
        XCTAssertEqual(mapped.httpMethod, "GET")
        XCTAssertNil(mapped.httpBody)
        XCTAssertNil(mapped.value(forHTTPHeaderField: "Content-Type"))
    }

    // MARK: - Helpers

    private func assertNoBody(_ sent: SPFNTransportRequest, file: StaticString = #filePath, line: UInt = #line)
    {
        XCTAssertNil(sent.body, "a bodyless operation sent body bytes", file: file, line: line)
        XCTAssertFalse(
            sent.headers.contains { $0.0.lowercased() == SPFNWireHeaders.contentType },
            "a bodyless operation sent a content-type",
            file: file,
            line: line
        )
    }

    /// The signer draws a random nonce, so the proof is verified rather than compared.
    private func assertProofSigns(
        _ sent: SPFNTransportRequest,
        _ operation: SPFNOperation,
        bodySha256: String
    ) throws
    {
        let input = SPFNProofInput(
            method: operation.method,
            path: operation.path,
            clientID: try XCTUnwrap(header(sent, SPFNWireHeaders.clientID)),
            keyID: try XCTUnwrap(header(sent, SPFNWireHeaders.keyID)),
            nonce: try XCTUnwrap(header(sent, SPFNWireHeaders.nonce)),
            issuedAtMillis: try XCTUnwrap(Int64(try XCTUnwrap(header(sent, SPFNWireHeaders.issuedAtMillis)))),
            bodySha256: bodySha256
        )
        XCTAssertNoThrow(
            try SPFNClientProof.verify(
                presented: try XCTUnwrap(header(sent, SPFNWireHeaders.proof)),
                for: input,
                publicKeySpkiDer: try ExecuteFixtures.fixturePublicKeySpkiDer()
            )
        )
    }

    private func header(_ sent: SPFNTransportRequest, _ name: String) -> String?
    {
        sent.headers.first { $0.0 == name }?.1
    }

    /// The digest the outside implementation signed for an operation with no body.
    private static func absentBodyDigest() throws -> String
    {
        let vectors = try WireFixtures.load("Contracts/fixtures/proof/proof-input.json").object().list("vectors")
        for value in vectors where (try? value.object().text("name")) == "handshake-no-body"
        {
            return try value.object()["input"].orFail("input").object().text("bodySha256")
        }
        throw FixtureFailure.missing("proof vector 'handshake-no-body'")
    }

    private func makeClient(_ transport: any SPFNTransport) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: try SPFNSession(
                transport: transport,
                keyProvider: try ExecuteFixtures.syntheticProvider(clientID: SessionFixtureValues.clientID),
                baseURL: baseURL,
                clock: FakeClock(SessionFixtureValues.issuedAtMillis),
                nonceGenerator: ScriptedNonceGenerator([])
            ),
            timeoutMillis: 15_000
        )
    }

    private static let mfaStatusBody = "{\"enrolled\":false,\"methods\":[],\"recoveryCodesRemaining\":0}"

    /// Hand-built: the contract has no bodyless DELETE yet, and an app contract may.
    private static let bodylessDelete = SPFNCall<Void, SPFNNoResponse>.noResponse(
        operation: SPFNOperation(
            id: "test.bodylessDelete",
            method: "DELETE",
            path: "/v1/things/t1",
            authProfile: "clientProofV1",
            requiresSession: false,
            declaresResponse: false
        ),
        encode: { _ in nil }
    )

    /// A POST whose declared body is an object with no members.
    private static let emptyObjectPost = SPFNCall<Void, SPFNNoResponse>.noResponse(
        operation: SPFNOperation(
            id: "test.emptyObjectPost",
            method: "POST",
            path: "/v1/things",
            authProfile: "clientProofV1",
            requiresSession: false,
            declaresResponse: false
        ),
        encode: { _ in .object([:]) }
    )
}
