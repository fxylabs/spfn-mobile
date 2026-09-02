# Changelog

`0.1.0-alpha.3` is the current published release and `0.1.0-alpha.2` is superseded.
Entries under an unreleased heading describe repository state, not shipped software.

## Unreleased

### What two real devices changed about the example cells

- **Maestro's `back` is Android's command and does nothing on iOS.** The generated flows
  for cells u7b and u10b failed on an iPhone 17 Pro simulator (iOS 26.3) on 2026-09-02 —
  not at the step, which reported success, but at the assertion after it, with the
  hierarchy still reading `stack=2`. `tools/ui-codegen` now emits a platform-conditional
  pair for a system back: `back` under `when: platform: Android`, the interactive-pop edge
  swipe under `when: platform: iOS`, which is the gesture `FlowHost`'s path binding
  reconciles. Registered as `docs/IMPLEMENTATION-PITFALLS.md` P22, and `validate.sh`
  section 14 now fails on a top-level `- back` in any `both` cell's flow. The harness's own
  hand-written flows were checked for the same shape and use no `back` at all.
- **A cold start is not a cell failure.** Cell u14 timed out once on a freshly wiped
  Pixel 3a emulator and passed twice on the same build warm. Every generated flow's FIRST
  wait is now 45 s while every later wait stays at 20 s, and `run-cells.sh` launches the
  app once with no fixture and waits for its root readout before any cell runs, so the
  cold start is paid outside the table.
- **`examples/ui-spec/run-cells.sh <ios|android>` runs the example cells**, in
  `tools/harness/run-harness.sh`'s shape and with its rule: it builds and installs nothing
  — the two commands per platform are in its header — runs every flow in one maestro
  invocation on the named device (`--device`), pulls the receipts off it, and fails unless
  every cell whose runner is `both` left one. Receipts and the Maestro report land in
  `examples/ui-spec/receipts/<platform>/<date>/`, gitignored but for a `.keep`.
  `--probe` proves the gate bites with no device at all: a full fixture directory passes,
  one receipt removed fails, and a table with no cells refuses to run rather than reporting
  full coverage.
- **A `Modal` flow covers its host on Android as well.** `spfn-ui`'s `FlowHost` draws a
  modal flow as an opaque, touch-tight cover filling everything the host gave it, where
  before it rendered inline under the host's own content while the same flow covered the
  host on iOS. It is deliberately not a `Dialog`: a dialog's content is a second semantics
  owner, and `testTagsAsResourceId` is resolved by walking semantics PARENTS, so every
  control in the flow would lose the resource id a Maestro `id:` selector matches. The
  cost of the choice is stated in the file: the host's content stays in the accessibility
  tree behind the cover, where iOS's `fullScreenCover` removes it. `spfn-ui` gained
  `androidx.compose.foundation` for `fillMaxSize` and `background`, declared in
  `tools/module-graph.json` like every other artifact it links.
- **`Generated/` has one owner again.** XcodeGen wrote the iOS example's `Info.plist` into
  `examples/ios-swiftui/Generated/`, which is `tools/ui-codegen`'s directory — whose write
  mode deletes what it did not emit and whose verify mode fails on what it finds. The
  plist moved beside that directory rather than into it.

### One spec, two app scaffolds: `ui-codegen` and the example apps

- **`tools/ui-codegen` generates the screen scaffold from one JSON spec.** Registered as
  `:ui-codegen`, not an SDK module and never published, sharing `:contract-codegen`'s
  bundle readers rather than copying them, zero external dependencies.
  `./gradlew :ui-codegen:spfnGenerateUi` writes; `:ui-codegen:spfnUiVerify` fails if any
  output is stale and is wired into `check`. From `examples/ui-spec/device-approval.json`
  it writes the SwiftUI and Compose halves of the device-approval flow — a service per
  service table, a route/flow/host per flow, a screen model and view skeleton per screen,
  an optional use case — plus the case table and one Maestro flow per cell. The two
  emitters are structurally mirrored, so a fix made on a Mac maps 1:1 to the Kotlin side.
- **Five things the spec refuses rather than warns about**: a `contract.manifestSha256`
  that is not the pinned bundle's, an operation the contract does not declare, a `then`
  target outside its own flow, a `start` that is not a screen of that flow, and a `call`
  naming a service method that does not exist. Each one's natural symptom would otherwise
  be a compile error inside a generated file nobody wrote. `examples/ui-spec/SCHEMA.md` is
  what a consumer app writes a spec against.
- **The generator is a fifth reader of the contract digest, and the spec is the second
  place it is written by hand.** `spfnGenerateUi` recomputes the bundle's sha256 and
  compares it with both `Contracts/upstream.lock.json` and the spec, refusing on either
  mismatch — two different mistakes, a bundle edited without re-pinning and a spec written
  against a bundle that is no longer pinned. `docs/IMPLEMENTATION-PITFALLS.md` P2 gained
  both roles; a full-digest `git grep -l` now names 48 files rather than 13, and 45 of
  them are derived by a generator in this repository.
- **The case table comes from the rules and the models come from the spec.** Two
  derivations from different sources — `Rules.kt` and the spec — meeting in the example
  app's unit suite, which drives each model against the table's own expectations. A table
  derived from the models it checks would prove only that the code equals itself (P10).
  Eighteen cells (u1–u14, plus u7b/u10b for system back and u8c/u9c for a response that
  arrives after `close()`), each declaring `unit`, `maestro` or `both`.
