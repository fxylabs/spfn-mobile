// SPFN Mobile — the example app's events screen (docs/architecture/event-stream-design.md §9-4).
//
// Counterpart of examples/ios-swiftui/Sources/EventsDemo.swift.
//
// One SpfnEventStream over an in-app demo server, so the screen runs with no network at all
// (the app's manifest has no INTERNET permission). The demo server answers the handshake
// and the token call behind the real `execute`, then streams `connected` and a
// `sessionActivity` frame every 1.5 s, alternating two workspaces; every fifth frame's
// payload is unreadable, so the dropped counter moves too. What the screen shows is the
// design's readout: `stream=open(n)`, the frames, dropped, filtered and reread counts, the
// last reread's cause, and a toggle that puts a condition on the listener.
//
// No SpfnEventStreamHost here: the host observes a key lifecycle, and this app enrols no
// key. The screen does the host's two remaining jobs by hand — it provides the stream and
// says it is in the foreground while it is composed — and a button stands in for sign-in,
// which is the "client module only" wiring of §3-6. Leaving the screen is leaving the
// foreground; the stream idles and the next visit reads again on `opened`.

package xyz.superfunction.spfn.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnEventPayload
import xyz.superfunction.spfn.client.SpfnEventSignal
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamConfiguration
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnKeyProvider
import xyz.superfunction.spfn.client.SpfnProofClock
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnStreamResponse
import xyz.superfunction.spfn.client.SpfnStreamTransport
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse
import xyz.superfunction.spfn.client.SpfnWireHeaders
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.events.LocalSpfnEventStream
import xyz.superfunction.spfn.events.SpfnEventEffect
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SecondaryButton
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** Which session moved, in which workspace: the demo's one payload. */
data class SessionActivity(val sessionId: String, val wsId: String)
{
    companion object : SpfnEventPayload<SessionActivity>
    {
        override val eventName = "sessionActivity";

        override fun decode(value: SpfnCanonicalValue): SessionActivity
        {
            val members = (value as? SpfnCanonicalValue.Obj)?.members ?: throw IllegalArgumentException("not an object");
            return SessionActivity(text(members["sessionId"]), text(members["wsId"]));
        }

        private fun text(value: SpfnCanonicalValue?): String =
            (value as? SpfnCanonicalValue.Text)?.value ?: throw IllegalArgumentException("not text");
    }
}

@Composable
fun EventsDemo()
{
    val scope = rememberCoroutineScope();
    val server = remember { DemoEventServer() };
    val stream = remember { demoStream(server, scope) };
    DisposableEffect(stream)
    {
        stream.setForeground(true);
        onDispose { stream.setForeground(false) };
    };
    CompositionLocalProvider(LocalSpfnEventStream provides stream)
    {
        EventsReadout(stream, server);
    };
}

@Composable
private fun EventsReadout(stream: SpfnEventStream, server: DemoEventServer)
{
    val state by stream.state.collectAsState();
    val diagnostics by stream.diagnostics.collectAsState();
    var frames by remember { mutableIntStateOf(0) };
    var rereads by remember { mutableIntStateOf(0) };
    var lastCause by remember { mutableStateOf("none") };
    var onlyWorkspaceA by remember { mutableStateOf(false) };
    var signedIn by remember { mutableStateOf(false) };

    SpfnEventEffect(SessionActivity, key = onlyWorkspaceA, where = { !onlyWorkspaceA || it.wsId == "w-a" })
    {
        signal ->
        when (signal)
        {
            is SpfnEventSignal.Frame -> frames += 1;
            is SpfnEventSignal.Reread ->
            {
                rereads += 1;
                lastCause = signal.cause.toString();
            }
            SpfnEventSignal.Unavailable -> lastCause = "unavailable";
        }
    };

    Screen(title = "events")
    {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4),
            verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4)
        )
        {
            SpfnText(text = "stream=${describe(state)}", role = TextRole.Mono);
            SpfnText(text = "frames=$frames", role = TextRole.Mono);
            SpfnText(text = "dropped=${diagnostics.droppedFrames}", role = TextRole.Mono);
            SpfnText(text = "filtered=${diagnostics.filteredFrames}", role = TextRole.Mono);
            SpfnText(text = "rereads=$rereads last=$lastCause", role = TextRole.Mono);
            SpfnText(text = "condition=${if (onlyWorkspaceA) "wsId == w-a" else "none"}", role = TextRole.Mono);
            PrimaryButton(title = if (signedIn) "sign out" else "sign in", id = "events.signIn")
            {
                signedIn = !signedIn;
                stream.setSignedIn(if (signedIn) DEMO_CLIENT_ID else null);
            };
            PrimaryButton(title = "toggle condition", id = "events.condition") { onlyWorkspaceA = !onlyWorkspaceA };
            SecondaryButton(title = "drop connection", id = "events.drop") { server.dropConnection() };
        }
    };
}

