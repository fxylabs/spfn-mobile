// SPFN Mobile — the Android SDK, wired to a reference server.
//
// The integration suite uses the shipped client rather than a copy of it: the same
// `SpfnClient`, `SpfnSession` and `SpfnOkHttpTransport` an app would link. The only thing
// assembled here is the per-operation call descriptor, which the contract generator does
// not emit yet (docs/SCAFFOLD-STATUS.md), and the clock, which every test injects.
//
// The reference server is started in process by default and left alone when the run names
// an external one; see `SpfnReferenceTarget.kt` for how that is chosen and why a named
// target is never allowed to fall back to a local server.

package xyz.superfunction.spfn.reference

import okhttp3.OkHttpClient
import xyz.superfunction.spfn.client.SpfnCall
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnClock
import xyz.superfunction.spfn.client.SpfnKeyProvider
import xyz.superfunction.spfn.client.SpfnSoftwareKeyProvider
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnMonotonicClock
import xyz.superfunction.spfn.client.SpfnProcessServerClock
import xyz.superfunction.spfn.client.SpfnProofClock
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnSystemClock
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse
import xyz.superfunction.spfn.generated.SpfnServerTimeResponse
import java.util.concurrent.TimeUnit

/** The call descriptors the three operations need, spelled out once. */
object SpfnReferenceCalls
{
    val echo: SpfnCall<SpfnEchoRequest, SpfnEchoResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.echoSend,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnEchoResponse.decode(value) }
    )

    val listItems: SpfnCall<SpfnListItemsRequest, SpfnListItemsResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.itemsList,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnListItemsResponse.decode(value) }
    )
}

/**
 * Which server this run is talking to, and everything that differs because of it.
 *
 * In process the client clock is frozen at the instant the server's test clock starts, so
 * a proof a frozen client mints is inside the server's replay window and the server's own
 * time is a value the case can assert exactly. Against an external server neither is true:
 * it runs on the wall clock, so the client has to as well, and the instant it answers with
 * is only knowable as "a real one".
 */
private class SpfnReferenceMode(
    val local: SpfnReferenceHarness?,
    val baseUrl: String,
    val control: SpfnReferenceControlSurface,
    val expectedServerTimeMillis: Long?,
    val clientClock: SpfnClock,
    val proofClock: SpfnProofClock
)
{
    companion object
    {
        fun resolve(): SpfnReferenceMode
        {
            val target = SpfnIntegrationTarget.resolve() ?: return inProcess();
            return SpfnReferenceMode(
                local = null,
                baseUrl = target.baseUrl,
                control = SpfnHttpControl(target),
                expectedServerTimeMillis = null,
                clientClock = SpfnSystemClock(),
                proofClock = SpfnProcessServerClock.shared
            );
        }

        private fun inProcess(): SpfnReferenceMode
        {
            val server = SpfnReferenceHarness();
            return SpfnReferenceMode(
                local = server,
                baseUrl = server.baseUrl,
                control = SpfnInProcessControl(server.server.state),
                expectedServerTimeMillis = SpfnReferenceTestClock.DEFAULT_START_MILLIS,
                clientClock = SpfnClock { SpfnReferenceTestClock.DEFAULT_START_MILLIS },
                // The in-process server deliberately freezes time so expiry tests can
                // move it without sleeping. Its proof clock must observe that same test
                // timeline; pairing a frozen server with System.nanoTime makes every
                // otherwise valid proof future-dated as soon as one millisecond passes.
                proofClock = SpfnProcessServerClock(
                    monotonicClock = SpfnMonotonicClock {
                        server.clock.nowMillis() * NANOS_PER_MILLISECOND
                    },
                    operationResolver = {
                        SpfnGeneratedOperations.operation(
                            xyz.superfunction.spfn.generated.SpfnGeneratedContract.CLOCK_SYNCHRONIZATION_OPERATION_ID
                        )
                    }
                )
            );
        }
    }
}

/**
 * One SDK client pointed at a reference server, with everything a test needs to observe.
 *
 * The server is started in process unless the run named an external one, and the case
 * bodies cannot tell the difference: every state a case arranges goes through [control],
 * which is a method call on one side and `/control` over HTTP on the other.
 *
 * The server is reset on construction, so a case that revoked a key cannot decide the
 * outcome of the case that runs after it. In process that is a fresh server anyway; against
 * a long-lived external one it is the only thing that keeps the cases independent.
 */
class SpfnReferenceClientHarness(timeoutMillis: Long = 5_000) : AutoCloseable
{
    private val mode = SpfnReferenceMode.resolve()

    val baseUrl: String get() = mode.baseUrl

    val control: SpfnReferenceControlSurface get() = mode.control

    /** The instant the server answers with, when this run is in a position to know it. */
    val expectedServerTimeMillis: Long? get() = mode.expectedServerTimeMillis

    val clientClock: SpfnClock get() = mode.clientClock

    private val okHttpClient = OkHttpClient()

    private val networkTransport: SpfnTransport = SpfnOkHttpTransport(okHttpClient)
    private val recordingTransport = SpfnReferenceRecordingTransport(networkTransport)

    val transport: SpfnTransport = recordingTransport

    val proofClock: SpfnProofClock = mode.proofClock

    val session: SpfnSession = SpfnSession(
        transport = transport,
        // The fixture test keypair, whose public half every reference target — this
        // repository's server and the primitives dev server — pre-registers. TEST
        // ONLY; the private half is published on purpose and authenticates nothing.
        keyProvider = SpfnSoftwareKeyProvider.fromPkcs8(
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            privateKeyPkcs8 = java.util.Base64.getDecoder().decode(SpfnReferenceTestKeys.PRIVATE_KEY_PKCS8_B64),
            publicKeySpkiDer = SpfnReferenceTestKeys.PUBLIC_KEY_SPKI_DER
        ),
        baseUrl = mode.baseUrl,
        clock = proofClock,
        timeoutMillis = timeoutMillis
    )

