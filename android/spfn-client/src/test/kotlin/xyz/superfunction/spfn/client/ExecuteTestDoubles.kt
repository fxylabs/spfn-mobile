// SPFN Mobile — what the execute suite sends requests at.
//
// Two doubles, because the suite asks two different kinds of question. [ScriptedTransport]
// answers by position and settles what one call does. [RevokingServer] answers by what the
// request presented, which is the only way to ask what several concurrent calls do to each
// other: under a positional script, whichever coroutine happened to run first would consume
// an answer meant for another one and the test would pass or fail by scheduling.
//
// ExecuteTestDoubles.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CompletableDeferred
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.generated.SpfnItem
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse
import xyz.superfunction.spfn.generated.SpfnRegisterRequest
import xyz.superfunction.spfn.generated.SpfnRegisterResponse
import xyz.superfunction.spfn.generated.SpfnRotateKeyRequest
import xyz.superfunction.spfn.generated.SpfnRotateKeyResponse

// ---- contract bodies, spelled as a server would put them on the wire --------

object ExecuteFixtures
{
    /**
     * A provider over the fixture test keypair. Not a credential; see
     * Contracts/fixtures/proof/proof-input.json — the private half is published there
     * on purpose. Read from the fixture rather than restated, so the suite cannot
     * silently sign with something else.
     */
    fun syntheticProvider(clientId: String = SessionFixtureValues.CLIENT_ID): SpfnSoftwareKeyProvider
    {
        val keyPair = WireFixtures.wire().obj("testKeyPair");
        return SpfnSoftwareKeyProvider.fromPkcs8(
            clientId = clientId,
            keyId = keyPair.text("keyId"),
            privateKeyPkcs8 = java.util.Base64.getDecoder().decode(keyPair.text("privateKeyPkcs8Base64")),
            publicKeySpkiDer = java.util.Base64.getDecoder().decode(keyPair.text("publicKeySpkiBase64"))
        );
    }

    /** The public half of the fixture keypair, for verifying what a session signed. */
    fun fixturePublicKeySpkiDer(): ByteArray =
        java.util.Base64.getDecoder().decode(WireFixtures.wire().obj("testKeyPair").text("publicKeySpkiBase64"))

    fun handshakeResponse(sessionId: String, expiringAt: Long): String =
        "{\"expiresAtMillis\":$expiringAt,\"sessionId\":\"$sessionId\"}"

    /** An error envelope in canonical form. Keys sort as the canonical encoder sorts them. */
    fun errorEnvelope(
        code: String,
        message: String = "refused",
        requestId: String = "req-test-0001"
    ): String = "{\"error\":{\"code\":\"$code\",\"message\":\"$message\",\"requestId\":\"$requestId\"}}"

    val ECHO_REQUEST = SpfnEchoRequest(message = "hello", sequence = 7)

    val ECHO_RESPONSE = SpfnEchoResponse(message = "hello", sequence = 7, serverTimeMillis = 1_750_000_000_500)

    val ECHO_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(ECHO_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)

    /**
     * A register request for the contract's unproven class. Every value is a synthetic
     * test constant; the "password" authenticates nothing and never meets a real endpoint.
     */
    val REGISTER_REQUEST = SpfnRegisterRequest(
        email = "enroll@example.invalid",
        phone = null,
        verificationToken = "verify-test-0001",
        password = "password-test-0001",
        publicKey = "cHVibGljLWtleS10ZXN0",
        keyId = "key-test-0001",
        fingerprint = "0".repeat(64),
        algorithm = SpfnKeyAlgorithm.ES256
    )

    val REGISTER_RESPONSE = SpfnRegisterResponse(
        userId = "user-test-0001",
        publicId = "public-test-0001",
        email = "enroll@example.invalid",
        phone = null
    )

    val REGISTER_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(REGISTER_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)

