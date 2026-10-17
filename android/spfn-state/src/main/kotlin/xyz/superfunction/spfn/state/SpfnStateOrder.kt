// SPFN Mobile — the two comparisons the state client must make the same way on every
// platform: `at` against the ordering stamp, and a key against a connection's query.
//
// `at` is `new Date().toISOString()` — fixed width, UTC, zero-padded ASCII — so its
// lexicographic order is its time order. It is compared as a string, never parsed:
// the web client compares strings, and a parser is where two platforms disagree (§2-5, P9).
// UTF-8 bytes are named here so that no reader wonders whether Kotlin's `compareTo` (UTF-16
// units) or Swift's `<` (canonical Unicode order) was meant; in ASCII all three agree.
//
// Sources/SPFNState/SPFNStateOrder.swift is the same pair in Swift.

package xyz.superfunction.spfn.state

internal object SpfnStateOrder
{
    /** True when `arrival` is older than `stamp`: its bytes sort before the stamp's. */
    fun isOlder(arrival: String, stamp: String): Boolean = precedes(arrival, stamp);

    /** UTF-8 byte order — `at` values, and key fields when they are listed. */
    fun precedes(left: String, right: String): Boolean = compareUtf8(left, right) < 0;

    /** True when the query carries every field of `key` with the same value. */
    fun carries(query: SpfnStateKey, key: SpfnStateKey): Boolean = key.all { query[it.key] == it.value };

    fun sortedFields(fields: Iterable<String>): List<String> = fields.sortedWith(::compareUtf8);

    private fun compareUtf8(left: String, right: String): Int
    {
        val lhs = left.encodeToByteArray();
        val rhs = right.encodeToByteArray();
        for (index in 0 until minOf(lhs.size, rhs.size))
        {
            val difference = (lhs[index].toInt() and 0xFF) - (rhs[index].toInt() and 0xFF);
            if (difference != 0)
            {
                return difference;
            }
        }
        return lhs.size - rhs.size;
    }
}
