// Minimal JSON reader for the generator.
//
// Hand-written so the generator has zero external dependencies and so object member
// order is preserved exactly as written in the bundle. Preserved order is what makes
// generation deterministic: sorting or hashing members would make the output depend on
// something other than the file.

package xyz.superfunction.spfn.codegen

sealed interface JsonValue
{
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Number(val value: Long) : JsonValue

    data class Text(val value: String) : JsonValue

    data class Arr(val elements: List<JsonValue>) : JsonValue

    data class Obj(val members: LinkedHashMap<String, JsonValue>) : JsonValue
}

class JsonException(message: String) : IllegalArgumentException(message)

fun JsonValue.obj(): Map<String, JsonValue> =
    (this as? JsonValue.Obj)?.members ?: throw JsonException("expected an object, got $this")

fun JsonValue.arr(): List<JsonValue> =
    (this as? JsonValue.Arr)?.elements ?: throw JsonException("expected an array, got $this")

fun JsonValue.text(): String =
    (this as? JsonValue.Text)?.value ?: throw JsonException("expected a string, got $this")

fun JsonValue.number(): Long =
    (this as? JsonValue.Number)?.value ?: throw JsonException("expected an integer, got $this")

fun JsonValue.bool(): Boolean =
    (this as? JsonValue.Bool)?.value ?: throw JsonException("expected a boolean, got $this")

fun Map<String, JsonValue>.required(key: String): JsonValue =
    this[key] ?: throw JsonException("missing required key '$key'")

object Json
{
    fun parse(text: String): JsonValue
    {
        val reader = Reader(text);
        reader.skipWhitespace();
        val value = reader.readValue();
        reader.skipWhitespace();
        if (!reader.isAtEnd())
        {
            throw JsonException("trailing content at offset ${reader.offset}");
        }
        return value;
    }

    private class Reader(private val text: String)
    {
        var offset: Int = 0
            private set

        fun isAtEnd(): Boolean = offset >= text.length

        fun skipWhitespace()
        {
            while (offset < text.length && text[offset].isWhitespace())
            {
                offset += 1;
            }
        }

        fun readValue(): JsonValue
        {
            if (offset >= text.length)
            {
                throw JsonException("unexpected end of input");
            }
            return when (text[offset])
            {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Text(readString())
                't' ->
                {
                    expect("true");
                    JsonValue.Bool(true);
                }
                'f' ->
                {
                    expect("false");
                    JsonValue.Bool(false);
                }
                'n' ->
                {
                    expect("null");
                    JsonValue.Null;
                }
                else -> readNumber()
            }
        }

        private fun expect(literal: String)
        {
            if (!text.startsWith(literal, offset))
            {
                throw JsonException("invalid token at offset $offset");
            }
            offset += literal.length;
        }

        private fun expectChar(expected: Char)
        {
            if (offset >= text.length || text[offset] != expected)
            {
                throw JsonException("expected '$expected' at offset $offset");
            }
            offset += 1;
        }

        private fun readObject(): JsonValue
        {
            expectChar('{');
            val members = LinkedHashMap<String, JsonValue>();
            skipWhitespace();

            if (offset < text.length && text[offset] == '}')
            {
                offset += 1;
                return JsonValue.Obj(members);
            }

            while (true)
            {
                skipWhitespace();
                val key = readString();
                if (members.containsKey(key))
                {
                    throw JsonException("duplicate key '$key'");
                }
                skipWhitespace();
                expectChar(':');
                skipWhitespace();
                members[key] = readValue();
                skipWhitespace();

                if (offset < text.length && text[offset] == ',')
                {
                    offset += 1;
                    continue;
                }
                expectChar('}');
                return JsonValue.Obj(members);
            }
        }

        private fun readArray(): JsonValue
        {
            expectChar('[');
            val elements = ArrayList<JsonValue>();
            skipWhitespace();

            if (offset < text.length && text[offset] == ']')
            {
                offset += 1;
                return JsonValue.Arr(elements);
            }

            while (true)
            {
                skipWhitespace();
                elements.add(readValue());
                skipWhitespace();

                if (offset < text.length && text[offset] == ',')
                {
                    offset += 1;
                    continue;
                }
                expectChar(']');
                return JsonValue.Arr(elements);
            }
        }

        private fun readString(): String
        {
            expectChar('"');
            val out = StringBuilder();

            while (true)
            {
                if (offset >= text.length)
                {
                    throw JsonException("unterminated string");
                }
                when (val character = text[offset])
                {
                    '"' ->
                    {
                        offset += 1;
                        return out.toString();
                    }
                    '\\' ->
                    {
                        offset += 1;
                        out.append(readEscape());
                    }
                    else ->
                    {
                        out.append(character);
                        offset += 1;
                    }
                }
            }
        }

        private fun readEscape(): String
        {
            if (offset >= text.length)
            {
                throw JsonException("unterminated escape");
            }
            val escape = text[offset];
            offset += 1;
            return when (escape)
            {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' ->
                {
                    if (offset + 4 > text.length)
                    {
                        throw JsonException("truncated \\u escape");
                    }
                    val code = text.substring(offset, offset + 4).toInt(16);
                    offset += 4;
                    String(Character.toChars(code));
                }
                else -> throw JsonException("invalid escape '\\$escape'")
            }
        }

        private fun readNumber(): JsonValue
        {
            val start = offset;
            if (offset < text.length && text[offset] == '-')
            {
                offset += 1;
            }
            while (offset < text.length && text[offset].isDigit())
            {
                offset += 1;
            }
            val slice = text.substring(start, offset);
            val value = slice.toLongOrNull()
                ?: throw JsonException("only integers are supported, got '$slice' at offset $start");
            return JsonValue.Number(value);
        }
    }
}
