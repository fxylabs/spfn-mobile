// SPFN Mobile — the SSE line reader against the server's own frames (design §9-1).
//
// The fixture is what `@spfn/core` 0.3.0-beta.13 writes: `connected`, an event with an id
// and the `{event, data}` envelope, a ping. Every other case is cut out of it at an
// awkward place. SPFNSSELineParserTests.swift carries the same names.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertEquals
import org.junit.Test

class SpfnSseLineParserTest
{
    private val fixture = ServerFrames.CONNECTED + ServerFrames.activity(1, "s-1", "w-1") + ServerFrames.PING;

    private val expected = listOf(
        SpfnSseEvent("connected", "{\"subscribedEvents\":[\"sessionActivity\",\"sessionUnread\"],\"timestamp\":1750000000000}"),
        SpfnSseEvent("sessionActivity", "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"s-1\",\"wsId\":\"w-1\"}}"),
        SpfnSseEvent("ping", "{\"timestamp\":1750000010000}")
    );

    private fun parse(vararg chunks: ByteArray): List<SpfnSseEvent>
    {
        val parser = SpfnSseLineParser();
        return chunks.flatMap { parser.feed(it) };
    }

    @Test
    fun parser_serverFixture_readsThreeEvents()
    {
        assertEquals(expected, parse(fixture.toByteArray()));
    }

    @Test
    fun parser_chunkBoundaryInsideLine()
    {
        val bytes = fixture.toByteArray();
        for (cut in 1 until bytes.size)
        {
            assertEquals("cut at $cut", expected, parse(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size)));
        }
    }

    @Test
    fun parser_crlfAndCrLineEnds()
    {
        assertEquals(expected, parse(fixture.replace("\n", "\r\n").toByteArray()));
        assertEquals(expected, parse(fixture.replace("\n", "\r").toByteArray()));
        // A CR ending one chunk pairs with the LF opening the next: one line end, not two.
        assertEquals(listOf(SpfnSseEvent("a", "1")), parse("event: a\r".toByteArray(), "\ndata: 1\r".toByteArray(), "\n\r\n".toByteArray()));
    }

    @Test
    fun parser_multipleDataLines_joinWithLineFeed()
    {
        assertEquals(listOf(SpfnSseEvent("message", "one\ntwo\n")), parse("data: one\ndata:two\ndata\n\n".toByteArray()));
    }

    @Test
    fun parser_commentsAndUnknownFields_ignored()
    {
        assertEquals(listOf(SpfnSseEvent("x", "1")), parse(": hello\nretry: 10\nid: 4\nfoo: bar\nevent: x\ndata: 1\n\n".toByteArray()));
    }

    @Test
    fun parser_fieldNameOnly_isEmptyValue()
    {
        assertEquals(listOf(SpfnSseEvent("message", "")), parse("event\ndata\n\n".toByteArray()));
    }

    @Test
    fun parser_emptyEvent_defaultsToMessage()
    {
        assertEquals(listOf(SpfnSseEvent("message", "x")), parse("event:\ndata: x\n\n".toByteArray()));
        // A dispatch with no data is not an event, and it resets the name.
        assertEquals(listOf(SpfnSseEvent("message", "y")), parse("event: lost\n\ndata: y\n\n".toByteArray()));
    }

    @Test
    fun parser_multibyteSplitAcrossChunks()
    {
        val bytes = "data: 세션 ✓\n\n".toByteArray(Charsets.UTF_8);
        for (cut in 1 until bytes.size)
        {
            assertEquals(listOf(SpfnSseEvent("message", "세션 ✓")), parse(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size)));
        }
    }

    @Test
    fun parser_streamEndsWithoutBlankLine_dropsLastEvent()
    {
        assertEquals(expected.take(1), parse((ServerFrames.CONNECTED + "event: sessionActivity\ndata: {}\n").toByteArray()));
    }
}
