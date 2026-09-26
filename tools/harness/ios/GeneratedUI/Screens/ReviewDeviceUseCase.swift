// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      91e7628f407dafb74f4b501b4effac2b2277dc629c2a7ad4de42749f410ca018
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

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
