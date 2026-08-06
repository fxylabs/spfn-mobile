// SPFN Mobile — the single execute path.
//
// Every operation goes through one function. That is the whole point of the file: there
// is no second way to send a request, so a rule stated here — the body is encoded once,
// the proof is fresh, an auth refusal buys exactly one re-handshake and nothing else buys
// anything — holds for every operation the contract will ever declare, including the ones
// that do not exist yet.
//
// Retry is off. The one exception is an auth refusal, which re-opens the session and
// re-sends once, because that is the only failure where the client knows what changed and
// knows the request was never applied. A transport failure is not retried: this layer
// cannot tell a request that never arrived from one that arrived and whose answer was
// lost, and re-sending the second kind is how a client applies an operation twice.
//
// android/spfn-client/.../SpfnClient.kt is the same path in Kotlin.

import SPFNCore
import SPFNGenerated

/// One operation with the two functions that turn its types into contract bytes.
///
/// The client is generic over request and response rather than knowing any operation, so
/// the per-operation values that fill this in can be generated later without this file
/// changing. Keeping the operation inside the descriptor is what stops a caller pairing
/// `echo.send` with the codec for `items.list`.
public struct SPFNCall<Request: Sendable, Response: Sendable>: Sendable
{
    public let operation: SPFNOperation
    public let encode: @Sendable (Request) throws -> SPFNCanonicalValue
    public let decode: @Sendable (SPFNCanonicalValue) throws -> Response

    public init(
        operation: SPFNOperation,
        encode: @escaping @Sendable (Request) throws -> SPFNCanonicalValue,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Response
    )
    {
        self.operation = operation
        self.encode = encode
        self.decode = decode
    }
}

/// Sends contract operations over a transport, against one session.
///
/// A struct, not an actor: it holds nothing that changes. Every piece of shared mutable
/// state a request touches — the session, the handshake in flight — belongs to
/// `SPFNSession`, which is the actor. Making this one too would add a second isolation
/// domain that protects nothing.
public struct SPFNClient: Sendable
{
    private let transport: any SPFNTransport
    private let session: SPFNSession
    private let timeoutMillis: Int64

    /// - Parameter timeoutMillis: the deadline for one attempt, not for the call. A
    ///   re-handshake and the re-sent request each get a fresh budget, because the
    ///   transport's deadline is per call and splitting one budget across three calls
    ///   would make the last one fail for reasons the caller never asked for. It applies
    ///   to the operation requests only: a handshake is a call the session makes, under
    ///   the deadline the session was given.
    public init(
        transport: any SPFNTransport,
        session: SPFNSession,
        timeoutMillis: Int64 = 15_000
    )
    {
        self.transport = transport
        self.session = session
        self.timeoutMillis = timeoutMillis
    }

    /// Sends one operation and returns its response.
    ///
    /// Throws `SPFNClientError` for everything this path classifies. An error it did not
    /// produce passes through as itself; see that type.
    public func execute<Request, Response>(
        _ call: SPFNCall<Request, Response>,
        request: Request
    ) async throws -> Response
    {
        // The auth class is resolved before anything is sent, and an unknown one is
        // refused rather than guessed at. The contract's own rule: nothing is ever
        // downgraded to anonymous handling, so a class this build does not know is a
        // contract this build does not implement.
        guard let authClass = SPFNGeneratedOperations.authClass(of: call.operation)
        else
        {
            throw SPFNClientError.undeclaredAuthClass(call.operation.authProfile)
        }

        // The handshake is the session's own operation whichever class it declares:
        // it opens the session every proven operation presents, so running it here
        // would send it without the bookkeeping that gives it its point.
        guard call.operation.id != SPFNGeneratedOperations.authClientProofHandshake.id
        else
        {
            throw SPFNClientError.unsupportedOperation(call.operation.id)
        }

        // Encoded once, for both attempts. The proof is not: the re-sent request carries
        // the same bytes under a new nonce, a new timestamp and a new proof over them.
        let canonicalBody = SPFNCanonicalJSON.encode(try call.encode(request))

        switch authClass
        {
        case .none:
            return try await executeUnproven(call, canonicalBody: canonicalBody)
        case .clientProofV1:
            break
        }

        let first = try await attempt(call.operation, canonicalBody: canonicalBody)

        do
        {
            return try Self.read(first.response, with: call.decode)
        }
        catch SPFNClientError.auth(let failure)
        {
            return try await retryOnce(
                call,
                canonicalBody: canonicalBody,
                presented: first.sessionID,
                failure: failure
            )
        }
    }

    // MARK: - The unproven class

    /// Sends an operation of the contract's unproven class: no proof, no identity, no
    /// nonce and no session header, because it is called before any key exists to sign
    /// with. Nothing here touches the session, so it can never provoke a handshake.
    ///
    /// There is also no retry. The one retry `execute` owns exists to replace a stale
    /// session, and this request presented none; an auth refusal here is the server's
    /// final answer and passes through as itself.
    private func executeUnproven<Request, Response>(
        _ call: SPFNCall<Request, Response>,
        canonicalBody: [UInt8]
    ) async throws -> Response
    {
        let response: SPFNTransportResponse
        do
        {
            response = try await transport.execute(
                SPFNTransportRequest(
                    method: call.operation.method,
                    url: session.baseURL + call.operation.path,
                    headers: [(SPFNWireHeaders.contentType, SPFNWireHeaders.requestContentType)],
                    body: canonicalBody,
                    timeoutMillis: timeoutMillis
                )
            )
        }
        catch
        {
            throw Self.classify(error)
        }
        return try Self.read(response, with: call.decode)
    }