- **Four refusals live in the screen models**, and they are what the table checks: an empty
  required input is refused before anything is sent, a submit while busy is ignored,
  approve or deny before the read is `ready` is ignored, and a response arriving after
  `close()` changes no state — the last one guarded by a per-request token rather than by
  hoping the task was cancelled in time.
- **`examples/android-compose` is a running Compose application** — `:example-compose`,
  namespace `xyz.superfunction.spfn.example`, no signing config and no secrets in the
  tree, depending on `:spfn-ui`, `:spfn-client`, `:spfn-generated` and `:spfn-core`.
  `assembleDebug` builds it and `testDebugUnitTest` runs 18 cell tests plus 3 that check
  the fixture table against the case table, on the JVM with no Robolectric.
  `gradle/verification-metadata.xml` gained the artifacts `activity-compose` drags in.
  `examples/ios-swiftui` is the mirrored shell — an xcodegen `project.yml` at deployment
  target 17.0 and four hand-written sources — written blind and compiled on a Mac later.
- **Verification runs with no server.** `SPFN_UI_FIXTURE=<cell>` — an intent extra on
  Android, a launch argument on iOS — installs a fake service seeded for that one cell;
  with the flag absent no fake is constructed at all and the app builds its real client
  from the same `local.properties` keys the harness uses, fail-closed and never printed.
  Buttons carry the id `<screen>.<action>` and readouts are the text `<name>=<value>`, so
  one Maestro flow drives both platforms. The example flows and their receipts are kept
  separate from the harness's rather than shared with them.
- **`validate.sh` gained section 14: the example apps hold the generated boundary.** A
  call descriptor may be named in a generated service file and nowhere else under
  `examples/`; `dismiss` is refused under a generated directory for the reason section 13
  refuses it inside `SPFNUI`; and every cell of the case table must have the flow and the
  test its runner declares, where `both` means both rather than either. All three carry a
  floor and report what they read, because a scan that read nothing looks exactly like a
  clean tree (P7). `tools/validate/probe-example-scaffold-rules.sh` proves each refusal
  bites, in 11 cases.

### The `ui` module, and an iOS 17 baseline

- **The package baseline is iOS 17 / macOS 14** (D5 revision, approved 2026-09-02; it was
  iOS 16 / macOS 13). iOS 16's last security update was 16.7.16 in 2026-05, and the only
  devices that cannot go past it are the iPhone 8, 8 Plus and X. No `COMPATIBILITY.md`
  row ever promised 16, so nothing offered is withdrawn. `Package.swift`, the iOS harness
  project, its trait-carrier manifest and the two throwaway RC consumer manifests move
  together — a consumer package declaring macOS 13 cannot resolve a dependency that
  requires 14. Android's `minSdk` is unchanged at 24.
- **One new module: `ui` — Swift `SPFNUI`, Android `spfn-ui`** — depending on core alone.
  It holds the UI runtime vocabulary and nothing else: `Loadable`
  (loading·ready·empty·error) for one read, `Busy` (idle·busy·error) for one write,
  `FlowRoute`/`Flow` for a stack of routes with a presented flag, and `FlowHost`, the one
  place a platform navigator is bound to that stack. The two error states carry core's own
  error envelope, which is the whole reason for the edge. `Paged` and `Form` are deferred.
- **`Flow` is free of the UI toolkit on both platforms.** Every rule it holds is a rule
  about a list — `push`, `pop`, `replace`, `open(at:)`, `close()`, where `pop()` on the
  last route is a documented no-op and `open` on an empty stack is refused — so the whole
  transition table runs as an ordinary unit suite on the JVM and on Linux rather than only
  where a UI toolkit exists. `FlowHost` is the only file in either half that imports one.
- **`FlowHost` owns no stack.** SwiftUI's `NavigationStack(path:)` and Compose's
  `NavDisplay` both write back on a system pop, and both are bound so the write becomes
  `flow.pop()` instead of a second copy of the stack. `@Environment(\.dismiss)` appears
  nowhere in `SPFNUI` and the validator refuses it: it closes a presentation without
  telling the flow, which is how a host ends up dismissed over a flow that still believes
  it is open. On Android the entry style is a three-line difference — `NavDisplay` disables
  its own back handling when the scene has no previous entry, so a `Modal` flow puts a
  `BackHandler` over exactly that gap and closes while a `Push` flow lets it fall through.
- **Compose and Navigation 3 are the Android toolkit, and their cost is recorded.**
  Compose 1.11.4 (1.12.0 requires compileSdk 37 and D5 pins 36), Navigation 3 1.1.7 — the
  current stable, 1.2.0 being at beta01 — and `org.jetbrains.kotlin.plugin.compose`, which
  is what AGP 9.2.1 asks for to turn the Compose feature on. No Compose BOM: the validator
  resolves each dependency line to a catalogue alias and cannot read through a
  `platform(...)` wrapper. `gradle/verification-metadata.xml` grew by 154 components, all
  network-fetched, written by running `--write-verification-metadata sha256` over the real
  build as well as `help` — artifacts only a task resolves are not recorded by `help`.
