// SPFN Mobile — a URLProtocol stub for the transport tests.
//
// The adapter is tested against a stub rather than a live server so the suite stays
// offline, which is the same rule the rest of this repository's checks follow. What the
// stub cannot show — real TCP framing — is the reference-server work, not this one.

import Foundation
import SPFNClient

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// What the stub does with one request.
enum StubOutcome: Sendable
{
    case http(status: Int, headers: [String: String], body: Data)
    case nonHTTP
    case failure(URLError)

    /// Hands the redirect to URLSession the way a protocol that met a 3xx does, so the
    /// session's own redirect handling actually runs.
    ///
    /// Answering a 3xx as an ordinary `.http` outcome does NOT exercise it: the session
    /// treats a delivered response as final and never asks the delegate anything. A
    /// redirect test built that way passes whether or not redirects are blocked, which is
    /// what a probe against this suite found.
    case redirect(status: Int, location: String)

    /// Accepts the request and never answers, so the caller's cancellation is the only
    /// thing that can end the call.
    case hang
}

/// Shared state for `StubURLProtocol`.
///
/// `@unchecked Sendable` justification: every stored property is read and written only
/// while `lock` is held, and nothing escapes the lock by reference.
final class StubRegistry: @unchecked Sendable
{
    private let lock = NSLock()
    private var handler: @Sendable (URLRequest) -> StubOutcome = { _ in .failure(URLError(.unsupportedURL)) }
    private var received: [URLRequest] = []
    private var stopped = 0

    func install(_ handler: @escaping @Sendable (URLRequest) -> StubOutcome)
    {
        lock.lock()
        defer { lock.unlock() }
        self.handler = handler
        received = []
        stopped = 0
    }

    /// Records that URLSession told the protocol to stop, which is how a cancellation
    /// that actually reached the underlying load becomes observable.
    func recordStopLoading()
    {
        lock.lock()
        defer { lock.unlock() }
        stopped += 1
    }

    var stopLoadingCount: Int
    {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    /// Waits for one more `stopLoading` than `baseline`.
    ///
    /// A delta rather than an absolute count, because a load left hanging by an earlier
    /// case can report its own `stopLoading` at any time. Polling rather than reading
    /// once, because the callback lands on the loader's thread after the caller's `await`
    /// has already returned.
    func awaitStopLoading(above baseline: Int, timeout: TimeInterval = 5) -> Bool
    {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline
        {
            if stopLoadingCount > baseline
            {
                return true
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
        return false
    }

    func answer(_ request: URLRequest) -> StubOutcome
    {
        lock.lock()
        defer { lock.unlock() }
        received.append(request)
        return handler(request)
    }

    var requests: [URLRequest]
    {
        lock.lock()
        defer { lock.unlock() }
        return received
    }
}

let stubRegistry = StubRegistry()

final class StubURLProtocol: URLProtocol
{
    override class func canInit(with request: URLRequest) -> Bool
    {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest
    {
        request
    }

    override func startLoading()
    {
        switch stubRegistry.answer(request)
        {
        case .http(let status, let headers, let body):
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://invalid.invalid")!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)

        case .redirect(let status, let location):
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://invalid.invalid")!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Location": location]
            )!
            var followUp = URLRequest(url: URL(string: location)!)
            followUp.httpMethod = "GET"
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, wasRedirectedTo: followUp, redirectResponse: response)
            client?.urlProtocolDidFinishLoading(self)

        case .nonHTTP:
            let response = URLResponse(
                url: request.url ?? URL(string: "https://invalid.invalid")!,
                mimeType: "application/octet-stream",
                expectedContentLength: 0,
                textEncodingName: nil
            )
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)

        case .failure(let error):
            client?.urlProtocol(self, didFailWithError: error)

        case .hang:
            break
        }
    }

    override func stopLoading()
    {
        stubRegistry.recordStopLoading()
    }

    /// A transport whose session routes every request through this stub.
    static func transport() -> SPFNURLSessionTransport
    {
        let configuration = SPFNURLSessionTransport.hardenedConfiguration()
        configuration.protocolClasses = [StubURLProtocol.self]
        return SPFNURLSessionTransport(session: URLSession(configuration: configuration))
    }
}
