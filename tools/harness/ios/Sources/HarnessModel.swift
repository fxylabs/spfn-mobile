// SPFN Mobile — the harness's whole behaviour.
//
// One lifecycle over the real keychain store and the real URLSession transport, driven
// by seven buttons. Nothing is faked except the sign-in token and the network switch,
// and both of those are seams the SDK already has.
//
// Two labels carry everything a flow asserts on: the lifecycle's own state, and the last
// action's outcome under a stable short name. A flow never reads a sentence.

import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

@MainActor
final class HarnessModel: ObservableObject
{
    /// Exactly `unenrolled`, `enrolled` or `rotationPending` — the SDK's own vocabulary,
    /// so a flow asserting on this text is asserting on the state machine.
    @Published private(set) var state = "unread"

    /// `idle` before anything runs, then `ok:<detail>` or `err:<name>`.
    @Published private(set) var outcome = "idle"

    @Published private(set) var networkBlocked = false

    /// True while an action is in flight, so a flow can wait for quiet instead of
    /// sleeping for a guessed number of seconds.
    @Published private(set) var busy = false

    private let configuration: HarnessConfiguration
    private let transport: HarnessTransport
    private let store: any SPFNKeyStore
    private let lifecycle: SPFNKeyLifecycle

    init(configuration: HarnessConfiguration = .fromLaunch())
    {
        self.configuration = configuration
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
}