    val ROTATE_REQUEST = SpfnRotateKeyRequest(
        publicKey = "cHVibGljLWtleS10ZXN0LTI",
        keyId = "key-test-0002",
        fingerprint = "1".repeat(64),
        algorithm = SpfnKeyAlgorithm.ES256
    )

    val ROTATE_RESPONSE = SpfnRotateKeyResponse(success = true, keyId = "key-test-0002")

    val ROTATE_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(ROTATE_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)

    /** Contract 0.10.0's bodyless operation. Every value is a synthetic test constant. */
    val DENY_REQUEST = SpfnDenyDeviceAuthRequest(userCode = "WDJB-MJHT")

    /**
     * Its sibling that does declare a response, so the two branches of the reader can be
     * asked the same questions and answer differently.
     */
    val APPROVE_REQUEST = SpfnApproveDeviceAuthRequest(userCode = "WDJB-MJHT")

    val APPROVE_RESPONSE = SpfnDeviceAuthInfoResponse(
        deviceName = "Test Phone",
        platform = SpfnKeyPlatform.IOS,
        fingerprintPrefix = "ab12cd34",
        requestedAtMillis = 1_750_000_000_000,
        expiresAtMillis = 1_750_000_600_000
    )

    val APPROVE_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(APPROVE_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)

    val LIST_REQUEST = SpfnListItemsRequest(limit = 2, cursor = "cursor-1")

    val LIST_RESPONSE = SpfnListItemsResponse(
        items = listOf(SpfnItem(id = "item-1", name = "first", updatedAtMillis = 1_750_000_000_100)),
        nextCursor = "cursor-2"
    )

    val LIST_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(LIST_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)
}

// ---- the three calls --------------------------------------------------------

// Hand-written here even though `SpfnGeneratedCalls` now ships one descriptor per
// operation. These are not a copy of it: this suite holds the one execute path to what it
// does with whatever descriptor it is handed, and `UNDECLARED` below names an auth class no
// contract declares, so the generator can never emit it. Asserting against generated values
// instead would make the client's own regression suite move whenever the contract does, and
// would leave the refusal that matters most untestable.

object ExecuteCalls
{
    val ECHO = SpfnCall<SpfnEchoRequest, SpfnEchoResponse>(
        operation = SpfnGeneratedOperations.echoSend,
        encode = { it.canonicalValue() },
        decode = { SpfnEchoResponse.decode(it) }
    )

    val LIST = SpfnCall<SpfnListItemsRequest, SpfnListItemsResponse>(
        operation = SpfnGeneratedOperations.itemsList,
        encode = { it.canonicalValue() },
        decode = { SpfnListItemsResponse.decode(it) }
    )

    /**
     * The handshake, described the same way as the others so the suite can show that
     * `execute` refuses it on the operation rather than on how it was described.
     */
    val HANDSHAKE = SpfnCall<SpfnHandshakeRequest, SpfnHandshakeResponse>(
        operation = SpfnGeneratedOperations.authClientProofHandshake,
        encode = { it.canonicalValue() },
        decode = { SpfnHandshakeResponse.decode(it) }
    )

    /** The contract's unproven class, as generated: no proof, no session, no handshake. */
    val REGISTER = SpfnCall<SpfnRegisterRequest, SpfnRegisterResponse>(
        operation = SpfnGeneratedOperations.authEnrollRegister,
        encode = { it.canonicalValue() },
        decode = { SpfnRegisterResponse.decode(it) }
    )

    /**
     * Proven but session-free: the rotation operation authenticates with the old key
     * alone, so it carries every proof header and never a session header.
     */
    val ROTATE = SpfnCall<SpfnRotateKeyRequest, SpfnRotateKeyResponse>(
        operation = SpfnGeneratedOperations.authKeysRotate,
        encode = { it.canonicalValue() },
        decode = { SpfnRotateKeyResponse.decode(it) }
    )

