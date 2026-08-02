// SPFN Mobile — reading the wire vectors and the bundle they came from.
//
// The Kotlin suite under android/spfn-client/src/test/kotlin reads the SAME files from
// the SAME directory, so a header name cannot drift on one platform without the other
// noticing. Files are read through the SDK's own strict parser, so loading the evidence
// exercises the thing being tested.

import Foundation
import SPFNCore

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
        try load("Contracts/spfn-mobile-contract.v1.json").object()["wireMapping"]
            .orFail("wireMapping")
            .object()
    }
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
