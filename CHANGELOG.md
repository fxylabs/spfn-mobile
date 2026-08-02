# Changelog

No release has been made. Entries below describe repository state, not shipped
software.

## 0.1.0-alpha.1 — unreleased

Step 1 laid out the repository. Step 2 made it compile on both platforms and proved one
vertical slice end to end. Nothing is committed, tagged or published.

### Decided in Step 4 preparation

- License: MIT, Copyright FXY Inc. (decision D8, 2026-08-01), matching the upstream
  SPFN primitives repository.
- First release-train version: `0.1.0-alpha.1` (decision D9, 2026-08-01), lockstep
  across the SwiftPM tag and Maven version; 1.0.0 waits on Step 5 evidence.

### Added after Step 2 — session and proof issuance

- `SPFNSession` (Swift) and `SpfnSession` (Android): holds the session a handshake
  opened, opens one when there is none or it has expired, and issues the headers a
  request carries. It does not retry, does not re-handshake on a server answer and does
  not classify transport failures; those belong to the single execute path above it.
- Many concurrent callers open at most one session. The in-flight handshake is shared
  explicitly rather than left to actor isolation, which admits other calls while a call
  is suspended on the network.
- The handshake body is encoded once and the same bytes are both signed and sent, so the
  proof cannot cover a different value from the one on the wire.
- `SPFNKeyProvider` / `SpfnKeyProvider` applies the key to one message instead of
  returning it, and the in-memory alpha implementation prints `redacted`. A session
  identifier is redacted the same way.
- Injected clock and nonce generator, so expiry is judged at an exact instant and every
  proof carries a fresh nonce — both assertable rather than assumed.
- The pinned contract bundle gained a `wireMapping` section: which header each proof
  field rides in, the request content type, and the rule that only a `requiresSession`
  operation carries a session header. The header names are a dev-bundle extension
  awaiting upstream ratification (D23).
- `Contracts/fixtures/request/wire.json`: two fully assembled requests — exact header
  names, exact values including the proof, exact body bytes — derived by
  `Contracts/fixtures/derive-expected-values.py` rather than by either SDK. Both test
  suites reproduce them and both assert their header constants against the bundle itself.
- The client module now depends on auth and generated on both platforms, recorded in
  `tools/module-graph.json` and mirrored by the SwiftPM manifest, the Gradle module and
  the CocoaPods fixture.

### Added after Step 2 — transport boundary

- `SPFNClient` (Swift) and `spfn-client` (Android): a transport that sends exactly one
  HTTP request and returns one HTTP response. It does not retry — including OkHttp's
  connection-failure retry, which is switched off because it can re-send a request that
  was already written to a socket the server had closed — does not follow redirects,
  keeps no cookies and no cache, distinguishes a timeout from a connectivity failure,
  keeps an absent request body distinct from an empty one, and returns every non-2xx
  status as a response rather than an error.
- A request that names the same header field twice is refused before anything is sent.
  OkHttp writes two header lines for it and URLRequest folds them into one comma-joined
  field, so the same request would otherwise put different bytes on the wire on the two
  platforms.
- Platform adapters: `SPFNURLSessionTransport` over URLSession, `SpfnOkHttpTransport`
  over OkHttp 5. Two test suites with corresponding case names, so the parity between
  them is checkable rather than asserted.
- OkHttp 5.4.0 and kotlinx-coroutines 1.11.0 — the Android SDK's first runtime
  dependencies, with real network-fetched checksums in
  `gradle/verification-metadata.xml`. The Swift package still has none.
- The validator's toolchain-baseline check now reads its module list from
  `tools/module-graph.json` instead of a hand-written list that stopped covering a
  module the moment one was added.

### Added in Step 2

- Toolchain baseline from decision D5: swift-tools 6.0 with Swift 6 language mode,
  iOS 16 / macOS 13, Gradle 9.5.1, AGP 9.2.1, Kotlin 2.4.10, JDK 21 toolchain,
  minSdk 24, compileSdk 36.
- Gradle wrapper, with the distribution and the wrapper jar pinned to the SHA-256
  checksums gradle.org publishes for 9.5.1 (`gradle/wrapper/WRAPPER-PINS.json`).
- All five Android modules compile as AAR libraries; `spfn-core` and `spfn-auth` run
  unit tests.
- `gradle/verification-metadata.xml` populated with real, network-fetched checksums for
  every resolved artifact.
- A pinned `clientProofV1` contract bundle with a real SHA-256, recorded in
  `Contracts/upstream.lock.json` as locally authored rather than upstream-exported.
- `tools/contract-codegen`: a zero-network, deterministic generator that produces the
  Swift and Kotlin clients from that bundle in one run, refuses to run when the bundle
  digest does not match the lock, and stamps every output with the digest it read.
- Canonical JSON (SPFN-CANON-JSON-1) and clientProofV1 proof assembly, implemented
  independently on each platform.
- Conformance fixtures under `Contracts/fixtures/`, whose expected values come from a
  third implementation rather than from either SDK, and two test suites that consume
  the same files.
- Validator rules for wrapper checksums, contract provenance, generated-source
  traceability, the repository allowlist and the compatibility matrix.

### Added in Step 1

- SwiftPM manifest with five products and no external dependencies.
- Gradle multi-project root and five Android module shells.
- `tools/module-graph.json` as the single source of truth for the module graph.
- `tools/validate/validate.sh`, a zero-dependency offline validator.
- Contract placeholders under `Contracts/` with enforced placeholder discipline.
- An internal, unpublished CocoaPods compatibility fixture generated from the module graph.
- Five inert, manual-only CI workflow files; none is a gate.
- `docs/SCAFFOLD-STATUS.md` and `docs/OPEN-DECISIONS.md`.

### Still deliberately absent

An upstream-exported contract bundle (D17), an upstream-ratified wire header mapping
(D23), the single execute path — typed server and auth errors, and a re-handshake
retry driven by what the server answered — persistence, the hybrid bridge, key custody
beyond the in-memory alpha provider, CODEOWNERS identities, signing identities,
registry configuration, pinned CI action SHAs, and every `COMPATIBILITY.md` support
row. See `docs/OPEN-DECISIONS.md`.
