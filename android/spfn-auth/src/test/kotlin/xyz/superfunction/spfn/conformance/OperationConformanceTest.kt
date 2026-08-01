// SPFN Mobile — generated client conformance, Kotlin half of the parity gate.
//
// Every assertion here runs against generated code. If the generator changed shape, or
// the bundle changed and the clients were not regenerated, this suite is what notices.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecodingException
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse

class OperationConformanceTest
{
    @Test
    fun generatedBindingMatchesTheLock()
    {
        val lock = SpfnCanonicalJson.parse(Fixtures.bytes("Contracts/upstream.lock.json")).members();
        val contract = lock.obj("contract");

        assertEquals(contract.text("version"), SpfnGeneratedContract.BINDING.importedVersion);
        assertEquals(contract.text("manifestSha256"), SpfnGeneratedContract.BINDING.importedManifestSha256);
        assertEquals(contract.text("supportedRange"), SpfnGeneratedContract.BINDING.supportedRange);
        assertEquals(contract.number("major"), SpfnGeneratedContract.BINDING.supportedMajor.toLong());
    }

    @Test
    fun generatedBindingDigestMatchesTheBundleOnDisk()
    {
        assertEquals(
            "the generated header claims a digest the bundle does not have",
            SpfnGeneratedContract.BINDING.importedManifestSha256,
            SpfnDigest.sha256Hex(Fixtures.bytes("Contracts/spfn-mobile-contract.v1.json"))
        );
    }

    @Test
    fun theBundleIsNotClaimedToBeAnUpstreamExport()
    {
        assertFalse(
            "no SPFN primitives export exists; claiming one would be the failure the lock prevents",
            SpfnGeneratedContract.BINDING.isUpstreamExport
        );
        assertEquals("spfn-mobile-step2-dev-bundle", SpfnGeneratedContract.BINDING.origin);
    }

    @Test
    fun operationDescriptorsMatchTheBundle()
    {
        val bundle = SpfnCanonicalJson.parse(Fixtures.bytes("Contracts/spfn-mobile-contract.v1.json")).members();
        val declared = bundle.list("operations").map { it.members() };
        assertEquals(declared.size, SpfnGeneratedOperations.all.size);

        for (entry in declared)
        {
            val id = entry.text("id");
            val operation = SpfnGeneratedOperations.operation(id);
            assertNotNull("$id was not generated", operation);
            assertEquals(entry.text("method"), operation?.method);
            assertEquals(entry.text("path"), operation?.path);
            assertEquals("clientProofV1", operation?.authProfile);
        }

        assertNull(SpfnGeneratedOperations.operation("no.such.operation"));
    }

    @Test
    fun requestVectorsCanonicalizeIdentically()
    {
        val fixture = Fixtures.load("request/operations.json").members();
        val requests = fixture.list("requests");
        assertTrue(requests.isNotEmpty());

        for (request in requests)
        {
            val entry = request.members();
            val name = entry.text("name");
            val canonical = roundTrip(entry.text("type"), requireNotNull(entry["value"]));

            assertEquals(
                "canonical request bytes differ for '$name'",
                entry.text("canonical"),
                SpfnCanonicalJson.encodeToString(canonical)
            );
            assertEquals(
                "canonical request digest differs for '$name'",
                entry.text("sha256"),
                SpfnDigest.sha256Hex(SpfnCanonicalJson.encode(canonical))
            );
        }
    }

    @Test
    fun responseVectorsDecodeAndReEncodeIdentically()
    {
        val fixture = Fixtures.load("request/operations.json").members();
        val responses = fixture.list("responses");
        assertTrue(responses.isNotEmpty());

        for (response in responses)
        {
            val entry = response.members();
            val name = entry.text("name");
            val canonical = roundTrip(entry.text("type"), SpfnCanonicalJson.parse(entry.text("wire")));

            assertEquals(
                "canonical response bytes differ for '$name'",
                entry.text("canonical"),
                SpfnCanonicalJson.encodeToString(canonical)
            );
            assertEquals(
                "canonical response digest differs for '$name'",
                entry.text("sha256"),
                SpfnDigest.sha256Hex(SpfnCanonicalJson.encode(canonical))
            );
        }
    }

    @Test
    fun unsupportedContractMajorIsAnUpgradeError()
    {
        SpfnGeneratedContract.BINDING.requireSupported("1.4.0");
        try
        {
            SpfnGeneratedContract.BINDING.requireSupported("2.0.0");
            fail("a contract on another major must be refused");
        }
        catch (failure: SpfnDecodingException)
        {
            assertEquals("CONTRACT_UNSUPPORTED", failure.code);
        }
    }

    /**
     * Decodes a fixture value into the generated type it names, then re-encodes it.
     * The `when` is the one place a test has to know the contract's type names.
     */
    private fun roundTrip(type: String, value: SpfnCanonicalValue): SpfnCanonicalValue = when (type)
    {
        "HandshakeRequest" -> SpfnHandshakeRequest.decode(value).canonicalValue()
        "HandshakeResponse" -> SpfnHandshakeResponse.decode(value).canonicalValue()
        "EchoRequest" -> SpfnEchoRequest.decode(value).canonicalValue()
        "EchoResponse" -> SpfnEchoResponse.decode(value).canonicalValue()
        "ListItemsRequest" -> SpfnListItemsRequest.decode(value).canonicalValue()
        "ListItemsResponse" -> SpfnListItemsResponse.decode(value).canonicalValue()
        else -> error("fixture names an unknown type '$type'")
    }
}
