// SPFN Mobile — what an app tells the event stream once, at its composition root.
//
// The stream path, the fixed list of event names, the server's ping interval and whether
// the token route wants a session. Every
// refusal happens here, at construction, so a wrong list is found on the first run rather
// than as a stream that never opens (docs/architecture/event-stream-design.md §3-1).
//
// android/spfn-client/.../SpfnEventStreamConfiguration.kt is the same type in Kotlin.

/// Why a configuration was refused. `field` names the argument, never its value.
public enum SPFNEventStreamError: Error, Equatable, Sendable
{
    case invalidConfiguration(field: String)
}

/// The reconnect schedule: `initialMillis`, multiplied per consecutive failure up to
/// `maxMillis`, then multiplied by a jitter drawn from [0.5, 1.0].
public struct SPFNEventStreamBackoff: Equatable, Sendable
{
    public let initialMillis: Int64
    public let multiplier: Int64
    public let maxMillis: Int64

    public init(initialMillis: Int64, multiplier: Int64, maxMillis: Int64) throws
    {
        guard initialMillis > 0, multiplier >= 1, maxMillis >= initialMillis
        else
        {
            throw SPFNEventStreamError.invalidConfiguration(field: "backoff")
        }
        self.initialMillis = initialMillis
        self.multiplier = multiplier
        self.maxMillis = maxMillis
    }

    private init(checkedInitial: Int64, multiplier: Int64, max: Int64)
    {
        self.initialMillis = checkedInitial
        self.multiplier = multiplier
        self.maxMillis = max
    }

    /// 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s: the design's §4 numbers.
    public static let standard = SPFNEventStreamBackoff(checkedInitial: 1_000, multiplier: 2, max: 30_000)

    /// The delay before the retry that follows `failure` consecutive failures (1-based).
    /// `jitter` is clamped into [0.5, 1.0] so an injected source cannot produce zero.
    public func delayMillis(failure: Int, jitter: Double) -> Int64
    {
        var base = initialMillis
        for _ in 0 ..< max(failure, 1) - 1
        {
            base = min(base * multiplier, maxMillis)
        }
        return Int64(Double(min(base, maxMillis)) * min(max(jitter, 0.5), 1.0))
    }
}

public struct SPFNEventStreamConfiguration: Sendable
{
    /// The configured names, deduplicated and sorted: the order the query carries them in.
    public let events: [String]
    public let streamPath: String
    public let tokenPath: String
    /// The SERVER's ping interval. The silence watchdog is 2.5 times it.
    public let pingIntervalMillis: Int64
    public let backoff: SPFNEventStreamBackoff
    /// One listener's queue length before it is replaced by one reread.
    public let deliveryBuffer: Int
    /// Whether the token call presents a session, opened first through the handshake route.
    /// False by default: the call is signed by the key alone, like an app's own signed calls.
    public let tokenRequiresSession: Bool

    /// - Parameters:
    ///   - events: the names this app receives on the stream — the server event router's
    ///     KEYS. Sorted and comma-joined, they are the `events=` query. Duplicates count once.
    ///   - tokenPath: derived from `streamPath` by the server's own rule when nil
    ///     (`/events/stream` becomes `/events/token`).
    /// - Throws: `SPFNEventStreamError.invalidConfiguration` for an empty list, an empty
    ///   name, a name holding a comma, or a path that does not begin with `/` or carries a query.
    public init(
        events: [String],
        streamPath: String = "/events/stream",
        tokenPath: String? = nil,
        pingIntervalMillis: Int64 = 10_000,
        backoff: SPFNEventStreamBackoff = .standard,
        deliveryBuffer: Int = 256,
        tokenRequiresSession: Bool = false
    ) throws
    {
        let names = Array(Set(events)).sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
        guard !names.isEmpty, names.allSatisfy({ !$0.isEmpty && !$0.contains(",") })
        else
        {
            throw SPFNEventStreamError.invalidConfiguration(field: "events")
        }
        let token = tokenPath ?? Self.derivedTokenPath(streamPath)
        try Self.requirePath(streamPath, field: "streamPath")
        try Self.requirePath(token, field: "tokenPath")
        guard pingIntervalMillis > 0, deliveryBuffer > 0
        else
        {
            throw SPFNEventStreamError.invalidConfiguration(field: pingIntervalMillis > 0 ? "deliveryBuffer" : "pingIntervalMillis")
        }
        self.events = names
        self.streamPath = streamPath
        self.tokenPath = token
        self.pingIntervalMillis = pingIntervalMillis
        self.backoff = backoff
        self.deliveryBuffer = deliveryBuffer
        self.tokenRequiresSession = tokenRequiresSession
    }

    /// 2.5 × the server's ping interval: two missed pings and half an interval more.
    public var silenceMillis: Int64
    {
        pingIntervalMillis * 5 / 2
    }

    /// Whether a listener may ask for `name`. The one check behind L-3, pulled out so it
    /// can be tested: the `preconditionFailure` that uses it stops the process.
    public func contains(_ name: String) -> Bool
    {
        events.contains(name)
    }

    /// The server's rule: the last path segment of the stream path becomes `token`.
    /// `/events/stream` → `/events/token`, `/sse` → `/token`.
    public static func derivedTokenPath(_ streamPath: String) -> String
    {
        guard let slash = streamPath.lastIndex(of: "/")
        else
        {
            return "token"
        }
        return String(streamPath[...slash]) + "token"
    }

    private static func requirePath(_ path: String, field: String) throws
    {
        guard path.hasPrefix("/"), !path.contains("?"), !path.contains("#")
        else
        {
            throw SPFNEventStreamError.invalidConfiguration(field: field)
        }
    }
}
