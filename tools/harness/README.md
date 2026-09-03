# The harness app, and the flows that drive it

Everything else in this repository is proved without a screen. Hundreds of unit tests, a
reference server, integration cases with receipts — and not one of them ever launches an
app. That is enough for the proof algorithm and the state machine. It stops being enough
the moment the question is whether a person can get through enrolment, or what a refusal
looks like when it reaches a view.

This directory is the answer to that question. It is not a sample and it is not published.

```sh
sh tools/harness/run-harness.sh ios
sh tools/harness/run-harness.sh android
```

## What is here

| Path | What it is |
| --- | --- |
| `ios/project.yml` | the iOS app's project, as data. `SPFNHarness.xcodeproj` is generated from it and gitignored |
| `ios/Sources/` | the iOS harness: a screen of buttons and readouts |
| `ios/Harness.xcconfig` | the device mode's keys, declared empty, plus an optional include of the gitignored `Local.xcconfig` |
| `ios/HarnessSupport/` | a SwiftPM package that enables the two adapter traits, and holds the harness rules that have tests |
| `android/` | the same app in Kotlin, as the one application module in this repository |
| `ios/GeneratedUI/`, `android/…/harness/generated/` | the approval screens, written by `tools/ui-codegen`. Nothing in either is edited by hand |
| `flows/` | the Maestro flows, one per cell of the two case tables below |
| `run-harness.sh` | builds, installs, runs every flow, and fails unless every case left a receipt |
| `probe-receipts.sh` | proves a receipt cannot be earned by a case that did not pass |
| `probe-target-refusal.sh` | proves a physical iPhone cannot be mistaken for a simulator |

## The case table

The lifecycle has three states and three operations. That is nine cells, and every one of
them has a flow. The table was closed before any of this was written, which is why there
is no review round on it: a flow either matches a cell or it does not.

| state ↓ / operation → | `enroll` | `rotate` | `resumeRotation` |
| --- | --- | --- | --- |
| **unenrolled** | c1 — succeeds, becomes `enrolled` | c2 — `notEnrolled` | c3 — `notEnrolled` |
| **enrolled** | c4 — `alreadyEnrolled` | c5 — succeeds, stays `enrolled` | c6 — `notEnrolled` |
| **rotationPending** | c7 — `rotationUnresolved` | c8 — `rotationUnresolved` | c9 — succeeds, becomes `enrolled` |

One case sits outside the table. Revocation is not a lifecycle operation — the SDK exposes
enrolment and rotation, and revocation is a contract operation an app sends — so c10 is the
sequence an app actually performs: revoke this key, make a proven call, meet
`SESSION_REVOKED`, drop the key.

**c10 does not run against the reference server, and the runner says so out loud.** That
server implements the two operations an SDK flow uses — native enrolment and rotation —
and refuses `auth.keys.revoke` with `CONTRACT_UNSUPPORTED`. The first full run found this
by failing on it. Against a target that really implements revocation, put the case back:

```sh
SPFN_HARNESS_REVOCATION_OPS=1 sh tools/harness/run-harness.sh ios
```

Out of scope is announced, never skipped quietly. A case that silently left the expected
list would turn the receipts — the only thing standing between this repository and a run
that reports coverage it did not have — into decoration.

## The second consuming app

This harness is not only a screen of buttons any more. `tools/ui-codegen` emits the
device-approval screens from `examples/ui-spec/device-approval.json` into two apps: the
example apps under `examples/`, and this one. Same spec, same generator, two targets —
`:ui-codegen:spfnGenerateHarnessUi` writes this half and `:ui-codegen:spfnHarnessUiVerify`
fails when it has drifted.

Why a second consumer, when the example apps already compile those screens: the example
apps drive them against FIXTURES. Eighteen cells prove the screens' rules — what a second
press during a call in flight does, what an answer arriving after its flow closed does —
and not one of them proves that the request the screen sends is one a server accepts, or
that the answer it gets back is one the SDK can read. A fixture cannot answer that. This
app can, because it is already the app with a real transport, a real key and a real
reference server behind it.

