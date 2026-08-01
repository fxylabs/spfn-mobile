// SPFN Mobile — what this checkout actually is.

/// Errors raised by entry points that are declared but not implemented yet.
///
/// Everything outside the Step 2 vertical slice still throws rather than returning an
/// empty success, so a partial build cannot be mistaken for a working SDK.
public enum SPFNScaffoldError: Error, Equatable, Sendable
{
    /// The payload names the symbol and the step that will implement it.
    case notImplementedInScaffold(symbol: String, plannedStep: String)
}

/// Machine-readable statement of what this checkout actually is.
public enum SPFNScaffold
{
    /// Still true. Step 2 added a real vertical slice — canonical serialization,
    /// clientProofV1, dual codegen and a conformance gate — but nothing has been
    /// committed, tagged, published or independently reviewed, and transport,
    /// persistence and the hybrid bridge do not exist.
    public static let isScaffold: Bool = true

    /// Human-readable disclaimer carried in-band with the binary.
    public static let disclaimer: String = """
        SPFN Mobile Step 2 vertical slice. Canonical serialization, clientProofV1 proof \
        assembly, generated clients and a cross-platform conformance gate exist; \
        transport, persistence and the hybrid bridge do not. There is \
        no supported release, no registry publication, and no public support of any \
        distribution channel.
        """
}