- **`validate.sh` gained section 13: the ui vocabulary is one vocabulary.** It extracts
  `Loadable`'s and `Busy`'s state names and `Flow`'s public method names from both source
  trees and compares them per type, lowercased, scoped to the declaring type rather than
  to the file. Both sides carry a floor and the pass message carries the count, because a
  reader that read nothing yields an empty set and two empty sets agree. SwiftUI joined
  section 8's Apple-only framework list, so the whole-file guard on `FlowHost.swift` is
  enforced rather than merely present. `tools/validate/probe-ui-vocabulary-rules.sh` proves
  each refusal bites, in 12 cases.

### The Swift package builds and tests on Linux

- **`swift build` and `swift test` run on Linux** — Swift 6.2.1 on Ubuntu 24.04 builds
  `SPFNCore`, `SPFNGenerated`, `SPFNAuth` and `SPFNClient` and runs 274 tests with 20
  skipped and 0 failures, including the conformance suites against the same
  `Contracts/fixtures` bytes macOS asserts against. Until now every Swift change on a
  Linux host was written without a compiler; this is what ends that.
- **`swift-crypto` backs the Linux cryptography path.** CryptoKit ships with every
  Apple platform this package supports and does not exist on Linux; swift-crypto is
  Apple's own port of the same API, so `SPFNDigest`, `SPFNClientProof`,
  `SPFNSoftwareKeyProvider` and `SPFNCustodyKey` swap an import
  (`#if canImport(CryptoKit) … #else import Crypto`) and nothing else — no wrapper, no
  abstraction over the crypto types. Every target edge carries
  `.when(platforms: [.linux])`, so an iOS or macOS build links nothing new; SwiftPM
  still resolves the package on every platform, and `Package.resolved` stays untracked.
  `tools/module-graph.json` lists `swift-crypto` in `externalDeps.swift` on core, auth
  and client, which is the allowlist the validator holds `Package.swift` to.
- **Apple-only modules are declared, not inferred.** `tools/module-graph.json` is at
  schemaVersion 4 and carries a per-module `linux` key: absent means the module builds
  on Linux, the literal `false` means it has no Linux half at all, and a value that is
  neither fails as unread rather than being skipped — the same three-state rule
  `androidModule` already followed. `SPFNSocialApple` and `SPFNSocialGoogle` declare
  `"linux": false`. SwiftPM cannot condition a target on a platform, so what makes that
  true in the build is a whole-file guard on every one of their sources and tests; the
  targets compile to empty modules on Linux, with zero defined symbols.
- **Inside `SPFNClient` only the hardware pieces are Apple-only.** `SPFNCustodyKey`'s
  `.secureEnclave` backend and the enclave-creating branch are behind
  `#if canImport(CryptoKit) && canImport(Security)`, and `SPFNKeychainKeyStore` is behind
  `#if canImport(Security)`. `SPFNKeyCustody.secureEnclave` is a wire value and stays on
  every platform; a platform that cannot open an enclave blob answers nil, which is what
  a platform that has one already answers for a blob it cannot open. `generate(keyID:)`
  is now an overload that asks the platform, rather than a default argument that could
  not be split by `#if`; both existing call shapes are unchanged.
- **`validate.sh` learned the Linux half.** Section 8 buckets every module line into
  Linux-capable or declared-absent and fails unless the two add up; it checks both ends
  of every whole-file guard, because a guard closed early still looks guarded; it refuses
  an Apple-only framework imported outside a `canImport` guard in a module that builds on
  Linux, and a CryptoKit import outside one anywhere. The section 8 dependency rule was
  relaxed so a target with no graph edges may carry an external product the graph allows
  it behind `.when(platforms: [.linux])` — and only that: an unconditional product, or a
  product allowed to some other module, still fails.
  `tools/validate/probe-social-adapter-rules.sh` gained nine cases, one per new refusal.

### The call descriptor is generated, one per operation

- **`SPFNCall` / `SpfnCall` move from the client module to the core module** —
  `Sources/SPFNCore/SPFNCall.swift` and `xyz.superfunction.spfn.core.SpfnCall`, with the
  `noResponse` factory. The type never needed anything the client owns, and the generated
  module — which depends on core alone — is where the per-operation values now live. An
  app that imported `SPFNClient` / `spfn-client` reaches the type exactly as before, since
  both depend on core; a Swift file that named `SPFNCall` without `import SPFNCore` needs
  that import.
- **The generator emits one call descriptor per operation**, into
  `SPFNGeneratedCalls.swift` / `SpfnGeneratedCalls.kt`: `SPFNGeneratedCalls.echoSend`,
  `SPFNGeneratedCalls.authDeviceApprove`, one value per operation named exactly as its
  operation constant is. `auth.device.deny` is emitted through `noResponse`, and
  `core.time` — the one operation the contract gives no `requestType` — carries `Void` /
  `Unit`, because the caller that sends it today sends no request value. The Kotlin values
  are `@JvmField`, so a Java caller reads a field rather than a getter. Decision 4 stands:
  no wrapper functions, and nothing else is added to the SDK surface.
- **Every hand-written copy is gone.** The two Swift suites, the two harnesses and the
  reference-server support file each held their own private descriptor set — five copies
  of the same seven lines, and the drift between two of them broke a compile. They all
  send the generated values now, as do the lifecycle's `auth.device.start`,
  `auth.device.poll` and `auth.keys.rotate`. `auth.enroll.oauthNative` is the one
  descriptor the SDK still builds by hand: its path carries a `{provider}` segment that
  has to be substituted before the request rides on it.

### Device-code sign-in, on both platforms

