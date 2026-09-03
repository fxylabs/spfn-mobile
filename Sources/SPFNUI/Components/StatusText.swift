#if canImport(SwiftUI)
// SPFN Mobile — a line that says how something went.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/StatusText.kt.
//
// What it may say is the whole point of it existing as a component rather than as a
// `SpfnText` with a colour. A screen's failure is classified into a key and the key is looked
// up in ``SPFNStrings``; the server's own `message` never reaches here, because a server can
// put anything in that field including something it echoed back from the request
// (decision C7, and SPFNCore's own header on `SPFNErrorEnvelope`).
//
// The component cannot enforce that on its own — it takes a `String` like anything else —
// but it is the one place the rule is written next to the drawing, and section 14 of
// tools/validate/validate.sh is what refuses a generated view that reaches for `.message`.

import SwiftUI

/// One line of status: a refusal, or something worth knowing.
public struct StatusText: View
{
    private let kind: StatusKind
    private let text: String
    private let identifier: String?

    @Environment(\.colorScheme) private var scheme

    /// - Parameters:
    ///   - kind: error or info, which decides the colour and nothing else.
    ///   - text: a sentence from ``SPFNStrings``, never one a server sent.
    ///   - identifier: an accessibility id, when a runner has to read this line.
    public init(kind: StatusKind, text: String, identifier: String? = nil)
    {
        self.kind = kind
        self.text = text
        self.identifier = identifier
    }

    public var body: some View
    {
        let palette = spfnPalette(for: scheme)
        return Text(text)
            .font(SPFNTokens.caption)
            .foregroundStyle(kind == .error ? palette.error : palette.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier(identifier ?? "")
    }
}
#endif
