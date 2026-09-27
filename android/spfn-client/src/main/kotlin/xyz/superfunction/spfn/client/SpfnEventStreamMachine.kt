// SPFN Mobile — the event stream's connection, as a pure state machine.
//
// `(state, input) -> (state, effects)` and nothing else: no clock, no socket, no
// coroutine. The runner in SpfnEventStream performs the effects and feeds every
// asynchronous result back as an input carrying the generation it was issued under; a
// result from an older generation is dropped (E-39). That is what makes the whole of
// docs/architecture/event-stream-design.md §4-1 a unit test on the JVM.
//
// The listener hub is NOT an input here. Nothing a screen does — attaching, detaching, a
// condition true, false or throwing — reaches this machine, which is the proof that
// navigation never reconnects and that a condition never reaches the server (§4-2).
//
// Sources/SPFNClient/SPFNEventStreamMachine.swift is the same table in Swift.

package xyz.superfunction.spfn.client

/** A minted one-use token. Never printed: its only reader is the stream request. */
class SpfnEventStreamToken internal constructor(internal val value: String)
{
    override fun equals(other: Any?): Boolean = other is SpfnEventStreamToken && other.value == value;

    override fun hashCode(): Int = value.hashCode();

    override fun toString(): String = "SpfnEventStreamToken(redacted)";
}

/** Where the connection is. `publicState` folds this and the counters into the API's state. */
internal sealed interface SpfnEventPhase
{
    data class Idle(val reason: SpfnIdleReason) : SpfnEventPhase

    data object Token : SpfnEventPhase

    data class Stream(val headersReceived: Boolean) : SpfnEventPhase

    data object Open : SpfnEventPhase

    data class Retrying(val delayMillis: Long, val reason: SpfnRetryReason) : SpfnEventPhase

    data object Offline : SpfnEventPhase

    data class Closed(val reason: SpfnCloseReason) : SpfnEventPhase
}

/**
 * The machine's whole memory.
 *
 * @property generation the issue number every asynchronous effect carries back.
 * @property retriedTokenRejection the one immediate token retry after a stream 401 is spent.
 * @property stable the open connection lasted the stable interval, so a drop starts over at 1.
 * @property requested the names the next stream request asks for: the configured list,
 *   narrowed by a 400 `invalidEvents` until the connection condition drops.
 */
internal data class SpfnEventMachineState(
    val phase: SpfnEventPhase,
    val requested: List<String>,
    val unavailable: List<String> = emptyList(),
    val attempt: Int = 1,
    val epoch: Int = 0,
    val generation: Long = 0,
    val foreground: Boolean = false,
    val clientId: String? = null,
    val networkAvailable: Boolean = true,
    val retriedTokenRejection: Boolean = false,
    val stable: Boolean = false
)
{
    val publicState: SpfnEventStreamState
        get() = when (phase)
        {
            is SpfnEventPhase.Idle -> SpfnEventStreamState.Idle(phase.reason)
            SpfnEventPhase.Token, is SpfnEventPhase.Stream -> SpfnEventStreamState.Connecting(attempt)
            SpfnEventPhase.Open -> SpfnEventStreamState.Open(epoch, unavailable)
            is SpfnEventPhase.Retrying -> SpfnEventStreamState.Retrying(attempt, phase.delayMillis, phase.reason)
            SpfnEventPhase.Offline -> SpfnEventStreamState.Offline(attempt)
            is SpfnEventPhase.Closed -> SpfnEventStreamState.Closed(phase.reason)
        };

    /** W: foreground and signed in. The only condition a connection exists under. */
    val wantsConnection: Boolean get() = foreground && clientId != null;
}

/** How a token call ended, already read off `SpfnClientError` (see SpfnEventStream). */
internal sealed interface SpfnTokenFailure
{
    data object Unauthorized : SpfnTokenFailure

    data object Forbidden : SpfnTokenFailure

    data class ServerError(val status: Int, val retryAfterMillis: Long?) : SpfnTokenFailure

    data object Network : SpfnTokenFailure

    data object Unreadable : SpfnTokenFailure
}

/** How a stream request was answered, read off its status, headers and body. */
internal sealed interface SpfnStreamAnswer
{
    data object EventStream : SpfnStreamAnswer

    data object NotEventStream : SpfnStreamAnswer