- **The waiting device is one new entry point**, `SPFNKeyLifecycle.enrollByDeviceCode` /
  `SpfnKeyLifecycle.enrollByDeviceCode`, beside the social `enroll()` and claiming the same
  in-flight flag, so a device-code sign-in and a social one cannot both be registering a
  key. It parks a key with `auth.device.start`, hands the `userCode` and its expiry to a
  callback exactly once, and then polls `auth.device.poll` on the interval the server
  named — no client default, no backoff. `pending` waits and asks again; a rate limit and
  a lost response ask again after the same interval; every other refusal ends the wait.
  The deadline is the `expiresAtMillis` the server named, judged on the
  `core.time`-synchronised proof clock rather than the device's wall clock. Every exit
  that is not an approval destroys the parked key, cancellation included, and the install
  stays `unenrolled` throughout: there is no fourth lifecycle state and nothing to resume.
- **The approver gets no wrapper.** `auth.device.info`, `approve` and `deny` are reached
  through the generated descriptors and `execute`, as `auth.keys.revoke` already is. The
  README shows the three calls per platform.
- The answer is a new result type carrying the `userId` the approval bound the key to, the
  key this SDK parked, and `passwordChangeRequired`.
- **A codegen defect the flow was the first to reach:** an optional boolean was emitted
  with the required reader, so a `pending` poll — which carries no `passwordChangeRequired`
  — could not be decoded at all. Both emitters now emit an optional reader for an optional
  boolean, `SPFNDecoding.optionalBoolean` / `SpfnDecoding.optionalBoolean` exist, and the
  three affected generated fields (`PollDeviceAuthResponse.passwordChangeRequired`,
  `ListKeysRequest.includeRevoked`, `RevokeAllKeysRequest.includeCurrent`) are regenerated.
- **The reference server implements the flow**: the five operations and the upstream
  six-state × four-operation table verbatim, with expiry judged on its own clock, the
  approver read from the admitted proof's `clientId` and never from a body, and `deny`
  answering 204 with an empty body through the generic no-response path rather than a
  special case on the operation id. Twenty-five tests, one per cell plus the unproven
  approval admission refuses.
- The integration matrix gains cases g–k on both platforms — approval, denial over the
  bodyless operation, a code that expires, a second approval, and an unproven approval —
  and `Contracts/fixtures/enrollment/enrollment.json` gains the `start` body each platform
  must send, derived from the contract rather than captured from either SDK.
- **The real-server suite gains four of those five**, r6–r9: an approval, a denial over
  the bodyless operation, a second approval of one code, and an approval nobody proved.
  They are the reference cases g, h, j and k run against the published `@spfn/auth` on a
  real PostgreSQL, so the flow is now proven against a server this repository did not
  write. Case i has no twin — an expiry is judged by moving a clock, a real server has
  none to move, and sitting out the ten-minute TTL would be a hang rather than a case.
  `tools/verify-server/run.sh` requires their receipts like the other five.
- **The four cells share one approver**, enrolled once by whichever cell asks first. The
  seeded account's rate limit allows ten `/_auth/login` calls a minute and r1–r5 already
  spend six; a device-code enrolment spends none, so the whole device-code half of the
  suite costs one login. The keys the cells add are revoked when the class ends, after the
  receipts are written, so a repeated run does not accumulate keys on the seeded account.
- The unproven approval is asserted as "refused and applied nothing" rather than by code.
  This deployment answers 401 `UnauthorizedError`, which the mobile contract does not
  list, where the reference server answers `CONTRACT_UNSUPPORTED`; both are refusals, and
  pinning either would pin a real server to one of two defensible answers.

### The pinned contract moves to 0.10.0, and an operation may declare no response

- `Contracts/spfn-mobile-contract.json` and `Contracts/upstream-provenance.json` are byte
  copies of SPFN primitives commit `77fe6246` (the release commit for `@spfn/core`
  `0.3.0-beta.6`, `@spfn/auth` `0.3.0-beta.8`, exporter
  `@spfn/auth/contract-bundle@6.0.0`). The lock names that commit, the digest is
  `29c26160…`, and the admitted window is `>=0.10.0 <0.11.0` — identical to the range the
  upstream evidence declares, because 0.10.0 is its minor's first release.
- Contract 0.10.0 adds the device-code flow: five operations (`auth.device.start`,
  `auth.device.poll`, `auth.device.info`, `auth.device.approve`, `auth.device.deny`,
  bringing the total to 16), eight types (31), the `DeviceAuthPollStatus` enum, the four
  `DeviceAuth*Error` codes (22), and a `deviceAuthorization` section describing the flow.
  `KeySummary.platform` changes from a bare string to the new `KeyPlatform` enum, which
  both generated clients now decode strictly. This change set pins and generates the
  contract; it does not implement device sign-in in the SDK or in the reference server.
- **An operation may declare no `responseType`.** `restOperations.responseBody` states
  what that means — "An operation that declares no responseType answers 204 with an empty
  body and there is nothing to decode" — and `auth.device.deny` is the first one.
  `tools/contract-codegen` used to require the key; it now reads its absence as the
  declared fact, refuses a present-but-unknown response type as before, and refuses a
  bundle whose clock operation lost its response type. Every generated descriptor carries
  `declaresResponse`, and both execute paths read that field rather than the operation id:
  a bodyless operation must answer 204 with an empty body, a body on one or a 2xx that is
  not 204 is refused by name, and an operation that does declare a response still refuses
  an empty 204 exactly as it did before.
