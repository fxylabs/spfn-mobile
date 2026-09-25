#if canImport(SwiftUI)
// SPFN Mobile — what a theme resolves to, injected and not.
//
// Counterpart of
// android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnThemeTest.kt, case for
// case (T1–T4 of the theming brief; T5 is the validator's, in
// tools/validate/probe-ui-vocabulary-rules.sh).
//
// What a component DRAWS is not observable without a renderer. What is observable is every
// lookup a component makes on its way to drawing — `appearance(for:)`, `colors(for:)`,
// `fill`, `label`, `outline`, `font(for:)`, `palette(for:)` — which are what `RoleButton`,
// `SpfnText` and the rest call and nothing else. The expectations for the default are written
// out from what the components drew BEFORE the theme existed (the old `foreground` and
// `background` in Components/Buttons.swift), not read off `SPFNTheme.default`, which would
// agree with itself by construction (P10).
//
// T4 hosts a real view tree, because nesting is the one claim that is about the environment
// rather than about a value.
//
// Guarded whole (docs/IMPLEMENTATION-PITFALLS.md P20): SwiftUI is not on Linux, and neither is
// anything this file tests.

import SwiftUI
import XCTest
@testable import SPFNUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

private let light = SPFNTokens.light

final class ThemeTests: XCTestCase
{
    // --- T1: no injection draws what the constants drew ---------------------------------

    func testT1TheDefaultPalettesAreTheTokenPalettes()
    {
        XCTAssertEqual(SPFNTheme.default.palette(for: .light), SPFNTokens.light)
        XCTAssertEqual(SPFNTheme.default.palette(for: .dark), SPFNTokens.dark)
    }

    func testT1TheDefaultTypeRolesAreTheTokenFonts()
    {
        let typography = SPFNTheme.default.typography
        XCTAssertEqual(typography.font(for: .title), SPFNTokens.title)
        XCTAssertEqual(typography.font(for: .body), SPFNTokens.body)
        XCTAssertEqual(typography.font(for: .caption), SPFNTokens.caption)
        XCTAssertEqual(typography.font(for: .mono), SPFNTokens.mono)
    }

    func testT1TheDefaultSpacingAndRadiiAreTheTokenSizes()
    {
        let spacing = SPFNTheme.default.spacing
        XCTAssertEqual(
            [spacing.space1, spacing.space2, spacing.space3, spacing.space4, spacing.space5, spacing.space6],
            [4, 8, 12, 16, 24, 32]
        )
        XCTAssertEqual(SPFNTheme.default.radius.small, SPFNTokens.radiusSmall)
        XCTAssertEqual(SPFNTheme.default.radius.large, SPFNTokens.radiusLarge)
    }

    func testT1ADefaultPrimaryIsTheAccentUnderTheBackgroundColour()
    {
        let colors = SPFNTheme.default.buttons.appearance(for: .primary).colors(for: .light)
        XCTAssertEqual(colors.fill(live: true, pressed: false), light.accent)
        XCTAssertEqual(colors.fill(live: true, pressed: true), light.accent)
        XCTAssertEqual(colors.label(live: true), light.background)
        XCTAssertEqual(colors.fill(live: false, pressed: false), light.surface)
        XCTAssertEqual(colors.label(live: false), light.textSecondary)
    }

    func testT1ADefaultDestructiveIsTheErrorColourWhereAPrimaryIsTheAccent()
    {
        let colors = SPFNTheme.default.buttons.appearance(for: .destructive).colors(for: .light)
        XCTAssertEqual(colors.fill(live: true, pressed: false), light.error)
        XCTAssertEqual(colors.label(live: true), light.background)
        XCTAssertEqual(colors.fill(live: false, pressed: false), light.surface)
        XCTAssertEqual(colors.label(live: false), light.textSecondary)
    }

    func testT1ADefaultSecondaryIsTheSurfaceOutlinedInTheTextColour()
    {
        let appearance = SPFNTheme.default.buttons.appearance(for: .secondary)
        let colors = appearance.colors(for: .light)
        XCTAssertEqual(appearance.borderWidth, 1)
        XCTAssertEqual(colors.fill(live: true, pressed: false), light.surface)
        XCTAssertEqual(colors.fill(live: false, pressed: false), light.surface)
        XCTAssertEqual(colors.label(live: true), light.text)
        XCTAssertEqual(colors.outline(live: true), light.text)
        XCTAssertEqual(colors.label(live: false), light.textSecondary)
        XCTAssertEqual(colors.outline(live: false), light.textSecondary)
    }

    func testT1ADefaultTextButtonIsTheAccentsWordsOnNothing()
    {
        let appearance = SPFNTheme.default.buttons.appearance(for: .text)
        let colors = appearance.colors(for: .light)
        XCTAssertEqual(appearance.borderWidth, 0)
        XCTAssertEqual(colors.fill(live: true, pressed: false), Color.clear)
        XCTAssertEqual(colors.label(live: true), light.accent)
        XCTAssertEqual(colors.label(live: false), light.textSecondary)
    }

