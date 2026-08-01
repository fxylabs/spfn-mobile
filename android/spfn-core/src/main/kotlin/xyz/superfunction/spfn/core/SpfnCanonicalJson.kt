// SPFN Mobile — canonical JSON, algorithm SPFN-CANON-JSON-1.
//
// Counterpart of Sources/SPFNCore/SPFNCanonicalJSON.swift. The two must produce the
// same bytes and the same error codes for the same input; Contracts/fixtures/canonical/
// is the shared evidence that they do.
//
//   - object keys are ordered by their UTF-8 byte sequence, ascending
//   - no insignificant whitespace is emitted
//   - numbers are signed 64-bit integers; a fractional or non-finite number is an error
//   - `"` and `\` are escaped; C0 controls use \b \f \n \r \t where defined and \u00XX
//     otherwise; every other scalar is emitted literally as UTF-8
//   - a duplicate object key is an error rather than a last-one-wins overwrite

package xyz.superfunction.spfn.core

/** A JSON value restricted to what the contract can express. */
sealed interface SpfnCanonicalValue
{
    data object Null : SpfnCanonicalValue

    data class Bool(val value: Boolean) : SpfnCanonicalValue

    data class Integer(val value: Long) : SpfnCanonicalValue

    data class Text(val value: String) : SpfnCanonicalValue

    data class Arr(val elements: List<SpfnCanonicalValue>) : SpfnCanonicalValue

    data class Obj(val members: Map<String, SpfnCanonicalValue>) : SpfnCanonicalValue
}

/**
 * Canonicalization and strict-parsing failures.
 *
 * [code] is the identifier a fixture vector names, and it is the same string the Swift
 * `SPFNCanonicalError.code` returns for the same condition.
 */
class SpfnCanonicalException(val code: String, message: String) : IllegalArgumentException(message)

object SpfnCanonicalJson
{
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** Serializes a value to its canonical UTF-8 bytes. */
    fun encode(value: SpfnCanonicalValue): ByteArray = encodeToString(value).toByteArray(Charsets.UTF_8)

    /** Serializes a value to its canonical UTF-8 string. */
    fun encodeToString(value: SpfnCanonicalValue): String
    {
        val out = StringBuilder();
        write(value, out);
        return out.toString();
    }

    /**
     * Parses JSON bytes into a value, strictly: integers only, no duplicate keys, no
     * trailing content. Parsing is deliberately not the inverse of [encode] — input
     * arrives from a server with arbitrary whitespace and key order — but
     * `encode(parse(x))` is canonical, and every digest is taken over that.
     */
    fun parse(bytes: ByteArray): SpfnCanonicalValue
    {
        val reader = Reader(bytes);
        reader.skipWhitespace();
        val value = reader.readValue();
        reader.skipWhitespace();
        if (!reader.isAtEnd())
        {
            throw SpfnCanonicalException("TRAILING_CONTENT", "trailing content at offset ${reader.offset}");
        }
        return value;
    }

    /** Convenience for fixture files and response bodies held as strings. */
    fun parse(text: String): SpfnCanonicalValue = parse(text.toByteArray(Charsets.UTF_8))

    /** Orders keys the way the canonical form requires: by UTF-8 bytes, ascending. */
    fun sortedKeys(keys: Collection<String>): List<String> = keys.sortedWith(::compareUtf8)

    private fun compareUtf8(lhs: String, rhs: String): Int
    {
        val left = lhs.toByteArray(Charsets.UTF_8);
        val right = rhs.toByteArray(Charsets.UTF_8);
        val shared = minOf(left.size, right.size);
        for (index in 0 until shared)
        {
            val a = left[index].toInt() and 0xFF;
            val b = right[index].toInt() and 0xFF;
            if (a != b)
            {
                return if (a < b) -1 else 1;
            }
        }
        return left.size.compareTo(right.size);
    }

    private fun write(value: SpfnCanonicalValue, out: StringBuilder)
    {
        when (value)
        {
            is SpfnCanonicalValue.Null -> out.append("null")
            is SpfnCanonicalValue.Bool -> out.append(if (value.value) "true" else "false")
            is SpfnCanonicalValue.Integer -> out.append(value.value.toString())
            is SpfnCanonicalValue.Text -> writeString(value.value, out)
            is SpfnCanonicalValue.Arr ->
            {
                out.append('[');
                value.elements.forEachIndexed { index, element ->
                    if (index > 0)
                    {
                        out.append(',');
                    }
                    write(element, out);
                }
                out.append(']');
            }
            is SpfnCanonicalValue.Obj ->
            {
                out.append('{');
                sortedKeys(value.members.keys).forEachIndexed { index, key ->
                    if (index > 0)
                    {
                        out.append(',');
                    }
                    writeString(key, out);
                    out.append(':');
                    write(requireNotNull(value.members[key]), out);
                }
                out.append('}');
            }
        }
    }