What that costs the harness is a text field and three buttons it used to have. The
approver's `info`, `approve` and `deny` were wired up by hand here — a second
implementation of screens the generator emits — and they are gone. One button opens the
generated flow, and everything after it belongs to `GeneratedUI/` and
`android/…/harness/generated/`.

### The approval cells

| cell | what the phone does | what the screen must say | what the SERVER must say |
| --- | --- | --- | --- |
| `d1-approve` | types the code and approves the waiting device | flow closed, `stack=0`, `http=200` | the request is `approved` |
| `d2-deny` | types the code and denies it | flow closed, `stack=0`, `http=204` | the request is `denied` |
| `d3-unknown-code` | types `ZZZZ-ZZZZ`, which was never issued | still on `enterCode`, `stack=1`, `state=error` | the parked request is still `pending` |

**Two assertions per cell, and both are needed.** `run-harness.sh` plays the waiting
device: before each cell it parks a device request with the reference server over `curl`
— `auth.device.start`, whose body carries a freshly generated P-256 key, because the
server checks that `fingerprint` is the SHA-256 of the decoded `publicKey` and the poll
that collects an approval parses those bytes as a key. It runs the flow with that
request's user code, and afterwards polls with the device code, up to ten times a second
apart.

A cell earns its receipt only when the flow passed AND the server agrees. A phone that
closed its flow without sending anything would pass every on-screen assertion and leave
the server holding the request `pending`, which is precisely the disagreement these three
cells exist to find. `d3` is the sharpest of the three: it types a code nobody was issued,
and the request this runner parked must be untouched afterwards — a stronger claim than
"the screen showed an error", and the only one that rules out a phone acting on somebody
else's request.

`d3`'s screen assertion stops at "an error is shown". The refusal's classification is the
server's to choose — an unknown code and a spent one are answered alike on purpose, so a
guess that landed cannot be told from one that did not — and a flow asserting a particular
code would be asserting a choice this repository does not own.

The three cells run one maestro invocation each, where the ten lifecycle cells share one.
That is not a preference: each needs a device request that exists before its launch, and
the user code the server issues for it is different every time, so it has to reach maestro
as a variable of that invocation.

## What runs where

Only two things need real hardware, and the SDK is honest about both: a key falls back to
the software path and reports `softwareKeychain` when no Secure Enclave or StrongBox is
there. So the split is real rather than a pretence.

| Needs a device | Runs on a simulator or emulator |
| --- | --- |
| a real Secure Enclave or StrongBox key | every screen, every transition, every refusal |
| a real Apple or Google sign-in | the whole enrol / rotate / revoke sequence |

That first row is thinner than it looks on iOS, and the harness measured it rather than
assuming it. Tapping `custody-probe` on an iPhone 16 Pro simulator, on an Apple silicon
Mac, reports `custody=secureEnclave` — the Mac has an enclave of its own and the simulator
lends it out. So the enclave code path is exercised without a phone, and what a phone adds
is that the enclave answering is the phone's, under its own device-bound key. The readout
cannot tell those two apart; only knowing which target you ran on can.

## The three things worth knowing before you touch this

**A cleared app is not a clean app.** On iOS the keychain survives the app's data being
cleared and survives the app being deleted. Every flow therefore starts by tapping `wipe`,
which calls the SDK's own `wipe()`, and then asserts the state really is `unenrolled`. A
flow that trusted `clearState` would begin its run holding the previous run's key.

**`rotationPending` has exactly one door.** `rotate()` persists the rotation candidate
before it sends, and a transport failure leaves that candidate in place because the server
may or may not have applied the request. So the flows reach that state by dropping the
network mid-rotation, through a harness button over the injected transport. Nothing in the
SDK changed to allow it — the transport is injected, which is what the boundary is for.

**The emulator's `127.0.0.1` is the emulator.** The reference server binds to the host's
loopback address, and how a target reaches that address is different for each kind:

