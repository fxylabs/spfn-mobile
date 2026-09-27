// SPFN Mobile — the example app's events screen (docs/architecture/event-stream-design.md §9-4).
//
// Counterpart of examples/android-compose/.../EventsDemo.kt.
//
// One SPFNEventStream over an in-app demo server, so the screen runs with no network at
// all. The demo server answers the handshake and the token call behind the real `execute`,
// then streams `connected` and a `sessionActivity` frame every 1.5 s, alternating two
// workspaces; every fifth frame's payload is unreadable, so the dropped counter moves too.
// The screen shows the design's readout: `stream=open(n)`, the frames, dropped, filtered and
// reread counts, the last reread's cause, and a toggle that puts a condition on the listener.
//
// No `.spfnEventStream(_:keyLifecycle:)` here: the attachment observes a key lifecycle, and
// this app enrols no key. The screen puts the stream in the environment itself and says it
// is in the foreground while it is on screen, and a button stands in for sign-in — the
// "client module only" wiring of §3-6.

import Foundation
import SPFNClient
import SPFNCore
import SPFNEvents
import SPFNGenerated
import SPFNUI
import SwiftUI

/// Which session moved, in which workspace: the demo's one payload.
struct SessionActivity: SPFNEventPayload
{
    static let eventName = "sessionActivity"

    let sessionID: String
    let wsID: String

    init(canonical: SPFNCanonicalValue) throws
    {
        let members = try SPFNDecoding.object(canonical, at: "$")
        sessionID = try SPFNDecoding.string(members["sessionId"], at: "$.sessionId")
        wsID = try SPFNDecoding.string(members["wsId"], at: "$.wsId")
    }
}

@MainActor
struct EventsDemo: View
{
    @State private var demo = EventsDemoModel()

    var body: some View
    {
        EventsReadout(model: demo)
            .environment(\.spfnEventStream, demo.stream)
            .onAppear { demo.stream.setForeground(true) }
            .onDisappear { demo.stream.setForeground(false) }
            .task
            {
                for await state in demo.stream.states
                {
                    demo.state = state
                }
            }
    }
}

@MainActor
@Observable
final class EventsDemoModel
{
    let server = DemoEventServer()
    let stream: SPFNEventStream
    var state: SPFNEventStreamState = .idle(.signedOut)
    var frames = 0
    var rereads = 0
    var lastCause = "none"
    var onlyWorkspaceA = false
    var signedIn = false

    init()
    {
        // A literal https base URL and a literal event list: neither can be refused.
        let session = try! SPFNSession(
            transport: server,
            keyProvider: DemoKeyProvider(),
            baseURL: "https://events.example.invalid",
            clock: DemoProofClock()
        )
        stream = SPFNEventStream(
            client: SPFNClient(transport: server, session: session),
            session: session,
            configuration: try! SPFNEventStreamConfiguration(events: [SessionActivity.eventName]),
            transport: server
        )
    }

    func receive(_ signal: SPFNEventSignal<SessionActivity>)
    {
        switch signal
        {
        case .frame:
            frames += 1
        case .reread(let cause):
            rereads += 1
            lastCause = "\(cause)"
        case .unavailable:
            lastCause = "unavailable"
        }
    }
}

@MainActor
private struct EventsReadout: View
{
    let model: EventsDemoModel

    var body: some View
    {
        Screen(title: "events")
        {
            VStack(alignment: .leading, spacing: SPFNTokens.space4)
            {
                SpfnText("stream=" + Self.describe(model.state), role: .mono)
                SpfnText("frames=\(model.frames)", role: .mono)
                SpfnText("dropped=\(model.stream.diagnostics.droppedFrames)", role: .mono)
                SpfnText("filtered=\(model.stream.diagnostics.filteredFrames)", role: .mono)
                SpfnText("rereads=\(model.rereads) last=\(model.lastCause)", role: .mono)
                SpfnText("condition=" + (model.onlyWorkspaceA ? "wsId == w-a" : "none"), role: .mono)
                PrimaryButton(
                    title: model.signedIn ? "sign out" : "sign in",
                    identifier: "events.signIn",
                    onTap:
                    {
                        model.signedIn.toggle()
                        model.stream.setSignedIn(model.signedIn ? "demo-client" : nil)
                    }
                )
                PrimaryButton(title: "toggle condition", identifier: "events.condition", onTap: { model.onlyWorkspaceA.toggle() })
                SecondaryButton(title: "drop connection", identifier: "events.drop", onTap: { model.server.dropConnection() })
            }
            .padding(SPFNTokens.space4)
        }
        .onSPFNEvent(SessionActivity.self, id: model.onlyWorkspaceA, where: { [only = model.onlyWorkspaceA] in !only || $0.wsID == "w-a" })
        {
            signal in
            model.receive(signal)
        }
    }

