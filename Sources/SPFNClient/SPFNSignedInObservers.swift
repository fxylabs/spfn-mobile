// SPFN Mobile — the signed-in client id, mirrored for observers that cannot wait.
//
// `SPFNKeyLifecycle.signedInClientIDs` hands its values over an async sequence, so a watcher
// hears of a change some time after the store was written. The state client cannot let that
// time pass: from the moment another account's key is stored, no value it holds for the
// previous account may be read (state-client-design.md §2-14). This mirror is written inside
// the call that writes the store and calls its observers there, on the caller's thread,
// before that call returns.
//
// android/spfn-client/.../SpfnSignedInObservers.kt is the same mirror in Kotlin.

import Foundation

/// One observer of the signed-in client id; `cancel()` removes it. Idempotent.
package final class SPFNSignedInObservation: Sendable
{
    private let cancelling: @Sendable () -> Void

    init(cancel: @escaping @Sendable () -> Void)
    {
        cancelling = cancel
    }

    package func cancel()
    {
        cancelling()
    }
}

/// The stored client id, kept equal to the active slot by every call that writes it.
final class SPFNSignedInMirror: @unchecked Sendable
{
    private let lock = NSLock()
    private var clientID: String?
    private var observers: [UUID: @Sendable (String?) -> Void] = [:]

    init(_ clientID: String?)
    {
        self.clientID = clientID
    }

    /// Calls `observer` with the id now, then with every change. Both under one lock, so no
    /// change falls between the registration and the value it starts from.
    func observe(_ observer: @escaping @Sendable (String?) -> Void) -> SPFNSignedInObservation
    {
        let id = UUID()
        lock.withLock
        {
            observers[id] = observer
            observer(clientID)
        }
        return SPFNSignedInObservation { [weak self] in self?.remove(id) }
    }

    /// The id the store holds now. Observers hear it once, and only when it changed: a
    /// rotation stores the same id (E-41).
    func set(_ next: String?)
    {
        lock.withLock
        {
            guard next != clientID
            else
            {
                return
            }
            clientID = next
            observers.values.forEach { $0(next) }
        }
    }

    private func remove(_ id: UUID)
    {
        _ = lock.withLock { observers.removeValue(forKey: id) }
    }
}
