// SPFN Mobile — the snapshot every state is read in at once (`getStates`).
//
// `{ at, states: { [id]?: { value, data? } }, manifest: { [id]: { key, write } } }` — the
// shape the state router derives (README "Contract"). The state client reads it itself:
// the generated decoder checks the document's shape first, and what stays here is the
// canonical value, so each state's entry is decoded by that state's own read decoder.
//
// android/spfn-state/.../SpfnStatesSnapshot.kt is the same value in Kotlin.

import SPFNCore

public struct SPFNStatesSnapshot: Equatable, Sendable
{
    /// What the manifest says of one state: its key fields and whether it has a write.
    public struct State: Equatable, Sendable
    {
        public let key: [String]
        public let write: Bool

        public init(key: [String], write: Bool)
        {
            self.key = key
            self.write = write
        }
    }

    public let at: String
    public let manifest: [String: State]
    /// Each state present, as `{ value, data? }`. A state the subject may not read is absent.
    public let states: [String: SPFNCanonicalValue]

    public init(at: String, manifest: [String: State], states: [String: SPFNCanonicalValue])
    {
        self.at = at
        self.manifest = manifest
        self.states = states
    }

    /// Reads a snapshot from its canonical value — the server's answer, or a fixture.
    public init(canonical: SPFNCanonicalValue) throws
    {
        let members = try SPFNDecoding.object(canonical, at: "$")
        at = try SPFNDecoding.string(members["at"], at: "$.at")
        states = try SPFNDecoding.object(members["states"], at: "$.states")
        manifest = try SPFNDecoding.object(members["manifest"], at: "$.manifest").reduce(into: [:])
        {
            manifest, member in
            manifest[member.key] = try Self.state(member.value, at: "$.manifest." + member.key)
        }
    }

    private static func state(_ value: SPFNCanonicalValue, at path: String) throws -> State
    {
        let members = try SPFNDecoding.object(value, at: path)
        let key = try SPFNDecoding.array(members["key"], at: path + ".key").enumerated().map
        {
            try SPFNDecoding.string($0.element, at: path + ".key[\($0.offset)]")
        }
        return State(key: key, write: try SPFNDecoding.boolean(members["write"], at: path + ".write"))
    }
}
