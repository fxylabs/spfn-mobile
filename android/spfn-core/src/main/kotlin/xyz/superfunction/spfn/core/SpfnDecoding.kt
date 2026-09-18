// SPFN Mobile — the field readers every generated decoder is built out of.
//
// Counterpart of Sources/SPFNCore/SPFNDecoding.swift. Hand-written and stable while the
// per-operation decoders that call them are generated into
// xyz.superfunction.spfn.generated from the pinned bundle. They are here so generated
// code stays a thin, obviously-correct listing of the contract rather than a place where
// logic hides, and so hand-written and generated code report one decoding failure type
// between them.

package xyz.superfunction.spfn.core

/** Decoding failures shared by generated response types. */
class SpfnDecodingException(val code: String, message: String) : IllegalArgumentException(message)

/**
 * Field readers used by generated decoders. Kept here so generated code stays a thin,
 * obviously-correct listing of the contract rather than a place where logic hides.
 */
object SpfnDecoding
{
    fun obj(value: SpfnCanonicalValue?, path: String): Map<String, SpfnCanonicalValue>
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Obj)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected object at $path");
        }
        return value.members;
    }

    fun string(value: SpfnCanonicalValue?, path: String): String
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Text)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected string at $path");
        }
        return value.value;
    }

    fun optionalString(value: SpfnCanonicalValue?, path: String): String?
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            return null;
        }
        if (value !is SpfnCanonicalValue.Text)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected string at $path");
        }
        return value.value;
    }

    fun integer(value: SpfnCanonicalValue?, path: String): Long
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Integer)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected integer at $path");
        }
        return value.value;
    }

    fun optionalInteger(value: SpfnCanonicalValue?, path: String): Long?
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            return null;
        }
        if (value !is SpfnCanonicalValue.Integer)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected integer at $path");
        }
        return value.value;
    }

    fun boolean(value: SpfnCanonicalValue?, path: String): Boolean
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Bool)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected boolean at $path");
        }
        return value.value;
    }

    /**
     * An optional boolean: absent and null both read as nothing.
     *
     * Its own reader rather than [boolean] with a fallback, for the reason every other
     * `optional*` above exists: a default would turn "the server said nothing" into
     * "the server said false", and the first field to need this — an approved device
     * poll's `passwordChangeRequired`, absent on the pending branch — is one where the
     * two readings are a login rule apart.
     */
    fun optionalBoolean(value: SpfnCanonicalValue?, path: String): Boolean?
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            return null;
        }
        if (value !is SpfnCanonicalValue.Bool)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected boolean at $path");
        }
        return value.value;
    }

    fun array(value: SpfnCanonicalValue?, path: String): List<SpfnCanonicalValue>
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Arr)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected array at $path");
        }
        return value.elements;
    }
}
