// SPFN Mobile — the server's wire mapping is the bundle's, not a second opinion.
//
// `SpfnReferenceWire` restates header names the generator does not emit. A restatement
// nobody checks is a copy that drifts, so this reads the pinned bundle and compares.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File

class SpfnReferenceWireConformanceTest
{
    @Test
    fun `header names match the pinned bundle`()
    {
        val headers = obj(obj(bundle(), "wireMapping"), "headers");
        val expected = headers.mapValues { (_, value) -> (value as SpfnCanonicalValue.Text).value };

        assertEquals(expected, SpfnReferenceWire.BY_CONTRACT_FIELD);
    }

    @Test
    fun `the request content type matches the pinned bundle`()
    {
        val wireMapping = obj(bundle(), "wireMapping");
        val declared = (wireMapping["requestContentType"] as SpfnCanonicalValue.Text).value;

        assertEquals(declared, SpfnReferenceWire.REQUEST_CONTENT_TYPE);
        assertTrue(SpfnReferenceWire.isRequestContentType(declared));
        assertTrue(SpfnReferenceWire.isRequestContentType("$declared; charset=utf-8"));
        assertTrue(!SpfnReferenceWire.isRequestContentType("text/plain"));
        assertTrue(!SpfnReferenceWire.isRequestContentType(null));
    }

    @Test
    fun `the only allowed profile is the one the bundle allowlists`()
    {
        val allowed = (obj(bundle(), "authProfiles")["allowed"] as SpfnCanonicalValue.Arr).elements
            .map { (it as SpfnCanonicalValue.Text).value };

        assertEquals(listOf(SpfnReferenceWire.PROFILE_NAME), allowed);
    }

    @Test
    fun `the replay window matches the pinned bundle`()
    {
        val declared = (obj(bundle(), "clientProofV1")["replayWindowMillis"] as SpfnCanonicalValue.Integer).value;

        assertEquals(declared, SpfnReferenceState.DEFAULT_REPLAY_WINDOW_MILLIS);
    }

    private fun bundle(): Map<String, SpfnCanonicalValue>
    {
        val root = System.getProperty("spfn.repoRoot") ?: error("spfn.repoRoot is not set");
        val file = File(root, "Contracts/spfn-mobile-contract.json");
        return (SpfnCanonicalJson.parse(file.readBytes()) as SpfnCanonicalValue.Obj).members;
    }

    private fun obj(members: Map<String, SpfnCanonicalValue>, key: String): Map<String, SpfnCanonicalValue> =
        (members[key] as SpfnCanonicalValue.Obj).members
}
