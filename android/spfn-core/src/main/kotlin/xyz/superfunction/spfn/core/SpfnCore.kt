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
     * versions are published. Real phones have run the harness since 2026-08-07, but
     * neither platform has met the nine-cell device gate and no support row in
     * COMPATIBILITY.md claims a value.
     */
    const val IS_SCAFFOLD: Boolean = true

    const val DISCLAIMER: String =
        "SPFN Mobile alpha. Canonical serialization, clientProofV1 proof assembly and " +
            "key custody, generated clients, the transport and a cross-platform conformance " +
            "gate exist; local persistence and a hybrid web bridge do not exist at all. " +
            "Real phones have run the harness since 2026-08-07 — an iPhone 14 Pro and a " +
            "Samsung SM-F721N both reported hardware key custody, and two Android runs " +
            "completed eight and seven of the nine lifecycle cells — but neither platform " +
            "has met the nine-cell device gate. Alpha versions are published for evaluation: " +
            "there is no supported release, no support row in COMPATIBILITY.md that claims a " +
            "value, and no public support of any distribution channel."
}