    func testT1EveryDefaultButtonKeepsTheSmallRadius()
    {
        for role in [ControlRole.primary, .secondary, .destructive, .text]
        {
            XCTAssertEqual(
                SPFNTheme.default.buttons.appearance(for: role).cornerRadius,
                SPFNTokens.radiusSmall,
                "\(role)"
            )
        }
    }

    // --- T2: a distinct primary container reaches the primary and nothing else ------------

    func testT2AnInjectedPrimaryContainerIsThePrimarysFillAndNotTheSecondarys()
    {
        let themed = theme(primaryContainer: .red)
        XCTAssertEqual(
            themed.buttons.appearance(for: .primary).colors(for: .light).fill(live: true, pressed: false),
            .red
        )
        XCTAssertEqual(
            themed.buttons.appearance(for: .secondary).colors(for: .light).fill(live: true, pressed: false),
            light.surface
        )
    }

    // --- T3: a dark appearance reads the dark half --------------------------------------

    func testT3ADarkAppearanceReadsTheInjectedDarkPalette()
    {
        let night = SPFNPalette(
            background: .black,
            surface: .gray,
            text: .white,
            textSecondary: .gray,
            accent: .orange,
            error: .pink,
            handle: .white
        )
        let base = SPFNTheme.default
        let themed = SPFNTheme(
            light: base.light,
            dark: night,
            typography: base.typography,
            spacing: base.spacing,
            radius: base.radius,
            buttons: base.buttons
        )
        XCTAssertEqual(themed.palette(for: .dark), night)
        XCTAssertEqual(themed.palette(for: .light), light)
    }

    func testT3ADarkAppearanceReadsAButtonsDarkColours()
    {
        let primary = SPFNTheme.default.buttons.primary
        let themed = SPFNButtonAppearance(
            light: primary.light,
            dark: colors(primary.dark, container: .purple),
            borderWidth: primary.borderWidth,
            cornerRadius: primary.cornerRadius
        )
        XCTAssertEqual(themed.colors(for: .dark).fill(live: true, pressed: false), .purple)
        XCTAssertEqual(themed.colors(for: .light).fill(live: true, pressed: false), light.accent)
    }

    // --- T4: the nearest injection wins -------------------------------------------------

    func testT4AnInnerThemeWinsInsideItAndTheOuterOneStandsOutsideIt() async
    {
        let outer = theme(primaryContainer: .green)
        let inner = theme(primaryContainer: .blue)
        let seen = await MainActor.run
        {
            let seen = Seen()
            host(
                VStack
                {
                    ThemeProbe(name: "unthemed", seen: seen)
                    VStack
                    {
                        ThemeProbe(name: "before", seen: seen)
                        VStack
                        {
                            ThemeProbe(name: "inside", seen: seen)
                        }
                        .spfnTheme(inner)
                        ThemeProbe(name: "after", seen: seen)
                    }
                    .spfnTheme(outer)
                }
            )
            return seen.themes
        }
        XCTAssertEqual(seen["unthemed"], SPFNTheme.default)
        XCTAssertEqual(seen["before"], outer)
        XCTAssertEqual(seen["inside"], inner)
        XCTAssertEqual(seen["after"], outer)
        XCTAssertNotEqual(outer, inner)
    }

    // --- builders -----------------------------------------------------------------------

    private func theme(primaryContainer: Color) -> SPFNTheme
    {
        let base = SPFNTheme.default
        let primary = base.buttons.primary
        return SPFNTheme(
            light: base.light,
            dark: base.dark,
            typography: base.typography,
            spacing: base.spacing,
            radius: base.radius,
            buttons: SPFNButtons(
                primary: SPFNButtonAppearance(
                    light: colors(primary.light, container: primaryContainer),
                    dark: primary.dark,
                    borderWidth: primary.borderWidth,
                    cornerRadius: primary.cornerRadius
                ),
                secondary: base.buttons.secondary,
                destructive: base.buttons.destructive,
                text: base.buttons.text
            )
        )
    }

    private func colors(_ base: SPFNButtonColors, container: Color) -> SPFNButtonColors
    {
        SPFNButtonColors(
            container: container,
            content: base.content,
            border: base.border,
            pressedContainer: container,
            disabledContainer: base.disabledContainer,
            disabledContent: base.disabledContent,
            disabledBorder: base.disabledBorder
        )
    }
}

/// Where each probe writes the theme it was drawn under.
@MainActor
private final class Seen
{
    var themes: [String: SPFNTheme] = [:]
}

/// Reads the theme in its environment and reports it, by name, the moment it is drawn.
private struct ThemeProbe: View
{
    let name: String
    let seen: Seen

    @Environment(\.spfnTheme) private var theme

    var body: some View
    {
        seen.themes[name] = theme
        return Color.clear.frame(width: 1, height: 1)
    }
}

/// Lays `view` out once in a real hosting view, which is what evaluates every `body` in it.
@MainActor
private func host<V: View>(_ view: V)
{
#if canImport(UIKit)
    let controller = UIHostingController(rootView: view)
    controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 320)
    controller.view.layoutIfNeeded()
#elseif canImport(AppKit)
    let hosting = NSHostingView(rootView: view)
    hosting.frame = CGRect(x: 0, y: 0, width: 320, height: 320)
    hosting.layoutSubtreeIfNeeded()
#endif
}
#endif
