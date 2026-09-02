// SPFN Mobile — the client key's life: enrollment, rotation, revocation, TTL.
//
// One rule shapes everything here: at every observable moment there is exactly one key
// a caller can sign with — the one in the active slot. Enrollment creates it, rotation
// replaces it, revocation wipes it, and no path exposes a second signer in between. A
// rotation candidate exists transiently in its own slot, persisted before the network
// call so a process death cannot lose track of a key the server may already know, and
// it becomes signable only by becoming the active key.
//
// The rotation state machine, spelled out because M5 tests every edge of it:
//
//   enrolled ──rotate(): persist candidate──▶ rotationPending ──success──▶ enrolled(new)
//     ▲                                            │
//     │◀──refusal in the same call: not applied────┘  (candidate destroyed, old kept)
//     │
//     │◀── resume: PROOF_INVALID means the old key is no longer registered, so the
//     │    earlier attempt WAS applied — the candidate is promoted, not discarded.
//     │    A transport failure leaves the machine where it was; SESSION_REVOKED
//     │    wipes everything, because the old key itself is dead.
//
// The asymmetry between rotate() and resumeRotation() on the same PROOF_INVALID is the
// point of having both: inside rotate() the request was sent exactly once and refused,
// so the server did not apply it; on resume the previous send's outcome is unknown, and
// a well-formed old-key proof failing verification means the old key is gone — which is
// what a completed rotation looks like from the outside.
//
// Device-code enrollment adds no state to that machine. The key it parks and the device
// code it polls with live in this call's own frame for as long as the call runs, and the
// install stays UNENROLLED until the approval is saved — so a process death, a
// cancellation or any refusal leaves nothing behind to resume, which is exactly the
// difference between it and a rotation.
//
// Sources/SPFNClient/SPFNKeyLifecycle.swift is the same machine in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnDeviceAuthPollStatus
import xyz.superfunction.spfn.generated.SpfnGeneratedCalls
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnOauthNativeRequest
import xyz.superfunction.spfn.generated.SpfnOauthNativeResponse
import xyz.superfunction.spfn.generated.SpfnPollDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnPollDeviceAuthResponse
import xyz.superfunction.spfn.generated.SpfnRotateKeyRequest
import xyz.superfunction.spfn.generated.SpfnRotateKeyResponse
import xyz.superfunction.spfn.generated.SpfnStartDeviceAuthRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64

/** The lifecycle's answer to "what key does this install hold". */
enum class SpfnKeyLifecycleState
{
    /** No usable key: enrollment is required before any proven operation. */
    UNENROLLED,

    /** One active key, ready to sign. */
    ENROLLED,

    /** A rotation was started and its outcome is unknown; call [SpfnKeyLifecycle.resumeRotation]. */
    ROTATION_PENDING
}

/** What enrollment settled: the identity the server issued for the key it registered. */
class SpfnEnrollmentResult(
    /**
     * The key owner's identity — the response's `userId`, which is what every proof's
     * `clientId` must equal from now on (the contract's `clientIdRule`).
     */
    val clientId: String,
    val keyId: String,
    val isNewUser: Boolean
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnEnrollmentResult &&
            other.clientId == clientId &&
            other.keyId == keyId &&
            other.isNewUser == isNewUser

    override fun hashCode(): Int = (31 * clientId.hashCode() + keyId.hashCode()) * 31 + isNewUser.hashCode()

    override fun toString(): String =
        "SpfnEnrollmentResult(clientId=$clientId, keyId=$keyId, isNewUser=$isNewUser)"
}

/**
 * What a device-code enrollment settled.
 *
 * Its own type rather than [SpfnEnrollmentResult]: the two flows answer with different
 * facts. A social enrollment learns whether the account was created just now; a device
 * approval learns whether the account it joined is owed a password change. Neither
 * question has an answer on the other path, and one type carrying both would be a type
 * where half the fields are always meaningless.
 *
 * `publicId`, `email` and `phone` reach the client on the approved poll and are not
 * carried here: the lifecycle owns keys, and an account's profile is the app's to read
 * through its own operations.
 */
