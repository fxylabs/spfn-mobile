#if canImport(SwiftUI)
// SPFN Mobile — one line of text, in one of the four roles a screen has.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/SpfnText.kt.
//
// The name carries a prefix and its Kotlin twin carries the same one, which is the one place
// this component set gives up the module's bare naming. `Text` is SwiftUI's own and a screen
// imports both modules: a second `Text` would not shadow SwiftUI's, it would make every
// unqualified use of either one ambiguous, in generated files first compiled on a Mac
// nobody on this host can run. `SpfnTextField` carries the prefix for the same collision.
// The other six components collide with nothing and are spelled bare on both platforms.

import SwiftUI

/// Text drawn in a token font and a token colour.
///
/// - Parameters:
///   - text: what it says. Never a server's words — see ``SPFNStrings``.
///   - role: which of the four type tokens it is set in.
///   - secondary: whether it is the supporting colour rather than the primary one.
public struct SpfnText: View
{
    private let text: String
    private let role: TextRole
    private let secondary: Bool

    @Environment(\.colorScheme) private var scheme

    public init(_ text: String, role: TextRole = .body, secondary: Bool = false)
    {
        self.text = text
        self.role = role
        self.secondary = secondary
    }

    public var body: some View
    {
        let palette = spfnPalette(for: scheme)
        return Text(text)
            .font(font)
            .foregroundStyle(secondary ? palette.textSecondary : palette.text)
    }

    private var font: Font
    {
        switch role
        {
        case .title:
            return SPFNTokens.title
        case .body:
            return SPFNTokens.body
        case .caption:
            return SPFNTokens.caption
        case .mono:
            return SPFNTokens.mono
        }
    }
}
#endif
