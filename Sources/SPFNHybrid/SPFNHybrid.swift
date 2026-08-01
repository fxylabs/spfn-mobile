// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

import SPFNAuth
import SPFNCore

/// WebView adapter surface for hybrid apps.
///
/// The compatibility gate (topology artifact §6) requires proving that this module
/// exposes no browser-redirect auth route, no credential injection into web
/// content, and no generic JavaScript bridge. Step 1 satisfies that trivially by
/// exposing no bridge at all.
public enum SPFNHybrid
{
    /// The bridge message names this SDK will accept. Empty by design in Step 1;
    /// a generic passthrough bridge is prohibited, so this list stays explicit.
    public static let allowedBridgeMessageNames: [String] = []

    /// Auth profiles the hybrid surface may participate in. Mirrors the core
    /// allowlist; the hybrid layer never widens it.
    public static let allowedAuthProfiles: [SPFNAuthProfile] = SPFNAuthPolicy.allowedProfiles

    /// Deliberately unimplemented. No credential ever crosses into web content.
    public static func attachBridge(named _: String) throws -> Never
    {
        throw SPFNScaffoldError.notImplementedInScaffold(
            symbol: "SPFNHybrid.attachBridge(named:)",
            plannedStep: "Step 3+ — hybrid adapter expansion"
        )
    }
}
