// SPFN Mobile — the server event stream: one connection, owned by the SDK.
//
// An app configures it once and hands it three facts as they change — foreground,
// signed-in client id, network. From those alone the object decides when a connection
// exists: only while the app is in the foreground and somebody is signed in. Screens only
// listen; attaching, detaching and a listener's condition never touch the connection
// (docs/architecture/event-stream-design.md §3).
//
// This file is the runner. The decisions are SpfnEventStreamMachine's, a pure function;
// this object performs its effects — the token call through `execute`, the stream through
// the stream transport, the timers — and feeds each result back stamped with the
// generation it was issued under. Every input is processed in order on the constructor's
// scope, one at a time, so the machine's state is never touched concurrently.
//
// The token call is signed by the key signed in when it is made, not by one fixed at
// construction: the object outlives every sign-in, rotation and account switch, and the
// key lifecycle is the one place that knows which key that is (SpfnKeyLifecycle.signedInClient()).
//
// The token is never a field. It travels from the token call's result to the stream
// request inside the two values between them, and it is printed as `redacted` (§6).
//
// Sources/SPFNClient/SPFNEventStream.swift is the same object in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnOperation
import java.net.URLEncoder
import kotlin.random.Random

/**
 * @param keyLifecycle whose signed-in key signs each token call, read again at every call
 *   (E-41, E-43). The stream opens on its `baseUrl`.
 * @param scope where every input is processed and every effect runs. The app's scope, not
 *   a screen's: the stream outlives every screen.
 * @param sleeper the timers' clock: silence, retry and stable. Injected so a suite can
 *   run the timers on virtual time.
 * @param clock wall time, read for a Retry-After given as an HTTP-date.
 * @param jitter a factor in [0.5, 1.0] for each backoff delay.
 */
