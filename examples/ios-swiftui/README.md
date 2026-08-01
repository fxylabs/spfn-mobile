# iOS SwiftUI reference app — placeholder

Nothing here yet. The `clientProofV1` slice can build a proof and canonicalize a
request, but there is no transport to send one over and no key custody to hold a key,
so a reference app would have to invent both.

The deployment target and Swift toolchain are now decided (D5: iOS 16, swift-tools 6.0),
so the remaining blocker is behaviour rather than baseline.

This app arrives alongside the Compose reference app, so the two demonstrate identical
behaviour against identical fixtures.
