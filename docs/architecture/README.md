# Architecture

What this repository actually implements, as of `main`. The approved design lives in the
topology decision artifact
`2026-07-27-spfn-mobile-sdk-repository-topology-decision.html`; where the two differ, the
tree is right and this page follows it.

Six Swift modules and five Android ones, one release train, `clientProofV1` as the only
auth profile,
and a contract imported from SPFN primitives by digest. The vertical slice is complete:
canonical serialization, proof assembly on P-256 ECDSA, hardware key custody, the key
lifecycle, a transport, a session and one execute path, with generated clients on both
platforms. Hardware custody has been probed on one iPhone and one Android phone, and two
corrected Android runs completed seven or eight of the nine lifecycle cells. Neither
platform has met the whole device gate, so every support row in `COMPATIBILITY.md`
remains UNRESOLVED.

## Module graph

`tools/module-graph.json` is the single source of truth. Four representations must
agree with it, and the validator checks all four:

| Representation | File |
| --- | --- |
| SwiftPM targets and products | `Package.swift` |
| Gradle projects | `settings.gradle.kts`, `android/*/build.gradle.kts` |
| CocoaPods fixture subspecs | `tools/cocoapods-compat/generated/…podspec` |
| Source directories | `Sources/*`, `android/*/src/main/kotlin` |

Three more read it rather than restate it, so they cannot drift at all: the podspec
fixture generator, `tools/rc-verify/rc-verify.sh` and
`tools/rc-verify/verify-published.sh` derive their consumer dependency lists, imports
and Maven coordinate lists from the graph.

| Swift target | Android module | Linux | Depends on | External |
| --- | --- | --- | --- | --- |
| `SPFNCore` | `spfn-core` | yes | — | swift-crypto (Linux) |
| `SPFNGenerated` | `spfn-generated` | yes | core | — |
| `SPFNAuth` | `spfn-auth` | yes | core | swift-crypto (Linux) |
| `SPFNClient` | `spfn-client` | yes | core, auth, generated | swift-crypto (Linux), OkHttp, coroutines (Android) |
| `SPFNUI` | `spfn-ui` | yes | core | Compose ×3, Navigation 3 ×2, coroutines (Android) |
| `SPFNSocialApple` | — (declared absent) | — (declared absent) | client | — |
| `SPFNSocialGoogle` | `spfn-social-google` | — (declared absent) | client | GoogleSignIn (trait), Credential Manager ×3 |

`SPFNCall` / `SpfnCall` — one operation paired with the codecs for its request and
response types — is in core, not in the client. The type depends on an operation, a
canonical value and the unit answer a bodyless operation gives back, all three of which
core owns; putting it there is what lets the generated module, which depends on core
alone, hold one descriptor per operation in `SPFNGeneratedCalls` / `SpfnGeneratedCalls`.
The client stays generic over request and response and knows no operation at all.

The graph gained two keys with the provider adapters. `swiftTrait` names the SwiftPM
trait a module's external dependency hangs off, and `externalDeps` is the allowlist the
validator holds both manifests to — in both directions, so an undeclared dependency and
an unused allowance each fail. The rule this replaced was "zero external dependencies",
which was true until an adapter needed the provider's own SDK; zero was never the
property worth keeping, reviewed was.

It also stopped assuming a module has both platforms. `androidModule` is either a name
or the literal `null`, and `null` means declared absent rather than not yet written.
`SPFNSocialApple` is the first: Apple ships no native sign-in SDK for Android, an
Android half would have owned a one-line nonce accessor and a seam the app fills in
anyway, and an Android app signing in with Apple needs only what `spfn-client` already
gives it — `SpfnSocialNonce`, its `requestValue` and `enroll(provider = "apple", …)`.

`null` is a declaration, and every reader has to tell it apart from a key it could not
read: the first is a decision, the second is a broken parse, and a reader that treats
both as "skip" reports a clean graph having read nothing. So the validator buckets every
module line into Android-backed or declared-iOS-only, fails unless the buckets add up to
the number of lines, and counts the two platforms against separate floors — one number
covering both would pass with an entire platform at zero.

