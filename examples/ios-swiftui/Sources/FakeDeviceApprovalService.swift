// SPFN Mobile — the service a launch fixture installs.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/FakeDeviceApprovalService.kt,
// field for field and method for method.
//
// Hand-written and outside `Generated/`, because what a fixture answers is a decision
// about the CASE TABLE and not about the spec: the generator knows that `lookup` answers
// an `SPFNDeviceAuthInfoResponse`, and only the table knows that cell u13 needs the second
// one to refuse.
//
// It sends nothing and holds no transport. A refusal here is a real `SPFNClientError` of
// the same case the client would raise, because the screen models classify on that type
// and a fake that threw something else would exercise a branch no server can reach.
//
// An `actor` rather than a class: the generated service protocol is `Sendable`, the models
// that hold one are `@MainActor`, and this type has counters of its own. An actor is
// Sendable by construction, and an actor-isolated method satisfies an `async` protocol
// requirement. The Kotlin twin needs none of this — it is a plain class, because the
// models there are main-thread by convention rather than by the type system (P15).

import Foundation
import SPFNClient
import SPFNCore
import SPFNGenerated

/// What one seeded call does.
enum Answer: Sendable
{
    /// Answers, with the fixture's own device.
    case ok

    /// Refuses, as a server that cannot find the code would.
    case refuse
}

actor FakeDeviceApprovalService: DeviceApprovalService
{
    /// How many reads have been asked for. Cell u14 is the one that counts it.
    private(set) var lookupCount: Int = 0

    private(set) var approveCount: Int = 0

    private(set) var denyCount: Int = 0

    private var writeCount: Int = 0

    private let lookupAnswers: [Answer]
    private let writeAnswers: [Answer]
    private let pause: @Sendable () async -> Void

    /// - Parameter lookupAnswers: what each successive read does. The last entry repeats,
    ///   so `[.ok]` is "every read answers" and `[.ok, .refuse]` is "the first answers and
    ///   every later one refuses" — which is the difference between reaching the detail
    ///   screen and never leaving the entry one.
    /// - Parameter writeAnswers: the same, for `approve` and `deny`, counted together
    ///   because a screen performs one of them and then closes.
    /// - Parameter pause: what a call waits on before answering.
    init(
        lookupAnswers: [Answer],
        writeAnswers: [Answer] = [.ok],
        pause: @escaping @Sendable () async -> Void = {}
    )
    {
        self.lookupAnswers = lookupAnswers
        self.writeAnswers = writeAnswers
        self.pause = pause
    }

    func lookup(_ request: SPFNDeviceAuthInfoRequest) async throws -> SPFNDeviceAuthInfoResponse
    {
        let answer = answerAt(lookupCount)
        lookupCount += 1
        await pause()
        guard answer == .ok
        else
        {
            throw notFound(request.userCode)
        }
        return device()
    }

    func approve(_ request: SPFNApproveDeviceAuthRequest) async throws -> SPFNDeviceAuthInfoResponse
    {
        approveCount += 1
        let answer = nextWrite()
        await pause()
        guard answer == .ok
        else
        {
            throw notFound(request.userCode)
        }
        return device()
    }

    func deny(_ request: SPFNDenyDeviceAuthRequest) async throws
    {
        denyCount += 1
        let answer = nextWrite()
        await pause()
        guard answer == .ok
        else
        {
            throw notFound(request.userCode)
        }
    }

    private func answerAt(_ index: Int) -> Answer
    {
        index < lookupAnswers.count ? lookupAnswers[index] : (lookupAnswers.last ?? .ok)
    }

    private func nextWrite() -> Answer
    {
        let answer = writeCount < writeAnswers.count ? writeAnswers[writeCount] : (writeAnswers.last ?? .ok)
        writeCount += 1
        return answer
    }

    /// The one device every fixture describes. Nothing here is a credential.
    private func device() -> SPFNDeviceAuthInfoResponse
    {
        SPFNDeviceAuthInfoResponse(
            deviceName: "Example device",
            platform: .ios,
            fingerprintPrefix: "ab12cd34",
            requestedAtMillis: 0,
            expiresAtMillis: 0
        )
    }

    /// The refusal a server gives for a code it does not hold, as the client would have
    /// classified it: a `.server` failure carrying the contract's own code and an envelope.
    private func notFound(_ userCode: String) -> SPFNClientError
    {
        .server(
            SPFNServerFailure(
                code: .deviceAuthNotFoundError,
                httpStatus: SPFNGeneratedErrorCode.deviceAuthNotFoundError.httpStatus,
                envelope: SPFNErrorEnvelope(
                    code: SPFNGeneratedErrorCode.deviceAuthNotFoundError.rawValue,
                    message: "no device is waiting on that code",
                    requestID: "fixture-\(userCode.count)"
                )
            )
        )
    }
}
