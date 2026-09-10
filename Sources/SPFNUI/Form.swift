// SPFN Mobile — the state of one screenful of input.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Form.kt.
//
// A form is a refusal per field plus one ``Busy``. It is not a `Loadable`: what a submit
// produces is a changed read somewhere else, or a pop, and never a value this type holds.
//
// ---------------------------------------------------------------------------
// Every field is checked, and the answer is a KEY
// ---------------------------------------------------------------------------
//
// ``check(values:rules:custom:)`` does not stop at the first refusal. A screen that
// refused one field at a time makes a person press submit four times to be told four
// things, and the four things were all knowable at the first press.
//
// A refusal is a ``FieldError`` — `required`, `minLength(2)` — and not a sentence. The
// words a screen shows are the app's, drawn through ``SPFNStrings`` or the screen's own
// wording, for the same reason ``LoadableView`` classifies an envelope into a key rather
// than drawing the server's `message` (decision C7). ``FieldError/custom(message:)`` is the
// one that carries text, because the whole of what a custom rule is, is a sentence the
// spec's author wrote.
//
// ---------------------------------------------------------------------------
// Written by hand on both platforms, and never as a regular expression
// ---------------------------------------------------------------------------
//
// The two halves have to answer the same for the same input, and the shortest way to make
// them disagree is to let either language's library decide what a character is
// (docs/IMPLEMENTATION-PITFALLS.md P9). So:
//
//   * a LENGTH is UTF-16 code units — `text.utf16.count` here, `text.length` there. Swift's
//     `count` is grapheme clusters and Kotlin has no cheap equivalent, so "a" is 1 and an
//     emoji is 2 on both platforms rather than 1 on one and 2 on the other.
//   * a DIGIT is one of the ten ASCII digits, listed. `Character.isNumber` and Kotlin's
//     `Char.isDigit()` both accept digits from other scripts.
//   * BLANK is the four ASCII characters named in ``isBlank(_:)`` and nothing else.
//   * a number is NOT parsed. Swift's `Int` is 64-bit and Kotlin's is 32, so `3000000000`
//     parses on one platform and not the other; what `number` means here is the SHAPE — an
//     optional sign and at least one digit — which is the same sentence in both languages.
//   * nothing is trimmed. "What is whitespace" is exactly the classification difference
//     above; a spec that wants a trimmed value trims it before handing the values in.
//
// ---------------------------------------------------------------------------
// What this type does NOT do
// ---------------------------------------------------------------------------
//
// It never decides whether to submit. ``canSubmit`` says whether a write is already in
// flight and the model is what asks; ``submitting()`` is not guarded, because a type that
// silently ignored a submit would hide the model rule (R2) rather than state it.

import SPFNCore

/// An extra rule a spec's author wrote, asked one field at a time.
///
/// `Sendable` rather than a bare closure so that the conformance is stated once by whoever
/// writes the rules; the answer is `nil` for "this is fine" and the sentence to show
/// otherwise. The sentence is the author's, which is why it is the one refusal that
/// carries text rather than a key.
public protocol FieldValidator: Sendable
{
    /// - Parameters:
    ///   - field: the field's name, as the spec spells it. The rule's own name is not
    ///     passed: a screen's validator switches on the field it is asked about.
    ///   - text: what is in the field right now.
    /// - Returns: nil when the value is acceptable, or the sentence to draw under it.
    func validate(field: String, text: String) -> String?
}

/// Why one field was refused.
public enum FieldError: Sendable, Equatable
{
    /// The field is required and empty.
    case required

    /// Shorter than the rule's minimum, which is the payload.
    case minLength(Int)

    /// Longer than the rule's maximum, which is the payload.
    case maxLength(Int)

    /// Not the shape the field's ``FieldKind`` names.
    case kind(FieldKind)

    /// Refused by the spec's own rule, which supplied the sentence.
    case custom(message: String)
}

/// What one field is checked against.
public struct FieldRules: Sendable, Equatable
{
    /// Whether an empty field is a refusal. When false, an empty field is checked against
    /// nothing else either: the rules below are about a VALUE, and there is no value.
    public let required: Bool

    /// The fewest UTF-16 code units the field accepts, or nil.
    public let minLength: Int?

    /// The most UTF-16 code units the field accepts, or nil.
    public let maxLength: Int?

    /// The shape the field expects, reusing ``SpfnTextField``'s own enum so that a spec
    /// value reaches the keyboard and the check through one name.
    public let kind: FieldKind

    /// The spec's name for the extra rule this field carries, or nil for none.
    ///
    /// Nothing here interprets it. It is what makes a field opt in to its
    /// ``FieldValidator``, and it is what a reader of a rules table sees when asking why
    /// the validator is consulted about this field and not that one.
    public let custom: String?

    public init(
        required: Bool = true,
        minLength: Int? = nil,
        maxLength: Int? = nil,
        kind: FieldKind = .text,
        custom: String? = nil
    )
    {
        self.required = required
        self.minLength = minLength
        self.maxLength = maxLength
        self.kind = kind
        self.custom = custom
    }
}

/// What one screenful of input can be: a refusal per field, and the state of the write.
public struct Form: Sendable, Equatable
{
    /// One entry per field the rules named, whether or not it was refused. A key with a
    /// nil value is a field that passed, and keeping it is what lets a view ask about a
    /// field without asking whether the check has run.
    public let fields: [String: FieldError?]

    /// The state of the write this form starts.
    public let submit: Busy

    public init(fields: [String: FieldError?] = [:], submit: Busy = .idle)
    {
        self.fields = fields
        self.submit = submit
    }

