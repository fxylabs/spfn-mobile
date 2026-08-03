// SPFN Mobile — what one executed request can fail with.
//
// Four classes, and the class is decided by the error code the contract declares, never
// by the HTTP status. A proxy can answer 401 with a body no SPFN server wrote; reading
// that as an auth failure would make the client re-handshake against something that
// never asked it to. So the status is carried for diagnosis and the code decides.
//
// Every value here is safe to print. Server-chosen text lives only inside
// `SPFNErrorEnvelope`, which redacts itself on every output path, and the failure types
// below carry nothing else the server wrote.
//
// android/spfn-client/.../SpfnClientError.kt is the same taxonomy in Kotlin.

import SPFNCore
import SPFNGenerated

/// Why a response could not be read as the contract describes it.
///
/// Every case is a constant. A reason assembled from the body — a field path the server
/// chose, an unrecognised code spelled out — would put server text one `print` away, so
/// no case has a payload at all.
public enum SPFNDecodingFailure: String, Equatable, Sendable
{
    /// The body was not canonical JSON, whatever the status said.
    case notCanonicalJSON

    /// A 2xx body parsed, but was not the response type the operation declares.
    case notTheDeclaredResponse

    /// A non-2xx body parsed, but was not an SPFN error envelope.
    case notAnErrorEnvelope

    /// An envelope arrived carrying a code this contract does not declare.
    case unknownErrorCode
}

/// A refusal the server authenticated the request on.
///
/// One of these is the only thing that makes `execute` re-handshake, and it does so at
/// most once per call.
public struct SPFNAuthFailure: Equatable, Sendable
{
    /// The declared code, resolved from the envelope. Always one of the auth family.
    public let code: SPFNGeneratedErrorCode

    /// The status the server actually answered with, which may differ from
    /// `code.httpStatus` — the contract states what a code is supposed to arrive as, and
    /// a disagreement is worth seeing rather than smoothing over.
    public let httpStatus: Int

    /// The envelope as it arrived. Server-chosen text; it never prints itself.
    public let envelope: SPFNErrorEnvelope

    public init(code: SPFNGeneratedErrorCode, httpStatus: Int, envelope: SPFNErrorEnvelope)
    {
        self.code = code
        self.httpStatus = httpStatus
        self.envelope = envelope
    }
}

/// A refusal on any ground other than authentication.
public struct SPFNServerFailure: Equatable, Sendable
{
    /// The declared code, resolved from the envelope.
    public let code: SPFNGeneratedErrorCode

    /// The status the server actually answered with. See `SPFNAuthFailure.httpStatus`.
    public let httpStatus: Int

    /// The envelope as it arrived. Server-chosen text; it never prints itself.
    public let envelope: SPFNErrorEnvelope

    public init(code: SPFNGeneratedErrorCode, httpStatus: Int, envelope: SPFNErrorEnvelope)
    {
        self.code = code
        self.httpStatus = httpStatus
        self.envelope = envelope
    }
}

/// Everything `SPFNClient.execute` classifies.
///
/// It is not everything `execute` can throw. An error the path did not produce — a
/// `SPFNAuthError` raised while assembling a proof, whatever a caller's own transport
/// raises — passes through unchanged rather than being flattened into one of these, for
/// the same reason the session refuses to wrap: a client-side assembly bug dressed as a
/// server failure is read as a server failure.
public enum SPFNClientError: Error, Equatable, Sendable
{
    /// No response existed. Cancellation arrives here as `.cancelled`, the same way it
    /// does when the transport is the one that observes it.
    case transport(SPFNTransportError)

    /// The server refused the request's authentication.
    case auth(SPFNAuthFailure)

    /// The server refused the request on contract grounds.
    case server(SPFNServerFailure)

    /// A response arrived that the contract cannot describe.
    case decoding(SPFNDecodingFailure)

    /// The operation does not go through `execute`. Only the handshake is in this
    /// position: it is what opens the session every other operation presents, so running
    /// it here would send it without the session bookkeeping that gives it its point.
    /// The associated value is the contract operation id, not anything a server sent.
    case unsupportedOperation(String)

    /// The operation names an auth class this build's contract does not declare, so
    /// nothing was sent. Fail-closed on purpose: the contract's own rule is that an
    /// operation is never downgraded to anonymous handling, and an unknown class sent
    /// with guessed headers would be exactly that. The associated value is the
    /// operation's `authProfile` string from the pinned bundle, not anything a server sent.
    case undeclaredAuthClass(String)
}

// `description` alone is not enough — `String(reflecting:)` and `dump` reach an enum's
// associated values through the mirror — so `debugDescription` is written out too. The
// mirror itself is left alone: every child under it is either a number, a contract-owned
// code, or an envelope that redacts itself.
extension SPFNClientError: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        switch self
        {
        case .transport(let error):
            return "SPFNClientError.transport(\(error))"
        case .auth(let failure):
            return "SPFNClientError.auth(\(failure))"
        case .server(let failure):
            return "SPFNClientError.server(\(failure))"
        case .decoding(let failure):
            return "SPFNClientError.decoding(\(failure.rawValue))"
        case .unsupportedOperation(let id):
            return "SPFNClientError.unsupportedOperation(\(id))"
        case .undeclaredAuthClass(let authProfile):
            return "SPFNClientError.undeclaredAuthClass(\(authProfile))"
        }
    }

    public var debugDescription: String
    {
        description
    }
}

extension SPFNAuthFailure: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNAuthFailure(code: \(code.rawValue), httpStatus: \(httpStatus), envelope: redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}

extension SPFNServerFailure: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNServerFailure(code: \(code.rawValue), httpStatus: \(httpStatus), envelope: redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}

// MARK: - Which codes are an auth failure

extension SPFNGeneratedErrorCode
{
    /// True for the codes a re-handshake could plausibly clear.
    ///
    /// Written as an exhaustive switch rather than a set literal or a status comparison:
    /// a code added to the contract stops this file compiling until someone decides
    /// which side of the line it falls on. A `default` here would silently classify
    /// every future code as a server failure.
    public var isAuthFailure: Bool
    {
        switch self
        {
        case .proofInvalid, .proofReplayed, .proofExpired, .sessionRevoked:
            return true
        case .profileRejected, .contractUnsupported:
            return false
        }
    }
}
