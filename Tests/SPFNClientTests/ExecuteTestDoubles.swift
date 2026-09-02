// SPFN Mobile — what the execute suite sends requests at.
//
// Two doubles, because the suite asks two different kinds of question. `ScriptedTransport`
// answers by position and settles what one call does. `RevokingServer` answers by what the
// request presented, which is the only way to ask what several concurrent calls do to each
// other: under a positional script, whichever task happened to run first would consume an
// answer meant for another one and the test would pass or fail by scheduling.
//
// ExecuteTestDoubles.kt is the counterpart.

import Foundation
import SPFNClient
import SPFNCore
import SPFNGenerated

// MARK: - Contract bodies, spelled as a server would put them on the wire

enum ExecuteFixtures
{
    /// A provider over the fixture test keypair. Not a credential; see
    /// Contracts/fixtures/proof/proof-input.json — the private half is published there
    /// on purpose. Read from the fixture rather than restated, so the suite cannot
    /// silently sign with something else.
    static func syntheticProvider(clientID: String = SessionFixtureValues.clientID) throws -> SPFNSoftwareKeyProvider
    {
        let keyPair = try WireFixtures.wire()["testKeyPair"].orFail("testKeyPair").object()
        guard let privateKeyDer = Data(base64Encoded: try keyPair.text("privateKeyPkcs8Base64"))
        else
        {
            throw FixtureFailure.shape("privateKeyPkcs8Base64 is not base64")
        }
        return try SPFNSoftwareKeyProvider(
            clientID: clientID,
            keyID: try keyPair.text("keyId"),
            privateKeyDer: [UInt8](privateKeyDer)
        )
    }

    /// The public half of the fixture keypair, for verifying what a session signed.
    static func fixturePublicKeySpkiDer() throws -> [UInt8]
    {
        let keyPair = try WireFixtures.wire()["testKeyPair"].orFail("testKeyPair").object()
        guard let spki = Data(base64Encoded: try keyPair.text("publicKeySpkiBase64"))
        else
        {
            throw FixtureFailure.shape("publicKeySpkiBase64 is not base64")
        }
        return [UInt8](spki)
    }

    static func handshakeResponse(sessionID: String, expiringAt millis: Int64) -> String
    {
        "{\"expiresAtMillis\":\(millis),\"sessionId\":\"\(sessionID)\"}"
    }

    /// An error envelope in canonical form. Keys sort as the canonical encoder sorts them.
    static func errorEnvelope(
        code: String,
        message: String = "refused",
        requestID: String = "req-test-0001"
    ) -> String
    {
        "{\"error\":{\"code\":\"\(code)\",\"message\":\"\(message)\",\"requestId\":\"\(requestID)\"}}"
    }

    static let echoRequest = SPFNEchoRequest(message: "hello", sequence: 7)

    static let echoResponse = SPFNEchoResponse(message: "hello", sequence: 7, serverTimeMillis: 1_750_000_000_500)

