// SPFN Mobile — `enroll` sends the person's name as contract 0.13.5's `profile.name`.
//
// One test per row of the E1–E8 table in the w-20mw8 brief. Every body is compared
// against Contracts/fixtures/enrollment/enrollment.json, whose `oauthNativeProfile`
// vectors derive-expected-values.py wrote by hand from the contract, so the two
// platforms agreeing means both agree with a third implementation (P10).
//
// SpfnEnrollProfileNameTest.kt is the counterpart and uses the same row names.

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNEnrollProfileNameTests: XCTestCase
{
    private let baseURL = "https://example.invalid"
    private let personName = "김민수"
    private let enrolled = "{\"mfaRequired\":false,\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"

    // MARK: - E1–E4: what the body carries

    func test_profileName_E1_theTokenOnlyFormSendsTodaysBytes() async throws
    {
        let token = try fixtureToken()
        let sent = try await enrolledBody { lifecycle in
            try await lifecycle.enroll(provider: "google") { _ in token }
        }

        XCTAssertEqual(sent, try oauthNative().text("canonical"), "the token-only body is the pre-0.13.5 fixture, byte for byte")
        XCTAssertFalse(sent.contains("\"profile\""))
    }

    func test_profileName_E2_aNameRidesAsProfileName() async throws
    {
        let token = try fixtureToken()
        let personName = self.personName
        let sent = try await enrolledBody { lifecycle in
            try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: token, displayName: personName) }
        }

        XCTAssertEqual(sent, try namedVector(personName).text("canonical"))
        XCTAssertTrue(sent.contains("\"profile\":{\"name\":\"김민수\"}"))
    }

    func test_profileName_E3_noNameSendsNoProfileKey() async throws
    {
        let token = try fixtureToken()
        let sent = try await enrolledBody { lifecycle in
            try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: token, displayName: nil) }
        }

        XCTAssertEqual(sent, try oauthNative().text("canonical"), "never profile: {} and never name: null")
    }

    func test_profileName_E4_aBlankNameSendsNoProfileKey() async throws
    {
        let token = try fixtureToken()
        let blanks = try profileVectors().list("blank").map { try $0.string() }
        XCTAssertFalse(blanks.isEmpty)

        for blank in blanks
        {
            let sent = try await enrolledBody { lifecycle in
                try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: token, displayName: blank) }
            }
            XCTAssertEqual(sent, try oauthNative().text("canonical"), "blank name \(blank.unicodeScalars.map { $0.value })")
        }
    }

    // MARK: - E5: the contract states no length, so none is invented

    /// 0.13.5 declares `name` as an optional string and nothing more, and upstream's
    /// export says "No length is declared because the route enforces none". So a long
    /// name is sent, and a refusal of it is the server's, read exactly as the same
    /// refusal of a body without a name.
    func test_profileName_E5_noLengthIsInventedAndARefusalIsClassifiedAsToday() async throws
    {
        let long = String(repeating: "가", count: 4_096)
        let refusal = ExecuteFixtures.errorEnvelope(code: "ValidationError")

        let (withName, sent) = try await refusedEnrollment(answer: .success(.json(400, refusal)), displayName: long)
        let (withoutName, _) = try await refusedEnrollment(answer: .success(.json(400, refusal)), displayName: nil)

        XCTAssertTrue(sent.contains(long), "the name is sent whole")
        XCTAssertEqual(withName as? SPFNClientError, withoutName as? SPFNClientError)
        guard case .server(let failure) = withName as? SPFNClientError
        else
        {
            return XCTFail("expected the server's refusal, got \(String(describing: withName))")
        }
        XCTAssertEqual(failure.code.rawValue, "ValidationError")
    }

    // MARK: - E6: nothing on the enroll path carries the name

    func test_profileName_E6_noErrorOrDescriptionCarriesTheName() async throws
    {
        let answers: [Result<SPFNTransportResponse, any Error>] = [
            .success(.json(400, ExecuteFixtures.errorEnvelope(code: "ValidationError"))),
            .success(.json(500, ExecuteFixtures.errorEnvelope(code: "Error"))),
            .failure(SPFNTransportError.timedOut),
            .success(.json(200, "{}")),
            .success(.json(200, "not-json")),
            .success(.json(202, "{\"mfaRequired\":true}")),
            .success(.json(200, "{\"mfaRequired\":false,\"keyId\":\"key-other-9999\",\"userId\":\"user-test-0001\"}")),
        ]
        for answer in answers
        {
            let (thrown, _) = try await refusedEnrollment(answer: answer, displayName: personName)
            assertNamesNoOne(thrown)
        }

        let lifecycle = try makeLifecycle(ScriptedTransport([]), store: InMemoryKeyStore())
        let personName = self.personName
        let missing = await failure {
            _ = try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: "", displayName: personName) }
        }
        XCTAssertEqual(missing as? SPFNKeyLifecycleError, .idTokenMissing)
        assertNamesNoOne(missing)

        let signIn = SPFNSocialSignIn(idToken: "idtoken-test", displayName: personName)
        var dumped = ""
        dump(signIn, to: &dumped)
        for text in [String(describing: signIn), String(reflecting: signIn), "\(signIn)", dumped]
        {
            XCTAssertFalse(text.contains(personName), text)
            XCTAssertFalse(text.contains("idtoken-test"), text)
        }
    }

    func test_profileName_E6_theStoredRecordDoesNotCarryTheName() async throws
    {
        let store = InMemoryKeyStore()
        let lifecycle = try makeLifecycle(ScriptedTransport([.success(.json(200, enrolled))]), store: store)
        let personName = self.personName
        _ = try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: "idtoken-test", displayName: personName) }

        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        var dumped = ""
        dump(active, to: &dumped)
        XCTAssertFalse(dumped.contains(personName))
        XCTAssertFalse(String(reflecting: active).contains(personName))
    }

    // MARK: - E7: a second factor ends the same way with a name as without

    func test_profileName_E7_aSecondFactorAnswerIsTheSameRefusal() async throws
    {
        let challenge = "{\"mfaRequired\":true,\"challenge\":{\"secret\":\"private-challenge\",\"expiresAtMillis\":1750000060000}}"
        let store = InMemoryKeyStore()
        let transport = ScriptedTransport([.success(.json(202, challenge))])
        let lifecycle = try makeLifecycle(transport, store: store)
        let personName = self.personName

        let thrown = await failure {
            _ = try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: "idtoken-test", displayName: personName) }
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .secondFactorRequired)
        XCTAssertEqual(thrown?.localizedDescription, "this SDK version does not finish a second-factor sign-in")
        let received = await transport.received
        XCTAssertTrue(String(decoding: received.first?.body ?? [], as: UTF8.self).contains("\"profile\""), "the name was sent")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    // MARK: - E8: the same names make the same bytes on both platforms

    /// Every sendable name in the shared table, the Korean one, a supplementary-plane
    /// emoji with escapes around it, and the three characters one platform's own
    /// whitespace test calls blank and the server does not.
    func test_profileName_E8_everySharedVectorIsReproducedByteForByte() async throws
    {
        let token = try fixtureToken()
        let vectors = try profileVectors().list("named").map { try $0.object() }
        XCTAssertEqual(vectors.count, 5)

        for vector in vectors
        {
            let displayName = try vector.text("displayName")
            let sent = try await enrolledBody { lifecycle in
                try await lifecycle.enrollWithSignIn(provider: "google") { _ in SPFNSocialSignIn(idToken: token, displayName: displayName) }
            }
            XCTAssertEqual(sent, try vector.text("canonical"), "name \(displayName.unicodeScalars.map { $0.value })")
            XCTAssertEqual(SPFNDigest.sha256Hex(Array(sent.utf8)), try vector.text("sha256"))
        }
    }

    // MARK: - Assembly

    /// Runs one enrollment the server accepts and answers the body it sent.
    private func enrolledBody(
        _ enroll: (SPFNKeyLifecycle) async throws -> SPFNEnrollmentResult
    ) async throws -> String
    {
        let transport = ScriptedTransport([.success(.json(200, enrolled))])
        let lifecycle = try makeLifecycle(transport, store: InMemoryKeyStore())
        _ = try await enroll(lifecycle)
        let received = await transport.received
        XCTAssertEqual(received.count, 1)
        return String(decoding: try XCTUnwrap(received.first?.body), as: UTF8.self)
    }

    /// Runs one enrollment that ends in `answer` and answers what it threw and sent.
    private func refusedEnrollment(
        answer: Result<SPFNTransportResponse, any Error>,
        displayName: String?
    ) async throws -> (thrown: (any Error)?, sent: String)
    {
        let transport = ScriptedTransport([answer])
        let lifecycle = try makeLifecycle(transport, store: InMemoryKeyStore())
        let thrown = await failure {
            _ = try await lifecycle.enrollWithSignIn(provider: "google")
            { _ in
                SPFNSocialSignIn(idToken: "idtoken-test", displayName: displayName)
            }
        }
        let received = await transport.received
        return (thrown, String(decoding: received.first?.body ?? [], as: UTF8.self))
    }

    private func assertNamesNoOne(_ thrown: (any Error)?, file: StaticString = #filePath, line: UInt = #line)
    {
        let error = thrown as Any
        var dumped = ""
        dump(error, to: &dumped)
        let texts = [
            String(describing: error),
            String(reflecting: error),
            thrown?.localizedDescription ?? "",
            dumped,
        ]
        for text in texts
        {
            XCTAssertFalse(text.contains(personName), text, file: file, line: line)
        }
    }

    private func makeLifecycle(_ transport: ScriptedTransport, store: InMemoryKeyStore) throws -> SPFNKeyLifecycle
    {
        let key = try testKey()
        let clock = FakeClock(1_750_000_000_000)
        return SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: baseURL,
            clock: clock,
            proofClock: clock,
            newKeyID: { "key-test-0001" },
            makeKey: { _ in key }
        )
    }

    private func testKey() throws -> SPFNCustodyKey
    {
        let keyPair = try WireFixtures.wire()["testKeyPair"].orFail("testKeyPair").object()
        guard let der = Data(base64Encoded: try keyPair.text("privateKeyPkcs8Base64"))
        else
        {
            throw FixtureFailure.shape("not base64")
        }
        return try SPFNCustodyKey.software(keyID: try keyPair.text("keyId"), privateKeyDer: [UInt8](der))
    }

    private func enrollment() throws -> [String: SPFNCanonicalValue]
    {
        try WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json").object()
    }

    private func oauthNative() throws -> [String: SPFNCanonicalValue]
    {
        try enrollment()["oauthNative"].orFail("oauthNative").object()
    }

    private func profileVectors() throws -> [String: SPFNCanonicalValue]
    {
        try enrollment()["oauthNativeProfile"].orFail("oauthNativeProfile").object()
    }

    private func namedVector(_ name: String) throws -> [String: SPFNCanonicalValue]
    {
        let vectors = try profileVectors().list("named").map { try $0.object() }
        return try XCTUnwrap(vectors.first { (try? $0.text("displayName")) == name })
    }

    private func fixtureToken() throws -> String
    {
        try oauthNative()["value"].orFail("value").object().text("idToken")
    }

    private func failure(_ body: () async throws -> Void) async -> (any Error)?
    {
        do
        {
            try await body()
        }
        catch
        {
            return error
        }
        XCTFail("expected a throw")
        return nil
    }
}
