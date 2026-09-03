// SPFN Mobile — the screen generator must refuse a spec it cannot honestly read.
//
// The style is tools/contract-codegen's BundleSectionTest: read the real spec, then break
// exactly one thing and require generation to refuse. P8 is the pattern under test — a
// generator that lets an unrecognised value fall through an else-branch does not fail, it
// emits a plausible app from a spec nobody wrote.
//
// The last cases are not refusals. One group is determinism, which is the property every
// header in the output claims; the last is discriminating power (P10): a table that did
// not move when the spec moved would be a table that proves nothing about the spec.
//
// Determinism is THREE cases, not one, because Main.kt's header names four inputs and
// running the same invocation twice only probes two of them. The same bytes under another
// path have to move exactly one line, and a lock disagreeing with the bytes it points at
// has to stop the run before a file is written.

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

    /**
     * The consumer every case here generates for.
     *
     * Deliberately NOT either shipped target. The generator takes the roots, the package
     * and the application id as arguments, and a suite that passed the real example
     * target's values would be checking the generator against the one arrangement its
     * Gradle task already checks — a target this suite invented is what proves the fields
     * are read rather than remembered. Nothing is written: `generate` returns a map.
     */
    private val target = Target(
        name = "suite",
        swiftRoot = "Suite/Generated",
        kotlinRoot = "suite/kotlin/probe/generated",
        kotlinPackage = "probe.generated",
        appId = "probe.app",
        tableRoot = "suite/cases",
        runnerReadouts = true,
        generateTask = ":ui-codegen:spfnGenerateSuiteUi",
        verifyTask = ":ui-codegen:spfnSuiteUiVerify"
    )

    private fun generate(repoRoot: File, specPath: String): Map<String, String> =
        generate(repoRoot, specPath, target)

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
     * The values the 3b keys admit, and every neighbouring word they do not.
     *
     * Each of the four is a closed set that reaches a component name or an enum case, so a
     * value outside it would not fail here — it would reach a Swift emitter that writes
     * `.gо` or a Kotlin one that writes `FieldKind.Otp`, and the first evidence would be a
     * compile error in a generated file nobody wrote (refusal 6's family, one layer down).
     */
    @Test
    fun `a value outside a closed set is refused, by its path`()
    {
        assertRefused(
            "entry-word.json",
            replaceOnce("\"entry\": \"modal\"", "\"entry\": \"drawer\""),
            "flows.approveDevice.entry is 'drawer'"
        );

        assertRefused(
            "role-word.json",
            replaceOnce("\"role\": \"destructive\"", "\"role\": \"danger\""),
            "screens.reviewDevice.actions.deny.role is 'danger'"
        );

        assertRefused(
            "kind-word.json",
            replaceOnce("\"kind\": \"code\"", "\"kind\": \"otp\""),
            "screens.enterCode.inputs.userCode.kind is 'otp'"
        );

        assertRefused(
            "detent-word.json",
            replaceOnce(
                "\"entry\": \"modal\", \"start\": \"enterCode\"",
                "\"entry\": \"sheet\", \"sheet\": { \"detent\": \"tall\" }, \"start\": \"enterCode\""
            ),
            "flows.approveDevice.sheet.detent is 'tall'"
        );
    }

    /**
     * A detent is required for a sheet and refused for anything else.
     *
     * Both directions, because they are different mistakes. A sheet with no detent has no
     * height to resolve; a modal with one carries a number nothing reads, which is exactly
     * the state `FlowEntry` stopped being an enum to avoid — said one layer up, in the spec.
     */
    @Test
    fun `a detent is required for a sheet and refused for anything else`()
    {
        assertRefused(
            "sheet-no-detent.json",
            replaceOnce("\"entry\": \"modal\"", "\"entry\": \"sheet\""),
            "flows.approveDevice.entry is 'sheet' but flows.approveDevice.sheet is absent"
        );

        assertRefused(
            "modal-with-detent.json",
            replaceOnce(
                "\"entry\": \"modal\", \"start\": \"enterCode\"",
                "\"entry\": \"modal\", \"sheet\": { \"detent\": \"half\" }, \"start\": \"enterCode\""
            ),
            "flows.approveDevice.sheet is written on a flow entered as 'modal'"
        );
    }

    /**
     * Refusal 8: an `inputs` entry has to decorate an input the screen really collects.
     *
     * The inputs are DERIVED from the contract, so a request field renamed upstream orphans
     * whatever the spec said about it and the field goes on being collected as plain text
     * with no label and no return key. Nothing fails and the screen is not the one somebody
     * wrote, which is P8 one layer up — the same shape refusal 6 exists for.
     */
    @Test
    fun `an inputs entry naming nothing the screen collects is refused`()
    {
        assertRefused(
            "orphan-input.json",
            replaceOnce("\"userCode\": { \"kind\": \"code\"", "\"userCod\": { \"kind\": \"code\""),
            "screens.enterCode.inputs.userCod decorates an input this screen does not collect"
        );
    }

    /**
     * A sheet reaches both platforms as a `FlowEntry` carrying its height.
     *
     * The one spec key that becomes a CALL rather than a name on each platform, so a detent
     * silently dropped would compile on both — `FlowEntry.Sheet` needs an argument, but
     * `.modal` is what an emitter that forgot would write, and that is a sheet flow presented
     * as a full-screen modal with nothing to say it went wrong.
     */
    @Test
    fun `a sheet flow carries its detent into both halves`()
    {
        val sheet = withSpec(
            "sheet-flow.json",
            replaceOnce(
                "\"entry\": \"modal\", \"start\": \"enterCode\"",
                "\"entry\": \"sheet\", \"sheet\": { \"detent\": \"half\" }, \"start\": \"enterCode\""
            )
        );
        val generated = generate(repoRoot, sheet);

        assertEmits(
            generated = generated,
            path = "${target.kotlinRoot}/flows/ApproveDeviceFlow.kt",
            expected = "val ApproveDeviceEntry: FlowEntry = FlowEntry.Sheet(SheetDetent.Half);"
        );
        assertEmits(
            generated = generated,
            path = "${target.swiftRoot}/Flows/ApproveDeviceFlow.swift",
            expected = "public let approveDeviceEntry: FlowEntry = .sheet(detent: .half)"
        );
    }

    /**
     * `header.close` suppresses a close and never a back.
     *
     * Both halves matter and the second is the one that was wrong first. A screen that is not
     * its flow's root has `close: false` — it has a back, not a close — so an emitter that
     * read that field as "pass an empty leading slot" erased the back control on every pushed
     * route in the app while the spec said nothing at all. Nothing failed: the header drew, it
     * simply had no way out on it, which is a screen a person is stuck on.
     */
    @Test
    fun `header close suppresses the flow's close only on the root that would have had one`()
    {
        val standard = generate(repoRoot, specPath);
        assertEmits(
            generated = standard,
            path = "${target.kotlinRoot}/views/ReviewDeviceScreen.kt",
            expected = "Screen(title = \"Review the device\", scroll = true)"
        );
        assertEmits(
            generated = standard,
            path = "${target.swiftRoot}/Views/EnterCodeView.swift",
            expected = "Screen(title: \"Approve a device\", scroll: true)"
        );

        val suppressed = generate(
            repoRoot,
            withSpec(
                "no-close.json",
                replaceOnce("\"title\": \"Approve a device\",", "\"title\": \"Approve a device\", \"header\": { \"close\": false },")
            )
        );
        assertEmits(
            generated = suppressed,
            path = "${target.kotlinRoot}/views/EnterCodeScreen.kt",
            expected = "Screen(title = \"Approve a device\", leading = {}, scroll = true)"
        );
        assertEmits(
            generated = suppressed,
            path = "${target.swiftRoot}/Views/EnterCodeView.swift",
            expected = "Screen(title: \"Approve a device\", leading: AnyView(EmptyView()), scroll: true)"
        );
        assertEmits(
            generated = suppressed,
            path = "${target.kotlinRoot}/views/ReviewDeviceScreen.kt",
            expected = "Screen(title = \"Review the device\", scroll = true)"
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
            "${target.kotlinRoot}/screens/AuditDeviceModel.kt",
            "class AuditDeviceModel(\n" +
                "    private val deviceAudit: DeviceAuditService,\n" +
                "    private val flow: Flow<ApproveDeviceRoute>,\n" +
                "    private val userCode: String\n" +
                ")"
        );
        assertEmits(
            generated,
            "${target.swiftRoot}/Screens/AuditDeviceModel.swift",
            "    public init(\n" +
                "        deviceAudit: any DeviceAuditService,\n" +
                "        flow: Flow<ApproveDeviceRoute>,\n" +
                "        userCode: String\n" +
                "    )"
        );

        // And a screen that names no service at all is given none, on either platform.
        assertEmits(generated, "${target.kotlinRoot}/screens/LeafletModel.kt", "class LeafletModel(\n    private val flow: Flow<ApproveDeviceRoute>\n)");
        assertEmits(generated, "${target.swiftRoot}/Screens/LeafletModel.swift", "    public init(\n        flow: Flow<ApproveDeviceRoute>\n    )");
        assertEmits(generated, "${target.kotlinRoot}/AppContainer.kt", "LeafletModel(approveDeviceFlow);");
        assertEmits(generated, "${target.swiftRoot}/AppContainer.swift", "LeafletModel(flow: approveDeviceFlow)");
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
            "${target.kotlinRoot}/screens/ReviewDeviceModel.kt",
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
            "${target.kotlinRoot}/screens/ReviewDeviceModel.kt",
            "deviceAudit.deny(SpfnDenyDeviceAuthRequest(userCode = userCode));"
        );
        assertEmits(
            generated,
            "${target.swiftRoot}/Screens/ReviewDeviceModel.swift",
            "    private let deviceApproval: any DeviceApprovalService\n" +
                "    private let deviceAudit: any DeviceAuditService\n"
        );
        assertEmits(
            generated,
            "${target.swiftRoot}/Screens/ReviewDeviceModel.swift",
            "try await deviceAudit.deny(SPFNDenyDeviceAuthRequest(userCode: userCode))"
        );

        assertEmits(
            generated,
            "${target.kotlinRoot}/AppContainer.kt",
            "ReviewDeviceModel(DefaultReviewDeviceUseCase(deviceApproval), deviceApproval, deviceAudit, approveDeviceFlow, userCode);"
        );
        assertEmits(
            generated,
            "${target.swiftRoot}/AppContainer.swift",
            "ReviewDeviceModel(useCase: DefaultReviewDeviceUseCase(service: deviceApproval), deviceApproval: deviceApproval, " +
                "deviceAudit: deviceAudit, flow: approveDeviceFlow, userCode: userCode)"
        );
    }

    @Test
    fun `generation is a pure function of its stated inputs`()
    {
        val first = generate(repoRoot, specPath);
        val second = generate(repoRoot, specPath);
        assertEquals("the two runs wrote different files", first.keys, second.keys);
        first.forEach { (path, content) ->
            assertEquals("$path differs between two runs of the same generator", content, second[path]);
        };
    }

    /**
     * The spec PATH is an input, and it reaches the output on exactly one line of each file.
     *
     * Main.kt names four inputs — the spec bytes, the bundle bytes, the spec's
     * repository-relative path and the lock's contract block — and the path is the one a
     * reader is most likely to read as an invocation detail. It is not: it is printed in
     * every header and in the case table's `spec` field, which is what a person checks the
     * digests against. So the same bytes generated under another path must produce output
     * that differs, and differs THERE and nowhere else.
     *
     * Both halves of that are load-bearing. More than one line moving would mean something
     * else in the output depends on where the file sat; no line moving would mean the
     * output no longer states which spec it came from, and every header's claim about its
     * own inputs would be one input short.
     */
    @Test
    fun `the same bytes under another path move the spec line and nothing else`()
    {
        val elsewhere = withSpec("same-bytes.json", specText);
        val here = generate(repoRoot, specPath);
        val there = generate(repoRoot, elsewhere);
        assertEquals("the two paths generated different files", here.keys, there.keys);

        val languages = mutableSetOf<String>();
        here.forEach { (path, content) ->
            val ours = content.lines();
            val theirs = there.getValue(path).lines();
            assertEquals("$path has a different number of lines under another path", ours.size, theirs.size);

            val moved = ours.indices.filter { ours[it] != theirs[it] };
            assertEquals(
                "$path should differ from its other-path twin on the spec line alone, and differs on " +
                    moved.map { "${it + 1}: '${ours[it]}' vs '${theirs[it]}'" },
                1,
                moved.size
            );
            val line = moved.single();
            assertEquals(
                "$path:${line + 1} is the line that moved, but it is not the spec path being named",
                ours[line].replace(specPath, elsewhere),
                theirs[line]
            );
            languages += path.substringAfterLast('.');
        };

        // Floored by language rather than by count: the claim is about the Swift half, the
        // Kotlin half, the case table and the flows, and a read that covered only one of
        // them would prove the property for one emitter (P7).
        assertEquals(
            "the path probe did not cover both platforms, the case table and the flows",
            setOf("kt", "swift", "json", "md", "yaml"),
            languages
        );
    }

    /**
     * A lock whose contract block disagrees with the bundle it points at stops the run.
     *
     * This is the fourth input, and the only one whose gate lives outside `Spec`: the lock
     * decides WHICH file the bundle bytes are read from and what their digest must be, so a
     * lock naming another `manifestSha256` describes a bundle that is not the one on disk.
     * Generating from it would emit headers stating a digest no file has.
     *
     * The refusal names the lock as the source of the claim it could not honour — `lock
     * says: <digest>` — and it happens inside `generate`, which returns a map and writes
     * nothing; the fixture root is listed before and after to keep that a measurement
     * rather than an inference. The control run is what makes the refusal mean the digest:
     * the same fixture root with the lock untouched generates.
     */
    @Test
    fun `a lock naming another bundle digest is refused before anything is written`()
    {
        val broken = "0".repeat(64);
        val good = fixtureRoot("lock-good") { it };
        val bad = fixtureRoot("lock-bad") { lock -> lock.replace(pinnedDigest(), broken) };
        val before = filesUnder(bad);

        assertTrue("the control fixture root generated nothing", generate(good, specPath).isNotEmpty());

        try
        {
            generate(bad, specPath);
            fail("a lock naming another bundle digest was accepted");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on the digest: $message", message.contains("bundle digest mismatch"));
            assertTrue("the refusal does not say what the lock claimed: $message", message.contains("lock says: $broken"));
        }
        assertEquals("the refused run wrote into the tree", before, filesUnder(bad));
    }

    /** The digest the real lock pins, which is also the one the real bundle hashes to. */
    private fun pinnedDigest(): String =
        Regex("\"manifestSha256\": \"([0-9a-f]{64})\"").find(lockText)?.groupValues?.get(1)
            ?: error("the lock declares no contract manifestSha256");

    private val lockText: String = File(repoRoot, "Contracts/upstream.lock.json").readText(Charsets.UTF_8)

    /**
     * A repository root holding only what `generate` reads — the lock, the bundle the lock
     * points at, and the spec — with [mutate] applied to the lock's text.
     *
     * Built rather than pointed at the real tree because the lock's path is fixed inside
     * Main.kt: the only way to ask the digest gate a question is to give it another root.
     */
    private fun fixtureRoot(name: String, mutate: (String) -> String): File
    {
        val root = File(repoRoot, "tools/ui-codegen/build/$name");
        root.deleteRecursively();
        val bundlePath = Regex("\"bundlePath\": \"([^\"]+)\"").find(lockText)?.groupValues?.get(1)
            ?: error("the lock names no bundlePath");
        copyInto(root, bundlePath);
        copyInto(root, specPath);
        File(root, "Contracts/upstream.lock.json").writeText(mutate(lockText), Charsets.UTF_8);
        return root;
    }

    private fun copyInto(root: File, relative: String)
    {
        val destination = File(root, relative);
        destination.parentFile?.mkdirs();
        File(repoRoot, relative).copyTo(destination, overwrite = true);
    }

    /** Every file under [root], by path relative to it, sorted. */
    private fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()

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
    fun `every emitted control and field names the id a runner finds it by`()
    {
        val generated = generate(repoRoot, specPath);
        assertIdentified(generated, ".kt", "id = \"");
        assertIdentified(generated, ".swift", "identifier: \"");
    }

    /**
     * Every element a runner reaches passes its id as an ARGUMENT to an SPFNUI component.
     *
     * This used to read the line AFTER the selector and require a sizing modifier on it,
     * because the views drew their own controls and the 48dp minimum was re-emitted into
     * every one of them (docs/IMPLEMENTATION-PITFALLS.md P21: Compose reported
     * `enterCode.cancel` at a rectangle whose centre lay inside `enterCode.userCode`, and
     * cell u5 tapped the wrong node). The views no longer draw controls — `PrimaryButton`
     * and `SpfnTextField` do — so the minimum is written once in the SDK and section 15 of
     * tools/validate/validate.sh is what holds the components to it on both platforms.
     *
     * What is left for this reader is the half that is still the GENERATOR's: an id on every
     * element, spelled `<screen>.<action>`, because a component whose id argument was left
     * off would compile and leave a cell with nothing to tap. Floored rather than merely
     * satisfied: a read that found no id would pass the loop while proving nothing (P7).
     * Seven elements carry one today — three on `enterCode`, four on `reviewDevice`, where
     * `retry`'s is `LoadableView`'s retry slot rather than a control of its own.
     */
    private fun assertIdentified(generated: Map<String, String>, suffix: String, selector: String)
    {
        val prefixes = listOf("enterCode.", "reviewDevice.");
        var found = 0;
        generated.filterKeys { (it.contains("/Views/") || it.contains("/views/")) && it.endsWith(suffix) }
            .forEach { (_, content) ->
                content.lines().map { it.trim() }.forEach { line ->
                    if (line.startsWith(selector) && prefixes.any { line.contains(it) })
                    {
                        found++;
                    }
                };
            };
        assertEquals("the generated $suffix views did not name the elements this reads", 7, found);
    }

    /**
     * C6: the readouts belong to the consumers a runner drives, and to no other.
     *
     * The same spec through a target with the flag off has to lose both lines and keep every
     * control, because that is what a third consumer — a real app — takes. A flag that
     * emitted the same file either way would be a decision recorded and not made.
     */
    @Test
    fun `a target that asks for no readouts gets none`()
    {
        val quiet = target.copy(name = "quiet", runnerReadouts = false, tableRoot = null);
        val loud = generate(repoRoot, specPath, target).getValue("${target.kotlinRoot}/views/EnterCodeScreen.kt");
        val silent = generate(repoRoot, specPath, quiet).getValue("${quiet.kotlinRoot}/views/EnterCodeScreen.kt");

        assertTrue("the readout target emitted no state readout", loud.contains("\"state=\" + stateName(state)"));
        assertTrue("the quiet target emitted a state readout", !silent.contains("state="));
        assertTrue("the quiet target emitted a stack readout", !silent.contains("stack="));
        assertTrue("the quiet target dropped a control", silent.contains("id = \"enterCode.submit\""));

        val swift = generate(repoRoot, specPath, quiet).getValue("${quiet.swiftRoot}/Views/EnterCodeView.swift");
        assertTrue("the quiet Swift target emitted a readout", !swift.contains("state="));
        assertTrue("the quiet Swift target dropped a control", swift.contains("identifier: \"enterCode.submit\""));
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
        val table = "${target.tableRoot}/device-approval.cases.json";
        val before = generate(repoRoot, specPath).getValue(table);
        val mutated = withSpec(
            "discriminate.json",
            replaceOnce(
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"close\", \"role\": \"primary\" }",
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"pop\", \"role\": \"primary\" }"
            )
        );
        val after = generate(repoRoot, mutated).getValue(table);
        assertNotEquals("the case table did not move when the spec did", before, after);
    }

    /**
     * Every Kotlin screen method has to catch wider than the client's own hierarchy.
     *
     * `catch (failure: SpfnClientError)` is what these were until 2f, and it looks right:
     * it is the taxonomy a server's answers arrive as, and it lets cancellation past. It
     * lets everything else past too. `SpfnClockSynchronizationException` is an
     * `IllegalStateException` raised before a request leaves, and on the 2026-09-03
     * emulator run it went through `EnterCodeModel.submit` and took the process with it —
     * three cells, no assertion anywhere near them, because the case table's fixtures
     * throw `SpfnClientError` and nothing else could reach that branch (P26).
     *
     * Read off the emitted text because that is the only place this host can see it: the
     * example app compiles these files, but a compiler is satisfied by the narrow catch
     * and the crash needs a device. A count is asserted for each clause so a rename that
     * emptied the read would pass the loop having read nothing (P7) — four calls across
     * the two screens, one on `enterCode` and three on `reviewDevice`.
     */
    @Test
    fun `every generated Kotlin call catches wider than SpfnClientError`()
    {
        val models = generate(repoRoot, specPath)
            .filterKeys { it.startsWith("${target.kotlinRoot}/screens/") && it.endsWith("Model.kt") };
        assertEquals("the generator wrote no Kotlin screen models to read", 2, models.size);

        var wide = 0;
        var cancellation = 0;
        models.forEach { (path, content) ->
            assertTrue(
                "$path still catches only the client's own hierarchy",
                !content.contains("catch (failure: SpfnClientError)")
            );
            wide += content.split("catch (failure: Exception)").size - 1;
            cancellation += content.split("catch (cancelled: CancellationException)").size - 1;
        };
        assertEquals("a call is not caught wide enough to survive what the SDK throws", 4, wide);
        assertEquals("a call classifies the cancellation it must rethrow", 4, cancellation);

        // Order decides which clause wins, and Kotlin takes the first that matches.
        assertEmits(
            generated = models,
            path = "${target.kotlinRoot}/screens/EnterCodeModel.kt",
            expected = "        catch (cancelled: CancellationException)\n" +
                "        {\n" +
                "            throw cancelled;\n" +
                "        }\n" +
                "        catch (failure: Exception)\n"
        );
    }
}
