// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

package xyz.superfunction.spfn.core.appcontract

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecodingException

/** Helpers the calls and types generated from this app's contract document share. */
internal object FixtureAPISupport
{
    /**
     * One path parameter as one path segment: RFC 3986 unreserved bytes as they are, every
     * other UTF-8 byte as %XX. A value that is exactly `.` or `..` is encoded whole, so path
     * normalisation cannot turn it into a step up the path.
     */
    fun pathSegment(value: String): String
    {
        if (value == "." || value == "..")
        {
            return "%2E".repeat(value.length);
        }
        return percentEncoded(value);
    }

    /** `?name=value&…` in the order given, with null values left out; empty when all are null. */
    fun queryString(pairs: List<Pair<String, String?>>): String
    {
        val fields = pairs.mapNotNull { (name, value) -> value?.let { name + "=" + percentEncoded(it) } };
        return if (fields.isEmpty()) "" else "?" + fields.joinToString("&");
    }

    fun percentEncoded(value: String): String
    {
        val out = StringBuilder();
        value.toByteArray(Charsets.UTF_8).forEach { signed ->
            val byte = signed.toInt() and 0xFF;
            if (isUnreserved(byte))
            {
                out.append(byte.toChar());
            }
            else
            {
                out.append('%').append(HEX[byte shr 4]).append(HEX[byte and 0x0F]);
            }
        };
        return out.toString();
    }

    private fun isUnreserved(byte: Int): Boolean =
        byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code ||
            byte == '-'.code || byte == '.'.code || byte == '_'.code || byte == '~'.code

    private const val HEX = "0123456789ABCDEF";

    /**
     * A required key's value, which may be null; its absence is a decoding failure, because
     * the server promised the key (a required, nullable property).
     */
    fun present(members: Map<String, SpfnCanonicalValue>, key: String, path: String): SpfnCanonicalValue =
        members[key] ?: throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");

    /** Absent and null alike as null, for an optional or nullable property. */
    fun nonNull(value: SpfnCanonicalValue?): SpfnCanonicalValue? = value?.takeIf { it !is SpfnCanonicalValue.Null }
}
