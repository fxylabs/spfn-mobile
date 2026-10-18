// SPFN Mobile — the network observer: NWPathMonitor as a stream of the network input.
//
// Public since the app root packages read it (superself-apps packages/spfn-app): the event
// stream's attachment and an app's root share one reading of the platform's network, so
// the two never disagree about whether there is one. The stream's rule is the attachment's
// — `setNetworkAvailable` on every element — and this type only observes.
//
// Guarded whole on Network, which only Apple ships; on Linux the module has no network
// observer and an app without one simply never calls `setNetworkAvailable`, which the
// stream recovers from by backoff (§3-6). `tools/validate/validate.sh` §8 holds the guard.
//
// android/spfn-events/.../SpfnNetworkMonitor.kt is the same observer in Kotlin.

#if canImport(Network)
import Foundation
import Network

public enum SPFNNetworkMonitor
{
    /// Whether a satisfied path exists, now and on every change, until the consumer stops.
    /// The monitor is cancelled when the stream terminates.
    public static func availability() -> AsyncStream<Bool>
    {
        AsyncStream
        {
            continuation in
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler =
            {
                path in
                continuation.yield(SPFNNetworkPathStatus(path.status).isAvailable)
            }
            continuation.onTermination =
            {
                _ in
                monitor.cancel()
            }
            monitor.start(queue: DispatchQueue(label: "xyz.superfunction.spfn.events.path"))
        }
    }
}

extension SPFNNetworkPathStatus
{
    init(_ status: NWPath.Status)
    {
        switch status
        {
        case .satisfied:
            self = .satisfied
        case .requiresConnection:
            self = .requiresConnection
        case .unsatisfied:
            self = .unsatisfied
        @unknown default:
            self = .unsatisfied
        }
    }
}
#endif