| Target | How it reaches the reference server |
| --- | --- |
| iOS simulator | shares the host's network stack; `127.0.0.1` already works |
| Android emulator | `10.0.2.2`, its own alias for the host loopback — the runner rewrites the URL |
| Android device | `adb reverse`, opened by the runner and removed when the run ends |

`adb reverse` is why an Android phone needs no extra setup: the device's own `127.0.0.1`
arrives at the host's over the debugging connection, so the server stays on loopback and
is never exposed to the network.

**On Android, that address also has to be the one the build permits.** The app speaks
plain HTTP to exactly one host — the one `spfn.harness.serverBaseUrl` names — and to
nothing else, so an emulator run puts `10.0.2.2` in that key and a device run behind
`adb reverse` puts `127.0.0.1`. The alternative was to keep permanent exceptions for all
three loopback spellings, which would make the exception a standing grant to addresses no
run had named. See the device sign-in section below for the key.

A physical iPhone is missing from that table because it never gets that far — the next
section is why.

## Maestro does not drive a physical iPhone, so that half is done by hand

This is not a gap in the runner and no cable closes it. Maestro ships no driver for a real
iOS device. In `maestro-ios.jar`, every method of `ios/devicectl/DeviceControlIOSDevice`
throws `NotImplementedError` except `uninstall`, `stop` and `setPermissions`: it cannot
install an app, launch one, read a view hierarchy or tap. The two dependencies it
downloads for iOS are named `applesimutils` and `simulator-server`. Upstream's own attempt
at device support, mobile-dev-inc/Maestro#2856, was closed unmerged on 2026-06-15 as a
partial implementation that was not advancing.

The parts that are missing are the parts Apple only opens for a simulator. `simctl` grants
media, location and permissions from outside the app; a real device has no equivalent, so
`addMedia`, `setLocation` and `permissions` have no answer at all and `clearState` becomes
a reinstall. So the runner refuses a physical iPhone at the target, where the reason is,
rather than later at the server, where it is not.

**What a real iPhone is actually for.** One thing: hardware custody, and less of it than
the split above first suggested. The ten cells are the same code on both targets, and the
simulator already reaches the enclave — the Mac's. What is left for the phone is that its
own enclave generates and holds the key. That is a small check, so the procedure is small:

```sh
# 1. build and sign for the device — the team id is yours, not this repository's
xcodegen generate --spec tools/harness/ios/project.yml
xcodebuild -project tools/harness/ios/SPFNHarness.xcodeproj -scheme SPFNHarness \
    -destination "platform=iOS,id=<device udid>" \
    -derivedDataPath /tmp/spfn-harness -allowProvisioningUpdates \
    DEVELOPMENT_TEAM=<your team id> CODE_SIGN_STYLE=Automatic \
    CODE_SIGN_IDENTITY="Apple Development" build

# 2. install and launch it
xcrun devicectl device install app --device <device udid> \
    /tmp/spfn-harness/Build/Products/Debug-iphoneos/SPFNHarness.app
xcrun devicectl device process launch --device <device udid> xyz.superfunction.spfn.harness
```

Three of those arguments are not decoration, and the first run of this procedure spent
itself on them. `project.yml` pins manual signing and an ad-hoc identity because that is
what a simulator build needs and it keeps every credential out of this repository, so a
device build has to override both on the command line — `DEVELOPMENT_TEAM` alone leaves
manual signing in place and fails asking for a profile it was never going to find.
`-allowProvisioningUpdates` is what registers the device and issues the profile; without
it the same build fails with the device unregistered. A free personal team registers a
device this way on its own, and a paid team may still answer `isn't registered in your
developer account`, which means adding the udid at developer.apple.com first.

Then tap `custody-probe` and read the `custody=` label.

The first launch on a device is refused — `invalid code signature, inadequate
entitlements or its profile has not been explicitly trusted by the user` — until the
developer certificate is trusted on the phone itself, under Settings → General → VPN &
Device Management. That is a per-device, per-certificate consent that no flag from this
side can grant.

| Reading | What it means |
| --- | --- |
| `custody=secureEnclave` | the phone's enclave generated and holds the key |
| `custody=softwareKeychain` | it fell back. The SDK is behaving correctly and saying so, but the enclave path did not happen on this phone — and a simulator on this machine does reach it, so the fallback is the phone's own answer |