schemaVersion 4 said the same thing about the Swift side, in the shape the Swift side
needs. `linux` is either ABSENT — the module builds on Linux — or the literal `false`,
which means declared absent. There is no name to carry the way `androidModule` does,
because there is one Swift target either way; what differs is whether that target has
anything in it on Linux. `SPFNSocialApple` and `SPFNSocialGoogle` are the two: one is
written against AuthenticationServices, the other against UIKit and AppKit, and neither
framework exists off Apple's platforms.

SwiftPM cannot condition a *target* on a platform — only a target's dependency edge —
so `linux: false` is not something `Package.swift` can state. What makes it true in the
build is in the sources: every source and test file of such a module is guarded whole,
`#if canImport(…)` as its first line of code and `#endif` as its last, so the target
compiles to an empty module on Linux. The validator reads that file by file and checks
both ends, because a guard closed early still *looks* guarded while whatever trails it
compiles anyway. It checks the other direction too: a module without the key may not
import AuthenticationServices, UIKit, AppKit, LocalAuthentication or Security outside a
`canImport` guard, and no file anywhere may import CryptoKit outside one. `Package.swift`
carries a comment saying where the mechanism lives; the comment is not the source of
truth, the graph is.

The three-state rule is the same one `androidModule` follows and it is there for the
same reason: absent, `false`, and a value the reader could not understand are three
different events. So the validator buckets every module line into Linux-capable or
declared-absent, fails unless the two add up to the number of lines, and fails a line
carrying `"linux":` followed by anything but `false` as unread rather than skipping it.

`swift-crypto` is the second external Swift package and the first that is not
trait-gated. CryptoKit is Apple's and ships with every platform the SDK supports;
Linux has none, and swift-crypto is Apple's own port of the same API, so core, auth and
client swap an import and nothing else. Every target edge to it carries
`.when(platforms: [.linux])`, so an iOS or macOS build never links it. SwiftPM still
*resolves* a platform-conditional dependency everywhere, so the lockfile names it on
macOS too; `Package.resolved` is untracked here, so none of that is committed.

A module appears here only once it carries an implementation. `SPFNPersistence` /
`spfn-sync` and `SPFNHybrid` / `spfn-hybrid` were declared in the Step 1 scaffold from
the approved layout, never implemented, and published as empty coordinates through
`0.1.0-alpha.3`; they were dropped rather than kept as reservations. That also settles
the `SPFNPersistence` / `spfn-sync` name asymmetry (D10) by removing both names. One
edge moved since Step 2: the client module
gained auth and generated when the session arrived, because a session signs a
`clientProofV1` proof over a generated operation and its generated request type. That is
the revision D13 left room for. The conformance suite needs the generated client from
inside the auth module, but only at test time; every main edge is still exactly what
`tools/module-graph.json` declares.

### The `ui` module

`ui` holds the UI runtime vocabulary, and it depends on core alone. What it carries is
four types a screen needs before it holds any screen: `Loadable`
(loading·ready·empty·error) for one read, `Busy` (idle·busy·error) for one write,
`FlowRoute`/`Flow` for a stack of routes with a presented flag, and `FlowHost`, the one
place a platform navigator is bound to that stack. `Loadable`'s and `Busy`'s error states
carry core's own error envelope, which is the whole reason for the edge; nothing here
needs a transport, a session or a generated operation, so an app that only renders state
links none of them.

`Flow` is deliberately free of the UI toolkit on both platforms — SwiftUI on one side,
Compose on the other — because every rule it holds is a rule about a list. That is what
lets the whole transition table run as an ordinary unit suite on a JVM and on Linux,
rather than only where a UI toolkit and a device exist. `FlowHost` is the only file in
either half that imports one.

