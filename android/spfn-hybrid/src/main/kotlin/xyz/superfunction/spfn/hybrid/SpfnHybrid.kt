// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

package xyz.superfunction.spfn.hybrid

import xyz.superfunction.spfn.auth.SpfnAuthPolicy
import xyz.superfunction.spfn.auth.SpfnAuthProfile
import xyz.superfunction.spfn.core.SpfnNotImplementedInScaffoldException

/**
 * Android counterpart of the Swift `SPFNHybrid`.
 *
 * The compatibility gate requires proving this module exposes no browser-redirect
 * auth route, no credential injection into web content, and no generic JavaScript
 * bridge. Step 1 satisfies that by exposing no bridge at all.
 */
object SpfnHybrid
{
    val ALLOWED_BRIDGE_MESSAGE_NAMES: List<String> = emptyList()

    val ALLOWED_AUTH_PROFILES: List<SpfnAuthProfile> = SpfnAuthPolicy.ALLOWED_PROFILES

    fun attachBridge(name: String): Nothing
    {
        throw SpfnNotImplementedInScaffoldException(
            symbol = "SpfnHybrid.attachBridge(name=$name)",
            plannedStep = "Step 3+ - hybrid adapter expansion"
        )
    }
}
