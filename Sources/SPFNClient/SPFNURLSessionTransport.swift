// SPFN Mobile — the URLSession transport adapter.
//
// URLSession is the whole iOS dependency: no package, no vendored client. The adapter's
// job is to make URLSession behave the way the transport contract says, which means
// switching off three conveniences that would otherwise corrupt an authenticated
// exchange — redirect following, cookies and caching.

import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// `SPFNTransport` over `URLSession`.
///
/// Redirects are not followed. A proof is bound to a method and a path, so a 3xx that the
/// stack quietly re-issued would arrive at the new location carrying a proof for the old
/// one. The 3xx is returned as the response instead, and the layers above decide.
public struct SPFNURLSessionTransport: SPFNTransport
{
    private let session: URLSession

    /// Builds a transport on a session that keeps nothing between calls.
    public init()
    {
        self.init(session: URLSession(configuration: SPFNURLSessionTransport.hardenedConfiguration()))
    }

    /// Builds a transport on a caller-supplied session.
    ///
    /// The tests use this to install a stub protocol. A caller that supplies a session is
    /// responsible for the cookie, cache and redirect posture of that session; the
    /// per-request settings this adapter applies still hold.
    public init(session: URLSession)
    {
        self.session = session
    }

    /// An ephemeral configuration with cookies and caching explicitly off.
    ///
    /// `.ephemeral` already keeps nothing on disk, but a shared cookie jar and a memory
    /// cache would still let one authenticated exchange influence the next. Both are
    /// named here rather than inherited so the posture survives an SDK default change.
    public static func hardenedConfiguration() -> URLSessionConfiguration
    {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        return configuration
    }

    public func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        let urlRequest = try Self.urlRequest(from: request)

        do
        {
            let (data, response) = try await session.data(
                for: urlRequest,
                delegate: SPFNRedirectBlocker.shared
            )
            guard let http = response as? HTTPURLResponse
            else
            {
                throw SPFNTransportError.invalidResponse("response is not an HTTP response")
            }
            return SPFNTransportResponse(
                statusCode: http.statusCode,
                headers: Self.headers(of: http),
                body: [UInt8](data)
            )
        }
        catch let error as SPFNTransportError
        {
            throw error
        }
        catch let error as URLError
        {
            throw Self.transportError(for: error)
        }
        catch is CancellationError
        {
            // A Task cancelled before the load starts fails with `CancellationError`
            // rather than `URLError.cancelled`. Letting it through unmapped would put an
            // error outside the four cases in front of a caller who was promised four.
            throw SPFNTransportError.cancelled
        }
        catch
        {
            // Nothing else is expected. Naming the type keeps the outcome diagnosable
            // without repeating anything from the request.
            throw SPFNTransportError.connectivity(String(describing: type(of: error)))
        }
    }

    /// Maps the transport request onto a `URLRequest` without adding anything of its own.
    ///
    /// Internal rather than private because a `URLProtocol` stub never sees the body it
    /// was given — URLSession hands the protocol a stream instead — so the body mapping is
    /// only assertable here.
    static func urlRequest(from request: SPFNTransportRequest) throws -> URLRequest
    {
        guard request.timeoutMillis > 0
        else
        {
            // The two stacks disagree about what a non-positive deadline means — OkHttp
            // reads zero as "no timeout", URLSession replaces it with its own default —
            // so the request is refused on both rather than behaving differently on each.
            throw SPFNTransportError.connectivity("timeoutMillis must be positive")
        }

        guard let url = URL(string: request.url), url.scheme != nil, url.host != nil
        else
        {
            // Not a network failure and not a response: nothing was ever sent. It is
            // reported as connectivity because the call could not reach a server, and the
            // reason names the defect without repeating the URL.
            throw SPFNTransportError.connectivity("request URL is not an absolute URL")
        }

        try Self.rejectDuplicateHeaderNames(request.headers)

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body.map { Data($0) }
        urlRequest.httpShouldHandleCookies = false
        urlRequest.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

        // `timeoutInterval` is URLSession's per-request deadline. It is an idle timeout
        // rather than OkHttp's whole-call timeout, which is the one place the two adapters
        // differ in trigger condition; both surface the expiry as `.timedOut`.
        urlRequest.timeoutInterval = Double(request.timeoutMillis) / 1000.0

        for (name, value) in request.headers
        {
            // Safe to append: the names are already known to be unique, so `addValue`
            // never reaches the folding path that would rewrite two fields into one.
            urlRequest.addValue(value, forHTTPHeaderField: name)
        }

        return urlRequest
    }

    /// Refuses a request that names the same header field twice, comparing names the way
    /// HTTP does — without regard to case.
    ///
    /// The two stacks cannot agree on what to do with a repeated name. OkHttp writes two
    /// header lines; URLRequest has no representation for that at all and folds them into
    /// one comma-joined value. Rather than let the same request produce different bytes on
    /// the two platforms, neither sends it.
    ///
    /// The reason carries the field name and never the value: a repeated `Authorization`
    /// is exactly the case where a value must not reach an error string.
    private static func rejectDuplicateHeaderNames(_ headers: [(String, String)]) throws
    {
        var seen = Set<String>()
        for (name, _) in headers
        {
            let key = name.lowercased()
            guard seen.insert(key).inserted
            else
            {
                throw SPFNTransportError.connectivity("duplicate request header name: \(key)")
            }
        }
    }

    /// Response headers as an ordered list.
    ///
    /// `allHeaderFields` is a dictionary, so the wire order and any repeated field are
    /// already gone by the time Foundation hands the response over. Sorting by lowercased
    /// name at least makes the result deterministic instead of arbitrary.
    private static func headers(of response: HTTPURLResponse) -> [(String, String)]
    {
        response.allHeaderFields
            .compactMap { key, value -> (String, String)? in
                guard let name = key as? String
                else
                {
                    return nil
                }
                return (name, value as? String ?? String(describing: value))
            }
            .sorted { $0.0.lowercased() < $1.0.lowercased() }
    }

    /// Maps a `URLError` onto the transport's four failure cases.
    ///
    /// The reason string is the numeric `NSURLError` code and nothing else. A localized
    /// description can embed the failing URL, and a URL can carry a nonce, so the useful
    /// half of the diagnostic is kept and the part that could leak is dropped.
    private static func transportError(for error: URLError) -> SPFNTransportError
    {
        switch error.code
        {
        case .timedOut:
            return .timedOut
        case .cancelled:
            return .cancelled
        default:
            return .connectivity("URLError \(error.code.rawValue)")
        }
    }
}

/// Refuses every redirect the session offers.
///
/// `@unchecked Sendable` justification: the class has no stored properties, so there is no
/// state for concurrent callbacks to race on, and its one method returns a constant.
/// NSObject conformance is what forces the annotation, not any shared mutable state.
private final class SPFNRedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable
{
    static let shared = SPFNRedirectBlocker()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    )
    {
        completionHandler(nil)
    }
}
