// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      83ef5335dcd7c486e823ce1bc60ee788e75fb2c3a16a9bcd084222a5a68fb888
// bundleSha256:    7879036f33c46ced4c51035521fd25d3934bbb38c21aa9b68779a1c6d1578846
// contractVersion: 0.13.5
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

import SPFNGenerated

/// What `reviewDevice` reads, named as the app's own act rather than as an operation.
///
/// It stands between the model and the service so the hand-written layer has somewhere
/// to put a rule that is neither the screen's nor the wire's.
public protocol ReviewDeviceUseCase: Sendable
{
    func lookup(userCode: String) async throws -> SPFNDeviceAuthInfoResponse
}

/// The pass-through. It adds a seam, not a rule.
public struct DefaultReviewDeviceUseCase: ReviewDeviceUseCase, Sendable
{
    private let service: any DeviceApprovalService

    public init(service: any DeviceApprovalService)
    {
        self.service = service
    }

    public func lookup(userCode: String) async throws -> SPFNDeviceAuthInfoResponse
    {
        try await service.lookup(SPFNDeviceAuthInfoRequest(userCode: userCode))
    }
}
