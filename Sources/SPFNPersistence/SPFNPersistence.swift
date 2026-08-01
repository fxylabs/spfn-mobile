// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

import SPFNCore

/// Local persistence and sync surface.
///
/// Named `SPFNPersistence` on Swift and `spfn-sync` on Android, carried verbatim
/// from the approved topology artifact §3. Reconciling the two names is an
/// UNRESOLVED decision recorded in docs/OPEN-DECISIONS.md.
public enum SPFNPersistence
{
    /// Deliberately unimplemented; store selection, migration and sync semantics
    /// are later-step work (topology artifact §9 item 7).
    public static func open(storeName _: String) throws -> Never
    {
        throw SPFNScaffoldError.notImplementedInScaffold(
            symbol: "SPFNPersistence.open(storeName:)",
            plannedStep: "Step 3+ — persistence/sync expansion"
        )
    }
}
