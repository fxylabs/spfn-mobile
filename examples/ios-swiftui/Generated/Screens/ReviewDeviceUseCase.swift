// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      18bfbc7bb24e30e2dbb65e195f5448594bab02fa52e257cb590d5a2c534a7633
// bundleSha256:    a7cab3c8e98f3d83384693a45b5d1754640529636c193afd54ef116e98e47c1a
// contractVersion: 0.13.4
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
