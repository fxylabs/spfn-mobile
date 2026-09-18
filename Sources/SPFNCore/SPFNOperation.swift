// SPFN Mobile — the operation descriptor shared by hand-written and generated code.
//
// This type is hand-written and stable. The per-operation values that fill it in live in
// SPFNGenerated and are produced by tools/contract-codegen from the pinned bundle, so
// adding an operation never means editing this file.

/// One contract operation, as the pinned bundle describes it.
public struct SPFNOperation: Equatable, Sendable
{
    public let id: String
    public let method: String
    public let path: String
    public let authProfile: String
    public let requiresSession: Bool

    /// Whether the contract declares a response body for this operation.
    ///
    /// `false` is the contract's own bodyless shape, stated by `restOperations.responseBody`
    /// from 0.10.0 on: "An operation that declares no responseType answers 204 with an empty
    /// body and there is nothing to decode". `SPFNClient` reads this rather than the
    /// operation id, so a later bodyless operation needs no change to the execute path.
    ///
    /// No default. Every descriptor states it, because a defaulted `true` would make the
    /// bodyless case something a hand-built descriptor forgets rather than something it
    /// declares.
    public let declaresResponse: Bool

    public init(
        id: String,
        method: String,
        path: String,
        authProfile: String,
        requiresSession: Bool,
        declaresResponse: Bool
    )
    {
        self.id = id
        self.method = method
        self.path = path
        self.authProfile = authProfile
        self.requiresSession = requiresSession
        self.declaresResponse = declaresResponse
    }
}

/// What an operation that declares no response body answers with.
///
/// A type of its own rather than `Void`: `Void` is not `Equatable` and cannot be the
/// `Response` of an `SPFNCall`, and a caller that got `()` back could not tell it apart
/// from a function that returns nothing at all. This value means "the server accepted it
/// and said nothing", which is a result.
///
/// Kotlin's counterpart is the `SpfnNoResponse` object, not `Unit`, for the same reason
/// on that side: a Java caller sees a named type rather than `kotlin.Unit`.
public struct SPFNNoResponse: Equatable, Sendable
{
    public static let value = SPFNNoResponse()

    public init() {}
}
