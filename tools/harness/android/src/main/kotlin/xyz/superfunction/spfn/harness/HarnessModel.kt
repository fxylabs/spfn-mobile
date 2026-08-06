package xyz.superfunction.spfn.harness

import android.content.Context
import xyz.superfunction.spfn.client.SpfnAndroidKeystoreEngine
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyLifecycleState
import xyz.superfunction.spfn.client.SpfnKeystoreCustodyKey
import xyz.superfunction.spfn.client.SpfnKeystoreKeyProvider
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnSharedPreferencesKeyMetadataStore
import xyz.superfunction.spfn.client.SpfnCall
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnListKeysRequest
import xyz.superfunction.spfn.generated.SpfnListKeysResponse
import xyz.superfunction.spfn.generated.SpfnRevokeKeyRequest
import xyz.superfunction.spfn.generated.SpfnRevokeKeyResponse

/**
 * The harness's whole behaviour.
 *
 * One lifecycle over the real SharedPreferences store, the real Android Keystore engine
 * and the real OkHttp transport, driven by ten buttons. Nothing is faked except the
 * sign-in token and the network switch, and both of those are seams the SDK already has.
 *
 * tools/harness/ios/Sources/HarnessModel.swift is the same behaviour in Swift, with the
 * same button names and the same reported strings.
 */
class HarnessModel(context: Context, private val configuration: HarnessConfiguration)
{
    /**
     * Exactly `unenrolled`, `enrolled` or `rotationPending`.
     *
     * Kotlin's enum spells these `UNENROLLED`, `ENROLLED` and `ROTATION_PENDING`, and
     * Swift's spells them the way they appear here. A flow can only assert one string, so
     * the Kotlin half reports the Swift spelling — the naming difference is an idiom, not
     * a behaviour difference, and the flows are about behaviour.
     */
    var state: String = "unread"
        private set;

    /** `idle` before anything runs, then `ok:<detail>` or `err:<name>`. */
    var outcome: String = "idle"
        private set;

    /** `unread` until probed, then the custody a freshly generated key actually landed in. */
    var custody: String = "unread"
        private set;

    var networkBlocked: Boolean = false
        private set;

    private val transport = HarnessTransport();

    /** Held rather than passed inline, because [probeCustody] generates through it too. */
    private val engine = SpfnAndroidKeystoreEngine();

    private val lifecycle = SpfnKeyLifecycle(
        transport = transport,
        store = SpfnSharedPreferencesKeyMetadataStore(context, "xyz.superfunction.spfn.harness"),
        engine = engine,
        baseUrl = configuration.baseUrl
    );

    // ---- observation -------------------------------------------------------

    fun refresh()
    {
        state = try
        {
            nameOf(lifecycle.state())
        }
        catch (error: Throwable)
        {
            "unreadable"
        };
    }

    private fun nameOf(value: SpfnKeyLifecycleState): String = when (value)
    {
        SpfnKeyLifecycleState.UNENROLLED -> "unenrolled"
        SpfnKeyLifecycleState.ENROLLED -> "enrolled"
        SpfnKeyLifecycleState.ROTATION_PENDING -> "rotationPending"
    };

    // ---- actions -----------------------------------------------------------

    suspend fun enroll() = run {
        val result = lifecycle.enroll(configuration.provider) { nonce ->
            configuration.idToken(nonce) ?: throw HarnessException.NoCannedToken()
        };
        "enrolled:${result.keyId}";
    };

    suspend fun rotate() = run { "rotated:${lifecycle.rotate().keyId}" };

    suspend fun resumeRotation() = run { "resumed:${lifecycle.resumeRotation().keyId}" };

    /**
     * Revokes the key this install is signing with, which is what makes the next proven
     * call answer SESSION_REVOKED. `revokeAll` spares the caller, so it cannot do this.
     */
    suspend fun revokeActiveKey() = run {
        val provider = activeProviderOrThrow();
        client(provider).execute(Calls.keysRevoke, SpfnRevokeKeyRequest(keyId = provider.keyId));
        "revoked:${provider.keyId}";
    };

    /**
     * A proven call whose only purpose is to meet whatever the server now thinks of this
     * key. After a revocation it is the SESSION_REVOKED the flow asserts on.
     */
    suspend fun provenCall() = run {
        val provider = activeProviderOrThrow();
        val listed = client(provider).execute(Calls.keysList, SpfnListKeysRequest());
        "listed:${listed.keys.size}";
    };

    /**
     * The SDK's own answer to a revoked session: both slots go. A flow calls this after
     * SESSION_REVOKED so the state machine returns to `unenrolled` the way an app would
     * return it.
     */
    suspend fun noteSessionRevoked() = run {
        lifecycle.noteSessionRevoked();
        "wiped";
    };

    suspend fun wipe() = run {
        lifecycle.wipe();
        "wiped";
    };

    /**
     * Which custody this device actually gives a client key.
     *
     * Generated through the same engine and the same default `SpfnKeyLifecycle` uses for
     * its own keys, read, and then destroyed: the Keystore entry does not outlive the
     * check and no request is sent. That last part is the point. Hardware custody is the
     * one thing a real phone proves that an emulator cannot, and on iOS it has to be
     * checkable by hand — Maestro ships no driver for a physical iOS device, so the
     * iPhone half of this is a person tapping the button and reading the label.
     *
     * The two platforms answer in their own vocabularies (`strongBox` here,
     * `secureEnclave` on Apple) because they name different hardware. A flow that ever
     * asserts on this asserts per platform.
     */
    suspend fun probeCustody() = run {
        val probe = SpfnKeystoreCustodyKey.generate("custody-probe", engine);
        custody = probe.custody.wireName;
        probe.destroy();
        "custody:${probe.custody.wireName}";
    };

    fun setNetworkBlocked(value: Boolean)
    {
        transport.setBlocked(value);
        networkBlocked = value;
        outcome = if (value) "ok:network-blocked" else "ok:network-open";
    }

    // ---- running one action ------------------------------------------------

    /**
     * Every button goes through here, so every button reports the same way: a short
     * stable name on failure, `ok:` and a detail on success, and the state re-read
     * afterwards whichever it was.
     */
    private suspend fun run(action: suspend () -> String)
    {
        outcome = try
        {
            "ok:${action()}"
        }
        catch (error: Throwable)
        {
            "err:${HarnessOutcome.name(error)}"
        };
        refresh();
    }

    private fun activeProviderOrThrow(): SpfnKeystoreKeyProvider =
        lifecycle.activeProvider() ?: throw HarnessException.NoActiveKey();

    private fun client(provider: SpfnKeystoreKeyProvider): SpfnClient = SpfnClient(
        transport = transport,
        session = SpfnSession(
            transport = transport,
            keyProvider = provider,
            baseUrl = configuration.baseUrl
        )
    );

    /**
     * The call descriptors the harness drives directly. `revoke` has no lifecycle method —
     * the SDK exposes enrolment and rotation, and revocation is an operation — so the
     * harness reaches it the way any app would (decision 01kzb8tjxp, D-3).
     */
    private object Calls
    {
        val keysList = SpfnCall(
            operation = SpfnGeneratedOperations.authKeysList,
            encode = { request: SpfnListKeysRequest -> request.canonicalValue() },
            decode = { canonical -> SpfnListKeysResponse.decode(canonical) }
        );

        val keysRevoke = SpfnCall(
            operation = SpfnGeneratedOperations.authKeysRevoke,
            encode = { request: SpfnRevokeKeyRequest -> request.canonicalValue() },
            decode = { canonical -> SpfnRevokeKeyResponse.decode(canonical) }
        );
    }
}
