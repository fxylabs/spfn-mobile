// SPFN Mobile — the clientProofV1 session.
//
// One responsibility: hold whatever session the server last opened, open one when there
// is none, and issue the proof headers a request carries. It does not decide what a
// server answer means beyond "did the handshake open a session", it does not retry, and
// it does not classify transport failures. Those belong to the single execute path
// above it, which is a separate change set — so nothing here has vocabulary for them.
//
// android/spfn-client/.../SpfnSession.kt is the same object in Kotlin.

import SPFNAuth
import SPFNCore
import SPFNGenerated

/// The session a handshake opened.
public struct SPFNSessionState: Equatable, Sendable
{
    /// The opaque session identifier the server issued. A bearer credential.
    public let sessionID: String

    /// When the server says the session stops being usable, in epoch milliseconds.
    public let expiresAtMillis: Int64

    public init(sessionID: String, expiresAtMillis: Int64)
    {
        self.sessionID = sessionID
        self.expiresAtMillis = expiresAtMillis
    }
}

// A session identifier authenticates requests, so it is redacted for the same reason a
// key is: the default reflection-based description would print it into any log.
extension SPFNSessionState: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNSessionState(sessionID: redacted, expiresAtMillis: \(expiresAtMillis))"
    }

    public var debugDescription: String
    {
        description
    }
}

/// The two ways opening a session can fail on this layer.
///
/// Everything else passes through unchanged: a `SPFNTransportError` stays a transport
/// error and a `SPFNAuthError` stays an auth error. Wrapping them here would erase the
/// distinction the execute path above is going to classify on.
public enum SPFNSessionError: Error, Equatable, Sendable
{
    /// The server refused the handshake and answered with a well-formed error envelope.
    ///
    /// The envelope is server-controlled text and never prints itself; read `rejection`
    /// or match the case to classify on it. The status is carried alongside it because
    /// it is the only layer that ever sees it: the execute path above classifies a
    /// refusal on the envelope's code and reports the status the server actually chose,
    /// which is not always the one the contract declares for that code.
    case handshakeRejected(httpStatus: Int, envelope: SPFNErrorEnvelope)

    /// The response body was not what its status said it would be. The reason names the
    /// shape that was expected and never carries any part of the body.
    case malformedResponse(String)

    /// The two shapes a handshake answer can fail to be.
    ///
    /// Public because the execute path above maps a malformed handshake onto its own
    /// decoding vocabulary, and comparing against a re-spelled literal there would let
    /// the two files disagree about which shape was expected without anything failing.
    public static let notAHandshakeResponse = "response body is not a HandshakeResponse"

    /// See `notAHandshakeResponse`.
    public static let notAnErrorEnvelope = "response body is not an SPFN error envelope"

    /// The envelope a refused handshake carried, for a caller that classifies on it.
    public var rejection: SPFNErrorEnvelope?
    {
        guard case .handshakeRejected(_, let envelope) = self
        else
        {
            return nil
        }
        return envelope
    }
}

// A refusal is the one case here whose payload the server wrote, so no default output
// path may carry it. `description` alone is not enough: `String(reflecting:)` and `dump`
// walk an enum's associated values through the mirror, so the mirror is emptied too.
//
// `malformedResponse` keeps its reason. That string is one of two constants this file
// owns and never comes from a response.
extension SPFNSessionError: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable
{
    public var description: String
    {
        switch self
        {
        case .handshakeRejected(let httpStatus, _):
            return "SPFNSessionError.handshakeRejected(httpStatus: \(httpStatus), envelope: redacted)"
        case .malformedResponse(let reason):
            return "SPFNSessionError.malformedResponse(\(reason))"
        }
    }

    public var debugDescription: String
    {
        description
    }

    public var customMirror: Mirror
    {
        Mirror(self, unlabeledChildren: [Any]())
    }
}

