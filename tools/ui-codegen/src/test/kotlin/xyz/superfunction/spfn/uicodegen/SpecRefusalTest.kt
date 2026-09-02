// SPFN Mobile — the screen generator must refuse a spec it cannot honestly read.
//
// The style is tools/contract-codegen's BundleSectionTest: read the real spec, then break
// exactly one thing and require generation to refuse. P8 is the pattern under test — a
// generator that lets an unrecognised value fall through an else-branch does not fail, it
// emits a plausible app from a spec nobody wrote.
//
// The last two cases are not refusals. One is determinism, which is the property every
// header in the output claims; the other is discriminating power (P10): a table that did
// not move when the spec moved would be a table that proves nothing about the spec.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpecRefusalTest
{
    private val repoRoot = File("../..")
    private val specPath = "examples/ui-spec/device-approval.json"
    private val specText: String = File(repoRoot, specPath).readText(Charsets.UTF_8)

    /** Writes a mutated spec under the module's build directory and answers its repo path. */
    private fun withSpec(name: String, text: String): String
    {
        val directory = File(repoRoot, "tools/ui-codegen/build/test-specs");
        directory.mkdirs();
        File(directory, name).writeText(text);
        return "tools/ui-codegen/build/test-specs/$name";
    }

    private fun replaceOnce(needle: String, replacement: String): String
    {
        assertTrue("the spec no longer carries '$needle'", specText.contains(needle));
        return specText.replaceFirst(needle, replacement);
    }

    private fun assertRefused(name: String, text: String, expected: String)
    {
        val path = withSpec(name, text);
        try
        {
            generate(repoRoot, path);
            fail("generation accepted a spec it must refuse: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    @Test
    fun `a spec pinned to another bundle is refused`()
    {
        assertRefused(
            "digest.json",
            replaceOnce("\"manifestSha256\": \"", "\"manifestSha256\": \"00"),
            "spec digest mismatch"
        );
    }

    @Test
    fun `an operation the contract does not declare is refused`()
    {
        assertRefused(
            "operation.json",
            replaceOnce("authDeviceInfo", "authDeviceInformation"),
            "which the pinned contract does not declare"
        );
    }

    @Test
    fun `a then that pushes a screen outside the flow is refused`()
    {
        assertRefused(
            "then.json",
            replaceOnce("\"push\": \"reviewDevice\"", "\"push\": \"somewhereElse\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a start that is not a screen is refused`()
    {
        assertRefused(
            "start.json",
            replaceOnce("\"start\": \"enterCode\"", "\"start\": \"nowhere\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a call naming a method no service declares is refused`()
    {
        assertRefused(
            "call.json",
            replaceOnce("deviceApproval.approve", "deviceApproval.accept"),
            "which no service declares"
        );
    }

    /**
     * The three unknown-key refusals, one per depth an optional key lives at.
     *
     * `useCase` is the one a reviewer wrote by hand: it is the correct spelling of an
     * English compound and the wrong spelling of this spec's key, so it reads right, parses
     * right, and emits a screen with no use-case layer. The other two are the same mistake
     * inside an action and inside a `then`, which is where the remaining optional keys are.
     */
    @Test
    fun `a key the generator does not read is refused, by its path`()
    {
        assertRefused(
            "screen-key.json",
            replaceOnce("\"usecase\": true", "\"useCase\": true"),
            "screens.reviewDevice.useCase is not a key this generator reads"
        );

        assertRefused(
            "action-key.json",
            replaceOnce(
                "\"retry\":   { \"call\": \"deviceApproval.lookup\" }",
                "\"retry\":   { \"call\": \"deviceApproval.lookup\", \"onFailure\": \"pop\" }"
            ),
            "screens.reviewDevice.actions.retry.onFailure is not a key this generator reads"
        );

        assertRefused(
            "then-key.json",
            replaceOnce(
                "\"then\": { \"push\": \"reviewDevice\" }",
                "\"then\": { \"push\": \"reviewDevice\", \"animated\": true }"
            ),
            "screens.enterCode.actions.submit.then.animated is not a key this generator reads"
        );
    }

    /**
     * The screen shapes SCHEMA.md permits and the worked example does not have.
     *
     * `device-approval.json` has one service and gives every screen an action that calls
     * it, so it cannot say what the emitters do with a screen that calls nothing or with
     * one whose actions span two services. The fixture beside it keeps the skeleton the
     * case rules cover and adds exactly those two shapes; the assertions below are on the
     * EMITTED TEXT, because no app is built from it — the compilers that read these two
     * languages are the example apps' own, and they read the worked example.
     */
    private val shapesSpec = "tools/ui-codegen/src/test/resources/screen-shapes.json"

    private fun assertEmits(generated: Map<String, String>, path: String, expected: String)
    {
        val content = generated[path] ?: fail("the generator wrote no $path") as String;
        assertTrue("$path does not carry:\n$expected\n\nit carries:\n$content", content.contains(expected));
    }

    /**
     * A sourced screen whose actions call nothing is generable, and so is one with no
     * service at all. Both threw before: the injected service was chosen with a
     * `firstNotNullOf` over the actions' calls, which has nothing to answer with on a
     * screen whose actions only navigate.
     */
    @Test
    fun `a screen with no service-calling action still generates`()
    {
        val generated = generate(repoRoot, shapesSpec);

        assertEmits(
            generated,
            "${KotlinEmitter.ROOT}/screens/AuditDeviceModel.kt",
            "class AuditDeviceModel(\n" +
                "    private val deviceAudit: DeviceAuditService,\n" +
                "    private val flow: Flow<ApproveDeviceRoute>,\n" +
                "    private val userCode: String\n" +
                ")"
        );
        assertEmits(
            generated,
            "${SwiftEmitter.ROOT}/Screens/AuditDeviceModel.swift",
            "    public init(\n" +
                "        deviceAudit: any DeviceAuditService,\n" +
                "        flow: Flow<ApproveDeviceRoute>,\n" +
                "        userCode: String\n" +
                "    )"
        );

        // And a screen that names no service at all is given none, on either platform.
        assertEmits(generated, "${KotlinEmitter.ROOT}/screens/LeafletModel.kt", "class LeafletModel(\n    private val flow: Flow<ApproveDeviceRoute>\n)");
        assertEmits(generated, "${SwiftEmitter.ROOT}/Screens/LeafletModel.swift", "    public init(\n        flow: Flow<ApproveDeviceRoute>\n    )");
        assertEmits(generated, "${KotlinEmitter.ROOT}/AppContainer.kt", "LeafletModel(approveDeviceFlow);");
        assertEmits(generated, "${SwiftEmitter.ROOT}/AppContainer.swift", "LeafletModel(flow: approveDeviceFlow)");
    }

    /**
     * A screen whose actions span two services takes two of them, mirrored on both
     * platforms and passed by `AppContainer`. One injected service could not represent
     * this at all: `deny` would have been called on the service `approve` came from.
     */
    @Test
    fun `a screen calling two services takes two of them on both platforms`()
    {
        val generated = generate(repoRoot, shapesSpec);

        assertEmits(
            generated,
            "${KotlinEmitter.ROOT}/screens/ReviewDeviceModel.kt",
            "class ReviewDeviceModel(\n" +
                "    private val useCase: ReviewDeviceUseCase,\n" +
                "    private val deviceApproval: DeviceApprovalService,\n" +
                "    private val deviceAudit: DeviceAuditService,\n" +
                "    private val flow: Flow<ApproveDeviceRoute>,\n" +
                "    private val userCode: String\n" +
                ")"
        );
        assertEmits(
            generated,
            "${KotlinEmitter.ROOT}/screens/ReviewDeviceModel.kt",
            "deviceAudit.deny(SpfnDenyDeviceAuthRequest(userCode = userCode));"
        );
        assertEmits(
            generated,
            "${SwiftEmitter.ROOT}/Screens/ReviewDeviceModel.swift",
            "    private let deviceApproval: any DeviceApprovalService\n" +
                "    private let deviceAudit: any DeviceAuditService\n"
        );
        assertEmits(
            generated,
            "${SwiftEmitter.ROOT}/Screens/ReviewDeviceModel.swift",
            "try await deviceAudit.deny(SPFNDenyDeviceAuthRequest(userCode: userCode))"
        );

        assertEmits(
            generated,
            "${KotlinEmitter.ROOT}/AppContainer.kt",
            "ReviewDeviceModel(DefaultReviewDeviceUseCase(deviceApproval), deviceApproval, deviceAudit, approveDeviceFlow, userCode);"
        );
        assertEmits(
            generated,
            "${SwiftEmitter.ROOT}/AppContainer.swift",
            "ReviewDeviceModel(useCase: DefaultReviewDeviceUseCase(service: deviceApproval), deviceApproval: deviceApproval, " +
                "deviceAudit: deviceAudit, flow: approveDeviceFlow, userCode: userCode)"
        );
    }

    @Test
    fun `generation is a pure function of its two inputs`()
    {
        val first = generate(repoRoot, specPath);
        val second = generate(repoRoot, specPath);
        assertEquals("the two runs wrote different files", first.keys, second.keys);
        first.forEach { (path, content) ->
            assertEquals("$path differs between two runs of the same generator", content, second[path]);
        };
    }

    /**
     * P21: every element a runner taps carries its own minimum touch target.
     *
     * A control shorter than the platform minimum is reachable only through a hit area
     * larger than itself, and the hit areas of stacked controls then overlap. Compose
     * reported `enterCode.cancel` at a rectangle whose centre lay inside
     * `enterCode.userCode`, so cell u5's tap opened the keyboard and the flow never closed.
     * Nothing on a device caught it before Maestro did, and nothing on this host can catch
     * it at all except a reader of the emitted text — which is what this is.
     *
     * The rule is read as "the line after the selector sizes the element", because that is
     * the shape both emitters write and the shape a careless edit breaks.
     */
    @Test
    fun `every emitted control and field carries a minimum touch target`()
    {
        val generated = generate(repoRoot, specPath);
        assertSized(generated, ".kt", ".testTag(", ".heightIn(min = TouchTarget)");
        assertSized(generated, ".swift", ".accessibilityIdentifier(", ".frame(minHeight: touchTarget)");
    }

    /**
     * Requires [sizing] on the line after every [selector] in the generated view files.
     *
     * Indentation is stripped before both reads, because the two emitters indent a field
     * and a control differently and the rule is about neither.
     *
     * Floored rather than merely satisfied: a read that found no selector would pass the
     * loop while proving nothing (P7), so what was read is counted. Seven elements carry a
     * selector today — three on `enterCode`, four on `reviewDevice`.
     */
    private fun assertSized(generated: Map<String, String>, suffix: String, selector: String, sizing: String)
    {
        var found = 0;
        generated.filterKeys { (it.contains("/Views/") || it.contains("/views/")) && it.endsWith(suffix) }
            .forEach { (path, content) ->
                val lines = content.lines().map { it.trim() };
                lines.forEachIndexed { index, line ->
                    if (line.startsWith(selector))
                    {
                        found++;
                        assertEquals(
                            "$path:${index + 1} names a control but the next line does not size it",
                            sizing,
                            lines.getOrNull(index + 1)
                        );
                    }
                };
            };
        assertEquals("the generated $suffix views did not name the elements this reads", 7, found);
    }

    /**
     * P10: the table has to move when the behaviour it describes moves.
     *
     * `approve`'s `then` goes from `close` to `pop`, which changes what the stack is after
     * the write. A table that still read `stack=0` for that cell would be a table derived
     * from something other than the spec.
     */
    @Test
    fun `changing one then changes at least one cell`()
    {
        val table = "examples/ui-spec/generated/device-approval.cases.json";
        val before = generate(repoRoot, specPath).getValue(table);
        val mutated = withSpec(
            "discriminate.json",
            replaceOnce(
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"close\" }",
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"pop\" }"
            )
        );
        val after = generate(repoRoot, mutated).getValue(table);
        assertNotEquals("the case table did not move when the spec did", before, after);
    }
}
