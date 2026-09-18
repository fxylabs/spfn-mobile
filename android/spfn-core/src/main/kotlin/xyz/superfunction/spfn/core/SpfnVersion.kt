// SPFN Mobile — the single mobile release-train version.
//
// Counterpart of Sources/SPFNCore/SPFNVersion.swift. One constant in one file because
// three other things are held to it: `tools/validate/validate.sh` greps this path for the
// contents of VERSION, SpfnCoreTest reads VERSION off disk and compares, and
// gradle.properties carries the same string for publication.

package xyz.superfunction.spfn.core

/** Mirror of the Swift `SPFNVersion`. Must equal the repository VERSION file. */
object SpfnVersion
{
    const val CURRENT: String = "0.1.0-alpha.3"
}
