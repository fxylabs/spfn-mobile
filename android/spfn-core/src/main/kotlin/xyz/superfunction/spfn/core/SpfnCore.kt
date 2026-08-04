// SPFN Mobile — what this checkout actually is.
//
// Counterpart of Sources/SPFNCore/SPFNVersion.swift and SPFNScaffold.swift.

package xyz.superfunction.spfn.core

/** Mirror of the Swift `SPFNVersion`. Must equal the repository VERSION file. */
object SpfnVersion
{
    const val CURRENT: String = "0.1.0-alpha.3"
}

/** Machine-readable statement of what this checkout actually is. */
object SpfnScaffold
{
    /**
     * Still true. Canonical serialization, clientProofV1 on P-256 ECDSA, the key
     * lifecycle, dual codegen, the transport and a conformance gate exist and alpha
     * versions are published, but nothing has run on a device and no support row in
     * COMPATIBILITY.md claims a value.
     */
    const val IS_SCAFFOLD: Boolean = true

    const val DISCLAIMER: String =
        "SPFN Mobile alpha. Canonical serialization, clientProofV1 proof assembly and " +
            "key custody, generated clients, the transport and a cross-platform conformance " +
            "gate exist; local persistence and a hybrid web bridge do not exist at all. " +
            "Alpha versions are published for evaluation: there is " +
            "no supported release, no device evidence, and no public support of any " +
            "distribution channel."
}