    /**
     * The contract's one operation that declares no response type. Built through the
     * factory rather than by hand: there is nothing to decode, and the factory is where
     * that decision is written down once.
     */
    val DENY = SpfnCall.noResponse<SpfnDenyDeviceAuthRequest>(
        operation = SpfnGeneratedOperations.authDeviceDeny,
        encode = { it.canonicalValue() }
    )

    /**
     * The same flow's operation that does declare a response, so the regression guard asks
     * a declared-response operation the questions the bodyless one is asked.
     */
    val APPROVE = SpfnCall<SpfnApproveDeviceAuthRequest, SpfnDeviceAuthInfoResponse>(
        operation = SpfnGeneratedOperations.authDeviceApprove,
        encode = { it.canonicalValue() },
        decode = { SpfnDeviceAuthInfoResponse.decode(it) }
    )

    /**
     * An operation naming an auth class the contract does not declare. The descriptor
     * is hand-built because the generator can never emit one — that is the point.
     */
    val UNDECLARED = SpfnCall<SpfnEchoRequest, SpfnEchoResponse>(
        operation = SpfnOperation(
            id = "mystery.op",
            method = "POST",
            path = "/v1/mystery",
            authProfile = "mysteryV9",
            requiresSession = true,
            declaresResponse = true
        ),
        encode = { it.canonicalValue() },
        decode = { SpfnEchoResponse.decode(it) }
    )
}

// ---- a server that answers what it was shown --------------------------------

/**
 * Issues a new session per handshake and refuses any request presenting a revoked one.
 *
 * Deliberately not a script. The question it exists to answer — do N concurrent calls
 * meeting one revocation share one re-handshake — is a question about what the calls do to
 * each other, and any answer chosen by position would be an answer about scheduling.
 *
 * @param holdingFirst how many operation requests are held until that many have arrived.
 *   Without it a concurrency test proves nothing: one coroutine can finish its whole retry
 *   before another has sent anything, and then the calls never met.
 */
class RevokingServer(
    private val revoked: Set<String>,
    private val expiresAtMillis: Long = SessionFixtureValues.EXPIRES_AT_MILLIS,
    holdingFirst: Int = 0
) : SpfnTransport
{
    private val firstRound: Barrier? = if (holdingFirst > 0) Barrier(holdingFirst) else null
    private val lock = Any()
    private val recorded = mutableListOf<SpfnTransportRequest>()
    private var issued = 0
    private var operations = 0

    val received: List<SpfnTransportRequest> get() = synchronized(lock) { recorded.toList() }

    val callCount: Int get() = synchronized(lock) { recorded.size }

    /** How many of the recorded calls opened a session. */
    val handshakes: Int
        get() = received.count { it.url.endsWith(SpfnGeneratedOperations.authClientProofHandshake.path) }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        synchronized(lock) { recorded.add(request) };

        if (request.url.endsWith(SpfnGeneratedOperations.authClientProofHandshake.path))
        {
            val next = synchronized(lock) { ++issued };
            return jsonResponse(200, ExecuteFixtures.handshakeResponse("session-$next", expiresAtMillis));
        }

        val arrival = synchronized(lock) { ++operations };
        if (firstRound != null && arrival <= firstRound.width)
        {
            firstRound.arriveAndWait();
        }

        val presented = request.headers.firstOrNull { it.first == SpfnWireHeaders.SESSION }?.second ?: "";
        if (revoked.contains(presented))
        {
            return jsonResponse(401, ExecuteFixtures.errorEnvelope("SESSION_REVOKED"));
        }
        return jsonResponse(200, ExecuteFixtures.ECHO_RESPONSE_BODY);
    }
}

/** Holds every arrival until [width] of them have arrived, then releases all of them. */
class Barrier(val width: Int)
{
    private val released = CompletableDeferred<Unit>()
    private val lock = Any()
    private var arrived = 0

    suspend fun arriveAndWait()
    {
        val full = synchronized(lock) { ++arrived >= width };
        if (full)
        {
            released.complete(Unit);
            return;
        }
        released.await();
    }
}
