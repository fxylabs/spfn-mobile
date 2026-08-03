// SPFN Mobile — what the reference server's own tests are built out of.
//
// The unit tests here speak raw HTTP on purpose. Driving the server through the SDK
// would make every answer depend on the SDK agreeing with it, and the questions these
// tests ask — is a non-canonical body refused, is a repeated header refused — are exactly
// the ones the SDK never asks because the SDK never sends them.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/** One raw HTTP answer, unclassified. */
class SpfnRawResponse(val statusCode: Int, val body: ByteArray)
{
    fun envelope(): SpfnErrorEnvelope = SpfnErrorEnvelope.decode(SpfnCanonicalJson.parse(body))

    fun errorCode(): String = envelope().code

    fun value(): SpfnCanonicalValue = SpfnCanonicalJson.parse(body)
}

/**
 * A running server plus the small amount of client-shaped code a raw test needs.
 *
 * The clock is a test clock and the port is whatever the operating system had free, so
 * two of these can run at once and neither has to sleep to reach an expiry.
 */
class SpfnReferenceHarness(
    sessionTtlMillis: Long = SpfnReferenceState.DEFAULT_SESSION_TTL_MILLIS
) : AutoCloseable
{
    val clock: SpfnReferenceTestClock = SpfnReferenceTestClock()

    val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    val server: SpfnReferenceServer = SpfnReferenceServer(
        clock = clock,
        sessionTtlMillis = sessionTtlMillis,
        log = { line -> logLines.add(line) }
    ).start()

    private var nonceCounter = 0

    val baseUrl: String
        get() = server.baseUrl

    override fun close()
    {
        server.close();
    }

    fun nextNonce(): String
    {
        nonceCounter += 1;
        return "nonce-harness-%06d".format(nonceCounter);
    }

    /**
     * The headers a well-formed request carries, over the exact [body] bytes given.
     *
     * Taking the digest over the caller's bytes rather than over a re-encoding is what
     * lets a test send a non-canonical body under a proof that verifies, which is the only
     * way to prove the canonical check is doing anything.
     */
    fun proofHeaders(
        operation: SpfnOperation,
        body: ByteArray,
        sessionId: String? = null,
        nonce: String = nextNonce(),
        issuedAtMillis: Long = clock.nowMillis(),
        clientId: String = SpfnReferenceTestKeys.CLIENT_ID,
        keyId: String = SpfnReferenceTestKeys.KEY_ID,
        /** Signs the assembled proof input; the test keypair unless a test brings its own. */
        proofFor: (SpfnProofInput) -> String = SpfnReferenceTestKeys::proofFor
    ): MutableList<Pair<String, String>>
    {
        val input = SpfnProofInput.forRequest(
            method = operation.method,
            path = operation.path,
            clientId = clientId,
            keyId = keyId,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis,
            canonicalBody = body
        );

        val headers = mutableListOf(
            SpfnReferenceWire.CONTENT_TYPE to SpfnReferenceWire.REQUEST_CONTENT_TYPE,
            SpfnReferenceWire.PROFILE to SpfnReferenceWire.PROFILE_NAME,
            SpfnReferenceWire.CLIENT_ID to clientId,
            SpfnReferenceWire.KEY_ID to keyId,
            SpfnReferenceWire.NONCE to nonce,
            SpfnReferenceWire.ISSUED_AT_MILLIS to issuedAtMillis.toString(),
            SpfnReferenceWire.PROOF to proofFor(input)
        );
        if (sessionId != null)
        {
            headers.add(SpfnReferenceWire.SESSION to sessionId);
        }
        return headers;
    }

    /** Opens a session the way a well-behaved client would, and returns its identifier. */
    fun openSession(): String
    {
        val operation = SpfnGeneratedOperations.authClientProofHandshake;
        val nonce = nextNonce();
        val body = SpfnCanonicalJson.encode(
            SpfnHandshakeRequest(
                clientId = SpfnReferenceTestKeys.CLIENT_ID,
                keyId = SpfnReferenceTestKeys.KEY_ID,
                nonce = nonce,
                issuedAtMillis = clock.nowMillis()
            ).canonicalValue()
        );

        val response = send(operation, body, proofHeaders(operation, body, nonce = nonce));
        check(response.statusCode == 200) { "handshake refused with ${response.statusCode}" };
        return SpfnHandshakeResponse.decode(response.value()).sessionId;
    }

    fun send(
        operation: SpfnOperation,
        body: ByteArray,
        headers: List<Pair<String, String>>
    ): SpfnRawResponse = send(operation.method, operation.path, body, headers)

    /**
     * Sends one request over a raw socket and reads the whole answer.
     *
     * A socket rather than `HttpURLConnection`, because half of what these tests need to
     * send is what an HTTP client library exists to prevent: a header field written twice,
     * a body whose bytes are exactly what the test chose. A library that quietly folded a
     * repeated field into one comma-joined value would turn a "repeated header" test into
     * a "malformed value" test without anything failing.
     */
    fun send(
        method: String,
        path: String,
        body: ByteArray?,
        headers: List<Pair<String, String>>
    ): SpfnRawResponse
    {
        val payload = body ?: ByteArray(0);
        val request = StringBuilder();
        request.append("$method $path HTTP/1.1\r\n");
        request.append("host: 127.0.0.1:${server.port}\r\n");
        for ((name, value) in headers)
        {
            request.append("$name: $value\r\n");
        }
        request.append("content-length: ${payload.size}\r\n");
        request.append("connection: close\r\n\r\n");

        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS;
            socket.connect(InetSocketAddress("127.0.0.1", server.port), TIMEOUT_MILLIS);
            socket.getOutputStream().apply {
                write(request.toString().toByteArray(Charsets.ISO_8859_1));
                write(payload);
                flush();
            };
            return parse(socket.getInputStream().readBytes());
        }
    }

    private fun parse(raw: ByteArray): SpfnRawResponse
    {
        val separator = indexOfHeaderEnd(raw);
        check(separator >= 0) { "the answer had no header terminator" };

        val head = String(raw, 0, separator, Charsets.ISO_8859_1);
        val status = head.lineSequence().first().split(' ')[1].toInt();
        return SpfnRawResponse(status, raw.copyOfRange(separator + 4, raw.size));
    }

    private fun indexOfHeaderEnd(raw: ByteArray): Int
    {
        for (index in 0..raw.size - 4)
        {
            if (raw[index] == CR && raw[index + 1] == LF && raw[index + 2] == CR && raw[index + 3] == LF)
            {
                return index;
            }
        }
        return -1;
    }

    private companion object
    {
        const val TIMEOUT_MILLIS = 10_000
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
    }
}

/**
 * Where a test records that it actually ran.
 *
 * `run-integration.sh` passes the directory and checks it afterwards. A suite that
 * skipped every case leaves nothing here, so a silent skip cannot be read as a pass.
 */
object SpfnIntegrationReceipt
{
    fun record(name: String)
    {
        val directory = File(
            System.getProperty("spfn.integrationReceipts")
                ?: error("spfn.integrationReceipts is not set; the integration suite refuses to run unobserved")
        );
        directory.mkdirs();
        File(directory, name).writeText("$name\n");
    }
}
