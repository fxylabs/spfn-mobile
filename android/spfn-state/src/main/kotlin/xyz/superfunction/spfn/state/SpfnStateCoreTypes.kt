// SPFN Mobile — what goes into the state client's decisions and what comes out.
//
// The decisions are SpfnStateCore's: one input at a time, a list of effects back. The
// runner (SpfnStateClient's engine) performs the effects — a request through `execute`, a
// query handed to the stream, a timer, an entry handed to a subscriber — and feeds each
// result back as an input. Nothing in the core waits, so the shared conformance fixtures
// drive it step by step, the way SpfnEventStreamMachine is driven (§4).
//
// Sources/SPFNState/SPFNStateCoreTypes.swift is the same vocabulary in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue

/** Sends one call. In the app, the client signed in at that moment (`signedInClient()`). */
internal interface SpfnStateExecutor
{
    suspend fun <Req, Resp> execute(call: SpfnCall<Req, Resp>, request: Req): Resp
}

/** One request the core wants sent, and what it is for. */
internal class SpfnStateRequest(val kind: Kind, val perform: suspend (SpfnStateExecutor) -> SpfnCanonicalValue)
{
    sealed interface Kind
    {
        data class Snapshot(val query: SpfnStateKey) : Kind

        data class Read(val slot: SpfnStateSlot) : Kind

        data class Write(val slot: SpfnStateSlot) : Kind
    }
}

/** What an app call that the core answers resolves to. */
internal sealed interface SpfnStateResolution
{
    /** A typed `SpfnStateFrame<V, D>`. */
    data class Frame(val typed: Any?) : SpfnStateResolution

    data class Snapshot(val snapshot: SpfnStatesSnapshot) : SpfnStateResolution
}

internal sealed interface SpfnStateInput
{
    data class Subscribe(val subscription: Int, val slot: SpfnStateSlot) : SpfnStateInput

    data class Unsubscribe(val subscription: Int) : SpfnStateInput

    /** The end of the turn the subscriptions were made in: the query is computed once (K-6). */
    data object Flush : SpfnStateInput

    data class Wire(val signal: SpfnWireSignal) : SpfnStateInput

    data class Stream(val state: SpfnEventStreamState) : SpfnStateInput

    data class Answered(val request: Int, val result: Result<SpfnCanonicalValue>) : SpfnStateInput

    data class Get(val call: Int, val slot: SpfnStateSlot) : SpfnStateInput

    class Write(val call: Int, val slot: SpfnStateSlot, val request: SpfnStateRequest, val decode: (SpfnCanonicalValue) -> Any?) : SpfnStateInput

    data class Snapshot(val call: Int, val query: SpfnStateKey?) : SpfnStateInput

    data object Retry : SpfnStateInput

    data object Close : SpfnStateInput

    data class RetryElapsed(val generation: Int) : SpfnStateInput

    /**
     * The key lifecycle's signed-in client id: the first is the baseline, every other one
     * that differs ends what the store holds for the last (§2-14).
     */
    data class Subject(val clientId: String?) : SpfnStateInput
}

internal sealed interface SpfnStateEffect
{
    /** An entry for one subscription; null is `Loading`. */
    data class Deliver(val subscription: Int, val cell: SpfnStateCell?) : SpfnStateEffect

    data class Finish(val subscription: Int) : SpfnStateEffect

    /** The entry `current` answers from now on. */
    data class Publish(val slot: SpfnStateSlot, val cell: SpfnStateCell) : SpfnStateEffect

    /** No entry any more: `current` answers `Loading` for every state (§2-14). */
    data object Clear : SpfnStateEffect

    data class Execute(val request: Int, val what: SpfnStateRequest) : SpfnStateEffect

    data class SetQuery(val query: SpfnStateKey) : SpfnStateEffect

    data object ReconnectNow : SpfnStateEffect

    data class Connection(val connection: SpfnStateConnection) : SpfnStateEffect

    data class StartRetryTimer(val generation: Int, val millis: Long) : SpfnStateEffect

    data object CancelRetryTimer : SpfnStateEffect

    data class Report(val error: Throwable) : SpfnStateEffect

    data class Resolve(val call: Int, val result: Result<SpfnStateResolution>) : SpfnStateEffect

    data object CountDropped : SpfnStateEffect

    /** Run `Flush` at the end of this turn; asked for once per turn. */
    data object ScheduleFlush : SpfnStateEffect

    data object AttachWire : SpfnStateEffect

    data object DetachWire : SpfnStateEffect
}
