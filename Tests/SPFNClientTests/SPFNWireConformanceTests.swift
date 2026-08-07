// SPFN Mobile — the wire mapping, checked against the contract and against the fixture.
//
// Two separate claims are made here, and neither rests on the other:
//
//   - the header names this SDK compiles against are the ones the pinned bundle's
//     `wireMapping` section states, so a renamed header fails a test run rather than
//     surfacing as a 401 against a real server;
//   - a session assembles exactly the bytes Contracts/fixtures/request/wire.json
//     records, and those expected bytes were produced by
//     Contracts/fixtures/derive-expected-values.py rather than by either SDK.
//
// SpfnWireConformanceTest.kt reads the same two files and asserts the same things.

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNWireConformanceTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    // MARK: - The constants agree with the contract

    func testHeaderNamesMatchTheBundleWireMapping() throws
    {
        let names = try WireFixtures.bundleWireMapping()["headers"].orFail("headers").object()

        var fromBundle: [String: String] = [:]
        for (field, value) in names
        {
            fromBundle[field] = try value.string()
        }

        XCTAssertEqual(fromBundle, SPFNWireHeaders.byContractField)
    }

    func testHeaderOrderMatchesTheBundleWireMapping() throws
    {
        let order = try WireFixtures.bundleWireMapping().list("headerOrder").map { try $0.string() }

        XCTAssertEqual(order, SPFNWireHeaders.contractFieldOrder)
    }

    func testRequestContentTypeMatchesTheBundleWireMapping() throws
    {
        XCTAssertEqual(
            try WireFixtures.bundleWireMapping().text("requestContentType"),
            SPFNWireHeaders.requestContentType
        )
    }

    /// The fixture and the generated operations have to agree about which operations
    /// carry a session, or the vectors would pin a rule the SDK never applies.
    func testEveryWireVectorAgreesWithItsGeneratedOperation() throws
    {
        for vector in try WireFixtures.wire().list("vectors").map({ try $0.object() })
        {
            let operation = try XCTUnwrap(SPFNGeneratedOperations.operation(id: try vector.text("operationId")))
            XCTAssertEqual(operation.method, try vector.text("method"))
            XCTAssertEqual(operation.path, try vector.text("path"))

            let carriesSession = try vector.headerPairs("headers").contains { $0.0 == SPFNWireHeaders.session }
            XCTAssertEqual(operation.requiresSession, carriesSession, "wireMapping.sessionRule")
        }
    }

    // MARK: - A session assembles the recorded bytes

    func testHandshakeMatchesTheWireVector() async throws
    {
        let vector = try WireFixtures.vector("handshake")
        let expected = try vector.headerPairs("headers")
        let issued = try issuedValues(expected)

        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
        ])
        let session = SPFNSession(
            transport: transport,
            keyProvider: try syntheticProvider(),
            baseURL: baseURL,
            clock: FakeClock(issued.millis),
            nonceGenerator: ScriptedNonceGenerator([issued.nonce])
        )

        _ = try await session.handshake()

        let recorded = await transport.received
        let sent = try XCTUnwrap(recorded.first)
        XCTAssertEqual(sent.method, try vector.text("method"))
        XCTAssertEqual(sent.url, baseURL + (try vector.text("path")))
        try assertHeadersMatchWireVector(sent.headers, expected: expected, vector: vector)
        XCTAssertEqual(
            String(decoding: sent.body ?? [], as: UTF8.self),
            try vector.text("canonicalBody")
        )
    }

    func testASessionOperationMatchesTheWireVector() async throws
    {
        let handshakeVector = try WireFixtures.vector("handshake")
        let vector = try WireFixtures.vector("echo-with-session")
        let expected = try vector.headerPairs("headers")
        let issued = try issuedValues(expected)
        let openingNonce = try issuedValues(try handshakeVector.headerPairs("headers")).nonce

        let transport = ScriptedTransport([
            .success(.json(200, SessionFixtureValues.handshakeResponseBody)),
        ])
        let session = SPFNSession(
            transport: transport,
            keyProvider: try syntheticProvider(),
            baseURL: baseURL,
            clock: FakeClock(issued.millis),
            // The handshake that opens the session consumes the first nonce; the request
            // the vector describes consumes the second.
            nonceGenerator: ScriptedNonceGenerator([openingNonce, issued.nonce])
        )

        let headers = try await session.proofHeaders(
            operation: SPFNGeneratedOperations.echoSend,
            canonicalBody: Array((try vector.text("canonicalBody")).utf8)
        )

        // `proofHeaders` on its own, so no identity: the identity is appended where the
        // request is handed to the transport, not where the proof is assembled.
        try assertHeadersMatchWireVector(headers, expected: expected, vector: vector, identity: [])
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "one handshake, then the request itself")
        XCTAssertEqual(try vector.text("sessionId"), SessionFixtureValues.sessionID)
    }

    // MARK: - Reading the vector

    private func issuedValues(_ headers: [(String, String)]) throws -> (nonce: String, millis: Int64)
    {
        let byName = Dictionary(uniqueKeysWithValues: headers)
        let nonce = try XCTUnwrap(byName[SPFNWireHeaders.nonce])
        let millis = try XCTUnwrap(Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis])))
        return (nonce, millis)
    }

    /// The fixture test keypair the vectors name. Read from the fixture rather than
    /// restated, so the suite cannot silently sign with something else.
    private func syntheticProvider() throws -> SPFNSoftwareKeyProvider
    {
        try ExecuteFixtures.syntheticProvider()
    }
}
