// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

package xyz.superfunction.spfn.sync

import xyz.superfunction.spfn.core.SpfnNotImplementedInScaffoldException

/** Android counterpart of the Swift `SPFNPersistence`. */
object SpfnSync
{
    fun open(storeName: String): Nothing
    {
        throw SpfnNotImplementedInScaffoldException(
            symbol = "SpfnSync.open(storeName=$storeName)",
            plannedStep = "Step 3+ - persistence/sync expansion"
        )
    }
}
