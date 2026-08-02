// SPFN Mobile — where the client key comes from.
//
// The session never owns key material. It asks a provider to apply the key to one
// message and gets a proof back, so the only place a key exists as a value is inside
// the provider and inside one call. A property that returned `[UInt8]` would put the
// key in a local variable at every call site, which is one more place it can be logged.
//
// Key custody — Keychain, Keystore, attestation — is not decided (docs/OPEN-DECISIONS.md).
// The in-memory provider below is an alpha stand-in and says so.

/// Supplies the client identity and applies the client key.
public protocol SPFNKeyProvider: Sendable
{
    /// The client identifier the proof is taken over. Not a secret.
    var clientID: String { get }

    /// The key identifier the proof is taken over. Not a secret.
    var keyID: String { get }

    /// Hands the key to `body` for the duration of one call and returns its result.
    ///
    /// The key is never returned, so a caller cannot retain it by accident.
    func withKey<T>(_ body: ([UInt8]) throws -> T) rethrows -> T
}

/// Holds the key in memory for the life of the process.
///
/// Suitable for tests and for the reference-server integration, not for a shipped app:
/// nothing here survives a restart and nothing here is protected by the platform
/// keystore. A real provider is a separate decision.
public struct SPFNInMemoryKeyProvider: SPFNKeyProvider
{
    public let clientID: String
    public let keyID: String

    private let key: [UInt8]

    public init(clientID: String, keyID: String, key: [UInt8])
    {
        self.clientID = clientID
        self.keyID = keyID
        self.key = key
    }

    public func withKey<T>(_ body: ([UInt8]) throws -> T) rethrows -> T
    {
        try body(key)
    }
}

// The default reflection-based description of a struct prints every stored property,
// including the key. Both descriptions are overridden rather than one, because
// `debugDescription` is what a debugger and most logging wrappers reach for.
extension SPFNInMemoryKeyProvider: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNInMemoryKeyProvider(clientID: \(clientID), keyID: \(keyID), key: redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}
