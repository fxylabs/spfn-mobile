// SPFN Mobile — the event stream's connection, as a pure state machine.
//
// `(state, input) -> (state, effects)` and nothing else: no clock, no socket, no task.
// The runner in SPFNEventStream performs the effects and feeds every asynchronous result
// back as an input carrying the generation it was issued under; a result from an older
// generation is dropped (E-39). That is what makes the whole of
// docs/architecture/event-stream-design.md §4-1 a unit test on Linux.
//
// The listener hub is NOT an input here. Nothing a screen does — attaching, detaching, a
// condition true or false — reaches this machine, which is the proof that navigation
// never reconnects and that a condition never reaches the server (§4-2).
//
// android/spfn-client/.../SpfnEventStreamMachine.kt is the same table in Kotlin.

/// A minted one-use token. Never printed: its only reader is the stream request.
public struct SPFNEventStreamToken: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    let value: String

    init(_ value: String)
    {
        self.value = value
    }

    public var description: String
    {
        "SPFNEventStreamToken(redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}

/// Where the connection is. `publicState` folds this and the counters into the API's state.
enum SPFNEventPhase: Equatable, Sendable
{
    case idle(SPFNIdleReason)
    case token
    case stream(headersReceived: Bool)
    case open
    case retrying(delayMillis: Int64, reason: SPFNRetryReason)
    case offline
    case closed(SPFNCloseReason)
}

/// The machine's whole memory. See the Kotlin twin for what each counter means.
struct SPFNEventMachineState: Equatable, Sendable
{
    var phase: SPFNEventPhase
    var requested: [String]
    var unavailable: [String] = []
    var attempt = 1
    var epoch = 0
    var generation: Int64 = 0
    var foreground = false
    var clientID: String?
    var networkAvailable = true
    var retriedTokenRejection = false
    var stable = false

    var publicState: SPFNEventStreamState
    {
        switch phase
        {
        case .idle(let reason):
            return .idle(reason)
        case .token, .stream:
            return .connecting(attempt: attempt)
        case .open:
            return .open(epoch: epoch, unavailableEvents: unavailable)
        case .retrying(let delay, let reason):
            return .retrying(attempt: attempt, delayMillis: delay, reason: reason)
        case .offline:
            return .offline(attempt: attempt)
        case .closed(let reason):
            return .closed(reason)
        }
    }

    /// W: foreground and signed in. The only condition a connection exists under.
    var wantsConnection: Bool
    {
        foreground && clientID != nil
    }

    /// Headers arrived: the silence watchdog runs and frames are read.
    var receiving: Bool
    {
        phase == .open || phase == .stream(headersReceived: true)
    }
}

/// How a token call ended, already read off `SPFNClientError` (see SPFNEventStream).
enum SPFNTokenFailure: Equatable, Sendable
{
    case unauthorized
    case forbidden
    case serverError(status: Int, retryAfterMillis: Int64?)
    case network
    case unreadable
}

/// How a stream request was answered, read off its status, headers and body.
enum SPFNStreamAnswer: Equatable, Sendable
{
    case eventStream
    case notEventStream
    case tokenRejected
    case invalidEvents(invalid: [String], valid: [String])
    case badRequest
    case forbidden
    case serverError(status: Int, retryAfterMillis: Int64?)
}

enum SPFNEventInput: Equatable, Sendable
{
    case setForeground(Bool)
    case setSignedIn(String?)
    case setNetworkAvailable(Bool)
    case tokenMinted(generation: Int64, token: SPFNEventStreamToken)
    case tokenFailed(generation: Int64, failure: SPFNTokenFailure)
    case streamAnswered(generation: Int64, answer: SPFNStreamAnswer)
    case streamFailed(generation: Int64)
    case streamEnded(generation: Int64)
    case bytesReceived(generation: Int64)
    case frameReceived(generation: Int64, event: SPFNSSEEvent)
    case silenceElapsed(generation: Int64)
    case retryElapsed(generation: Int64)
    case stableElapsed(generation: Int64)

    /// The generation an asynchronous result was issued under; nil for the three inputs.
    var generation: Int64?
    {
        switch self
        {
        case .setForeground, .setSignedIn, .setNetworkAvailable:
            return nil
        case .tokenMinted(let generation, _), .tokenFailed(let generation, _), .streamAnswered(let generation, _),
             .frameReceived(let generation, _):
            return generation
        case .streamFailed(let generation), .streamEnded(let generation), .bytesReceived(let generation),
             .silenceElapsed(let generation), .retryElapsed(let generation), .stableElapsed(let generation):
            return generation
        }
    }
}

enum SPFNEventEffect: Equatable, Sendable
{
    case mintToken(generation: Int64)
    case cancelToken
    case openStream(generation: Int64, names: [String], token: SPFNEventStreamToken)
    case closeStream
    case startSilenceTimer(generation: Int64, millis: Int64)
    case startRetryTimer(generation: Int64, millis: Int64)
    case startStableTimer(generation: Int64, millis: Int64)
    case cancelTimers
    case deliver(name: String, data: String)
    case reread(epoch: Int)
    case markUnavailable([String])
    case countUnexpected(name: String)
    case publish(SPFNEventStreamState)

    /// Every in-flight effect of the previous generation, stopped.
    static let abandon: [SPFNEventEffect] = [.cancelToken, .closeStream, .cancelTimers]
}

struct SPFNEventStep: Equatable, Sendable
{
    var state: SPFNEventMachineState
    var effects: [SPFNEventEffect]
}

/// The E-table. `jitter` returns a factor in [0.5, 1.0] and is the machine's only source of
/// randomness, injected so a suite can fix it.
struct SPFNEventStreamMachine: Sendable
{
    static let connected = "connected"
    static let ping = "ping"

    /// An open this long resets `attempt` to 1 (E-47).
    static let stableMillis: Int64 = 30_000

    let configuration: SPFNEventStreamConfiguration
    let jitter: @Sendable () -> Double

    /// Created: `idle(signedOut)`, background, signed out, network assumed present.
    func initial() -> SPFNEventMachineState
    {
        SPFNEventMachineState(phase: .idle(.signedOut), requested: configuration.events)
    }

    func step(_ state: SPFNEventMachineState, _ input: SPFNEventInput) -> SPFNEventStep
    {
        let step: SPFNEventStep
        switch input
        {
        case .setForeground(let isForeground):
            step = setForeground(state, isForeground)
        case .setSignedIn(let clientID):
            step = setSignedIn(state, clientID)
        case .setNetworkAvailable(let isAvailable):
            step = setNetworkAvailable(state, isAvailable)
        default:
            step = input.generation == state.generation ? issued(state, input) : unchanged(state)
        }
        return published(before: state, step)
    }

    // MARK: - The three condition inputs

    private func setForeground(_ state: SPFNEventMachineState, _ isForeground: Bool) -> SPFNEventStep
    {
        guard state.foreground != isForeground
        else
        {
            return unchanged(state)
        }
        var next = state
        next.foreground = isForeground
        return reconcile(next)
    }

    private func setSignedIn(_ state: SPFNEventMachineState, _ clientID: String?) -> SPFNEventStep
    {
        guard state.clientID != clientID
        else
        {
            return unchanged(state)
        }
        let switched = state.clientID != nil && clientID != nil
        var next = state
        next.clientID = clientID
        guard switched, next.wantsConnection
        else
        {
            return reconcile(next)
        }
        // Another account (E-43): whatever was open belongs to the old subject, and the
        // narrowing learned under it is forgotten with it.
        var begun = begin(fresh(next), attempt: 1)
        begun.effects += unavailableChange(state, begun.state)
        return begun
    }

    private func setNetworkAvailable(_ state: SPFNEventMachineState, _ isAvailable: Bool) -> SPFNEventStep
    {
        guard state.networkAvailable != isAvailable
        else
        {
            return unchanged(state)
        }
        var next = state
        next.networkAvailable = isAvailable
        switch next.phase
        {
        case .retrying where !isAvailable:
            // E-34: a retry timer against no network would only fail as `network`.
            next.phase = .offline
            next.generation += 1
            return SPFNEventStep(state: next, effects: [.cancelTimers])
        case .offline where isAvailable:
            // E-35: at once; the delay that was being waited out was already dropped.
            return begin(next, attempt: next.attempt)
        default:
            // E-36, E-51, E-45, E-49: recorded only.
            return unchanged(next)
        }
    }

    /// After F or A changed: idle when W is false, a first attempt when it just became true.
    private func reconcile(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        if !state.wantsConnection
        {
            return idle(state)
        }
        if case .idle = state.phase
        {
            return begin(fresh(state), attempt: 1)
        }
        return unchanged(state)
    }

    private func idle(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        let reason: SPFNIdleReason = state.clientID == nil ? .signedOut : .background
        var next = state
        if case .idle = state.phase
        {
            next.phase = .idle(reason)
            return unchanged(next)
        }
        next = fresh(state)
        next.phase = .idle(reason)
        next.generation = state.generation + 1
        return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon + unavailableChange(state, next))
    }

    // MARK: - Asynchronous results

    private func issued(_ state: SPFNEventMachineState, _ input: SPFNEventInput) -> SPFNEventStep
    {
        switch input
        {
        case .tokenMinted(_, let token):
            return tokenMinted(state, token)
        case .tokenFailed(_, let failure):
            return tokenFailed(state, failure)
        case .streamAnswered(_, let answer):
            return streamAnswered(state, answer)
        case .streamFailed:
            return dropped(state, .network)
        case .streamEnded:
            return dropped(state, .serverClosed)
        case .silenceElapsed:
            return state.receiving ? dropped(state, .silence) : unchanged(state)
        case .bytesReceived:
            return state.receiving ? SPFNEventStep(state: state, effects: [silenceTimer(state)]) : unchanged(state)
        case .frameReceived(_, let event):
            return frame(state, event)
        case .retryElapsed:
            return retryElapsed(state)
        case .stableElapsed:
            return stableElapsed(state)
        case .setForeground, .setSignedIn, .setNetworkAvailable:
            return unchanged(state)
        }
    }

    /// E-33.
    private func retryElapsed(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        guard case .retrying = state.phase
        else
        {
            return unchanged(state)
        }
        return begin(state, attempt: state.attempt)
    }

    /// E-47: the open lasted; a drop from here starts over at attempt 1.
    private func stableElapsed(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        guard state.phase == .open
        else
        {
            return unchanged(state)
        }
        var next = state
        next.stable = true
        next.attempt = 1
        return unchanged(next)
    }

    private func tokenMinted(_ state: SPFNEventMachineState, _ token: SPFNEventStreamToken) -> SPFNEventStep
    {
        guard state.phase == .token
        else
        {
            return unchanged(state)
        }
        var next = state
        next.phase = .stream(headersReceived: false)
        return SPFNEventStep(state: next, effects: [.openStream(generation: state.generation, names: state.requested, token: token)])
    }

    private func tokenFailed(_ state: SPFNEventMachineState, _ failure: SPFNTokenFailure) -> SPFNEventStep
    {
        guard state.phase == .token
        else
        {
            return unchanged(state)
        }
        switch failure
        {
        case .unauthorized:
            return close(state, .unauthorized)
        case .forbidden:
            return close(state, .forbidden)
        case .serverError(let status, let retryAfter):
            return retry(state, attempt: state.attempt + 1, reason: .serverError(status: status), retryAfterMillis: retryAfter)
        case .network:
            return retry(state, attempt: state.attempt + 1, reason: .network)
        case .unreadable:
            return retry(state, attempt: state.attempt + 1, reason: .unreadable)
        }
    }

    private func streamAnswered(_ state: SPFNEventMachineState, _ answer: SPFNStreamAnswer) -> SPFNEventStep
    {
        guard state.phase == .stream(headersReceived: false)
        else
        {
            return unchanged(state)
        }
        switch answer
        {
        case .eventStream:
            var next = state
            next.phase = .stream(headersReceived: true)
            return SPFNEventStep(state: next, effects: [silenceTimer(state)])
        case .notEventStream:
            return retry(state, attempt: state.attempt + 1, reason: .unreadable)
        case .tokenRejected:
            // E-15: the token may have expired between mint and use. One fresh token, then backoff.
            return state.retriedTokenRejection
                ? retry(state, attempt: state.attempt + 1, reason: .tokenRejected)
                : begin(state, attempt: state.attempt, retriedTokenRejection: true)
        case .invalidEvents(let invalid, let valid):
            return narrow(state, invalid: invalid, valid: valid)
        case .badRequest:
            return close(state, .unknownEvents([]))
        case .forbidden:
            return close(state, .forbidden)
        case .serverError(let status, let retryAfter):
            return retry(state, attempt: state.attempt + 1, reason: .serverError(status: status), retryAfterMillis: retryAfter)
        }
    }

    /// E-16 (Q-F): reconnect with what the server serves, and say which names are missing.
    /// The request must shrink every time, so a server that contradicts itself ends in
    /// `closed` rather than in a loop (E-52).
    private func narrow(_ state: SPFNEventMachineState, invalid: [String], valid: [String]) -> SPFNEventStep
    {
        let narrowed = state.requested.filter { valid.contains($0) }
        guard !narrowed.isEmpty, narrowed.count < state.requested.count
        else
        {
            return close(state, .unknownEvents(invalid.sorted()))
        }
        var next = state
        next.requested = narrowed
        next.unavailable = configuration.events.filter { !narrowed.contains($0) }
        var begun = begin(next, attempt: next.attempt, retriedTokenRejection: state.retriedTokenRejection)
        begun.effects += unavailableChange(state, next)
        return begun
    }

    private func frame(_ state: SPFNEventMachineState, _ event: SPFNSSEEvent) -> SPFNEventStep
    {
        guard state.receiving
        else
        {
            return unchanged(state)
        }
        guard state.phase == .open
        else
        {
            return event.name == Self.connected ? opened(state) : SPFNEventStep(state: state, effects: [silenceTimer(state)])
        }
        let effects: [SPFNEventEffect]
        if event.name == Self.connected || event.name == Self.ping
        {
            effects = []
        }
        else if configuration.contains(event.name)
        {
            effects = [.deliver(name: event.name, data: event.data)]
        }
        else
        {
            effects = [.countUnexpected(name: event.name)]
        }
        return SPFNEventStep(state: state, effects: effects + [silenceTimer(state)])
    }

    /// E-13: a new epoch, and every attached listener reads again.
    private func opened(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        var next = state
        next.phase = .open
        next.epoch += 1
        next.retriedTokenRejection = false
        next.stable = false
        return SPFNEventStep(
            state: next,
            effects: [
                silenceTimer(next),
                .startStableTimer(generation: next.generation, millis: Self.stableMillis),
                .reread(epoch: next.epoch),
            ]
        )
    }

    /// A stream that stopped: from the attempt it was on, or from 1 after a stable open.
    private func dropped(_ state: SPFNEventMachineState, _ reason: SPFNRetryReason) -> SPFNEventStep
    {
        switch state.phase
        {
        case .open:
            return retry(state, attempt: state.stable ? 1 : state.attempt + 1, reason: reason)
        case .stream:
            return retry(state, attempt: state.attempt + 1, reason: reason)
        default:
            return unchanged(state)
        }
    }

    // MARK: - Building blocks

    /// T(attempt), or N(attempt) with no network. Always a new generation.
    private func begin(_ state: SPFNEventMachineState, attempt: Int, retriedTokenRejection: Bool = false) -> SPFNEventStep
    {
        var next = state
        next.attempt = attempt
        next.generation = state.generation + 1
        next.retriedTokenRejection = retriedTokenRejection
        next.stable = false
        guard state.networkAvailable
        else
        {
            next.phase = .offline
            return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon)
        }
        next.phase = .token
        return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon + [.mintToken(generation: next.generation)])
    }

    /// R(attempt) after the backoff — or after the server's Retry-After when it asks for
    /// longer (Q-D) — or N(attempt) with no network (the R/N rule).
    private func retry(
        _ state: SPFNEventMachineState,
        attempt: Int,
        reason: SPFNRetryReason,
        retryAfterMillis: Int64? = nil
    ) -> SPFNEventStep
    {
        var next = state
        next.attempt = attempt
        next.generation = state.generation + 1
        next.retriedTokenRejection = false
        next.stable = false
        guard state.networkAvailable
        else
        {
            next.phase = .offline
            return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon)
        }
        let backoff = configuration.backoff.delayMillis(failure: max(attempt - 1, 1), jitter: jitter())
        let delay = max(backoff, min(retryAfterMillis ?? 0, SPFNRetryAfter.capMillis))
        next.phase = .retrying(delayMillis: delay, reason: reason)
        return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon + [.startRetryTimer(generation: next.generation, millis: delay)])
    }

    private func close(_ state: SPFNEventMachineState, _ reason: SPFNCloseReason) -> SPFNEventStep
    {
        var next = state
        next.phase = .closed(reason)
        next.generation = state.generation + 1
        return SPFNEventStep(state: next, effects: SPFNEventEffect.abandon)
    }

    /// The configured list again, and nothing unavailable: a new account or a new foreground.
    private func fresh(_ state: SPFNEventMachineState) -> SPFNEventMachineState
    {
        var next = state
        next.requested = configuration.events
        next.unavailable = []
        return next
    }

    private func unavailableChange(_ before: SPFNEventMachineState, _ after: SPFNEventMachineState) -> [SPFNEventEffect]
    {
        before.unavailable == after.unavailable ? [] : [.markUnavailable(after.unavailable)]
    }

    private func silenceTimer(_ state: SPFNEventMachineState) -> SPFNEventEffect
    {
        .startSilenceTimer(generation: state.generation, millis: configuration.silenceMillis)
    }

    private func unchanged(_ state: SPFNEventMachineState) -> SPFNEventStep
    {
        SPFNEventStep(state: state, effects: [])
    }

    /// Appends `publish` whenever the public state changed, so no transition can forget it.
    private func published(before: SPFNEventMachineState, _ step: SPFNEventStep) -> SPFNEventStep
    {
        let state = step.state.publicState
        guard state != before.publicState
        else
        {
            return step
        }
        return SPFNEventStep(state: step.state, effects: step.effects + [.publish(state)])
    }
}