`FlowHost` owns no stack. SwiftUI's `NavigationStack(path:)` and Compose's `NavDisplay`
both write back on a system pop, and both are bound so that the write turns into
`flow.pop()` rather than into a second copy of the stack. `@Environment(\.dismiss)` is
refused outright in `SPFNUI` and the validator's section 13 enforces it: it closes a
presentation without telling the flow, which is exactly how a host ends up dismissed over
a flow that still believes it is open.

The two platforms are asymmetric in `externalDeps` because they are asymmetric in fact.
SwiftUI and Observation are frameworks the OS ships, so SwiftPM resolves no package for
them and the Swift allowlist is empty; Compose and Navigation 3 are Maven artifacts like
any other, so every one of them is named in the graph and pinned in the version
catalogue. Accepting that cost — Compose's transitive set is what grew
`gradle/verification-metadata.xml` by 154 components — was part of approving the module.

`Paged` and `Form` are deferred. A paged read is more than a `Loadable` with a cursor
bolted on and a form is more than a `Busy` per field; neither has a shape this repository
has had to satisfy yet, and a module is added with behaviour or not at all — which is the
first of the five rules below.

## The example scaffold, and the generator that writes it

`ui` gives a screen its vocabulary; it does not say how an app is put together out of it.
`examples/` answers that with one worked flow — device approval — built the way a consumer
app should build one, and `tools/ui-codegen` writes the repetitive part of it from a spec
so the two platforms cannot drift into different shapes of the same screen.

**Three layers, and the top one is the only one that knows an operation exists.**

| Layer | Where | Knows about |
| --- | --- | --- |
| Services | `…/Generated/Services/`, `…/generated/services/` | one `SPFNCall` descriptor per method, and `client.execute` |
| Screen models | `…/Screens/`, `…/screens/` | a service protocol and a `Flow`; no client, no descriptor, no toolkit |
| Views | `…/Views/`, `…/views/` | a screen model, and nothing below it |

The rule with teeth is the first one. `SPFNGeneratedCalls` / `SpfnGeneratedCalls` may be
named in a generated service file and nowhere else under `examples/`, and section 14 of
`validate.sh` fails on any other file that names one. It is a rule an app breaks by
reaching for one convenient descriptor inside a view — nothing stops that at compile time,
the result still works, and the layering is gone. A screen model takes its service and its
flow through its initializer (D9), so the same model runs against a real client on a device
and against a fake on a JVM with no substitution machinery in between.

**The spec is the source, and it is small on purpose.** `examples/ui-spec/device-approval.json`
has exactly three top-level tables — `services`, `flows`, `screens` — and
`examples/ui-spec/SCHEMA.md` is what a consumer writes against. Five things are refused
rather than warned about: a `contract.manifestSha256` that is not the pinned bundle's, an
operation the contract does not declare, a `then` target outside its own flow, a `start`
that is not a screen of that flow, and a `call` naming a service method that does not
exist. Each of the five is a mistake whose natural symptom is a compile error inside a
generated file that nobody wrote, which is the worst place to read one.

A screen's state type is derived, not declared: `source: null` gives `Busy`, an object
response gives `Loadable` without `empty`, and a list response gives `Loadable` with
`empty`. The pinned bundle does not distinguish a list response from an object one, so
today every response is treated as an object and `SCHEMA.md` says so as a stated limit
rather than leaving the reader to infer it from output.

**The case table is derived from the rules, and the models from the spec.** They are two
derivations from different sources — `Rules.kt` on one side, the spec on the other — and
the example app's unit suite is where they meet, driving each model against the table's own
expectations. A table generated from the models it checks would prove only that the code
equals itself (P10). Eighteen cells cover the four refusals a model owns: an empty input is
refused before anything is sent, a second submit while busy is ignored, approve or deny
before the read is `ready` is ignored, and a response arriving after `close()` changes
nothing. Each cell declares its runner — `unit`, `maestro` or `both` — and section 14 fails
on a cell that does not have what it claims, so the table cannot advertise coverage nobody
wrote.

