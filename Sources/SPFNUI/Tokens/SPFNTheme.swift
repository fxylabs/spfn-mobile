#if canImport(SwiftUI)
// SPFN Mobile — the look an app gives the components, as one value it can inject.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnTheme.kt. The two
// files carry the SAME KEYS, type by type, and section 15 of tools/validate/validate.sh
// compares them: a theme key one platform has and the other does not is an app that can be
// themed on one platform only.
//
// ``SPFNTokens`` is where the DEFAULT comes from and nothing else. Every component reads the
// theme in its environment, and a view tree nobody themed reads ``SPFNTheme/default``, which
// is the tokens re-spelled — so an app that injects nothing draws exactly what it drew
// before the theme existed. Section 15 also refuses a component that reads ``SPFNTokens``
// directly, because that component is one an app cannot theme.
//
// What is NOT here is as deliberate as what is. The touch minimum, the header height and
// the icon size are the platform's (``Metrics``); a sheet's detents are geometry the flow
// decides (``SheetGeometry``); the transitions are one set shared by every navigator. None of
// those is a look, and a theme that could shrink a button under 44pt would be a theme that
// could bring back P21.
//
// Guarded whole, first line of code to last (docs/IMPLEMENTATION-PITFALLS.md P20): `Color`
// and `Font` are SwiftUI's and SPFNUI builds on Linux.

import SwiftUI

/// The four type roles a ``SpfnText`` can be set in.
///
/// A `Font` each and no line height: SwiftUI's `Font` carries none, and the line spacing a
/// `Text` takes is the platform's for the font it is given.
public struct SPFNTypography: Sendable, Equatable
{
    /// A screen's title.
    public let title: Font

    /// Everything a person reads.
    public let body: Font

    /// A hint, a caption, an error line.
    public let caption: Font

    /// Anything whose characters have to line up: a code, a readout.
    public let mono: Font

    public init(title: Font, body: Font, caption: Font, mono: Font)
    {
        self.title = title
        self.body = body
        self.caption = caption
        self.mono = mono
    }

    /// The font a role is set in. Written once so no component picks a font of its own.
    func font(for role: TextRole) -> Font
    {
        switch role
        {
        case .title:
            return title
        case .body:
            return body
        case .caption:
            return caption
        case .mono:
            return mono
        }
    }
}

/// The six gaps a layout is built out of, smallest first.
public struct SPFNSpacing: Sendable, Equatable
{
    /// The tightest gap: between a label and the thing it labels.
    public let space1: CGFloat

    /// Between two lines of one idea.
    public let space2: CGFloat

    /// Between a field and its error.
    public let space3: CGFloat

    /// The standard gutter, and the gap between two controls.
    public let space4: CGFloat

    /// Between two groups on one screen.
    public let space5: CGFloat

    /// Between a screen's header and what it introduces.
    public let space6: CGFloat

    public init(
        space1: CGFloat,
        space2: CGFloat,
        space3: CGFloat,
        space4: CGFloat,
        space5: CGFloat,
        space6: CGFloat
    )
    {
        self.space1 = space1
        self.space2 = space2
        self.space3 = space3
        self.space4 = space4
        self.space5 = space5
        self.space6 = space6
    }
}

/// The two corner radii.
public struct SPFNRadius: Sendable, Equatable
{
    /// A field, a button — anything a finger lands on.
    public let small: CGFloat

    /// A sheet, a card — anything a screen sits inside.
    public let large: CGFloat

    public init(small: CGFloat, large: CGFloat)
    {
        self.small = small
        self.large = large
    }
}

/// What one kind of button is drawn in, in one appearance.
///
/// `disabledBorder` is one more than the container and the content: an outlined control
/// that greys out greys its outline too, and a theme that could not say what to would leave
/// the outline at full strength around a label that had gone quiet.
public struct SPFNButtonColors: Sendable, Equatable
{
    /// What the control stands on.
    public let container: Color

    /// The label and the spinner.
    public let content: Color

    /// The outline, drawn only when the appearance's `borderWidth` is above zero.
    public let border: Color

    /// What the control stands on while a finger is down on it.
    public let pressedContainer: Color

    /// What a disabled or busy control stands on.
    public let disabledContainer: Color

    /// A disabled or busy control's label and spinner.
    public let disabledContent: Color

    /// A disabled or busy control's outline.
    public let disabledBorder: Color

