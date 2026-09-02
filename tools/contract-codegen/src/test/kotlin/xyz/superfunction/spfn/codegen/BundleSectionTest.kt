// SPFN Mobile — the generator must refuse a bundle missing a section it consumes.
//
// The probe style is the one the conformance suites use against the `mac` clause: read
// the real pinned bundle, then hold the parser to what it must refuse. P8 is the pattern
// under test — a parser that lets an unrecognised or absent structure fall through an
// else-branch does not fail; it emits plausible clients from a contract it never read.
// Each case here removes or corrupts one section and requires generation to refuse.

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BundleSectionTest
{
    private val bundleText: String =
        File("../../Contracts/spfn-mobile-contract.json").readText(Charsets.UTF_8)

    private val kotlinRoot = "android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated"

    private fun read(text: String): Bundle = Bundle.read(
        bundleText = text,
        sha256 = "unused-under-test",
        supportedRange = ">=0.10.0 <0.11.0",
        contractMajor = 0,
        contractMinor = 10
    )

    /** Renames one key so `required()` cannot find it, leaving the JSON well-formed. */
    private fun withoutKey(key: String): String
    {
        val marker = "\"$key\"";
        assertTrue("the pinned bundle no longer carries '$key'", bundleText.contains(marker));
        return bundleText.replace(marker, "\"${key}Removed\"");
    }

    private fun assertRefused(section: String, text: String)
    {
        try
        {
            read(text);
            fail("the generator read a bundle whose '$section' section is missing");
        }
        catch (_: JsonException)
        {
            // The refusal this probe exists to require.
        }
    }

    /** Requires the refusal AND that its message carries the reason, not a P8 fallback. */
    private fun assertRefusedSaying(section: String, text: String, fragment: String)
    {
        try
        {
            read(text);
            fail("the generator accepted a bundle it must refuse: $section");
        }
        catch (refusal: JsonException)
        {
            assertTrue(
                "refusal for $section says '${refusal.message}', expected it to name: $fragment",
                refusal.message.orEmpty().contains(fragment)
            );
        }
    }

    /** Rewrites every string-typed field to the given spelling, keeping the JSON well-formed. */
    private fun withFieldType(spelling: String): String
    {
        val marker = "\"type\": \"string\"";
        assertTrue("the pinned bundle no longer carries a string field", bundleText.contains(marker));
        return bundleText.replace(marker, "\"type\": \"$spelling\"");
    }

    @Test
    fun thePinnedBundleReadsAndExposesTheNewSections()
    {
        val bundle = read(bundleText);
        assertEquals(listOf("clientProofV1", "none"), bundle.authClasses);
        assertEquals(90L, bundle.keyPolicyTtlDays);
        assertEquals("auth.keys.rotate", bundle.keyRotationOperationId);
        assertTrue(bundle.clientIdRule.contains("key owner"));
        assertTrue(bundle.operations.any { it.id == "auth.enroll.oauthNative" });
        assertTrue(bundle.operations.any { it.id == "auth.keys.rotate" });
        assertEquals("core.time", bundle.clockSynchronizationOperationId);
        assertEquals("serverTimeMillis", bundle.clockSynchronizationEpochField);
        assertEquals(null, bundle.operations.single { it.id == "core.time" }.requestType);
    }

    @Test
    fun aBundleWithoutClockSynchronizationIsRefused()
    {
        assertRefused("clockSynchronization", withoutKey("clockSynchronization"));
    }

    @Test
    fun aClockSynchronizationOperationNamingNoOperationIsRefusedExplicitly()
    {
        assertRefusedSaying(
            "clockSynchronization operation cross-reference",
            bundleText.replace("\"operation\": \"core.time\"", "\"operation\": \"core.missing\""),
            "which is not an operation"
        );
    }

    @Test
    fun aNonClockOperationWithoutARequestTypeIsRefused()
    {
        val marker = "      \"requestType\": \"HandshakeRequest\",\n";
        assertTrue(bundleText.contains(marker));
        assertRefusedSaying(
            "non-clock requestType",
            bundleText.replace(marker, ""),
            "auth.clientProof.handshake"
        );
    }

    @Test
    fun theBodylessClockOperationCannotAcquireARequestType()
    {
        val marker = "      \"responseType\": \"ServerTimeResponse\",";
        assertTrue(bundleText.contains(marker));
        assertRefusedSaying(
            "clock requestType",
            bundleText.replace(marker, "      \"requestType\": \"HandshakeRequest\",\n$marker"),
            "must have no requestType"
        );
    }

    @Test
    fun aBundleWithoutOperationAuthClassesIsRefused()
    {
        assertRefused("operationAuthClasses", withoutKey("operationAuthClasses"));
    }

    @Test
    fun aBundleWithoutKeyPolicyIsRefused()
    {
        assertRefused("keyPolicy", withoutKey("keyPolicy"));
    }

    @Test
    fun aBundleWithoutRestOperationsIsRefused()
    {
        assertRefused("restOperations", withoutKey("restOperations"));
    }

    @Test
    fun aBundleWithoutClientIdRuleIsRefused()
    {
        assertRefused("clientIdRule", withoutKey("clientIdRule"));
    }

    @Test
    fun anOperationNamingAnUndeclaredAuthClassIsRefused()
    {
        val marker = "\"authProfile\": \"none\"";
        assertTrue(bundleText.contains(marker));
        // Every `none` operation now names a class the contract does not declare. The
        // generator must refuse rather than emit `mystery` as a plausible class name.
        assertRefused(
            "operationAuthClasses cross-reference",
            bundleText.replace(marker, "\"authProfile\": \"mystery\"")
        );
    }

    // The 0.7.0/0.8.0 grammar: decimal<scale> is parsed, bounds-checked and emitted as
    // Swift Decimal / Kotlin BigDecimal through the SPFNDecimalCoding helpers, whose
    // encode side refuses — never rounds — a value finer than the scale.

    @Test
    fun aDecimalFieldEmitsTheDecimalTypesAndTheCodingCalls()
    {
        val bundle = read(withFieldType("decimal<2>"));

        val swift = SwiftEmitter.emit(bundle).values.joinToString("\n");
        assertTrue(swift.contains(": Decimal"));
        assertTrue(swift.contains("try SPFNDecimalCoding.scaledInteger("));
        assertTrue(swift.contains("SPFNDecimalCoding.decimal("));
        assertTrue(swift.contains("import Foundation"));

        val kotlin = KotlinEmitter.emit(bundle).values.joinToString("\n");
        assertTrue(kotlin.contains(": java.math.BigDecimal"));
        assertTrue(kotlin.contains("SpfnDecimalCoding.scaledInteger("));
        assertTrue(kotlin.contains("SpfnDecimalCoding.decimal("));
        assertTrue(kotlin.contains("import xyz.superfunction.spfn.core.SpfnDecimalCoding"));
    }

    /** A bundle with no decimal keeps its output free of the decimal imports. */
    @Test
    fun aBundleWithoutDecimalEmitsNoDecimalImport()
    {
        val bundle = read(bundleText);
        assertTrue(!SwiftEmitter.emit(bundle).values.joinToString("\n").contains("import Foundation"));
        assertTrue(!KotlinEmitter.emit(bundle).values.joinToString("\n").contains("SpfnDecimalCoding"));
    }

    @Test
    fun aDecimalScaleOutsideOneToEighteenIsRefused()
    {
        assertRefusedSaying("decimal<0> field", withFieldType("decimal<0>"), "outside 1..18");
        assertRefusedSaying("decimal<19> field", withFieldType("decimal<19>"), "outside 1..18");
    }

    @Test
    fun aMalformedDecimalScaleIsRefusedRatherThanReadAsATypeName()
    {
        assertRefusedSaying("decimal<2x> field", withFieldType("decimal<2x>"), "not a decimal spelling");
    }

    @Test
    fun aNumberFieldIsRefusedNamingTheSpellingThatReplacedIt()
    {
        assertRefusedSaying("number field", withFieldType("number"), "decimal<scale>");
    }

    /**
     * Contract 0.6.1 put `since`/`deprecatedIn`/`removedIn` on operations and an
     * `operationAvailability` block beside them. The generator consumes none of it and
     * must read past all of it: an unknown key is left alone, only a missing needed key
     * refuses.
     */
    @Test
    fun operationAvailabilityKeysAreReadPast()
    {
        val marker = "\"id\": \"echo.send\",";
        assertTrue(bundleText.contains(marker));
        val bundle = read(
            bundleText.replace(marker, "\"id\": \"echo.send\", \"deprecatedIn\": \"0.99.0\", \"removedIn\": \"1.0.0\",")
        );
        assertTrue(bundle.operations.any { it.id == "echo.send" });
    }

    // Contract 0.10.0 made `responseType` optional. `restOperations.responseBody` states
    // what an absent one means — "An operation that declares no responseType answers 204
    // with an empty body and there is nothing to decode" — so the generator must read it
    // as a declared fact and carry it onto the descriptor, while still refusing the two
    // shapes that are contract errors rather than bodyless operations.

    /** N7. A bundle whose operation declares no responseType generates, and says so. */
    @Test
    fun n7AnOperationWithoutAResponseTypeGeneratesAndTheDescriptorSaysSo()
    {
        val bundle = read(bundleText);

        val deny = bundle.operations.single { it.id == "auth.device.deny" };
        assertEquals(null, deny.responseType);
        assertFalse(deny.declaresResponse);
        assertTrue(bundle.operations.single { it.id == "auth.device.approve" }.declaresResponse);

        // The fact reaches both emitters, on the descriptor rather than in a comment.
        val swift = SwiftEmitter.emit(bundle).values.joinToString("\n");
        assertTrue(swift.contains("id: \"auth.device.deny\","));
        assertTrue(swift.contains("declaresResponse: false"));
        assertTrue(swift.contains("declaresResponse: true"));

        val kotlin = KotlinEmitter.emit(bundle).values.joinToString("\n");
        assertTrue(kotlin.contains("id = \"auth.device.deny\","));
        assertTrue(kotlin.contains("declaresResponse = false"));
        assertTrue(kotlin.contains("declaresResponse = true"));

        // Exactly one operation in this contract is bodyless. A generator that read every
        // absent key as absent — or every present one as absent — would pass the two
        // assertions above and fail this one.
        assertEquals(1, bundle.operations.count { !it.declaresResponse });
    }

    /**
     * N8. A responseType that is present must still name a declared type. This is the P8
     * shape: the else-branch must refuse rather than emit an undeclared name.
     */
    @Test
    fun n8AResponseTypeNamingAnUnknownTypeIsStillRefused()
    {
        val marker = "\"responseType\": \"ServerTimeResponse\",";
        assertTrue(bundleText.contains(marker));
        assertRefusedSaying(
            "unknown responseType",
            bundleText.replace(marker, "\"responseType\": \"NoSuchResponse\","),
            "unknown response type 'NoSuchResponse'"
        );
    }

    /**
     * N9. The clock operation is the one that may not use the new gap. Its response is
     * what anchors proof time, so a bundle that dropped its responseType is refused rather
     * than read as an operation that answers 204.
     */
    @Test
    fun n9AClockOperationWithoutAResponseTypeIsRefused()
    {
        val marker = "      \"responseType\": \"ServerTimeResponse\",\n";
        assertTrue(bundleText.contains(marker));
        assertRefusedSaying(
            "clock responseType",
            bundleText.replace(marker, ""),
            "must declare a responseType"
        );
    }

    // The per-operation call descriptors. What makes the file worth generating is that it
    // is complete and that the two shapes the contract distinguishes are not written by
    // hand — so those are what these three cases hold it to, with every expected name read
    // out of the bundle rather than out of a list repeated here (P10).

    private fun swiftCalls(bundle: Bundle): String = SwiftEmitter.emit(bundle)
        .getValue("Sources/SPFNGenerated/Generated/SPFNGeneratedCalls.swift")

    private fun kotlinCalls(bundle: Bundle): String = KotlinEmitter.emit(bundle)
        .getValue("$kotlinRoot/SpfnGeneratedCalls.kt")

    /**
     * One descriptor's own text, from its declaration to the `)` that closes it.
     *
     * Every assertion below reads a block rather than the whole file. A file-wide
     * `contains` is the way these cases could pass while proving nothing: an emitter that
     * paired `echoSend`'s name with `itemsList`'s operation writes both strings somewhere,
     * and only asking whether they are in the same block catches it.
     */
    private fun descriptorBlock(file: String, declaration: String): String
    {
        assertTrue("the calls file declares nothing matching '$declaration'", file.contains(declaration));
        val block = file.substringAfter(declaration).substringBefore("\n    )");
        assertTrue("the '$declaration' block is unterminated", block.length < file.length);
        return block;
    }

    /** Every operation, exactly once, built on its own operation, and nothing else. */
    @Test
    fun theCallsFileNamesEveryOperationExactlyOnce()
    {
        val bundle = read(bundleText);
        val swift = swiftCalls(bundle);
        val kotlin = kotlinCalls(bundle);

        assertEquals(16, bundle.operations.size);
        bundle.operations.forEach { operation ->
            val name = Names.lowerCamel(operation.id);
            assertEquals(
                "the Swift calls file declares '$name' other than once",
                1,
                Regex("public static let $name:").findAll(swift).count()
            );
            assertEquals(
                "the Kotlin calls file declares '$name' other than once",
                1,
                Regex("\n    val $name:").findAll(kotlin).count()
            );
            // The operation it is built on, inside its own block — not merely somewhere in
            // a file that also holds fifteen other operation names.
            assertTrue(
                "the Swift '$name' descriptor is not built on SPFNGeneratedOperations.$name",
                descriptorBlock(swift, "public static let $name:")
                    .contains("operation: SPFNGeneratedOperations.$name,")
            );
            assertTrue(
                "the Kotlin '$name' descriptor is not built on SpfnGeneratedOperations.$name",
                descriptorBlock(kotlin, "\n    val $name:")
                    .contains("operation = SpfnGeneratedOperations.$name,")
            );
        }

        // Nothing beyond the contract. A descriptor for an operation the bundle does not
        // declare would pass every assertion above.
        assertEquals(16, Regex("public static let \\w+:").findAll(swift).count());
        assertEquals(16, Regex("\n    val \\w+:").findAll(kotlin).count());
    }

    /**
     * The bodyless operation goes through the `noResponse` factory. Writing its decoder
     * into the generated file would compile and behave the same today — and would be a
     * second place for "there is nothing to decode" to be written down, which is the one
     * thing the factory exists to prevent.
     */
    @Test
    fun theBodylessOperationIsEmittedThroughTheNoResponseFactory()
    {
        val bundle = read(bundleText);
        val bodyless = bundle.operations.single { it.responseType == null };
        assertEquals("auth.device.deny", bodyless.id);
        val name = Names.lowerCamel(bodyless.id);
        val swiftRequest = Names.swiftType(bodyless.requestType!!);
        val kotlinRequest = Names.kotlinType(bodyless.requestType!!);

        val swift = swiftCalls(bundle);
        val swiftBlock = descriptorBlock(swift, "public static let $name:");
        assertTrue(
            "the Swift bodyless descriptor does not go through noResponse",
            swiftBlock.contains("SPFNCall<$swiftRequest, SPFNNoResponse>.noResponse(")
        );
        // Nothing to decode means no decoder in the block: a generated `decode:` here
        // would be the second copy of the factory's one correct closure.
        assertFalse(
            "the Swift bodyless descriptor also carries a hand-written decoder",
            swiftBlock.contains("decode:")
        );
        // And it is the only descriptor that uses the factory: a factory call on an
        // operation that does declare a response would answer with the unit value where
        // the server sent a body.
        assertEquals(1, Regex("\\.noResponse\\(").findAll(swift).count());

        val kotlin = kotlinCalls(bundle);
        val kotlinBlock = descriptorBlock(kotlin, "\n    val $name:");
        assertTrue(
            "the Kotlin bodyless descriptor does not go through noResponse",
            kotlinBlock.contains("SpfnCall<$kotlinRequest, SpfnNoResponse> = SpfnCall.noResponse(")
        );
        assertFalse(
            "the Kotlin bodyless descriptor also carries a hand-written decoder",
            kotlinBlock.contains("decode =")
        );
        assertEquals(1, Regex("SpfnCall\\.noResponse\\(").findAll(kotlin).count());
    }

    /**
     * The clock operation is the one the contract gives no `requestType`. Its descriptor
     * carries the no-request representation — `Void` / `Unit` — because the caller that
     * sends it today (`SPFNProcessServerClock` / `SpfnProcessServerClock`) sends no
     * request value at all.
     */
    @Test
    fun theRequestlessOperationIsEmittedWithTheNoRequestRepresentation()
    {
        val bundle = read(bundleText);
        val requestless = bundle.operations.single { it.requestType == null };
        assertEquals("core.time", requestless.id);
        val name = Names.lowerCamel(requestless.id);
        val swiftResponse = Names.swiftType(requestless.responseType!!);
        val kotlinResponse = Names.kotlinType(requestless.responseType!!);

        val swift = swiftCalls(bundle);
        val swiftBlock = descriptorBlock(swift, "public static let $name:");
        assertTrue(
            "the Swift requestless descriptor does not carry Void",
            swiftBlock.startsWith(" SPFNCall<Void, $swiftResponse> = SPFNCall(")
        );
        assertTrue(
            "the Swift requestless descriptor does not encode an empty canonical object",
            swiftBlock.contains("encode: { _ in SPFNCanonicalValue.object([:]) },")
        );
        // Only that one. Every other operation names a request type the bundle declares,
        // and an emitter that read a missing key as "no request" everywhere would put a
        // second Void descriptor in the file.
        assertEquals(1, Regex("SPFNCall<Void, ").findAll(swift).count());

        val kotlin = kotlinCalls(bundle);
        val kotlinBlock = descriptorBlock(kotlin, "\n    val $name:");
        assertTrue(
            "the Kotlin requestless descriptor does not carry Unit",
            kotlinBlock.startsWith(" SpfnCall<Unit, $kotlinResponse> = SpfnCall(")
        );
        assertTrue(
            "the Kotlin requestless descriptor does not encode an empty canonical object",
            kotlinBlock.contains("encode = { _ -> SpfnCanonicalValue.Obj(emptyMap()) },")
        );
        assertEquals(1, Regex("SpfnCall<Unit, ").findAll(kotlin).count());
    }

    @Test
    fun aRotationOperationNamingNoOperationIsRefused()
    {
        val marker = "\"rotationOperation\": \"auth.keys.rotate\"";
        assertTrue(bundleText.contains(marker));
        assertRefused(
            "keyPolicy.rotationOperation cross-reference",
            bundleText.replace(marker, "\"rotationOperation\": \"auth.keys.retire\"")
        );
    }
}