**Write down which phone.** `custody=secureEnclave` is a fact about the hardware that
answered, and a simulator on this Mac answers the same word, so a reading with no target
beside it is not evidence of anything. `run-harness.sh` states its own target twice —
once at the top and once beside `RESULT:` — precisely so a pasted transcript carries it;
a manual reading has no run to do that, so the model and iOS version belong in the report
next to the word. The reading this procedure has actually produced is one: an iPhone 14
Pro (iPhone15,2) on iOS 26.5.2 answers `custody=secureEnclave`. So the phone's own enclave
does generate and hold the key, and the one thing a simulator could not settle is settled.

The probe generates a key through the same call `SPFNKeyLifecycle` uses, reads which
custody it landed in, and drops it. It stores nothing and sends nothing, which is what
makes it runnable on a phone with no route to the reference server. `xcrun devicectl list
devices` names the udid.

**Put it on the same wifi and it still will not appear.** A device that reads
`unavailable` with no `transportType` is paired and not connected, and the network is
usually not what is missing. `devicectl` connects over CoreDevice, which finds a phone by
its `_remotepairing._tcp` advertisement, and a phone only advertises that once wireless
debugging has been turned on for this Mac — which is done from Xcode's Devices window
while the phone is attached by cable. A phone that has been wirelessly debugged by
something older advertises `_apple-mobdev2._tcp` instead, which looks like presence and
is not the service `devicectl` browses for, so `dns-sd -B _remotepairing._tcp local`
answering nothing while `_apple-mobdev2._tcp` answers is the shape of this exact
misreading. Attach the cable, and the entry turns `wired` and `connected`.

Android has the same button for the same reason, and it answers in its own vocabulary:
`strongBox` or `trustedEnvironment`. The two platforms name different hardware, so a flow
that ever asserts on this asserts per platform.

## Sign-in, and why it is a launch argument

Maestro drives the app under test. The Apple and Google sign-in sheets are system UI
outside that app, so a flow cannot be relied on to get through them. The SDK already
takes the sign-in as a closure, so the harness substitutes one:

| Launch argument | What it does |
| --- | --- |
| `SPFN_HARNESS_TEST_USER` | composes the reference server's test token around the nonce the SDK supplies |
| `SPFN_HARNESS_ID_TOKEN` | a real provider token, used verbatim. A device run against a real server needs this |

The test token cannot be a fixed string. That server checks the token's nonce against the
fingerprint of the key being enrolled, and the fingerprint does not exist until the key
does — which is exactly why the SDK hands the nonce to the closure.

An Android device run leaves both out, takes the real sheet by hand, and everything on
either side of that one tap stays automatic.

## The device sign-in mode

This is the mode where nothing is substituted: a real account, a real id token, a real
SPFN server, and a file left behind saying what happened. It is driven by a person,
because the sheet is the provider's own UI and no flow may be trusted to get through it.

Both platforms run it from the same screen, with the same words on it. What differs is
which providers exist, where the configuration is read from, and how the receipts come off
the phone — those are the two platform sections that follow this one.

### The screen

Top to bottom, the same on both:

| Part | What it is |
| --- | --- |
| the readouts | `state=`, `outcome=`, `busy=`, `network=`, `custody=`, `case=`, the configuration line, `receipt=`, `device-code=`, `expires-in=`, `stack=`, `http=`. A flow matches these by their text, never by an id |
| `case (pick one)` | the five cases as one boxed single-choice selector |
| the precondition line | what the selected case asks you to do at the sheet |
| `sign-in-apple`, `sign-in-google` | the only two things in this mode that do anything. Android has the Google one only |
| `device code` | `sign-in-with-a-code`, which signs THIS device in, and `open-approve`, which opens the generated approval screens over everything above. `open-approve` is disabled without an active key: the approval calls are proven ones |
| `sdk lifecycle (flows)` | a divider, and under it the ten buttons the Maestro flows tap — `enroll`, `rotate`, `resume`, `revoke`, `proven-call`, `note-revoked`, `wipe`, `custody-probe`, `block-network`, `open-network` |