    static var echoResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(try! echoResponse.canonicalValue()), as: UTF8.self)
    }

    /// A register request for the contract's unproven class. Every value is a synthetic
    /// test constant; the "password" authenticates nothing and never meets a real endpoint.
    static let registerRequest = SPFNRegisterRequest(
        email: "enroll@example.invalid",
        phone: nil,
        verificationToken: "verify-test-0001",
        password: "password-test-0001",
        publicKey: "cHVibGljLWtleS10ZXN0",
        keyId: "key-test-0001",
        fingerprint: String(repeating: "0", count: 64),
        algorithm: .es256
    )

    static let registerResponse = SPFNRegisterResponse(
        userId: "user-test-0001",
        publicId: "public-test-0001",
        email: "enroll@example.invalid",
        phone: nil
    )

    static var registerResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(try! registerResponse.canonicalValue()), as: UTF8.self)
    }

    static let rotateRequest = SPFNRotateKeyRequest(
        publicKey: "cHVibGljLWtleS10ZXN0LTI",
        keyId: "key-test-0002",
        fingerprint: String(repeating: "1", count: 64),
        algorithm: .es256
    )

    static let rotateResponse = SPFNRotateKeyResponse(success: true, keyId: "key-test-0002")

    static var rotateResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(try! rotateResponse.canonicalValue()), as: UTF8.self)
    }

    /// Contract 0.10.0's bodyless operation. Every value is a synthetic test constant.
    static let denyRequest = SPFNDenyDeviceAuthRequest(userCode: "WDJB-MJHT")

    /// Its sibling that does declare a response, so the two branches of the reader can be
    /// asked the same questions and answer differently.
    static let approveRequest = SPFNApproveDeviceAuthRequest(userCode: "WDJB-MJHT")

    static let approveResponse = SPFNDeviceAuthInfoResponse(
        deviceName: "Test Phone",
        platform: .ios,
        fingerprintPrefix: "ab12cd34",
        requestedAtMillis: 1_750_000_000_000,
        expiresAtMillis: 1_750_000_600_000
    )

    static var approveResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(try! approveResponse.canonicalValue()), as: UTF8.self)
    }

    static let listRequest = SPFNListItemsRequest(limit: 2, cursor: "cursor-1")

    static let listResponse = SPFNListItemsResponse(
        items: [SPFNItem(id: "item-1", name: "first", updatedAtMillis: 1_750_000_000_100)],
        nextCursor: "cursor-2"
    )

    static var listResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(try! listResponse.canonicalValue()), as: UTF8.self)
    }
}

// MARK: - The three calls

// Hand-written here even though `SPFNGeneratedCalls` now ships one descriptor per
// operation. These are not a copy of it: this suite holds the one execute path to what it
// does with whatever descriptor it is handed, and `undeclared` below names an auth class
// no contract declares, so the generator can never emit it. Asserting against generated
// values instead would make the client's own regression suite move whenever the contract
// does, and would leave the refusal that matters most untestable.

enum ExecuteCalls
{
    static let echo = SPFNCall<SPFNEchoRequest, SPFNEchoResponse>(
        operation: SPFNGeneratedOperations.echoSend,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNEchoResponse(canonical: $0) }
    )

    static let list = SPFNCall<SPFNListItemsRequest, SPFNListItemsResponse>(
        operation: SPFNGeneratedOperations.itemsList,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNListItemsResponse(canonical: $0) }
    )

    /// The handshake, described the same way as the others so the suite can show that
    /// `execute` refuses it on the operation rather than on how it was described.
    static let handshake = SPFNCall<SPFNHandshakeRequest, SPFNHandshakeResponse>(
        operation: SPFNGeneratedOperations.authClientProofHandshake,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNHandshakeResponse(canonical: $0) }
    )

    /// The contract's unproven class, as generated: no proof, no session, no handshake.
    static let register = SPFNCall<SPFNRegisterRequest, SPFNRegisterResponse>(
        operation: SPFNGeneratedOperations.authEnrollRegister,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRegisterResponse(canonical: $0) }
    )

    /// Proven but session-free: the rotation operation authenticates with the old key
    /// alone, so it carries every proof header and never a session header.
    static let rotate = SPFNCall<SPFNRotateKeyRequest, SPFNRotateKeyResponse>(
        operation: SPFNGeneratedOperations.authKeysRotate,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRotateKeyResponse(canonical: $0) }
    )

    /// The contract's one operation that declares no response type. Built through the
    /// factory rather than by hand: there is nothing to decode, and the factory is where
    /// that decision is written down once.
    static let deny = SPFNCall<SPFNDenyDeviceAuthRequest, SPFNNoResponse>.noResponse(
        operation: SPFNGeneratedOperations.authDeviceDeny,
        encode: { try $0.canonicalValue() }
    )

    /// The same flow's operation that does declare a response, so the regression guard
    /// asks a declared-response operation the questions the bodyless one is asked.
    static let approve = SPFNCall<SPFNApproveDeviceAuthRequest, SPFNDeviceAuthInfoResponse>(
        operation: SPFNGeneratedOperations.authDeviceApprove,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNDeviceAuthInfoResponse(canonical: $0) }
    )

    /// An operation naming an auth class the contract does not declare. The descriptor
    /// is hand-built because the generator can never emit one — that is the point.
    static let undeclared = SPFNCall<SPFNEchoRequest, SPFNEchoResponse>(
        operation: SPFNOperation(
            id: "mystery.op",
            method: "POST",
            path: "/v1/mystery",
            authProfile: "mysteryV9",
            requiresSession: true,
            declaresResponse: true
        ),
        encode: { try $0.canonicalValue() },
        decode: { try SPFNEchoResponse(canonical: $0) }
    )
}

