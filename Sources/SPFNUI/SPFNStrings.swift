// SPFN Mobile — the words a generated screen is allowed to show.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/SpfnStrings.kt.
// Same keys, same order, and section 15 of tools/validate/validate.sh compares the two key
// sets exactly as it compares the tokens'.
//
// ---------------------------------------------------------------------------
// Why the SDK holds these words at all
// ---------------------------------------------------------------------------
//
// A screen that failed has to say something, and there are only two places the sentence can
// come from: the server's envelope, or here. It cannot come from the envelope. `message` is
// text a server chose — SPFNCore's own header says a server can put anything in it, including
// an identifier it echoed back — so putting it on a screen publishes whatever the server
// felt like saying to whoever is holding the phone. That refusal is decision C7 and it is
// older than this file: `ScreenFailure` has always carried the envelope and never its text.
//
// So the generated screen classifies the failure into a KEY and looks the key up here. The
// envelope still travels — a support conversation needs the code and the request id — it is
// simply not what is drawn.
//
// No `Foundation`, no `SwiftUI`, no localisation table. A lookup that could miss is a screen
// that can be blank; these are stored properties and the compiler is what checks them. When
// the consuming app wants its own wording it overrides at the call site, which is the same
// door a host app already has for a screen's title.

/// Every sentence a generated screen can draw.
public enum SPFNStrings
{
    /// The code names no device the server is holding a request for.
    public static let errorDeviceNotFound = "That code does not match a device waiting for approval."

    /// Nothing was reached: no response existed, or the response was not one this build reads.
    public static let errorNetwork = "Could not reach the server. Check the connection and try again."

    /// The server refused this device's credentials.
    public static let errorUnauthorized = "This device is not signed in. Sign in again and retry."

    /// The screen refused its own input before anything was sent.
    public static let errorValidation = "Fill this in to continue."

    /// A failure this SDK classifies as nothing more specific.
    public static let errorUnexpected = "Something went wrong. Try again."

    /// A read is in flight and has never completed.
    public static let stateLoading = "Loading…"

    /// A read completed and there is nothing to show.
    public static let stateEmpty = "Nothing to show."

    /// The control on an error state that runs the read again.
    public static let actionRetry = "Try again"

    /// The header control that drops one route.
    public static let controlBack = "Back"

    /// The header control that closes the whole flow.
    public static let controlClose = "Close"
}
