// SPFN Mobile — the device-authorization table, one test per cell.
//
// The table is the upstream service's own, restated in SpfnReferenceState's header and
// transcribed here from the same source (spfn packages/auth/.../device-auth.service.ts at
// 77fe6246) rather than from this server's behaviour — P10: an oracle read off the subject
// proves the subject agrees with itself.
//
//   | state ↓ op → | info           | approve        | deny           | poll                       |
//   | pending      | device details | → approved     | → denied       | pending                    |
//   | approved     | AlreadyHandled | AlreadyHandled | AlreadyHandled | key registered, → consumed |
//   | denied       | AlreadyHandled | AlreadyHandled | AlreadyHandled | Denied                     |
//   | consumed     | NotFound       | NotFound       | NotFound       | NotFound                   |
//   | expired      | Expired        | Expired        | Expired        | Expired                    |
//   | unknown      | NotFound       | NotFound       | NotFound       | NotFound                   |
//
// Twenty-four cells, plus two things that are not cells and that the cells cannot see:
// an `approve` presented without a proof, which admission refuses before the table is ever
// consulted, and a record that is consumed AND past its TTL, which is the only arrangement
// where the order of the two refusal checks is observable.
//
// Raw HTTP on purpose, as the rest of this directory does: driving the server through the
// SDK would make every answer depend on the SDK agreeing with it.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnEcdsa
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.generated.SpfnDeviceAuthPollStatus
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import xyz.superfunction.spfn.generated.SpfnPollDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnPollDeviceAuthResponse
import xyz.superfunction.spfn.generated.SpfnStartDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnStartDeviceAuthResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SpfnReferenceDeviceAuthTest
{
    // ---- row: pending ------------------------------------------------------

    @Test
    fun pendingInfoDescribesTheWaitingDevice()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = startDevice(harness);

            val response = harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode));

            assertEquals(200, response.statusCode);
            val described = SpfnDeviceAuthInfoResponse.decode(response.value());
            assertEquals(DEVICE_NAME, described.deviceName);
            assertEquals(SpfnKeyPlatform.ANDROID, described.platform);
            assertEquals(
                "the approver is shown the same prefix length the key list truncates to",
                parked.fingerprint.take(SpfnReferenceState.KEY_FINGERPRINT_PREFIX_LENGTH),
                described.fingerprintPrefix
            );
            assertEquals(SpfnReferenceTestClock.DEFAULT_START_MILLIS, described.requestedAtMillis);
            assertEquals(
                SpfnReferenceTestClock.DEFAULT_START_MILLIS + SpfnReferenceState.DEVICE_AUTH_TTL_MILLIS,
                described.expiresAtMillis
            );
        }
    }

    @Test
    fun pendingApproveBindsTheRecordAndDescribesTheDevice()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = startDevice(harness);

            val response = harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode));

            assertEquals(200, response.statusCode);
            assertEquals(DEVICE_NAME, SpfnDeviceAuthInfoResponse.decode(response.value()).deviceName);
        }
    }

    @Test
    fun pendingDenyAnswers204WithNoBody()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = startDevice(harness);

            val response = harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode));

            assertEquals("the first no-response operation this server serves", 204, response.statusCode);
            assertEquals("a 204 carries no bytes at all", 0, response.body.size);
        }
    }

    @Test
    fun pendingPollAnswersPendingWithTheInterval()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = startDevice(harness);

            val response = harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode));

            assertEquals(200, response.statusCode);
            val answered = SpfnPollDeviceAuthResponse.decode(response.value());
            assertEquals(SpfnDeviceAuthPollStatus.PENDING, answered.status);
            assertEquals(SpfnReferenceState.DEVICE_AUTH_INTERVAL_MILLIS, answered.intervalMillis);
            assertNull("a pending answer carries the interval and nothing else", answered.userId);
        }
    }

    // ---- row: approved -----------------------------------------------------

    @Test
    fun approvedInfoIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = approvedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    @Test
    fun approvedApproveIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = approvedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    @Test
    fun approvedDenyIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = approvedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    /**
     * The one cell with a side effect: the poll registers the parked key under the
     * approver and spends the record. Registration is asserted by proving with the key —
     * a handshake under the approver's own id — because "the answer said approved" and
     * "the key can now sign" are different claims and only the second one is the point.
     */
    @Test
    fun approvedPollRegistersTheKeyAndConsumesTheRecord()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = approvedDevice(harness);

            val response = harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode));

            assertEquals(200, response.statusCode);
            val answered = SpfnPollDeviceAuthResponse.decode(response.value());
            assertEquals(SpfnDeviceAuthPollStatus.APPROVED, answered.status);
            assertEquals(
                "the owner is the approver the admitted proof named",
                SpfnReferenceTestKeys.CLIENT_ID,
                answered.userId
            );
            assertEquals(SpfnReferenceState.publicIdOf(SpfnReferenceTestKeys.CLIENT_ID), answered.publicId);
            assertEquals(false, answered.passwordChangeRequired);
            assertNull("an approved answer carries no interval", answered.intervalMillis);

            assertEquals(
                "the parked key proves for the approver",
                200,
                harness.handshake(parked.keyId, SpfnReferenceTestKeys.CLIENT_ID, parked.keyPair).statusCode
            );
        }
    }

    // ---- row: denied -------------------------------------------------------

    @Test
    fun deniedInfoIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = deniedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    @Test
    fun deniedApproveIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = deniedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    @Test
    fun deniedDenyIsAlreadyHandled()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = deniedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode)),
                "DeviceAuthAlreadyHandledError",
                409
            );
        }
    }

    /** The one answer that tells the waiting device to stop rather than to keep waiting. */
    @Test
    fun deniedPollIsDenied()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = deniedDevice(harness);
            assertRefusal(
                harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode)),
                "DeviceAuthDeniedError",
                403
            );
        }
    }

    // ---- row: consumed -----------------------------------------------------

    @Test
    fun consumedInfoIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = consumedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    @Test
    fun consumedApproveIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = consumedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    @Test
    fun consumedDenyIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = consumedDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    /** A spent record answers as unknown, which is what makes the approval a one-shot. */
    @Test
    fun consumedPollIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = consumedDevice(harness);
            assertRefusal(
                harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    // ---- row: expired ------------------------------------------------------

    @Test
    fun expiredInfoIsExpired()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = expiredDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode)),
                "DeviceAuthExpiredError",
                400
            );
        }
    }

    @Test
    fun expiredApproveIsExpired()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = expiredDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode)),
                "DeviceAuthExpiredError",
                400
            );
        }
    }

    @Test
    fun expiredDenyIsExpired()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = expiredDevice(harness);
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode)),
                "DeviceAuthExpiredError",
                400
            );
        }
    }

    /**
     * Expiry outranks approval: a record nobody collected in time registers nothing. The
     * record is approved first and then aged past its TTL, which is the only ordering
     * that tells the two apart.
     */
    @Test
    fun expiredPollIsExpired()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = approvedDevice(harness);
            harness.clock.advance(SpfnReferenceState.DEVICE_AUTH_TTL_MILLIS + 1);
            assertRefusal(
                harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode)),
                "DeviceAuthExpiredError",
                400
            );
        }
    }

    // ---- row: unknown ------------------------------------------------------

    @Test
    fun unknownInfoIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(UNISSUED_USER_CODE)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    @Test
    fun unknownApproveIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(UNISSUED_USER_CODE)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    @Test
    fun unknownDenyIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(UNISSUED_USER_CODE)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    @Test
    fun unknownPollIsNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            assertRefusal(
                harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(UNISSUED_DEVICE_CODE)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    /**
     * The `consumed` row's answers have to survive the TTL, which is the whole reason the
     * two checks are ordered rather than independent.
     *
     * Not a cell of its own — a record is in one row at a time — but the cell above it
     * cannot tell the order apart: every consumed record in this file is inside its TTL,
     * so a server that judged expiry first would answer all four of them identically and
     * pass. Aged past the TTL the two orders disagree, and only this test sees it. A
     * spent code that started saying "expired" would be an enumeration oracle: a code
     * that was never issued says "not found", and the difference is the answer to "did my
     * guess land?" ten minutes late.
     */
    @Test
    fun consumedThenExpiredStillAnswersNotFound()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = consumedDevice(harness);
            harness.clock.advance(SpfnReferenceState.DEVICE_AUTH_TTL_MILLIS + 1);

            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
            assertRefusal(
                harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode)),
                "DeviceAuthNotFoundError",
                404
            );
            assertRefusal(
                harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode)),
                "DeviceAuthNotFoundError",
                404
            );
        }
    }

    // ---- not in the table: an approval nobody proved ------------------------

    /**
     * `approve` is the one call that binds an account, so it is the one that must be
     * proved. An unproven one is refused by admission before the table is consulted —
     * which the still-pending record afterwards is what proves.
     */
    @Test
    fun unprovenApproveIsRefusedByAdmission()
    {
        SpfnReferenceHarness().use { harness ->
            val parked = startDevice(harness);

            val refused = harness.sendUnproven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode));

            // The admission refusal, in the vocabulary admission owns: a proven operation
            // arriving without the contract header fields is the two ends disagreeing
            // about what the contract is, which is what this server answers that with.
            assertEquals("CONTRACT_UNSUPPORTED", refused.errorCode());
            assertEquals(409, refused.statusCode);
            assertTrue(
                "an approval refused by admission is not one of the table's own answers",
                refused.errorCode() !in DEVICE_AUTH_CODES
            );

            val stillPending = harness.sendProven(SpfnGeneratedOperations.authDeviceInfo, infoBody(parked.userCode));
            assertEquals("the record was not touched", 200, stillPending.statusCode);
        }
    }

    // ---- arranging a row ---------------------------------------------------

    /** One parked device: what `start` answered, and the key it parked. */
    private class ParkedDevice(
        val deviceCode: String,
        val userCode: String,
        val keyId: String,
        val fingerprint: String,
        val keyPair: KeyPair
    )

    private fun startDevice(harness: SpfnReferenceHarness): ParkedDevice
    {
        // A fresh keypair every time: the pre-registered fixture key is already in the
        // directory, and a flow whose whole point is registering a new key cannot be
        // arranged with one the server already holds.
        val generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(ECGenParameterSpec("secp256r1"));
        val keyPair = generator.generateKeyPair();
        val spkiDer = keyPair.public.encoded;
        val fingerprint = SpfnDigest.sha256Hex(spkiDer);
        val keyId = "key-device-%08x".format(fingerprint.hashCode());

        val body = SpfnCanonicalJson.encode(
            SpfnStartDeviceAuthRequest(
                publicKey = java.util.Base64.getEncoder().encodeToString(spkiDer),
                keyId = keyId,
                fingerprint = fingerprint,
                algorithm = SpfnKeyAlgorithm.ES256,
                deviceName = DEVICE_NAME,
                platform = SpfnKeyPlatform.ANDROID
            ).canonicalValue()
        );
        val response = harness.sendUnproven(SpfnGeneratedOperations.authDeviceStart, body);
        check(response.statusCode == 200) { "start refused with ${response.statusCode}" };
        val started = SpfnStartDeviceAuthResponse.decode(response.value());
        return ParkedDevice(started.deviceCode, started.userCode, keyId, fingerprint, keyPair);
    }

    private fun approvedDevice(harness: SpfnReferenceHarness): ParkedDevice
    {
        val parked = startDevice(harness);
        val response = harness.sendProven(SpfnGeneratedOperations.authDeviceApprove, approveBody(parked.userCode));
        check(response.statusCode == 200) { "approve refused with ${response.statusCode}" };
        return parked;
    }

    private fun deniedDevice(harness: SpfnReferenceHarness): ParkedDevice
    {
        val parked = startDevice(harness);
        val response = harness.sendProven(SpfnGeneratedOperations.authDeviceDeny, denyBody(parked.userCode));
        check(response.statusCode == 204) { "deny refused with ${response.statusCode}" };
        return parked;
    }

    private fun consumedDevice(harness: SpfnReferenceHarness): ParkedDevice
    {
        val parked = approvedDevice(harness);
        val response = harness.sendUnproven(SpfnGeneratedOperations.authDevicePoll, pollBody(parked.deviceCode));
        check(response.statusCode == 200) { "the approved poll refused with ${response.statusCode}" };
        return parked;
    }

    private fun expiredDevice(harness: SpfnReferenceHarness): ParkedDevice
    {
        val parked = startDevice(harness);
        harness.clock.advance(SpfnReferenceState.DEVICE_AUTH_TTL_MILLIS + 1);
        return parked;
    }

    private fun infoBody(userCode: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnDeviceAuthInfoRequest(userCode).canonicalValue())

    private fun approveBody(userCode: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnApproveDeviceAuthRequest(userCode).canonicalValue())

    private fun denyBody(userCode: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnDenyDeviceAuthRequest(userCode).canonicalValue())

    private fun pollBody(deviceCode: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnPollDeviceAuthRequest(deviceCode).canonicalValue())

    private fun assertRefusal(response: SpfnRawResponse, code: String, httpStatus: Int)
    {
        assertEquals("expected $code", httpStatus, response.statusCode);
        assertEquals(code, response.errorCode());
    }

    private companion object
    {
        const val DEVICE_NAME = "Kitchen Tablet"

        /** Well-formed and never issued: the shape a guess would take. */
        const val UNISSUED_USER_CODE = "ZZZZ-9999"

        const val UNISSUED_DEVICE_CODE = "device-code-that-was-never-issued"

        /** The four codes the table itself answers with, and none of them is an admission. */
        val DEVICE_AUTH_CODES = setOf(
            "DeviceAuthExpiredError",
            "DeviceAuthDeniedError",
            "DeviceAuthNotFoundError",
            "DeviceAuthAlreadyHandledError"
        )
    }
}