**Verification needs no server.** A launch fixture (`SPFN_UI_FIXTURE=<cell>`, an intent
extra on Android and a launch argument on iOS) installs a fake service seeded for one cell;
absent the flag the app builds its real client and no fake exists. Buttons carry the id
`<screen>.<action>` and every readout is the text `<name>=<value>`, so one Maestro flow per
cell drives both platforms.

## How a module is added

Five rules, confirmed 2026-08-04 after the empty persistence and hybrid coordinates were
dropped. They exist so the next capability arrives as behaviour rather than as a name.

**A module is added with an implementation or not at all.** No reservations, no stub
entry points that throw. `validate.sh` refuses unimplemented-entry-point vocabulary
(`notImplemented`, `TODO`, `plannedStep`, …) anywhere under `Sources` or
`android/*/src/main`; examples and tools are excluded, because a placeholder example is
honest about being one.

The provider adapters are the first modules added under these rules, and they are also
what tested the second one below: each drags in a provider SDK that most consumers will
not link, and on iOS a consumer that does not enable the module's trait does not
resolve, check out or link it at all.

**The default shape of an extension is an injected protocol, not a module.** A new
capability starts as a protocol plus a default implementation inside the module that
uses it — `SPFNKeyProvider`, `SPFNKeyStore`, `SPFNTransport` and `SPFNClock` all live in
`SPFNClient` for exactly this reason. A separate module is justified when it drags in a
heavy or optional dependency, or when most consumers demonstrably will not link it. That
is why `@spfn/storage` is its own package upstream: the AWS and GCS SDKs are optional.

**One release train stays one release train.** Every module shares the `VERSION` value
in lockstep (D9), so an untouched module still ships a new version. SwiftPM is what makes
this the simple choice — one repository is one package is one version, and per-module
versions would mean splitting the repository. Whether to split later is not decided in
advance: the condition would be a guess, and a documented condition reads as a promise.

**`tools/module-graph.json` is the only place a module is named.** Everything else
derives from it: the SwiftPM manifest and Gradle settings are checked against it, and the
podspec generator, `rc-verify.sh` and `verify-published.sh` read it directly. Adding a
module leaves exactly two hand edits — one symbol touch in each consumer smoke, since
only a person knows which symbol proves a module non-empty, and the CODEOWNERS sample
block.

**An extension that needs a server round trip waits for the contract.** Local persistence
with server sync and push both need operations that the pinned bundle does not declare,
so the module cannot exist before the operation does. Upstream is the same story from the
other side: `@spfn/notification` names `push` in its channel union with no channel behind
it.

## What v1 does not include

Three capabilities are out of scope, decided rather than postponed, and none of them
carries an activation condition — a condition written before the requirement is known
reads as a promise.

| Capability | Why it is out | Where it is recorded |
| --- | --- | --- |
| Local persistence and server sync | the contract declares no sync operation, and upstream `@spfn/storage` is server-side object storage that owns no device-local store | D25 |
| Push | `@spfn/notification` implements email, SMS and Slack; `push` is a name in its channel union with no channel behind it, so the server half does not exist either | D26 |
| Hybrid WebView bridge | the module was dropped rather than kept empty; the validator now refuses WebView and JavaScript-bridge vocabulary anywhere in the surface | D10, COMPATIBILITY Hybrid row |

Interactive-browser auth (`oidcPkceV1`) is a stronger prohibition than a scope decision:
`validate.sh` fails on redirect and PKCE vocabulary in the surface at all. The provider
adapters narrowed that check without weakening it: inside the two adapter module trees a
line may name the provider-token vocabulary (`id_token`, `oauth`, `openid`), because an
adapter that cannot say why Apple hashes the request nonce has to be re-derived by
everyone who reads it. Redirect and PKCE vocabulary stays refused there too.

