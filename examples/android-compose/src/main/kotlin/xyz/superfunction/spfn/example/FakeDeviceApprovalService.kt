// SPFN Mobile — the service a launch fixture installs.
//
// Hand-written and outside `generated/`, because what a fixture answers is a decision
// about the CASE TABLE and not about the spec: the generator knows that `lookup` answers
// a `SpfnDeviceAuthInfoResponse`, and only the table knows that cell u13 needs the second
// one to refuse.
//
// It sends nothing and holds no transport. A refusal here is a real `SpfnClientError` of
// the same class the client would raise, because the screen models classify on that type
// and a fake that threw something else would exercise a branch no server can reach.
//
// Nothing here runs unless `SPFN_UI_FIXTURE` named a cell. See MainActivity: with no
// launch argument there is no fake at all, not a fake that happens to be idle.

package xyz.superfunction.spfn.example

import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.client.SpfnServerFailure
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.example.generated.services.DeviceApprovalService
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnKeyPlatform

/** What one seeded call does. */
enum class Answer
{
    /** Answers, with the fixture's own device. */
    OK,

    /** Refuses, as a server that cannot find the code would. */
    REFUSE
}

/**
 * The device-approval service, seeded per cell.
 *
 * @param lookupAnswers what each successive read does. The last entry repeats, so
 *   `listOf(OK)` is "every read answers" and `listOf(OK, REFUSE)` is "the first answers
 *   and every later one refuses" — which is the difference between reaching the detail
 *   screen and never leaving the entry one.
 * @param writeAnswers the same, for `approve` and `deny`, counted together because a
 *   screen performs one of them and then closes.
 * @param pause what a call waits on before answering. The app installs a delay, so an
 *   in-flight state is visible to a person; a test installs a gate it opens itself, so
 *   there is no timing in the suite at all.
 */
class FakeDeviceApprovalService(
    private val lookupAnswers: List<Answer>,
    private val writeAnswers: List<Answer> = listOf(Answer.OK),
    private val pause: suspend () -> Unit = {}
) : DeviceApprovalService
{
    /** How many reads have been asked for. Cell u14 is the one that counts it. */
    var lookupCount: Int = 0
        private set;

    var approveCount: Int = 0
        private set;

    var denyCount: Int = 0
        private set;

    private var writeCount: Int = 0;

    override suspend fun lookup(request: SpfnDeviceAuthInfoRequest): SpfnDeviceAuthInfoResponse
    {
        val answer = answerAt(lookupCount);
        lookupCount++;
        pause();
        if (answer == Answer.REFUSE)
        {
            throw notFound(request.userCode);
        }
        return device();
    }

    override suspend fun approve(request: SpfnApproveDeviceAuthRequest): SpfnDeviceAuthInfoResponse
    {
        approveCount++;
        val answer = nextWrite();
        pause();
        if (answer == Answer.REFUSE)
        {
            throw notFound(request.userCode);
        }
        return device();
    }

    override suspend fun deny(request: SpfnDenyDeviceAuthRequest)
    {
        denyCount++;
        val answer = nextWrite();
        pause();
        if (answer == Answer.REFUSE)
        {
            throw notFound(request.userCode);
        }
    }

    private fun answerAt(index: Int): Answer =
        lookupAnswers.getOrElse(index) { lookupAnswers.last() }

    private fun nextWrite(): Answer
    {
        val answer = writeAnswers.getOrElse(writeCount) { writeAnswers.last() };
        writeCount++;
        return answer;
    }

    /** The one device every fixture describes. Nothing here is a credential. */
    private fun device(): SpfnDeviceAuthInfoResponse = SpfnDeviceAuthInfoResponse(
        deviceName = "Example device",
        platform = SpfnKeyPlatform.ANDROID,
        fingerprintPrefix = "ab12cd34",
        requestedAtMillis = 0,
        expiresAtMillis = 0
    )

    /**
     * The refusal a server gives for a code it does not hold, as the client would have
     * classified it: a `Server` failure carrying the contract's own code and an envelope.
     */
    private fun notFound(userCode: String): SpfnClientError = SpfnClientError.Server(
        SpfnServerFailure(
            code = SpfnGeneratedErrorCode.DeviceAuthNotFoundError,
            httpStatus = SpfnGeneratedErrorCode.DeviceAuthNotFoundError.httpStatus,
            envelope = SpfnErrorEnvelope(
                code = SpfnGeneratedErrorCode.DeviceAuthNotFoundError.wireCode,
                message = "no device is waiting on that code",
                requestId = "fixture-${userCode.length}"
            )
        )
    )
}
