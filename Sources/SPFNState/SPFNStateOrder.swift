// SPFN Mobile — the two comparisons the state client must make the same way on every
// platform: `at` against the ordering stamp, and a key against a connection's query.
//
// `at` is `new Date().toISOString()` — fixed width, UTC, zero-padded ASCII — so its
// lexicographic order is its time order. It is compared as a string, never parsed:
// the web client compares strings, and a parser is where two platforms disagree (§2-5, P9).
// UTF-8 bytes are named here so that no reader wonders whether Swift's `<` (canonical
// Unicode order) or Kotlin's `compareTo` (UTF-16 units) was meant; in ASCII all three agree.
//
// android/spfn-state/.../SpfnStateOrder.kt is the same pair in Kotlin.

enum SPFNStateOrder
{
    /// True when `arrival` is older than `stamp`: its bytes sort before the stamp's.
    static func isOlder(_ arrival: String, than stamp: String) -> Bool
    {
        precedes(arrival, stamp)
    }

    /// UTF-8 byte order — `at` values, and key fields when they are listed.
    static func precedes(_ left: String, _ right: String) -> Bool
    {
        Array(left.utf8).lexicographicallyPrecedes(Array(right.utf8))
    }

    /// True when the query carries every field of `key` with the same value.
    static func carries(_ query: SPFNStateKey, _ key: SPFNStateKey) -> Bool
    {
        key.allSatisfy { query[$0.key] == $0.value }
    }

    static func sortedFields(_ fields: some Sequence<String>) -> [String]
    {
        fields.sorted(by: precedes)
    }
}