/** The design's readout spelling: `open(3)`, `retrying(2, 1000 ms, network)`. */
private fun describe(state: SpfnEventStreamState): String = when (state)
{
    is SpfnEventStreamState.Idle -> "idle(${state.reason.name.lowercase()})"
    is SpfnEventStreamState.Connecting -> "connecting(${state.attempt})"
    is SpfnEventStreamState.Open -> "open(${state.epoch})"
    is SpfnEventStreamState.Retrying -> "retrying(${state.attempt}, ${state.delayMillis} ms, ${state.reason})"
    is SpfnEventStreamState.Offline -> "offline(${state.attempt})"
    is SpfnEventStreamState.Closed -> "closed(${state.reason})"
};

private fun demoStream(server: DemoEventServer, scope: CoroutineScope): SpfnEventStream
{
    val session = SpfnSession(
        transport = server,
        keyProvider = DemoKeyProvider,
        baseUrl = DEMO_BASE_URL,
        clock = DemoProofClock
    );
    return SpfnEventStream(
        client = SpfnClient(server, session),
        session = session,
        configuration = SpfnEventStreamConfiguration(events = listOf(SessionActivity.eventName)),
        transport = server,
        scope = scope
    );
}

/**
 * The demo server: the handshake and the token behind `execute`, and a stream of frames.
 * Nothing leaves the process; the URL is never dialled.
 */
private class DemoEventServer : SpfnTransport, SpfnStreamTransport
{
    private val drops = Channel<Unit>(Channel.CONFLATED);
    private var minted = 0;

    fun dropConnection()
    {
        drops.trySend(Unit);
    }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse =
        if (request.url.endsWith("/events/token"))
        {
            answer("{\"token\":\"demo-token-${++minted}\"}")
        }
        else
        {
            answer("{\"expiresAtMillis\":${System.currentTimeMillis() + 300_000},\"sessionId\":\"demo-session\"}")
        };

    override suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse
    {
        drops.tryReceive();
        // Ends cleanly when "drop connection" is pressed, the way a server redeploy looks.
        val frames = channelFlow {
            send(frame("connected", "{\"subscribedEvents\":[\"sessionActivity\"],\"timestamp\":${System.currentTimeMillis()}}"));
            val ticker = launch()
            {
                var sequence = 0;
                while (true)
                {
                    delay(1_500);
                    sequence += 1;
                    send(frame("sessionActivity", activity(sequence)));
                }
            };
            drops.receive();
            ticker.cancel();
        };
        return SpfnStreamResponse(200, listOf("content-type" to "text/event-stream"), frames) {};
    }

    private fun activity(sequence: Int): String =
        if (sequence % 5 == 0)
        {
            "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":$sequence}}"
        }
        else
        {
            "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"s-$sequence\",\"wsId\":\"${if (sequence % 2 == 0) "w-a" else "w-b"}\"}}"
        };

    private fun frame(name: String, data: String): ByteArray = "event: $name\ndata: $data\n\n".toByteArray(Charsets.UTF_8);

    private fun answer(body: String): SpfnTransportResponse =
        SpfnTransportResponse(
            statusCode = 200,
            headers = listOf(
                "content-type" to "application/json",
                SpfnWireHeaders.SERVER_CONTRACT_VERSION to SpfnGeneratedContract.BINDING.importedVersion,
                SpfnWireHeaders.SUPPORTED_CONTRACT_RANGE to SpfnGeneratedContract.BINDING.supportedRange
            ),
            body = body.toByteArray(Charsets.UTF_8)
        );
}

/** Signs with zeros: the demo server reads no proof. Not a key, and nothing verifies it. */
private object DemoKeyProvider : SpfnKeyProvider
{
    override val clientId: String = DEMO_CLIENT_ID

    override val keyId: String = "demo-key"

    override fun sign(message: ByteArray): ByteArray = ByteArray(64);
}

/** The device's own clock: there is no server time to anchor to. */
private object DemoProofClock : SpfnProofClock
{
    override suspend fun nowMillis(transport: SpfnTransport, baseUrl: String, timeoutMillis: Long): Long = System.currentTimeMillis();

    override suspend fun discardAnchor(baseUrl: String) = Unit;
}

private const val DEMO_BASE_URL = "https://events.example.invalid";
private const val DEMO_CLIENT_ID = "demo-client";
