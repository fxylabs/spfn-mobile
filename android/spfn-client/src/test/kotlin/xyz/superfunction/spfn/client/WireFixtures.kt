// SPFN Mobile — reading the wire vectors and the bundle they came from.
//
// The Swift suite under Tests/SPFNClientTests reads the SAME files from the SAME
// directory, so a header name cannot drift on one platform without the other noticing.
// Files are read through the SDK's own strict parser, so loading the evidence exercises
// the thing being tested.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File

object WireFixtures
{
    /**
     * Repository root, injected by the Gradle test task rather than guessed, so the
     * suite reads the same checkout the build is running against.
     */
    private val repoRoot: File = File(
        requireNotNull(System.getProperty("spfn.repoRoot"))
        {
            "spfn.repoRoot is not set; the module build script must pass it to the test task"
        }
    )

    fun load(relativePath: String): SpfnCanonicalValue =
        SpfnCanonicalJson.parse(File(repoRoot, relativePath).readBytes())

    /** `Contracts/fixtures/request/wire.json`, the fully assembled requests. */
    fun wire(): Map<String, SpfnCanonicalValue> = load("Contracts/fixtures/request/wire.json").members()

    /** One named vector out of the wire fixture. */
    fun vector(name: String): Map<String, SpfnCanonicalValue> =
        wire().list("vectors").map { it.members() }.firstOrNull { it.text("name") == name }
            ?: error("wire vector '$name' is missing")

    /**
     * `wireMapping` out of the pinned contract bundle itself, so the constants the SDK
     * compiles against can be checked against the contract rather than against a copy.
     */
    fun bundleWireMapping(): Map<String, SpfnCanonicalValue> =
        load("Contracts/spfn-mobile-contract.v1.json").members().obj("wireMapping")
}

fun SpfnCanonicalValue.members(): Map<String, SpfnCanonicalValue> =
    (this as? SpfnCanonicalValue.Obj)?.members ?: error("expected an object, got $this")

fun SpfnCanonicalValue.elements(): List<SpfnCanonicalValue> =
    (this as? SpfnCanonicalValue.Arr)?.elements ?: error("expected an array, got $this")

fun SpfnCanonicalValue.text(): String =
    (this as? SpfnCanonicalValue.Text)?.value ?: error("expected a string, got $this")

fun Map<String, SpfnCanonicalValue>.text(key: String): String =
    (this[key] ?: error("fixture is missing '$key'")).text()

fun Map<String, SpfnCanonicalValue>.list(key: String): List<SpfnCanonicalValue> =
    (this[key] ?: error("fixture is missing '$key'")).elements()

fun Map<String, SpfnCanonicalValue>.obj(key: String): Map<String, SpfnCanonicalValue> =
    (this[key] ?: error("fixture is missing '$key'")).members()

/** A fixture's `headers` array, as the ordered pairs the transport takes. */
fun Map<String, SpfnCanonicalValue>.headerPairs(key: String): List<Pair<String, String>> =
    list(key).map { entry ->
        val fields = entry.elements();
        require(fields.size == 2) { "a header entry must be [name, value]" };
        fields[0].text() to fields[1].text();
    }
