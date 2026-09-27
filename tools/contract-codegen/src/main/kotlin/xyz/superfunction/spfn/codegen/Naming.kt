// Names for code generated from an app contract (docs/architecture/app-contract-codegen.md §3).
//
// Every rule is mechanical so a reader can predict a name from the document: a nested type
// is its parent's name plus the property's, an array element adds `Item`, and nothing is
// singularised. Keywords are escaped rather than renamed, so the wire name and the source
// name stay the same word.

package xyz.superfunction.spfn.codegen

object Naming
{
    /** `head_seq` → `HeadSeq`, `getItem` → `GetItem`: split on `_` and `-`, capitalise each part. */
    fun pascal(name: String): String =
        name.split('_', '-').filter { it.isNotEmpty() }.joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }

    /** `in_review` → `inReview`; `PROOF_INVALID` → `proofInvalid`, as the core enums read. */
    fun swiftCase(value: String): String = Names.enumCase(value.replace('-', '_').replace('.', '_').replace(' ', '_'))

    /** `in_review` → `InReview`. */
    fun kotlinCase(value: String): String = swiftCase(value).replaceFirstChar { it.uppercaseChar() }

    private val ENUM_VALUE = Regex("[A-Za-z][A-Za-z0-9_. -]*");

    /** Names the forward-compatible case and the members an enumeration already has. */
    private val RESERVED_CASES = setOf("unknown", "wireValue", "companion");

    /**
     * Refuses values that cannot become a case name in both languages, or that become the
     * same case name — including the `unknown` case every generated enumeration carries.
     */
    fun checkEnumCases(values: List<String>, where: String)
    {
        values.forEach { value ->
            if (!ENUM_VALUE.matches(value) || swiftCase(value).isEmpty())
            {
                throw JsonException(
                    "$where has constant '$value', which is not an ASCII name ([A-Za-z] then [A-Za-z0-9_. -]) (R8)"
                );
            }
            if (swiftCase(value).lowercase() in RESERVED_CASES.map { it.lowercase() })
            {
                throw JsonException("$where has constant '$value', whose case name is reserved by the generated enum (R9)");
            }
        };
        val collisions = values.groupBy { swiftCase(it) }.filterValues { it.size > 1 };
        if (collisions.isNotEmpty())
        {
            throw JsonException(
                "$where has constants that generate one case name: ${collisions.values.flatten().sorted().joinToString(", ")} (R9)"
            );
        }
    }

    private val SWIFT_KEYWORDS = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
        "inout", "internal", "let", "open", "operator", "private", "precedencegroup", "protocol", "public",
        "rethrows", "static", "struct", "subscript", "typealias", "var", "break", "case", "catch", "continue",
        "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return",
        "throw", "switch", "where", "while", "Any", "as", "await", "false", "is", "nil", "self", "Self",
        "super", "throws", "true", "try"
    );

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while"
    );

    fun swiftIdentifier(name: String): String = if (name in SWIFT_KEYWORDS) "`$name`" else name

    fun kotlinIdentifier(name: String): String = if (name in KOTLIN_KEYWORDS) "`$name`" else name
}
