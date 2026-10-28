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

/**
 * What a wire listener receives (`listenWire`, revision 4, S-2): several names in ONE
 * queue, values left as the server sent them, and the connection's own facts beside them.
 * One queue is the point: a frame of connection e is never handled before `Opened` of e.
 */
sealed interface SpfnWireSignal
{
    /** The first signal, whatever the connection is doing. */
    data object Attached : SpfnWireSignal

    /**
     * A connection opened — or, for a listener attached while one is open, that connection
     * once more right after `Attached`. Queued frames of older connections are dropped.
     */
    data class Opened(val connected: SpfnStreamConnected) : SpfnWireSignal

    /** One frame of one of the listener's names: the envelope's `data`, undecoded. */
    data class Frame(val name: String, val value: SpfnCanonicalValue) : SpfnWireSignal

    /** The server granted or revoked one of the listener's names on the open connection (S-3). */
    data class Subscription(val name: String, val status: SpfnSubscriptionStatus) : SpfnWireSignal

    /** This listener's queue filled; its queued frames were dropped. Connection signals stay. */
    data object Overflow : SpfnWireSignal

    /** Names of this listener the server does not serve; each name is reported once. */
    data class Unavailable(val names: List<String>) : SpfnWireSignal
}

/**
 * The server's `connected` frame, with what the client knows about the connection it opened.
 *
 * @property epoch the stream's open count, the `epoch` of `Open`.
 * @property query the query fields this connection was opened with (`setQuery`).
 * @property initial the events whose initial frames follow; null from a server that predates them.
 * @property clientId the signed-in client id this connection was opened for (`setSignedIn`).
 *   A change of account begins a new connection (E-43), so an older connection never carries
 *   the new id: the state client trusts the wire again only once one does
 *   (state-client-design.md §2-14).
 */
data class SpfnStreamConnected(
    val epoch: Int,
    val query: Map<String, String>,
    val subscribedEvents: List<String>,
    val deniedEvents: List<String>,
    val initial: List<String>?,
    val clientId: String? = null
)

enum class SpfnSubscriptionStatus
{
    GRANTED,
    REVOKED
}
