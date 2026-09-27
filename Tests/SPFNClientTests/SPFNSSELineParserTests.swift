// SPFN Mobile — the SSE line reader against the server's own frames (design §9-1).
//
// The fixture is what `@spfn/core` 0.3.0-beta.13 writes: `connected`, an event with an id
// and the `{event, data}` envelope, a ping. Every other case is cut out of it at an
// awkward place. The Kotlin suite carries the same names.

import XCTest
@testable import SPFNClient

final class SPFNSSELineParserTests: XCTestCase
{
    private let fixture = ServerFrames.connected + ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1") + ServerFrames.ping

    private let expected = [
        SPFNSSEEvent(name: "connected", data: "{\"subscribedEvents\":[\"sessionActivity\",\"sessionUnread\"],\"timestamp\":1750000000000}"),
        SPFNSSEEvent(name: "sessionActivity", data: "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"s-1\",\"wsId\":\"w-1\"}}"),
        SPFNSSEEvent(name: "ping", data: "{\"timestamp\":1750000010000}"),
    ]

    private func parse(_ chunks: [UInt8]...) -> [SPFNSSEEvent]
    {
        var parser = SPFNSSELineParser()
        return chunks.flatMap { parser.feed($0) }
    }

    func test_parser_serverFixture_readsThreeEvents()
    {
        XCTAssertEqual(parse(Array(fixture.utf8)), expected)
    }

    func test_parser_chunkBoundaryInsideLine()
    {
        let bytes = Array(fixture.utf8)
        for cut in 1 ..< bytes.count
        {
            XCTAssertEqual(parse(Array(bytes[..<cut]), Array(bytes[cut...])), expected, "cut at \(cut)")
        }
    }

    func test_parser_crlfAndCrLineEnds()
    {
        XCTAssertEqual(parse(Array(fixture.replacingOccurrences(of: "\n", with: "\r\n").utf8)), expected)
        XCTAssertEqual(parse(Array(fixture.replacingOccurrences(of: "\n", with: "\r").utf8)), expected)
        // A CR ending one chunk pairs with the LF opening the next: one line end, not two.
        XCTAssertEqual(parse(Array("event: a\r".utf8), Array("\ndata: 1\r".utf8), Array("\n\r\n".utf8)), [SPFNSSEEvent(name: "a", data: "1")])
    }

    func test_parser_multipleDataLines_joinWithLineFeed()
    {
        XCTAssertEqual(parse(Array("data: one\ndata:two\ndata\n\n".utf8)), [SPFNSSEEvent(name: "message", data: "one\ntwo\n")])
    }

    func test_parser_commentsAndUnknownFields_ignored()
    {
        XCTAssertEqual(parse(Array(": hello\nretry: 10\nid: 4\nfoo: bar\nevent: x\ndata: 1\n\n".utf8)), [SPFNSSEEvent(name: "x", data: "1")])
    }

    func test_parser_fieldNameOnly_isEmptyValue()
    {
        XCTAssertEqual(parse(Array("event\ndata\n\n".utf8)), [SPFNSSEEvent(name: "message", data: "")])
    }

    func test_parser_emptyEvent_defaultsToMessage()
    {
        XCTAssertEqual(parse(Array("event:\ndata: x\n\n".utf8)), [SPFNSSEEvent(name: "message", data: "x")])
        // A dispatch with no data is not an event, and it resets the name.
        XCTAssertEqual(parse(Array("event: lost\n\ndata: y\n\n".utf8)), [SPFNSSEEvent(name: "message", data: "y")])
    }

    func test_parser_multibyteSplitAcrossChunks()
    {
        let bytes = Array("data: 세션 ✓\n\n".utf8)
        for cut in 1 ..< bytes.count
        {
            XCTAssertEqual(parse(Array(bytes[..<cut]), Array(bytes[cut...])), [SPFNSSEEvent(name: "message", data: "세션 ✓")])
        }
    }

    func test_parser_streamEndsWithoutBlankLine_dropsLastEvent()
    {
        XCTAssertEqual(parse(Array((ServerFrames.connected + "event: sessionActivity\ndata: {}\n").utf8)), Array(expected.prefix(1)))
    }
}
