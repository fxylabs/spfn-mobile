// SPFN Mobile — platform signals, read as the event stream's three inputs.
//
// The rules the attachment applies are small and named, so a Linux test can hold them
// without SwiftUI or Network: a scene that is active or inactive is in the foreground —
// pulling down Control Center or opening the app switcher makes a scene inactive, and
// reading that as the background would reconnect on every swipe (§8 H-6) — and a network
// path that is satisfied is the network. The SwiftUI and Network files map their own types
// onto these two enums and nothing else.
//
// android/spfn-events/.../SpfnEventSignals.kt is the same rule set in Kotlin.

/// One scene's phase, mirrored from SwiftUI's `ScenePhase`.
public enum SPFNScenePresence: Equatable, Sendable
{
    case active
    case inactive
    case background

    /// Only `.background` is the background.
    public var isForeground: Bool
    {
        self != .background
    }
}

/// The network path's status, mirrored from Network's `NWPath.Status`.
public enum SPFNNetworkPathStatus: Equatable, Sendable
{
    case satisfied
    case unsatisfied
    case requiresConnection

    /// Only a satisfied path is the network.
    public var isAvailable: Bool
    {
        self == .satisfied
    }
}
