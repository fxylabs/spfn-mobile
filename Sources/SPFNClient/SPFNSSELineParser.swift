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
// android/spfn-client/.../SpfnSseLineParser.kt is the same reader in Kotlin.

/// One dispatched event: its name (`message` when none was given) and its joined data.
public struct SPFNSSEEvent: Equatable, Sendable
{
    public let name: String
    public let data: String

    public init(name: String, data: String)
    {
        self.name = name
        self.data = data
    }
}

public struct SPFNSSELineParser: Sendable
{
    private static let carriageReturn = UInt8(ascii: "\r")
    private static let lineFeed = UInt8(ascii: "\n")

    private var line: [UInt8] = []
    private var skipLineFeed = false
    private var eventName = ""
    private var data = ""
    private var hasData = false

    public init() {}

    /// Reads one chunk and returns the events it completed, in order.
    public mutating func feed(_ chunk: [UInt8]) -> [SPFNSSEEvent]
    {
        var events: [SPFNSSEEvent] = []
        for byte in chunk
        {
            if skipLineFeed, byte == Self.lineFeed
            {
                skipLineFeed = false
                continue
            }
            skipLineFeed = byte == Self.carriageReturn
            if byte == Self.carriageReturn || byte == Self.lineFeed
            {
                if let event = endLine()
                {
                    events.append(event)
                }
            }
            else
            {
                line.append(byte)
            }
        }
        return events
    }

    private mutating func endLine() -> SPFNSSEEvent?
    {
        let text = String(decoding: line, as: UTF8.self)
        line.removeAll(keepingCapacity: true)
        if text.isEmpty
        {
            return dispatch()
        }
        if !text.hasPrefix(":")
        {
            readField(text)
        }
        return nil
    }

    private mutating func readField(_ text: String)
    {
        let colon = text.firstIndex(of: ":")
        let field = colon.map { String(text[..<$0]) } ?? text
        var value = colon.map { String(text[text.index(after: $0)...]) } ?? ""
        if value.hasPrefix(" ")
        {
            value.removeFirst()
        }
        switch field
        {
        case "event":
            eventName = value
        case "data":
            data += hasData ? "\n" + value : value
            hasData = true
        default:
            break
        }
    }

    private mutating func dispatch() -> SPFNSSEEvent?
    {
        let event = hasData ? SPFNSSEEvent(name: eventName.isEmpty ? "message" : eventName, data: data) : nil
        eventName = ""
        data = ""
        hasData = false
        return event
    }
}