    data object TokenRejected : SpfnStreamAnswer

    data class InvalidEvents(val invalid: List<String>, val valid: List<String>) : SpfnStreamAnswer

    data object BadRequest : SpfnStreamAnswer

    data object Forbidden : SpfnStreamAnswer

    data class ServerError(val status: Int, val retryAfterMillis: Long?) : SpfnStreamAnswer
}

internal sealed interface SpfnEventInput
{
    data class SetForeground(val isForeground: Boolean) : SpfnEventInput

    data class SetSignedIn(val clientId: String?) : SpfnEventInput

    data class SetNetworkAvailable(val isAvailable: Boolean) : SpfnEventInput

    /** An asynchronous result, stamped with the generation its effect was issued under. */
    sealed interface Issued : SpfnEventInput
    {
        val generation: Long
    }

    data class TokenMinted(override val generation: Long, val token: SpfnEventStreamToken) : Issued

    data class TokenFailed(override val generation: Long, val failure: SpfnTokenFailure) : Issued

    data class StreamAnswered(override val generation: Long, val answer: SpfnStreamAnswer) : Issued

    data class StreamFailed(override val generation: Long) : Issued

    data class StreamEnded(override val generation: Long) : Issued

    data class BytesReceived(override val generation: Long) : Issued

    data class FrameReceived(override val generation: Long, val event: SpfnSseEvent) : Issued

    data class SilenceElapsed(override val generation: Long) : Issued

    data class RetryElapsed(override val generation: Long) : Issued

    data class StableElapsed(override val generation: Long) : Issued
}

internal sealed interface SpfnEventEffect
{
    data class MintToken(val generation: Long) : SpfnEventEffect

    data object CancelToken : SpfnEventEffect

    data class OpenStream(val generation: Long, val names: List<String>, val token: SpfnEventStreamToken) : SpfnEventEffect

    data object CloseStream : SpfnEventEffect

    data class StartSilenceTimer(val generation: Long, val millis: Long) : SpfnEventEffect

    data class StartRetryTimer(val generation: Long, val millis: Long) : SpfnEventEffect

    data class StartStableTimer(val generation: Long, val millis: Long) : SpfnEventEffect

    data object CancelTimers : SpfnEventEffect

    data class Deliver(val name: String, val data: String) : SpfnEventEffect

    data class Reread(val epoch: Int) : SpfnEventEffect

    data class MarkUnavailable(val names: List<String>) : SpfnEventEffect

    data class CountUnexpected(val name: String) : SpfnEventEffect

    data class Publish(val state: SpfnEventStreamState) : SpfnEventEffect
}

internal data class SpfnEventStep(val state: SpfnEventMachineState, val effects: List<SpfnEventEffect>);

/**
 * The E-table. `jitter` returns a factor in [0.5, 1.0] and is the machine's only source of
 * randomness, injected so a suite can fix it.
 */
