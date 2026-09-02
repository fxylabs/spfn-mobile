// SPFN Mobile — URLSession adapter conformance to the transport contract.
//
// Every case here has a counterpart with the same name in
// android/spfn-client/src/test/.../SpfnOkHttpTransportTest.kt. Where a platform cannot
// express the same assertion, the divergence is stated in the test's own comment rather
// than hidden by dropping the case.

import Foundation
import XCTest
@testable import SPFNClient

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

private func request(
    method: String = "GET",
    url: String = "https://example.invalid/v1/thing?nonce=abc",
    headers: [(String, String)] = [],
    body: [UInt8]? = nil,
    timeoutMillis: Int64 = 30_000
) -> SPFNTransportRequest
{
    SPFNTransportRequest(
        method: method,
        url: url,
        headers: headers,
        body: body,
        timeoutMillis: timeoutMillis
    )
}

final class SPFNURLSessionRequestMappingTests: XCTestCase
{
    func testRequestMappingCarriesMethodUrlHeadersAndBody() throws
    {
        let mapped = try SPFNURLSessionTransport.urlRequest(
            from: request(
                method: "POST",
                url: "https://example.invalid/v1/thing?nonce=abc",
                headers: [("X-Spfn-Client", "c1"), ("Content-Type", "application/json")],
                body: [0x7B, 0x7D]
            )
        )

        XCTAssertEqual(mapped.httpMethod, "POST")
        XCTAssertEqual(mapped.url?.absoluteString, "https://example.invalid/v1/thing?nonce=abc")
        XCTAssertEqual(mapped.value(forHTTPHeaderField: "X-Spfn-Client"), "c1")
        XCTAssertEqual(mapped.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(mapped.httpBody, Data([0x7B, 0x7D]))
    }

    func testDuplicateRequestHeaderNamesAreRefused() throws
    {
        // URLRequest would fold these into one comma-joined field while OkHttp writes two
        // header lines. The same request must not produce different bytes on the two
        // platforms, so neither sends it.
        let repeated: [[(String, String)]] = [
            [("X-Spfn-Trace", "first"), ("X-Spfn-Trace", "second")],
            [("Accept", "application/json"), ("accept", "text/plain")],
            [("X-Spfn-Trace", "first"), ("X-Other", "x"), ("X-SPFN-TRACE", "second")],
        ]

        for headers in repeated
        {
            XCTAssertThrowsError(try SPFNURLSessionTransport.urlRequest(from: request(headers: headers)))
            { error in
                XCTAssertEqual(
                    error as? SPFNTransportError,
                    .connectivity("duplicate request header name: \(headers[0].0.lowercased())")
                )
            }
        }
    }

    func testDistinctHeaderNamesAreNotRefused() throws
    {
        let mapped = try SPFNURLSessionTransport.urlRequest(
            from: request(headers: [("Accept", "application/json"), ("X-Spfn-Trace", "first")])
        )

        XCTAssertEqual(mapped.value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertEqual(mapped.value(forHTTPHeaderField: "X-Spfn-Trace"), "first")
    }

    func testDuplicateHeaderRefusalCarriesNoHeaderValue() throws
    {
        XCTAssertThrowsError(
            try SPFNURLSessionTransport.urlRequest(
                from: request(headers: [("Authorization", "spfn-proof deadbeef"), ("authorization", "spfn-proof cafe")])
            )
        )
        { error in
            guard case .connectivity(let reason)? = error as? SPFNTransportError
            else
            {
                return XCTFail("expected a connectivity refusal, got \(error)")
            }
            XCTAssertFalse(reason.contains("deadbeef"), "a header value reached the refusal reason")
            XCTAssertFalse(reason.contains("cafe"), "a header value reached the refusal reason")
            XCTAssertTrue(reason.contains("authorization"))
        }
    }

    func testAbsentBodyAndEmptyBodyAreDistinct() throws
    {
        let absent = try SPFNURLSessionTransport.urlRequest(from: request(method: "POST", body: nil))
        let empty = try SPFNURLSessionTransport.urlRequest(from: request(method: "POST", body: []))

        XCTAssertNil(absent.httpBody)
        XCTAssertEqual(empty.httpBody, Data())
    }

    func testTimeoutMillisMapsToTheRequestDeadline() throws
    {
        let mapped = try SPFNURLSessionTransport.urlRequest(from: request(timeoutMillis: 2_500))
        XCTAssertEqual(mapped.timeoutInterval, 2.5, accuracy: 0.0001)
    }

    func testCookiesAndCachingAreOffOnEveryRequest() throws
    {
        let mapped = try SPFNURLSessionTransport.urlRequest(from: request())
        XCTAssertFalse(mapped.httpShouldHandleCookies)
        XCTAssertEqual(mapped.cachePolicy, .reloadIgnoringLocalAndRemoteCacheData)
    }

    /// Asserted against the configuration the session is actually built from, not against
    /// behaviour. A round-trip test cannot see this: `.ephemeral` keeps nothing on disk, so
    /// a single exchange looks identical whether or not the hardening ran. The Kotlin
    /// counterpart is `hardeningTurnsOffRetryRedirectsCookiesAndCache`.
    func testHardenedConfigurationTurnsOffCookiesAndCaching() throws
    {
        let inherited = URLSessionConfiguration.ephemeral
        XCTAssertNotNil(inherited.urlCache, "the fixture must start with the unsafe defaults on")
        XCTAssertNotNil(inherited.httpCookieStorage, "the fixture must start with the unsafe defaults on")

        let configuration = SPFNURLSessionTransport.hardenedConfiguration()
        XCTAssertNil(configuration.urlCache, "an inherited cache must not survive the hardening")
        XCTAssertNil(configuration.httpCookieStorage, "an inherited cookie jar must not survive the hardening")
        XCTAssertFalse(configuration.httpShouldSetCookies)
        XCTAssertEqual(configuration.httpCookieAcceptPolicy, .never)
        XCTAssertEqual(configuration.requestCachePolicy, .reloadIgnoringLocalAndRemoteCacheData)
    }

    func testNonPositiveTimeoutIsRefused() throws
    {
        for millis in [Int64(0), -1]
        {
            XCTAssertThrowsError(try SPFNURLSessionTransport.urlRequest(from: request(timeoutMillis: millis)))
            { error in
                XCTAssertEqual(
                    error as? SPFNTransportError,
                    .connectivity("timeoutMillis must be positive")
                )
            }
        }
    }

    func testMalformedUrlSurfacesAsConnectivity() throws
    {
        XCTAssertThrowsError(try SPFNURLSessionTransport.urlRequest(from: request(url: "/v1/thing")))
        { error in
            XCTAssertEqual(
                error as? SPFNTransportError,
                .connectivity("request URL is not an absolute URL")
            )
        }
    }
}

final class SPFNURLSessionTransportTests: XCTestCase
{
    func testResponseMappingCarriesStatusHeadersAndBody() async throws
    {
        stubRegistry.install { _ in
            .http(status: 200, headers: ["X-Spfn-Echo": "one", "Content-Type": "application/json"], body: Data([0x31, 0x32]))
        }

        let response = try await StubURLProtocol.transport().execute(request())

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(response.body, [0x31, 0x32])
        XCTAssertEqual(response.headers.first { $0.0.lowercased() == "x-spfn-echo" }?.1, "one")
        XCTAssertEqual(
            response.headers.map { $0.0.lowercased() },
            response.headers.map { $0.0.lowercased() }.sorted(),
            "response headers are ordered deterministically"
        )
    }

    func testNonSuccessStatusIsReturnedNotThrown() async throws
    {
        for status in [401, 429, 500]
        {
            stubRegistry.install { _ in .http(status: status, headers: [:], body: Data()) }
            let response = try await StubURLProtocol.transport().execute(request())
            XCTAssertEqual(response.statusCode, status)
            XCTAssertEqual(stubRegistry.requests.count, 1, "a failed status must not be retried by the transport")
        }
    }

    /// swift-corelibs-foundation does not implement
    /// `URLProtocolClient.urlProtocol(_:wasRedirectedTo:redirectResponse:)`: its
    /// `_ProtocolClient` witness traps, so a stub that hands the session a redirect
    /// the way a real protocol does kills the test process on Linux before any
    /// assertion runs. Handing the 3xx over as an ordinary response instead would make
    /// the row pass without exercising the session's redirect handling at all, which is
    /// the exact defect a probe against this suite already found once. So the row is
    /// Apple-only rather than weakened, and the transport is not touched to suit it.
    #if canImport(Darwin)
    func testRedirectIsReturnedNotFollowed() async throws
    {
        stubRegistry.install { _ in .redirect(status: 302, location: "https://elsewhere.invalid/v1/thing") }

        let response = try await StubURLProtocol.transport().execute(request())

        XCTAssertEqual(response.statusCode, 302)
        XCTAssertEqual(stubRegistry.requests.count, 1, "the redirect target must not be requested")
        XCTAssertEqual(
            response.headers.first { $0.0.lowercased() == "location" }?.1,
            "https://elsewhere.invalid/v1/thing"
        )
    }
    #endif

    func testTimeoutSurfacesAsTimedOut() async
    {
        stubRegistry.install { _ in .failure(URLError(.timedOut)) }

        await assertTransportError(.timedOut)
        {
            try await StubURLProtocol.transport().execute(request())
        }
    }

    func testConnectivityFailureSurfacesAsConnectivity() async
    {
        stubRegistry.install { _ in .failure(URLError(.cannotConnectToHost)) }

        await assertTransportError(.connectivity("URLError \(URLError.Code.cannotConnectToHost.rawValue)"))
        {
            try await StubURLProtocol.transport().execute(request())
        }
    }

    func testNonHttpResponseSurfacesAsInvalidResponse() async
    {
        stubRegistry.install { _ in .nonHTTP }

        await assertTransportError(.invalidResponse("response is not an HTTP response"))
        {
            try await StubURLProtocol.transport().execute(request())
        }
    }

    func testCancellationSurfacesAsCancelled() async
    {
        let started = DispatchSemaphore(value: 0)
        stubRegistry.install { _ in
            started.signal()
            return .hang
        }

        let transport = StubURLProtocol.transport()
        let task = Task { try await transport.execute(request()) }

        XCTAssertEqual(started.wait(timeout: .now() + 10), .success, "the stub never received the request")
        let stopsBeforeCancel = stubRegistry.stopLoadingCount
        task.cancel()

        do
        {
            _ = try await task.value
            XCTFail("a cancelled call must not return a response")
        }
        catch
        {
            // Swift surfaces cancellation as a transport error rather than as
            // `CancellationError`: a caller inspecting one error type sees every outcome.
            // Kotlin does the opposite for the same reason — see the Kotlin counterpart.
            XCTAssertEqual(error as? SPFNTransportError, .cancelled)
        }

        // `stopLoading` is URLSession telling the loader to stop, so it is the evidence
        // that the cancellation reached the underlying load rather than only the caller.
        // The Kotlin counterpart proves the same thing with an OkHttp EventListener.
        XCTAssertTrue(
            stubRegistry.awaitStopLoading(above: stopsBeforeCancel),
            "the underlying HTTP load was not cancelled"
        )
    }

    func testCancellationBeforeTheLoadStartsSurfacesAsCancelled() async
    {
        stubRegistry.install { _ in .hang }
        let transport = StubURLProtocol.transport()

        let task = Task
        {
            // Enters `execute` already cancelled. URLSession may then fail with
            // `CancellationError` instead of `URLError.cancelled`; the contract says the
            // caller sees `.cancelled` either way, which is what this pins.
            for _ in 0 ..< 1_000 where !Task.isCancelled
            {
                await Task.yield()
            }
            return try await transport.execute(request())
        }
        task.cancel()

        do
        {
            _ = try await task.value
            XCTFail("a cancelled call must not return a response")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNTransportError, .cancelled)
        }
    }

    /// Runs `body`, requires it to throw, and requires the thrown error to be `expected`.
    private func assertTransportError(
        _ expected: SPFNTransportError,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () async throws -> SPFNTransportResponse
    ) async
    {
        do
        {
            _ = try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        }
        catch
        {
            XCTAssertEqual(error as? SPFNTransportError, expected, file: file, line: line)
        }
    }
}

final class SPFNTransportRedactionTests: XCTestCase
{
    func testDescriptionsCarryNoHeaderOrBodyMaterial() throws
    {
        let outbound = request(
            headers: [("Authorization", "spfn-proof deadbeef")],
            body: Array("secret-payload".utf8)
        )
        let inbound = SPFNTransportResponse(
            statusCode: 200,
            headers: [("Set-Cookie", "session=deadbeef")],
            body: Array("secret-response".utf8)
        )

        for rendered in [outbound.description, outbound.debugDescription, "\(outbound)"]
        {
            XCTAssertFalse(rendered.contains("deadbeef"), "a header value reached a description")
            XCTAssertFalse(rendered.contains("secret-payload"), "body bytes reached a description")
            XCTAssertFalse(rendered.contains("example.invalid"), "the URL reached a description")
            XCTAssertTrue(rendered.contains("14 bytes"))
        }

        for rendered in [inbound.description, inbound.debugDescription, "\(inbound)"]
        {
            XCTAssertFalse(rendered.contains("deadbeef"), "a header value reached a description")
            XCTAssertFalse(rendered.contains("secret-response"), "body bytes reached a description")
        }

        XCTAssertTrue(request(body: nil).description.contains("absent"))
        XCTAssertTrue(request(body: []).description.contains("0 bytes"))
    }
}
