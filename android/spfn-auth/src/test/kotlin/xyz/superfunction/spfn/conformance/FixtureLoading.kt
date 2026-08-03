// SPFN Mobile — shared fixture loading for the Kotlin conformance suite.
//
// The Swift suite under Tests/SPFNConformanceTests reads the SAME files from the SAME
// directory. Neither suite carries its own copy of an expected value, so a vector
// cannot drift on one platform without the other noticing.

package xyz.superfunction.spfn.conformance

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File

object Fixtures
{
    /**
     * Repository root, injected by the Gradle test task rather than guessed, so the
     * suite reads the same checkout the build is running against.
     */
    val repoRoot: File = File(
        requireNotNull(System.getProperty("spfn.repoRoot"))
        {
            "spfn.repoRoot is not set; the module build script must pass it to the test task"
        }
    )

    private val directory: File = File(repoRoot, "Contracts/fixtures")

    /**
     * Loads a fixture through the SDK's own strict parser, so reading the evidence
     * exercises the thing being tested.
     */
    fun load(relativePath: String): SpfnCanonicalValue =
        SpfnCanonicalJson.parse(File(directory, relativePath).readBytes())

    fun bytes(relativePath: String): ByteArray = File(repoRoot, relativePath).readBytes()
}

fun SpfnCanonicalValue.members(): Map<String, SpfnCanonicalValue> =
    (this as? SpfnCanonicalValue.Obj)?.members ?: error("expected an object, got $this")

fun SpfnCanonicalValue.elements(): List<SpfnCanonicalValue> =
    (this as? SpfnCanonicalValue.Arr)?.elements ?: error("expected an array, got $this")

fun SpfnCanonicalValue.text(): String =
    (this as? SpfnCanonicalValue.Text)?.value ?: error("expected a string, got $this")

fun SpfnCanonicalValue.number(): Long =
    (this as? SpfnCanonicalValue.Integer)?.value ?: error("expected an integer, got $this")

fun Map<String, SpfnCanonicalValue>.text(key: String): String =
    (this[key] ?: error("fixture is missing '$key'")).text()

fun SpfnCanonicalValue.flag(): Boolean =
    (this as? SpfnCanonicalValue.Bool)?.value ?: error("expected a boolean, got $this")

fun Map<String, SpfnCanonicalValue>.number(key: String): Long =
    (this[key] ?: error("fixture is missing '$key'")).number()

fun Map<String, SpfnCanonicalValue>.bool(key: String): Boolean =
    (this[key] ?: error("fixture is missing '$key'")).flag()

fun Map<String, SpfnCanonicalValue>.list(key: String): List<SpfnCanonicalValue> =
    (this[key] ?: error("fixture is missing '$key'")).elements()

fun Map<String, SpfnCanonicalValue>.obj(key: String): Map<String, SpfnCanonicalValue> =
    (this[key] ?: error("fixture is missing '$key'")).members()

/** The synthetic test key the proof vectors are signed with. Never a credential. */