The last two readouts belong to the generated flow. `stack=` is its own depth, spelled
exactly as the generated screens spell it — a flow that is open draws the number twice,
and the two agree because they are one flow read twice rather than two counters. `http=`
is the status of the last response the transport received, which is what tells a 200 from
a 204 when a screen shows the same thing either way.

The Android half is Jetpack Compose and the iOS half is SwiftUI, and neither draws above
its platform's foundation layer. A control is found by the id it carries — on Android a
Compose test tag, published as the resource id a runner selects on by
`testTagsAsResourceId` on the root — and a readout is found by its text. That is the same
split the generated example screens use, so this repository now has one rule for it rather
than one per app.

**One tap is one attempt.** Every device-mode attempt wipes before it asks the provider for
anything, so there is nothing to remember and no order to get wrong. The first device run
produced three `alreadyEnrolled` receipts purely because a person forgot the wipe, and each
one proved nothing about a provider. `wipe` under the divider is still there because the
flows tap it; it is not part of this mode.

If a wipe ever fails, the attempt stops there rather than enrolling on top of a state
nobody could clear. The screen says `outcome=err:wipe:<name>` and `receipt=none` — no file
is written, because nothing happened to write one about.

**A case is a selection, not a button.** Exactly one case is always selected and
`first-enroll` is selected at launch. The names are the spec's wire names on both
platforms — `first-enroll`, not `case-first-enroll` and not `[first-enroll]`. It is the
selected case that names the receipt, so the `case=` readout and the file agree by
construction.

**`network=` is permanent.** It reads `open` or `blocked` at all times, because the
transport switch that `network-failure` uses is the same one the `block-network` button
flips and a switch left shut looks exactly like a real network drop. Three receipts of the
first device run went that way, on a state nothing on the screen showed.

### The five cases

| case | expected outcome | what to do |
| --- | --- | --- |
| `first-enroll` | `enrolled`, `isNewUser` true | sign in with an account this server has never seen |
| `re-login` | `enrolled`, `isNewUser` false | sign in with the account `first-enroll` used |
| `user-cancel` | `cancelled`, no key left behind | dismiss the sheet |
| `network-failure` | `failed`, no key left behind | complete the sheet — the app drops its own transport for the attempt |
| `server-reject` | `failed`, no key left behind | complete the sheet — the app sends a token the server cannot verify |

The case is a declaration, not a switch. `first-enroll`, `re-login` and `user-cancel` are
one code path — the app cannot tell a first enrolment from a second one, or a dismissal
from a sign-in that never started — so what the person meant is recorded beside what
actually happened. What separates the first two is the ACCOUNT you pick in the sheet: the
attempt wiped this install either way, so it is the server that has seen the account
before, and `isNewUser` is the field that says so.

Only the last two change what the app does, and both do it on this side of the wire.
`network-failure` flips the same transport switch the `block-network` button flips, so the
sheet still works and the enrolment request is what fails. `server-reject` appends a marker
to the token the adapter returned, so the sheet is real and the signature the server checks
is not. Neither touches the SDK, and neither needs a server mode, a stub or a second
endpoint.

`alreadyEnrolled` is now unreachable here. If one ever appears in a receipt it is not a
case that was run wrong — it means a wipe reported success and left a key, which is a
finding about the SDK.

## The device sign-in mode, on Android

Apple is not here and will not be. There is no Android Apple adapter — Apple ships no
native sign-in SDK for this platform — so the mode is Google only, by declaration rather
than by omission (`tools/module-graph.json`, `social-apple`).

### What you configure, and where

Two keys in `local.properties` at the repository root. That file is gitignored, and the
build reads nothing else — no environment variable, no committed default.

| Key | What it is |
| --- | --- |
| `spfn.harness.google.serverClientId` | the **web** OAuth client id of your Google project. Credential Manager calls it `serverClientId`, and the Android client id is not it |
| `spfn.harness.serverBaseUrl` | scheme, host and port of the SPFN server the phone enrols against. No path, no query |

