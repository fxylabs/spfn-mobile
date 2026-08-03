# Changelog

No release has been made. Entries below describe repository state, not shipped
software.

## 0.1.0-alpha.2 — unreleased

The candidate version moved, not the code. `0.1.0-alpha.1` exists as a public tag and
was never published to Central: its dispatch failed because the candidate commit it
names predates the cold-cache verification-metadata fix (#14), so a build of that
exact tree cannot pass dependency verification on a cold runner. Published versions —
and their tags — are immutable here, so the tag stays where it is and the candidate
is reissued as `0.1.0-alpha.2` from a tree that contains the fix. Everything below
the next heading describes work that happened while the train carried the alpha.1
number.

### clientProofV1 revised to asymmetric ECDSA P-256 (contract 0.2.0)

- The pin moved to primitives commit `4380bc40`, contract `0.2.0`, digest
  `28f2fd4c…`: the proof is now an ECDSA P-256 signature over the unchanged
  SPFN-PROOF-INPUT-1 bytes — raw `r ‖ s` 64 bytes as base16-lower (128 hex), DER
  refused on the wire, low-S not required — verified against a registered public
  key (SPKI DER base64). Wire header names, admission order, replay window, nonce
  rules and the refusal codes are unchanged by contract.
- The key provider is a signer now: `withKey` (which exposed the symmetric key to
  the call site) is retired for `sign(message) → raw r ‖ s`, so a private key never
  exists as a value outside a provider and a hardware-backed provider can land
  later without a protocol change. The shipped implementation is a software P-256
  provider; Kotlin converts the JCA's DER through a strict `SpfnEcdsa` codec whose
  33- and 31-byte integer paddings are pinned by unit tests, while CryptoKit's
  `rawRepresentation` is already raw.
- The reference server registers public keys (`/control/register-key`, mirroring
  the primitives dev surface field for field) and verifies signatures; the fixtures
  are two-tier — proof-input bytes stay byte-pinned, signatures are judged by
  verification against the fixed test keypair, and `derive-expected-values.py`
  gained a pure-stdlib P-256 implementation (cross-checked against OpenSSL) that
  signs the recorded values deterministically via RFC 6979.

## 0.1.0-alpha.1 — superseded by 0.1.0-alpha.2, tagged but never published

Step 1 laid out the repository. Step 2 made it compile on both platforms and proved one
vertical slice end to end. Nothing is committed, tagged or published.

### Dependency verification: the three artifacts only a cold cache reveals

- The observability shipped for it found the CI failure the same day: dispatch
  30798267699 failed on `Dependency verification failed for configuration
  'classpath'` for three metadata artifacts missing from
  `gradle/verification-metadata.xml` — `guava-parent-33.3.1-jre.pom`,
  `junit-bom-5.11.0-M2.module`, `kotlinx-coroutines-bom-1.8.0.pom`. A metadata
  artifact is verified only when it is downloaded and parsed; a warm local cache
  serves the parsed descriptor and never re-verifies, so every local run passed
  while every cold CI runner failed. Reproduced locally both ways with a fresh
  `GRADLE_USER_HOME`: the exact three-artifact failure before the fix, a clean
  publish after it.
- The fix is the three missing checksums and nothing else — 13 lines, append-only,
  no churn, no broad trust: each SHA-256 computed from the repo1.maven.org artifact
  and cross-checked against Central's own published checksum sidecar. Fail-closed
  proven by flipping one hex digit on a cold home: exactly that artifact refuses.

### RC failure observability — evidence leaves the runner, secrets do not

- Central dispatch 30795139768 failed inside the RC harness and the logs died with
  the runner. `publish-central.yml` now surfaces failure evidence: on the failure
  path only, the tail of every log under the harness output directory is printed and
  the `rc-out/logs` directory is uploaded as an artifact. Observability is confined
  to that directory on purpose — the harness's swift/gradle/manifest logs never
  carry the in-memory key, its passphrase or the Central token, and GitHub's secret
  masking is treated as a second net, not the mechanism.
- The upload uses the repository's first third-party action,
  `actions/upload-artifact`, pinned by commit SHA. The validator's no-actions rule
  became an allow-list with exactly that entry for exactly that workflow: a
  tag-pinned form of the same action, any other action, or any action at all in any
  other workflow still fails, and each refusal is a probe case. D14 stays open for
  the general action set.
- The workflow clone is `--no-tags`: the candidate tag exists on the remote now, and
  the harness creates its own. The existing tag-removal line stays as the second
  layer; the CI failure was not the tag either way, since the SwiftPM stage had
  already passed.
- `tools/rc-verify/local-signed-run.sh`: the manual reproduction that cleared the
  key and signing path, automated — fresh `--no-tags` clone under a directory named
  exactly `spfn-mobile` (the basename is the SwiftPM package identity the consumer
  resolves), exact-commit checkout, passphrase read with `stty -echo` (portable
  where shell read flags are not), armored key exported into process memory only,
  and a trap that removes the clone on every exit path. Key material is never
  echoed, logged or written to disk.

### The publication transition — a Central path that fails closed

- D4 resolved (2026-08-03): the `xyz.superfunction` namespace is domain-verified on
  the Central Portal, so `xyz.superfunction.spfn` is a real coordinate.
  `gradle.properties` records `spfn.maven.group` with `verified=true`, the root build
  script requires the flag to stay true, and every staged POM now carries the full
  Central-required metadata set — name, description, url, MIT license, developers,
  scm — asserted per element by the RC harness at staging time.
- `.github/workflows/publish-central.yml`: the one path to Maven Central.
  `workflow_dispatch` only — the validator parses every workflow's `on:` trigger set,
  flow-style and block-style alike, and fails anything that is not exactly
  `workflow_dispatch`, so an unlisted or future trigger kind fails too — against a
  person-named commit validated as exactly 40 hex characters through an env
  assignment (never interpolated into run text), re-running the RC verification
  before anything is bundled. The upload is `USER_MANAGED`: the Portal
  holds the deployment until a person confirms it in the Portal UI. Secrets are
  referenced by name from a validator-pinned allowlist and are not registered, so
  every dispatch fails today, by design. Gradle never gains a remote repository:
  Central is reached by posting the staged bundle to the Portal publisher API, chosen
  over a publishing plugin because it keeps the staging gate as the only Gradle
  publication path and adds no third-party build code.
- Signing exists as lookup configuration only (D7): an in-memory PGP key injected per
  run as `ORG_GRADLE_PROJECT_*` environment variables into `useInMemoryPgpKeys`. With
  a key, every staged artifact gains its detached `.asc` and the harness requires
  them; without one, a local RC run stays unsigned and the harness requires the
  absence. No key identity, key file or keyring path exists in the tree.
- The validator's credential rule turned from a flat ban into a boundary with teeth:
  both credential forms — the `credentials { }` block and the call form — are
  detected everywhere, only pure lookups are admitted, a literal username or password
  value fails wherever it appears, a credential-shaped property in
  `gradle.properties` fails, and `.asc`/`.gpg`/keyring files join the forbidden-file
  scan. Every new refusal is probed against the real validator by
  `tools/validate/probe-publication-rules.sh`, including the one admission that must
  keep passing.

### Step 5 — the release candidate verified without publishing

- `tools/rc-verify/rc-verify.sh`: one reproducible run that produces the whole
  no-publish candidate evidence (D3, resolved 2026-08-03). It creates the prefix-free
  local tag, resolves it from a throwaway SwiftPM consumer with
  `.package(url: "file://…", exact:)` under per-run cache and config paths so nothing
  is replayed from a previous resolution, builds and runs a smoke executable that
  imports every public product and touches a symbol in each, stages every Android
  module (AAR + POM + sources) to a `$TMPDIR` directory, compiles a throwaway Android
  consumer against the staged coordinates, and removes the tag and every intermediate
  directory on exit — success or failure. Only the output directory survives.
- The publication gate turned from a flat refusal into a narrow door with the same
  lock. The committed `gradle.properties` value must stay `false` and is read from the
  file itself, so a tree committed with `true` fails every build and a CLI override
  cannot launder it. Enabling publication for a run is legal only as a CLI override
  targeting an absolute staging directory outside the repository. Every refusal — and
  the one legal admission — is proven by `tools/validate/probe-publishing-gate.sh`, and
  the validator pins the gate's load-bearing lines so removing a refusal removes a
  string it checks.
- Staged coordinates are `xyz.superfunction.spfn:<module>:0.1.0-alpha.1`. That group is
  still the D4 PROPOSED value: `spfn.maven.group.verified` stays `false`, and every
  staged POM restates in its own description that the group is proposed, unverified and
  local-staging only.
- SBOMs are CycloneDX on both platforms (D7, resolved 2026-08-03): the CycloneDX Gradle
  plugin for Android, registered on demand only — the default build graph carries no
  SBOM task, and the harness fails if one leaks in — and static generation for iOS
  (`tools/rc-verify/generate-ios-sbom.sh`), whose component edges are read from
  `tools/module-graph.json` because the Swift package has zero external dependencies to
  resolve. Alpha candidates are unsigned: candidate identity is the source commit,
  `SHA256SUMS` and `manifest.json` binding version, commit, pinned contract digest and
  every artifact hash. Signing and provenance attestation arrive with public releases.
- `docs/IMPLEMENTATION-PITFALLS.md`: the implementation-pitfall registry adopted for
  this repository's delegated work, copied verbatim from the reviewed final report.

### The contract moved upstream

- `Contracts/spfn-mobile-contract.json` is now an SPFN primitives export, copied byte for
  byte from `contracts/mobile/` at commit `d31aa9a1` and pinned at digest `96c48f9c…`.
  It replaces the bundle Step 2 hand-authored here, which resolves D17 and clears the
  Step 5 blocker. The exporter's own `Contracts/upstream-provenance.json` sits beside it.
- Nothing an implementation depends on changed. Operations, wire mapping, canonical JSON,
  auth profiles, error envelope, types and proof input are byte-identical to what the dev
  bundle carried, and every conformance vector reproduced unchanged — only the digest
  references moved. Two prose fields were rewritten by upstream and are worth naming
  rather than folding into "unchanged": the `CONTRACT_UNSUPPORTED` summary now reads
  "the request is not the shape this contract describes" instead of describing a version
  mismatch, and `clientProofV1.revocationRule` now says revocation is *not inferable*
  from a proof failure where it used to say the two are *distinguishable* — the same
  rule, stated from the attacker's side. Upstream also added `typeGrammar`,
  `clientProofV1.admissionOrder` and `nonceRule`, each stating behaviour both sides
  already implemented.
- The contract line restarts at `0.1.0`, and that changed a rule rather than a number.
  `requireSupported` compared majors alone, which on a 0.x line accepts a `0.2.0` server
  the declared range `>=0.1.0 <0.2.0` excludes — a check weaker than the string it prints.
  It now parses strict SemVer and enforces both bounds: a malformed version refuses, a
  version below the pin refuses, a pre-release other than the pinned one refuses, a
  *pinned* pre-release admits only its own core, and numeric components compare as digit
  strings so a version longer than `Int` cannot overflow into acceptance.
  `tools/conformance/semver-range-vectors.json` holds 41 range cases and 24 parser cases;
  both platforms run both tables, so the rule cannot drift on one of them.
- Enforcing the pin exactly made the *declared* range a false advertisement, in the
  opposite direction to the bug above. `supportedRange` is contract data, copied from the
  bundle; a pre-release pin declares `>=1.0.0-dev.1 <2.0.0` while this SDK admits only
  `1.0.0-dev.1`, so a refusal that quoted the declared range named a window it would not
  honour. `admittedRange` is the enforced window and is what the upgrade error carries —
  the declared range for a release pin, the pinned version alone for a pre-release pin,
  and nothing at all for a pin the SDK cannot parse.
- The shared tables are held to being evidence rather than a transcript. Each suite runs
  the range and parser rules this change set *replaced* and requires the tables to catch
  them, so reverting the rule and relaxing the tables to match fails instead of passing
  quietly. The validator counts table entries structurally rather than grepping for a
  quoted word — prose in a `why` field can contain any word — checks every entry carries
  the fields both suites read, and requires each suite to still consume both tables and
  still carry the probe.
- The validator's provenance gate turned around with the fact it guards. It used to
  refuse any upstream claim for want of evidence; it now checks the lock against
  `upstream-provenance.json` field by field, requires the bundle to label itself
  `UPSTREAM_EXPORT`, refuses evidence naming this repository as the source, and derives
  the expected range from the pinned version so a pin cannot quietly widen what the SDK
  accepts. Digest and fixture checks moved out of the dev-bundle branch so moving the
  lock upstream could not silently drop them.
- Both platform suites pass, and the two-platform integration matrix passes in
  external-target mode against the primitives `04-mobile-contract-dev` server — ten
  receipts against an implementation nobody here wrote.

### Decided in Step 4 preparation

- License: MIT, Copyright FXY Inc. (decision D8, 2026-08-01), matching the upstream
  SPFN primitives repository.
- First release-train version: `0.1.0-alpha.1` (decision D9, 2026-08-01), lockstep
  across the SwiftPM tag and Maven version; 1.0.0 waits on Step 5 evidence.
- iOS distribution channel: Swift Package Manager only (decision D11, 2026-08-02).
  CocoaPods is not supported, the fixture under `tools/cocoapods-compat/` stays as
  proof that the Swift sources are single-sourced, and no activation condition is
  recorded. The validator now holds that shut: `tools/validate/d11-policy.lock.json`
  pins by digest both places the decision is written down — the D11 row and the policy
  statement in the fixture README — so reopening it has to be a deliberate edit to the
  lock rather than a sentence nobody noticed. A phrase blocklist covers the rest of that
  file as a second net, and `tools/validate/probe-d11-guardrail.sh` proves both still
  bite.

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

A client clock skew margin (D24), generated per-operation call descriptors — the execute
path is generic, and the three operations are described by hand in the test suites until
the generator emits them — an exchange with a *deployed* SPFN service, since the matrix
has now run against the primitives dev server but never against a real deployment,
persistence, the hybrid bridge, key custody beyond the in-memory alpha provider,
CODEOWNERS identities, signing identities, registry configuration, pinned CI action SHAs,
and every `COMPATIBILITY.md` support row. See `docs/OPEN-DECISIONS.md`.
