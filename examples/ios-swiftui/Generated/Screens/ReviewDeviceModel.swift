// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      cd02e9ed576538e540a939229a0e476a76708e84286a3ccd09f5f680bf7ab8b5
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

import Foundation
import Observation
import SPFNClient
import SPFNGenerated
import SPFNUI

/// The `reviewDevice` screen's state and rules, with no toolkit in sight.
///
/// There is no `empty`, and that is the contract's doing rather than a simplification:
/// the bundle models a response as one named type or none at all, so nothing in it can
/// say "this operation answers with a list" (examples/ui-spec/SCHEMA.md).
///
/// A write in flight is held on a separate flag rather than in the state, because this
/// screen's vocabulary has no `busy` member and putting the write into `loading` would
/// blank a value the screen is still showing.
@MainActor
@Observable
public final class ReviewDeviceModel
{
    /// What this screen's read has produced so far.
    public private(set) var state: Loadable<SPFNDeviceAuthInfoResponse> = .loading

    private let useCase: any ReviewDeviceUseCase
    private let deviceApproval: any DeviceApprovalService
    private let flow: Flow<ApproveDeviceRoute>
    private let userCode: String

    /// Which request is the current one.
    ///
    /// Bumped by everything that starts or abandons a call, and checked again when the
    /// answer comes back. An answer whose token is stale — a superseded call, or a call
    /// whose flow has since closed — is dropped rather than written into a screen
    /// nobody is looking at any more.
    private var generation: Int = 0

    /// Whether one of this screen's writes is in flight.
    private var writing: Bool = false

    public init(
        useCase: any ReviewDeviceUseCase,
        deviceApproval: any DeviceApprovalService,
        flow: Flow<ApproveDeviceRoute>,
        userCode: String
    )
    {
        self.useCase = useCase
        self.deviceApproval = deviceApproval
        self.flow = flow
        self.userCode = userCode
    }

    /// The flow's stack, so the screen can print its depth as a readout.
    public var stack: [ApproveDeviceRoute] { flow.stack }

    /// Reads this screen's source. Called once when the screen appears, however it appeared.
    public func load() async
    {
        generation += 1
        let token = generation
        state = .loading
        let value: SPFNDeviceAuthInfoResponse
        do
        {
            value = try await useCase.lookup(userCode: userCode)
        }
        catch
        {
            if isCurrent(token)
            {
                state = .error(ScreenFailure.envelope(error))
            }
            return
        }
        if isCurrent(token)
        {
            state = .ready(value)
        }
    }

    /// Lets the waiting device in, answering with the device it just let in.
    ///
    /// Ignored unless this screen is showing a value and no write of its own is running.
    public func approve() async
    {
        guard !writing, case .ready = state
        else
        {
            return
        }
        generation += 1
        let token = generation
        writing = true
        do
        {
            _ = try await deviceApproval.approve(SPFNApproveDeviceAuthRequest(userCode: userCode))
        }
        catch
        {
            writing = false
            if isCurrent(token)
            {
                state = .error(ScreenFailure.envelope(error))
            }
            return
        }
        writing = false
        guard isCurrent(token)
        else
        {
            return
        }
        flow.close()
    }

    /// Drops this route. On the flow's first route this does nothing.
    public func back()
    {
        generation += 1
        flow.pop()
    }

    /// Refuses the waiting device. Answers 204 with no body, so it names no response type.
    ///
    /// Ignored unless this screen is showing a value and no write of its own is running.
    public func deny() async
    {
        guard !writing, case .ready = state
        else
        {
            return
        }
        generation += 1
        let token = generation
        writing = true
        do
        {
            try await deviceApproval.deny(SPFNDenyDeviceAuthRequest(userCode: userCode))
        }
        catch
        {
            writing = false
            if isCurrent(token)
            {
                state = .error(ScreenFailure.envelope(error))
            }
            return
        }
        writing = false
        guard isCurrent(token)
        else
        {
            return
        }
        flow.close()
    }

    /// Reads the source again. Ignored while a write of this screen's is in flight.
    public func retry() async
    {
        if writing
        {
            return
        }
        await load()
    }

    /// Whether an answer bearing `token` still belongs to a screen that is on show.
    ///
    /// Three questions: is this the current request, is the flow still presented, and
    /// is this screen's own route still on the stack. The last is not implied by the
    /// others — a route popped while a call was in flight leaves both of them true.
    private func isCurrent(_ token: Int) -> Bool
    {
        token == generation
            && flow.isPresented
            && flow.stack.contains(ApproveDeviceRoute.reviewDevice(userCode: userCode))
    }
}
