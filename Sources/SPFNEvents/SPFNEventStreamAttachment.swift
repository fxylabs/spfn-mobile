// SPFN Mobile — the root attachment and screen-level listening, in SwiftUI.
//
// An app attaches its stream at the root once: `.spfnEventStream(events, keyLifecycle:)`.
// The attachment puts the stream in the environment and carries three facts into it — the
// scene's phase (counted over scenes, SPFNForegroundTally), who is signed in (the key
// lifecycle's read-only value) and whether a network path is satisfied. The stream decides
// everything else. When the attached view goes away its scene leaves the tally, and the
// stream is told the foreground that remains; the stream itself is the app's.
//
// `onSPFNEvent` is `task(id:)` around `SPFNEventStream.listen`. A form with a condition
// takes an `id` as well, and there is no form with a condition and without one: the
// condition is fixed when the listener attaches, and a task whose id did not change does
// not restart, so a condition capturing a workspace id would keep filtering for the first
// workspace after the screen moved on — silently (§8 H-11). The id is what re-attaches it.
//
// Guarded whole on SwiftUI, so the module builds on Linux as its toolkit-free types.
//
// android/spfn-events/.../SpfnEventStreamHost.kt and SpfnEventEffect.kt are the Compose half.

#if canImport(SwiftUI)
import SwiftUI
import SPFNClient
import SPFNCore

private struct SPFNEventStreamKey: EnvironmentKey
{
    static let defaultValue: SPFNEventStream? = nil
}

extension EnvironmentValues
{
    /// The stream the nearest `.spfnEventStream(_:keyLifecycle:)` attached, or nil.
    public var spfnEventStream: SPFNEventStream?
    {
        get
        {
            self[SPFNEventStreamKey.self]
        }
        set
        {
            self[SPFNEventStreamKey.self] = newValue
        }
    }
}

extension View
{
    /// Attach `stream` at the app's root, once per scene. Every scene may attach the same
    /// stream; a second, different stream shadows the first for the views below it.
    public func spfnEventStream(_ stream: SPFNEventStream, keyLifecycle: SPFNKeyLifecycle) -> some View
    {
        modifier(SPFNEventStreamAttachment(stream: stream, keyLifecycle: keyLifecycle))
    }

    /// Every signal of `Payload`'s event, while this view is on screen.
    public func onSPFNEvent<Payload: SPFNEventPayload>(
        _ payload: Payload.Type,
        perform: @escaping @MainActor (SPFNEventSignal<Payload>) async -> Void
    ) -> some View
    {
        onSPFNEvent(Payload.eventName, decode: Payload.init(canonical:), perform: perform)
    }

    /// The frames of `Payload`'s event that pass `condition`. `id` is whatever `condition`
    /// captures: when it changes, the listener re-attaches with the new condition.
    public func onSPFNEvent<Payload: SPFNEventPayload, ID: Equatable & Sendable>(
        _ payload: Payload.Type,
        id: ID,
        where condition: @escaping @Sendable (Payload) -> Bool,
        perform: @escaping @MainActor (SPFNEventSignal<Payload>) async -> Void
    ) -> some View
    {
        onSPFNEvent(Payload.eventName, decode: Payload.init(canonical:), id: id, where: condition, perform: perform)
    }

    /// Every signal of the event `name`, decoded by `decode`.
    public func onSPFNEvent<Event: Sendable>(
        _ name: String,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event,
        perform: @escaping @MainActor (SPFNEventSignal<Event>) async -> Void
    ) -> some View
    {
        modifier(SPFNEventListening(name: name, decode: decode, condition: { _ in true }, id: name, perform: perform))
    }

    /// The frames of the event `name` that pass `condition`; `id` is what `condition` captures.
    public func onSPFNEvent<Event: Sendable, ID: Equatable & Sendable>(
        _ name: String,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event,
        id: ID,
        where condition: @escaping @Sendable (Event) -> Bool,
        perform: @escaping @MainActor (SPFNEventSignal<Event>) async -> Void
    ) -> some View
    {
        modifier(SPFNEventListening(name: name, decode: decode, condition: condition, id: id, perform: perform))
    }
}

private struct SPFNEventStreamAttachment: ViewModifier
{
    let stream: SPFNEventStream
    let keyLifecycle: SPFNKeyLifecycle

    @Environment(\.scenePhase) private var scenePhase
    @State private var scene = UUID()

    func body(content: Content) -> some View
    {
        content
            .environment(\.spfnEventStream, stream)
            .onAppear { report(scenePhase) }
            .onChange(of: scenePhase) { _, phase in report(phase) }
            .onDisappear { stream.setForeground(SPFNForegroundTally.shared(for: stream).remove(scene)) }
            .task(id: ObjectIdentifier(keyLifecycle))
            {
                [stream, keyLifecycle] in
                for await clientID in await keyLifecycle.signedInClientIDs
                {
                    stream.setSignedIn(clientID)
                }
            }
            .task(id: ObjectIdentifier(stream))
            {
                [stream] in
                await Self.observeNetwork(stream)
            }
            .task(id: ObjectIdentifier(stream))
            {
                [stream] in
                await Self.warnAboutUnavailableEvents(stream)
            }
    }

    private func report(_ phase: ScenePhase)
    {
        stream.setForeground(SPFNForegroundTally.shared(for: stream).set(scene, SPFNScenePresence(phase)))
    }

    private static func observeNetwork(_ stream: SPFNEventStream) async
    {
        #if canImport(Network)
        for await available in SPFNNetworkMonitor.availability()
        {
            stream.setNetworkAvailable(available)
        }
        #endif
    }

    /// Q-F: the server does not serve some configured names and the stream runs without
    /// them. Said once per change, in debug builds only; event names are safe to log (§6).
    private static func warnAboutUnavailableEvents(_ stream: SPFNEventStream) async
    {
        #if DEBUG
        var reported: [String] = []
        for await state in stream.states
        {
            guard case .open(_, let unavailable) = state, unavailable != reported
            else
            {
                continue
            }
            reported = unavailable
            if !unavailable.isEmpty
            {
                print("SPFNEvents: the server does not serve these configured events: \(unavailable.joined(separator: ", "))")
            }
        }
        #endif
    }
}

private struct SPFNEventListening<Event: Sendable, ID: Equatable & Sendable>: ViewModifier
{
    let name: String
    let decode: @Sendable (SPFNCanonicalValue) throws -> Event
    let condition: @Sendable (Event) -> Bool
    let id: ID
    let perform: @MainActor (SPFNEventSignal<Event>) async -> Void

    @Environment(\.spfnEventStream) private var stream

    func body(content: Content) -> some View
    {
        content.task(id: id)
        {
            [stream, name, decode, condition, perform] in
            // L-8: listening with no attachment above is a programmer error, not a wait.
            guard let stream
            else
            {
                preconditionFailure("no .spfnEventStream(_:keyLifecycle:) above this view: attach the stream at the app's root")
            }
            for await signal in stream.listen(name, decode: decode, where: condition)
            {
                await perform(signal)
            }
        }
    }
}

extension SPFNScenePresence
{
    init(_ phase: ScenePhase)
    {
        switch phase
        {
        case .active:
            self = .active
        case .inactive:
            self = .inactive
        case .background:
            self = .background
        @unknown default:
            self = .inactive
        }
    }
}
#endif