- The device sign-in evidence is retired. `tools/device-receipts/receipt-gate.sh` judges a
  receipt against the pinned contract version; the 2026-09-01 receipts name `0.9.0`, so the
  gate now refuses them and `tools/rc-verify/rc-verify.sh` refuses every candidate. The
  receipts stay committed as history. A fresh fifteen-cell run against a `0.3.0-beta.8`
  server is required before publication, and it is owned by a person with two phones.

### Publication now requires evidence that a person held a phone

- A device session on 2026-09-01 drove all fifteen social sign-in cells — {iOS × Apple,
  iOS × Google, Android × Google} × {first-enroll, re-login, user-cancel,
  network-failure, server-reject} — by hand on real phones against a real SPFN server.
  The 27 receipts it produced are committed under `tools/device-receipts/runs/2026-09-01/`,
  retries and mis-declared attempts included: the record is what happened, not a
  selection from it.
- `tools/device-receipts/receipt-gate.sh` turns those files into a refusal. Each cell
  needs a receipt whose recorded outcome matches the case table and whose contract
  version is the one this repository pins, so re-pinning the contract retires the
  evidence and demands a fresh device run. `tools/rc-verify/rc-verify.sh` runs it first
  and will not verify a candidate without it; the two environment overrides the gate
  offers its own probe are cleared on that path, so no shell variable can point it at
  hand-written evidence.
- `tools/device-receipts/probe-receipt-gate.sh` proves the gate bites in each of the
  fifteen ways its evidence can be absent, unreadable, malformed, mis-named, carrying an
  identifier or token it must never carry, or simply wrong — and asserts no two of those
  refusals produce the same sentence, which is the difference between "the gate could
  not look" and "the gate looked and found nothing wrong". `tools/validate/validate.sh`
  runs both the probe and the gate on every run.
- COMPATIBILITY.md gains a device sign-in row, resolved for contract `0.9.0`. The iOS
  and Android rows stay UNRESOLVED: their gates are whole platforms, and a proven
  sign-in path is narrower than that.

### Decimal fields are emitted, and encoding is where an impossible value fails

- The generator now emits `decimal<scale>` fields as Swift `Decimal` and Kotlin
  `BigDecimal`, wired through `SPFNDecimalCoding`/`SpfnDecimalCoding` in the core
  modules. The wire form is #95's scaled integer — `decimal<2>` carries 1999 for 19.99 —
  and the canonical value model stays integer-only: nothing about the wire, canonical
  JSON or the proof input changed.
- A value finer than the declared scale is refused at encoding time, never rounded, and
  so is a value whose scaled integer leaves the Int64 range. The refusal happens before
  the proof is signed and before a byte leaves the device: generated Swift encoding
  became `func canonicalValue() throws` (Kotlin's was already a function; its exceptions
  are unchecked), so an impossible value fails the call that tried to encode it. A
  shared case table holds both platforms to the same vectors, row for row.
- The pinned contract declares no decimal field yet, so the only visible change in the
  generated sources is the throwing Swift boundary. The first bundle that ships one now
  generates working clients instead of stopping the build.
- Android lint caught `BigInteger.longValueExact` at API 31 against minSdk 24 — the P14
  trap, replaced with an explicit bounds comparison. The desktop JVM tests had passed.

### The contract is pinned at 0.8.0, and the generator reads the decimal grammar

- The pin moves from 0.6.0 to 0.8.0 at primitives `22a1abea`, the commit published to
  npmjs as `@spfn/auth@0.2.0-beta.91` and `@spfn/core@0.2.0-beta.71`. Three contract
  versions landed upstream in between: 0.6.1 put `since`/`deprecatedIn`/`removedIn` on
  every operation, 0.7.0 removed `number` from the grammar and added `decimal<scale>`,
  and 0.8.0 removed the envelope's decoder instructions (`unknownCodePolicy`,
  `unknownCodeRule`) in favour of the fact behind them, `unlistedCodes`.
- The generator parses `decimal<scale>` and bounds-checks the scale (1..18), and still
  refuses to emit it: no field in this contract uses one, and the encode-time rejection
  path into the integer-only canonical value model — reject, never round, per the #95
  decision — is a design decision to make before the first emission, not a line to add
  to an emitter. A malformed scale (`decimal<2x>`) is refused at parse rather than read
  as a type name, which is the P8 failure shape.
- `number` is still refused, and the refusal now names its replacement: the grammar
  dropped the spelling in 0.7.0, and `decimal<scale>` is how a fractional value is
  declared. The availability keys are read past, and a test holds the parser to that.
- The SDKs' own decoding behaviour does not change with the envelope: neither SDK ever
  read `unknownCodePolicy` from the bundle — an unknown code already surfaced as an
  unknown-code failure carrying the raw string, which remains this decoder's own
  decision now that the contract states none.

### The contract is pinned at 0.6.0, and the generator now refuses what it cannot emit

- The pin moves from 0.4.1 to 0.6.0 at primitives `8c95d1b2`, the commit published to
  npmjs as `@spfn/auth@0.2.0-beta.90` and `@spfn/core@0.2.0-beta.70`. Two breaking minors
  landed upstream in between: 0.5.0 added enums, a floating-point scalar, a map spelling
  and one date convention, and 0.6.0 put the contract version on both wires.
