#if canImport(SwiftUI)
// SPFN Mobile — the four controls a screen can put on itself.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Buttons.kt. Four
// public views over one private body, because the ROLE is what a screen spec declares
// (`actions.<a>.role`) and a generated view reads better naming the role than passing it.
//
// Three things every one of them holds to, and each is a defect this repository has already
// paid for once:
//
//   - the minimum touch target, in BOTH directions. A control smaller than 44pt is reachable
//     only through a hit area larger than itself, neighbouring hit areas then overlap, and a
//     runner tapping the reported centre taps the neighbour (P21).
//   - `busy` disables. A control that spins and still accepts a press sends the second
//     request the model is about to ignore, and the person pressing has no way to know that
//     — the screen looks identical either way.
//   - an accessibility identifier is an ARGUMENT and not an option. Every control in a
//     generated view is reached by `<screen>.<action>`, and a control a runner cannot find
//     is a cell that cannot be written.

import SwiftUI

/// The one thing this screen is for.
public struct PrimaryButton: View
{
    private let title: String
    private let identifier: String
    private let busy: Bool
    private let enabled: Bool
    private let onTap: () -> Void

    public init(
        title: String,
        identifier: String,
        busy: Bool = false,
        enabled: Bool = true,
        onTap: @escaping () -> Void
    )
    {
        self.title = title
        self.identifier = identifier
        self.busy = busy
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View
    {
        RoleButton(
            role: .primary,
            title: title,
            identifier: identifier,
            busy: busy,
            enabled: enabled,
            onTap: onTap
        )
    }
}

/// A control that is not the point of the screen.
public struct SecondaryButton: View
{
    private let title: String
    private let identifier: String
    private let busy: Bool
    private let enabled: Bool
    private let onTap: () -> Void

    public init(
        title: String,
        identifier: String,
        busy: Bool = false,
        enabled: Bool = true,
        onTap: @escaping () -> Void
    )
    {
        self.title = title
        self.identifier = identifier
        self.busy = busy
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View
    {
        RoleButton(
            role: .secondary,
            title: title,
            identifier: identifier,
            busy: busy,
            enabled: enabled,
            onTap: onTap
        )
    }
}

/// A control that takes something away.
public struct DestructiveButton: View
{
    private let title: String
    private let identifier: String
    private let busy: Bool
    private let enabled: Bool
    private let onTap: () -> Void

    public init(
        title: String,
        identifier: String,
        busy: Bool = false,
        enabled: Bool = true,
        onTap: @escaping () -> Void
    )
    {
        self.title = title
        self.identifier = identifier
        self.busy = busy
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View
    {
        RoleButton(
            role: .destructive,
            title: title,
            identifier: identifier,
            busy: busy,
            enabled: enabled,
            onTap: onTap
        )
    }
}

/// A control that reads as text: a cancel, a "not now".
public struct TextButton: View
{
    private let title: String
    private let identifier: String
    private let busy: Bool
    private let enabled: Bool
    private let onTap: () -> Void

    public init(
        title: String,
        identifier: String,
        busy: Bool = false,
        enabled: Bool = true,
        onTap: @escaping () -> Void
    )
    {
        self.title = title
        self.identifier = identifier
        self.busy = busy
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View
    {
        RoleButton(
            role: .text,
            title: title,
            identifier: identifier,
            busy: busy,
            enabled: enabled,
            onTap: onTap
        )
    }
}

/// What all four of them are.
///
/// The fill, the border and the radius are the injected theme's ``SPFNButtonAppearance``
/// rather than the system's — decision C2 refuses Material on the other platform, and a
/// screen that looked like two different apps would be the same divergence. The minimum
/// touch target around them is not the theme's and cannot be made smaller by one (P21).
private struct RoleButton: View
{
    let role: ControlRole
    let title: String
    let identifier: String
    let busy: Bool
    let enabled: Bool
    let onTap: () -> Void

    @Environment(\.spfnTheme) private var theme

    var body: some View
    {
        // A busy control is disabled as well as spinning: the model would ignore the second
        // press anyway, and a control that accepts a press it discards says nothing to the
        // person who made it.
        let live = enabled && !busy
        return Button(action: onTap)
        {
            HStack(spacing: theme.spacing.space2)
            {
                if busy
                {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(title)
                    .font(theme.typography.body)
            }
            .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
            .padding(.horizontal, theme.spacing.space4)
            // Inside the LABEL, because `.plain` takes the tap on the label's own hit shape and
            // an HStack that drew nothing but text answers only over the letters (P39).
            .contentShape(Rectangle())
        }
        .buttonStyle(RoleButtonStyle(appearance: theme.buttons.appearance(for: role), live: live))
        .frame(minWidth: Metrics.touchTarget, minHeight: Metrics.touchTarget)
        .disabled(!live)
        .accessibilityIdentifier(identifier)
    }
}

/// The press, which only a `ButtonStyle` is told about, and the appearance drawn around it.
///
/// The label is drawn as given — its `contentShape` is what answers a finger (P39) — and
/// dimmed while pressed, the feedback the plain style gave before a theme could colour one.
/// What this adds is the fill, the outline and the radius, with the fill following
/// ``SPFNButtonColors/pressedContainer`` while a finger is down.
private struct RoleButtonStyle: ButtonStyle
{
    let appearance: SPFNButtonAppearance
    let live: Bool

    func makeBody(configuration: Configuration) -> some View
    {
        RoleButtonBody(configuration: configuration, appearance: appearance, live: live)
    }
}

/// The styled body, as a view of its own because `makeBody` cannot hold `@Environment` and
/// the colours depend on the appearance the environment is in.
private struct RoleButtonBody: View
{
    let configuration: ButtonStyleConfiguration
    let appearance: SPFNButtonAppearance
    let live: Bool

    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        let colors = appearance.colors(for: scheme)
        let shape = RoundedRectangle(cornerRadius: appearance.cornerRadius)
        return configuration.label
            .opacity(configuration.isPressed ? 0.7 : 1)
            .foregroundStyle(colors.label(live: live))
            .background(shape.fill(colors.fill(live: live, pressed: configuration.isPressed)))
            .clipShape(shape)
            .overlay(outline(colors, shape: shape))
    }

    /// The outline, drawn only when the appearance has one: a zero-width stroke is still a
    /// stroke to some renderers, and "no border" should be nothing at all.
    @ViewBuilder
    private func outline(_ colors: SPFNButtonColors, shape: RoundedRectangle) -> some View
    {
        if appearance.borderWidth > 0
        {
            shape.strokeBorder(colors.outline(live: live), lineWidth: appearance.borderWidth)
        }
    }
}
#endif