Neither value is ever printed — not by the build, not by the app, not into a receipt. A
build that finds a key present but malformed **fails**, naming the key and the shape it
wanted; a build that finds no keys at all **succeeds**, and the app installs with the
`sign-in-google` button disabled and `social=not-configured` on the screen. Those two
outcomes are different on purpose: an absent configuration is a normal checkout, and a
typo in a configured one must not look like the same thing.

The disabled button is the one titled `sign-in-google`. Its id is `btn_social_google`,
which is what it has always been — a selector matches the id, and renaming one to agree with
a title would break every selector that names it in exchange for nothing.

`spfn.harness.serverBaseUrl` also drives the cleartext exception. `AndroidManifest.xml` no
longer says "this app may speak plain HTTP to anything"; the build writes a network
security configuration permitting cleartext to **exactly the host that key names** and to
nothing else. A build configured with no server permits cleartext to nothing at all.

That is one host, not a set, and it decides what every run on this platform can reach:

| Run | What goes in `spfn.harness.serverBaseUrl` |
| --- | --- |
| device sign-in against a LAN server | that machine's address |
| Maestro flows on an emulator | `10.0.2.2`, the emulator's alias for the host loopback |
| Maestro flows on a device behind `adb reverse` | `127.0.0.1` |

A request to a host the build does not name is refused by the platform, before it leaves.

### Running it

```sh
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :harness-android:installDebug
```

Then, on the phone: pick the case in the selector, tap `sign-in-google`, and complete or
dismiss the sheet. That is the whole attempt — see **The device sign-in mode** above for
the screen and the five cases, which are the same here as on iOS.

The selector holds "exactly one of these" in one field: a single case, so there is no
arrangement of taps that selects two or none. Each row keeps the id it always had —
`btn_case_first_enroll` through `btn_case_server_reject`, now a Compose test tag rather than
an entry in `res/values/ids.xml` — so anything that could already find a case still finds
it.

### Collecting the receipts

Every attempt writes one file, whichever way it went, into the app's external files
directory:

```sh
adb pull /sdcard/Android/data/xyz.superfunction.spfn.harness/files ./receipts
```

The file is `receipt-google-<case>-<epochMillis>.json` and the screen names the last one in
its `receipt=` label. **No receipt contains a token, an email, a name or any account
identifier** — a receipt that carried one would be a credential rather than evidence, and
that is a blocking defect, not a cleanup.

| Field | What fills it |
| --- | --- |
| `outcome` | `enrolled`, `cancelled` or `failed`. A dismissed sheet is `cancelled`, never a failure |
| `responseCode` | the status of the last response the attempt received, or `null` when none arrived |
| `errorCode` | the SDK's own name for the refusal. A server refusal on the native enrolment endpoint arrives as a `decoding:` name, because that endpoint sits outside the clientProof middleware and does not answer in the contract's error envelopes |
| `isNewUser`, `keyIdMatch` | the server's answer and this install's own check of it. `null` on anything but an enrolment, because no server said anything |
| `keyRemainsAfterFailure` | read from the **Keystore**, not from the SDK's metadata: on Android the alias exists before the sign-in is asked for, so whether a failure left one behind is a question only the Keystore can answer |
| `serverCommit` | a response header, and only if it **is** a commit hash — 7 to 40 lowercase hex characters after lowercasing. Anything else is `null`, because a header is written by whatever answered and an unvalidated one is how an address or a name reaches a file that was supposed to hold neither. The contract declares no such header, so `null` is an ordinary reading |

Attempts do not overwrite each other. The file name carries milliseconds, which is what the
spec fixes after two attempts at one case finished inside the same second and the second
one destroyed the first one's evidence.

## The device sign-in mode, on iOS

Everything in the flows proves the SDK's own behaviour with a token the harness composed.
What it cannot prove is that a person tapping Apple's or Google's own sheet ends up enrolled
against a real server — that needs a real provider, a real signature, and a human. So the
iOS harness has a second half that no flow drives.