internal class SpfnEventStreamMachine(
    private val configuration: SpfnEventStreamConfiguration,
    private val jitter: () -> Double
)
{
    /** Created: `idle(signedOut)`, background, signed out, network assumed present. */
    fun initial(): SpfnEventMachineState =
        SpfnEventMachineState(phase = SpfnEventPhase.Idle(SpfnIdleReason.SIGNED_OUT), requested = configuration.events);

    fun step(state: SpfnEventMachineState, input: SpfnEventInput): SpfnEventStep
    {
        val step = when (input)
        {
            is SpfnEventInput.SetForeground -> setForeground(state, input.isForeground)
            is SpfnEventInput.SetSignedIn -> setSignedIn(state, input.clientId)
            is SpfnEventInput.SetNetworkAvailable -> setNetworkAvailable(state, input.isAvailable)
            is SpfnEventInput.Issued -> if (input.generation == state.generation) issued(state, input) else SpfnEventStep(state, emptyList())
        };
        return published(state, step);
    }

    // ---- the three condition inputs ----------------------------------------

    private fun setForeground(state: SpfnEventMachineState, isForeground: Boolean): SpfnEventStep =
        if (state.foreground == isForeground) unchanged(state) else reconcile(state.copy(foreground = isForeground));

    private fun setSignedIn(state: SpfnEventMachineState, clientId: String?): SpfnEventStep
    {
        if (state.clientId == clientId)
        {
            return unchanged(state);
        }
        val switched = state.clientId != null && clientId != null;
        val next = state.copy(clientId = clientId);
        // Another account (E-43): whatever was open belongs to the old subject, and the
        // narrowing learned under it is forgotten with it.
        if (!switched || !next.wantsConnection)
        {
            return reconcile(next);
        }
        val begun = begin(fresh(next), attempt = 1);
        return SpfnEventStep(begun.state, begun.effects + unavailableChange(state, begun.state));
    }

    private fun setNetworkAvailable(state: SpfnEventMachineState, isAvailable: Boolean): SpfnEventStep
    {
        if (state.networkAvailable == isAvailable)
        {
            return unchanged(state);
        }
        val next = state.copy(networkAvailable = isAvailable);
        return when
        {
            // E-34: a retry timer against no network would only fail as `network`.
            next.phase is SpfnEventPhase.Retrying && !isAvailable ->
                SpfnEventStep(next.copy(phase = SpfnEventPhase.Offline, generation = next.generation + 1), listOf(SpfnEventEffect.CancelTimers))
            // E-35: at once; the delay that was being waited out was already dropped.
            next.phase == SpfnEventPhase.Offline && isAvailable -> begin(next, next.attempt)
            // E-36, E-51, E-45, E-49: recorded only.
            else -> unchanged(next)
        };
    }

    /** After F or A changed: idle when W is false, a first attempt when it just became true. */
    private fun reconcile(state: SpfnEventMachineState): SpfnEventStep = when
    {
        !state.wantsConnection -> idle(state)
        state.phase is SpfnEventPhase.Idle -> begin(fresh(state), attempt = 1)
        else -> unchanged(state)
    };

    private fun idle(state: SpfnEventMachineState): SpfnEventStep
    {
        val reason = if (state.clientId == null) SpfnIdleReason.SIGNED_OUT else SpfnIdleReason.BACKGROUND;
        if (state.phase is SpfnEventPhase.Idle)
        {
            return unchanged(state.copy(phase = SpfnEventPhase.Idle(reason)));
        }
        val next = fresh(state).copy(phase = SpfnEventPhase.Idle(reason), generation = state.generation + 1);
        return SpfnEventStep(next, ABANDON + unavailableChange(state, next));
    }

    // ---- asynchronous results ----------------------------------------------

    private fun issued(state: SpfnEventMachineState, input: SpfnEventInput.Issued): SpfnEventStep = when (input)
    {
        is SpfnEventInput.TokenMinted -> tokenMinted(state, input.token)
        is SpfnEventInput.TokenFailed -> tokenFailed(state, input.failure)
        is SpfnEventInput.StreamAnswered -> streamAnswered(state, input.answer)
        is SpfnEventInput.StreamFailed -> dropped(state, SpfnRetryReason.Network)
        is SpfnEventInput.StreamEnded -> dropped(state, SpfnRetryReason.ServerClosed)
        is SpfnEventInput.SilenceElapsed -> if (state.receiving) dropped(state, SpfnRetryReason.Silence) else unchanged(state)
        is SpfnEventInput.BytesReceived -> if (state.receiving) SpfnEventStep(state, listOf(silenceTimer(state))) else unchanged(state)
        is SpfnEventInput.FrameReceived -> frame(state, input.event)
        is SpfnEventInput.RetryElapsed -> if (state.phase is SpfnEventPhase.Retrying) begin(state, state.attempt) else unchanged(state)
        is SpfnEventInput.StableElapsed -> if (state.phase == SpfnEventPhase.Open) unchanged(state.copy(stable = true, attempt = 1)) else unchanged(state)
    };

    private fun tokenMinted(state: SpfnEventMachineState, token: SpfnEventStreamToken): SpfnEventStep
    {
        if (state.phase != SpfnEventPhase.Token)
        {
            return unchanged(state);
        }
        return SpfnEventStep(
            state.copy(phase = SpfnEventPhase.Stream(headersReceived = false)),
            listOf(SpfnEventEffect.OpenStream(state.generation, state.requested, token))
        );
    }

    private fun tokenFailed(state: SpfnEventMachineState, failure: SpfnTokenFailure): SpfnEventStep
    {
        if (state.phase != SpfnEventPhase.Token)
        {
            return unchanged(state);
        }
        return when (failure)
        {
            SpfnTokenFailure.Unauthorized -> close(state, SpfnCloseReason.Unauthorized)
            SpfnTokenFailure.Forbidden -> close(state, SpfnCloseReason.Forbidden)
            is SpfnTokenFailure.ServerError -> retry(state, state.attempt + 1, SpfnRetryReason.ServerError(failure.status), failure.retryAfterMillis)
            SpfnTokenFailure.Network -> retry(state, state.attempt + 1, SpfnRetryReason.Network)
            SpfnTokenFailure.Unreadable -> retry(state, state.attempt + 1, SpfnRetryReason.Unreadable)
        };
    }

    private fun streamAnswered(state: SpfnEventMachineState, answer: SpfnStreamAnswer): SpfnEventStep
    {
        if (state.phase != SpfnEventPhase.Stream(headersReceived = false))
        {
            return unchanged(state);
        }
        return when (answer)
        {
            SpfnStreamAnswer.EventStream ->
                SpfnEventStep(state.copy(phase = SpfnEventPhase.Stream(headersReceived = true)), listOf(silenceTimer(state)))
            SpfnStreamAnswer.NotEventStream -> retry(state, state.attempt + 1, SpfnRetryReason.Unreadable)
            SpfnStreamAnswer.TokenRejected -> tokenRejected(state)
            is SpfnStreamAnswer.InvalidEvents -> narrow(state, answer)
            SpfnStreamAnswer.BadRequest -> close(state, SpfnCloseReason.UnknownEvents(emptyList()))
            SpfnStreamAnswer.Forbidden -> close(state, SpfnCloseReason.Forbidden)
            is SpfnStreamAnswer.ServerError -> retry(state, state.attempt + 1, SpfnRetryReason.ServerError(answer.status), answer.retryAfterMillis)
        };
    }

    /** E-15: the token may have expired between mint and use. One fresh token, then backoff. */
    private fun tokenRejected(state: SpfnEventMachineState): SpfnEventStep =
        if (state.retriedTokenRejection) retry(state, state.attempt + 1, SpfnRetryReason.TokenRejected)
        else begin(state, state.attempt, retriedTokenRejection = true);

    /**
     * E-16 (Q-F): reconnect with what the server serves, and say which names are missing.
     * The request must shrink every time, so a server that contradicts itself ends in
     * `closed` rather than in a loop (E-52).
     */
    private fun narrow(state: SpfnEventMachineState, answer: SpfnStreamAnswer.InvalidEvents): SpfnEventStep
    {
        val narrowed = state.requested.filter { it in answer.valid };
        if (narrowed.isEmpty() || narrowed.size == state.requested.size)
        {
            return close(state, SpfnCloseReason.UnknownEvents(answer.invalid.sorted()));
        }
        val next = state.copy(requested = narrowed, unavailable = configuration.events - narrowed.toSet());
        val begun = begin(next, next.attempt, retriedTokenRejection = state.retriedTokenRejection);
        return SpfnEventStep(begun.state, begun.effects + unavailableChange(state, next));
    }

    private fun frame(state: SpfnEventMachineState, event: SpfnSseEvent): SpfnEventStep
    {
        if (!state.receiving)
        {
            return unchanged(state);
        }
        if (state.phase != SpfnEventPhase.Open)
        {
            return if (event.name == CONNECTED) opened(state) else SpfnEventStep(state, listOf(silenceTimer(state)));
        }
        val effect = when
        {
            event.name == CONNECTED || event.name == PING -> null
            configuration.contains(event.name) -> SpfnEventEffect.Deliver(event.name, event.data)
            else -> SpfnEventEffect.CountUnexpected(event.name)
        };
        return SpfnEventStep(state, listOfNotNull(effect, silenceTimer(state)));
    }

    /** E-13: a new epoch, and every attached listener reads again. */
    private fun opened(state: SpfnEventMachineState): SpfnEventStep
    {
        val next = state.copy(phase = SpfnEventPhase.Open, epoch = state.epoch + 1, retriedTokenRejection = false, stable = false);
        return SpfnEventStep(
            next,
            listOf(
                silenceTimer(next),
                SpfnEventEffect.StartStableTimer(next.generation, STABLE_MILLIS),
                SpfnEventEffect.Reread(next.epoch)
            )
        );
    }

    /** A stream that stopped: from the attempt it was on, or from 1 after a stable open. */
    private fun dropped(state: SpfnEventMachineState, reason: SpfnRetryReason): SpfnEventStep = when
    {
        state.phase == SpfnEventPhase.Open -> retry(state, if (state.stable) 1 else state.attempt + 1, reason)
        state.phase is SpfnEventPhase.Stream -> retry(state, state.attempt + 1, reason)
        else -> unchanged(state)
    };

    // ---- building blocks ---------------------------------------------------

    /** T(attempt), or N(attempt) with no network. Always a new generation. */
    private fun begin(state: SpfnEventMachineState, attempt: Int, retriedTokenRejection: Boolean = false): SpfnEventStep
    {
        val generation = state.generation + 1;
        val base = state.copy(attempt = attempt, generation = generation, retriedTokenRejection = retriedTokenRejection, stable = false);
        return if (state.networkAvailable)
        {
            SpfnEventStep(base.copy(phase = SpfnEventPhase.Token), ABANDON + SpfnEventEffect.MintToken(generation))
        }
        else
        {
            SpfnEventStep(base.copy(phase = SpfnEventPhase.Offline), ABANDON)
        };
    }

    /**
     * R(attempt) after the backoff — or after the server's Retry-After when it asks for
     * longer (Q-D) — or N(attempt) with no network (the R/N rule).
     */
    private fun retry(state: SpfnEventMachineState, attempt: Int, reason: SpfnRetryReason, retryAfterMillis: Long? = null): SpfnEventStep
    {
        val generation = state.generation + 1;
        val base = state.copy(attempt = attempt, generation = generation, retriedTokenRejection = false, stable = false);
        if (!state.networkAvailable)
        {
            return SpfnEventStep(base.copy(phase = SpfnEventPhase.Offline), ABANDON);
        }
        val backoff = configuration.backoff.delayMillis(maxOf(attempt - 1, 1), jitter());
        val delay = maxOf(backoff, minOf(retryAfterMillis ?: 0, SpfnRetryAfter.CAP_MILLIS));
        return SpfnEventStep(
            base.copy(phase = SpfnEventPhase.Retrying(delay, reason)),
            ABANDON + SpfnEventEffect.StartRetryTimer(generation, delay)
        );
    }

    private fun close(state: SpfnEventMachineState, reason: SpfnCloseReason): SpfnEventStep =
        SpfnEventStep(state.copy(phase = SpfnEventPhase.Closed(reason), generation = state.generation + 1), ABANDON);

    /** The configured list again, and nothing unavailable: a new account or a new foreground. */
    private fun fresh(state: SpfnEventMachineState): SpfnEventMachineState =
        state.copy(requested = configuration.events, unavailable = emptyList());

    private fun unavailableChange(before: SpfnEventMachineState, after: SpfnEventMachineState): List<SpfnEventEffect> =
        if (before.unavailable == after.unavailable) emptyList() else listOf(SpfnEventEffect.MarkUnavailable(after.unavailable));

    private fun silenceTimer(state: SpfnEventMachineState): SpfnEventEffect =
        SpfnEventEffect.StartSilenceTimer(state.generation, configuration.silenceMillis);

    private fun unchanged(state: SpfnEventMachineState): SpfnEventStep = SpfnEventStep(state, emptyList());

    /** Appends `publish` whenever the public state changed, so no transition can forget it. */
    private fun published(before: SpfnEventMachineState, step: SpfnEventStep): SpfnEventStep
    {
        val state = step.state.publicState;
        return if (state == before.publicState) step else SpfnEventStep(step.state, step.effects + SpfnEventEffect.Publish(state));
    }

    /** Headers arrived: the silence watchdog runs and frames are read. */
    private val SpfnEventMachineState.receiving: Boolean
        get() = phase == SpfnEventPhase.Open || phase == SpfnEventPhase.Stream(headersReceived = true);

    internal companion object
    {
        const val CONNECTED = "connected";
        const val PING = "ping";

        /** An open this long resets `attempt` to 1 (E-47). */
        const val STABLE_MILLIS: Long = 30_000;

        /** Every in-flight effect of the previous generation, stopped. */
        val ABANDON: List<SpfnEventEffect> =
            listOf(SpfnEventEffect.CancelToken, SpfnEventEffect.CloseStream, SpfnEventEffect.CancelTimers);
    }
}