    /// Whether every field the last check looked at was accepted.
    public var isValid: Bool
    {
        fields.values.allSatisfy { $0 == nil }
    }

    /// Whether a submit may be started: no write is already in flight.
    ///
    /// Not "and the input is valid" — a person has to be able to press the control to be
    /// TOLD what is wrong, and being told is what ``check(values:rules:custom:)`` is for.
    public var canSubmit: Bool
    {
        submit != .busy
    }

    /// Every field checked against its rules, first failure per field reported.
    ///
    /// The rules are what decides which fields exist: a value with no rule is not looked
    /// at, and a rule with no value is checked against the empty string, which is what a
    /// field a screen never drew amounts to.
    ///
    /// The submit is `idle`, because a check is what a model runs BEFORE it starts a
    /// write; a refusal from a previous attempt is not carried into a fresh answer.
    public static func check(
        values: [String: String],
        rules: [String: FieldRules],
        custom: FieldValidator? = nil
    ) -> Form
    {
        var refusals: [String: FieldError?] = [:]
        for (field, rule) in rules
        {
            let refusal = refusal(
                field: field,
                text: values[field] ?? "",
                rule: rule,
                custom: custom
            )
            refusals.updateValue(refusal, forKey: field)
        }
        return Form(fields: refusals, submit: .idle)
    }

    /// The field was edited, so its refusal is stale and goes.
    ///
    /// A field the last check did not look at is left alone rather than added: the key set
    /// is the rules', and inventing one here would make ``isValid`` true for a form that
    /// has never been checked at all.
    public func edited(_ field: String) -> Form
    {
        guard fields[field] != nil else { return self }
        var cleared = fields
        cleared.updateValue(nil, forKey: field)
        return Form(fields: cleared, submit: submit)
    }

    /// The write started.
    public func submitting() -> Form
    {
        Form(fields: fields, submit: .busy)
    }

    /// The write succeeded. The refusals are kept as they were — what happens next is a
    /// pop or a re-read, and neither is this type's to decide.
    public func submitted() -> Form
    {
        Form(fields: fields, submit: .idle)
    }

    /// The write failed, carrying the envelope the server or the transport produced.
    public func submitFailed(_ envelope: SPFNErrorEnvelope) -> Form
    {
        Form(fields: fields, submit: .error(envelope))
    }

    // ---- the checks, in the order a field is checked in ---------------------

    /// The first rule this field breaks, or nil.
    private static func refusal(
        field: String,
        text: String,
        rule: FieldRules,
        custom: FieldValidator?
    ) -> FieldError?
    {
        if text.isEmpty
        {
            return rule.required ? .required : nil
        }
        if let minimum = rule.minLength, length(of: text) < minimum
        {
            return .minLength(minimum)
        }
        if let maximum = rule.maxLength, length(of: text) > maximum
        {
            return .maxLength(maximum)
        }
        if !accepts(rule.kind, text)
        {
            return .kind(rule.kind)
        }
        return customRefusal(field: field, text: text, rule: rule, custom: custom)
    }

    /// The spec's own rule, asked only when the field carries one and every rule above it
    /// has passed. A validator that is never reached is never called.
    private static func customRefusal(
        field: String,
        text: String,
        rule: FieldRules,
        custom: FieldValidator?
    ) -> FieldError?
    {
        guard rule.custom != nil, let custom = custom else { return nil }
        guard let message = custom.validate(field: field, text: text) else { return nil }
        return .custom(message: message)
    }

    /// How long the field is, in UTF-16 code units — the one unit both platforms count the
    /// same way. See this file's header.
    private static func length(of text: String) -> Int
    {
        text.utf16.count
    }

    /// Whether the text is the shape the kind names. `code` and `text` name no shape.
    private static func accepts(_ kind: FieldKind, _ text: String) -> Bool
    {
        switch kind
        {
        case .code, .text:
            return true
        case .email:
            return isEmail(text)
        case .number:
            return isInteger(text)
        }
    }

    /// The three clauses and no fourth: there is an `@`, nothing is blank, and neither
    /// side of the first `@` is empty. A second `@` is not refused — this is the shape a
    /// screen checks before it spends a request, and whether an address exists is the
    /// server's answer.
    private static func isEmail(_ text: String) -> Bool
    {
        var seenAt = false
        var local = 0
        var domain = 0
        for character in text
        {
            if isBlank(character)
            {
                return false
            }
            if character == "@" && !seenAt
            {
                seenAt = true
                continue
            }
            if seenAt
            {
                domain += 1
            }
            else
            {
                local += 1
            }
        }
        return seenAt && local > 0 && domain > 0
    }

    /// An optional leading `+` or `-` and at least one ASCII digit, and nothing else. Not
    /// parsed — see this file's header for why the two platforms' integers are not one
    /// integer.
    private static func isInteger(_ text: String) -> Bool
    {
        var digits = 0
        for (offset, character) in text.enumerated()
        {
            if offset == 0 && (character == "+" || character == "-")
            {
                continue
            }
            guard asciiDigits.contains(character) else { return false }
            digits += 1
        }
        return digits > 0
    }

    /// The ten ASCII digits, listed rather than asked of a character property: `isNumber`
    /// accepts every script's digits and Kotlin's `isDigit()` accepts a different set of
    /// them (P9).
    private static let asciiDigits: Set<Character> = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"]

    /// The four ASCII characters an address may not contain. Named rather than asked of a
    /// whitespace property, for the reason above.
    private static func isBlank(_ character: Character) -> Bool
    {
        character == " " || character == "\t" || character == "\n" || character == "\r"
    }
}
