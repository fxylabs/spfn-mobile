# Android Compose reference app — placeholder

Nothing here yet. The library modules compile now (AGP 9.2.1, Kotlin 2.4.10, minSdk 24,
compileSdk 36 — decision D5), but an application module would need `targetSdk`, a
manifest, a launcher and a signing configuration, and it would have nothing to show:
the `clientProofV1` slice can build a proof and canonicalize a request, while transport
and key custody do not exist.

Adding an app now would mean inventing a signing configuration for a build nobody ships.

This app arrives alongside the SwiftUI reference app, so the two demonstrate identical
behaviour against identical fixtures.
