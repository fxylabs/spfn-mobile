// SPFN Mobile — the transport contract, independent of any HTTP stack.
//
// A test double implements the protocol so the contract the layers above depend on —
// one call per execute, a non-2xx is a value, an error is one of four cases — is pinned
// without URLSession in the picture. SpfnTransportContractTest.kt is the counterpart.

import Foundation
import XCTest
import SPFNClient

/// A transport that answers from a script and counts what it was asked.
actor RecordingTransport: SPFNTransport
{
    private let outcome: Result<SPFNTransportResponse, SPFNTransportError>
    private(set) var received: [SPFNTransportRequest] = []

    init(_ outcome: Result<SPFNTransportResponse, SPFNTransportError>)
    {
        self.outcome = outcome
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        received.append(request)
        return try outcome.get()
    }
}

final class SPFNTransportContractTests: XCTestCase
{
    private func request() -> SPFNTransportRequest
    {
        SPFNTransportRequest(
            method: "POST",
            url: "https://example.invalid/v1/thing",
            headers: [("X-Spfn-Client", "c1")],
            body: [0x7B, 0x7D],
            timeoutMillis: 5_000
        )
    }

    func testNonSuccessStatusIsReturnedNotThrown() async throws
    {
        let transport = RecordingTransport(.success(
            SPFNTransportResponse(statusCode: 401, headers: [("WWW-Authenticate", "spfn")], body: [])
        ))

        let response = try await (transport as any SPFNTransport).execute(request())

        XCTAssertEqual(response.statusCode, 401)
        XCTAssertTrue(response.body.isEmpty)
    }

    func testEachExecuteSendsExactlyOneRequest() async
    {
        let transport = RecordingTransport(.failure(.connectivity("URLError -1004")))

        for _ in 0 ..< 3
        {
            _ = try? await transport.execute(request())
        }

        let received = await transport.received
        XCTAssertEqual(received.count, 3, "the transport must not retry on its own")
    }

    func testEveryFailureIsOneOfTheFourCases() async
    {
        let cases: [SPFNTransportError] = [
            .connectivity("URLError -1004"),
            .timedOut,
            .cancelled,
            .invalidResponse("response is not an HTTP response"),
        ]

        for expected in cases
        {
            let transport = RecordingTransport(.failure(expected))
            do
            {
                _ = try await transport.execute(request())
                XCTFail("expected \(expected)")
            }
            catch
            {
                XCTAssertEqual(error as? SPFNTransportError, expected)
            }
        }

        XCTAssertNotEqual(SPFNTransportError.connectivity("a"), .connectivity("b"))
        XCTAssertNotEqual(SPFNTransportError.timedOut, .cancelled)
    }

    func testRequestReachesTheTransportUnchanged() async throws
    {
        let transport = RecordingTransport(.success(
            SPFNTransportResponse(statusCode: 200, headers: [], body: [])
        ))

        _ = try await transport.execute(request())

        let recorded = await transport.received
        let received = try XCTUnwrap(recorded.first)
        XCTAssertEqual(received.method, "POST")
        XCTAssertEqual(received.url, "https://example.invalid/v1/thing")
        XCTAssertEqual(received.headers.map(\.0), ["X-Spfn-Client"])
        XCTAssertEqual(received.body, [0x7B, 0x7D])
        XCTAssertEqual(received.timeoutMillis, 5_000)
    }
}
