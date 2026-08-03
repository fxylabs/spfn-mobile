// SPFN Mobile — what this checkout actually is.
//
// Counterpart of Sources/SPFNCore/SPFNVersion.swift and SPFNScaffold.swift.

package xyz.superfunction.spfn.core

/** Mirror of the Swift `SPFNVersion`. Must equal the repository VERSION file. */
object SpfnVersion
{
    const val CURRENT: String = "0.1.0-alpha.2"
}

/** Mirror of the Swift `SPFNScaffoldError.notImplementedInScaffold`. */
class SpfnNotImplementedInScaffoldException(
    val symbol: String,
    val plannedStep: String
) : IllegalStateException("$symbol is not implemented yet (planned: $plannedStep)")

/** Machine-readable statement of what this checkout actually is. */
object SpfnScaffold
{
    /**
     * Still true. Step 2 added a real vertical slice — canonical serialization,
     * clientProofV1, dual codegen and a conformance gate — but nothing has been
     * committed, tagged, published or independently reviewed, and transport,
     * persistence and the hybrid bridge do not exist.
     */
    const val IS_SCAFFOLD: Boolean = true

    const val DISCLAIMER: String =
        "SPFN Mobile Step 2 vertical slice. Canonical serialization, clientProofV1 proof " +
            "assembly, generated clients and a cross-platform conformance gate exist; " +
            "transport, persistence and the hybrid bridge do not. There is " +
            "no supported release, no registry publication, and no public support of any " +
            "distribution channel."
}