    // MARK: - The two attempts

    /// One request: fresh headers, one transport call, no interpretation of the answer.
    private func attempt(_ operation: SPFNOperation, canonicalBody: [UInt8]) async throws -> Attempt
    {
        do
        {
            let headers = try await session.proofHeaders(operation: operation, canonicalBody: canonicalBody)
            let response = try await transport.execute(
                SPFNTransportRequest(
                    method: operation.method,
                    url: session.baseURL + operation.path,
                    headers: headers,
                    body: canonicalBody,
                    timeoutMillis: timeoutMillis
                )
            )
            return Attempt(response: response, sessionID: Self.sessionID(in: headers))
        }
        catch
        {
            throw Self.classify(error)
        }
    }

    /// Re-opens the session and sends the request once more.
    ///
    /// Straight-line on purpose. There is no loop and no counter: the second attempt has
    /// no path back into this function, so "at most one re-handshake per call" is a
    /// property of the shape rather than of a variable somebody could forget to reset.
    private func retryOnce<Request, Response>(
        _ call: SPFNCall<Request, Response>,
        canonicalBody: [UInt8],
        presented sessionID: String?,
        failure: SPFNAuthFailure
    ) async throws -> Response
    {
        // No session was presented, so there is nothing a re-handshake would replace.
        guard let sessionID
        else
        {
            throw SPFNClientError.auth(failure)
        }

        // Cancellation that lands between the two attempts costs no further request, and
        // surfaces in the shape the transport would have raised a moment later.
        guard !Task.isCancelled
        else
        {
            throw SPFNClientError.transport(.cancelled)
        }

        await session.invalidate(staleSessionID: sessionID)
        let second = try await attempt(call.operation, canonicalBody: canonicalBody)
        return try Self.read(second.response, with: call.decode)
    }

    private struct Attempt
    {
        let response: SPFNTransportResponse

        /// The session the request actually presented, so a refusal discards that session
        /// and not whichever one is held by the time the refusal is read.
        let sessionID: String?
    }

    // MARK: - Reading the answer

    /// Turns one response into a value, or into the failure it describes.
    private static func read<Response>(
        _ response: SPFNTransportResponse,
        with decode: (SPFNCanonicalValue) throws -> Response
    ) throws -> Response
    {
        guard let parsed = try? SPFNCanonicalJSON.parse(response.body)
        else
        {
            throw SPFNClientError.decoding(.notCanonicalJSON)
        }

        guard (200 ... 299).contains(response.statusCode)
        else
        {
            guard let envelope = try? SPFNErrorEnvelope.decode(parsed)
            else
            {
                throw SPFNClientError.decoding(.notAnErrorEnvelope)
            }
            throw refusal(envelope, httpStatus: response.statusCode)
        }

        guard let value = try? decode(parsed)
        else
        {
            throw SPFNClientError.decoding(.notTheDeclaredResponse)
        }
        return value
    }

    /// Classifies a refusal on the code the envelope declares.
    ///
    /// The status is carried, never consulted. A 401 an intermediary wrote carries no
    /// envelope and never reaches here at all, so it cannot make the client re-handshake
    /// against something that never refused a proof.
    private static func refusal(_ envelope: SPFNErrorEnvelope, httpStatus: Int) -> SPFNClientError
    {
        guard let code = try? SPFNGeneratedErrorCode.decode(envelope.code)
        else
        {
            return .decoding(.unknownErrorCode)
        }
        guard code.isAuthFailure
        else
        {
            return .server(SPFNServerFailure(code: code, httpStatus: httpStatus, envelope: envelope))
        }
        return .auth(SPFNAuthFailure(code: code, httpStatus: httpStatus, envelope: envelope))
    }

    /// Maps what the layers below raise onto the taxonomy, and leaves alone what it does
    /// not recognise. Flattening an unknown error into one of the cases would make a
    /// client-side bug read as something the server did.
    private static func classify(_ error: any Error) -> any Error
    {
        if let failure = error as? SPFNTransportError
        {
            return SPFNClientError.transport(failure)
        }
        guard let failure = error as? SPFNSessionError
        else
        {
            return error
        }
        switch failure
        {
        case .handshakeRejected(let httpStatus, let envelope):
            return refusal(envelope, httpStatus: httpStatus)
        case .malformedResponse(let reason) where reason == SPFNSessionError.notAnErrorEnvelope:
            return SPFNClientError.decoding(.notAnErrorEnvelope)
        case .malformedResponse:
            return SPFNClientError.decoding(.notTheDeclaredResponse)
        }
    }

    private static func sessionID(in headers: [(String, String)]) -> String?
    {
        headers.first { $0.0 == SPFNWireHeaders.session }?.1
    }
}
