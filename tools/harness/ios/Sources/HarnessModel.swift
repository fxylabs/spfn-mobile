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
    func signIn(with provider: HarnessProvider) async
    {
        busy = true
        defer { busy = false }

        guard isReady(provider)
        else
        {
            // Belt and braces: the button is disabled in this state. If it is ever
            // reachable anyway, refusing is the whole point — Google's SDK answers a
            // missing client id with an NSException, which no Swift caller can catch.
            outcome = "err:\(HarnessOutcome.name(for: HarnessError.notConfigured))"
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

    /// The enrolment itself: the SDK's call, the SDK's adapters, and nothing in between
    /// but the token sabotage the server-reject case asks for.
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
                Calls.keysRevoke,
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
                Calls.keysList,
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

    // MARK: - Running one action

    /// Every button goes through here, so every button reports the same way: a short
    /// stable name on failure, `ok:` and a detail on success, and the state re-read
    /// afterwards whichever it was.
    /// Called by the view at tap time, synchronously, before the task exists. See
    /// `HarnessView.asyncButton` for why the model cannot do this itself.
    func markBusy()
    {
        busy = true
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

    /// The call descriptors the harness drives directly. `revoke` has no lifecycle
    /// method — the SDK exposes enrolment and rotation, and revocation is an operation —
    /// so the harness reaches it the way any app would (decision 01kzb8tjxp, D-3).
    private enum Calls
    {
        static let keysList = SPFNCall<SPFNListKeysRequest, SPFNListKeysResponse>(
            operation: SPFNGeneratedOperations.authKeysList,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNListKeysResponse(canonical: $0) }
        )

        static let keysRevoke = SPFNCall<SPFNRevokeKeyRequest, SPFNRevokeKeyResponse>(
            operation: SPFNGeneratedOperations.authKeysRevoke,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNRevokeKeyResponse(canonical: $0) }
        )
    }
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