- `Contracts/upstream.lock.json` gained `publishedPackages`. The lock names a commit,
  which npm cannot install, and nothing in an installed `@spfn/auth` says which contract
  it implements — the bundle is not in the package and neither is the code that builds
  it. That mapping now exists in one written-down place instead of nowhere.
- The registry is named too, because there are two. npmjs and the Gitea registry carry
  `@spfn` at different versions, and `--registry` does not override an `@spfn:registry`
  npmrc scope — only `--@spfn:registry` does. `tools/verify-server/spfn-versions.sh` asks
  both and exits non-zero when they disagree; it has no mode that answers with one number,
  because a single number with no registry beside it is the shape of the mistake.
- The generator refuses a field type it cannot emit, at generation time. It used to read
  every unrecognised spelling as a struct reference, so 0.6.0's `KeyAlgorithm` became a
  reference to a type nothing declared and both SDKs emitted code that would not compile.
  The contract's own grammar predicted it: "a consumer that does not recognise a container
  spelling reads it as a type name and fails at compile time." Now an undeclared name, a
  `number`, a map, a name declared as both type and enum, and two enum values that
  generate one case name each stop generation with the field named.
- `number` is refused rather than implemented. SPFN-CANON-JSON-1 carries signed 64-bit
  integers only — "a fractional or non-finite number is a canonicalization error" — so a
  float could not be signed at all. The grammar is shared with app contracts, which is why
  it names a spelling this contract's own encoding refuses.
- Error codes carry their surface. The contract declares six `clientProofV1` refusals and
  twelve `rest` ones in a single array, and they are not interchangeable: a proven call
  can meet the first set and never the second. `isAuthFailure` is false for every `rest`
  code because a re-handshake re-establishes a session those operations never open — and
  a test asserts that by surface, not by list, so a rate limit cannot be routed into the
  re-handshake path and rate-limited again.
- Case names are readable on both surfaces. The name generator split on underscores and
  lowercased each part, which is right for `PROOF_INVALID` and turned
  `NonceKeyBindingError` into `noncekeybindingerror`. A part that already carries
  lowercase now keeps its shape.
- The error fixture is derived from the bundle instead of restated beside it. It carried
  six codes against a contract that declares eighteen, and the conformance gate failed for
  a reason that had nothing to do with either SDK. The canonicalization and the signatures
  stay independently derived; the set of codes was never something to derive.

### A runner for the real server, which refuses far more than it runs

- `tools/verify-server/run.sh` points the SDK at a scaffolded SPFN app — the published
  `@spfn/auth` on a real PostgreSQL — rather than at `tools/reference-server`. The
  reference server is two ends built from one reading of the contract, so agreeing with
  it cannot catch a contract this repository reads correctly and no SPFN server
  implements. The app lives outside this repository at `workspaces/spfn-verify-app`,
  overridable with `SPFN_VERIFY_APP`, for the reasons decision `01kz6nq4ga` records.
- Every way the setup can be wrong is an exit, never a substitution. Falling back to the
  reference server would report real-server coverage the run did not have, which is worse
  than no run at all. The version comparison is equality rather than a floor: a newer
  package may serve a contract this SDK was not generated from, and the whole point is
  that both ends read the same one.
- The runner fails closed on a pin it cannot compare. `Contracts/upstream.lock.json`
  names a primitives commit, which npm cannot install, so the comparison needs the
  published versions recorded alongside it. Until that field exists the runner exits
  rather than treat an uncomparable pin as a matching one.
- The database password is never printed. `DATABASE_URL` is read into a variable and only
  its host and port reach the output, which is what a reader needs in order to act.
- `tools/verify-server/probe-refusals.sh` proves each refusal bites, asserting the reason
  and not only the exit code — a guard that fires for the wrong reason passes an
  exit-code check while protecting nothing. It also proves a correct setup still passes,
  without which a runner that refused everything would satisfy the probe while blocking
  every real run. `tools/validate/validate.sh` runs it, because these refusals fire only
  when a setup is already wrong, which is exactly when nobody is watching them.

### Native social sign-in has an SDK surface, and the nonce is no longer a string

- `SPFNSocialApple` and `SPFNSocialGoogle` / `spfn-social-google` are the first modules
  added under the five module rules. Each carries an implementation, each drags in a
  provider dependency most consumers will not link, and each closes exactly one gap:
  obtaining a provider token on the device. Key generation, the registration request and
  the account are owned by `SPFNKeyLifecycle.enroll` and by the server, and none of it
  was reimplemented.
- The Apple adapter is iOS-only, and the module graph can now say so: `androidModule` is
  either a name or the literal `null`. Apple ships no native sign-in SDK for Android, so
  the Android half would have owned a one-line nonce accessor and a seam the app fills
  in anyway, while App Store guideline 4.8 requires the button on iOS alone. An Android
  app signing in with Apple is unaffected — `SpfnSocialNonce`, its `requestValue` and
  `enroll(provider = "apple", …)` are in `spfn-client` and always were.
- `null` is a declaration, not an omission, and the validator refuses to confuse the two:
  every module line is bucketed into Android-backed or declared-iOS-only, the buckets
  must add up to the number of lines, and each platform is counted against its own floor.
  A skip that also covers a line nobody could parse is how a graph check reports green
  having read nothing.
- The nonce is the fingerprint of the key being enrolled, not a random value. Contract
  0.4.1's `nativeEnrollment.nonceRule` requires the body's `nonce` and `fingerprint` to be
  the same string, and the server refuses the call when they differ. An id_token is
  bearer-shaped, so verifying it alone would let whoever stole one register their own key
  on the victim's account; deriving the nonce from the key means a stolen token carries
  the victim's fingerprint and pairs with nothing else.
