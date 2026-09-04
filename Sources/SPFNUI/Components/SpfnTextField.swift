#if canImport(SwiftUI)
// SPFN Mobile — one field, and the whole of what a keyboard does on this platform.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/SpfnTextField.kt.
// The prefix is the one `SpfnText` carries and for the same reason: `TextField` is SwiftUI's
// own, a generated screen imports both modules, and a second one would make every
// unqualified use ambiguous in a file first compiled on a Mac.
//
// ---------------------------------------------------------------------------
// The keyboard contract lives here and in Screen, and nowhere else
// ---------------------------------------------------------------------------
//
// Seven clauses, split between two components and no third:
//
//   Screen  — the body gets out of the keyboard's way, and a tap outside the field puts the
//             keyboard away.
//   here    — the KIND decides the keyboard raised, the return key says what pressing it
//             does, `autofocus` decides whether the field takes focus when the screen
//             appears, and typing clears the error under the field.
//
// The last one is `onChange` and it is deliberately the VIEW's call rather than the model's.
// A model that cleared its own error inside the text setter would clear it for a screen that
// has since been popped, which is the R9/P24 family: the model exposes the act and guards it
// with the same on-show test every answer passes through, and the view is what decides that
// editing is when to make it.
//
// ---------------------------------------------------------------------------
// `code` is the strict kind and the reason the enum exists
// ---------------------------------------------------------------------------
//
// A user code is machine-issued ASCII. Left as ordinary text, iOS capitalises its first
// letter, offers a correction for what looks like a word, and can substitute a smart dash
// for the hyphen in `ABCD-1234` — and the request then carries a code the server never
// issued, with no failure anywhere except a refusal the person cannot explain. So `code`
// asks for `.asciiCapable`, capitalises EVERY character rather than the first, and turns
// autocorrection off.

import SwiftUI

/// A labelled field with a border, a hint, and its refusal underneath it.
public struct SpfnTextField: View
{
    private let label: String
    private let hint: String
    private let kind: FieldKind
    private let identifier: String
    private let error: String?
    private let enabled: Bool
    private let submitOnReturn: Bool
    private let autofocus: Bool
    private let onSubmit: () -> Void
    private let onChange: (String) -> Void

    @Binding private var text: String
    @FocusState private var focused: Bool
    @Environment(\.colorScheme) private var scheme

    /// - Parameters:
    ///   - label: what the field is, drawn above it.
    ///   - hint: the placeholder inside it.
    ///   - kind: what is expected, which decides the keyboard. See ``FieldKind``.
    ///   - identifier: the accessibility id, `<screen>.<input>`. Not optional: a field a
    ///     runner cannot find is a cell nobody can write.
    ///   - text: the binding the screen holds.
    ///   - error: the refusal to draw under the field, or nil.
    ///   - enabled: false makes it uneditable and dims it.
    ///   - submitOnReturn: whether the return key performs this screen's action. It decides
    ///     the key's LABEL as well as its effect, so the key never says `go` and do nothing.
    ///   - autofocus: whether the field takes focus when the screen appears.
    ///   - onSubmit: what the return key does when `submitOnReturn`.
    ///   - onChange: called on every edit. The generated view spends it on clearing the
    ///     error; nothing here assumes that is all it is for.
    public init(
        label: String,
        hint: String = "",
        kind: FieldKind = .text,
        identifier: String,
        text: Binding<String>,
        error: String? = nil,
        enabled: Bool = true,
        submitOnReturn: Bool = false,
        autofocus: Bool = false,
        onSubmit: @escaping () -> Void = {},
        onChange: @escaping (String) -> Void = { _ in }
    )
    {
        self.label = label
        self.hint = hint
        self.kind = kind
        self.identifier = identifier
        self._text = text
        self.error = error
        self.enabled = enabled
        self.submitOnReturn = submitOnReturn
        self.autofocus = autofocus
        self.onSubmit = onSubmit
        self.onChange = onChange
    }

    public var body: some View
    {
        let palette = spfnPalette(for: scheme)
        return VStack(alignment: .leading, spacing: SPFNTokens.space1)
        {
            SpfnText(label, role: .caption, secondary: true)
            field(palette)
            if let error = error
            {
                StatusText(kind: .error, text: error, identifier: identifier + ".error")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func field(_ palette: SPFNPalette) -> some View
    {
        TextField(hint, text: $text)
            .font(kind == .code ? SPFNTokens.mono : SPFNTokens.body)
            .foregroundStyle(enabled ? palette.text : palette.textSecondary)
            .focused($focused)
            .disabled(!enabled)
            .modifier(KeyboardTraits(kind: kind, submitOnReturn: submitOnReturn))
            .onSubmit
            {
                if submitOnReturn
                {
                    onSubmit()
                }
            }
            .onChange(of: text)
            { _, updated in
                onChange(updated)
            }
            .padding(.horizontal, SPFNTokens.space3)
            .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                    .fill(palette.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: SPFNTokens.radiusSmall)
                    .strokeBorder(
                        error == nil ? palette.textSecondary : palette.error,
                        lineWidth: Metrics.borderWidth
                    )
            )
            .accessibilityIdentifier(identifier)
            // `.task` rather than `.onAppear`: a screen that is not on show never runs one,
            // which is the P24 half of autofocus — a field on a popped route does not steal
            // the keyboard back from the screen underneath it.
            .task
            {
                if autofocus && enabled
                {
                    focused = true
                }
            }
    }
}

/// The keyboard traits, in one modifier because half of them exist on one platform only.
///
/// `keyboardType` and `textInputAutocapitalization` are UIKit-backed and do not exist on
/// macOS; `submitLabel` and `autocorrectionDisabled` do. A chain written straight into the
/// body above would not compile for a mac target, and this package declares one.
private struct KeyboardTraits: ViewModifier
{
    let kind: FieldKind
    let submitOnReturn: Bool

    func body(content: Content) -> some View
    {
    #if os(iOS) || os(tvOS) || os(visionOS)
        content
            .keyboardType(keyboardType)
            .textInputAutocapitalization(capitalization)
            .autocorrectionDisabled(kind != .text)
            .submitLabel(submitOnReturn ? .go : .done)
    #else
        content
            .autocorrectionDisabled(kind != .text)
            .submitLabel(submitOnReturn ? .go : .done)
    #endif
    }

#if os(iOS) || os(tvOS) || os(visionOS)
    /// `.asciiCapable` for a code, because a machine-issued code has no characters outside
    /// ASCII and an emoji keyboard on one is an invitation to send something unsendable.
    private var keyboardType: UIKeyboardType
    {
        switch kind
        {
        case .code:
            return .asciiCapable
        case .text:
            return .default
        case .email:
            return .emailAddress
        case .number:
            return .numberPad
        }
    }

    /// Every character for a code, none for anything a server matches exactly.
    private var capitalization: TextInputAutocapitalization
    {
        switch kind
        {
        case .code:
            return .characters
        case .text:
            return .sentences
        case .email, .number:
            return .never
        }
    }
#endif
}
#endif
