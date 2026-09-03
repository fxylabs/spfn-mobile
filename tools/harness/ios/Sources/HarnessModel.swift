// SPFN Mobile — the harness's whole behaviour.
//
// One lifecycle over the real keychain store and the real URLSession transport, driven
// by ten buttons. Nothing is faked except the sign-in token and the network switch,
// and both of those are seams the SDK already has.
//
// Two labels carry everything a flow asserts on: the lifecycle's own state, and the last
// action's outcome under a stable short name. A flow never reads a sentence.
//
// A third, `custody`, is read by a person rather than a flow. It names which hardware
// holds a key, which is the one question a simulator cannot answer for us — see
// `probeCustody()`.

import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated
import SPFNHarnessSupport
import SPFNUI

@MainActor
final class HarnessModel: ObservableObject
{
    /// Exactly `unenrolled`, `enrolled` or `rotationPending` — the SDK's own vocabulary,
    /// so a flow asserting on this text is asserting on the state machine.
    @Published private(set) var state = "unread"

    /// `idle` before anything runs, then `ok:<detail>` or `err:<name>`.
    @Published private(set) var outcome = "idle"

    /// `unread` until probed, then the custody a freshly generated key actually landed in.
    @Published private(set) var custody = "unread"

    /// Whether the transport is currently refusing to send, for the permanent `network=`
    /// readout. Mirrored here rather than read from the transport because a view redraws on
    /// a published change and not on a lock; `setNetworkBlocked` is the only writer of the
    /// transport's own flag, so the mirror cannot drift from it.
    @Published private(set) var networkBlocked = false

    /// Which of the five device cases the next provider tap is running. The app cannot
    /// work this out for itself — see `HarnessDeviceCase`.
    @Published var deviceCase: HarnessDeviceCase = .firstEnroll

    /// The file name of the last receipt written, or why none was. `none` before the
    /// first attempt, and never silently empty: a receipt that could not be written and
    /// a case that was never run are different facts.
    @Published private(set) var receipt = "none"

    /// True while an action is in flight, so a flow can wait for quiet instead of
    /// sleeping for a guessed number of seconds.
    @Published private(set) var busy = false

    /// The code this device is showing while it waits to be approved, or `none`.
    ///
    /// Written by the SDK's `showCode` callback and by nothing else. It is cleared when
    /// the wait ends however it ends: a code still on screen after the wait is over is a
    /// code somebody would type into the other phone for nothing.
    @Published private(set) var deviceCode = "none"

    /// When that code stops being usable, so the screen can count down to it. Nil when no
    /// code is showing.
    @Published private(set) var deviceCodeExpiresAtMillis: Int64?

    /// The generated app's graph, once `open-approve` has built one.
    ///
    /// Nil until the button is tapped, which is what keeps the flow host drawing nothing
    /// and the `stack=` readout at zero on a screen nobody has opened the flow on. A tap
    /// builds a FRESH one every time: the container closes over the key provider the
    /// lifecycle held at that moment, and a wipe between two attempts would otherwise
    /// leave the second one signing with a key that no longer exists.
    ///
    /// The type is the generated `AppContainer` and nothing wraps it. Everything the
    /// approval screens do — the code field, the two writes, the navigation — is the
    /// generator's, and this app supplies the transport, the key and the address.
    @Published private(set) var approval: AppContainer?

    /// The status of the last response the transport received, or `none`.
    ///
    /// Written from `HarnessTransport`'s own callback rather than read on demand, so a
    /// response that no flow acted on still reaches the screen. It is a record of the
    /// WIRE and not of an action: the approval screens send through the generated
    /// service, which reports its refusals on its own `state=` readout and never here.
    @Published private(set) var httpStatus = "none"

    /// The provider whose button must be showing a spinner, or nil.
    ///
    /// `busy` cannot answer this. It is true for any of the twelve actions on the screen,
    /// and what a person needs to see is that the button THEY tapped is working — a
    /// spinner on the other provider would be a wrong answer rather than a vague one.
    @Published private(set) var runningProvider: HarnessProvider?

    /// The last completed action, for the banner to show and for nothing else to read.
    ///
    /// Published as a value with an identity rather than as a string: two identical
    /// results in a row are two completions, and a plain `String` would leave the second
    /// one silent because nothing changed.
    @Published private(set) var signal: HarnessSignal?

