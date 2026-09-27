// SPFN Mobile — the URLSession stream adapter against the URLProtocol stub (design §9-3).
//
// The stub hands the delegate its body in pieces, which is the shape a stream arrives in.
// What is held here is that the pieces arrive in order, that the request carries the
// stream's hardening — no cookie handling, the idle timeout rather than a deadline — and
// that the headers deadline is the only timeout the adapter judges itself (§8 H-5).
//
// The stub's redirect outcome traps inside swift-corelibs-foundation, so the redirect row
// runs on Apple platforms only (see StubURLProtocol).

import Foundation
import XCTest
@testable import SPFNClient

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class SPFNURLSessionStreamTransportTests: XCTestCase
{
    private func request(timeoutMillis: Int64 = 5_000) -> SPFNTransportRequest
    {
        SPFNTransportRequest(
            method: "GET",
            url: "https://example.invalid/events/stream?token=t&events=a",
            headers: [("accept", "text/event-stream")],
            timeoutMillis: timeoutMillis
        )
    }

    func test_adapter_deliversChunksInOrder() async throws
    {
        let body = ServerFrames.connected + ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1") + ServerFrames.ping
        let bytes = Array(body.utf8)
        let pieces = stride(from: 0, to: bytes.count, by: 7).map { Data(bytes[$0 ..< min($0 + 7, bytes.count)]) }
        stubRegistry.install { _ in .chunked(status: 200, headers: ["Content-Type": "text/event-stream"], chunks: pieces) }

        let response = try await StubURLProtocol.streamTransport().open(request())
        var parser = SPFNSSELineParser()
        var names: [String] = []
        for try await chunk in response.chunks
        {
            names += parser.feed(chunk).map(\.name)
        }
        response.cancel()

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(names, ["connected", "sessionActivity", "ping"])
    }

    func test_adapter_idleTimeoutAndHardeningHold() async throws
    {
        stubRegistry.install { _ in .chunked(status: 200, headers: ["Content-Type": "text/event-stream"], chunks: []) }

        let response = try await StubURLProtocol.streamTransport().open(request())
        response.cancel()

        let sent = try XCTUnwrap(stubRegistry.requests.last)
        XCTAssertEqual(sent.timeoutInterval, SPFNURLSessionStreamTransport.idleTimeoutSeconds)
        XCTAssertFalse(sent.httpShouldHandleCookies)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "accept"), "text/event-stream")
        XCTAssertNil(SPFNURLSessionTransport.hardenedConfiguration().httpCookieStorage)
    }

    func test_adapter_failure_isNotRetried() async throws
    {
        stubRegistry.install { _ in .failure(URLError(.networkConnectionLost)) }

        do
        {
            _ = try await StubURLProtocol.streamTransport().open(request())
            XCTFail("a failed connection must surface, not be retried with the same token")
        }
        catch let error as SPFNTransportError
        {
            XCTAssertEqual(error, .connectivity("URLError \(URLError.Code.networkConnectionLost.rawValue)"))
        }
        XCTAssertEqual(stubRegistry.requests.count, 1)
    }

    func test_adapter_headersDeadline_isTheOnlyTimeout() async throws
    {
        stubRegistry.install { _ in .hang }

        do
        {
            _ = try await StubURLProtocol.streamTransport().open(request(timeoutMillis: 100))
            XCTFail("headers that never come must time out")
        }
        catch let error as SPFNTransportError
        {
            XCTAssertEqual(error, .timedOut)
        }
    }

    #if canImport(Darwin)
    func test_adapter_doesNotFollowRedirects() async throws
    {
        stubRegistry.install { request in
            request.url?.path == "/elsewhere"
                ? .http(status: 200, headers: [:], body: Data())
                : .redirect(status: 302, location: "https://example.invalid/elsewhere")
        }

        let response = try await StubURLProtocol.streamTransport().open(request())
        response.cancel()

        XCTAssertEqual(response.statusCode, 302)
        XCTAssertEqual(stubRegistry.requests.count, 1)
    }
    #endif
}
