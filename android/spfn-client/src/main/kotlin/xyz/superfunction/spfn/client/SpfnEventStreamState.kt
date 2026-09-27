// SPFN Mobile — the event stream's state, for diagnostics and decoration.
//
// A screen never decides when to read from this value: that is the reread signal's job
// (SpfnEventSignal). The state says what the connection is doing, and the example app's
// events screen prints it (docs/architecture/event-stream-design.md §3-2).
//
// Sources/SPFNClient/SPFNEventStreamState.swift is the same vocabulary in Swift.

package xyz.superfunction.spfn.client

sealed interface SpfnEventStreamState
{
    /** Not the moment to connect: nobody is signed in, or the app is in the background. */
    data class Idle(val reason: SpfnIdleReason) : SpfnEventStreamState

    /** Minting a token or opening the stream. `attempt` counts from the last stable open. */
    data class Connecting(val attempt: Int) : SpfnEventStreamState

    /**
     * The server's `connected` frame arrived. `epoch` counts opens of this object, from 1.
     * `unavailableEvents` are configured names the server does not serve; the stream was
     * reopened without them (§10 Q-F).
     */
    data class Open(val epoch: Int, val unavailableEvents: List<String> = emptyList()) : SpfnEventStreamState

    /** Waiting `delayMillis` before the next attempt. */
    data class Retrying(val attempt: Int, val delayMillis: Long, val reason: SpfnRetryReason) : SpfnEventStreamState

    /** The moment to connect, but no network: waiting for one, with no timer. */
    data class Offline(val attempt: Int) : SpfnEventStreamState

    /** Refused for good. Left only when a condition drops and returns (E-44, E-43). */
    data class Closed(val reason: SpfnCloseReason) : SpfnEventStreamState
}

enum class SpfnIdleReason
{
    SIGNED_OUT,
    BACKGROUND
}

sealed interface SpfnRetryReason
{
    data object Network : SpfnRetryReason

    data object Silence : SpfnRetryReason

    data object ServerClosed : SpfnRetryReason

    data class ServerError(val status: Int) : SpfnRetryReason

    data object TokenRejected : SpfnRetryReason

    data object Unreadable : SpfnRetryReason
}

sealed interface SpfnCloseReason
{
    data object Unauthorized : SpfnCloseReason

    data object Forbidden : SpfnCloseReason

    data class UnknownEvents(val names: List<String>) : SpfnCloseReason
}
