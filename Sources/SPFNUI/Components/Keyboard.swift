#if canImport(SwiftUI)
// SPFN Mobile — putting the keyboard away.
//
// Counterpart of the `FocusManager.clearFocus()` call in
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Screen.kt, which
// needs no file of its own because Compose hands the focus manager to any composable that
// asks. SwiftUI does not: `@FocusState` is per-field and a container has no way to reach the
// field's binding, so the only thing a Screen can do is ask the responder chain.
//
// Half of one clause of the keyboard contract. The other half — the body getting OUT of the
// keyboard's way — is layout, and `Screen` owns it on both platforms.

import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

/// Resigning first responder, where there is one.
///
/// A no-op on a platform with no keyboard to put away, rather than a compile error: this
/// package declares a mac target and `UIApplication` is not there.
enum SPFNKeyboard
{
    @MainActor
    static func dismiss()
    {
    #if canImport(UIKit)
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    #endif
    }
}
#endif