    public init(
        container: Color,
        content: Color,
        border: Color,
        pressedContainer: Color,
        disabledContainer: Color,
        disabledContent: Color,
        disabledBorder: Color
    )
    {
        self.container = container
        self.content = content
        self.border = border
        self.pressedContainer = pressedContainer
        self.disabledContainer = disabledContainer
        self.disabledContent = disabledContent
        self.disabledBorder = disabledBorder
    }

    /// The fill for a control in this state.
    func fill(live: Bool, pressed: Bool) -> Color
    {
        if !live
        {
            return disabledContainer
        }
        return pressed ? pressedContainer : container
    }

    /// The label's colour for a control in this state.
    func label(live: Bool) -> Color
    {
        live ? content : disabledContent
    }

    /// The outline's colour for a control in this state.
    func outline(live: Bool) -> Color
    {
        live ? border : disabledBorder
    }
}

/// One kind of button: its colours per appearance and the shape they fill.
///
/// The minimum height is not here and cannot be: it is the platform's touch target
/// (``Metrics``, P21), and a control a theme could make smaller than a finger is a control a
/// device runner taps the neighbour of.
public struct SPFNButtonAppearance: Sendable, Equatable
{
    /// The colours in a light appearance.
    public let light: SPFNButtonColors

    /// The colours in a dark appearance.
    public let dark: SPFNButtonColors

    /// How thick the outline is drawn. Zero draws none.
    public let borderWidth: CGFloat

    /// The corner radius of the fill and the outline.
    public let cornerRadius: CGFloat

    public init(
        light: SPFNButtonColors,
        dark: SPFNButtonColors,
        borderWidth: CGFloat,
        cornerRadius: CGFloat
    )
    {
        self.light = light
        self.dark = dark
        self.borderWidth = borderWidth
        self.cornerRadius = cornerRadius
    }

    /// The colours for `scheme`.
    public func colors(for scheme: ColorScheme) -> SPFNButtonColors
    {
        scheme == .dark ? dark : light
    }
}

/// One appearance per button kind a screen spec can declare.
public struct SPFNButtons: Sendable, Equatable
{
    /// ``PrimaryButton``.
    public let primary: SPFNButtonAppearance

    /// ``SecondaryButton``.
    public let secondary: SPFNButtonAppearance

    /// ``DestructiveButton``.
    public let destructive: SPFNButtonAppearance

    /// ``TextButton``.
    public let text: SPFNButtonAppearance

    public init(
        primary: SPFNButtonAppearance,
        secondary: SPFNButtonAppearance,
        destructive: SPFNButtonAppearance,
        text: SPFNButtonAppearance
    )
    {
        self.primary = primary
        self.secondary = secondary
        self.destructive = destructive
        self.text = text
    }

    /// The appearance a control of `role` is drawn in.
    func appearance(for role: ControlRole) -> SPFNButtonAppearance
    {
        switch role
        {
        case .primary:
            return primary
        case .secondary:
            return secondary
        case .destructive:
            return destructive
        case .text:
            return text
        }
    }
}

/// Everything the components draw with, as one value an app injects with
/// ``SwiftUI/View/spfnTheme(_:)``.
public struct SPFNTheme: Sendable, Equatable
{
    /// The palette a light appearance reads.
    public let light: SPFNPalette

    /// The palette a dark appearance reads.
    public let dark: SPFNPalette

    /// The four type roles.
    public let typography: SPFNTypography

    /// The six gaps.
    public let spacing: SPFNSpacing

    /// The two corner radii.
    public let radius: SPFNRadius

    /// One appearance per button kind.
    public let buttons: SPFNButtons

    public init(
        light: SPFNPalette,
        dark: SPFNPalette,
        typography: SPFNTypography,
        spacing: SPFNSpacing,
        radius: SPFNRadius,
        buttons: SPFNButtons
    )
    {
        self.light = light
        self.dark = dark
        self.typography = typography
        self.spacing = spacing
        self.radius = radius
        self.buttons = buttons
    }

    /// The palette for `scheme`.
    ///
    /// Not a key: it is HOW a palette is chosen, and the two platforms choose by different
    /// mechanisms — the `colorScheme` environment value here, `isSystemInDarkTheme` there.
    public func palette(for scheme: ColorScheme) -> SPFNPalette
    {
        scheme == .dark ? dark : light
    }

