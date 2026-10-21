// SPFN Mobile — the person's name off the relay's `user` field, case table rows U1–U10.
//
// The name is parsed from a plain string, like the rest of the relay, so these rows run on
// a host JVM. The parser is spfn-core's SpfnCanonicalJson rather than org.json, whose host
// stubs answer defaults and would make every row here pass against nothing.

package xyz.superfunction.spfn.social.apple

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

@OptIn(SpfnInternalNonceAccess::class)
class SpfnSocialAppleNameTest
{
    /**
     * U1: a first authorization — token and both halves of the name, through the relay,
     * the Custom Tab driver and the adapter. The call holds nothing once it has answered.
     */
    @Test
    fun u1_aRelayWithBothNamesAnswersTheTokenAndTheName() = runBlocking {
        val relays = SpfnSocialAppleRelays();
        val tab = ScriptedTab();
        val adapter = SpfnSocialApple(SpfnSocialAppleCustomTabDriver(tab, relays));

        val call = async { adapter.credential(appleNonce()) };
        val state = tab.opened.await();
        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null, FIRST_AUTHORIZATION)));
        val credential = call.await();

        assertEquals(TOKEN, credential.idToken);
        assertEquals(SpfnSocialAppleName(GIVEN, FAMILY), credential.name);
        assertFalse("nothing waits, so nothing keeps the name", relays.isWaiting(state));
    }

    /** U2: every later sign-in — the token, and no name. */
    @Test
    fun u2_aRelayWithoutUserAnswersTheTokenAndNoName()
    {
        val credential = credentialOf(null);

        assertEquals(TOKEN, credential.idToken);
        assertNull(credential.name);
    }

    /**
     * U3: a `user` that is not JSON — or not JSON as strictly as the canonical parser reads
     * it — is no name, and never a failed sign-in.
     */
    @Test
    fun u3_aUserThatIsNotJsonIsNoNameAndNeverAFailure()
    {
        val malformed = listOf(
            "",
            "not json",
            "{",
            "{\"name\":{\"firstName\":\"$GIVEN\"}",
            "{\"name\":{\"firstName\":\"$GIVEN\"}} trailing",
            "{\"name\":{\"firstName\":\"$GIVEN\"},\"name\":{}}",
            "{\"name\":{\"firstName\":\"$GIVEN\"},\"rank\":1.5}",
            "{name:{firstName:\"$GIVEN\"}}"
        );
        for (user in malformed)
        {
            val credential = credentialOf(user);

            assertEquals("user '$user'", TOKEN, credential.idToken);
            assertNull("user '$user'", credential.name);
        }
    }

    /** U4: only the given name — the family name is null, the name is still made. */
    @Test
    fun u4_aUserWithOnlyFirstNameAnswersAGivenNameAlone()
    {
        val credential = credentialOf("{\"name\":{\"firstName\":\"$GIVEN\"}}");

        assertEquals(TOKEN, credential.idToken);
        assertEquals(SpfnSocialAppleName(GIVEN, null), credential.name);
        assertEquals(
            "and the other half the same way",
            SpfnSocialAppleName(null, FAMILY),
            credentialOf("{\"name\":{\"lastName\":\"$FAMILY\"}}").name
        );
    }

    /** U5: an empty or blank half is absent; a name with no non-blank half is no name. */
    @Test
    fun u5_emptyOrBlankNamesAreAbsent()
    {
        for (blank in listOf("", " ", "\\t\\n", "\\u00a0"))
        {
            assertNull(
                "blank '$blank'",
                credentialOf("{\"name\":{\"firstName\":\"$blank\",\"lastName\":\"$blank\"}}").name
            );
        }
        assertNull(credentialOf("{\"name\":{}}").name);
        assertNull(
            "a half that is not text is absent too",
            credentialOf("{\"name\":{\"firstName\":1,\"lastName\":null}}").name
        );
        assertEquals(
            SpfnSocialAppleName(GIVEN, null),
            credentialOf("{\"name\":{\"firstName\":\"$GIVEN\",\"lastName\":\"  \"}}").name
        );
        assertEquals(
            SpfnSocialAppleName(null, FAMILY),
            credentialOf("{\"name\":{\"firstName\":\"\",\"lastName\":\"$FAMILY\"}}").name
        );
    }

    /**
     * U6: over the server's 2048-byte cap is no name, though it is well-formed — the cap
     * is in UTF-8 bytes, not characters — and exactly at the cap is still read.
     */
    @Test
    fun u6_aUserOverTheByteCapIsNotParsed()
    {
        val atCap = userOfBytes(MAX_RELAY_USER_BYTES, "a");
        val overCap = userOfBytes(MAX_RELAY_USER_BYTES + 1, "a");
        assertEquals(MAX_RELAY_USER_BYTES, atCap.toByteArray(Charsets.UTF_8).size);

        assertEquals(GIVEN, credentialOf(atCap).name?.givenName);
        assertNull(credentialOf(overCap).name);

        // Under the cap in characters, over it in bytes: each Hangul syllable is three.
        val wide = "{\"name\":{\"firstName\":\"${"가".repeat(700)}\"}}";
        assertTrue(wide.length <= MAX_RELAY_USER_BYTES);
        assertNull(credentialOf(wide).name);
        assertEquals(TOKEN, credentialOf(wide).idToken);
    }

    /**
     * U7: no `name` object, or one that is not an object, is no name — down to nesting as
     * deep as the byte cap allows, which the recursive parser must survive.
     */
    @Test
    fun u7_aUserWithoutANameObjectIsNoName()
    {
        val nameless = listOf(
            "{\"email\":\"jane@example.com\"}",
            "{\"name\":\"$GIVEN $FAMILY\"}",
            "{\"name\":[\"$GIVEN\",\"$FAMILY\"]}",
            "{\"name\":null}",
            "{\"name\":{\"name\":{\"firstName\":\"$GIVEN\"}}}",
            "[{\"name\":{\"firstName\":\"$GIVEN\"}}]",
            "\"$GIVEN\"",
            "42",
            "null",
            "[".repeat(1024) + "]".repeat(1024),
            "[".repeat(MAX_RELAY_USER_BYTES),
            "{\"a\":".repeat(340) + "1" + "}".repeat(340)
        );
        for (user in nameless)
        {
            val credential = credentialOf(user);

            assertEquals(user.take(40), TOKEN, credential.idToken);
            assertNull(user.take(40), credential.name);
        }
    }

    /** U8: nothing renders the token or the name — the result, the relay, any failure. */
    @Test
    fun u8_noRenderingCarriesTheTokenOrTheName()
    {
        val relay = SpfnSocialAppleRelay(TOKEN, STATE, null, FIRST_AUTHORIZATION);
        val answer = classifyRelay(relay, STATE);
        val credential = (answer as SpfnSocialAppleAnswer.Signed).credential;
        val failures = listOf<Throwable>(
            SpfnSocialAppleException.Cancelled(),
            SpfnSocialAppleException.IdentityTokenMissing(),
            SpfnSocialAppleException.Failed("invalid_request"),
            SpfnSocialAppleException.NonceProviderMismatch("google"),
            SpfnSocialApple.classify(IllegalStateException("$TOKEN $GIVEN $FAMILY")),
            refusalOf(classifyRelay(SpfnSocialAppleRelay(null, STATE, "$TOKEN $GIVEN", FIRST_AUTHORIZATION), STATE))
        );
        val rendered = listOf(relay.toString(), answer.toString(), credential.toString(), credential.name.toString()) +
            failures.flatMap { listOf(it.toString(), it.message.orEmpty()) };

        assertTrue(credential.name != null);
        for (text in rendered)
        {
            for (secret in listOf(TOKEN, GIVEN, FAMILY))
            {
                assertFalse("'$text' carries '$secret'", text.contains(secret));
            }
        }
    }

    /** U9: `idToken(nonce)` on a relay with a name is the token and only the token. */
    @Test
    fun u9_idTokenOnARelayWithANameReturnsTheTokenOnly() = runBlocking {
        val relays = SpfnSocialAppleRelays();
        val tab = ScriptedTab();
        val adapter = SpfnSocialApple(SpfnSocialAppleCustomTabDriver(tab, relays));

        val call = async { adapter.idToken(appleNonce()) };
        relays.deliver(SpfnSocialAppleRelay(TOKEN, tab.opened.await(), null, FIRST_AUTHORIZATION));

        assertEquals(TOKEN, call.await());
        assertEquals(
            "the enroll-closure form, against a scripted driver",
            TOKEN,
            SpfnSocialApple(RecordingAppleDriver(token = TOKEN, name = SpfnSocialAppleName(GIVEN, FAMILY))).idToken(appleNonce())
        );
    }

    /** U10: an error relay (R3–R5) carrying `user` is the same failure it is without one. */
    @Test
    fun u10_anErrorRelayIgnoresUser()
    {
        for (error in listOf("user_cancelled_authorize", "invalid_request", "expired", "Invalid request", ""))
        {
            val with = refusalOf(classifyRelay(SpfnSocialAppleRelay(null, STATE, error, FIRST_AUTHORIZATION), STATE));
            val without = refusalOf(classifyRelay(SpfnSocialAppleRelay(null, STATE, error), STATE));

            assertEquals("error '$error'", without.javaClass, with.javaClass);
            assertEquals("error '$error'", (without as? SpfnSocialAppleException.Failed)?.reason, (with as? SpfnSocialAppleException.Failed)?.reason);
        }
        assertTrue(
            "and no token, no error, but a name is still no token",
            refusalOf(classifyRelay(SpfnSocialAppleRelay(null, STATE, null, FIRST_AUTHORIZATION), STATE))
                is SpfnSocialAppleException.IdentityTokenMissing
        );
    }

    /** Defect 3: a forged relay with a name and the wrong state is still R6 — not this call's. */
    @Test
    fun r6_aForgedRelayWithANameIsStillNotThisCalls() = runBlocking {
        for (state in listOf(null, "", OTHER_STATE))
        {
            assertNull(classifyRelay(SpfnSocialAppleRelay(TOKEN, state, null, FIRST_AUTHORIZATION), STATE));
        }

        val relays = SpfnSocialAppleRelays();
        val answer = relays.begin(STATE)!!;
        assertFalse(relays.deliver(SpfnSocialAppleRelay("forged-token", OTHER_STATE, null, FIRST_AUTHORIZATION)));
        assertFalse(answer.isCompleted);
        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, STATE, null)));
        assertNull("the forged name never reached the call", (answer.await() as SpfnSocialAppleAnswer.Signed).credential.name);
    }

    /** The credential the call's own relay answers with, for a `user` value. */
    private fun credentialOf(user: String?): SpfnSocialAppleCredential =
        (classifyRelay(SpfnSocialAppleRelay(TOKEN, STATE, null, user), STATE) as SpfnSocialAppleAnswer.Signed).credential

    private fun refusalOf(answer: SpfnSocialAppleAnswer?): SpfnSocialAppleException =
        (answer as SpfnSocialAppleAnswer.Refused).failure

    /** A well-formed `user` of exactly [bytes] UTF-8 bytes, padded in the family name. */
    private fun userOfBytes(bytes: Int, pad: String): String
    {
        val shell = "{\"name\":{\"firstName\":\"$GIVEN\",\"lastName\":\"\"}}";
        return shell.replace("\"lastName\":\"\"", "\"lastName\":\"${pad.repeat(bytes - shell.length)}\"");
    }

    private fun appleNonce(): SpfnSocialNonce =
        SpfnSocialNonce.forProvider(FINGERPRINT, SpfnSocialApple.PROVIDER)

    private companion object
    {
        const val TOKEN = "apple-token-0001"
        const val GIVEN = "Jane"
        const val FAMILY = "Appleseed"
        const val FIRST_AUTHORIZATION = "{\"name\":{\"firstName\":\"$GIVEN\",\"lastName\":\"$FAMILY\"},\"email\":\"jane@example.com\"}"
        const val STATE = "5f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9012a3b4c5d6"
        const val OTHER_STATE = "0000000000000000000000000000000000000000000000000000000000000000"

        /** A fingerprint shaped as one taken over a real key: 64 lowercase hex characters. */
        const val FINGERPRINT = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"
    }
}
