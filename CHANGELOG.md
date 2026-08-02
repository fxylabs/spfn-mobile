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
- iOS distribution channel: Swift Package Manager only (decision D11, 2026-08-02).
  CocoaPods is not supported, the fixture under `tools/cocoapods-compat/` stays as
  proof that the Swift sources are single-sourced, and no activation condition is
  recorded. The validator now holds that shut: it asserts the resolved D11 row and
  refuses reopening wording in the fixture README, with
  `tools/validate/probe-d11-guardrail.sh` proving the pattern still bites.

### Added after Step 2 — the integration run against a server this repository did not write

- `sh tools/reference-server/run-integration.sh` takes a target: give it
  `SPFN_INTEGRATION_TARGET_URL` and `SPFN_INTEGRATION_CONTROL_TOKEN`, or
  `SPFN_INTEGRATION_LAUNCH_FILE`, and it runs the same ten cases against a server it did
  not start. The point is the canonical implementation. Everything the run proved until
  now, it proved against this repository's own reference server, which is two ends built
  from one reading of the contract; the SPFN primitives mobile contract surface is a
  reading nobody here wrote.
- In that mode the script starts nothing and stops nothing. It probes the target, checks
  the control token before a suite runs rather than after five cases failed for a reason
  that looks like a broken server, and ends by checking the target is still up — which is
  what "no server left behind" means when the server was never this run's to leave. The
  orphan sweep is skipped there on purpose: a target may be a reference server of its own,
  and killing somebody else's process is the script reaching outside its own run.
- A named target that cannot be used is a failure, never a quiet fall back to a local
  server. A missing token, an unreadable launch file, a URL that is not absolute and a
  token the target refuses each stop the run. A run that checked the local server while
  reporting the external one would claim the strongest evidence this repository can
  produce while producing none of it.
- The Android suite reaches an external server the same way the Swift suite already did.
  Every state a case arranges — dropping a session, revoking a key, holding a request —
  now goes through one control surface, which is a method call in process and `/control`
  over HTTP against a target, so the five case bodies are the same code in both modes and
  record the same receipts.
- Case (b) drops the session through the control surface instead of moving a test clock,
  because an external server runs on a wall clock nothing can move. The rule the clock
  covered — a session dies at the instant the server said it would — is now a reference
  server unit test, where the clock is injected and a test moves it by hand.
- The control token is never a command-line argument: `curl` reads it from a config file
  and the Android suite reads it from a launch file, because arguments are readable by
  every process on the machine.

### Added after Step 2 — the local reference server and the integration run

- `tools/reference-server` (`:reference-server`): a local server implementing the pinned
  contract — three operations, clientProofV1 verification in the contract's check order,
  the contract's error envelope, and nothing else. A test fixture, not a deployment: it
  binds the loopback interface, keeps nothing on disk, and the only key it verifies a
  proof against is the synthetic conformance vector, marked TEST VECTOR ONLY.
- Both SDKs now complete a real HTTP round trip. Before this, every claim rested on
  fixtures and stand-in transports; the exchange itself had never happened.
- The server refuses a body whose bytes are not the canonical form of the value they
  encode, even when the proof over those bytes verifies. `bodySha256` is taken over the
  bytes that arrived, never over a re-encoding of them: digesting what the server produced
  would make the digest agree with itself no matter what the client sent.
- Shape failures — an unroutable path, a repeated header field, a non-canonical body, a
  session header in the wrong place — answer `CONTRACT_UNSUPPORTED`. The contract declares
  six codes and forbids inventing a seventh, and none of the four auth-family codes may
  carry a malformed request: the SDK re-handshakes exactly once on those, and it would be
  re-sending the same malformed bytes. `PROFILE_REJECTED` is used for exactly the one
  thing it names.
- The server has its own replay ledger, bounded: an entry is dropped once the window has
  passed it, and not one moment earlier, since a nonce dropped early becomes spendable
  again while a proof carrying it would still be accepted. Its check order is not a second
  opinion — `SpfnReferenceCheckOrderTest` presents every combination of the four refusal
  grounds to both it and the SDK's `SpfnProofAcceptance` and fails if they ever disagree.
- `sh tools/reference-server/run-integration.sh`: one command that starts the server, runs
  the Swift suite in one process and the Android suite in another, and stops the server.
  Both run the same five cases — round trip, session expiry recovered by exactly one
  re-handshake, a revocation that survives the re-handshake, a byte-for-byte replay, and a
  timeout and a cancellation against a server holding the call open.
- The run fails when a suite skipped rather than ran. A skipped XCTest is reported as a
  passing XCTest, so each case writes a receipt file and the runner fails unless all ten
  are on disk. The Swift suite also announces its skip on standard output when
  `SPFN_REFERENCE_SERVER_URL` is unset.
- Integration cases are excluded from `./gradlew build` by name, so the unit gates stay
  fast; the runner always runs all of them.
- Nothing a request carried reaches the server's log. One line per request names the
  method, the path and the status, and a test runs a full exchange and fails if any header
  value, session identifier or body fragment turns up in it.

### Added after Step 2 — the single execute path

- `SPFNClient` (Swift) and `SpfnClient` (Android): one function every operation goes
  through. Nothing else sends a request, so the rules stated on it — the body is encoded
  once, the proof is fresh, and the retry policy below — hold for every operation the
  contract will ever declare.
- A typed error taxonomy: transport, auth, server, decoding, and a refusal for an
  operation that does not belong on this path. Which one a refusal is depends on the
  error code the contract declares, never on the HTTP status: a 401 an intermediary
  wrote carries no envelope and lands in decoding, so it cannot provoke a re-handshake
  against something that never refused a proof. The code-to-class mapping is an
  exhaustive switch, so a code added to the contract fails the build until it is
  classified.
- Retry stays off, with one exception: an auth refusal re-opens the session and re-sends
  the request exactly once, with a new nonce and a new proof over the same body bytes. A
  transport failure is not retried, because this layer cannot tell a request that never
  arrived from one whose answer was lost. A refused handshake is surfaced rather than
  retried. The ceiling is the shape of the code — a straight line with no path back —
  rather than a counter.
- A refusal discards only the session the refused request presented. Concurrent calls
  meeting one revocation therefore share a single re-handshake instead of each throwing
  away the session the previous one just opened.
- Cancellation between the two attempts costs no further request, and keeps the shape
  each platform already uses for it: `SPFNTransportError.cancelled` on Swift,
  `CancellationException` on Android.
- Neither new failure type prints what the server wrote, on any default output path, and
  each suite proves the two layers of redaction separately rather than as one.
- `SPFNSession` gained three things the path above needs: a public `baseURL` so there is
  one copy of it rather than two, an `invalidate` that only discards a named session, and
  the HTTP status alongside a refused handshake's envelope.

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
  operation carries a session header. The header names were a dev-bundle extension when
  this landed; SPFN primitives issue #46 has since adopted them unchanged and shipped
  them in `@spfn/auth@0.2.0-beta.85`, which resolves D23.
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

An upstream-exported contract bundle (D17) — the wire header mapping it will carry is
ratified (D23), but the bundle in `Contracts/` is still hand-authored — a client clock
skew margin (D24), generated per-operation call descriptors — the execute path is
generic, and the three operations are described by hand in the test suites until the
generator emits them — a completed exchange with a real server, since the runner can now
be pointed at one but no run in this repository has been, persistence, the hybrid bridge,
key custody
beyond the in-memory alpha provider, CODEOWNERS identities, signing identities,
registry configuration, pinned CI action SHAs, and every `COMPATIBILITY.md` support
row. See `docs/OPEN-DECISIONS.md`.
