// SPFN Mobile — canonical JSON conformance, Swift half of the parity gate.

import SPFNCore
import XCTest

final class CanonicalConformanceTests: XCTestCase
{
    func testCanonicalSerializationVectors() throws
    {
        let fixture = try Fixtures.load("canonical/serialization.json").members()
        let vectors = try fixture.list("vectors")
        XCTAssertFalse(vectors.isEmpty, "the fixture must carry vectors")

        for vector in vectors
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let parsed = try SPFNCanonicalJSON.parse(try entry.text("input"))
            let encoded = SPFNCanonicalJSON.encodeToString(parsed)

            XCTAssertEqual(encoded, try entry.text("canonical"), "canonical bytes differ for '\(name)'")
            XCTAssertEqual(
                SPFNDigest.sha256Hex(Array(encoded.utf8)),
                try entry.text("sha256"),
                "canonical digest differs for '\(name)'"
            )
        }
    }

    func testCanonicalFormIsIdempotent() throws
    {
        let fixture = try Fixtures.load("canonical/serialization.json").members()
        for vector in try fixture.list("vectors")
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let once = SPFNCanonicalJSON.encodeToString(try SPFNCanonicalJSON.parse(try entry.text("input")))
            let twice = SPFNCanonicalJSON.encodeToString(try SPFNCanonicalJSON.parse(once))
            XCTAssertEqual(once, twice, "canonicalizing a canonical form changed it for '\(name)'")
        }
    }

    func testRejectedInputsFailWithTheNamedCode() throws
    {
        let fixture = try Fixtures.load("canonical/rejects.json").members()
        let vectors = try fixture.list("vectors")
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let expected = try entry.text("errorCode")

            XCTAssertThrowsError(try SPFNCanonicalJSON.parse(try entry.text("input")), "'\(name)' was accepted")
            { error in
                guard let canonicalError = error as? SPFNCanonicalError
                else
                {
                    return XCTFail("'\(name)' threw \(error), not an SPFNCanonicalError")
                }
                XCTAssertEqual(canonicalError.code, expected, "'\(name)' reported the wrong code")
            }
        }
    }
}
