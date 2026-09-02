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

/// The `enterCode` screen's state and rules, with no toolkit in sight.
///
/// Constructor injection, so a test drives this class against a fake service and a
/// real `Flow` with no device, no view and no server.
@MainActor
@Observable
public final class EnterCodeModel
{
    /// What this screen's write is doing.
    public private(set) var state: Busy = .idle

    private let deviceApproval: any DeviceApprovalService
    private let flow: Flow<ApproveDeviceRoute>

    /// Which request is the current one.
    ///
    /// Bumped by everything that starts or abandons a call, and checked again when the
    /// answer comes back. An answer whose token is stale — a superseded call, or a call
    /// whose flow has since closed — is dropped rather than written into a screen
    /// nobody is looking at any more.
    private var generation: Int = 0

    public init(
        deviceApproval: any DeviceApprovalService,
        flow: Flow<ApproveDeviceRoute>
    )
    {
        self.deviceApproval = deviceApproval
        self.flow = flow
    }

    /// The flow's stack, so the screen can print its depth as a readout.
    public var stack: [ApproveDeviceRoute] { flow.stack }

    /// Closes the flow. Its stack empties, so nothing of it is presented.
    public func cancel()
    {
        generation += 1
        flow.close()
    }

    /// Describes the device waiting on a user code, so the approver can recognise it before deciding.
    ///
    /// Ignored while a write is already in flight, and refused outright when a required
    /// input is blank — a refusal the screen states without sending anything.
    public func submit(userCode: String) async
    {
        if state == .busy
        {
            return
        }
        if userCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            state = .error(ScreenFailure.validation("userCode"))
            return
        }
        generation += 1
        let token = generation
        state = .busy
        do
        {
            _ = try await deviceApproval.lookup(SPFNDeviceAuthInfoRequest(userCode: userCode))
        }
        catch
        {
            if isCurrent(token)
            {
                state = .error(ScreenFailure.envelope(error))
            }
            return
        }
        guard isCurrent(token)
        else
        {
            return
        }
        state = .idle
        flow.push(.reviewDevice(userCode: userCode))
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
            && flow.stack.contains(ApproveDeviceRoute.enterCode)
    }
}