    /// What this build was configured with, for the screen to state and the buttons to
    /// obey. Nothing here is a value — only whether each half of it is present.
    let device: HarnessDeviceConfiguration

    private let configuration: HarnessConfiguration
    private let transport: HarnessTransport
    private let store: any SPFNKeyStore
    private let lifecycle: SPFNKeyLifecycle

    init(configuration: HarnessConfiguration = .fromLaunch())
    {
        self.configuration = configuration
        self.device = configuration.device
        self.transport = HarnessTransport()
        self.store = SPFNKeychainKeyStore(service: "xyz.superfunction.spfn.harness")
        self.lifecycle = SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: configuration.baseURL
        )
        // The transport answers on whatever thread the response arrived on, and this
        // model is a `@MainActor` type — so the hop is written here, exactly as it is
        // written for the device code's own callback below.
        transport.onResponse =
        { [weak self] status in
            Task { @MainActor in self?.httpStatus = String(status) }
        }
    }

    /// How deep the approval flow's stack is, which is zero when there is no flow.
    ///
    /// Read through the generated `Flow`, never mirrored into a stored property here: the
    /// flow is the single source of truth for its own routes, and a copy would be a
    /// second opinion about which screen is on show. `Flow` is `@Observable`, so a view
    /// that reads this in its body is redrawn when the stack moves.
    var stackDepth: Int
    {
        approval?.approveDeviceFlow.stack.count ?? 0
    }

    /// Whether a key exists to prove the approval calls with.
    ///
    /// Both states with an active key count, `rotationPending` included: the old key is
    /// still the active one until the rotation is resumed, and that is exactly the key
    /// `activeProvider()` answers with.
    var hasActiveKey: Bool
    {
        state == "enrolled" || state == "rotationPending"
    }

    // MARK: - Observation

    func refresh() async
    {
        do
        {
            state = Self.name(of: try await lifecycle.state())
        }
        catch
        {
            state = "unreadable"
        }
    }

    private static func name(of value: SPFNKeyLifecycleState) -> String
    {
        switch value
        {
        case .unenrolled:
            return "unenrolled"
        case .enrolled:
            return "enrolled"
        case .rotationPending:
            return "rotationPending"
        }
    }

    // MARK: - Actions
    //
    // `enroll` and `signIn` are the same SDK call reached two ways. A flow calls the
    // first with a canned token because Maestro cannot drive a system sheet; a person
    // calls the second, which puts the real sheet up and writes a receipt. Neither is a
    // substitute for the other, which is why both exist.

    func enroll() async
    {
        await run
        {
            let configuration = self.configuration
            let result = try await self.lifecycle.enroll(provider: configuration.provider)
            { nonce in
                guard let token = configuration.idToken(for: nonce)
                else
                {
                    throw HarnessError.noCannedToken
                }
                return token
            }
            return "enrolled:\(result.keyID)"
        }
    }

    // MARK: - The device verification mode

    /// One attempt at the selected case, through the real provider sheet, ending in a
    /// receipt on disk whatever happened.
    ///
    /// This is the only action that does not go through `run`. `run` reports one line and
    /// re-reads the state; this has to observe several things in a fixed order — what the
    /// wire said, what the SDK classified, whether a key survived — and then write them
    /// down. Sharing the shorter path would have meant reading some of them after the
    /// state had already been re-read, which is the one ordering that cannot be trusted.
    ///
    /// One tap is the whole attempt. The wipe below used to be the operator's job, and the
    /// first device run produced three `alreadyEnrolled` receipts from forgetting it —
    /// three attempts that proved nothing about a provider and only that a person had one
    /// more thing to remember.
    ///
    /// The `defer` announces every exit, including the two refusals below, because a
    /// refusal is a completed tap: the attempt that did not happen is exactly the thing a
    /// person needs told, and it is the reading the `receipt=` readout alone gets wrong
    /// most often.
    func signIn(with provider: HarnessProvider) async
    {
        busy = true
        runningProvider = provider
        defer
        {
            busy = false
            runningProvider = nil
            announce("\(outcome)\n\(receipt)")
        }

        guard isReady(provider)
        else
        {
            // Belt and braces: the button is disabled in this state. If it is ever
            // reachable anyway, refusing is the whole point — Google's SDK answers a
            // missing client id with an NSException, which no Swift caller can catch.
            //
            // `receipt` is reset for the reason `wipeBeforeAttempt` resets it: this tap
            // wrote no file, and leaving the previous attempt's name standing lets an
            // older file be read as this one's evidence (P7). It matters more now that the
            // name is announced when the tap ends.
            outcome = "err:\(HarnessOutcome.name(for: HarnessError.notConfigured))"
            receipt = "none"
            return
        }

        guard await wipeBeforeAttempt()
        else
        {
            return
        }

        transport.beginAttempt()

        // Restored to whatever it was rather than to open: a person may have blocked the
        // network with the button before running this case, and putting it back to open
        // would change a setting they made. The `outcome` these two calls write is
        // overwritten by the receipt below, which is the line worth reading.
        let restoreBlocked = transport.isBlocked
        if deviceCase.blocksNetwork
        {
            setNetworkBlocked(true)
        }

        let attempt = await attemptEnrollment(with: provider)

        if deviceCase.blocksNetwork
        {
            setNetworkBlocked(restoreBlocked)
        }

        await refresh()
        await recordReceipt(for: provider, attempt: attempt)
    }

    /// Clears whatever a previous attempt left, and answers whether the attempt may go on.
    ///
    /// It runs BEFORE the case's own arrangements — before the transport is shut for
    /// `network-failure` — because a wipe is local work that a blocked transport has no
    /// business failing. Reversing the two would turn one case into a wipe failure.
    ///
    /// A wipe that fails abandons the attempt rather than pushing on. Enrolling on top of a
    /// state nobody could clear is exactly the reading the auto-wipe exists to stop
    /// producing, and a receipt written from it would be evidence of the harness rather
    /// than of the SDK. No receipt is written, and `receipt` is reset rather than left
    /// naming the previous attempt's file: an operator reading this screen must not be able
    /// to attribute an older file to this tap. The reason sits beside it on `outcome=`,
    /// which is what keeps "no attempt was made" apart from "the attempt left no evidence"
    /// (docs/IMPLEMENTATION-PITFALLS.md P7).
    ///
    /// There is no cancellation branch here and the Kotlin half has one. That is a real
    /// difference rather than an omission (P15): `SPFNKeyLifecycle.wipe()` is a synchronous
    /// `throws` method reached across an actor, and an actor hop is not a cancellation
    /// point, so nothing here can raise `CancellationError`. Kotlin's `wipe` is a `suspend`
    /// function over a mutex, where a cancellation genuinely arrives and its rethrow is
    /// load-bearing. A symmetric catch on this side would be a branch that never runs, and
    /// the two halves are meant to agree on behaviour rather than on shape.
    private func wipeBeforeAttempt() async -> Bool
    {
        do
        {
            try await lifecycle.wipe()
            await refresh()
            return true
        }
        catch
        {
            outcome = "err:wipe:\(HarnessOutcome.name(for: error))"
            receipt = "none"
            await refresh()
            return false
        }
    }

    /// The enrolment itself: the SDK's call, the SDK's adapters, and nothing in between
    /// but the token sabotage the server-reject case asks for.
    ///
    /// `alreadyEnrolled` is now unreachable from here: the attempt wiped first, so the
    /// lifecycle was `unenrolled` when this ran. It is deliberately NOT special-cased. If
    /// it ever appears in a receipt it means a wipe reported success and left a key, which
    /// is a finding about the SDK or the store — and a receipt that classified it as
    /// anything other than the plain `failed` / `alreadyEnrolled` it is would hide it.
    private func attemptEnrollment(with provider: HarnessProvider) async -> Result<SPFNEnrollmentResult, any Error>
    {
        let deviceCase = self.deviceCase
        do
        {
            return .success(try await lifecycle.enroll(provider: provider.rawValue)
            { nonce in
                let token = try await HarnessSocialSignIn.idToken(provider: provider, nonce: nonce)
                return HarnessTokenSabotage.applied(to: token, for: deviceCase)
            })
        }
        catch
        {
            return .failure(error)
        }
    }

    /// Turns what happened into the receipt's cells and writes it.
    ///
    /// `keyRemainsAfterFailure` is read AFTER the attempt and only means something when
    /// the attempt did not enrol: the design promise is that a cancelled or failed
    /// enrolment leaves no key behind. On a success the key is supposed to be there, so
    /// the field is false rather than a true that would read as a broken promise.
    ///
    /// A state that could not be read at all counts as a key remaining. That is the
    /// pessimistic answer and it is the right one: an unreadable keychain is not evidence
    /// that nothing survived, and a receipt that claimed it was would be a green built
    /// out of a failure to look.
    private func recordReceipt(for provider: HarnessProvider, attempt: Result<SPFNEnrollmentResult, any Error>) async
    {
        let observation = transport.observation
        let enrolled: SPFNEnrollmentResult?
        let errorCode: String?

        switch attempt
        {
        case .success(let result):
            enrolled = result
            errorCode = nil
            outcome = "ok:enrolled:\(result.keyID)"
        case .failure(let error):
            enrolled = nil
            errorCode = HarnessOutcome.name(for: error)
            outcome = "err:\(HarnessOutcome.name(for: error))"
        }

        let receipt = HarnessReceipt(
            provider: provider,
            deviceCase: deviceCase,
            outcome: Self.outcome(for: attempt),
            responseCode: observation?.statusCode,
            errorCode: errorCode,
            isNewUser: enrolled?.isNewUser ?? false,
            keyIDMatch: await keyIDMatches(enrolled),
            keyRemainsAfterFailure: enrolled == nil && state != "unenrolled",
            serverBaseURL: configuration.baseURL,
            serverCommit: observation?.serverCommit,
            recordedAt: Date()
        )

        do
        {
            self.receipt = try receipt.write().lastPathComponent
        }
        catch
        {
            // Not silent, and not the same word as "no receipt". A run that cannot write
            // its evidence is a broken harness; a run that produced none is a case that
            // never happened, and an assertion has to be able to tell them apart (P7).
            self.receipt = "unwritten:\(HarnessOutcome.name(for: error))"
        }
    }

    /// Whether the key the server confirmed is the key this install now signs with. The
    /// SDK already refuses a server that names another key, so this is the second half of
    /// that promise: the confirmed key is also the one that got persisted.
    private func keyIDMatches(_ enrolled: SPFNEnrollmentResult?) async -> Bool
    {
        guard let enrolled, let active = try? await lifecycle.activeProvider()
        else
        {
            return false
        }
        return active.keyID == enrolled.keyID
    }

    private static func outcome(for attempt: Result<SPFNEnrollmentResult, any Error>) -> HarnessReceiptOutcome
    {
        switch attempt
        {
        case .success:
            return .enrolled
        case .failure(let error):
            return HarnessSocialSignIn.isCancellation(error) ? .cancelled : .failed
        }
    }

    /// Whether this build can put `provider`'s sheet up and have somewhere to send what
    /// comes back.
    ///
    /// The server half is read from the base URL the SDK was actually given, not from the
    /// build-time configuration: a run launched with `SPFN_HARNESS_BASE_URL` and no
    /// `Local.xcconfig` has a server, and a readiness check that only looked at the
    /// build-time half would grey out a button that works.
    ///
    /// Apple needs nothing else. Its sheet is the operating system's own, and what it
    /// really needs — the entitlement — is a signing-time fact no app can read about
    /// itself. Google needs a client id whose callback scheme this bundle registers,
    /// because the alternative is an NSException at tap time.
    func isReady(_ provider: HarnessProvider) -> Bool
    {
        switch provider
        {
        case .apple:
            return serverConfigured
        case .google:
            return serverConfigured && device.googleClientID != nil
        }
    }

    var serverConfigured: Bool
    {
        !configuration.baseURL.isEmpty
    }

    /// One ASCII line naming which half of the configuration is missing, rather than only
    /// that something is.
    var configSummary: String
    {
        let server = serverConfigured ? "ready" : "missing"
        let google = device.googleClientID == nil ? "missing" : "ready"
        return "server:\(server) google:\(google)"
    }

    func selectCase(_ value: HarnessDeviceCase)
    {
        deviceCase = value
    }

    func rotate() async
    {
        await run { "rotated:\(try await self.lifecycle.rotate().keyID)" }
    }

    func resumeRotation() async
    {
        await run { "resumed:\(try await self.lifecycle.resumeRotation().keyID)" }
    }

    /// Revokes the key this install is signing with, which is what makes the next proven
    /// call answer SESSION_REVOKED. `revokeAll` spares the caller, so it cannot do this.
    ///
    /// Sent through the generated descriptor and `execute`: revocation has no lifecycle
    /// method — the SDK exposes enrolment and rotation, and revocation is an operation —
    /// so the harness reaches it the way any app would (decision 01kzb8tjxp, D-3).
    func revokeActiveKey() async
    {
        await run
        {
            guard let provider = try await self.lifecycle.activeProvider()
            else
            {
                throw HarnessError.noActiveKey
            }
            _ = try await self.client(signingWith: provider).execute(
                SPFNGeneratedCalls.authKeysRevoke,
                request: SPFNRevokeKeyRequest(keyId: provider.keyID)
            )
            return "revoked:\(provider.keyID)"
        }
    }

    /// A proven call whose only purpose is to meet whatever the server now thinks of
    /// this key. After a revocation it is the SESSION_REVOKED the flow asserts on.
    func provenCall() async
    {
        await run
        {
            guard let provider = try await self.lifecycle.activeProvider()
            else
            {
                throw HarnessError.noActiveKey
            }
            let listed = try await self.client(signingWith: provider).execute(
                SPFNGeneratedCalls.authKeysList,
                request: SPFNListKeysRequest()
            )
            return "listed:\(listed.keys.count)"
        }
    }

    /// The SDK's own answer to a revoked session: both slots go. A flow calls this after
    /// SESSION_REVOKED so the state machine returns to `unenrolled` the way an app would
    /// return it.
    func noteSessionRevoked() async
    {
        await run
        {
            try await self.lifecycle.noteSessionRevoked()
            return "wiped"
        }
    }

    func wipe() async
    {
        await run
        {
            try await self.lifecycle.wipe()
            return "wiped"
        }
    }

    // MARK: - The device-code flow

    /// Signs this device in with a code somebody approves elsewhere.
    ///
    /// The callback is called once, as soon as the server answers, and this model is a
    /// `@MainActor` type — so the hop onto the main actor is written here rather than
    /// assumed. The SDK switches to no executor of its own: it calls back on whatever the
    /// caller was running on, which is what lets an app decide where its own drawing
    /// happens.
    func signInWithACode() async
    {
        await run
        {
            let settled = try await self.lifecycle.enrollByDeviceCode(deviceName: Self.deviceName)
            { userCode, expiresAtMillis in
                Task { @MainActor in self.showCode(userCode, expiresAtMillis: expiresAtMillis) }
            }
            return "signed-in:\(settled.keyID)"
        }
        // However the wait ended, the code on screen is spent.
        deviceCode = "none"
        deviceCodeExpiresAtMillis = nil
    }

    private func showCode(_ userCode: String, expiresAtMillis: Int64)
    {
        // Exactly as the server spelled it. The server folds case, spaces and dashes on
        // the way back in, so nothing here reformats what a person is about to read out.
        deviceCode = userCode
        deviceCodeExpiresAtMillis = expiresAtMillis
    }

    /// Opens the generated approval flow on a graph built for this install's own key.
    ///
    /// The approver's three operations used to be three buttons and a text field here,
    /// each one assembling a request and calling a generated descriptor by hand. They are
    /// the generated screens' work now, and this is the whole of what an app supplies:
    /// a transport, a key and an address. What that removed is not decoration — it was a
    /// second implementation of a flow the generator already emits, which is exactly the
    /// thing this harness exists to drive rather than to duplicate.
    func openApprove() async
    {
        await run
        {
            guard let container = try await self.approvalContainer()
            else
            {
                throw HarnessError.noActiveKey
            }
            self.approval = container
            return "approve-open"
        }
    }

    /// The generated graph over the harness's own wire, or nil when there is no key.
    ///
    /// The three things the generator cannot know, and nothing else: the transport this
    /// harness already sends through — network switch included, so a blocked transport
    /// blocks these screens too — the key the lifecycle is currently signing with, and
    /// the base URL this build was launched with. The same base URL the model itself
    /// uses, so the flow can never reach an address the rest of the screen does not.
    func approvalContainer() async throws -> AppContainer?
    {
        guard let provider = try await lifecycle.activeProvider()
        else
        {
            return nil
        }
        return AppContainer.live(
            transport: transport,
            keyProvider: provider,
            baseURL: configuration.baseURL
        )
    }

    /// The label this device gives itself to the approver. Display only, and a constant:
    /// the SDK reads nothing from the OS, and neither does this.
    private static let deviceName = "SPFN iOS harness"

    /// Which custody this device actually gives a client key.
    ///
    /// Generated through the same call and the same default `SPFNKeyLifecycle` uses for
    /// its own keys, read, and then dropped: nothing is stored and no request is sent.
    /// That last part is the point. Hardware custody is the one thing a real iPhone
    /// proves that a simulator cannot, and Maestro ships no driver for a physical iOS
    /// device — so this has to be a check a person can run by hand, on a phone with no
    /// route to the reference server.
    ///
    /// Whether a simulator answers `softwareKeychain` here is not assumed. An Apple
    /// silicon Mac has an enclave of its own, and if the simulator lends it out then both
    /// targets report `secureEnclave` and this readout stops separating them. Reading it
    /// settles that; guessing it would have put a wrong expectation in a flow.
    func probeCustody() async
    {
        await run
        {
            let probe = SPFNCustodyKey.generate(keyID: "custody-probe")
            self.custody = probe.custody.rawValue
            return "custody:\(probe.custody.rawValue)"
        }
    }

    func setNetworkBlocked(_ value: Bool)
    {
        transport.setBlocked(value)
        networkBlocked = value
        outcome = value ? "ok:network-blocked" : "ok:network-open"
    }

    /// The network switch as a BUTTON: the same flag, and the completion signal a tap
    /// owes the person who made it.
    ///
    /// Separate from `setNetworkBlocked` rather than folded into it, because `signIn`
    /// calls that one twice around an attempt to arrange the `network-failure` case. A
    /// signal there would announce a step of an attempt as though it were the result of
    /// one, twice, over the sheet.
    func toggleNetworkBlocked(_ value: Bool)
    {
        setNetworkBlocked(value)
        announce(outcome)
    }

    // MARK: - Running one action

    /// Every button goes through here, so every button reports the same way: a short
    /// stable name on failure, `ok:` and a detail on success, and the state re-read
    /// afterwards whichever it was.
    /// Called by the view at tap time, synchronously, before the task exists. See
    /// `HarnessView.asyncButton` for why the model cannot do this itself.
    ///
    /// `running` names the provider whose button must show a spinner, and it is set here
    /// for the same reason and with the same urgency as `busy`: a spinner that appeared
    /// only once the task began would leave the tap looking unanswered for exactly the
    /// window this method exists to close.
    func markBusy(running provider: HarnessProvider? = nil)
    {
        busy = true
        runningProvider = provider
    }

    /// The completion signal a tap owes the person who made it.
    ///
    /// The text carries NO readout prefix — `ok:wiped`, not `outcome=ok:wiped`. Every flow
    /// selector in tools/harness/flows/ matches either an accessibility identifier or a
    /// readout's text (`outcome=…`, `state=…`, `busy=…`), and dropping the prefix is one
    /// of the two things keeping a flow off this banner. The other is that the banner is
    /// hidden from the accessibility hierarchy entirely — see `HarnessView.banner`.
    private func announce(_ text: String)
    {
        signal = HarnessSignal(text: text)
    }

    private func run(_ action: @escaping () async throws -> String) async
    {
        busy = true
        do
        {
            outcome = "ok:\(try await action())"
        }
        catch
        {
            outcome = "err:\(HarnessOutcome.name(for: error))"
        }
        await refresh()
        busy = false
        // After `refresh`, so the banner and the readouts are never two readings of one
        // action taken at two different moments.
        announce(outcome)
    }

    private func client(signingWith provider: SPFNSecureEnclaveKeyProvider) -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: SPFNSession(
                transport: transport,
                keyProvider: provider,
                baseURL: configuration.baseURL
            )
        )
    }
}

/// One completed action, for the banner to show and then forget.
///
/// The identity is what makes it a signal rather than a value. Tapping `wipe` twice
/// produces `ok:wiped` twice, and a banner keyed on the text alone would show the first
/// and stay silent for the second — the reading "nothing happened" that this whole layer
/// exists to stop producing.
struct HarnessSignal: Equatable, Identifiable
{
    let id = UUID()
    let text: String
}

/// What the harness itself refuses, as opposed to what the SDK refuses.
enum HarnessError: Error
{
    /// A flow asked for enrolment without supplying a token and this build has no
    /// provider SDK to obtain one. A device run supplies the sheet instead.
    case noCannedToken

    case noActiveKey

    /// A provider was tapped in a build with no `Local.xcconfig`, or with one missing
    /// the half that provider needs. Unreachable through the screen, which disables the
    /// button — and kept anyway, because the alternative for Google is an NSException.
    case notConfigured

    /// No foreground window to present a provider sheet from. Refused before the sheet
    /// is asked for rather than after it fails to appear.
    case noPresentationAnchor
}
