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

    /// Every code carries the surface the contract puts it on.
    ///
    /// The two sets arrive in one list and are not interchangeable: a proven call can be
    /// met by a `clientProofV1` refusal and never by a `rest` one. A generator that read
    /// the list as one set would build a refusal enum with twelve members that cannot
    /// occur on the surface it guards, and nothing downstream would notice.
    func testEveryCodeCarriesTheSurfaceTheContractPutsItOn() throws
    {
        let fixture = try Fixtures.load("error/envelopes.json").members()
        let known = try fixture.list("known")
        XCTAssertFalse(known.isEmpty)

        for vector in known
        {
            let entry = try vector.members()
            let raw = try entry.text("code")
            let expectedSurface = try entry.text("surface")
            let expectedRetryable = try entry.bool("retryable")
            let code = try SPFNGeneratedErrorCode.decode(raw)

            XCTAssertEqual(code.surface.rawValue, expectedSurface, "surface differs for '\(raw)'")
            XCTAssertEqual(code.isRetryable, expectedRetryable, "retryable differs for '\(raw)'")
        }
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
