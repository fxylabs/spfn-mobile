// SPFN Mobile — the SSE line reader.
//
// The WHATWG event-stream grammar, cut to what this stream needs: lines end in LF, CRLF or
// CR; a line starting with `:` is a comment; `event` names the event and `data` lines are
// joined with LF; a blank line dispatches. `id` and `retry` are read and dropped — the
// server sends no `retry:` and never reads `Last-Event-ID` (design §1). An event the
// stream ends in the middle of is discarded, as the grammar says.
//
// Bytes are split into lines BEFORE they are decoded, so a UTF-8 sequence cut across two
// chunks is decoded whole, and a CR that ends one chunk still pairs with the LF that
// opens the next.
//
// Sources/SPFNClient/SPFNSSELineParser.swift is the same reader in Swift.

package xyz.superfunction.spfn.client

import java.io.ByteArrayOutputStream

/** One dispatched event: its name (`message` when none was given) and its joined data. */
data class SpfnSseEvent(val name: String, val data: String)

class SpfnSseLineParser
{
    private val line = ByteArrayOutputStream();
    private var skipLineFeed = false;
    private var eventName = "";
    private val data = StringBuilder();
    private var hasData = false;

    /** Reads one chunk and returns the events it completed, in order. */
    fun feed(chunk: ByteArray): List<SpfnSseEvent>
    {
        val events = mutableListOf<SpfnSseEvent>();
        for (byte in chunk)
        {
            if (skipLineFeed && byte == LF)
            {
                skipLineFeed = false;
                continue;
            }
            skipLineFeed = byte == CR;
            if (byte == CR || byte == LF)
            {
                endLine()?.let { events.add(it) };
            }
            else
            {
                line.write(byte.toInt());
            }
        }
        return events;
    }

    private fun endLine(): SpfnSseEvent?
    {
        val text = line.toString(Charsets.UTF_8.name());
        line.reset();
        if (text.isEmpty())
        {
            return dispatch();
        }
        if (!text.startsWith(":"))
        {
            readField(text);
        }
        return null;
    }

    private fun readField(text: String)
    {
        val colon = text.indexOf(':');
        val field = if (colon < 0) text else text.substring(0, colon);
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ");
        when (field)
        {
            "event" -> eventName = value;
            "data" -> appendData(value);
            else -> Unit
        }
    }

    private fun appendData(value: String)
    {
        if (hasData)
        {
            data.append('\n');
        }
        data.append(value);
        hasData = true;
    }

    private fun dispatch(): SpfnSseEvent?
    {
        val event = if (hasData) SpfnSseEvent(eventName.ifEmpty { "message" }, data.toString()) else null;
        eventName = "";
        data.setLength(0);
        hasData = false;
        return event;
    }

    private companion object
    {
        const val CR: Byte = '\r'.code.toByte();
        const val LF: Byte = '\n'.code.toByte();
    }
}
