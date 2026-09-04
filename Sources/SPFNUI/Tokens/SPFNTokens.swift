#if canImport(SwiftUI)
// SPFN Mobile — every visual value the components draw with, in one file.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnTokens.kt. The two
// files carry the SAME KEYS and different spellings of the same values, and section 15 of
// tools/validate/validate.sh compares the key sets. A token added to one platform only is
// a component that can be written for one platform only, which is the divergence section 13
// prevents for the state vocabulary and this prevents for the visual one.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository
// is (docs/IMPLEMENTATION-PITFALLS.md P20): `Color` and `Font` are SwiftUI's and SPFNUI
// builds on Linux.
//
// ---------------------------------------------------------------------------
// The values are neutral and they are placeholders. The KEYS are not.
// ---------------------------------------------------------------------------
//
// Decision S10: what a token is called, and which token a component reaches for, is settled
// here and now; what colour it resolves to is settled by the design flow that has not run
// yet. So every value below is the plainest thing that can be correct — the system font,
// black on white, one grey in two shades, one blue, one red — and replacing them is
// expected to touch this file and its twin and nothing else.
//
// ``dark`` is STRUCTURE ONLY. It carries the light palette's values today, so that every
// component is already written against a palette it looks up rather than against constants,
// and the day the dark values arrive they arrive as six numbers in one place. A dark palette
// that did not exist at all would mean every component had to grow a branch later; a dark
// palette guessed at now would ship a theme nobody designed.

import SwiftUI

/// The six colours a screen is drawn out of, as one value so that a scheme is one lookup.
///
/// A struct rather than six statics per scheme: a component asks the environment for the
/// scheme once and then reads colours off the answer, which is what makes "the same
/// component in the dark palette" a different value rather than a different code path.
public struct SPFNPalette: Sendable
{
    /// The surface a screen stands on.
    public let background: Color

    /// A surface raised above the background — a field, a sheet, a card.
    public let surface: Color

    /// Body text and anything a person reads first.
    public let text: Color

    /// Text that supports other text: a hint, a caption, a disabled control.
    public let textSecondary: Color

    /// The one colour that means "this is the thing to press".
    public let accent: Color

    /// The one colour that means "this went wrong".
    public let error: Color

    public init(
        background: Color,
        surface: Color,
        text: Color,
        textSecondary: Color,
        accent: Color,
        error: Color
    )
    {
        self.background = background
        self.surface = surface
        self.text = text
        self.textSecondary = textSecondary
        self.accent = accent
        self.error = error
    }
}

/// What every SPFN component draws with.
public enum SPFNTokens
{
    /// The palette a light appearance reads.
    public static let light = SPFNPalette(
        background: Color(red: 1.0, green: 1.0, blue: 1.0),
        surface: Color(red: 0.957, green: 0.957, blue: 0.965),
        text: Color(red: 0.0, green: 0.0, blue: 0.0),
        textSecondary: Color(red: 0.42, green: 0.42, blue: 0.44),
        accent: Color(red: 0.043, green: 0.373, blue: 1.0),
        error: Color(red: 0.776, green: 0.157, blue: 0.157)
    )

    /// The palette a dark appearance reads, which today is the light one.
    ///
    /// Structure without values, deliberately: see this file's header. Every component
    /// already resolves a palette, so the dark theme is a change to these six lines.
    public static let dark = SPFNPalette(
        background: Color(red: 1.0, green: 1.0, blue: 1.0),
        surface: Color(red: 0.957, green: 0.957, blue: 0.965),
        text: Color(red: 0.0, green: 0.0, blue: 0.0),
        textSecondary: Color(red: 0.42, green: 0.42, blue: 0.44),
        accent: Color(red: 0.043, green: 0.373, blue: 1.0),
        error: Color(red: 0.776, green: 0.157, blue: 0.157)
    )

    /// The tightest gap: between a label and the thing it labels.
    public static let space1: CGFloat = 4

    /// Between two lines of one idea.
    public static let space2: CGFloat = 8

    /// Between a field and its error.
    public static let space3: CGFloat = 12

    /// The standard gutter, and the gap between two controls.
    public static let space4: CGFloat = 16

    /// Between two groups on one screen.
    public static let space5: CGFloat = 24

    /// Between a screen's header and what it introduces.
    public static let space6: CGFloat = 32

    /// A field, a button — anything a finger lands on.
    public static let radiusSmall: CGFloat = 8

    /// A sheet, a card — anything a screen sits inside.
    public static let radiusLarge: CGFloat = 16

    /// A screen's title.
    public static let title: Font = .system(size: 20, weight: .semibold)

    /// Everything a person reads.
    public static let body: Font = .system(size: 16)

    /// A hint, a caption, an error line.
    public static let caption: Font = .system(size: 13)

    /// Anything whose characters have to line up: a code, a readout.
    public static let mono: Font = .system(size: 13, design: .monospaced)
}

/// The palette for `scheme`.
///
/// Not a token and deliberately not in the key set: it is HOW a palette is chosen, and the
/// two platforms choose one by different mechanisms — a SwiftUI environment value here, a
/// Compose `isSystemInDarkTheme` there. A key that could not mean the same thing on both
/// sides has no business in a set the two sides are compared on.
///
/// A free function rather than an `EnvironmentValues` extension, because `@Environment`
/// tracks a key path into a STORED value: a computed property over `colorScheme` reads
/// correctly and invalidates on more than it needs to. Every component holds
/// `@Environment(\.colorScheme)` and calls this, which is one line more and no ambiguity.
func spfnPalette(for scheme: ColorScheme) -> SPFNPalette
{
    scheme == .dark ? SPFNTokens.dark : SPFNTokens.light
}

#endif