    /// The design's readout spelling: `open(3)`, `retrying(2, 1000 ms, network)`.
    private static func describe(_ state: SPFNEventStreamState) -> String
    {
        switch state
        {
        case .idle(let reason):
            return "idle(\(reason))"
        case .connecting(let attempt):
            return "connecting(\(attempt))"
        case .open(let epoch, _):
            return "open(\(epoch))"
        case .retrying(let attempt, let delay, let reason):
            return "retrying(\(attempt), \(delay) ms, \(reason))"
        case .offline(let attempt):
            return "offline(\(attempt))"
        case .closed(let reason):
            return "closed(\(reason))"
        }
    }
}

/// The demo server: the handshake and the token behind `execute`, and a stream of frames.
/// Nothing leaves the process; the URL is never dialled.
final class DemoEventServer: SPFNTransport, SPFNStreamTransport, @unchecked Sendable
{
    private let lock = NSLock()
    private var minted = 0
    private var drop: (@Sendable () -> Void)?

    func dropConnection()
    {
        lock.withLock { drop }?()
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        if request.url.hasSuffix("/events/token")
        {
            let token = lock.withLock
            {
                minted += 1
                return minted
            }
            return Self.answer("{\"token\":\"demo-token-\(token)\"}")
        }
        let expiry = Int64(Date().timeIntervalSince1970 * 1_000) + 300_000
        return Self.answer("{\"expiresAtMillis\":\(expiry),\"sessionId\":\"demo-session\"}")
    }

    /// Ends cleanly when "drop connection" is pressed, the way a server redeploy looks.
    func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse
    {
        let (chunks, sink) = AsyncThrowingStream.makeStream(of: [UInt8].self, throwing: (any Error).self)
        sink.yield(Self.frame("connected", "{\"subscribedEvents\":[\"sessionActivity\"],\"timestamp\":1}"))
        let ticker = Task
        {
            var sequence = 0
            while !Task.isCancelled
            {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                sequence += 1
                sink.yield(Self.frame("sessionActivity", Self.activity(sequence)))
            }
        }
        lock.withLock
        {
            drop =
            {
                ticker.cancel()
                sink.finish()
            }
        }
        return SPFNStreamResponse(statusCode: 200, headers: [("content-type", "text/event-stream")], chunks: chunks)
        {
            ticker.cancel()
            sink.finish()
        }
    }

    private static func activity(_ sequence: Int) -> String
    {
        guard sequence % 5 != 0
        else
        {
            return "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\(sequence)}}"
        }
        let workspace = sequence % 2 == 0 ? "w-a" : "w-b"
        return "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"s-\(sequence)\",\"wsId\":\"\(workspace)\"}}"
    }

    private static func frame(_ name: String, _ data: String) -> [UInt8]
    {
        Array("event: \(name)\ndata: \(data)\n\n".utf8)
    }

    private static func answer(_ body: String) -> SPFNTransportResponse
    {
        SPFNTransportResponse(
            statusCode: 200,
            headers: [
                ("content-type", "application/json"),
                (SPFNWireHeaders.serverContractVersion, SPFNGeneratedContract.binding.importedVersion),
                (SPFNWireHeaders.supportedContractRange, SPFNGeneratedContract.binding.supportedRange),
            ],
            body: Array(body.utf8)
        )
    }
}

/// Signs with zeros: the demo server reads no proof. Not a key, and nothing verifies it.
struct DemoKeyProvider: SPFNKeyProvider
{
    let clientID = "demo-client"
    let keyID = "demo-key"

    func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        [UInt8](repeating: 0, count: 64)
    }
}

/// The device's own clock: there is no server time to anchor to.
struct DemoProofClock: SPFNProofClock
{
    func nowMillis(transport: any SPFNTransport, baseURL: String, timeoutMillis: Int64) async throws -> Int64
    {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    func discardAnchor(baseURL: String) async {}
}
