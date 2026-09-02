// SPFN Mobile — an operation that declares no response body.
//
// Contract 0.10.0 added `auth.device.deny`, which names no responseType, and stated in
// `restOperations.responseBody` what that means: "An operation that declares no
// responseType answers 204 with an empty body and there is nothing to decode".
//
// Six cells, closed from that sentence before the reader was written and named here one to
// one. Three ask what a bodyless operation accepts, one asks that it still has refusals,
// and two are the regression guard: the operations that do declare a response must not
// have acquired the new branch's tolerance. The expected values come from the contract
// text quoted above, not from what the reader happens to do.
//
// The cells run through `SPFNClient.execute`, not against the reader directly, because
// what the change set owes is that the one reader on this platform enforces the rule for
// every path into it.
//
// SpfnNoResponseOperationTest.kt is the counterpart and uses the same cell names.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNNoResponseOperationTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    /// The descriptor is what the reader consults, so the suite states what the contract
    /// says about these two operations before asking what the reader does with them.
    func testTheGeneratedDescriptorsCarryWhatTheContractDeclares()
    {
        XCTAssertFalse(
            SPFNGeneratedOperations.authDeviceDeny.declaresResponse,
            "auth.device.deny names no responseType in contract 0.10.0"
        )
        XCTAssertTrue(
            SPFNGeneratedOperations.authDeviceApprove.declaresResponse,
            "auth.device.approve answers with DeviceAuthInfoResponse"
        )
    }

    // MARK: - N1 … N4: the operation declares no response

    /// N1. 204 with an empty body is the contract's answer, and it succeeds.
    func testN1NoResponseOperationAcceptsNoContentWithAnEmptyBody() async throws
    {
        let transport = ScriptedTransport([.success(.json(204, ""))])
        let client = try makeClient(transport, nonces: ["nonce-deny"])

        let answered: SPFNNoResponse = try await client.execute(
            ExecuteCalls.deny,
            request: ExecuteFixtures.denyRequest
        )

        XCTAssertEqual(answered, SPFNNoResponse.value)
        let recorded = await transport.received
        XCTAssertEqual(
            recorded.last?.url,
            baseURL + SPFNGeneratedOperations.authDeviceDeny.path,
            "the one request is the deny itself"
        )
    }

    /// N2. 204 carrying a body. There is nothing to decode, so bytes here mean the two
    /// ends disagree about the operation — refused, and the failure names the case.
    func testN2NoResponseOperationRefusesABodyOnTheNoContent() async throws
    {
        let thrown = try await denyFailing(with: .json(204, "{}"))

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.bodyOnNoResponseOperation))
    }

    /// N3. 200 with a body. A 2xx that is not 204 is not the answer the contract
    /// describes, and `{}` is not "empty" — accepting it would hide a server answering a
    /// different operation than the one that was called.
    func testN3NoResponseOperationRefusesATwoHundred() async throws
    {
        let thrown = try await denyFailing(with: .json(200, "{}"))

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notNoContentOnNoResponseOperation))
    }

    /// N4. A refusal reaches a bodyless operation exactly as it reaches every other one:
    /// the envelope is read and classified on the code it declares.
    func testN4NoResponseOperationStillReadsTheErrorEnvelope() async throws
    {
        let thrown = try await denyFailing(
            with: .json(404, ExecuteFixtures.errorEnvelope(code: "DeviceAuthNotFoundError"))
        )

        guard case SPFNClientError.server(let failure)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected a server refusal, got \(String(describing: thrown))")
        }
        XCTAssertEqual(failure.code, .deviceAuthNotFoundError)
        XCTAssertEqual(failure.httpStatus, 404)
    }

    // MARK: - N5, N6: the operation declares a response

    /// N5. The regression guard in the direction the new branch could leak into: an
    /// operation that declares a response is not allowed to accept 204 with no body just
    /// because the reader now knows what that means for a different operation.
    func testN5DeclaredResponseOperationStillRefusesAnEmptyNoContent() async throws
    {
        let thrown = try await approveFailing(with: .json(204, ""))

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .decoding(.notCanonicalJSON),
            "an empty body is not the declared response; this is the pre-0.10.0 refusal, unchanged"
        )
    }

    /// N6. And it still reads its declared response, which is the behaviour every
    /// operation but one has.
    func testN6DeclaredResponseOperationReadsItsDeclaredBody() async throws
    {
        let transport = ScriptedTransport([.success(.json(200, ExecuteFixtures.approveResponseBody))])
        let client = try makeClient(transport, nonces: ["nonce-approve"])

        let described: SPFNDeviceAuthInfoResponse = try await client.execute(
            ExecuteCalls.approve,
            request: ExecuteFixtures.approveRequest
        )

        XCTAssertEqual(described, ExecuteFixtures.approveResponse)
    }

    // MARK: - Helpers

    /// The device operations are proven but session-free — `requiresSession` is false for
    /// all five, as it is for `auth.keys.rotate` — so no handshake is sent and each script
    /// holds exactly one answer. A script that led with a handshake would have that answer
    /// consumed by the operation itself, and every cell would be about the wrong response.
    private func denyFailing(with response: SPFNTransportResponse) async throws -> (any Error)?
    {
        let transport = ScriptedTransport([.success(response)])
        let client = try makeClient(transport, nonces: ["nonce-deny"])

        do
        {
            _ = try await client.execute(
                ExecuteCalls.deny,
                request: ExecuteFixtures.denyRequest
            ) as SPFNNoResponse
            XCTFail("the response was accepted")
            return nil
        }
        catch
        {
            return error
        }
    }

    private func approveFailing(with response: SPFNTransportResponse) async throws -> (any Error)?
    {
        let transport = ScriptedTransport([.success(response)])
        let client = try makeClient(transport, nonces: ["nonce-approve"])

        do
        {
            _ = try await client.execute(
                ExecuteCalls.approve,
                request: ExecuteFixtures.approveRequest
            ) as SPFNDeviceAuthInfoResponse
            XCTFail("the response was accepted")
            return nil
        }
        catch
        {
            return error
        }
    }

    private func makeClient(
        _ transport: any SPFNTransport,
        nonces: [String] = []
    ) throws -> SPFNClient
    {
        SPFNClient(
            transport: transport,
            session: SPFNSession(
                transport: transport,
                keyProvider: try ExecuteFixtures.syntheticProvider(clientID: SessionFixtureValues.clientID),
                baseURL: baseURL,
                clock: FakeClock(SessionFixtureValues.issuedAtMillis),
                nonceGenerator: ScriptedNonceGenerator(nonces)
            ),
            timeoutMillis: 15_000
        )
    }
}