Pick the case in the selector, tap `sign-in-apple` or `sign-in-google`, and the app writes a
JSON receipt into its Documents directory. That is the whole attempt — see **The device
sign-in mode** above for the screen and the five cases, which are the same here as on
Android. The receipt is the evidence; the screen is a convenience.

Both providers are here, which is the one thing this platform has that Android does not.
The selector is a row group rather than a segmented control: five case names do not fit
across a phone, and a `Picker`'s rows are not elements a flow could name. Each row keeps
the accessibility identifier it always had — `btn_case_first-enroll` through
`btn_case_server-reject`.

### What a receipt says

`Documents/receipt-<provider>-<case>-<epochMillis>.json`, one per attempt, the schema
shared with the Android half. Read them off the phone with Finder — the app declares
`UIFileSharingEnabled`, so it appears under the device's Files tab — or through the Files
app on the phone itself.

Milliseconds, not seconds. Two attempts at the same case that finished inside one second
used to land on one name, and the second write destroyed the first attempt's evidence —
which a cancelled sheet reaches easily.

**A receipt never carries a token, an email address or a name.** That is enforced by
shape rather than by care: every field is a boolean, an integer, a timestamp, an
SDK-classified error name or a version constant, and the adapters drop a provider's
message text before the harness ever sees an error. A receipt holding a token would be a
credential, and finding one is a blocking defect rather than a cleanup.

Two fields are worth reading closely:

- `errorCode` is the SDK's own classification, never a translation. `server-reject`
  arrives as one of the `decoding:` names rather than as a contract error code, because
  `/_auth/oauth/:provider/native` sits outside the clientProof middleware and answers with
  something the contract's error envelope does not describe. That is the expected reading,
  not a bug in the harness.
- `serverCommit` is the one field whose value arrives from the network, so it is the one
  place PII could enter a file that leaves the phone. It is kept only when it matches
  `^[0-9a-f]{7,40}$` after lowercasing, and is `null` otherwise — including against every
  server in this repository, none of which states a build in a header yet.

Both rules have tests. `swift test --package-path tools/harness/ios/HarnessSupport` runs
them: the file name at millisecond granularity, and the commit filter against hashes,
length boundaries, uppercase hex, non-ASCII lookalike digits and a handful of strings a
misconfigured header could carry instead.

### Configuring it, and what happens when you have not

Three values are needed and none of them may be committed: where the verify server is on
your wifi, and a Google OAuth client id with the URL scheme derived from it. Write them
into `tools/harness/ios/Local.xcconfig`, which is gitignored:

| key | what it holds |
| --- | --- |
| `SPFN_HARNESS_SERVER_HOST` | the Mac's LAN address or `.local` name — host only, no scheme and no path |
| `SPFN_HARNESS_SERVER_PORT` | the verify server's port |
| `SPFN_HARNESS_GOOGLE_CLIENT_ID` | the iOS OAuth client id from the Google Cloud console |
| `SPFN_HARNESS_GOOGLE_REVERSED_CLIENT_ID` | that client id with its dot components reversed |

Host and port are two keys rather than one URL because `//` opens a comment in an
xcconfig: a whole URL written there truncates to `http:` without a word of warning.

A checkout without that file still builds. Every key expands to an empty string, the
`config=` readout says which half is missing, and both provider buttons are disabled and
say `(not configured)`. That is deliberate and it is not politeness: Google's SDK answers
a missing client id, or an unregistered callback scheme, by raising an NSException, which
no Swift caller can catch. So the app recomputes the scheme from the client id and checks
the bundle really registers it before it will let the button be tapped at all.

### Signing it

The device build needs a **paid** Apple Developer Program team. Sign in with Apple is a
paid-programme capability, so the `com.apple.developer.applesignin` entitlement the
harness now carries cannot be signed by a free personal team.

The custody probe above never needed that entitlement, and a free team can still run it
by dropping the entitlements file for that build alone:

