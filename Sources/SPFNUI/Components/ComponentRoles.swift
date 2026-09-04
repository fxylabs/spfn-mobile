// SPFN Mobile — the four closed sets a component takes as an argument.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/ComponentRoles.kt.
// Every one of them is compared by section 13 of tools/validate/validate.sh, for the reason
// `Loadable` and `SheetDetent` are: a role only one platform has is a screen only one
// platform can be written for, and the screen spec names these roles by their spec spelling
// on both.
//
// Free of SwiftUI on purpose, so it compiles on the Linux host and the parity reader sees
// the same shape it sees in `SheetDetent`. What each role RESOLVES to — a font, a fill, a
// keyboard type — is the component's business and is written twice; what a role IS, is
// written once per platform and checked.

/// How a piece of text is set.
///
/// Four and no fifth: a screen that needed a heavier title would be asking for a token, not
/// for a role. The names are the spec's own — `screens.<s>.…` never says `headline`.
public enum TextRole: String, Sendable
{
    /// A screen's title, and the heaviest text a screen carries.
    case title

    /// Everything a person reads.
    case body

    /// A hint, a label, an error line.
    case caption

    /// Anything whose characters have to line up: a code, a readout.
    case mono
}

/// What a control means, which decides how it is drawn and nothing else.
///
/// Spelled exactly as `actions.<a>.role` spells it, so that a spec value reaches a component
/// argument without a translation table in between.
public enum ControlRole: String, Sendable
{
    /// The one thing this screen is for. Filled with the accent colour.
    case primary

    /// A control that is not the point of the screen. Outlined.
    case secondary

    /// A control that takes something away. Filled with the error colour.
    case destructive

    /// A control that reads as text: a cancel, a "not now".
    case text
}

/// What a field expects, which decides the keyboard it raises.
///
/// It is not the field's TYPE — every one of these is a string as far as the request is
/// concerned. It is what the person typing should be given: `code` is the strict one, and
/// the reason this enum exists at all (see ``SPFNTextField``).
public enum FieldKind: String, Sendable
{
    /// A short machine-issued code. ASCII, upper-cased, and never corrected.
    case code

    /// Free text.
    case text

    /// An email address.
    case email

    /// A number.
    case number
}

/// What a line of status text is saying.
public enum StatusKind: String, Sendable
{
    /// Something went wrong. Drawn in the error colour.
    case error

    /// Something worth knowing. Drawn in the secondary colour.
    case info
}
