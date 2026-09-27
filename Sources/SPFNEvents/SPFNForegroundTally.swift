// SPFN Mobile — the foreground, counted over scenes.
//
// On an iPad an app can have several windows, and the attachment runs once per scene: the
// app is in the foreground while ANY of its scenes is. So each attachment reports its own
// scene's presence here and hands the stream the tally's answer, and a scene that goes away
// is taken out of the count (docs/architecture/event-stream-design.md §3-3).
//
// Plain Swift behind a lock, so the rule is a Linux test. One tally per stream, found by
// the stream's identity; the entry goes when its last scene does.

import Foundation
import SPFNClient

public final class SPFNForegroundTally: @unchecked Sendable
{
    private static let registryLock = NSLock()
    nonisolated(unsafe) private static var registry: [ObjectIdentifier: SPFNForegroundTally] = [:]

    private let lock = NSLock()
    private var scenes: [UUID: Bool] = [:]
    private let onEmpty: @Sendable () -> Void

    public convenience init()
    {
        self.init(onEmpty: {})
    }

    private init(onEmpty: @escaping @Sendable () -> Void)
    {
        self.onEmpty = onEmpty
    }

    /// The tally every attachment of `stream` shares.
    public static func shared(for stream: SPFNEventStream) -> SPFNForegroundTally
    {
        let key = ObjectIdentifier(stream)
        return registryLock.withLock
        {
            if let tally = registry[key]
            {
                return tally
            }
            let tally = SPFNForegroundTally
            {
                registryLock.withLock { _ = registry.removeValue(forKey: key) }
            }
            registry[key] = tally
            return tally
        }
    }

    /// Whether any counted scene is in the foreground.
    public var isForeground: Bool
    {
        lock.withLock { scenes.values.contains(true) }
    }

    /// Records `scene`'s presence and answers the app's foreground.
    @discardableResult
    public func set(_ scene: UUID, _ presence: SPFNScenePresence) -> Bool
    {
        lock.withLock
        {
            scenes[scene] = presence.isForeground
            return scenes.values.contains(true)
        }
    }

    /// Takes `scene` out of the count and answers the app's foreground.
    @discardableResult
    public func remove(_ scene: UUID) -> Bool
    {
        let (foreground, empty) = lock.withLock
        {
            scenes[scene] = nil
            return (scenes.values.contains(true), scenes.isEmpty)
        }
        if empty
        {
            onEmpty()
        }
        return foreground
    }
}
