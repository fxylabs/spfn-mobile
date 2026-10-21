// SPFN Mobile — the person's name, read off the relay's `user` field.
//
// Apple sends the name once: with the first authorization of an app for that account, as
// a JSON string in the `form_post`, and never again. On iOS that is the credential's own
// field; on Android the relay link lands in this module's receiver, so if this module
// dropped the field the app would have no other way to see it and the name would be lost
// for that account for good (operator decision, 2026-10-21). So the name is read here,
// handed back with the token, and nothing else: no log, no message, no rendering, no
// saved state. It lives as long as the caller keeps the result.
//
// The field is untrusted. The receiver is exported, so any app can fire a link at it with
// any `user`; the name is only read once the state has made the relay this call's (R6),
// and even then a value too large, not JSON, or not shaped as Apple shapes it is no name
// rather than a failure — a sign-in never fails over a display field. Only `name.firstName`
// and `name.lastName` are read; `email` is not, because the token already carries it.
//
// This is the one source tree validate.sh section 23 (C9) lets read a profile field, and it
// is one file so the exemption stays one file.

package xyz.superfunction.spfn.social.apple

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue

/**
 * The person's name as Apple gave it on the first authorization. Either half may be
 * absent; a name with neither is not made. Not a data class: its rendering would print
 * the name, which is personal data, so [toString] names only the type.
 */
class SpfnSocialAppleName(
    /** Apple's `name.firstName`, or null when it was absent or blank. */
    val givenName: String?,
    /** Apple's `name.lastName`, or null when it was absent or blank. */
    val familyName: String?
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnSocialAppleName && other.givenName == givenName && other.familyName == familyName

    override fun hashCode(): Int = 31 * givenName.hashCode() + familyName.hashCode()

    override fun toString(): String = "SpfnSocialAppleName"
}

/**
 * The name in a relay's `user` value, or null for anything that is not one (U2–U7).
 * Nothing escapes: the size is checked before a byte is parsed, and a parse that fails —
 * on syntax, a fractional number, a duplicate key — is no name.
 */
internal fun appleName(user: String?): SpfnSocialAppleName?
{
    // A UTF-16 unit encodes to one UTF-8 byte at least, so the length bounds the bytes
    // before any are made.
    if (user == null || user.length > MAX_RELAY_USER_BYTES)
    {
        return null;
    }
    val bytes = user.toByteArray(Charsets.UTF_8);
    if (bytes.size > MAX_RELAY_USER_BYTES)
    {
        return null;
    }
    val parsed = try
    {
        SpfnCanonicalJson.parse(bytes)
    }
    catch (malformed: Exception)
    {
        return null;
    };
    return nameOf(parsed);
}

/** `name.firstName` and `name.lastName`, each kept only when it is non-blank text. */
private fun nameOf(user: SpfnCanonicalValue): SpfnSocialAppleName?
{
    val name = ((user as? SpfnCanonicalValue.Obj)?.members?.get("name") as? SpfnCanonicalValue.Obj)
        ?.members
        ?: return null;
    val given = textOf(name["firstName"]);
    val family = textOf(name["lastName"]);
    if (given == null && family == null)
    {
        return null;
    }
    return SpfnSocialAppleName(given, family);
}

private fun textOf(value: SpfnCanonicalValue?): String? =
    (value as? SpfnCanonicalValue.Text)?.value?.takeIf { it.isNotBlank() }

/**
 * The server's `MAX_RELAY_USER_BYTES` (`@spfn/auth` 0.3.0-beta.44): it relays `user` only
 * when it fits, so a longer one did not come from it.
 */
internal const val MAX_RELAY_USER_BYTES = 2048
