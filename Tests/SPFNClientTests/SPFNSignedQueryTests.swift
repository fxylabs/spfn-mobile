// SPFN Mobile — a proven operation with a query signs its path and sends path + query.
//
// The server verifies the path it routed — Hono's `c.req.path`, which carries no query —
// so the proof input is `operation.path` and the query rides only on the URL. The
// expected bytes are the `queryVectors` of Contracts/fixtures/request/wire.json, derived
// by Contracts/fixtures/derive-expected-values.py rather than by either SDK.
//
// SpfnSignedQueryTest.kt reads the same vectors and asserts the same things.

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore

final class SPFNSignedQueryTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    func testAProvenGetWithAQueryMatchesTheWireVector() async throws
    {
        try await assertSentAsRecorded(try queryVector("proven-get-with-query"))
    }

    /// `a b&c` is encoded on the URL and never signed; a nil value leaves no `name=`.
    func testAnEncodedQueryAndANilValueMatchTheWireVector() async throws
    {
        try await assertSentAsRecorded(try queryVector("proven-get-with-encoded-query"))
    }

    /// The proof input of a call with a query is byte for byte the one its bare path
    /// would sign, and a proof over it does not verify over path + query.
    func testTheProofInputIsTheBarePath() async throws
    {
        let vector = try queryVector("proven-get-with-query")
        let sent = try await sendOnce(vector)
        let input = try proofInput(vector, path: try vector.text("path"), headers: sent.headers)
        let withQuery = try proofInput(vector, path: try vector.text("pathAndQuery"), headers: sent.headers)

        XCTAssertEqual(try SPFNClientProof.canonicalString(for: input), try vector.text("canonicalString"))
        XCTAssertThrowsError(
            try SPFNClientProof.verify(
                presented: try XCTUnwrap(header(SPFNWireHeaders.proof, in: sent)),
                for: withQuery,
                publicKeySpkiDer: try ExecuteFixtures.fixturePublicKeySpkiDer()
            )
        )
    }

    /// The one retry after PROOF_EXPIRED re-sends the same URL, query included, under a
    /// new nonce and a new proof over the same bare path.
    func testTheRetryAfterAnExpiredProofResendsTheQueryUnderAFreshProof() async throws
    {
        let vector = try queryVector("proven-get-with-query")
        let clock = AnchoringProofClock(SessionFixtureValues.issuedAtMillis)
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_EXPIRED"))),
            .success(.json(200, "{}")),
        ])

        _ = try await client(transport, clock: clock, nonces: []).execute(try call(vector), request: ())

        XCTAssertEqual(clock.synchronizations, 2, "the discarded anchor was fetched again")
        let recorded = await transport.received
        XCTAssertEqual(recorded.map(\.url), Array(repeating: baseURL + (try vector.text("pathAndQuery")), count: 2))
        XCTAssertNotEqual(header(SPFNWireHeaders.nonce, in: recorded[0]), header(SPFNWireHeaders.nonce, in: recorded[1]))
        for sent in recorded
        {
            XCTAssertNoThrow(
                try SPFNClientProof.verify(
                    presented: try XCTUnwrap(header(SPFNWireHeaders.proof, in: sent)),
                    for: try proofInput(vector, path: try vector.text("path"), headers: sent.headers),
                    publicKeySpkiDer: try ExecuteFixtures.fixturePublicKeySpkiDer()
                )
            )
        }
    }

    func testACallWithoutAQueryIsSentToItsPathAlone()
    {
        let call = SPFNCall<Void, SPFNCanonicalValue>(
            operation: SPFNOperation(
                id: "getThing",
                method: "GET",
                path: "/v1/things",
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            query: [("cursor", nil)],
            encode: { _ in nil },
            decode: { $0 }
        )

        XCTAssertEqual(call.pathAndQuery, "/v1/things")
    }

    // MARK: - Driving one vector

    private func assertSentAsRecorded(_ vector: [String: SPFNCanonicalValue]) async throws
    {
        let sent = try await sendOnce(vector)

        XCTAssertEqual(sent.method, try vector.text("method"))
        XCTAssertEqual(sent.url, baseURL + (try vector.text("pathAndQuery")))
        XCTAssertNil(sent.body, "a GET with no request type sends no body")
        try assertHeadersMatchWireVector(sent.headers, expected: try vector.headerPairs("headers"), vector: vector)
    }

    private func sendOnce(_ vector: [String: SPFNCanonicalValue]) async throws -> SPFNTransportRequest
    {
        let issued = try issuedValues(try vector.headerPairs("headers"))
        let transport = ScriptedTransport([.success(.json(200, "{}"))])

        _ = try await client(transport, clock: FakeClock(issued.millis), nonces: [issued.nonce])
            .execute(try call(vector), request: ())

        let recorded = await transport.received
        XCTAssertEqual(recorded.count, 1, "no handshake: the operation requires no session")
        return try XCTUnwrap(recorded.first)
    }

    /// The call a generated app-contract client would build for the vector's operation.
    private func call(_ vector: [String: SPFNCanonicalValue]) throws -> SPFNCall<Void, SPFNCanonicalValue>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: try vector.text("operationId"),
                method: try vector.text("method"),
                path: try vector.text("path"),
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            query: try vector.list("query").map { pair in
                let fields = try pair.array()
                return (try fields[0].string(), fields[1] == .null ? nil : try fields[1].string())
            },
            encode: { _ in nil },
            decode: { $0 }
        )
    }

    private func client(_ transport: ScriptedTransport, clock: any SPFNProofClock, nonces: [String]) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: try SPFNSession(
                transport: transport,
                keyProvider: try ExecuteFixtures.syntheticProvider(),
                baseURL: baseURL,
                clock: clock,
                nonceGenerator: ScriptedNonceGenerator(nonces)
            )
        )
    }

    // MARK: - Reading the vector

    private func queryVector(_ name: String) throws -> [String: SPFNCanonicalValue]
    {
        for value in try WireFixtures.wire().list("queryVectors") where (try? value.object().text("name")) == name
        {
            return try value.object()
        }
        throw FixtureFailure.missing("query vector '\(name)'")
    }

    private func proofInput(
        _ vector: [String: SPFNCanonicalValue],
        path: String,
        headers: [(String, String)]
    ) throws -> SPFNProofInput
    {
        let byName = Dictionary(uniqueKeysWithValues: headers)
        return SPFNProofInput(
            method: try vector.text("method"),
            path: path,
            clientID: try XCTUnwrap(byName[SPFNWireHeaders.clientID]),
            keyID: try XCTUnwrap(byName[SPFNWireHeaders.keyID]),
            nonce: try XCTUnwrap(byName[SPFNWireHeaders.nonce]),
            issuedAtMillis: try XCTUnwrap(Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis]))),
            bodySha256: try vector.text("bodySha256")
        )
    }

    private func issuedValues(_ headers: [(String, String)]) throws -> (nonce: String, millis: Int64)
    {
        let byName = Dictionary(uniqueKeysWithValues: headers)
        let nonce = try XCTUnwrap(byName[SPFNWireHeaders.nonce])
        let millis = try XCTUnwrap(Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis])))
        return (nonce, millis)
    }

    private func header(_ name: String, in request: SPFNTransportRequest) -> String?
    {
        request.headers.first { $0.0 == name }?.1
    }
}
