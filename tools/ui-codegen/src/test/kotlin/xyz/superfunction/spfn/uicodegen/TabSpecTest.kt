// SPFN Mobile — what specVersion 3 adds: the tab bar, its refusals, and what it generates.
//
// The style is SpecRefusalTest's: read the real spec, break exactly one thing in
// `tabs.json` and require generation to refuse, naming the field. Then the positive half:
// the real spec's bar reaches both apps as one `AppTabs` scaffold and the case table as the
// design's rows (docs/architecture/tab-host-design.md §4, §9-2), and a target narrowed past
// the bar's flows has no bar at all.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TabSpecTest
{
    private val repoRoot = File("../..");

    private val specPath = "examples/ui-spec";

    private val tabsPiece = "tabs.json";

    private val specPieces: Map<String, String> = listOf(
        "device-approval.json",
        tabsPiece,
        "contracts/approveDevice.md"
    ).associateWith { File(repoRoot, "$specPath/$it").readText(Charsets.UTF_8) };

    private val target = Target(
        name = "suite",
        swiftRoot = "Suite/Generated",
        kotlinRoot = "suite/kotlin/probe/generated",
        kotlinPackage = "probe.generated",
        appId = "probe.app",
        tableRoot = "suite/cases",
        flows = null,
        runnerReadouts = true,
        generateTask = ":ui-codegen:spfnGenerateSuiteUi",
        verifyTask = ":ui-codegen:spfnSuiteUiVerify"
    );

    private fun generate(path: String, into: Target = target): Map<String, String> =
        generate(repoRoot, path, into).files

    private fun withSpec(name: String, pieces: Map<String, String>): String
    {
        val relative = "tools/ui-codegen/build/test-specs/tabs-$name";
        val directory = File(repoRoot, relative);
        directory.deleteRecursively();
        pieces.forEach { (piece, text) ->
            val file = File(directory, piece);
            file.parentFile?.mkdirs();
            file.writeText(text);
        };
        return relative;
    }

    /** The pieces with [needle] replaced once in the tab piece alone. */
    private fun inTabs(needle: String, replacement: String): Map<String, String>
    {
        val text = specPieces.getValue(tabsPiece);
        assertTrue("the tab piece does not carry '$needle'", text.contains(needle));
        return specPieces + (tabsPiece to text.replaceFirst(needle, replacement));
    }

    private fun assertRefused(name: String, pieces: Map<String, String>, expected: String)
    {
        try
        {
            generate(withSpec(name, pieces));
            fail("generation accepted a spec it must refuse: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    // ---- refusal 16 ---------------------------------------------------------

    @Test
    fun `tabs in a spec older than version 3 is refused`()
    {
        assertRefused(
            "version-two",
            specPieces.mapValues { (_, text) -> text.replaceFirst("\"specVersion\": 3", "\"specVersion\": 2") },
            "tabs is a specVersion 3 key and this spec says 2"
        );
    }

    @Test
    fun `an empty bar is refused`()
    {
        val text = specPieces.getValue(tabsPiece);
        val emptied = text.substring(0, text.indexOf("\"tabs\": [")) + "\"tabs\": []\n}\n";
        assertRefused("empty", specPieces + (tabsPiece to emptied), "tabs is an empty list");
    }

    @Test
    fun `a tab id written twice is refused`()
    {
        assertRefused(
            "duplicate",
            inTabs("{ \"id\": \"account\"", "{ \"id\": \"home\""),
            "tabs.home and tabs.home are one name once written as a type"
        );
    }

    @Test
    fun `a tab id that is not a spec name is refused`()
    {
        assertRefused(
            "spelling",
            inTabs("{ \"id\": \"account\"", "{ \"id\": \"my-account\""),
            "tabs.my-account is not a name this generator can spell"
        );
    }

    @Test
    fun `a root that is also a screen is refused`()
    {
        assertRefused(
            "root-screen",
            inTabs("\"root\": \"accountHome\"", "\"root\": \"editName\""),
            "tabs.account.root is 'editName', which is also a flow or a screen"
        );
    }

    @Test
    fun `a flow the spec does not declare is refused`()
    {
        assertRefused(
            "unknown-flow",
            inTabs("\"flows\": [\"itemDetail\"]", "\"flows\": [\"itemDetails\"]"),
            "tabs.home.flows names 'itemDetails', which is not a flow this spec declares"
        );
    }

    @Test
    fun `one flow on two tabs is refused`()
    {
        assertRefused(
            "two-tabs",
            inTabs("\"flows\": [\"itemDetail\"]", "\"flows\": [\"itemDetail\", \"profile\"]"),
            "tabs.home.flows and tabs.account.flows both name 'profile'; one flow belongs to one tab"
        );
    }

    @Test
    fun `a key a tab does not have is refused`()
    {
        assertRefused(
            "badge",
            inTabs("\"title\": \"Home\",", "\"title\": \"Home\", \"badge\": 3,"),
            "tabs[0].badge is not a key this generator reads"
        );
    }

    @Test
    fun `two pieces declaring a bar are refused`()
    {
        val second = specPieces.getValue("device-approval.json").trimEnd().removeSuffix("}").trimEnd() +
            ",\n  \"tabs\": [ { \"id\": \"menu\", \"title\": \"Menu\", \"root\": \"menuRoot\", \"flows\": [\"pushTour\"] } ]\n}\n";
        assertRefused(
            "two-bars",
            specPieces + ("device-approval.json" to second),
            "the bar is one ordered list and lives in one place"
        );
    }

    // ---- what the bar generates ---------------------------------------------

    @Test
    fun `the bar reaches both apps as one scaffold and the container as one state`()
    {
        val generated = generate(specPath);
        val kotlin = generated.getValue("${target.kotlinRoot}/AppTabs.kt");
        val swift = generated.getValue("${target.swiftRoot}/AppTabs.swift");

        assertTrue(kotlin.contains("TabItem(id = \"home\", title = \"Home\", icon = mark) { HomeListRoot(container, header); },"));
        assertTrue(kotlin.contains("id = \"accountHome.editProfile\","));
        assertTrue(kotlin.contains("EditProfileFlowHost(container);"));
        assertTrue(swift.contains("TabItem(id: \"account\", title: \"Account\", icon: Image(systemName: \"circle.fill\"))"));
        assertTrue(swift.contains("identifier: \"homeList.itemDetail\","));
        assertTrue(
            generated.getValue("${target.kotlinRoot}/AppContainer.kt")
                .contains("val tabs: TabState = TabState(listOf(\"home\", \"account\"));")
        );
        assertTrue(
            generated.getValue("${target.swiftRoot}/AppContainer.swift")
                .contains("self.tabs = try! TabState(tabs: [\"home\", \"account\"])")
        );
    }

    @Test
    fun `the table carries the design's rows and no showcase row for a tab's flow`()
    {
        val generated = generate(specPath);
        val table = generated.getValue("${target.tableRoot}/device-approval.cases.json");

        listOf("tabs-c1", "tabs-c3", "tabs-c9", "tabs-c13", "tabs-c14", "tabs-c16", "tabs-c33").forEach { cell ->
            assertTrue("the table has no cell $cell", table.contains("\"id\": \"$cell\""));
        };
        assertTrue("C-13 is not the JVM's", table.contains("\"id\": \"tabs-c13\"") && table.contains("\"flow\": null"));
        // A flow a tab opens is the tab table's: the menu does not host it, so a showcase cell
        // that opened it from the menu would open it nowhere.
        listOf("itemDetail-", "profile-", "editProfile-", "accountSheet-").forEach { prefix ->
            assertFalse("a tab's flow has a showcase cell: $prefix", table.contains("\"id\": \"$prefix"));
        };

        val c9 = generated.getValue("${target.tableRoot}/flows/tabs-c9.yaml");
        assertTrue(
            "C-9's Android half does not select the start tab after its back",
            c9.contains("      platform: Android\n    commands:\n") && c9.contains("          text: \"tab=home\"")
        );
        assertFalse("C-9 carries a bare system back", c9.lines().any { it == "- back" });

        // The iOS bar is the system `TabView`'s: its buttons are found by label, below the
        // root's readouts so the root's own title is never the one pressed; Android's by tag.
        val c1 = generated.getValue("${target.tableRoot}/flows/tabs-c1.yaml");
        assertTrue(
            "C-1 does not press the system tab button by its label on iOS",
            c1.contains(
                "      platform: iOS\n    commands:\n      - tapOn:\n          text: \"Account\"\n" +
                    "          below:\n            text: \"stack=.*\"\n"
            )
        );
        assertTrue(
            "C-1 does not press the SDK bar item by its tag on Android",
            c1.contains("      platform: Android\n    commands:\n      - tapOn:\n          id: \"tab.account\"\n")
        );
    }

    @Test
    fun `a target narrowed past the bar's flows has no bar`()
    {
        val narrowed = generate(specPath, target.copy(flows = setOf("approveDevice", "itemDetail")));
        assertFalse(narrowed.containsKey("${target.kotlinRoot}/AppTabs.kt"));
        assertFalse(narrowed.containsKey("${target.swiftRoot}/AppTabs.swift"));
        assertFalse(narrowed.getValue("${target.kotlinRoot}/AppContainer.kt").contains("TabState"));
    }
}