/// Holds the session and issues proof headers.
///
/// An actor, because `ensureSession` must open at most one session no matter how many
/// callers ask at once. Actor isolation alone does not give that: `ensureSession`
/// suspends on the network, and an actor admits other calls while a call is suspended.
/// The in-flight handshake is therefore shared explicitly rather than implied.
public actor SPFNSession
{
    /// The server every request goes to, without a trailing slash.
    ///
    /// Public and immutable so the execute path above reads the base URL from the session
    /// that signs against it rather than holding a second copy of it. Two copies is one
    /// too many: a request proved against one host and sent to another is a 401 nobody
    /// can explain from the call site.
    public let baseURL: String

    private let transport: any SPFNTransport
    private let keyProvider: any SPFNKeyProvider
    private let clock: any SPFNProofClock
    private let nonceGenerator: any SPFNNonceGenerator
    private let timeoutMillis: Int64

    private var state: SPFNSessionState?

    /// Bumped by `invalidate()`. A handshake that started before the bump does not
    /// store its result, so a session discarded mid-flight cannot come back. The
    /// network call itself is left to finish; its answer is simply not installed.
    private var generation: UInt64 = 0
    private var inFlight: (generation: UInt64, task: Task<SPFNSessionState, Error>)?

    public init(
        transport: any SPFNTransport,
        keyProvider: any SPFNKeyProvider,
        baseURL: String,
        clock: any SPFNProofClock = SPFNProcessServerClock.shared,
        nonceGenerator: any SPFNNonceGenerator = SPFNRandomNonceGenerator(),
        timeoutMillis: Int64 = 15_000
    )
    {
        self.transport = transport
        self.keyProvider = keyProvider
        self.baseURL = Self.withoutTrailingSlash(baseURL)
        self.clock = clock
        self.nonceGenerator = nonceGenerator
        self.timeoutMillis = timeoutMillis
    }

    /// The session currently held, expired or not. Exposed so a caller can observe the
    /// session without provoking a handshake.
    public var currentState: SPFNSessionState?
    {
        state
    }

    /// Opens a session unconditionally, replacing whatever is held.
    @discardableResult
    public func handshake() async throws -> SPFNSessionState
    {
        let operation = SPFNGeneratedOperations.authClientProofHandshake
        let nonce = nonceGenerator.nextNonce()
        let issuedAtMillis = try await clock.nowMillis(
            transport: transport,
            baseURL: baseURL,
            timeoutMillis: timeoutMillis
        )

        let body = SPFNHandshakeRequest(
            clientId: keyProvider.clientID,
            keyId: keyProvider.keyID,
            nonce: nonce,
            issuedAtMillis: issuedAtMillis
        )

        // Encoded exactly once. The bytes the proof is taken over and the bytes that go
        // on the wire are the same array, so they cannot drift apart the way two
        // separate encodings of the same value can.
        let canonicalBody = SPFNCanonicalJSON.encode(try body.canonicalValue())

        let headers = try proofHeaders(
            operation: operation,
            canonicalBody: canonicalBody,
            nonce: nonce,
            issuedAtMillis: issuedAtMillis,
            sessionID: nil
        )

        let started = generation
        let response = try await transport.execute(
            SPFNTransportRequest(
                method: operation.method,
                url: baseURL + operation.path,
                headers: headers,
                body: canonicalBody,
                timeoutMillis: timeoutMillis
            )
        )

        let opened = try Self.readSession(from: response)
        if started == generation
        {
            state = opened
        }
        return opened
    }

    /// Returns a usable session, opening one only when there is none or it has expired.
    ///
    /// Concurrent callers share one handshake. A caller that arrives while a handshake
    /// is in flight waits for it instead of starting a second one.
    @discardableResult
    public func ensureSession() async throws -> SPFNSessionState
    {
        if let current = state,
           try await clock.nowMillis(
               transport: transport,
               baseURL: baseURL,
               timeoutMillis: timeoutMillis
           ) < current.expiresAtMillis
        {
            return current
        }

        if let existing = inFlight, existing.generation == generation
        {
            return try await existing.task.value
        }

        let started = generation
        let task = Task
        {
            defer { self.releaseInFlight(startedAt: started) }
            return try await self.handshake()
        }
        inFlight = (generation: started, task: task)
        return try await task.value
    }

    /// Issues the headers one request carries, with a fresh nonce and a fresh timestamp.
    ///
    /// The returned list is the complete set of headers the request needs: `content-type`
    /// when it has a body, then the proof fields in contract order, then the session for
    /// an operation that requires one. A caller adds nothing.
    ///
    /// `canonicalBody` must be the exact bytes the request will send. Passing `nil` means
    /// the request carries no body at all, which the proof digests differently from a
    /// body of zero length.
    public func proofHeaders(
        operation: SPFNOperation,
        canonicalBody: [UInt8]?
    ) async throws -> [(String, String)]
    {
        let sessionID = operation.requiresSession ? try await ensureSession().sessionID : nil

        // Read after the handshake, not before: a nonce and a timestamp minted before a
        // network round trip would already be stale by the time the request is sent.
        return try proofHeaders(
            operation: operation,
            canonicalBody: canonicalBody,
            nonce: nonceGenerator.nextNonce(),
            issuedAtMillis: try await clock.nowMillis(
                transport: transport,
                baseURL: baseURL,
                timeoutMillis: timeoutMillis
            ),
            sessionID: sessionID
        )
    }

    /// Discards the held session and abandons any handshake still in flight.
    public func invalidate()
    {
        state = nil
        generation &+= 1
        inFlight = nil
    }

    /// Discards the held session only while it is still the one `staleSessionID` names.
    ///
    /// This is what a caller reacting to a refused request wants, and the unconditional
    /// `invalidate()` is not. Several requests can be in flight against one session; when
    /// the server revokes it they are all refused, and if each of them discarded whatever
    /// the session happens to be holding by then, the first one to re-open a session would
    /// have it thrown away by the second, which would re-open another, and so on. Matching
    /// on the identifier the refused request actually presented means the first refusal
    /// discards the session and the rest find it already gone and join the one handshake.
    ///
    /// Actor-isolated, so the comparison and the discard are one step. A caller doing the
    /// same thing with `currentState` and `invalidate()` would have a suspension between
    /// them, which is exactly where the session it checked can be replaced.
    public func invalidate(staleSessionID: String)
    {
        guard state?.sessionID == staleSessionID
        else
        {
            return
        }
        invalidate()
    }

    // MARK: - Assembly

    private func proofHeaders(
        operation: SPFNOperation,
        canonicalBody: [UInt8]?,
        nonce: String,
        issuedAtMillis: Int64,
        sessionID: String?
    ) throws -> [(String, String)]
    {
        let input = SPFNProofInput.forRequest(
            method: operation.method,
            path: operation.path,
            clientID: keyProvider.clientID,
            keyID: keyProvider.keyID,
            nonce: nonce,
            issuedAtMillis: issuedAtMillis,
            canonicalBody: canonicalBody
        )

        let proof = try SPFNClientProof.proof(for: input) { message in
            try keyProvider.sign(message)
        }

        var headers: [(String, String)] = []
        if canonicalBody != nil
        {
            headers.append((SPFNWireHeaders.contentType, SPFNWireHeaders.requestContentType))
        }
        headers.append((SPFNWireHeaders.profile, SPFNClientProof.profileName))
        headers.append((SPFNWireHeaders.clientID, keyProvider.clientID))
        headers.append((SPFNWireHeaders.keyID, keyProvider.keyID))
        headers.append((SPFNWireHeaders.nonce, nonce))
        headers.append((SPFNWireHeaders.issuedAtMillis, String(issuedAtMillis)))
        headers.append((SPFNWireHeaders.proof, proof))

        // Guarded by the operation rather than by the caller: the handshake opens the
        // session it would otherwise present, so it can never carry one.
        if operation.requiresSession, let sessionID
        {
            headers.append((SPFNWireHeaders.session, sessionID))
        }
        return headers
    }

    private func releaseInFlight(startedAt: UInt64)
    {
        if inFlight?.generation == startedAt
        {
            inFlight = nil
        }
    }

    // MARK: - Reading the answer

    /// Reads a handshake answer, or throws the reason it was not one.
    ///
    /// Both reasons are fixed strings. A reason built from the body would put whatever
    /// the server sent into an error, and from there into a log.
    private static func readSession(from response: SPFNTransportResponse) throws -> SPFNSessionState
    {
        let opened = (200 ... 299).contains(response.statusCode)
        let reason = opened
            ? SPFNSessionError.notAHandshakeResponse
            : SPFNSessionError.notAnErrorEnvelope

        guard let parsed = try? SPFNCanonicalJSON.parse(response.body)
        else
        {
            throw SPFNSessionError.malformedResponse(reason)
        }

        guard opened
        else
        {
            guard let envelope = try? SPFNErrorEnvelope.decode(parsed)
            else
            {
                throw SPFNSessionError.malformedResponse(reason)
            }
            throw SPFNSessionError.handshakeRejected(httpStatus: response.statusCode, envelope: envelope)
        }

        guard let decoded = try? SPFNHandshakeResponse(canonical: parsed)
        else
        {
            throw SPFNSessionError.malformedResponse(reason)
        }
        return SPFNSessionState(
            sessionID: decoded.sessionId,
            expiresAtMillis: decoded.expiresAtMillis
        )
    }

    private static func withoutTrailingSlash(_ url: String) -> String
    {
        var trimmed = url
        while trimmed.hasSuffix("/")
        {
            trimmed.removeLast()
        }
        return trimmed
    }
}
