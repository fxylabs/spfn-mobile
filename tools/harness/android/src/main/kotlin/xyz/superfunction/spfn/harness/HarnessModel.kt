package xyz.superfunction.spfn.harness

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import xyz.superfunction.spfn.client.SpfnAndroidKeystoreEngine
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyLifecycleState
import xyz.superfunction.spfn.client.SpfnKeystoreCustodyKey
import xyz.superfunction.spfn.client.SpfnKeystoreKeyProvider
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnSharedPreferencesKeyMetadataStore
import xyz.superfunction.spfn.generated.SpfnGeneratedCalls
import xyz.superfunction.spfn.generated.SpfnListKeysRequest
import xyz.superfunction.spfn.generated.SpfnRevokeKeyRequest
import xyz.superfunction.spfn.harness.generated.AppContainer

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

    /**
     * Whether the transport is currently refusing to send, for the permanent `network=`
     * readout.
     *
     * Read from the transport rather than mirrored in a field of its own. An attempt for
     * the `network-failure` case shuts the transport directly and restores it directly, so
     * a mirror here would be a second version of the same fact with nothing keeping the two
     * equal. The first device run burned three attempts on a switch nobody could see; a
     * readout that could disagree with the transport would be worse than none.
     */
    val networkBlocked: Boolean
        get() = transport.isBlocked;

    /**
     * Which case the next device sign-in will be recorded as.
     *
     * The app cannot infer it. A first enrolment and a re-login are the same code path, and
     * a dismissal looks like a sign-in that never started, so the person declares the
     * intent before tapping and the receipt records what happened under it.
     */
    var socialCase: HarnessSocialCase = HarnessSocialCase.FIRST_ENROLL
        private set;

    /** `none` until an attempt writes one, then the receipt file the run left behind. */
    var receipt: String = "none"
        private set;

    /**
     * The code this device is showing while it waits to be approved, or `none`.
     *
     * Written by the SDK's `showCode` callback and by nothing else. It is cleared when the
     * wait ends however it ends: a code still on screen after the wait is over is a code
     * somebody would type into the other phone for nothing.
     */
    var deviceCode: String = "none"
        private set;

    /** When that code stops being usable, so the screen can count down to it, or null. */
    var deviceCodeExpiresAtMillis: Long? = null
        private set;

    /**
     * The status of the last response the transport received, or `none`.
     *
     * Written from the transport's own callback rather than read on demand, so a response
     * that no tap produced still reaches the screen. It is a record of the WIRE and not
     * of an action: the generated approval screens send through this same transport and
     * report their own refusals on their own `state=` readout, never here.
     */
    var httpStatus: String = "none"
        private set;

    /** Set by the screen: what to run on the main thread once a readout has changed. */
    var onReadoutChanged: () -> Unit = {};

    private val transport = HarnessTransport();

    /** Where anything that ends up on screen has to be written from. */
    private val mainThread = Handler(Looper.getMainLooper());

    /**
     * The application context, not the Activity: a receipt store outlives no screen, and
     * holding the Activity here would keep a destroyed one alive.
     */
    private val receipts = HarnessReceiptStore(context.applicationContext);

    /** Held rather than passed inline, because [probeCustody] generates through it too. */
    private val engine = SpfnAndroidKeystoreEngine();

    private val lifecycle = SpfnKeyLifecycle(
        transport = transport,
        store = SpfnSharedPreferencesKeyMetadataStore(context, "xyz.superfunction.spfn.harness"),
        engine = engine,
        baseUrl = configuration.baseUrl
    );

    init
    {
        // The transport answers on whichever thread the request ran on, and everything
        // that reaches the screen is written on the main one — the same post the device
        // code's own callback makes, for the same reason.
        transport.onResponse = { status ->
            mainThread.post {
                httpStatus = status.toString();
                onReadoutChanged();
            };
        };
    }

    /**
     * Whether a key exists to prove the approval calls with.
     *
     * Both states with an active key count, `rotationPending` included: the old key is
     * still the active one until the rotation is resumed, and that is exactly the key
     * `activeProvider()` answers with.
     */
    val hasActiveKey: Boolean
        get() = state == "enrolled" || state == "rotationPending";

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
     *
     * Sent through the generated descriptor and `execute`: revocation has no lifecycle
     * method — the SDK exposes enrolment and rotation, and revocation is an operation —
     * so the harness reaches it the way any app would (decision 01kzb8tjxp, D-3).
     */
    suspend fun revokeActiveKey() = run {
        val provider = activeProviderOrThrow();
        client(provider).execute(SpfnGeneratedCalls.authKeysRevoke, SpfnRevokeKeyRequest(keyId = provider.keyId));
        "revoked:${provider.keyId}";
    };

    /**
     * A proven call whose only purpose is to meet whatever the server now thinks of this
     * key. After a revocation it is the SESSION_REVOKED the flow asserts on.
     */
    suspend fun provenCall() = run {
        val provider = activeProviderOrThrow();
        val listed = client(provider).execute(SpfnGeneratedCalls.authKeysList, SpfnListKeysRequest());
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

    // ---- the device-code flow ----------------------------------------------

    /**
     * Signs this device in with a code somebody approves elsewhere.
     *
     * The callback is called once, as soon as the server answers, and it arrives on
     * whatever dispatcher this action is running on — the IO one, here, because that is
     * where [perform] puts every action. The SDK switches to no dispatcher of its own, so
     * an app that draws from the callback gets itself to the main thread; this one posts.
     */
    suspend fun signInWithACode() = run {
        try
        {
            val settled = lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
            { userCode, expiresAtMillis ->
                mainThread.post {
                    // Exactly as the server spelled it. The server folds case, spaces and
                    // dashes on the way back in, so nothing here reformats what a person
                    // is about to read out.
                    deviceCode = userCode;
                    deviceCodeExpiresAtMillis = expiresAtMillis;
                    onReadoutChanged();
                };
            };
            "signed-in:${settled.keyId}";
        }
        finally
        {
            // However the wait ended, the code on screen is spent.
            mainThread.post {
                deviceCode = "none";
                deviceCodeExpiresAtMillis = null;
                onReadoutChanged();
            };
        }
    };

    /**
     * Builds the generated approval graph and reports it the way every other control
     * reports its result.
     *
     * The container's flow is constructed open on its start screen, so building one IS
     * opening it — what the screen does with the answer is draw the flow host. Not a
     * `suspend` function and not routed through [run]: it sends nothing, and a
     * `busy=busy` for a read of the local key store would teach a flow to wait for a
     * request nobody made.
     */
    fun openApprove(): AppContainer?
    {
        val container = approvalContainer();
        outcome = if (container == null) "err:noActiveKey" else "ok:approve-open";
        return container;
    }

    /**
     * The generated graph over the harness's own wire, or null when there is no key.
     *
     * The approver's three operations used to be three methods here, each one assembling
     * a request and calling a generated descriptor by hand. They are the generated
     * screens' work now, and this is the whole of what an app supplies: the transport
     * this harness already sends through — network switch included, so a blocked
     * transport blocks those screens too — the key the lifecycle is currently signing
     * with, and the base URL this build was launched with. The SAME base URL the rest of
     * the model uses, so the flow can never reach an address the screen does not name,
     * and the cleartext exception this build was given still covers it.
     */
    fun approvalContainer(): AppContainer?
    {
        val provider = lifecycle.activeProvider() ?: return null;
        return AppContainer.live(
            transport = transport,
            keyProvider = provider,
            baseUrl = configuration.baseUrl
        );
    }

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
        outcome = if (value) "ok:network-blocked" else "ok:network-open";
    }

    // ---- the device sign-in mode -------------------------------------------

    fun selectSocialCase(value: HarnessSocialCase)
    {
        socialCase = value;
    }

    /**
     * The real Google sheet, the real server, and a receipt either way.
     *
     * Not routed through [run]: this action's outcome vocabulary is the receipt's —
     * `enrolled`, `cancelled` or `failed` — and a cancelled sign-in reported as `err:` on
     * the screen would contradict the receipt sitting next to it (P16).
     *
     * One tap is the whole attempt. [wipeBeforeAttempt] used to be the operator's job, and
     * the first device run produced three `alreadyEnrolled` receipts from forgetting it.
     */
    suspend fun signInWithGoogle(activity: Activity)
    {
        if (!wipeBeforeAttempt())
        {
            return;
        }

        val case = socialCase;
        try
        {
            val attempt = HarnessSocialAttempt(lifecycle, transport, configuration.baseUrl).run(activity, case);
            outcome = "${attempt.outcome}:${case.wireName}";
            receipt = fileNameOf(attempt);
        }
        catch (cancellation: CancellationException)
        {
            // Rethrown before the net below, for the reason [run] gives (P16).
            throw cancellation;
        }
        catch (error: Throwable)
        {
            // Reached only when the attempt could not RUN — an unconfigured build, or a
            // Keystore that could not be read. A sign-in that merely failed is not here;
            // it is an outcome with a receipt of its own.
            //
            // [receipt] is reset for the reason [wipeBeforeAttempt] resets it: this tap
            // produced no file, and leaving the previous attempt's name standing lets an
            // older file be read as this one's evidence (P7). It matters more now that the
            // name is also announced to the operator when the tap ends.
            outcome = "err:${HarnessOutcome.name(error)}";
            receipt = "none";
        }
        refresh();
    }

    /**
     * Clears whatever a previous attempt left, and answers whether the attempt may go on.
     *
     * It runs BEFORE the attempt, which is what shuts the transport for the
     * `network-failure` case: a wipe is local work, and a blocked transport has no business
     * failing it. Reversing the two would turn one case into a wipe failure.
     *
     * A wipe that fails abandons the attempt rather than pushing on. Enrolling on top of a
     * state nobody could clear is exactly the reading the auto-wipe exists to stop
     * producing, and a receipt written from it would be evidence about the harness rather
     * than about the SDK. No receipt is written and [receipt] is reset rather than left
     * naming the previous attempt's file, so an older file cannot be read as this tap's.
     * The reason sits beside it on `outcome=`, which is what keeps "no attempt was made"
     * apart from "the attempt left no evidence" (docs/IMPLEMENTATION-PITFALLS.md P7).
     */
    private suspend fun wipeBeforeAttempt(): Boolean
    {
        try
        {
            lifecycle.wipe();
        }
        catch (cancellation: CancellationException)
        {
            // Rethrown untouched, for the reason [run] gives: the coroutine was cancelled,
            // and a scope told otherwise believes it never was (P16). The Swift half has no
            // caller to rethrow to at this point and reports the word instead — the same
            // rule, with the strength each language actually has (P15).
            throw cancellation;
        }
        catch (error: Throwable)
        {
            outcome = "err:wipe:${HarnessOutcome.name(error)}";
            receipt = "none";
            refresh();
            return false;
        }
        refresh();
        return true;
    }

    /**
     * Writes the receipt and answers its name, or answers why it could not be written.
     *
     * The attempt's own outcome is already on the label by the time this runs, so a
     * failure here loses nothing but the file. It is reported rather than swallowed: a
     * receipt that is absent and a receipt that could not be written are different events,
     * and a run that showed neither would be indistinguishable from a run nobody made
     * (docs/IMPLEMENTATION-PITFALLS.md P7).
     */
    private fun fileNameOf(attempt: HarnessReceipt): String = try
    {
        receipts.write(attempt);
    }
    catch (unavailable: HarnessException)
    {
        "unwritten:${HarnessOutcome.name(unavailable)}";
    }
    catch (failure: IOException)
    {
        "unwritten:ioError";
    };

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
        catch (cancellation: CancellationException)
        {
            // Rethrown before the net below can take it. A cancelled coroutine is the
            // screen going away, not a refusal the SDK produced, and naming it one would
            // put a failure on a label nobody caused (P16).
            throw cancellation;
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

    private companion object
    {
        /**
         * The label this device gives itself to the approver. Display only, and a
         * constant: the SDK reads nothing from the OS, and neither does this.
         */
        const val DEVICE_NAME = "SPFN Android harness"
    }
}
