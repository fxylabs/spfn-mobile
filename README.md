# SPFN Mobile

> **Step 2 scaffold with one vertical slice. This is not an SDK yet.**
>
> There is no transport, no persistence, no bridge, no release, no package on any
> registry, and **no public support** of any distribution channel is promised. Nothing
> here has been committed, pushed or published. The pinned contract bundle is an SPFN
> primitives export, and the round trip has been proved against a primitives dev server
> on localhost — never against a deployed service. Read
> [docs/SCAFFOLD-STATUS.md](docs/SCAFFOLD-STATUS.md) before assuming any capability
> exists.

SPFN Mobile is the polyglot repository for the SPFN iOS (Swift) and Android (Kotlin)
native SDKs and the optional hybrid WebView adapter. Both platforms share one release
train and one contract import, so the security model, proof canonicalization and error
model cannot drift apart between them.

## What exists right now

| Area | State |
| --- | --- |
| Swift module graph | 6 targets, building in Swift 6 language mode |
| Android module graph | 6 modules, compiling as AAR libraries |
| Contract | one bundle pinned by real SHA-256 — **exported by SPFN primitives**, copied here at an exact commit |
| Codegen | `tools/contract-codegen` produces both clients from that bundle, deterministically |
| `clientProofV1` | canonical proof input, SHA-256 digest, ECDSA P-256 proof over a registered public key, replay and revocation rules |
| Canonical JSON | SPFN-CANON-JSON-1, implemented independently on both platforms |
| Conformance | one fixture directory, two suites, cross-platform digest parity |
| Offline validator | working, and still the cheapest gate in the repository |
| CI | three required checks on every pull request — `swift`, `android`, `contract` — and three manual-only files for what D2 leaves outside |
| Release / publication | disabled by default, and no channel is configured |

## Layout

```
Package.swift            SwiftPM manifest (repo root, required by SwiftPM)
settings.gradle.kts      Android multi-project root
Contracts/               pinned contract bundle, lock, schemas, conformance fixtures
Sources/                 Swift targets: SPFNCore, SPFNGenerated, SPFNAuth,
                         SPFNClient
Tests/                   Swift unit, repository and conformance tests
android/                 Kotlin modules: spfn-core, spfn-generated, spfn-auth,
                         spfn-client
tools/                   module graph, offline validator, contract codegen,
                         CocoaPods fixture
examples/                reference apps (placeholders)
docs/                    architecture, migration, security, open decisions
```

## Distribution

Swift Package Manager is the only iOS distribution channel (D11, resolved). Android
modules are Gradle/Maven modules. **CocoaPods is not supported**; `tools/cocoapods-compat/`
holds an internal, unpublished fixture whose only job is to prove the module graph stays
single-sourced.

Android consumers must build with Kotlin 2.4 or later: the published AARs carry
Kotlin 2.4 metadata, which the Kotlin 2.2.x compiler bundled by an AGP 9 default
toolchain cannot read. See the Android row in `COMPATIBILITY.md`.

## Running the checks

```sh
sh tools/validate/validate.sh                        # offline structural validation
swift build && swift test                            # Swift targets and conformance
./gradlew build                                      # Android modules and conformance
./gradlew :contract-codegen:spfnCodegenVerify        # generated sources are up to date
./gradlew spfnToolchainReport                        # the effective D5 baseline
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
```

The Gradle commands need an Android SDK; point `ANDROID_HOME` at it. Each command is
separate evidence, and the validator never infers or reports the result of the others.

## Continuous integration

Three workflows gate a pull request, and **the checks to mark required are `swift`,
`android` and `contract`** — branch protection is a repository setting, so no file here
can turn them on.

Each one calls a script under `tools/ci/` and does nothing else, because a workflow file
cannot be run on a developer machine and a gate nobody can reproduce locally is a gate
nobody can fix:

```sh
sh tools/ci/validate.sh       # the offline validator, judged against tools/ci/validate-known-red.txt
sh tools/ci/android.sh        # unit tests, lint, codegen verification
sh tools/ci/swift.sh          # swift build --build-tests, swift test --skip-build
```

`tools/ci/validate.sh` is the one that needs explaining: the validator exits non-zero
whenever it counts any failure, and the device-receipt gate has been red on purpose since
the 2026-09-02 contract re-pin. So the script judges the validator's output against a
named list of admitted failures instead of obeying its exit code — and a failure that
stops happening reddens the check too, so the list cannot outlive its reason. See
[tools/ci/README.md](tools/ci/README.md).

What CI does not run is what D2 leaves outside it: the macOS half of the Swift suite, any
emulator or real device, the integration run (its `swift-e` cell is Mac-only), and
anything signed or published. `security.yml`, `release-candidate.yml` and
`publish-central.yml` stay manual for that reason.

## Signing a device in with a code

A device with no key on file shows a short code; somebody approves it on a device that is
already signed in. Both halves are below, and both are the whole of what an app writes.

**The waiting device** — one call, which returns when somebody answers:

```swift
let signedIn = try await lifecycle.enrollByDeviceCode(deviceName: "Kitchen iPad")
{ userCode, expiresAtMillis in show(userCode, until: expiresAtMillis) }
```

```kotlin
val signedIn = lifecycle.enrollByDeviceCode(deviceName = "Kitchen tablet")
{ userCode, expiresAtMillis -> show(userCode, expiresAtMillis) }
```