    val client: SpfnClient = SpfnClient(transport, session, timeoutMillis = timeoutMillis)

    init
    {
        control.reset();
    }

    fun stats(): SpfnReferenceStats = control.stats()

    /**
     * Compares the process anchor with a later server sample without minting a proof.
     * A positive result would mean the clock can create a future-dated proof.
     */
    suspend fun proofClockLeadMillis(timeoutMillis: Long = 5_000): Long
    {
        val derived = proofClock.nowMillis(transport, baseUrl, timeoutMillis);
        val operation = SpfnGeneratedOperations.coreTime;
        val response = transport.execute(
            xyz.superfunction.spfn.client.SpfnTransportRequest(
                method = operation.method,
                url = baseUrl + operation.path,
                headers = emptyList(),
                body = null,
                timeoutMillis = timeoutMillis
            )
        );
        val server = SpfnServerTimeResponse.decode(xyz.superfunction.spfn.core.SpfnCanonicalJson.parse(response.body));
        return derived - server.serverTimeMillis;
    }

    suspend fun lastProofLeadMillis(timeoutMillis: Long = 5_000): Long?
    {
        val issuedAt = recordingTransport.lastIssuedAtMillis ?: return null;
        val operation = SpfnGeneratedOperations.coreTime;
        val response = networkTransport.execute(
            xyz.superfunction.spfn.client.SpfnTransportRequest(
                method = operation.method,
                url = baseUrl + operation.path,
                headers = emptyList(),
                body = null,
                timeoutMillis = timeoutMillis
            )
        );
        val server = SpfnServerTimeResponse.decode(xyz.superfunction.spfn.core.SpfnCanonicalJson.parse(response.body));
        return issuedAt - server.serverTimeMillis;
    }

    /**
     * A client over a session signing with [provider] — what case f uses to prove with
     * a key the lifecycle enrolled rather than the pre-registered fixture key.
     */
    fun client(provider: SpfnKeyProvider, timeoutMillis: Long = 5_000): SpfnClient
    {
        val signingSession = SpfnSession(
            transport = transport,
            keyProvider = provider,
            baseUrl = baseUrl,
            clock = proofClock,
            timeoutMillis = timeoutMillis
        );
        return SpfnClient(transport, signingSession, timeoutMillis = timeoutMillis);
    }

    override fun close()
    {
        okHttpClient.dispatcher.executorService.shutdown();
        okHttpClient.connectionPool.evictAll();
        okHttpClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS);
        // Only ever the server this harness started. An external target outlives the run
        // that used it, and stopping one would be this suite reaching outside itself.
        mode.local?.close();
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L

private class SpfnReferenceRecordingTransport(private val delegate: SpfnTransport) : SpfnTransport
{
    @Volatile
    var lastIssuedAtMillis: Long? = null
        private set

    override suspend fun execute(request: xyz.superfunction.spfn.client.SpfnTransportRequest): xyz.superfunction.spfn.client.SpfnTransportResponse
    {
        request.headers.firstOrNull { it.first == xyz.superfunction.spfn.client.SpfnWireHeaders.ISSUED_AT_MILLIS }
            ?.second?.toLongOrNull()?.let { lastIssuedAtMillis = it };
        return delegate.execute(request);
    }
}

/**
 * The custody seams' software halves for the integration run: JCA keys behind the
 * engine seam and an in-memory metadata store. What case f proves is the wire; the
 * persistence and hardware halves have their own suites and their own device axis.
 */
class SpfnIntegrationSoftwareEngine : xyz.superfunction.spfn.client.SpfnKeystoreEngine
{
    private val keys = java.util.concurrent.ConcurrentHashMap<String, java.security.KeyPair>()

    override fun generate(
        alias: String,
        preferStrongBox: Boolean
    ): xyz.superfunction.spfn.client.SpfnKeystoreGeneratedKey
    {
        val generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"));
        val pair = generator.generateKeyPair();
        keys[alias] = pair;
        return xyz.superfunction.spfn.client.SpfnKeystoreGeneratedKey(
            xyz.superfunction.spfn.client.SpfnKeyCustody.TRUSTED_ENVIRONMENT,
            pair.public.encoded
        );
    }

    override fun publicKeySpkiDer(alias: String): ByteArray? = keys[alias]?.public?.encoded

    override fun signDer(alias: String, message: ByteArray): ByteArray
    {
        val key = keys[alias]?.private ?: throw IllegalStateException("no signing key under this alias");
        val signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    override fun contains(alias: String): Boolean = keys.containsKey(alias)

    override fun delete(alias: String)
    {
        keys.remove(alias);
    }
}

class SpfnIntegrationMetadataStore : xyz.superfunction.spfn.client.SpfnKeyMetadataStore
{
    private val records = java.util.concurrent.ConcurrentHashMap<String, xyz.superfunction.spfn.client.SpfnStoredKeyMetadata>()

    override fun load(slot: String): xyz.superfunction.spfn.client.SpfnStoredKeyMetadata? = records[slot]

    override fun save(slot: String, metadata: xyz.superfunction.spfn.client.SpfnStoredKeyMetadata)
    {
        records[slot] = metadata;
    }

    override fun delete(slot: String)
    {
        records.remove(slot);
    }
}
