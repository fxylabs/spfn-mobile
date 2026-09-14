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

    /**
     * The spec every case here starts from, which is a DIRECTORY.
     *
     * `device-approval.json` holds the showcase's eight flows and `contracts/approveDevice.md`
     * holds the ninth, in the `json spfn-ui` block at its end. So a mutation is made on a COPY
     * of the pieces and written into a directory of its own: a case that edited one file would
     * be asking the generator about half a spec.
     */
    private val specPath = "examples/ui-spec"

    /** The one contract document of the example spec, which is the ninth flow. */
    private val contractDocument = "contracts/approveDevice.md"

    /** Every piece of the spec, by its name inside the directory. */
    private val specPieces: Map<String, String> = listOf(
        "device-approval.json",
        contractDocument
    ).associateWith { File(repoRoot, "$specPath/$it").readText(Charsets.UTF_8) }

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
        // Every flow, which is what a consumer of the whole showcase takes. The two cases
        // below narrow a copy of this target, so the field is exercised both ways.
        flows = null,
        runnerReadouts = true,
        generateTask = ":ui-codegen:spfnGenerateSuiteUi",
        verifyTask = ":ui-codegen:spfnSuiteUiVerify"
    )

    private fun generate(repoRoot: File, specPath: String): Map<String, String> =
        generate(repoRoot, specPath, target).files

    /** Writes a mutated spec as a directory under the module's build directory. */
    private fun withSpec(name: String, pieces: Map<String, String>): String
    {
        val relative = "tools/ui-codegen/build/test-specs/$name";
        val directory = File(repoRoot, relative);
        directory.deleteRecursively();
        pieces.forEach { (piece, text) ->
            val file = File(directory, piece);
            file.parentFile?.mkdirs();
            file.writeText(text);
        };
        return relative;
    }

    /**
     * The pieces with [needle] replaced once in EVERY piece that carries it.
     *
     * Every piece rather than the first, because the two that overlap are the two the union
     * exists to hold together: both state the pinned digest, and both name the operations they
     * call. A mutation applied to one of those would be answered by the merge's own refusal —
     * "the pieces of one spec are written against one contract bundle" — and the case below
     * would pass on the wrong sentence.
     */
    private fun replaceOnce(needle: String, replacement: String): Map<String, String>
    {
        val carrying = specPieces.filterValues { it.contains(needle) };
        assertTrue("no piece of the spec carries '$needle'", carrying.isNotEmpty());
        return specPieces + carrying.mapValues { (_, text) -> text.replaceFirst(needle, replacement) };
    }

    private fun assertRefused(name: String, pieces: Map<String, String>, expected: String)
    {
        val path = withSpec(name, pieces);
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
            "digest",
            replaceOnce("\"manifestSha256\": \"", "\"manifestSha256\": \"00"),
            "spec digest mismatch"
        );
    }

    @Test
    fun `an operation the contract does not declare is refused`()
    {
        assertRefused(
            "operation",
            replaceOnce("authDeviceInfo", "authDeviceInformation"),
            "which the pinned contract does not declare"
        );
    }

    @Test
    fun `a then that pushes a screen outside the flow is refused`()
    {
        assertRefused(
            "then",
            replaceOnce("\"push\": \"reviewDevice\"", "\"push\": \"somewhereElse\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a start that is not a screen is refused`()
    {
        assertRefused(
            "start",
            replaceOnce("\"start\": \"enterCode\"", "\"start\": \"nowhere\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a call naming a method no service declares is refused`()
    {
        assertRefused(
            "call",
            // Spelled with the key in front of it, because the document names this method in
            // its Controls table too and a prose row is not what the generator reads.
            replaceOnce(
                "\"approve\": { \"call\": \"deviceApproval.approve\"",
                "\"approve\": { \"call\": \"deviceApproval.accept\""
            ),
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
            "screen-key",
            replaceOnce("\"usecase\": true", "\"useCase\": true"),
            "screens.reviewDevice.useCase is not a key this generator reads"
        );

        assertRefused(
            "action-key",
            replaceOnce(
                "\"retry\": { \"call\": \"deviceApproval.lookup\" }",
                "\"retry\": { \"call\": \"deviceApproval.lookup\", \"onFailure\": \"pop\" }"
            ),
            "screens.reviewDevice.actions.retry.onFailure is not a key this generator reads"
        );

        assertRefused(
            "then-key",
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
            "entry-word",
            replaceOnce("\"entry\": \"modal\"", "\"entry\": \"drawer\""),
            "flows.approveDevice.entry is 'drawer'"
        );

        assertRefused(
            "role-word",
            replaceOnce("\"role\": \"destructive\"", "\"role\": \"danger\""),
            "screens.reviewDevice.actions.deny.role is 'danger'"
        );

        assertRefused(
            "kind-word",
            replaceOnce("\"kind\": \"code\"", "\"kind\": \"otp\""),
            "screens.enterCode.inputs.userCode.kind is 'otp'"
        );

        assertRefused(
            "detent-word",
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
            "sheet-no-detent",
            replaceOnce("\"entry\": \"modal\"", "\"entry\": \"sheet\""),
            "flows.approveDevice.entry is 'sheet' but flows.approveDevice.sheet is absent"
        );

        assertRefused(
            "modal-with-detent",
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
            "orphan-input",
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
            "sheet-flow",
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
     * read that field as "pass an empty slot" erased the way out on every pushed route in the
     * app while the spec said nothing at all. Nothing failed: the header drew, it simply had
     * no way out on it, which is a screen a person is stuck on.
     *
     * The slot it passes is the TRAILING one, because that is where the X is drawn
     * (decision N3): an empty LEADING slot would now erase the back and leave the close.
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
                "no-close",
                replaceOnce("\"title\": \"Approve a device\",", "\"title\": \"Approve a device\", \"header\": { \"close\": false },")
            )
        );
        assertEmits(
            generated = suppressed,
            path = "${target.kotlinRoot}/views/EnterCodeScreen.kt",
            expected = "Screen(title = \"Approve a device\", trailing = {}, scroll = true)"
        );
        assertEmits(
            generated = suppressed,
            path = "${target.swiftRoot}/Views/EnterCodeView.swift",
            expected = "Screen(title: \"Approve a device\", trailing: AnyView(EmptyView()), scroll: true)"
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
        val elsewhere = withSpec("same-bytes", specPieces);
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
     * A document's PROSE is not an input to the generator; its BLOCK is.
     *
     * The prose is the half a contract document exists to have rewritten — it is where what
     * the screens must do is argued, and nothing downstream reads it. While the digest was
     * taken over the whole file, rewording one sentence of `contracts/approveDevice.md`
     * rewrote the `specSha256:` line of all 137 generated files, so the document was
     * expensive to edit for its own purpose.
     *
     * The prose is REPLACED here rather than nudged, because the claim is about the whole
     * half and a probe that changed one word would pass just as well against a digest that
     * happened to ignore that word. And the whole output is compared rather than the digest
     * alone: the digest not moving is the mechanism, generated files not moving is the
     * property, and the second is the one anybody notices.
     */
    @Test
    fun `rewriting a document's prose leaves the digest and the generated files alone`()
    {
        val rewritten = "# approveDevice\n\nNot one word of this prose is the one it replaced.\n\n" +
            machineBlockOf(contractDocument);

        // Both runs from ONE spec path, rewritten in place between them. The path is itself
        // an input and is printed on every header, so two fixture directories would differ
        // on the `spec:` line and the comparison below would be about the wrong thing.
        val directory = withSpec("prose", specPieces);
        val here = generate(repoRoot, directory);
        withSpec("prose", specPieces + (contractDocument to rewritten));
        val there = generate(repoRoot, directory);

        assertEquals("the two runs wrote different files", here.keys, there.keys);
        assertEquals("a reworded document moved the spec digest", specDigestOf(here), specDigestOf(there));
        here.forEach { (path, content) ->
            assertEquals("$path moved when only the document's prose did", content, there.getValue(path));
        };
    }

    /**
     * One character inside the block moves the digest, and moves nothing else.
     *
     * The character is a SPACE added inside the block's JSON, which is the case a digest
     * taken over anything other than the block's own bytes would get wrong: parse the block
     * and hash the result and this edit disappears; trim or collapse the block's whitespace
     * and it disappears too. Neither happens — the digest is the block's bytes, so an edit
     * to the spec is an edit whatever it looks like.
     *
     * That the rest of the output holds still is the other half. The block is unchanged as
     * JSON, so every generated file must be its old self on every line but the one that
     * names the digest — which is what makes this the twin of the case above rather than a
     * second copy of it.
     */
    @Test
    fun `one character inside the block moves the digest and only the digest line`()
    {
        val spaced = specPieces.getValue(contractDocument)
            .replaceFirst("\"specVersion\": 1", "\"specVersion\":  1");
        assertNotEquals("the block was not edited", specPieces.getValue(contractDocument), spaced);

        // One spec path, rewritten in place between the runs, for the reason above.
        val directory = withSpec("block", specPieces);
        val here = generate(repoRoot, directory);
        withSpec("block", specPieces + (contractDocument to spaced));
        val there = generate(repoRoot, directory);

        assertNotEquals(
            "a byte changed inside the block left the digest where it was",
            specDigestOf(here),
            specDigestOf(there)
        );
        here.forEach { (path, content) ->
            val moved = content.lines().zip(there.getValue(path).lines())
                .filter { (ours, theirs) -> ours != theirs };
            assertEquals(
                "$path should differ from its spaced-block twin on the digest line alone, and differs on $moved",
                1,
                moved.size
            );
            assertTrue(
                "$path moved on a line that is not the digest: ${moved.single()}",
                moved.single().first.contains("specSha256")
            );
        };
    }

    /** The named piece's machine block, fences and all, as a document would carry it. */
    private fun machineBlockOf(piece: String): String
    {
        val document = specPieces.getValue(piece);
        val fence = document.indexOf("```json spfn-ui");
        assertTrue("$piece holds no machine block to take", fence > 0);
        return document.substring(fence);
    }

    /**
     * The spec digest a run printed, which is one value across its files or a failure.
     *
     * Read off the OUTPUT rather than asked of `SpecInput`, because the digest is only
     * interesting where it lands: every header's `specSha256:` line and the case table's
     * `specSha256` field. Taking it from both is also what keeps this an assertion — two
     * artefacts naming two digests would be a generator disagreeing with itself.
     */
    private fun specDigestOf(generated: Map<String, String>): String
    {
        val printed = generated.values
            .flatMap { it.lines() }
            .filter { it.contains("specSha256") }
            .mapNotNull { Regex("[0-9a-f]{64}").find(it)?.value }
            .toSet();
        assertEquals("the run did not print exactly one spec digest, but $printed", 1, printed.size);
        return printed.first();
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

    /** One file or one directory of the real tree, at the same relative path under [root]. */
    private fun copyInto(root: File, relative: String)
    {
        val destination = File(root, relative);
        destination.parentFile?.mkdirs();
        val source = File(repoRoot, relative);
        if (source.isDirectory)
        {
            source.copyRecursively(destination, overwrite = true);
        }
        else
        {
            source.copyTo(destination, overwrite = true);
        }
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
        val loud = generate(repoRoot, specPath, target).files.getValue("${target.kotlinRoot}/views/EnterCodeScreen.kt");
        val silent = generate(repoRoot, specPath, quiet).files.getValue("${quiet.kotlinRoot}/views/EnterCodeScreen.kt");

        assertTrue("the readout target emitted no state readout", loud.contains("\"state=\" + stateName(state)"));
        assertTrue("the quiet target emitted a state readout", !silent.contains("state="));
        assertTrue("the quiet target emitted a stack readout", !silent.contains("stack="));
        assertTrue("the quiet target dropped a control", silent.contains("id = \"enterCode.submit\""));

        val swift = generate(repoRoot, specPath, quiet).files.getValue("${quiet.swiftRoot}/Views/EnterCodeView.swift");
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
            "discriminate",
            replaceOnce(
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"close\", \"role\": \"primary\" }",
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"pop\", \"role\": \"primary\" }"
            )
        );
        val after = generate(repoRoot, mutated).getValue(table);
        assertNotEquals("the case table did not move when the spec did", before, after);
    }

    @Test
    fun `a body key this generator does not carry is refused`()
    {
        assertRefused(
            "body-key",
            replaceOnce("\"body\": \"lorem.long\"", "\"body\": \"lorem.enormous\""),
            "which is not a body this generator carries"
        );
    }

    /**
     * A screen that reads shows what it read, and static prose under it is the second
     * answer to a question that already has one.
     */
    @Test
    fun `a body on a screen that reads is refused`()
    {
        assertRefused(
            "body-source",
            replaceOnce("\"usecase\": true,", "\"usecase\": true, \"body\": \"lorem.short\","),
            "a screen that reads shows what it read"
        );
    }

    /**
     * The showcase flows are emitted for the app that shows them and for nobody else.
     *
     * `--flows` is a call argument, so a misspelling in it is the failure that would
     * otherwise emit an app with no screens at all and report success. The narrowing itself
     * is checked in the other direction too: the harness's one flow brings its own screens
     * and its own service and leaves the other eight flows' behind.
     */
    @Test
    fun `a target narrowed to a flow the spec does not declare is refused`()
    {
        try
        {
            generate(repoRoot, specPath, target.copy(flows = setOf("approveDevice", "approveDevices")));
            fail("generation accepted a --flows value naming no flow");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on the unknown flow: $message", message.contains("approveDevices"));
        }
    }

    @Test
    fun `a narrowed target emits the screens of its own flows and no others`()
    {
        val narrowed = generate(repoRoot, specPath, target.copy(flows = setOf("approveDevice"))).files;
        val views = narrowed.keys.filter { it.startsWith("${target.swiftRoot}/Views/") }.sorted();
        assertEquals(
            listOf("${target.swiftRoot}/Views/EnterCodeView.swift", "${target.swiftRoot}/Views/ReviewDeviceView.swift"),
            views
        );
        // The whole spec still has to be read before it is narrowed, so the count below is
        // the evidence that narrowing dropped something rather than that nothing was there.
        val whole = generate(repoRoot, specPath).keys.filter { it.startsWith("${target.swiftRoot}/Views/") };
        assertEquals("the unnarrowed target lost views of its own", 14, whole.size);
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
     * emptied the read would pass the loop having read nothing (P7) — five calls across
     * three screens: one on `enterCode`, three on `reviewDevice` and one on `form`. The
     * showcase's other eleven screens call nothing and have nothing to catch, which the
     * count of models CARRYING a catch is what states.
     */
    @Test
    fun `every generated Kotlin call catches wider than SpfnClientError`()
    {
        val models = generate(repoRoot, specPath)
            .filterKeys { it.startsWith("${target.kotlinRoot}/screens/") && it.endsWith("Model.kt") };
        assertEquals("the generator wrote no Kotlin screen models to read", 14, models.size);

        var wide = 0;
        var cancellation = 0;
        var catching = 0;
        models.forEach { (path, content) ->
            assertTrue(
                "$path still catches only the client's own hierarchy",
                !content.contains("catch (failure: SpfnClientError)")
            );
            if (content.contains("catch ("))
            {
                catching += 1;
            }
            wide += content.split("catch (failure: Exception)").size - 1;
            cancellation += content.split("catch (cancelled: CancellationException)").size - 1;
        };
        assertEquals("the screens that call are not the ones this suite is reading", 3, catching);
        assertEquals("a call is not caught wide enough to survive what the SDK throws", 5, wide);
        assertEquals("a call classifies the cancellation it must rethrow", 5, cancellation);

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

    /**
     * Every generated Kotlin view's body is spaced, and by the step its Swift twin uses.
     *
     * The Swift emitter writes `VStack(alignment: .leading, spacing: SPFNTokens.space4)` and
     * the Kotlin one wrote a bare `Column`, whose children touch. On the 3e emulator round
     * that is the third of the four differences the Android screenshots carried against the
     * iOS ones: two paragraphs and the readouts above them with no air between them, on a
     * screen iOS drew with a step.
     *
     * Read off the emitted text, because it is a LAYOUT and this host has no device. Every
     * view is read rather than one, since the Column is written once and a screen that lost
     * it would be a screen the count catches; and the Swift half is asserted in the same
     * breath, because "both platforms space their bodies by space4" is the claim, and a
     * Kotlin-only reader would pass on the day the Swift emitter stopped (P7, P10).
     */
    @Test
    fun `a generated Kotlin view's Column is spaced by space4, as its Swift twin's VStack is`()
    {
        val generated = generate(repoRoot, specPath);
        val kotlin = generated.filterKeys { it.startsWith("${target.kotlinRoot}/views/") };
        val swift = generated.filterKeys { it.startsWith("${target.swiftRoot}/Views/") };
        assertEquals("the generator wrote no Kotlin views to read", 14, kotlin.size);
        assertEquals("the generator wrote no Swift views to read", 14, swift.size);

        kotlin.forEach { (path, content) ->
            assertTrue(
                "$path draws its body in a Column that does not space its children",
                content.contains(
                    "Column(modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), " +
                        "verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))"
                )
            );
            assertTrue(
                "$path uses Arrangement without importing it",
                content.contains("import androidx.compose.foundation.layout.Arrangement")
            );
        };
        swift.forEach { (path, content) ->
            assertTrue(
                "$path draws its body in a VStack that does not space its children",
                content.contains("VStack(alignment: .leading, spacing: SPFNTokens.space4)")
            );
        };
    }

    // ---- the spec is a directory of pieces (N5, D1) --------------------------

    /**
     * The union is the point: nine flows arrive from two files and the output is one app.
     *
     * Floored on both sides rather than on the total, because a merge that silently dropped
     * a piece would still produce a spec — the wrong one — and a count of everything cannot
     * say which half went missing (P7).
     */
    @Test
    fun `a directory spec is the union of its json and its contract documents`()
    {
        val generated = generate(repoRoot, specPath);
        assertTrue(
            "the flow the contract document carries did not reach the output",
            generated.containsKey("${target.kotlinRoot}/views/EnterCodeScreen.kt")
        );
        assertTrue(
            "the flows the json carries did not reach the output",
            generated.containsKey("${target.kotlinRoot}/views/LongScreen.kt")
        );
        assertEmits(
            generated = generated,
            path = "${target.kotlinRoot}/services/DeviceApprovalService.kt",
            expected = "suspend fun approve("
        );
    }

    /**
     * A block is read line by line, and the three shapes a document really arrives in.
     *
     * A ``` inside the JSON's own text does not close the block, because a fence is a LINE;
     * a document checked out with CRLF endings reads the same as one without; and a tag with
     * trailing spaces is the same tag. A regular expression over the whole document would
     * have to be right about all three at once, and the greedy version swallows the prose
     * between two documents' blocks.
     */
    @Test
    fun `a machine block is read whatever the document does around it`()
    {
        val document = "# a flow\n\n" +
            "```json spfn-ui\n" +
            "{\n  \"note\": \"``` is three backticks\"\n}\n" +
            "```\n\nprose after the block\n";
        val block = "{\n  \"note\": \"``` is three backticks\"\n}";

        assertEquals("a fence inside a string closed the block", block, SpecInput.machineBlock(document, "a.md"));
        assertEquals(
            "a document with CRLF endings read differently",
            block,
            SpecInput.machineBlock(document.replace("\n", "\r\n"), "a.md")
        );
        assertEquals(
            "spaces after the tag stopped it being the tag",
            "{}",
            SpecInput.machineBlock("```json spfn-ui   \n{}\n```  \n", "a.md")
        );
    }

    @Test
    fun `a document that does not hold exactly one machine block is refused by name`()
    {
        val one = "```json spfn-ui\n{}\n```\n";
        assertMachineBlockRefused("", "b.md holds 0 spfn-ui blocks");
        assertMachineBlockRefused(one + "\n" + one, "b.md holds 2 spfn-ui blocks");
        assertMachineBlockRefused("```json spfn-ui\n{}\n", "b.md opens a spfn-ui block that no closing fence ends");
    }

    private fun assertMachineBlockRefused(document: String, expected: String)
    {
        try
        {
            SpecInput.machineBlock(document, "b.md");
            fail("a document was accepted that must be refused: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    /**
     * The three ways two pieces can disagree, each refused with both paths in the message.
     *
     * A name declared twice is the one that matters most: two documents describing one flow
     * is two truths about what is on the phone, and the generator would take whichever it
     * read last. The other two are the fields that are properties of the WHOLE spec — the
     * pinned bundle and, for a method both pieces declare, the operation behind it.
     */
    @Test
    fun `two pieces that disagree are refused, naming both of them`()
    {
        val document = specPieces.getValue("contracts/approveDevice.md");

        assertRefused(
            "flow-twice",
            specPieces + ("contracts/again.md" to document),
            "flows.approveDevice is declared in both"
        );

        assertRefused(
            "digest-split",
            specPieces + ("contracts/approveDevice.md" to
                document.replaceFirst("\"manifestSha256\": \"", "\"manifestSha256\": \"00")),
            "the pieces of one spec are written against one contract bundle"
        );

        // The json's `form` screen CALLS this method, so the json declares it too; a call
        // needs no response type, which is why the mutation goes on this side.
        assertRefused(
            "method-split",
            specPieces + ("device-approval.json" to specPieces.getValue("device-approval.json")
                .replaceFirst("\"lookup\": { \"operation\": \"authDeviceInfo\" }",
                    "\"lookup\": { \"operation\": \"authDeviceApprove\" }")),
            "a method two pieces both declare is one method"
        );
    }

    // ---- who writes the views (N5, D2) --------------------------------------

    @Test
    fun `a views value outside the pair is refused, and a json flow may not claim authored`()
    {
        assertRefused(
            "views-word",
            replaceOnce(
                "\"approveDevice\": { \"entry\": \"modal\", \"start\": \"enterCode\" }",
                "\"approveDevice\": { \"entry\": \"modal\", \"start\": \"enterCode\", \"views\": \"manual\" }"
            ),
            "flows.approveDevice.views is 'manual'"
        );

        assertRefused(
            "views-in-json",
            replaceOnce(
                "\"pushTour\":      { \"entry\": \"push\",  \"start\": \"tourOne\" }",
                "\"pushTour\":      { \"entry\": \"push\",  \"start\": \"tourOne\", \"views\": \"authored\" }"
            ),
            "a view written by hand is written from a contract document"
        );
    }

    /**
     * The switch, in the only place this suite can see it: what the run says it produced.
     *
     * An authored flow's views are absent from the files — so `write` writes nothing there
     * and `verify` has nothing to compare — and present in `authoredViews`, which is what
     * keeps `staleOutputs` from deleting them. Everything else about the flow is still
     * generated: the model, the route, the flow and the container are the generator's
     * whatever draws the screen.
     */
    @Test
    fun `an authored flow's views are not written, and the rest of it still is`()
    {
        val authored = withSpec(
            "authored-views",
            replaceOnce(
                "\"approveDevice\": { \"entry\": \"modal\", \"start\": \"enterCode\" }",
                "\"approveDevice\": { \"entry\": \"modal\", \"start\": \"enterCode\", \"views\": \"authored\" }"
            )
        );
        val generated = generate(repoRoot, authored, target);

        assertEquals(
            "the authored flow's two screens are not the four view files this run must leave alone",
            setOf(
                "${target.kotlinRoot}/views/EnterCodeScreen.kt",
                "${target.kotlinRoot}/views/ReviewDeviceScreen.kt",
                "${target.swiftRoot}/Views/EnterCodeView.swift",
                "${target.swiftRoot}/Views/ReviewDeviceView.swift"
            ),
            generated.authoredViews
        );
        generated.authoredViews.forEach { view ->
            assertTrue("$view was generated over a person's own file", view !in generated.files.keys);
        };

        listOf(
            "${target.kotlinRoot}/screens/EnterCodeModel.kt",
            "${target.kotlinRoot}/flows/ApproveDeviceFlow.kt",
            "${target.swiftRoot}/Screens/ReviewDeviceModel.swift",
            // And a reference flow in the same run keeps its view: the switch is per flow.
            "${target.kotlinRoot}/views/LongScreen.kt"
        ).forEach { path ->
            assertTrue("$path stopped being generated when one flow's views became a person's", generated.files.containsKey(path));
        };
    }

    /**
     * The other half of the switch: the deletion rule that owns these directories.
     *
     * `write` deletes what it did not emit and `verify` reports it, which is what keeps a
     * generated directory honest — and is exactly what would eat a hand-written view. So the
     * exemption is read here against a tree holding one of each: a view this run declared
     * authored, and a leftover from a spec nobody has any more.
     */
    @Test
    fun `an authored view is not stale, and a leftover beside it still is`()
    {
        val root = File(repoRoot, "tools/ui-codegen/build/authored-tree");
        root.deleteRecursively();
        val views = "${target.kotlinRoot}/views";
        File(root, views).mkdirs();
        File(root, "$views/EnterCodeScreen.kt").writeText("// written by hand from contracts/approveDevice.md\n");
        File(root, "$views/GhostScreen.kt").writeText("// left behind by a spec that no longer declares it\n");

        val generated = Generated(
            files = mapOf("$views/LongScreen.kt" to "// generated\n"),
            authoredViews = setOf("$views/EnterCodeScreen.kt")
        );
        assertEquals(
            "the stale reader did not answer with the one file nothing declares",
            listOf("$views/GhostScreen.kt"),
            staleOutputs(root, generated)
        );
    }
}