    /// What every component draws with when nobody injected a theme: ``SPFNTokens``, key
    /// for key.
    public static let `default` = SPFNTheme(
        light: SPFNTokens.light,
        dark: SPFNTokens.dark,
        typography: SPFNTypography(
            title: SPFNTokens.title,
            body: SPFNTokens.body,
            caption: SPFNTokens.caption,
            mono: SPFNTokens.mono
        ),
        spacing: SPFNSpacing(
            space1: SPFNTokens.space1,
            space2: SPFNTokens.space2,
            space3: SPFNTokens.space3,
            space4: SPFNTokens.space4,
            space5: SPFNTokens.space5,
            space6: SPFNTokens.space6
        ),
        radius: SPFNRadius(small: SPFNTokens.radiusSmall, large: SPFNTokens.radiusLarge),
        buttons: DefaultButtons.buttons
    )
}

/// The four button appearances the components drew before there was a theme, derived from
/// the default palettes so that the two cannot drift.
private enum DefaultButtons
{
    static let buttons = SPFNButtons(
        primary: filled(SPFNTokens.light.accent, SPFNTokens.dark.accent),
        secondary: outlined(),
        destructive: filled(SPFNTokens.light.error, SPFNTokens.dark.error),
        text: bare()
    )

    /// A solid fill in `light`/`dark`, with the background's colour on it.
    private static func filled(_ light: Color, _ dark: Color) -> SPFNButtonAppearance
    {
        SPFNButtonAppearance(
            light: filledColors(light, SPFNTokens.light),
            dark: filledColors(dark, SPFNTokens.dark),
            borderWidth: 0,
            cornerRadius: SPFNTokens.radiusSmall
        )
    }

    private static func filledColors(_ fill: Color, _ palette: SPFNPalette) -> SPFNButtonColors
    {
        SPFNButtonColors(
            container: fill,
            content: palette.background,
            border: .clear,
            pressedContainer: fill,
            disabledContainer: palette.surface,
            disabledContent: palette.textSecondary,
            disabledBorder: .clear
        )
    }

    /// The raised surface, outlined in the text colour.
    private static func outlined() -> SPFNButtonAppearance
    {
        SPFNButtonAppearance(
            light: outlinedColors(SPFNTokens.light),
            dark: outlinedColors(SPFNTokens.dark),
            borderWidth: Metrics.borderWidth,
            cornerRadius: SPFNTokens.radiusSmall
        )
    }

    private static func outlinedColors(_ palette: SPFNPalette) -> SPFNButtonColors
    {
        SPFNButtonColors(
            container: palette.surface,
            content: palette.text,
            border: palette.text,
            pressedContainer: palette.surface,
            disabledContainer: palette.surface,
            disabledContent: palette.textSecondary,
            disabledBorder: palette.textSecondary
        )
    }

    /// No fill and no outline: the accent colour's words.
    private static func bare() -> SPFNButtonAppearance
    {
        SPFNButtonAppearance(
            light: bareColors(SPFNTokens.light),
            dark: bareColors(SPFNTokens.dark),
            borderWidth: 0,
            cornerRadius: SPFNTokens.radiusSmall
        )
    }

    private static func bareColors(_ palette: SPFNPalette) -> SPFNButtonColors
    {
        SPFNButtonColors(
            container: .clear,
            content: palette.accent,
            border: .clear,
            pressedContainer: .clear,
            disabledContainer: .clear,
            disabledContent: palette.textSecondary,
            disabledBorder: .clear
        )
    }
}

private struct SPFNThemeKey: EnvironmentKey
{
    /// A view tree nobody themed draws what the components drew before themes existed.
    static let defaultValue = SPFNTheme.default
}

extension EnvironmentValues
{
    /// The theme the components under this point draw with.
    public var spfnTheme: SPFNTheme
    {
        get { self[SPFNThemeKey.self] }
        set { self[SPFNThemeKey.self] = newValue }
    }
}

extension View
{
    /// Draws every SPFN component under this view with `theme`.
    ///
    /// An environment value, so the nearest injection wins: a `.spfnTheme` inside another
    /// one themes its own subtree and leaves the outer theme everywhere else.
    public func spfnTheme(_ theme: SPFNTheme) -> some View
    {
        environment(\.spfnTheme, theme)
    }
}
#endif
