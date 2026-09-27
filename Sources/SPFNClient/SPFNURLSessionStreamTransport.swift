// SPFN Mobile — the URLSession stream adapter.
//
// A data task whose delegate receives the response and then the body's bytes as they
// arrive (`urlSession(_:dataTask:didReceive:)`). The delegate shape rather than
// `URLSession.bytes(for:)`: the delegate methods exist in swift-corelibs-foundation too,
// so this file builds on Linux beside the rest of the module (design §2-1).
//
// The same hardening the request adapter applies (SPFNURLSessionTransport): no cookie, no
// cache, and no redirect — the URL carries a one-use token, and a redirect would carry it
// to another host. A 3xx is answered as itself, and the state machine retries it (E-19).
//
// `timeoutMillis` bounds the wait for the headers, judged here. After them the request's
// `timeoutInterval` is set to 60 s, which URLSession reads as an IDLE timeout: with a ping
// every 10 s it never fires on a healthy stream, and it backs up the state machine's 25 s
// silence watchdog, which is the one place silence is judged (§3-9, H-5).
//
// Nothing here prints the URL. An error names a URLError code and nothing else (§6).

import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct SPFNURLSessionStreamTransport: SPFNStreamTransport
{
    /// URLSession's idle timeout on an open stream: longer than the silence watchdog.
    static let idleTimeoutSeconds: TimeInterval = 60

    private let makeConfiguration: @Sendable () -> URLSessionConfiguration

    public init()
    {
        self.init { SPFNURLSessionTransport.hardenedConfiguration() }
    }

    /// The adapter suite routes the session through a URLProtocol stub. Each stream gets
    /// its own session, because a session's delegate is the session's, not the task's.
    init(makeConfiguration: @escaping @Sendable () -> URLSessionConfiguration)
    {
        self.makeConfiguration = makeConfiguration
    }

    public func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse
    {
        var urlRequest = try SPFNURLSessionTransport.urlRequest(from: request)
        urlRequest.timeoutInterval = Self.idleTimeoutSeconds
        let delegate = SPFNStreamDelegate()
        let session = URLSession(
            configuration: makeConfiguration(),
            delegate: delegate,
            delegateQueue: nil
        )
        return try await delegate.start(session.dataTask(with: urlRequest), in: session, headersWithin: request.timeoutMillis)
    }
}

/// One stream's delegate. URLSession holds its delegate strongly until the session is
/// invalidated, and every exit below invalidates it, so the two do not outlive the stream.
private final class SPFNStreamDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable
{
    private let lock = NSLock()
    private var answered: CheckedContinuation<SPFNStreamResponse, any Error>?
    private let chunks: AsyncThrowingStream<[UInt8], any Error>
    private let chunkSink: AsyncThrowingStream<[UInt8], any Error>.Continuation

    override init()
    {
        (chunks, chunkSink) = AsyncThrowingStream.makeStream(of: [UInt8].self, throwing: (any Error).self)
        super.init()
    }

    func start(_ task: URLSessionDataTask, in session: URLSession, headersWithin timeoutMillis: Int64) async throws -> SPFNStreamResponse
    {
        let deadline = Task
        {
            [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(timeoutMillis, 1)) * 1_000_000)
            guard !Task.isCancelled
            else
            {
                return
            }
            self?.answer(with: .failure(SPFNTransportError.timedOut))
            session.invalidateAndCancel()
        }
        defer { deadline.cancel() }
        return try await withTaskCancellationHandler
        {
            try await withCheckedThrowingContinuation
            {
                continuation in
                lock.withLock { answered = continuation }
                task.resume()
            }
        }
        onCancel:
        {
            session.invalidateAndCancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    )
    {
        guard let http = response as? HTTPURLResponse
        else
        {
            answer(with: .failure(SPFNTransportError.invalidResponse("response is not an HTTP response")))
            completionHandler(.cancel)
            return
        }
        answer(with: .success(SPFNStreamResponse(
            statusCode: http.statusCode,
            headers: Self.headers(of: http),
            chunks: chunks,
            cancel: { session.invalidateAndCancel() }
        )))
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data)
    {
        chunkSink.yield([UInt8](data))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?)
    {
        let failure = error.map(Self.transportError(for:))
        answer(with: .failure(failure ?? SPFNTransportError.invalidResponse("the stream ended before its headers")))
        if let failure, failure != .cancelled
        {
            chunkSink.finish(throwing: failure)
        }
        else
        {
            chunkSink.finish()
        }
        session.finishTasksAndInvalidate()
    }

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

    /// Resumes the caller of `start` exactly once: the headers, a failure or the deadline,
    /// whichever comes first. Every later call finds no continuation and does nothing.
    private func answer(with result: Result<SPFNStreamResponse, any Error>)
    {
        let continuation: CheckedContinuation<SPFNStreamResponse, any Error>? = lock.withLock
        {
            defer { answered = nil }
            return answered
        }
        continuation?.resume(with: result)
    }

    private static func headers(of response: HTTPURLResponse) -> [(String, String)]
    {
        response.allHeaderFields.compactMap
        {
            key, value -> (String, String)? in
            guard let name = key as? String
            else
            {
                return nil
            }
            return (name, value as? String ?? String(describing: value))
        }
    }

    private static func transportError(for error: any Error) -> SPFNTransportError
    {
        guard let error = error as? URLError
        else
        {
            return .connectivity(String(describing: type(of: error)))
        }
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