```sh
xcodebuild -project tools/harness/ios/SPFNHarness.xcodeproj -scheme SPFNHarness \
    -destination "platform=iOS,id=<device udid>" \
    -allowProvisioningUpdates DEVELOPMENT_TEAM=<your team id> \
    CODE_SIGN_STYLE=Automatic CODE_SIGN_IDENTITY="Apple Development" \
    CODE_SIGN_ENTITLEMENTS="" build
```

### Why there is a second Package.swift

`ios/HarnessSupport/` is a small package, and it exists because a package trait can only
be turned on by a manifest. `SPFNSocialGoogle` links Google's SDK and
exposes `init(presenting:)` only under the `SocialGoogle` trait, and an Xcode app target
has no manifest to enable it from. XcodeGen will write `traits = (...)` into the
generated project and Xcode 26.2 ignores it — measured with a probe project, which
resolved zero remote packages and could not see `SPFNGooglePresentingContext`. So the
trait is declared in `HarnessSupport/Package.swift`, which the app depends on; adding it
to the graph turns the traits on for every copy of the SDK in that graph.

It carries three of the harness's own types too — the receipt, the case and provider
names, and the server-commit filter — for a reason that is not tidiness. An Xcode app
target has no suite `swift test` can run, and those two rules have expected values worth
pinning, so they live where a test can reach them. The app imports them unchanged.

Nothing in the SDK changed for any of this.

## Running against something other than the reference server

```sh
SPFN_HARNESS_TARGET_URL=http://192.168.1.10:8790 \
SPFN_HARNESS_ID_TOKEN=<a real provider token> \
    sh tools/harness/run-harness.sh android
```

The runner starts nothing and stops nothing in this mode. It never falls back to the local
server: a run that checked the reference server while reporting a real one would be the
most expensive kind of green there is.

**On Android that host has to be the one in `local.properties`.** The app permits cleartext
to exactly one host, so a target the build never heard of is refused by the platform before
any request leaves. Put the same address in `spfn.harness.serverBaseUrl` and rebuild.

## Picking a target

The runner uses the one booted simulator or the one attached device. Two of either and it
refuses rather than guessing — name one with `SPFN_HARNESS_TARGET`.

**A signed-in simulator can fail all nine cells at once.** A simulator carrying an Apple
account eventually raises the system alert asking for that account's password, and it
sits above the harness. Every flow then dies identically on `Element not found: Id
matching regex: btn_wipe`, which reads like the app failed to render and is not that —
the app is behind the alert, showing every button. `xcrun simctl io <udid> screenshot`
answers the question in one command, and `xcrun simctl erase <udid>` clears the account
along with everything else. A run that begins with nine identical element-not-found
failures is worth one screenshot before it is worth any debugging.

## On a real Android phone

```sh
ANDROID_HOME=$HOME/Library/Android/sdk sh tools/harness/run-harness.sh android
```

Plug it in with USB debugging on and accept the prompt — an unauthorized device is not
listed as `device` by `adb`, so the runner refuses it rather than picking it. Everything
else is the same as an emulator run: the runner opens the reverse route, builds, installs
the debug APK, and removes the route afterwards.

The debug build signs with the machine's own `~/.android/debug.keystore`, which is why
no signing material lives in this repository and none is needed to install on a phone.

**Wake it first.** A sleeping or locked Android target does not fail the run, it hangs it:
`am instrument` is refused while the user's storage is locked, and Maestro waits forever
with nothing in its log after `Selected device`. The runner wakes the target and refuses
when it cannot, but a phone with a PIN has to be unlocked by hand.

That refusal was written from an emulator that could not be woken at all: it stayed in
direct-boot with user 0 `RUNNING_LOCKED` through two reboots and a disabled lock screen,
and every instrumentation start answered `Package dev.mobile.maestro is not encryption
aware`. If a target ever does that, it is the target, not the harness.

Google sign-in on an emulator additionally needs a `google_apis_playstore` system image.
The `google_apis` image has no Play Store and cannot update its own Play services, which
`GetGoogleIdOption` expects to be reasonably current. A real phone has neither problem,
which is the other reason device runs are the ones that settle provider sign-in.