- `enroll` takes a sign-in closure instead of a token, and generates the key, runs the
  provider flow and registers the result in one call. The key has to exist before the
  provider is asked — that is what the nonce is derived from — so a sign-in the user
  abandons would otherwise strand a key nobody registered. Owning the whole flow is what
  lets the SDK destroy it: a Keystore entry to delete on Android, a value to drop on iOS.
- A second `enroll` while the first one's sign-in is up is refused with a new
  `enrollmentInFlight` / `EnrollmentInFlight`. The state checks cannot see it — an
  enrollment in progress has saved nothing, so both calls read unenrolled. The Android
  half no longer holds its mutex across the sign-in either, which also removes a deadlock
  for a closure that calls back into the lifecycle.
- `SPFNSocialNonce` / `SpfnSocialNonce` publishes one value, `requestValue`: the SHA-256
  of the fingerprint when the nonce was minted for Apple, and the fingerprint itself for
  every other provider. `make()` and `appleRequestValue` are gone. One value means an app
  cannot pick the wrong shape, and it stays public because an app driving kakao or naver
  through their own SDKs needs it. Each adapter refuses a nonce minted for another
  provider.
- The fingerprint is lowercase hex and deliberately not base64. A base64url value's last
  character carries fewer than six bits, and Naver hands back a different trailing
  character than it was given (primitives #57).
- The reference server now refuses an enrollment whose nonce is not the fingerprint, and
  the enrollment fixture carries the fingerprint as its nonce. A fake server more
  permissive than the real one is the one way a fake server does harm.
- The Android adapter runs on Credential Manager — `androidx.credentials` 1.6.0,
  `credentials-play-services-auth` 1.6.0 and `googleid` 1.2.0 — rather than on the
  deprecated one-tap surface in `play-services-auth`. It was written on the deprecated
  API first, behind two suppressions, and moved before anything shipped; a suppression
  is what keeps a retired API out of a build log, so `validate.sh` refuses one anywhere
  in SDK sources and the probe proves the refusal bites. The nonce still rides raw, now
  in `GetGoogleIdOption`, and a dismissal is still told apart from a failure, now by
  `GetCredentialCancellationException` rather than by a numeric status code.
- The manifest baseline moved to swift-tools-version 6.1 (D5 revision 3b) for package
  traits. `SocialApple` and `SocialGoogle` are declared, neither is on by default, and a
  trait-off consumer resolves nothing: a cold build creates no `Package.resolved` and no
  checkout, which is now what the trait-off build is expected to prove.
- `tools/module-graph.json` gained `swiftTrait` and `externalDeps`, and the validator's
  "zero external dependencies" rule became an allowlist read from the graph, checked in
  both directions on both platforms — an undeclared dependency fails, and so does an
  allowance nothing uses. Zero was never the property worth keeping; reviewed was.
- The interactive-browser vocabulary ban is narrowed to a named exception rather than
  loosened: only inside the two adapter module trees, and only for the provider-token
  words. Redirect and PKCE vocabulary stays refused everywhere, adapters included.
- Kakao and Naver adapters are not here. The server has no `verifyNativeIdToken` for
  them (primitives #56, #57), and a module is added with an implementation or not at all.
- A cancelled task now reads as a cancellation on both platforms. Both adapters caught
  everything and classified it, so cancelling the caller — `CancellationException` in
  Kotlin, `CancellationError` in Swift — was reported as a sign-in the provider refused,
  while the caller's own scope believed it had never been cancelled. Apple's session also
  had no cancellation handler at all: the sheet's dismissal arrives as a delegate
  callback, but a cancelled task arrives as nothing, and the continuation stayed
  suspended for the life of the process. It now resumes and takes the sheet down.
- Nothing outside `spfn-client` mints a `SpfnSocialNonce`. Its constructor and its
  `fingerprint` are `internal`, which Kotlin's name mangling carries across to Java, and
  the adapter modules reach a gated factory instead — `internal` stops at the Gradle
  module where Swift's `package` spans the whole package. What this guards changed with
  the nonce itself: the risk is no longer an app reading a value, it is an app minting a
  nonce for a key it does not hold, which the server can only refuse.
- An enrollment the server accepted and the device cannot record no longer leaves a
  Keystore alias behind. Persisting moved inside the same guard as the request, so a
  store that throws destroys the key rather than orphaning an alias the retry cannot
  find. This is Android-only by nature — on iOS a failed enrollment drops a value.
- `SpfnSocialGoogleCredentialDriver` takes an `Activity` rather than a `Context`.
  `getCredential` puts an account picker on the screen and Android asks for an activity
  to present it from; a `Context` parameter compiled against an application context and
  failed at the one moment a user was watching.

### The empty modules are gone, and the rule that made them is written down

- `SPFNPersistence` / `spfn-sync` and `SPFNHybrid` / `spfn-hybrid` are dropped. Both were
  declared in the Step 1 scaffold from the approved layout, never implemented, and
  published as empty coordinates through `0.1.0-alpha.3` — 21 and 30 lines of Swift whose
  only entry point threw. A published coordinate reads as a promise, and there was
  nothing behind these two. `0.1.0-alpha.3` keeps them because published versions are
  immutable; the next release does not.
- The removal settles D10 by deleting both names. The `SPFNPersistence` / `spfn-sync`
  asymmetry was never a naming oversight: one side promised storage and the other
  promised synchronization, because nothing had decided which the module was.
- The hybrid guarantee got stronger by losing its module. "No bridge exists" was proven
  by an empty allowlist literal, which one edit widens. It is now proven by refusing
  WebView and JavaScript-bridge vocabulary anywhere in the surface, which cannot be
  widened without adding a module.
- `SPFNScaffoldError` and `SpfnNotImplementedInScaffoldException` are gone with their
  only callers. A new check refuses unimplemented-entry-point vocabulary in SDK sources
  outright, so a stub module cannot come back the way this one arrived.
- The in-band disclaimer was lying. It claimed nothing had been committed, tagged or
  published and that the transport did not exist, months after all four were false. It
  now names what exists, what does not exist at all, and that no device evidence exists.
- `docs/architecture/README.md` records the five module rules this settles: implementation
  before module, injected protocol before module, one release train, one place where a
  module is named, and contract before any module that needs a server round trip.
- The module graph is the only place a module is named. The podspec generator already
  derived from it; `rc-verify.sh` and `verify-published.sh` now do too, so their consumer
  dependency lists, imports and Maven coordinates cannot drift from the train.

## 0.1.0-alpha.3 — published 2026-08-04

Published to Maven Central (deployment `b7ae0261`) with the matching SwiftPM tag at
source commit `70781e4`. Verified from the published coordinates alone by
`tools/rc-verify/verify-published.sh`: all six modules on repo1.maven.org with
matching sha256 sidecars and valid PGP signatures, an Android consumer compiled
against `mavenCentral()` with a refreshed cache, and a SwiftPM consumer resolving
the public tag to that same commit and running its smoke executable.

`0.1.0-alpha.2` was published before the asymmetric clientProofV1 revision below:
it signs proofs with the retired symmetric HMAC profile and cannot authenticate
against a server on contract `0.2.0` or later. Published versions are immutable
here, so the train reissues as `0.1.0-alpha.3` from a tree that carries the
asymmetric profile and the key lifecycle.

### Contract 0.3.0: the REST enrollment surface, custody, and the key lifecycle

- The pin moved to primitives commit `7e727310`, contract `0.3.0`, digest
  `a41a3c06…`: four REST operations under `/_auth` (register, login, native social
  enrollment, key rotation), the `operationAuthClasses` section that declares the
  unproven class, `keyPolicy` (ttlDays 90, rotation via `auth.keys.rotate`), the
  `restOperations` wire rules and `clientProofV1.clientIdRule` — clientId now
  identifies the key owner on the REST surface, refused as the non-disclosing
  PROOF_INVALID otherwise. The generator requires every new section and refuses a
  bundle missing one; the auth classes are generated as a type on both platforms.
- The execute path resolves an operation's auth class before sending. The unproven
  class goes out with the content type alone — no proof, identity, nonce or session
  header, no handshake, no retry — and an operation naming an undeclared class is
  refused unsent (fail-closed). Proven session-free operations (rotation) carry
  every proof header and never a session header.
- Hardware custody landed behind seams: `SPFNCustodyKey` generates inside the
  Secure Enclave when available and records a software-keychain fallback otherwise
  (device-only accessibility, no synchronization); `SpfnKeystoreCustodyKey`
  generates EC P-256 in the Android Keystore, StrongBox first with the TEE fallback
  recorded. Enclave/StrongBox runtime behaviour is real-device evidence, deferred
  with the COMPATIBILITY axis; the software halves and every selection, persistence
  and wipe rule are unit-tested on both platforms.
- `SPFNKeyLifecycle` / `SpfnKeyLifecycle` own enrollment and rotation with exactly
  one signable key at every observable moment: enroll generates, sends the exact
  contract body (SPKI DER base64, SHA-256-of-SPKI fingerprint, ES256), persists the
  issued userId as the proof clientId, and destroys the key on failure; rotate
  persists the candidate before the network call, swaps only on confirmation, and
  resumes an interrupted rotation deterministically. SESSION_REVOKED wipes; the TTL
  judgment is foreground arithmetic over the generated keyPolicy constants.
- The reference server mirrors the surface: a fixed-rule test idToken enrolls a key
  under its owner, rotation replaces a key and drops its sessions, ownership is
  refused inside the proof step indistinguishably from every other proof failure,
  and the integration matrix gained the enrollment→proof→rotate→new-key-proof end
  to end on both platforms (local mode; an external primitives dev target carries
  the three dev operations only and case f is out of scope there).

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

## 0.1.0-alpha.2 — published 2026-08-03, superseded by 0.1.0-alpha.3

Published to Maven Central (deployment `d0ca11b5`) with the matching SwiftPM tag.
This is the last release on the symmetric HMAC clientProofV1: it does not
authenticate against a server on contract `0.2.0` or later.

The candidate version moved, not the code. `0.1.0-alpha.1` exists as a public tag and
was never published to Central: its dispatch failed because the candidate commit it
names predates the cold-cache verification-metadata fix (#14), so a build of that
exact tree cannot pass dependency verification on a cold runner. Published versions —
and their tags — are immutable here, so the tag stays where it is and the candidate
is reissued as `0.1.0-alpha.2` from a tree that contains the fix. Everything below
the next heading describes work that happened while the train carried the alpha.1
number.

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
