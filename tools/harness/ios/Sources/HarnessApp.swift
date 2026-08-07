// SPFN Mobile — the iOS harness app's entry point.
//
// Built by tools/harness/run-harness.sh, driven by tools/harness/flows, and published
// nowhere. It exists so the SDK can be exercised through a screen: everything else this
// repository proves, it proves without one.

import SwiftUI

@main
struct HarnessApp: App
{
    var body: some Scene
    {
        WindowGroup
        {
            HarnessView()
        }
    }
}