class SpfnEventStream(
    private val keyLifecycle: SpfnKeyLifecycle,
    val configuration: SpfnEventStreamConfiguration,
    private val transport: SpfnStreamTransport = SpfnOkHttpStreamTransport(),
    private val scope: CoroutineScope,
    private val sleeper: SpfnSleeper = SpfnDelaySleeper(),
    private val clock: SpfnClock = SpfnSystemClock(),
    jitter: () -> Double = { Random.nextDouble(0.5, 1.0) }
)
{
    private val machine = SpfnEventStreamMachine(configuration, jitter);
    private val hub = SpfnEventListenerHub(configuration.deliveryBuffer);
    private val inbox = Channel<SpfnEventInput>(Channel.UNLIMITED);
    private val published = MutableStateFlow<SpfnEventStreamState>(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT));

    // Touched only by the inbox loop below.
    private var machineState = machine.initial();
    private var tokenJob: Job? = null;
    private var streamJob: Job? = null;
    private var silenceJob: Job? = null;
    private var retryJob: Job? = null;
    private var stableJob: Job? = null;

    /** For diagnostics and decoration. A screen reads again on a reread signal, not on this. */
    val state: StateFlow<SpfnEventStreamState> = published.asStateFlow();

    /** Dropped, filtered and unexpected frame counts, summed over every listener. */
    val diagnostics: StateFlow<SpfnEventDiagnostics> = hub.diagnostics;

    init
    {
        scope.launch {
            for (input in inbox)
            {
                process(input);
            }
        };
    }

    // ---- the three inputs --------------------------------------------------

    /** True while the app is in the foreground. False after construction. */
    fun setForeground(isForeground: Boolean)
    {
        inbox.trySend(SpfnEventInput.SetForeground(isForeground));
    }

    /** The signed-in client id, or null. Another id is another account (E-43). */
    fun setSignedIn(clientId: String?)
    {
        inbox.trySend(SpfnEventInput.SetSignedIn(clientId));
    }

    /** True after construction; without a path monitor, backoff recovers on its own. */
    fun setNetworkAvailable(isAvailable: Boolean)
    {
        inbox.trySend(SpfnEventInput.SetNetworkAvailable(isAvailable));
    }

    // ---- listening -------------------------------------------------------

    /** [listen] by a payload description: its `eventName` and its `decode`. */
    fun <E> listen(payload: SpfnEventPayload<E>, where: (E) -> Boolean = { true }): Flow<SpfnEventSignal<E>> =
        listen(payload.eventName, payload::decode, where);

    /**
     * Signals for one configured event name: `reread(attached)` first, then the frames that
     * decode and pass `where`, and a reread whenever what the screen holds may be stale.
     *
     * The name is checked here, when called, not when collected. The listener attaches when
     * collection starts and detaches when it is cancelled; neither touches the connection.
     * Collect it where the screen lives (`LaunchedEffect`), not in a `viewModelScope` that
     * outlives the screen (§8 H-8), and key that effect by whatever `where` captures (H-11).
     *
     * @throws IllegalArgumentException `name` is not in the configured events (L-3).
     */
    fun <E> listen(name: String, decode: (SpfnCanonicalValue) -> E, where: (E) -> Boolean = { true }): Flow<SpfnEventSignal<E>>
    {
        require(configuration.contains(name)) { "'$name' is not in the configured events" };
        return flow {
            val listener = hub.attach(name, decode, where);
            try
            {
                while (true)
                {
                    hub.take(listener).forEach { emit(it) };
                }
            }
            finally
            {
                hub.detach(listener);
            }
        };
    }

    // ---- the runner --------------------------------------------------------

    private fun process(input: SpfnEventInput)
    {
        val step = machine.step(machineState, input);
        machineState = step.state;
        step.effects.forEach { perform(it) };
    }

    private fun perform(effect: SpfnEventEffect)
    {
        when (effect)
        {
            is SpfnEventEffect.MintToken -> tokenJob = scope.launch { inbox.trySend(mint(effect.generation)) }
            SpfnEventEffect.CancelToken -> tokenJob?.cancel()
            is SpfnEventEffect.OpenStream -> streamJob = scope.launch { stream(effect) }
            SpfnEventEffect.CloseStream -> streamJob?.cancel()
            is SpfnEventEffect.StartSilenceTimer -> silenceJob = restart(silenceJob, effect.millis, SpfnEventInput.SilenceElapsed(effect.generation))
            is SpfnEventEffect.StartRetryTimer -> retryJob = restart(retryJob, effect.millis, SpfnEventInput.RetryElapsed(effect.generation))
            is SpfnEventEffect.StartStableTimer -> stableJob = restart(stableJob, effect.millis, SpfnEventInput.StableElapsed(effect.generation))
            SpfnEventEffect.CancelTimers -> listOf(silenceJob, retryJob, stableJob).forEach { it?.cancel() }
            is SpfnEventEffect.Deliver -> hub.deliver(effect.name, effect.data)
            is SpfnEventEffect.Reread -> hub.reread(effect.epoch)
            is SpfnEventEffect.MarkUnavailable -> hub.markUnavailable(effect.names)
            is SpfnEventEffect.CountUnexpected -> hub.countUnexpected()
            is SpfnEventEffect.Publish -> published.value = effect.state
        };
    }

    private fun restart(previous: Job?, millis: Long, expiry: SpfnEventInput): Job
    {
        previous?.cancel();
        return scope.launch {
            sleeper.sleep(millis);
            inbox.trySend(expiry);
        };
    }

    /**
     * The token call, through `execute`: signed by the key signed in at this moment, and
     * sessionless unless the configuration says the server wants one (§2-2), in which case
     * `execute` opens it and re-handshakes once. With nobody signed in nothing is sent, and the answer is the one an
     * unsigned call would have met; the sign-out itself follows as `setSignedIn(null)`. Any
     * failure is an input; a cancelled call's input carries a generation the machine has
     * moved past.
     */
    private suspend fun mint(generation: Long): SpfnEventInput = try
    {
        when (val client = keyLifecycle.signedInClient())
        {
            null -> SpfnEventInput.TokenFailed(generation, SpfnTokenFailure.Unauthorized)
            else -> SpfnEventInput.TokenMinted(generation, client.execute(tokenCall(), Unit))
        }
    }
    catch (failure: Exception)
    {
        SpfnEventInput.TokenFailed(generation, tokenFailure(failure, clock.nowMillis()))
    };

    private fun tokenCall(): SpfnCall<Unit, SpfnEventStreamToken> =
        SpfnCall(
            operation = SpfnOperation(
                id = TOKEN_OPERATION_ID,
                method = "POST",
                path = configuration.tokenPath,
                authProfile = TOKEN_AUTH_PROFILE,
                requiresSession = configuration.tokenRequiresSession,
                declaresResponse = true
            ),
            encode = { null },
            decode = { decodeToken(it) }
        );

    private suspend fun stream(effect: SpfnEventEffect.OpenStream)
    {
        val response = try
        {
            transport.open(streamRequest(effect.names, effect.token))
        }
        catch (_: Exception)
        {
            inbox.trySend(SpfnEventInput.StreamFailed(effect.generation));
            return;
        };
        try
        {
            read(effect.generation, response);
        }
        finally
        {
            response.cancel();
        }
    }

    private suspend fun read(generation: Long, response: SpfnStreamResponse)
    {
        val answer = streamAnswer(response.statusCode, response.headers, bodyIfRefused(response), clock.nowMillis());
        inbox.trySend(SpfnEventInput.StreamAnswered(generation, answer));
        if (answer != SpfnStreamAnswer.EventStream)
        {
            return;
        }
        val parser = SpfnSseLineParser();
        val end = try
        {
            response.chunks.collect { chunk ->
                inbox.trySend(SpfnEventInput.BytesReceived(generation));
                parser.feed(chunk).forEach { inbox.trySend(SpfnEventInput.FrameReceived(generation, it)) };
            };
            SpfnEventInput.StreamEnded(generation)
        }
        catch (_: Exception)
        {
            SpfnEventInput.StreamFailed(generation)
        };
        inbox.trySend(end);
    }

    /** A refusal's body, read to its end (a 400 names the valid events); nothing for a 2xx. */
    private suspend fun bodyIfRefused(response: SpfnStreamResponse): ByteArray
    {
        if (response.statusCode in 200..299)
        {
            return ByteArray(0);
        }
        val body = java.io.ByteArrayOutputStream();
        try
        {
            response.chunks.collect { if (body.size() < REFUSAL_BODY_LIMIT) body.write(it) };
        }
        catch (_: java.io.IOException)
        {
            // A refusal whose body was cut is still a refusal; it is read on its status.
        }
        return body.toByteArray();
    }

    private fun streamRequest(names: List<String>, token: SpfnEventStreamToken): SpfnTransportRequest =
        SpfnTransportRequest(
            method = "GET",
            url = keyLifecycle.baseUrl + configuration.streamPath +
                "?token=" + encode(token.value) + "&events=" + names.joinToString(",") { encode(it) },
            // Not signed: the server reads only the token on this path, and a proof here
            // would be spent for nothing (§2-2). The identity headers ride as on every request.
            headers = SpfnClientIdentity.headers + listOf("accept" to "text/event-stream", "cache-control" to "no-cache"),
            timeoutMillis = STREAM_HEADERS_TIMEOUT_MILLIS
        );

    internal companion object
    {
        const val TOKEN_OPERATION_ID = "events.token";
        const val TOKEN_AUTH_PROFILE = "clientProofV1";
        const val STREAM_HEADERS_TIMEOUT_MILLIS: Long = 15_000;
        const val REFUSAL_BODY_LIMIT = 65_536;

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8");

        /** `{"token": "<64 hex>"}`. Anything else is the declared response not arriving (E-9). */
        fun decodeToken(value: SpfnCanonicalValue): SpfnEventStreamToken
        {
            val token = ((value as? SpfnCanonicalValue.Obj)?.members?.get("token") as? SpfnCanonicalValue.Text)?.value;
            require(!token.isNullOrEmpty()) { "the token response carries no token" };
            return SpfnEventStreamToken(token);
        }

        /** E-4 … E-9: what `execute` classified, read as the machine's vocabulary. */
        fun tokenFailure(failure: Exception, nowMillis: Long): SpfnTokenFailure = when
        {
            failure is SpfnClientError.Auth -> SpfnTokenFailure.Unauthorized
            failure is SpfnClientError.Server && failure.failure.httpStatus == 403 -> SpfnTokenFailure.Forbidden
            failure is SpfnClientError.Server -> SpfnTokenFailure.ServerError(
                failure.failure.httpStatus,
                if (failure.failure.httpStatus == 429) SpfnRetryAfter.millis(failure.failure.retryAfter, nowMillis) else null
            )
            failure is SpfnClientError.Transport -> SpfnTokenFailure.Network
            else -> SpfnTokenFailure.Unreadable
        };

        /** E-12 … E-19: a stream response's status, headers and refusal body, read. */
        fun streamAnswer(status: Int, headers: List<Pair<String, String>>, body: ByteArray, nowMillis: Long): SpfnStreamAnswer = when (status)
        {
            in 200..299 -> if (isEventStream(headers)) SpfnStreamAnswer.EventStream else SpfnStreamAnswer.NotEventStream
            401 -> SpfnStreamAnswer.TokenRejected
            400 -> invalidEvents(body) ?: SpfnStreamAnswer.BadRequest
            403 -> SpfnStreamAnswer.Forbidden
            429 -> SpfnStreamAnswer.ServerError(429, SpfnRetryAfter.millis(SpfnRetryAfter.header(headers), nowMillis))
            else -> SpfnStreamAnswer.ServerError(status, null)
        };

        private fun isEventStream(headers: List<Pair<String, String>>): Boolean =
            headers.any { it.first.equals("content-type", ignoreCase = true) && it.second.trim().lowercase().startsWith("text/event-stream") };

        /** The server's `{"error", "invalidEvents": [...], "validEvents": [...]}`, or null. */
        private fun invalidEvents(body: ByteArray): SpfnStreamAnswer.InvalidEvents?
        {
            val members = (SpfnEventListenerHub.parse(body) as? SpfnCanonicalValue.Obj)?.members ?: return null;
            val invalid = texts(members["invalidEvents"]) ?: return null;
            val valid = texts(members["validEvents"]) ?: return null;
            return SpfnStreamAnswer.InvalidEvents(invalid, valid);
        }

        private fun texts(value: SpfnCanonicalValue?): List<String>?
        {
            val elements = (value as? SpfnCanonicalValue.Arr)?.elements ?: return null;
            val texts = elements.mapNotNull { (it as? SpfnCanonicalValue.Text)?.value };
            return if (texts.size == elements.size) texts else null;
        }
    }
}
