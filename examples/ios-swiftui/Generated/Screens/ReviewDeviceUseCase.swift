// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
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