// MARK: - A server that answers what it was shown

/// Issues a new session per handshake and refuses any request presenting a revoked one.
///
/// Deliberately not a script. The question it exists to answer — do N concurrent calls
/// meeting one revocation share one re-handshake — is a question about what the calls do
/// to each other, and any answer chosen by position would be an answer about scheduling.
actor RevokingServer: SPFNTransport
{
    private let revoked: Set<String>
    private let expiresAtMillis: Int64
    private let firstRound: Barrier?
    private var issued = 0
    private var operations = 0

    private(set) var received: [SPFNTransportRequest] = []

    /// - Parameter holdingFirst: how many operation requests are held until that many
    ///   have arrived. Without it a concurrency test proves nothing: one task can finish
    ///   its whole retry before another has sent anything, and then the calls never met.
    init(
        revoking revoked: Set<String>,
        expiresAtMillis: Int64 = SessionFixtureValues.expiresAtMillis,
        holdingFirst: Int = 0
    )
    {
        self.revoked = revoked
        self.expiresAtMillis = expiresAtMillis
        self.firstRound = holdingFirst > 0 ? Barrier(holdingFirst) : nil
    }

    /// How many of the recorded calls opened a session.
    var handshakes: Int
    {
        received.filter { $0.url.hasSuffix(SPFNGeneratedOperations.authClientProofHandshake.path) }.count
    }

    var callCount: Int
    {
        received.count
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        received.append(request)

        guard !request.url.hasSuffix(SPFNGeneratedOperations.authClientProofHandshake.path)
        else
        {
            issued += 1
            return .json(200, ExecuteFixtures.handshakeResponse(
                sessionID: "session-\(issued)",
                expiringAt: expiresAtMillis
            ))
        }

        operations += 1
        if let firstRound, operations <= firstRound.width
        {
            await firstRound.arriveAndWait()
        }

        let presented = request.headers.first { $0.0 == SPFNWireHeaders.session }?.1 ?? ""
        guard !revoked.contains(presented)
        else
        {
            return .json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))
        }
        return .json(200, ExecuteFixtures.echoResponseBody)
    }
}

/// Holds every arrival until `width` of them have arrived, then releases all of them.
actor Barrier
{
    let width: Int

    private var arrived = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(_ width: Int)
    {
        self.width = width
    }

    func arriveAndWait() async
    {
        arrived += 1
        guard arrived < width
        else
        {
            for continuation in waiting
            {
                continuation.resume()
            }
            waiting = []
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiting.append(continuation)
        }
    }
}

// MARK: - Cancelling a call from inside it

/// Hands a task to whoever asks for it, however the two happen to be ordered.
///
/// The cancellation test has to cancel the very task it is about to await, from inside a
/// transport call that task made. Reaching for the handle directly is a race the test
/// would lose about half the time; waiting for it here is not.
actor TaskHolder
{
    private var task: Task<Void, any Error>?
    private var waiting: [CheckedContinuation<Task<Void, any Error>, Never>] = []

    func hold(_ task: Task<Void, any Error>)
    {
        self.task = task
        for continuation in waiting
        {
            continuation.resume(returning: task)
        }
        waiting = []
    }

    func cancelWhenHeld() async
    {
        if let task
        {
            task.cancel()
            return
        }
        let held = await withCheckedContinuation { (continuation: CheckedContinuation<Task<Void, any Error>, Never>) in
            waiting.append(continuation)
        }
        held.cancel()
    }
}
