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
platforms. Nothing has run on a device, so every support row in `COMPATIBILITY.md` is
UNRESOLVED.

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

| Swift target | Android module | Depends on | External |
| --- | --- | --- | --- |
| `SPFNCore` | `spfn-core` | — | — |
| `SPFNGenerated` | `spfn-generated` | core | — |
| `SPFNAuth` | `spfn-auth` | core | — |
| `SPFNClient` | `spfn-client` | core, auth, generated | OkHttp, coroutines (Android) |
| `SPFNSocialApple` | — (iOS only) | client | — |
| `SPFNSocialGoogle` | `spfn-social-google` | client | GoogleSignIn (trait), Credential Manager ×3 |

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
