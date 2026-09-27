// SPFN Mobile — what a listener receives, and how a payload describes itself.
//
// A frame is a signal, not state: delivery is at most once and a missed frame never
// returns. So besides the frames that pass its condition, a listener receives a reread
// signal whenever what it holds may be stale — when it attaches, when a new connection
// opens, when its own queue overflowed — and reads again (design §3-4).
//
// Sources/SPFNClient/SPFNEventSignal.swift is the same vocabulary in Swift.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnCanonicalValue

sealed interface SpfnEventSignal<out E>
{
    /** Read again. Delivered whatever the listener's condition says (L-13). */
    data class Reread(val cause: SpfnRereadCause) : SpfnEventSignal<Nothing>

    /** One frame whose payload decoded and passed the listener's condition. */
    data class Frame<E>(val value: E) : SpfnEventSignal<E>

    /**
     * The server does not serve this listener's event name, so the stream runs without it
     * and no frame of it will arrive (§10 Q-F). Sent once per listener. Rereads still come.
     */
    data object Unavailable : SpfnEventSignal<Nothing>
}

sealed interface SpfnRereadCause
{
    data object Attached : SpfnRereadCause

    data class Opened(val epoch: Int) : SpfnRereadCause

    data object Overflow : SpfnRereadCause
}

/**
 * An event name and its decoder in one place. A payload's `companion object` implements
 * it, so a call site writes the payload's name alone: `listen(SessionActivity)`.
 */
interface SpfnEventPayload<E>
{
    val eventName: String

    fun decode(value: SpfnCanonicalValue): E
}

/**
 * Counts, never contents: a payload is never logged (§6).
 *
 * @property droppedFrames frames a listener's decoder could not read (L-11).
 * @property filteredFrames frames a listener's condition declined (L-10).
 * @property unexpectedFrames frames named outside the configured list (E-23).
 */
data class SpfnEventDiagnostics(
    val droppedFrames: Long = 0,
    val filteredFrames: Long = 0,
    val unexpectedFrames: Long = 0
)