Kakao and Naver adapters are not here for a reason that is not a decision at all: the
server has no `verifyNativeIdToken` for them yet (primitives #56, #57). A module is
added with an implementation or not at all, so they arrive when the server half does.

## Native social enrollment

`auth.enroll.oauthNative` and `SPFNKeyLifecycle.enroll` already owned everything except
one gap — obtaining a provider token on the device — and the adapter modules are that
gap and nothing else. Two things are worth knowing about the surface.

**The nonce is the key's fingerprint, and enrollment is one call.** The contract's
`nativeEnrollment.nonceRule` requires the enrollment body's nonce to equal its
fingerprint — the SHA-256 of the public key being registered. An id_token is
bearer-shaped, so a server that verified only the token would let whoever stole one
enroll their own key on the victim's account; deriving the nonce from the key means a
stolen token carries the victim's fingerprint and pairs with nothing else.

That ordering is why `enroll` takes a closure rather than a token: the key must exist
before the provider is asked, and a sign-in the user abandons would otherwise strand a
key nobody registered. The SDK owns the whole flow, so it can destroy it — an Android
Keystore entry to delete, an iOS value to drop.

`SPFNSocialNonce` carries the fingerprint and the provider it was minted for, and
publishes one value: `requestValue`, which is the SHA-256 of the fingerprint for Apple
and the fingerprint itself for everyone else. One value means an app has nothing to
choose between and so cannot choose wrong — and it stays public because an app driving
kakao or naver through their own SDKs needs it. Each adapter refuses a nonce minted for
another provider. Minting one is the lifecycle's job: the constructor is package-visible
in Swift, and in Kotlin — which has no package visibility — it is `internal` with an
opt-in-gated factory the sibling adapter modules use.

**The Android side is Credential Manager, not play-services-auth.** Google deprecated
the one-tap sign-in surface, and the adapter was written on it once before being moved:
`androidx.credentials` is the API, `credentials-play-services-auth` is the provider
behind it, and `googleid` carries the request option that holds the nonce and the
credential that comes back. New code on a retired API buys nothing and schedules the
same migration for a worse moment, so `validate.sh` now refuses a deprecation
suppression anywhere in SDK sources — the point being that a suppression is precisely
what keeps this out of a build log, so a build log cannot be what catches it.

**The Apple adapter is iOS-only.** App Store guideline 4.8 requires Sign in with Apple
on iOS and nothing requires it on Android, where Apple's own flow is a web one an app
runs itself. What that app hands the SDK is the same thing every other provider hands
it: a provider token and the nonce it was bound to.

**The raw value is not base64.** A base64url value's last character carries fewer than
six bits, and a provider that re-encodes it can hand back a different last character
than it was given — measured against Naver, which drops a trailing `A`. Hex has a fixed
meaning per position. The constraint is kept from the start because changing the shape
later would break flows that are already enrolled.

## Key custody and the key lifecycle

A `clientProofV1` proof is a P-256 ECDSA signature, so the private key is the whole
security story. It is generated on the device, never leaves it, and only the public key
reaches the server.

| Piece | Swift | What it settles |
| --- | --- | --- |
| Signer | `SPFNKeyProvider` | the injection point: anything that can sign over the proof input |
| Hardware signer | `SPFNSecureEnclaveKeyProvider` | Secure Enclave on iOS, Android Keystore on the other side |
| Persistence | `SPFNKeyStore`, `SPFNKeychainKeyStore` | where the key record lives between launches |
| Record | `SPFNStoredKey` | key id, owner, custody, generation time, custody blob |
| State machine | `SPFNKeyLifecycle` | enrollment, rotation, resumption, revocation, wipe |

Custody is recorded, not implied. `SPFNKeyCustody` is `secureEnclave` or
`softwareKeychain`, and the fallback is written into the record rather than hidden, so a
caller deciding what a key protects against reads an answer instead of inferring one from
which code path ran.

The lifecycle answers one question — what key does this install hold — with three states:
`unenrolled`, `enrolled`, `rotationPending`. Rotation is interruptible on purpose: the
device cannot know whether a rotation the network swallowed reached the server, so it
records that the outcome is unknown and `resumeRotation()` settles it, rather than
minting a second key and hoping. Key TTL comes from the bundle's `keyPolicy`, counted
from generation, which is the client-side moment closest to registration that survives a
restart.

Enrollment and rotation are contract operations, not SDK inventions: `auth.enroll.register`,
`auth.enroll.login`, `auth.enroll.oauthNative` and `auth.keys.rotate` are the upstream
`/_auth` surface, exported into the bundle. The three enrollment operations are the
contract's unproven class (`authProfile: none`) because they run before a key exists to
sign with, and the contract declares that rather than the SDK deciding it. Rotation is
not in that class: it is proven with the key being replaced, which is what makes it a
rotation rather than a second enrollment.

## Device-code sign-in

Contract 0.10.0's `deviceAuthorization` flow lets a device with no key on file park its
public key, show a short code, and be let in by somebody holding a device that is already
signed in. It lives where enrollment lives — one more entry point on `SPFNKeyLifecycle` /
`SpfnKeyLifecycle`, beside the social `enroll()` — because it is the same act: it ends
with one public key registered under one account and one record in the active slot. The
approver's three calls (`auth.device.info`, `approve`, `deny`) get no wrapper at all; an
app reaches them through the generated descriptors — `SPFNGeneratedCalls.authDeviceApprove`
and its two neighbours — and `execute`, the way it already reaches `auth.keys.revoke`.

**There is no fourth lifecycle state.** The parked key and the device code live in the
call's own frame for as long as the wait runs, and the install stays `unenrolled` until
the approval is saved — so a process death, a cancellation or any refusal leaves nothing
behind and nothing to resume. That is the difference between this and a rotation: a
rotation persists its candidate because the server may already know about it, and a
device code the server never approved is a record the server will expire on its own.
Adding a state would mean promising to resume a wait across a launch, which would mean
persisting a credential (the device code) that the flow is designed to hand out once.

The waiting device obeys the server and nothing else: it waits the `intervalMillis` the
`start` answer named and then whatever each `pending` names, with no client default and
no backoff, and it stops at the `expiresAtMillis` it was told — judged on the
`core.time`-synchronised proof clock, never the device's wall clock, so a device with a
wrong clock neither gives up early nor polls a code it was told is dead.

**A lost network answer is a lost poll wherever it happens in the wait.** Each iteration
makes two requests that can be dropped: the `core.time` fetch that anchors the proof clock
— a real request on a fresh install, where nothing has anchored it yet — and the poll
itself. Both cost the same interval and are asked again, because neither says anything
about the device code, and the deadline is judged when the clock finally answers. A clock
that refuses to synchronise at all is a different answer and ends the wait: an untrusted
base URL and a contract carrying no usable clock operation are the same on every retry,
so a device that retried them would poll until a deadline it can never read went past.

## Contract import model

SPFN primitives owns the canonical route DSL, schemas and the `clientProofV1` server
invariant. It exports an immutable, digest-pinned bundle. This repository imports that
bundle and generates Swift and Kotlin clients from it in one run.

**That export exists as of 2026-08-02.** Step 2 needed something to generate from, so it
authored a development bundle here and pinned its real digest; SPFN primitives has since
built the exporter, and the bundle is now a byte copy taken at an exact commit (D17,
resolved). `tools/validate/validate.sh` no longer refuses upstream claims — it checks them
against `Contracts/upstream-provenance.json`, the file the exporter wrote, field by
field, and refuses evidence naming this repository as the source.

There is deliberately no cross-repository atomic commit. The model is two-phase: the
producer releases an immutable compatible contract first, then the consumer imports it
by digest. Server and deployed SDKs are never replaced simultaneously in reality, so a
protocol that pretends otherwise would be lying about the operational model.

A breaking contract never breaks an existing mobile release. Primitives publishes a new
contract major with an overlap window, mobile adds support for it, and only then is
removing the old contract approved separately.

## Toolchain boundary

Node, pnpm and Turbo are not the root build system here. Contract codegen is a
non-published Kotlin/JVM tool inside the JDK and Gradle toolchain Android already
requires, so the repository does not acquire a second toolchain. It is registered as
`:contract-codegen` and excluded from every SDK-module check, because it is a build tool
rather than something anyone links against.

`tools/reference-server` is registered the same way, as `:reference-server`, and for the
same reason: it is a test fixture that implements the pinned contract so both SDKs can be
driven over real HTTP, not something anyone links against. Its `main` source set has zero
external dependencies — the HTTP layer is the JDK's own `com.sun.net.httpserver` — and it
compiles the canonical serializer, the proof input and the generated contract listing
straight out of the Android modules' source directories rather than reimplementing them.
An Android library cannot be a dependency of a JVM module, and a second copy of
SPFN-CANON-JSON-1 would turn the round trip into two copies agreeing with each other.

`:ui-codegen` and `:example-compose` join them, and are excluded the same way for two
different reasons. The generator is a build tool like `:contract-codegen`, sharing its
bundle readers rather than copying them. The Compose example is an Android *application*
— it is built and its screen models are tested, but it is not a library anyone links, so
the publication, lint and API checks that apply to `android/*` do not reach it. The
validator counts SDK modules under `android/` only, which is what keeps an app under
`examples/` from being read as an eleventh module.

One tool sits outside that boundary on purpose:
`Contracts/fixtures/derive-expected-values.py` derives the expected conformance values
using only the Python standard library. It is not part of any build, and its
independence from both SDKs is precisely what makes the fixtures evidence rather than a
restatement.

## Where the independence in the integration run comes from

The reference server shares source with the Android modules, so a round trip against it
cannot be evidence that the two ends agree about the contract — they were built from one
reading of it. Three things supply the independence instead, in ascending order of what
they are worth:

| Source | What it is independent of |
| --- | --- |
| `Contracts/fixtures/derive-expected-values.py` | both SDKs; it is a third implementation of the algorithms |
| the Swift integration suite | the server's codebase; it crosses the same wire from another language and another process |
| an external target given to `run-integration.sh` | this repository; the server is a reading of the contract nobody here wrote |

The third is why the runner takes `SPFN_INTEGRATION_TARGET_URL`, or a launch file, and
runs the same ten cases against a server it did not start. The one thing the mode must
never do is fall back to the local server when the target cannot be used: the run would
report the strongest evidence available here while producing the weakest. So every way of
naming a target that cannot be reached is a failure, in the runner and in the suite alike.

## Where the algorithms live

Canonical serialization (SPFN-CANON-JSON-1), digests and the proof input
(SPFN-PROOF-INPUT-1) are hand-written once per platform in `SPFNCore`/`spfn-core` and
`SPFNAuth`/`spfn-auth`. Generated code is a listing of the contract — types, operations,
error codes — and contains no logic. Reviewing a contract change is reading a diff of
names and types; reviewing an algorithm change is reading four files that rarely move.

The wire mapping is the one place a contract detail is restated by hand rather than
generated: the generator emits types and operations, and a session has to name an HTTP
header at compile time. `SPFNWireHeaders`/`SpfnWireHeaders` holds that table, and both
conformance suites read `wireMapping` out of the pinned bundle and fail if the table
drifts from it — so the restatement is checked rather than trusted.

## Three layers in the client module

| Layer | Knows | Does not know |
| --- | --- | --- |
| transport | how to send one HTTP request | what a 401 means, whether to retry |
| session | how to open a session and prove a request | what a server answer to an operation means |
| execute | what an answer means and what a refusal is worth retrying | how either of the two below works |

Each layer refuses the vocabulary of the one above it, and that is what makes the rules
checkable. The transport cannot retry, so an attempt count is exactly the number of calls
the layers above chose to make. The session does not classify, so every refusal is
classified in one place. And because `execute` is the only way to send a request, a rule
it states holds for every operation rather than for the ones somebody remembered.

Retry lives at the top for the same reason. Re-sending needs two things no lower layer
has: knowing that the request was refused rather than lost, and knowing what changed
since. An auth refusal is the only case where both are true, so it is the only case that
is retried, and it is retried once.
