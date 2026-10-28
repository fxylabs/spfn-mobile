// SPFN Mobile — the signed-in client id, mirrored for observers that cannot wait.
//
// `SpfnKeyLifecycle.signedInClientId` is a StateFlow, so a collector hears of a change some
// time after the store was written. The state client cannot let that time pass: from the
// moment another account's key is stored, no value it holds for the previous account may be
// read (state-client-design.md §2-14). This mirror is written inside the call that writes the
// store and calls its observers there, on the caller's thread, before that call returns.
//
// Sources/SPFNClient/SPFNSignedInObservers.swift is the same mirror in Swift.

package xyz.superfunction.spfn.client

/**
 * Marks the synchronous signed-in observer of [SpfnKeyLifecycle].
 *
 * It is the state client's, which lives in another Gradle module, so `internal` cannot hold
 * it. An app reads [SpfnKeyLifecycle.signedInClientId]. `@RequiresOptIn` is a Kotlin rule
 * only, so the function also carries `@JvmSynthetic`: Java does not see it either
 * (docs/IMPLEMENTATION-PITFALLS.md P15). Swift's counterpart is `package`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "The synchronous signed-in observer is the state client's; an app reads signedInClientId."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class SpfnSignedInObserverAccess

/** One observer of the signed-in client id; [cancel] removes it. Idempotent. */
@SpfnSignedInObserverAccess
class SpfnSignedInObservation internal constructor(private val cancelling: () -> Unit)
{
    @JvmSynthetic
    fun cancel()
    {
        cancelling();
    }
}

/** The stored client id, kept equal to the active slot by every call that writes it. */
internal class SpfnSignedInMirror(private var clientId: String?)
{
    private val lock = Any();
    private val observers = mutableMapOf<Any, (String?) -> Unit>();

    /**
     * Calls [observer] with the id now, then with every change. Both under one lock, so no
     * change falls between the registration and the value it starts from.
     */
    @OptIn(SpfnSignedInObserverAccess::class)
    fun observe(observer: (String?) -> Unit): SpfnSignedInObservation
    {
        val token = Any();
        synchronized(lock) {
            observers[token] = observer;
            observer(clientId);
        };
        return SpfnSignedInObservation { synchronized(lock) { observers.remove(token) } };
    }

    /**
     * The id the store holds now. Observers hear it once, and only when it changed: a
     * rotation stores the same id (E-41).
     */
    fun set(next: String?)
    {
        synchronized(lock) {
            if (next == clientId)
            {
                return;
            }
            clientId = next;
            observers.values.forEach { it(next) };
        };
    }
}
