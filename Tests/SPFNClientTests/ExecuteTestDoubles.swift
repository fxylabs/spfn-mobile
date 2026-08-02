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
    /// The synthetic key the wire vectors are signed with. Not a credential; see
    /// Contracts/fixtures/MANIFEST.json.
    static func syntheticProvider(clientID: String = SessionFixtureValues.clientID) throws -> SPFNInMemoryKeyProvider
    {
        let key = try WireFixtures.wire()["syntheticKey"].orFail("syntheticKey").object().text("keyUtf8")
        return SPFNInMemoryKeyProvider(
            clientID: clientID,
            keyID: SessionFixtureValues.keyID,
            key: Array(key.utf8)
        )
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
        String(decoding: SPFNCanonicalJSON.encode(echoResponse.canonicalValue), as: UTF8.self)
    }

    static let listRequest = SPFNListItemsRequest(limit: 2, cursor: "cursor-1")

    static let listResponse = SPFNListItemsResponse(
        items: [SPFNItem(id: "item-1", name: "first", updatedAtMillis: 1_750_000_000_100)],
        nextCursor: "cursor-2"
    )

    static var listResponseBody: String
    {
        String(decoding: SPFNCanonicalJSON.encode(listResponse.canonicalValue), as: UTF8.self)
    }
}

// MARK: - The three calls

// Hand-written here rather than shipped: what the library owes is one execute path, and
// the per-operation descriptors that ride on it are the generator's job, not this change
// set's. Writing them in the suite is what keeps that boundary visible.

enum ExecuteCalls
{
    static let echo = SPFNCall<SPFNEchoRequest, SPFNEchoResponse>(
        operation: SPFNGeneratedOperations.echoSend,
        encode: { $0.canonicalValue },
        decode: { try SPFNEchoResponse(canonical: $0) }
    )

    static let list = SPFNCall<SPFNListItemsRequest, SPFNListItemsResponse>(
        operation: SPFNGeneratedOperations.itemsList,
        encode: { $0.canonicalValue },
        decode: { try SPFNListItemsResponse(canonical: $0) }
    )

    /// The handshake, described the same way as the others so the suite can show that
    /// `execute` refuses it on the operation rather than on how it was described.
    static let handshake = SPFNCall<SPFNHandshakeRequest, SPFNHandshakeResponse>(
        operation: SPFNGeneratedOperations.authClientProofHandshake,
        encode: { $0.canonicalValue },
        decode: { try SPFNHandshakeResponse(canonical: $0) }
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
