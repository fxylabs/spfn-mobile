// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      5babeed3f41fa7c8eb049bc79d7719ff9f0d79ede06c4073015643be04668f7a
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

import SPFNClient
import SPFNGenerated

/// The `deviceApproval` service: one method per operation the spec names.
///
/// The one generated file that names a call descriptor. Everything above it sees this
/// protocol and the generated request and response types, and
/// `tools/validate/validate.sh` refuses a `SPFNGeneratedCalls.` reference anywhere
/// under `examples/` outside this directory.
public protocol DeviceApprovalService: Sendable
{
    /// Lets the waiting device in, answering with the device it just let in.
    func approve(_ request: SPFNApproveDeviceAuthRequest) async throws -> SPFNDeviceAuthInfoResponse

    /// Refuses the waiting device. Answers 204 with no body, so it names no response type.
    func deny(_ request: SPFNDenyDeviceAuthRequest) async throws

    /// Describes the device waiting on a user code, so the approver can recognise it before deciding.
    func lookup(_ request: SPFNDeviceAuthInfoRequest) async throws -> SPFNDeviceAuthInfoResponse
}

/// ``DeviceApprovalService`` against a real server, through one client.
///
/// An operation that declares no response type answers 204 with an empty body, so its
/// method answers `Void` and the descriptor's `SPFNNoResponse` is discarded here rather
/// than travelling up into a screen.
public struct DefaultDeviceApprovalService: DeviceApprovalService, Sendable
{
    private let client: SPFNClient

    public init(client: SPFNClient)
    {
        self.client = client
    }

    public func approve(_ request: SPFNApproveDeviceAuthRequest) async throws -> SPFNDeviceAuthInfoResponse
    {
        try await client.execute(SPFNGeneratedCalls.authDeviceApprove, request: request)
    }

    public func deny(_ request: SPFNDenyDeviceAuthRequest) async throws
    {
        _ = try await client.execute(SPFNGeneratedCalls.authDeviceDeny, request: request)
    }

    public func lookup(_ request: SPFNDeviceAuthInfoRequest) async throws -> SPFNDeviceAuthInfoResponse
    {
        try await client.execute(SPFNGeneratedCalls.authDeviceInfo, request: request)
    }
}