class SpfnDeviceCodeEnrollmentResult(
    /**
     * The key owner's identity — the approved poll's `userId`, which is what every
     * proof's `clientId` must equal from now on (the contract's `clientIdRule`).
     */
    val clientId: String,
    /**
     * The key this flow parked and the approval registered. This SDK's own identifier,
     * minted before `auth.device.start` was sent.
     */
    val keyId: String,
    /** The login rule the account carries, exactly as the approved poll stated it. */
    val passwordChangeRequired: Boolean
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnDeviceCodeEnrollmentResult &&
            other.clientId == clientId &&
            other.keyId == keyId &&
            other.passwordChangeRequired == passwordChangeRequired

    override fun hashCode(): Int =
        (31 * clientId.hashCode() + keyId.hashCode()) * 31 + passwordChangeRequired.hashCode()

    override fun toString(): String =
        "SpfnDeviceCodeEnrollmentResult(clientId=$clientId, keyId=$keyId, " +
            "passwordChangeRequired=$passwordChangeRequired)"
}

/**
 * How the wait between two polls is spent.
 *
 * A seam for the same reason the clock is one: the device-code flow's only observable
 * timing rule is "wait exactly what the server asked for", and a suite that really
 * waited five seconds per poll could not assert it in a unit test.
 */
fun interface SpfnSleeper
{
    suspend fun sleep(millis: Long)
}

/**
 * The default sleeper. `delay` is what a cancelled coroutine stops at, which is what
 * makes a cancelled wait end at the wait rather than at the next request.
 */
class SpfnDelaySleeper : SpfnSleeper
{
    override suspend fun sleep(millis: Long) = delay(millis)
}

/** Everything the lifecycle refuses on its own, before or instead of the network. */
sealed class SpfnKeyLifecycleException(message: String) : IllegalStateException(message)
{
    /**
     * Enrollment was asked for while a key exists. Wipe first — implicitly enrolling
     * over a live key would orphan a registration the server still honours.
     */
    class AlreadyEnrolled : SpfnKeyLifecycleException("a key is already enrolled; wipe before enrolling again")

    /** Rotation or signing was asked for with no active key. */
    class NotEnrolled : SpfnKeyLifecycleException("no active key; enroll first")

    /** A new enrollment or rotation was asked for while a rotation is unresolved. */
    class RotationUnresolved : SpfnKeyLifecycleException("a rotation is unresolved; call resumeRotation first")

    /**
     * A second enrollment was asked for while the first one's sign-in is still running.
     *
     * The state checks cannot see this: an enrollment in progress has saved nothing yet,
     * so both calls would read UNENROLLED and both would register a key.
     */
    class EnrollmentInFlight : SpfnKeyLifecycleException("an enrollment is already running; wait for it to finish")

    /**
     * The sign-in returned an empty token. Sending it would spend a key generation on a
     * request the server can only refuse.
     */
    class IdTokenMissing : SpfnKeyLifecycleException("the sign-in produced no id_token")

    /**
     * The provider id cannot be a path segment. The id is substituted into the
     * operation path before signing, so anything but `[a-z0-9-]` would change the
     * route — or smuggle one — rather than name a provider.
     */
    class MalformedProviderId : SpfnKeyLifecycleException("a provider id is lowercase alphanumerics and hyphens")

    /**
     * The server's answer named a key other than the one this call sent. The message
     * carries this SDK's own identifiers, never server text.
     */
    class ServerNamedAnotherKey(val sent: String, val received: String) :
        SpfnKeyLifecycleException("the server confirmed key '$received' where '$sent' was registered")

    /**
     * A record exists but its key cannot be opened on this device. Re-enrollment is
     * the only way forward.
     */
    class KeyUnloadable : SpfnKeyLifecycleException("a stored key could not be opened on this device")

    /**
     * The device code reached the `expiresAtMillis` the `start` answer named before
     * anyone approved it, judged on the proof clock. The wait ends here rather than at
     * the server's own refusal: a client that polled past the expiry it was told would
     * be asking about a code it already knows is dead.
     */
    class DeviceCodeExpired : SpfnKeyLifecycleException("the device code expired before it was approved")
}

/**
 * Owns the key slots and drives enrollment and rotation over the execute path.
 *
 * A mutex serializes the mutating flows for the same reason the session holds one:
 * `rotate` reads, sends and swaps, and two of those interleaved would be two
 * candidates for one active key.
 *
 * @param newKeyId mints key identifiers; UUIDs by default. Injected so a suite can
 *   pin the wire bytes a flow produces against the fixtures.
 */
