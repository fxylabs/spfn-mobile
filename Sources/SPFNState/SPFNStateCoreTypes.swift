// SPFN Mobile — what goes into the state client's decisions and what comes out.
//
// The decisions are SPFNStateCore's: one input at a time, a list of effects back. The
// runner (SPFNStateClient's engine) performs the effects — a request through `execute`, a
// query handed to the stream, a timer, an entry handed to a subscriber — and feeds each
// result back as an input. Nothing in the core waits, so the shared conformance fixtures
// drive it step by step, the way SPFNEventStreamMachine is driven (§4).
//
// android/spfn-state/.../SpfnStateCoreTypes.kt is the same vocabulary in Kotlin.

import SPFNClient
import SPFNCore

/// Sends one call. In the app, the client signed in at that moment (`signedInClient()`).
protocol SPFNStateExecutor: Sendable
{
    func execute<Request: Sendable, Response: Sendable>(_ call: SPFNCall<Request, Response>, request: Request) async throws -> Response
}

/// One request the core wants sent, and what it is for.
struct SPFNStateRequest: Sendable
{
    enum Kind: Equatable, Sendable
    {
        case snapshot(query: SPFNStateKey)
        case read(SPFNStateSlot)
        case write(SPFNStateSlot)
    }

    let kind: Kind
    let perform: @Sendable (any SPFNStateExecutor) async throws -> SPFNCanonicalValue
}

/// What an app call that the core answers resolves to.
enum SPFNStateResolution: Sendable
{
    /// A typed `SPFNStateFrame<Value, Data>`.
    case frame(any Sendable)
    case snapshot(SPFNStatesSnapshot)
}

enum SPFNStateInput: Sendable
{
    case subscribe(subscription: Int, slot: SPFNStateSlot)
    case unsubscribe(subscription: Int)
    /// The end of the turn the subscriptions were made in: the query is computed once (K-6).
    case flush
    case wire(SPFNWireSignal)
    case stream(SPFNEventStreamState)
    case answered(request: Int, Result<SPFNCanonicalValue, any Error>)
    case get(call: Int, slot: SPFNStateSlot)
    case write(call: Int, slot: SPFNStateSlot, request: SPFNStateRequest, decode: @Sendable (SPFNCanonicalValue) throws -> any Sendable)
    case snapshot(call: Int, query: SPFNStateKey?)
    case retry
    case close
    case retryElapsed(generation: Int)
}

enum SPFNStateEffect: Sendable
{
    /// An entry for one subscription; nil is `loading`.
    case deliver(subscription: Int, SPFNStateCell?)
    case finish(subscription: Int)
    /// The entry `current` answers from now on.
    case publish(SPFNStateSlot, SPFNStateCell)
    case execute(request: Int, SPFNStateRequest)
    case setQuery(SPFNStateKey)
    case reconnectNow
    case connection(SPFNStateConnection)
    case startRetryTimer(generation: Int, millis: Int64)
    case cancelRetryTimer
    case report(any Error)
    case resolve(call: Int, Result<SPFNStateResolution, any Error>)
    case countDropped
    /// Run `flush` at the end of this turn; asked for once per turn.
    case scheduleFlush
    case attachWire
    case detachWire
}
