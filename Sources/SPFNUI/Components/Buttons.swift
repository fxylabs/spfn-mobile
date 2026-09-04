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
/// `buttonStyle(.plain)` because the fill, the border and the radius are this repository's
/// tokens rather than the system's — decision C2 refuses Material on the other platform, and
/// a screen that looked like two different apps would be the same divergence.
private struct RoleButton: View
{
    let role: ControlRole
    let title: String
    let identifier: String
    let busy: Bool
    let enabled: Bool
    let onTap: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        let palette = spfnPalette(for: scheme)
        // A busy control is disabled as well as spinning: the model would ignore the second
        // press anyway, and a control that accepts a press it discards says nothing to the
        // person who made it.
        let live = enabled && !busy
        return Button(action: onTap)
        {
            HStack(spacing: SPFNTokens.space2)
            {
                if busy
                {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(title)
                    .font(SPFNTokens.body)
            }
            .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
            .padding(.horizontal, SPFNTokens.space4)
        }
        .buttonStyle(.plain)
        .foregroundStyle(foreground(palette, live: live))
        .background(background(palette, live: live))
        .clipShape(RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall))
        .overlay(border(palette, live: live))
        .frame(minWidth: Metrics.touchTarget, minHeight: Metrics.touchTarget)
        .disabled(!live)
        .accessibilityIdentifier(identifier)
    }

    private func foreground(_ palette: SPFNPalette, live: Bool) -> Color
    {
        if !live
        {
            return palette.textSecondary
        }
        switch role
        {
        case .primary, .destructive:
            return palette.background
        case .secondary:
            return palette.text
        case .text:
            return palette.accent
        }
    }

    @ViewBuilder
    private func background(_ palette: SPFNPalette, live: Bool) -> some View
    {
        switch role
        {
        case .primary:
            RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                .fill(live ? palette.accent : palette.surface)
        case .destructive:
            RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                .fill(live ? palette.error : palette.surface)
        case .secondary:
            RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                .fill(palette.surface)
        case .text:
            Color.clear
        }
    }

    @ViewBuilder
    private func border(_ palette: SPFNPalette, live: Bool) -> some View
    {
        if role == .secondary
        {
            RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                .strokeBorder(
                    live ? palette.text : palette.textSecondary,
                    lineWidth: Metrics.borderWidth
                )
        }
        else
        {
            EmptyView()
        }
    }
}
#endif