    private fun writeString(text: String, out: StringBuilder)
    {
        out.append('"');
        for (character in text)
        {
            when (character)
            {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                {
                    if (character.code < 0x20)
                    {
                        out.append("\\u");
                        out.append(HEX_DIGITS[(character.code shr 12) and 0xF]);
                        out.append(HEX_DIGITS[(character.code shr 8) and 0xF]);
                        out.append(HEX_DIGITS[(character.code shr 4) and 0xF]);
                        out.append(HEX_DIGITS[character.code and 0xF]);
                    }
                    else
                    {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Minimal strict JSON reader. Hand-written on purpose: a stock parser on each
     * platform would disagree about duplicate keys, number width and error identity,
     * which is exactly what the parity gate is supposed to pin down.
     */
    private class Reader(private val bytes: ByteArray)
    {
        var offset: Int = 0
            private set

        fun isAtEnd(): Boolean = offset >= bytes.size

        private fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF

        fun skipWhitespace()
        {
            while (offset < bytes.size)
            {
                val byte = byteAt(offset);
                if (byte != 0x20 && byte != 0x09 && byte != 0x0A && byte != 0x0D)
                {
                    return;
                }
                offset += 1;
            }
        }

        fun readValue(): SpfnCanonicalValue
        {
            if (offset >= bytes.size)
            {
                throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
            }
            return when (byteAt(offset).toChar())
            {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> SpfnCanonicalValue.Text(readString())
                't' ->
                {
                    expect("true");
                    SpfnCanonicalValue.Bool(true);
                }
                'f' ->
                {
                    expect("false");
                    SpfnCanonicalValue.Bool(false);
                }
                'n' ->
                {
                    expect("null");
                    SpfnCanonicalValue.Null;
                }
                else -> readNumber()
            }
        }

        private fun expect(literal: String)
        {
            val expected = literal.toByteArray(Charsets.UTF_8);
            if (offset + expected.size > bytes.size)
            {
                throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
            }
            for (index in expected.indices)
            {
                if (bytes[offset + index] != expected[index])
                {
                    throw SpfnCanonicalException("INVALID_TOKEN", "invalid token at offset $offset");
                }
            }
            offset += expected.size;
        }

        private fun expectByte(expected: Char)
        {
            if (offset >= bytes.size)
            {
                throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
            }
            if (byteAt(offset) != expected.code)
            {
                throw SpfnCanonicalException("INVALID_TOKEN", "invalid token at offset $offset");
            }
            offset += 1;
        }

        private fun readObject(): SpfnCanonicalValue
        {
            offset += 1;
            val members = LinkedHashMap<String, SpfnCanonicalValue>();
            skipWhitespace();

            if (offset < bytes.size && byteAt(offset) == '}'.code)
            {
                offset += 1;
                return SpfnCanonicalValue.Obj(members);
            }

            while (true)
            {
                skipWhitespace();
                val key = readString();
                if (members.containsKey(key))
                {
                    throw SpfnCanonicalException("DUPLICATE_KEY", "duplicate key '$key'");
                }
                skipWhitespace();
                expectByte(':');
                skipWhitespace();
                members[key] = readValue();
                skipWhitespace();

                if (offset >= bytes.size)
                {
                    throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
                }
                if (byteAt(offset) == ','.code)
                {
                    offset += 1;
                    continue;
                }
                expectByte('}');
                return SpfnCanonicalValue.Obj(members);
            }
        }

        private fun readArray(): SpfnCanonicalValue
        {
            offset += 1;
            val elements = ArrayList<SpfnCanonicalValue>();
            skipWhitespace();

            if (offset < bytes.size && byteAt(offset) == ']'.code)
            {
                offset += 1;
                return SpfnCanonicalValue.Arr(elements);
            }

            while (true)
            {
                skipWhitespace();
                elements.add(readValue());
                skipWhitespace();

                if (offset >= bytes.size)
                {
                    throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
                }
                if (byteAt(offset) == ','.code)
                {
                    offset += 1;
                    continue;
                }
                expectByte(']');
                return SpfnCanonicalValue.Arr(elements);
            }
        }

        private fun readString(): String
        {
            expectByte('"');
            val out = java.io.ByteArrayOutputStream();

            while (true)
            {
                if (offset >= bytes.size)
                {
                    throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
                }
                val byte = byteAt(offset);

                if (byte == '"'.code)
                {
                    offset += 1;
                    return decodeUtf8(out.toByteArray());
                }
                if (byte == '\\'.code)
                {
                    offset += 1;
                    readEscape(out);
                    continue;
                }
                if (byte < 0x20)
                {
                    throw SpfnCanonicalException("INVALID_TOKEN", "control character at offset $offset");
                }
                out.write(byte);
                offset += 1;
            }
        }

        private fun decodeUtf8(raw: ByteArray): String
        {
            val decoded = String(raw, Charsets.UTF_8);
            if (!decoded.toByteArray(Charsets.UTF_8).contentEquals(raw))
            {
                throw SpfnCanonicalException("INVALID_UTF8", "string is not valid UTF-8");
            }
            return decoded;
        }

        private fun readEscape(out: java.io.ByteArrayOutputStream)
        {
            if (offset >= bytes.size)
            {
                throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
            }
            val escape = byteAt(offset);
            offset += 1;

            when (escape.toChar())
            {
                '"' -> out.write('"'.code)
                '\\' -> out.write('\\'.code)
                '/' -> out.write('/'.code)
                'b' -> out.write(0x08)
                'f' -> out.write(0x0C)
                'n' -> out.write(0x0A)
                'r' -> out.write(0x0D)
                't' -> out.write(0x09)
                'u' -> out.write(String(Character.toChars(readEscapedScalar())).toByteArray(Charsets.UTF_8))
                else -> throw SpfnCanonicalException("INVALID_ESCAPE", "invalid escape at offset ${offset - 1}")
            }
        }

        /**
         * Reads one `\uXXXX` payload, joining a surrogate pair into the scalar it
         * encodes. A lone surrogate is refused rather than replaced: silently
         * substituting U+FFFD is how two platforms end up with different bytes for
         * the same input.
         */
        private fun readEscapedScalar(): Int
        {
            val high = readHex4();

            if (high in 0xD800..0xDBFF)
            {
                if (offset + 1 >= bytes.size || byteAt(offset) != '\\'.code || byteAt(offset + 1) != 'u'.code)
                {
                    throw SpfnCanonicalException("INVALID_ESCAPE", "unpaired high surrogate at offset $offset");
                }
                offset += 2;
                val low = readHex4();
                if (low !in 0xDC00..0xDFFF)
                {
                    throw SpfnCanonicalException("INVALID_ESCAPE", "invalid low surrogate at offset $offset");
                }
                return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00);
            }

            if (high in 0xDC00..0xDFFF)
            {
                throw SpfnCanonicalException("INVALID_ESCAPE", "lone low surrogate at offset $offset");
            }
            return high;
        }

        private fun readHex4(): Int
        {
            if (offset + 4 > bytes.size)
            {
                throw SpfnCanonicalException("UNEXPECTED_END", "unexpected end of input");
            }
            var value = 0;
            repeat(4)
            {
                val byte = byteAt(offset);
                val digit = when (byte.toChar())
                {
                    in '0'..'9' -> byte - '0'.code
                    in 'a'..'f' -> byte - 'a'.code + 10
                    in 'A'..'F' -> byte - 'A'.code + 10
                    else -> throw SpfnCanonicalException("INVALID_ESCAPE", "invalid escape at offset $offset")
                }
                value = value * 16 + digit;
                offset += 1;
            }
            return value;
        }

        private fun readNumber(): SpfnCanonicalValue
        {
            val start = offset;
            if (offset < bytes.size && byteAt(offset) == '-'.code)
            {
                offset += 1;
            }

            var sawDigit = false;
            var isInteger = true;

            while (offset < bytes.size)
            {
                val character = byteAt(offset).toChar();
                if (character in '0'..'9')
                {
                    sawDigit = true;
                    offset += 1;
                    continue;
                }
                if (character == '.' || character == 'e' || character == 'E' || character == '+' || character == '-')
                {
                    isInteger = false;
                    offset += 1;
                    continue;
                }
                break;
            }

            val text = String(bytes, start, offset - start, Charsets.UTF_8);

            if (!sawDigit)
            {
                throw SpfnCanonicalException("INVALID_TOKEN", "invalid token at offset $start");
            }
            if (!isInteger)
            {
                throw SpfnCanonicalException("NON_INTEGER_NUMBER", "non-integer number '$text'");
            }
            val number = text.toLongOrNull()
                ?: throw SpfnCanonicalException("INVALID_NUMBER", "invalid number '$text'");
            return SpfnCanonicalValue.Integer(number);
        }
    }
}
