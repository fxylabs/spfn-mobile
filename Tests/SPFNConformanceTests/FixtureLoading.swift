// SPFN Mobile — shared fixture loading for the Swift conformance suite.
//
// The Kotlin suite under android/spfn-auth/src/test/kotlin reads the SAME files from
// the SAME directory. Neither suite carries its own copy of an expected value, so a
// vector cannot drift on one platform without the other noticing.

import Foundation
import SPFNCore
import XCTest

enum Fixtures
{
    /// Repository root, located relative to this source file so the suite works from
    /// any working directory.
    static let repoRoot: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNConformanceTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repo root

    static let directory: URL = repoRoot.appendingPathComponent("Contracts/fixtures")

    /// Loads a fixture through the SDK's own strict parser, so reading the evidence
    /// exercises the thing being tested.
    static func load(_ relativePath: String) throws -> SPFNCanonicalValue
    {
        let data = try Data(contentsOf: directory.appendingPathComponent(relativePath))
        return try SPFNCanonicalJSON.parse([UInt8](data))
    }
}

// Small readers so the test bodies stay about the contract rather than about optionals.
extension SPFNCanonicalValue
{
    func members(file: StaticString = #filePath, line: UInt = #line) throws -> [String: SPFNCanonicalValue]
    {
        guard case .object(let members) = self
        else
        {
            XCTFail("expected an object, got \(self)", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return members
    }

    func elements(file: StaticString = #filePath, line: UInt = #line) throws -> [SPFNCanonicalValue]
    {
        guard case .array(let elements) = self
        else
        {
            XCTFail("expected an array, got \(self)", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return elements
    }

    func text(file: StaticString = #filePath, line: UInt = #line) throws -> String
    {
        guard case .string(let value) = self
        else
        {
            XCTFail("expected a string, got \(self)", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return value
    }

    func number(file: StaticString = #filePath, line: UInt = #line) throws -> Int64
    {
        guard case .integer(let value) = self
        else
        {
            XCTFail("expected an integer, got \(self)", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return value
    }
}

extension Dictionary where Key == String, Value == SPFNCanonicalValue
{
    func text(_ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> String
    {
        guard let value = self[key]
        else
        {
            XCTFail("fixture is missing '\(key)'", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return try value.text(file: file, line: line)
    }

    func number(_ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> Int64
    {
        guard let value = self[key]
        else
        {
            XCTFail("fixture is missing '\(key)'", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return try value.number(file: file, line: line)
    }

    func list(_ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> [SPFNCanonicalValue]
    {
        guard let value = self[key]
        else
        {
            XCTFail("fixture is missing '\(key)'", file: file, line: line)
            throw ConformanceFailure.shape
        }
        return try value.elements(file: file, line: line)
    }
}

enum ConformanceFailure: Error
{
    case shape
}
