// SPFN Mobile — reading the wire vectors and the bundle they came from.
//
// The Kotlin suite under android/spfn-client/src/test/kotlin reads the SAME files from
// the SAME directory, so a header name cannot drift on one platform without the other
// noticing. Files are read through the SDK's own strict parser, so loading the evidence
// exercises the thing being tested.

import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import XCTest

enum WireFixtures
{
    /// Repository root, located relative to this source file so the suite works from
    /// any working directory.
    static let repoRoot: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNClientTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repo root

    static func load(_ relativePath: String) throws -> SPFNCanonicalValue
    {
        let data = try Data(contentsOf: repoRoot.appendingPathComponent(relativePath))
        return try SPFNCanonicalJSON.parse([UInt8](data))
    }

    /// `Contracts/fixtures/request/wire.json`, the fully assembled requests.
    static func wire() throws -> [String: SPFNCanonicalValue]
    {
        try load("Contracts/fixtures/request/wire.json").object()
    }

    /// One named vector out of the wire fixture.
    static func vector(_ name: String) throws -> [String: SPFNCanonicalValue]
    {
        let vectors = try wire()["vectors"].orFail("vectors").array()
        for value in vectors where (try? value.object()["name"]?.string()) == name
        {
            return try value.object()
        }
        throw FixtureFailure.missing("wire vector '\(name)'")
    }

    /// `wireMapping` out of the pinned contract bundle itself, so the constants the SDK
    /// compiles against can be checked against the contract rather than against a copy.
    static func bundleWireMapping() throws -> [String: SPFNCanonicalValue]
    {
        try load("Contracts/spfn-mobile-contract.json").object()["wireMapping"]
            .orFail("wireMapping")
            .object()
    }
}

/// Every header must equal the fixture byte for byte except the proof: an ECDSA signer
/// draws a random nonce, so the SDK's proof cannot be pinned. It is judged by
/// verification instead — over the exact proof input the vector pins, under the fixture
/// public key — and the fixture's own recorded proof must verify the same way, which
/// proves this platform's verifier accepts a signature produced outside either SDK.
func assertHeadersMatchWireVector(
    _ sent: [(String, String)],
    expected: [(String, String)],
    vector: [String: SPFNCanonicalValue],
    file: StaticString = #filePath,
    line: UInt = #line
) throws
{
    XCTAssertEqual(sent.map(\.0), expected.map(\.0), "header names or order differ", file: file, line: line)
    for (sentPair, expectedPair) in zip(sent, expected) where sentPair.0 != SPFNWireHeaders.proof
    {
        XCTAssertEqual(sentPair.1, expectedPair.1, "header '\(sentPair.0)' differs", file: file, line: line)
    }

    let byName = Dictionary(uniqueKeysWithValues: sent)
    let sentProof = try XCTUnwrap(byName[SPFNWireHeaders.proof], file: file, line: line)
    let input = SPFNProofInput(
        method: try vector.text("method"),
        path: try vector.text("path"),
        clientID: try XCTUnwrap(byName[SPFNWireHeaders.clientID], file: file, line: line),
        keyID: try XCTUnwrap(byName[SPFNWireHeaders.keyID], file: file, line: line),
        nonce: try XCTUnwrap(byName[SPFNWireHeaders.nonce], file: file, line: line),
        issuedAtMillis: try XCTUnwrap(
            Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis], file: file, line: line)),
            file: file,
            line: line
        ),
        bodySha256: try vector.text("bodySha256")
    )
    let publicKey = try ExecuteFixtures.fixturePublicKeySpkiDer()

    XCTAssertNoThrow(
        try SPFNClientProof.verify(presented: sentProof, for: input, publicKeySpkiDer: publicKey),
        "the SDK's own proof does not verify under the fixture public key",
        file: file,
        line: line
    )
    XCTAssertNoThrow(
        try SPFNClientProof.verify(presented: try vector.text("proof"), for: input, publicKeySpkiDer: publicKey),
        "the fixture's recorded proof does not verify on this platform",
        file: file,
        line: line
    )
}

enum FixtureFailure: Error, Equatable
{
    case shape(String)
    case missing(String)
}

extension Optional where Wrapped == SPFNCanonicalValue
{
    func orFail(_ name: String) throws -> SPFNCanonicalValue
    {
        guard let self
        else
        {
            throw FixtureFailure.missing(name)
        }
        return self
    }
}

extension SPFNCanonicalValue
{
    func object() throws -> [String: SPFNCanonicalValue]
    {
        guard case .object(let members) = self
        else
        {
            throw FixtureFailure.shape("expected an object")
        }
        return members
    }

    func array() throws -> [SPFNCanonicalValue]
    {
        guard case .array(let elements) = self
        else
        {
            throw FixtureFailure.shape("expected an array")
        }
        return elements
    }

    func string() throws -> String
    {
        guard case .string(let text) = self
        else
        {
            throw FixtureFailure.shape("expected a string")
        }
        return text
    }

    func integer() throws -> Int64
    {
        guard case .integer(let number) = self
        else
        {
            throw FixtureFailure.shape("expected an integer")
        }
        return number
    }
}

extension Dictionary where Key == String, Value == SPFNCanonicalValue
{
    func text(_ key: String) throws -> String
    {
        try self[key].orFail(key).string()
    }

    func number(_ key: String) throws -> Int64
    {
        try self[key].orFail(key).integer()
    }

    func list(_ key: String) throws -> [SPFNCanonicalValue]
    {
        try self[key].orFail(key).array()
    }

    /// A fixture's `headers` array, as the ordered pairs the transport takes.
    func headerPairs(_ key: String) throws -> [(String, String)]
    {
        try list(key).map { pair in
            let fields = try pair.array()
            guard fields.count == 2
            else
            {
                throw FixtureFailure.shape("a header entry must be [name, value]")
            }
            return (try fields[0].string(), try fields[1].string())
        }
    }
}
