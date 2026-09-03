// SPFN Mobile — the four closed sets a component takes as an argument.
//
// Counterpart of Sources/SPFNUI/Components/ComponentRoles.swift. Every one of them is
// compared by section 13 of tools/validate/validate.sh, for the reason `Loadable` and
// `SheetDetent` are: a role only one platform has is a screen only one platform can be
// written for, and the screen spec names these roles by their spec spelling on both.
//
// Free of Compose on purpose. What each role RESOLVES to — a text style, a fill, a keyboard
// type — is the component's business and is written twice; what a role IS, is written once
// per platform and checked.

package xyz.superfunction.spfn.ui.components

/**
 * How a piece of text is set.
 *
 * Four and no fifth: a screen that needed a heavier title would be asking for a token, not
 * for a role. The names are the spec's own — `screens.<s>.…` never says `headline`.
 */
public enum class TextRole
{
    /** A screen's title, and the heaviest text a screen carries. */
    Title,

    /** Everything a person reads. */
    Body,

    /** A hint, a label, an error line. */
    Caption,

    /** Anything whose characters have to line up: a code, a readout. */
    Mono
}

/**
 * What a control means, which decides how it is drawn and nothing else.
 *
 * Spelled exactly as `actions.<a>.role` spells it, so that a spec value reaches a component
 * argument without a translation table in between.
 */
public enum class ControlRole
{
    /** The one thing this screen is for. Filled with the accent colour. */
    Primary,

    /** A control that is not the point of the screen. Outlined. */
    Secondary,

    /** A control that takes something away. Filled with the error colour. */
    Destructive,

    /** A control that reads as text: a cancel, a "not now". */
    Text
}

/**
 * What a field expects, which decides the keyboard it raises.
 *
 * It is not the field's TYPE — every one of these is a string as far as the request is
 * concerned. It is what the person typing should be given: [Code] is the strict one, and the
 * reason this enum exists at all (see `SpfnTextField`).
 */
public enum class FieldKind
{
    /** A short machine-issued code. ASCII, upper-cased, and never corrected. */
    Code,

    /** Free text. */
    Text,

    /** An email address. */
    Email,

    /** A number. */
    Number
}

/** What a line of status text is saying. */
public enum class StatusKind
{
    /** Something went wrong. Drawn in the error colour. */
    Error,

    /** Something worth knowing. Drawn in the secondary colour. */
    Info
}