/** The two ways this suite sends a device operation, and the handshake that checks a key. */
private fun SpfnReferenceHarness.sendUnproven(
    operation: xyz.superfunction.spfn.core.SpfnOperation,
    body: ByteArray
): SpfnRawResponse = send(
    operation,
    body,
    listOf(SpfnReferenceWire.CONTENT_TYPE to SpfnReferenceWire.REQUEST_CONTENT_TYPE)
)

private fun SpfnReferenceHarness.sendProven(
    operation: xyz.superfunction.spfn.core.SpfnOperation,
    body: ByteArray
): SpfnRawResponse = send(operation, body, proofHeaders(operation, body))

/**
 * Opens a session proved by [keyPair] under [clientId].
 *
 * The cheapest question that can only be answered "yes" by a key the server has
 * registered to that owner: an unregistered keyId and a clientId that is not the key's
 * owner are both PROOF_INVALID, so a 200 here is registration and ownership together.
 */
private fun SpfnReferenceHarness.handshake(keyId: String, clientId: String, keyPair: KeyPair): SpfnRawResponse
{
    val operation = SpfnGeneratedOperations.authClientProofHandshake;
    val nonce = nextNonce();
    val body = SpfnCanonicalJson.encode(
        SpfnHandshakeRequest(
            clientId = clientId,
            keyId = keyId,
            nonce = nonce,
            issuedAtMillis = clock.nowMillis()
        ).canonicalValue()
    );
    val headers = proofHeaders(
        operation = operation,
        body = body,
        nonce = nonce,
        clientId = clientId,
        keyId = keyId,
        proofFor = { input ->
            SpfnClientProof.proof(input) { message ->
                val signer = Signature.getInstance("SHA256withECDSA");
                signer.initSign(keyPair.private);
                signer.update(message);
                SpfnEcdsa.derToRaw(signer.sign());
            }
        }
    );
    return send(operation, body, headers);
}
