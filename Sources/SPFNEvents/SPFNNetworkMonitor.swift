// SPFN Mobile — the network observer: NWPathMonitor as a stream of the network input.
//
// Guarded whole on Network, which only Apple ships; on Linux the module has no network
// observer and an app without one simply never calls `setNetworkAvailable`, which the
// stream recovers from by backoff (§3-6). `tools/validate/validate.sh` §8 holds the guard.

#if canImport(Network)
import Foundation
import Network

enum SPFNNetworkMonitor
{
    /// Whether a satisfied path exists, now and on every change, until the consumer stops.
    /// The monitor is cancelled when the stream terminates.
    static func availability() -> AsyncStream<Bool>
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
