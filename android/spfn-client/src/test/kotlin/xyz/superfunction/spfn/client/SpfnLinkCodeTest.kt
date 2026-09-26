// SPFN Mobile — reading a link code off a typed entry or a scanned URL.
//
// One table, and SPFNLinkCodeTests.swift holds the same rows in the same order. It is not
// a file under Contracts/fixtures: that directory holds only vectors a third
// implementation derived from the contract text, and these rules are this SDK's own, not
// the contract's. So the two copies are kept equal by hand, and a row added to one is
// added to the other.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpfnLinkCodeTest
{
    /** Input, what `parse` answers, and why. */
    private class Case(val input: String, val expected: String?, val why: String)

    @Test
    fun everyRowOfTheTable()
    {
        for (case in TABLE)
        {
            assertEquals(case.why, case.expected, SpfnLinkCode.parse(case.input, ALLOWED_HOSTS));
        }
    }

    /**
     * The allowed set is compared in lower case too, so an app that spells its host the
     * way a person writes it is not locked out by its own spelling.
     */
    @Test
    fun anAllowedHostIsComparedWithoutCase()
    {
        assertEquals("ABCD-EFGH", SpfnLinkCode.parse("https://link.example.com/ABCD-EFGH", setOf("Link.Example.COM")));
    }

    @Test
    fun noAllowedHostRefusesEveryUrlAndStillReadsATypedCode()
    {
        assertNull(SpfnLinkCode.parse("https://link.example.com/ABCD-EFGH", emptySet()));
        assertEquals("ABCD-EFGH", SpfnLinkCode.parse("abcd-efgh", emptySet()));
    }

    private companion object
    {
        val ALLOWED_HOSTS = setOf("link.example.com", "xn--bcher-kva.example")

        val TABLE = listOf(
            Case("ABCD-EFGH", "ABCD-EFGH", "the code as a signed-in device shows it"),
            Case("abcdefgh", "ABCD-EFGH", "lower case, no dash"),
            Case(" abcd efgh \n", "ABCD-EFGH", "spaces inside, whitespace around"),
            Case("ab-cd-ef-gh", "ABCD-EFGH", "dashes anywhere"),
            Case("ABCD-EFG", null, "seven characters"),
            Case("ABCD-EFGHJ", null, "nine characters"),
            Case("ABCD-EFG1", null, "a character outside the alphabet"),
            Case("ABCD-EFGß", null, "a letter that upper-cases to two alphabet letters"),
            Case("ABCD\u2013EFGH", null, "an en dash is not a dash"),
            Case("link.example.com/ABCD-EFGH", null, "a URL without a scheme is not a typed code"),
            Case("https://link.example.com/ABCD-EFGH", "ABCD-EFGH", "the code as the only segment"),
            Case("https://link.example.com/l/abcd-efgh", "ABCD-EFGH", "the code as the last of several segments"),
            Case("HTTPS://LINK.Example.COM/l/ABCDEFGH", "ABCD-EFGH", "scheme and host in mixed case"),
            Case("https://xn--bcher-kva.example/ABCD-EFGH", "ABCD-EFGH", "an IDN host in its allowed xn-- form"),
            Case("  https://link.example.com/ABCD-EFGH\n", "ABCD-EFGH", "whitespace around a URL"),
            Case("http://link.example.com/ABCD-EFGH", null, "http"),
            Case("ftp://link.example.com/ABCD-EFGH", null, "another scheme"),
            Case("https://other.example.com/ABCD-EFGH", null, "a host not allowed"),
            Case("https://link.example.com.other.example/ABCD-EFGH", null, "an allowed host as a prefix"),
            Case("https://b\u00fccher.example/ABCD-EFGH", null, "an IDN host in Unicode"),
            Case("https://user@link.example.com/ABCD-EFGH", null, "userinfo"),
            Case("https://link.example.com:443/ABCD-EFGH", null, "a port"),
            Case("https://link.example.com", null, "no path"),
            Case("https://link.example.com/ABCD-EFGH/extra", null, "a segment after the code"),
            Case("https://link.example.com/ABCD-EFGH/", null, "a trailing slash"),
            Case("https://link.example.com//ABCD-EFGH", null, "an empty segment"),
            Case("https://link.example.com/?code=ABCD-EFGH", null, "the code only in the query"),
            Case("https://link.example.com/ABCD-EFGH?ref=qr", null, "a query after the code"),
            Case("https://link.example.com/ABCD-EFGH#top", null, "a fragment"),
            Case("https://link.example.com/ABCD%2DEFGH", null, "percent-encoding"),
            Case("https://link.example.com\\ABCD-EFGH", null, "a backslash for a slash"),
            Case("https://link.example.com/ab cd-efgh", null, "a space inside a URL"),
        )
    }
}