class SpfnKeyLifecycle(
    private val transport: SpfnTransport,
    private val store: SpfnKeyMetadataStore,
    private val engine: SpfnKeystoreEngine,
    private val baseUrl: String,
    private val clock: SpfnClock = SpfnSystemClock(),
    private val proofClock: SpfnProofClock = SpfnProcessServerClock.shared,
    private val nonceGenerator: SpfnNonceGenerator = SpfnRandomNonceGenerator(),
    private val sleeper: SpfnSleeper = SpfnDelaySleeper(),
    private val timeoutMillis: Long = 15_000,
    private val preferStrongBox: Boolean = true,
    private val newKeyId: () -> String = { UUID.randomUUID().toString().lowercase() }
)
{
    private val mutex = Mutex()

    /**
     * True from the moment [enroll] claims the flow to the moment it leaves, however it
     * leaves.
     *
     * The mutex cannot serve this. `enroll` now awaits the app's sign-in — which lasts as
     * long as a person takes — and holding the mutex across it would make every other
     * call on this object wait behind a UI, and would deadlock outright if the sign-in
     * closure called back into the lifecycle, because a Kotlin Mutex is not reentrant.
     * So the mutex guards only the short critical sections and this flag guards the flow.
     */
    private val enrollmentInFlight = AtomicBoolean(false)

    // ---- observation -------------------------------------------------------

    fun state(): SpfnKeyLifecycleState
    {
        if (store.load(CANDIDATE_SLOT) != null)
        {
            return SpfnKeyLifecycleState.ROTATION_PENDING;
        }
        val active = store.load(ACTIVE_SLOT);
        return if (active?.clientId != null)
        {
            SpfnKeyLifecycleState.ENROLLED
        }
        else
        {
            SpfnKeyLifecycleState.UNENROLLED
        };
    }

    /**
     * The one signer this install holds, or null before enrollment. A rotation
     * candidate is never returned here — it becomes signable by becoming active.
     */
    fun activeProvider(): SpfnKeystoreKeyProvider? = SpfnKeystoreKeyProvider.load(store, ACTIVE_SLOT, engine)

    // ---- M7: the TTL judgment ----------------------------------------------

    /**
     * Milliseconds until the active key reaches `keyPolicy.ttlDays`, negative once it
     * has, or null with no active key. Foreground arithmetic only: nothing here
     * schedules anything, because background execution is outside this SDK's scope.
     */
    fun keyRemainingMillis(): Long?
    {
        val active = store.load(ACTIVE_SLOT) ?: return null;
        if (active.clientId == null)
        {
            return null;
        }
        val ttlMillis = SpfnGeneratedContract.KEY_POLICY_TTL_DAYS * 24 * 60 * 60 * 1_000;
        return active.createdAtMillis + ttlMillis - clock.nowMillis();
    }

    /**
     * True when the active key is inside [leadTimeMillis] of its TTL — the moment a
     * foregrounded app should start a rotation.
     */
    fun rotationDue(leadTimeMillis: Long = 0): Boolean
    {
        val remaining = keyRemainingMillis() ?: return false;
        return remaining <= leadTimeMillis;
    }

    // ---- M1–M3: enrollment -------------------------------------------------

    /**
     * Generates a key, signs in with the provider, and enrolls the key — one call.
     *
     * The three steps are one call because the nonce is the key's fingerprint (the
     * contract's `nativeEnrollment.nonceRule`). The key therefore has to exist before the
     * provider is asked for a token, and a sign-in the user abandons would strand a
     * Keystore entry nobody registered. Owning the whole flow is what lets this delete it.
     *
     * [idToken] is handed the nonce and returns the provider's token. Everything the
     * closure needs to reach a provider is on the nonce: `requestValue` is already the
     * shape that provider expects, so a caller driving kakao or naver directly puts that
     * value in the request and nothing else.
     *
     * The request body is exact (M1): the public key as SPKI DER base64, the minted
     * keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16, the
     * nonce equal to that fingerprint, and the literal algorithm name. On success the
     * response's `userId` is persisted as the clientId every future proof carries (M2).
     * On any failure the generated key is destroyed (M3).
     */
    suspend fun enroll(provider: String, idToken: suspend (SpfnSocialNonce) -> String): SpfnEnrollmentResult
    {
        if (!isProviderId(provider))
        {
            throw SpfnKeyLifecycleException.MalformedProviderId();
        }

        // The state read and the claim are one critical section, so two callers cannot
        // both read UNENROLLED and both proceed. The claim itself is atomic rather than
        // mutex-held, so releasing it below never has to suspend.
        mutex.withLock {
            when (state())
            {
                SpfnKeyLifecycleState.ENROLLED -> throw SpfnKeyLifecycleException.AlreadyEnrolled()
                SpfnKeyLifecycleState.ROTATION_PENDING -> throw SpfnKeyLifecycleException.RotationUnresolved()
                SpfnKeyLifecycleState.UNENROLLED -> Unit
            }
            if (!enrollmentInFlight.compareAndSet(false, true))
            {
                throw SpfnKeyLifecycleException.EnrollmentInFlight();
            }
        }

        try
        {
            val key = SpfnKeystoreCustodyKey.generate(newKeyId(), engine, preferStrongBox);
            // One local for both fields the contract binds to each other, so they cannot
            // drift apart in the body below.
            val fingerprint = SpfnDigest.sha256Hex(key.publicKeySpkiDer);
            try
            {
                val token = idToken(SpfnSocialNonce(fingerprint = fingerprint, provider = provider));
                if (token.isEmpty())
                {
                    throw SpfnKeyLifecycleException.IdTokenMissing();
                }

                val response = client(signer = null).execute(
                    oauthNativeCall(provider),
                    SpfnOauthNativeRequest(
                        idToken = token,
                        nonce = fingerprint,
                        publicKey = Base64.encode(key.publicKeySpkiDer),
                        keyId = key.keyId,
                        fingerprint = fingerprint,
                        algorithm = ALGORITHM_NAME
                    )
                );
                if (response.keyId != key.keyId)
                {
                    throw SpfnKeyLifecycleException.ServerNamedAnotherKey(sent = key.keyId, received = response.keyId);
                }
                // Persisting is inside the same guard as the request, because a save that
                // throws leaves an enrollment the server accepted with no local metadata
                // naming it. The key would then outlive the throw as an orphan alias and
                // the retry would mint a second one.
                mutex.withLock {
                    store.save(ACTIVE_SLOT, key.metadata(clientId = response.userId, createdAtMillis = clock.nowMillis()));
                }
                return SpfnEnrollmentResult(
                    clientId = response.userId,
                    keyId = key.keyId,
                    isNewUser = response.isNewUser
                );
            }
            catch (failure: Throwable)
            {
                // The Keystore entry already exists, so a failed enrollment must delete
                // it here — the Swift counterpart only has a value to drop at this point.
                // A cancelled sign-in reaches this too, which is the case the whole
                // closure shape exists for.
                key.destroy();
                throw failure;
            }
        }
        finally
        {
            // Non-suspending on purpose: a cancelled coroutine cannot run a suspending
            // release, and a claim that outlived its call would lock the install out of
            // ever enrolling again.
            enrollmentInFlight.set(false);
        }
    }

    // ---- M8: enrollment by device code --------------------------------------

    /**
     * Enrolls this device by showing a code somebody approves on a device already signed
     * in — the contract's `deviceAuthorization` flow, from the waiting side.
     *
     * One call, for the same reason [enroll] is one: the key has to exist before
     * `auth.device.start` can park it, the code the user reads names that parked key, and
     * an approval nobody comes back to collect would strand a Keystore entry nobody
     * registered. Owning the whole wait is what lets this delete it.
     *
     * [showCode] is called exactly once, immediately after `start` answers, with the code
     * as the server spelled it (`XXXX-XXXX` — the server folds case, spaces and dashes on
     * the way back in, so nothing here reformats it) and the instant it expires. It is
     * called on whatever dispatcher the calling coroutine is on, which is usually not the
     * main thread; this SDK switches to no dispatcher of its own, so an app that draws
     * from it posts there itself (the harness does).
     *
     * The rules, in the order they are enforced (M8):
     *
     *   1. The state checks and the in-flight claim are [enroll]'s, and the claim is the
     *      same flag: a device-code enrollment and a social one cannot both be running,
     *      because both would register a key while the store still reads UNENROLLED.
     *   2. The `start` body is exact: the public key as SPKI DER base64, the minted
     *      keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16, the
     *      literal algorithm name, this build's client kind as the platform, and the
     *      caller's [deviceName] when it gave one. Nothing is read off the OS.
     *   3. The wait obeys the server: `intervalMillis` from `start`, then from each
     *      `pending`. There is no client-side default and no backoff. A `pending` answer
     *      is not a failure; every refusal the contract marks retryable — one today,
     *      `TooManyRequestsError` — and every lost response are asked again after that
     *      same interval, and everything else ends the wait.
     *   4. The deadline is `start`'s `expiresAtMillis` judged on the proof clock, the one
     *      `core.time` synchronised. The device's own wall clock never enters it, and a
     *      lost `core.time` fetch is a lost poll: it costs the same interval and is asked
     *      again, so the deadline is judged when the clock answers.
     *   5. Every exit that is not an approval deletes the Keystore entry, cancellation
     *      included. No fourth lifecycle state exists: until the approval is saved this
     *      install is UNENROLLED, and a process death leaves it that way.
     */
    suspend fun enrollByDeviceCode(
        deviceName: String? = null,
        showCode: (userCode: String, expiresAtMillis: Long) -> Unit
    ): SpfnDeviceCodeEnrollmentResult
    {
        // The state read and the claim are one critical section, so two callers cannot
        // both read UNENROLLED and both proceed — whichever entry point each called.
        mutex.withLock {
            when (state())
            {
                SpfnKeyLifecycleState.ENROLLED -> throw SpfnKeyLifecycleException.AlreadyEnrolled()
                SpfnKeyLifecycleState.ROTATION_PENDING -> throw SpfnKeyLifecycleException.RotationUnresolved()
                SpfnKeyLifecycleState.UNENROLLED -> Unit
            }
            if (!enrollmentInFlight.compareAndSet(false, true))
            {
                throw SpfnKeyLifecycleException.EnrollmentInFlight();
            }
        }

        try
        {
            val key = SpfnKeystoreCustodyKey.generate(newKeyId(), engine, preferStrongBox);
            val fingerprint = SpfnDigest.sha256Hex(key.publicKeySpkiDer);
            try
            {
                val started = client(signer = null).execute(
                    SpfnGeneratedCalls.authDeviceStart,
                    SpfnStartDeviceAuthRequest(
                        publicKey = Base64.encode(key.publicKeySpkiDer),
                        keyId = key.keyId,
                        fingerprint = fingerprint,
                        algorithm = ALGORITHM_NAME,
                        deviceName = deviceName,
                        platform = PLATFORM
                    )
                );
                showCode(started.userCode, started.expiresAtMillis);

                val approved = awaitApproval(
                    deviceCode = started.deviceCode,
                    expiresAtMillis = started.expiresAtMillis,
                    intervalMillis = waitMillis(started.intervalMillis)
                );

                // Saved exactly as `enroll` saves it, so a key this flow enrolled is a
                // key `rotate` can replace and `activeProvider` can sign with. Inside the
                // same guard as the request, for the reason `enroll` states: a save that
                // throws would otherwise leave a registration the server honours with no
                // local metadata naming it, and the alias orphaned.
                mutex.withLock {
                    store.save(
                        ACTIVE_SLOT,
                        key.metadata(clientId = approved.clientId, createdAtMillis = clock.nowMillis())
                    );
                }
                return SpfnDeviceCodeEnrollmentResult(
                    clientId = approved.clientId,
                    keyId = key.keyId,
                    passwordChangeRequired = approved.passwordChangeRequired
                );
            }
            catch (failure: Throwable)
            {
                // Every non-approved exit, cancellation included. `destroy` does not
                // suspend, so a coroutine that was cancelled mid-wait still runs it —
                // which is what keeps a cancelled sign-in from leaving an orphan alias.
                key.destroy();
                throw failure;
            }
        }
        finally
        {
            // Non-suspending on purpose: a cancelled coroutine cannot run a suspending
            // release, and a claim that outlived its call would lock the install out of
            // ever enrolling again.
            enrollmentInFlight.set(false);
        }
    }

    /** What an approved poll settled, before the key it belongs to is saved. */
    private class DeviceApproval(val clientId: String, val passwordChangeRequired: Boolean)

    /**
     * The wait: sleep the interval, judge the deadline, poll, read the answer.
     *
     * The deadline is checked between the sleep and the request rather than after it, so
     * a code that expired while this device was waiting costs no request at all.
     *
     * Two things can be lost inside one iteration and both cost the same interval: the
     * clock read and the poll. On a fresh install the first iteration's clock read is a
     * real `core.time` request, and a network that dropped it says exactly as much about
     * the code as a network that dropped the poll one line below — nothing.
     */
    private suspend fun awaitApproval(
        deviceCode: String,
        expiresAtMillis: Long,
        intervalMillis: Long
    ): DeviceApproval
    {
        var waitMillis = intervalMillis;
        while (true)
        {
            sleeper.sleep(waitMillis);

            val now = clockNow() ?: continue;
            if (now >= expiresAtMillis)
            {
                throw SpfnKeyLifecycleException.DeviceCodeExpired();
            }

            val answer = pollOnce(deviceCode) ?: continue;

            // The branch is read from `status` and never from which fields arrived: the
            // contract's `pollStatusRule` states that every field but the discriminant is
            // optional because it belongs to one branch, so guessing from presence would
            // be reading a shape nothing declared.
            when (answer.status)
            {
                SpfnDeviceAuthPollStatus.PENDING -> waitMillis = waitMillis(answer.intervalMillis)
                SpfnDeviceAuthPollStatus.APPROVED ->
                {
                    val clientId = answer.userId;
                    val passwordChangeRequired = answer.passwordChangeRequired;
                    if (clientId == null || passwordChangeRequired == null)
                    {
                        throw SpfnClientError.Decoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE);
                    }
                    return DeviceApproval(clientId, passwordChangeRequired);
                }
            }
        }
    }

    /**
     * The proof clock, or null for the one failure that means "ask again after the
     * interval".
     *
     * A lost `core.time` fetch is not an answer about the device code, so it does not end
     * the wait and delete the key: it waits and reads again, and the deadline is judged
     * when the clock finally answers. Only the transport failure is retried. A refusal to
     * synchronize at all — an untrusted base URL, a contract with no usable clock
     * operation — is the same on every retry and ends the wait, and cancellation is the
     * caller withdrawing and is rethrown as itself.
     */
    private suspend fun clockNow(): Long?
    {
        try
        {
            return proofClock.nowMillis(transport, baseUrl, timeoutMillis);
        }
        catch (_: SpfnClockSynchronizationException.RequestFailed)
        {
            return null;
        }
    }

    /**
     * One poll, or null for the two answers that mean "ask again after the interval".
     *
     * A lost response is one of them: the poll applies nothing, so re-sending it cannot
     * apply anything twice — which is why this operation may be retried where the execute
     * path retries nothing. A cancelled call is not a lost one and is rethrown as itself,
     * because the caller withdrawing is not a network failure.
     */
    private suspend fun pollOnce(deviceCode: String): SpfnPollDeviceAuthResponse?
    {
        try
        {
            return client(signer = null).execute(SpfnGeneratedCalls.authDevicePoll, SpfnPollDeviceAuthRequest(deviceCode));
        }
        catch (failure: SpfnClientError.Transport)
        {
            if (failure.error is SpfnTransportError.Cancelled)
            {
                throw failure;
            }
            return null;
        }
        catch (failure: SpfnClientError.Server)
        {
            // `TooManyRequestsError` today, and whatever the contract marks retryable
            // tomorrow: the code is still live, this device only asked too fast.
            if (!failure.failure.code.isRetryable)
            {
                throw failure;
            }
            return null;
        }
    }

    /**
     * The interval the server asked this device to wait, or a decoding refusal.
     *
     * Absent, zero and negative are one answer: one this client cannot obey. The contract
     * declares an integer and the server's own configuration refuses anything but a
     * positive whole number of milliseconds, so a value outside that is a server this
     * client does not understand — and waiting zero would spin the poll straight into the
     * rate limit the interval exists to stay under.
     */
    private fun waitMillis(intervalMillis: Long?): Long
    {
        if (intervalMillis == null || intervalMillis <= 0)
        {
            throw SpfnClientError.Decoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE);
        }
        return intervalMillis;
    }

    // ---- M4–M5: rotation ---------------------------------------------------

    /**
     * Replaces the active key: a fresh key is generated, persisted as the candidate,
     * and registered through `auth.keys.rotate` under the old key's proof. Success
     * swaps the candidate in; a refusal destroys the candidate and keeps the old key,
     * because a refused request was never applied. Only a transport failure leaves
     * the machine in ROTATION_PENDING — the one case where the server's state is
     * genuinely unknown — and [resumeRotation] resolves it.
     */
    suspend fun rotate(): SpfnEnrollmentResult = mutex.withLock {
        when (state())
        {
            SpfnKeyLifecycleState.UNENROLLED -> throw SpfnKeyLifecycleException.NotEnrolled()
            SpfnKeyLifecycleState.ROTATION_PENDING -> throw SpfnKeyLifecycleException.RotationUnresolved()
            SpfnKeyLifecycleState.ENROLLED -> Unit
        }
        val old = activeProvider() ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        val candidate = SpfnKeystoreCustodyKey.generate(newKeyId(), engine, preferStrongBox);
        store.save(CANDIDATE_SLOT, candidate.metadata(clientId = old.clientId, createdAtMillis = clock.nowMillis()));

        try
        {
            val response = send(candidate, old);
            return@withLock promote(candidate, old.clientId, response.keyId);
        }
        catch (failure: SpfnClientError)
        {
            when
            {
                failure is SpfnClientError.Transport ->
                    // No response: the server may or may not have applied it. The
                    // candidate stays persisted and the state answers ROTATION_PENDING.
                    throw failure

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.SESSION_REVOKED ->
                {
                    // The old key itself is dead; nothing here can sign anymore (M6).
                    wipeLocked();
                    throw failure;
                }

                else ->
                {
                    // A refusal in the same call that sent the one request: not applied.
                    discardCandidate(candidate);
                    throw failure;
                }
            }
        }
        catch (failure: SpfnAuthException)
        {
            // Proof assembly failed before anything was sent, so the server cannot
            // have applied a request that never existed: the candidate is discarded.
            discardCandidate(candidate);
            throw failure;
        }
    }

    /**
     * Resolves a rotation whose outcome was lost to a transport failure.
     *
     * Re-sends the same candidate under the old key's proof. Success completes the
     * rotation. `PROOF_INVALID` also completes it: this SDK signed a well-formed
     * proof, so the only reading is that the old key is no longer registered — which
     * is what the earlier attempt having been applied looks like. `SESSION_REVOKED`
     * wipes. Any other refusal discards the candidate and keeps the old key.
     */
    suspend fun resumeRotation(): SpfnEnrollmentResult = mutex.withLock {
        val record = store.load(CANDIDATE_SLOT) ?: throw SpfnKeyLifecycleException.NotEnrolled();
        val clientId = record.clientId ?: throw SpfnKeyLifecycleException.KeyUnloadable();
        val candidate = SpfnKeystoreCustodyKey.reload(record, engine)
            ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        // A death between the swap and the candidate cleanup leaves both slots naming
        // one key; the resume is then only the cleanup.
        val active = store.load(ACTIVE_SLOT);
        if (active?.keyId == record.keyId)
        {
            store.delete(CANDIDATE_SLOT);
            return@withLock SpfnEnrollmentResult(clientId = clientId, keyId = record.keyId, isNewUser = false);
        }

        val old = activeProvider() ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        try
        {
            val response = send(candidate, old);
            return@withLock promote(candidate, clientId, response.keyId);
        }
        catch (failure: SpfnClientError)
        {
            when
            {
                failure is SpfnClientError.Transport -> throw failure

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.PROOF_INVALID ->
                    return@withLock promote(candidate, clientId, candidate.keyId)

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.SESSION_REVOKED ->
                {
                    wipeLocked();
                    throw failure;
                }

                else ->
                {
                    discardCandidate(candidate);
                    throw failure;
                }
            }
        }
    }

    // ---- M6: revocation ----------------------------------------------------

    /**
     * The reaction to `SESSION_REVOKED`: every slot is cleared, and the state answers
     * UNENROLLED — the "re-enrollment required" signal a caller reads.
     */
    suspend fun noteSessionRevoked() = mutex.withLock { wipeLocked() }

    /** Deletes both slots and their Keystore entries. Nothing can sign until a new enrollment. */
    suspend fun wipe() = mutex.withLock { wipeLocked() }

    private fun wipeLocked()
    {
        for (slot in listOf(ACTIVE_SLOT, CANDIDATE_SLOT))
        {
            val record = store.load(slot);
            if (record != null)
            {
                engine.delete(record.alias);
            }
            store.delete(slot);
        }
    }

    // ---- assembly ----------------------------------------------------------

    private fun discardCandidate(candidate: SpfnKeystoreCustodyKey)
    {
        candidate.destroy();
        store.delete(CANDIDATE_SLOT);
    }

    private suspend fun send(candidate: SpfnKeystoreCustodyKey, old: SpfnKeystoreKeyProvider): SpfnRotateKeyResponse =
        client(signer = old).execute(
            SpfnGeneratedCalls.authKeysRotate,
            SpfnRotateKeyRequest(
                publicKey = Base64.encode(candidate.publicKeySpkiDer),
                keyId = candidate.keyId,
                fingerprint = SpfnDigest.sha256Hex(candidate.publicKeySpkiDer),
                algorithm = ALGORITHM_NAME
            )
        )

    /**
     * Swaps the candidate into the active slot, in the order a death cannot corrupt:
     * active first, candidate cleanup second — the resume path reads that overlap.
     * The replaced key's Keystore entry is deleted last; the server no longer honours
     * it, so keeping it would be a second signer in name only.
     */
    private fun promote(
        candidate: SpfnKeystoreCustodyKey,
        clientId: String,
        confirmedKeyId: String
    ): SpfnEnrollmentResult
    {
        if (confirmedKeyId != candidate.keyId)
        {
            throw SpfnKeyLifecycleException.ServerNamedAnotherKey(sent = candidate.keyId, received = confirmedKeyId);
        }
        val replaced = store.load(ACTIVE_SLOT);
        store.save(ACTIVE_SLOT, candidate.metadata(clientId = clientId, createdAtMillis = clock.nowMillis()));
        store.delete(CANDIDATE_SLOT);
        if (replaced != null && replaced.alias != candidate.alias)
        {
            engine.delete(replaced.alias);
        }
        return SpfnEnrollmentResult(clientId = clientId, keyId = candidate.keyId, isNewUser = false);
    }

    /**
     * One client per call, over one session. For the unproven enrollment the signer
     * is never consulted — the unproven path touches no session state — so the
     * enrollment client carries a placeholder that throws if anything ever asks it
     * to sign, which nothing on that path can.
     */
    private fun client(signer: SpfnKeystoreKeyProvider?): SpfnClient
    {
        val provider: SpfnKeyProvider = signer ?: UnenrolledKeyProvider;
        return SpfnClient(
            transport = transport,
            session = SpfnSession(
                transport = transport,
                keyProvider = provider,
                baseUrl = baseUrl,
                clock = proofClock,
                nonceGenerator = nonceGenerator,
                timeoutMillis = timeoutMillis
            ),
            timeoutMillis = timeoutMillis
        );
    }

    private object UnenrolledKeyProvider : SpfnKeyProvider
    {
        override val clientId: String = ""

        override val keyId: String = ""

        override fun sign(message: ByteArray): ByteArray =
            throw IllegalStateException("the unproven path never signs; nothing may ask this provider to");
    }

    /**
     * The one descriptor this file still builds by hand.
     *
     * Every other operation it sends is a value in [SpfnGeneratedCalls]. This one cannot
     * be: the contract's path carries a `{provider}` segment, and the descriptor a request
     * actually rides on has to name the route it goes to. So the generated operation is
     * the template and the substitution happens here, on a provider id
     * [isProviderId] has already judged.
     */
    private fun oauthNativeCall(provider: String): SpfnCall<SpfnOauthNativeRequest, SpfnOauthNativeResponse>
    {
        val template = SpfnGeneratedOperations.authEnrollOauthNative;
        return SpfnCall(
            operation = SpfnOperation(
                id = template.id,
                method = template.method,
                path = template.path.replace("{provider}", provider),
                authProfile = template.authProfile,
                requiresSession = template.requiresSession,
                declaresResponse = template.declaresResponse
            ),
            encode = { it.canonicalValue() },
            decode = { SpfnOauthNativeResponse.decode(it) }
        );
    }

    companion object
    {
        /** The slot names this lifecycle owns inside the injected store. */
        const val ACTIVE_SLOT: String = "active"
        const val CANDIDATE_SLOT: String = "rotation-candidate"

        /**
         * The signature algorithm every key this lifecycle generates is signed with.
         *
         * A generated enum since contract 0.6.0 rather than the string it used to be.
         * The contract declares the set, so a value outside it is now a compile error
         * here instead of a refusal the server has to raise.
         */
        private val ALGORITHM_NAME: SpfnKeyAlgorithm = SpfnKeyAlgorithm.ES256

        /**
         * The platform a parked key is registered under, and it is the identity header's
         * own value rather than a second constant: `x-spfn-client-kind` is what the
         * server already judges this build by, and two spellings of one fact are two
         * facts as soon as somebody edits one. Null would mean this build reports a kind
         * the contract's `KeyPlatform` set does not name, which is a mismatch the field
         * cannot state.
         */
        private val PLATFORM: SpfnKeyPlatform? =
            SpfnKeyPlatform.entries.firstOrNull { it.wireValue == SpfnClientIdentity.KIND }

        /**
         * The set the validator's own path exemption names: lowercase alphanumerics
         * and hyphens, non-empty. Everything else would rewrite the route it rides in.
         * ASCII-explicit ranges, not character classes, so the two platforms cannot
         * disagree over non-ASCII digits (the P9 lesson).
         */
        fun isProviderId(provider: String): Boolean =
            provider.isNotEmpty() && provider.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}
