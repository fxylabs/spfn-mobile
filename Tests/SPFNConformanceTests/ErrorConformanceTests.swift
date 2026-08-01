// SPFN Mobile — error model conformance, Swift half of the parity gate.

import SPFNCore
import SPFNGenerated
import XCTest

final class ErrorConformanceTests: XCTestCase
{
    func testKnownErrorEnvelopesDecode() throws
    {
        let fixture = try Fixtures.load("error/envelopes.json").members()
        let known = try fixture.list("known")
        XCTAssertFalse(known.isEmpty)

        for vector in known
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let envelope = try SPFNErrorEnvelope.decode(try SPFNCanonicalJSON.parse(try entry.text("wire")))
            let code = try SPFNGeneratedErrorCode.decode(envelope.code)

            XCTAssertEqual(envelope.code, try entry.text("code"), "code differs for '\(name)'")
            XCTAssertEqual(Int64(code.httpStatus), try entry.number("httpStatus"), "status differs for '\(name)'")
            XCTAssertEqual(
                SPFNCanonicalJSON.encodeToString(envelope.canonicalValue),
                try entry.text("wire"),
                "re-encoding the envelope changed it for '\(name)'"
            )
            XCTAssertEqual(
                SPFNDigest.sha256Hex(SPFNCanonicalJSON.encode(envelope.canonicalValue)),
                try entry.text("sha256"),
                "envelope digest differs for '\(name)'"
            )
        }
    }

    func testEveryContractErrorCodeIsGenerated() throws
    {
        let fixture = try Fixtures.load("error/envelopes.json").members()
        let expected = try fixture.list("known").map { try $0.members().text("code") }.sorted()
        XCTAssertEqual(SPFNGeneratedErrorCode.allCases.map(\.rawValue).sorted(), expected)
    }

    func testUnknownErrorCodeIsRejectedRatherThanMapped() throws
    {
        let fixture = try Fixtures.load("error/envelopes.json").members()
        for vector in try fixture.list("rejected")
        {
            let entry = try vector.members()
            let envelope = try SPFNErrorEnvelope.decode(try SPFNCanonicalJSON.parse(try entry.text("wire")))
            XCTAssertEqual(envelope.code, try entry.text("rawCode"))

            XCTAssertThrowsError(try SPFNGeneratedErrorCode.decode(envelope.code))
            { error in
                guard case SPFNDecodingError.unknownErrorCode(let raw) = error
                else
                {
                    return XCTFail("expected unknownErrorCode, got \(error)")
                }
                XCTAssertEqual(raw, try? entry.text("rawCode"), "the raw code must survive the failure")
            }
        }
    }
}