**The approver** — three generated descriptors through `execute`, with the key this device
already has. There is no SDK wrapper, the same way there is none for `auth.keys.revoke`:

```swift
let waiting = try await client.execute(SPFNGeneratedCalls.authDeviceInfo, request: SPFNDeviceAuthInfoRequest(userCode: typed))
_ = try await client.execute(SPFNGeneratedCalls.authDeviceApprove, request: SPFNApproveDeviceAuthRequest(userCode: typed))
_ = try await client.execute(SPFNGeneratedCalls.authDeviceDeny, request: SPFNDenyDeviceAuthRequest(userCode: typed))
```

```kotlin
val waiting = client.execute(SpfnGeneratedCalls.authDeviceInfo, SpfnDeviceAuthInfoRequest(typed))
client.execute(SpfnGeneratedCalls.authDeviceApprove, SpfnApproveDeviceAuthRequest(typed))
client.execute(SpfnGeneratedCalls.authDeviceDeny, SpfnDenyDeviceAuthRequest(typed))
```

`SPFNGeneratedCalls` / `SpfnGeneratedCalls` carries one descriptor per contract operation,
named exactly as the operation is. Nothing is written by hand.

What the app must show is the `userCode` exactly as it arrived — `XXXX-XXXX`, unmodified,
since the server folds case, spaces and dashes when it is typed back in — and that it
expires: the callback's second argument is the instant it stops working, and the call ends
by itself at that instant with the SDK's device-code expiry error. Only that expiry and a
real refusal end the wait — a request the network lost, whichever of the wait's two it was,
costs the interval and is asked again — so an app has no retry of its own to write. The
callback runs on
whatever executor the caller was on; the SDK switches to none of its own, so an app that
draws from it hops to its own main thread. The approver should be shown what `info`
answers — the device name and the fingerprint prefix — before approving, because that is
the whole defence against approving somebody else's device.

Every poll asks the server to hold it for up to 20 seconds while nobody has answered
(`waitMillis`, contract 0.13.1), and its transport deadline is that hold plus the ordinary
one. An approval therefore lands within a request's round trip rather than at the next
interval; a `pending` answer after a hold carries an interval of 0 and is asked again at
once, and everything else still waits the interval the server named.

## Signing in by a code another device shows

The same sign-in the other way round (contract 0.13.2's `deviceLink`): the device that is
already signed in shows a code, as text and as a QR its own app draws, and the new device
reads it. The new device then shows a two-digit number; the person taps that number among
three on the signed-in device, and the new device is in.

```swift
guard let code = SPFNLinkCode.parse(scannedOrTyped, allowedHosts: ["app.example.com"])
else { return showNotACode() }
let signedIn = try await lifecycle.enrollByLinkCode(code: code, deviceName: "Pocket phone")
{ matchNumber, expiresAtMillis in show(matchNumber, until: expiresAtMillis) }
```

```kotlin
val code = SpfnLinkCode.parse(scannedOrTyped, setOf("app.example.com")) ?: return showNotACode()
val signedIn = lifecycle.enrollByLinkCode(code, deviceName = "Pocket phone")
{ matchNumber, expiresAtMillis -> show(matchNumber, expiresAtMillis) }
```

`SPFNLinkCode.parse` / `SpfnLinkCode.parse` takes what a person typed (`abcd efgh`,
`ABCD-EFGH`) or what a camera read, and answers `XXXX-XXXX` or nothing. A URL is read only
when it is `https`, its host is in the set the app passes, and the code is its last path
segment, with no userinfo, port, query, fragment or percent-encoding; the full rule list
is on the function. `enrollByLinkCode` folds a typed code the same way and refuses one
that is not eight characters of the code alphabet before it generates a key.

It is `enrollByDeviceCode`'s sibling in every rule — one enrollment of any kind at a time,
the key destroyed on every ending that is not an approval, the deadline judged on the
server's clock, the same long poll, the same result type — and it ends in one of these,
each of which an app shows differently:

| Ending | Arrives as | Show |
| --- | --- | --- |
| approved | the returned `SPFNDeviceCodeEnrollmentResult` / `SpfnDeviceCodeEnrollmentResult` | signed in |
| not a code | `malformedLinkCode` / `MalformedLinkCode`, nothing sent | "that is not a code" |
| unknown or already-used code | `DeviceLinkNotFoundError` (404) on `redeem` | "code not found" |
| code expired | `DeviceLinkExpiredError` (400) on `redeem` or a poll, or `linkCodeExpired` / `LinkCodeExpired` | "code expired — show a new one" |
| refused or wrong number picked | `DeviceLinkDeniedError` (403) on a poll | "not approved" |
| approval collected elsewhere | `DeviceLinkNotFoundError` (404) on a poll | start again |

The server refusals are `SPFNClientError.server` / `SpfnClientError.Server` carrying the
generated code. The signed-in device's half — issuing the code, reading the three numbers,
confirming — is not on the mobile contract, so a phone is always the new device here.

## Where the decisions live

The approved repository and release topology is the decision artifact
`2026-07-27-spfn-mobile-sdk-repository-topology-decision.html`. Everything still open is
listed in [docs/OPEN-DECISIONS.md](docs/OPEN-DECISIONS.md) — including the Maven
namespace, ownership and signing. D17 is resolved: the contract is exported by SPFN
primitives and pinned here at an exact commit.
