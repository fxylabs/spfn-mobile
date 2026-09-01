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
| `ios/Sources/` | the iOS harness: a screen with ten buttons and four labels |
| `android/` | the same app in Kotlin, as the one application module in this repository |
| `flows/` | the Maestro flows, one per cell of the case table below |
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

## The device sign-in mode, on Android

This is the mode where nothing is substituted: a real Google account, a real id token, a
real SPFN server, and a file left behind saying what happened. It is driven by a person,
because the sheet is the provider's own UI and no flow may be trusted to get through it.

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
`social-google` button disabled and `social=not-configured` on the screen. Those two
outcomes are different on purpose: an absent configuration is a normal checkout, and a
typo in a configured one must not look like the same thing.

The same two keys drive the cleartext exception. `AndroidManifest.xml` no longer says
"this app may speak plain HTTP to anything"; the build writes a network security
configuration permitting cleartext to the emulator's host alias, the device's own
loopback, and the one host `spfn.harness.serverBaseUrl` names — and to nothing else.

### Running it

```sh
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :harness-android:installDebug
```

Then, on the phone: pick the case, tap `social-google`, and complete or dismiss the sheet.

| Button | The case it declares |
| --- | --- |
| `case-first-enroll` | this account has never enrolled here. Tap `wipe` first |
| `case-re-login` | the same account again. Tap `wipe` first, then sign in as the same person |
| `case-user-cancel` | dismiss the sheet instead of choosing an account |
| `case-network-failure` | the harness holds the transport shut for the attempt; the sheet still runs |
| `case-server-reject` | the harness damages the token after the provider issued it, so the server refuses it |

The case is a declaration, not a switch. `first-enroll`, `re-login` and `user-cancel` are
the same code path — the app cannot tell a first enrolment from a second one, and a
dismissal from a sign-in that never started — so what the person meant is recorded next to
what actually happened. Only the last two change behaviour, and both do it on this side of
the wire: nothing about the server or the SDK is configured for them.

### Collecting the receipts

Every attempt writes one file, whichever way it went, into the app's external files
directory:

```sh
adb pull /sdcard/Android/data/xyz.superfunction.spfn.harness/files ./receipts
```

The file is `receipt-google-<case>-<epochSeconds>.json` and the screen names the last one
in its `receipt=` label. **No receipt contains a token, an email, a name or any account
identifier** — a receipt that carried one would be a credential rather than evidence, and
that is a blocking defect, not a cleanup.

| Field | What fills it |
| --- | --- |
| `outcome` | `enrolled`, `cancelled` or `failed`. A dismissed sheet is `cancelled`, never a failure |
| `responseCode` | the status of the last response the attempt received, or `null` when none arrived |
| `errorCode` | the SDK's own name for the refusal. A server refusal on the native enrolment endpoint arrives as a `decoding:` name, because that endpoint sits outside the clientProof middleware and does not answer in the contract's error envelopes |
| `isNewUser`, `keyIdMatch` | the server's answer and this install's own check of it. `null` on anything but an enrolment, because no server said anything |
| `keyRemainsAfterFailure` | read from the **Keystore**, not from the SDK's metadata: on Android the alias exists before the sign-in is asked for, so whether a failure left one behind is a question only the Keystore can answer |
| `serverCommit` | the first commit-shaped response header the server sent, or `null`. The contract declares none, so `null` is an ordinary reading |

A second attempt at the same case within the same second overwrites the first, because the
file name is the spec's and its resolution is one second. Pull between attempts if that
matters.

## Running against something other than the reference server

```sh
SPFN_HARNESS_TARGET_URL=http://192.168.1.10:8790 \
SPFN_HARNESS_ID_TOKEN=<a real provider token> \
    sh tools/harness/run-harness.sh android
```

The runner starts nothing and stops nothing in this mode. It never falls back to the local
server: a run that checked the reference server while reporting a real one would be the
most expensive kind of green there is.

**On Android that host also has to be in `local.properties`.** The app permits cleartext
to the hosts named at build time and to nothing else, so a target the build never heard of
is refused by the platform before any request leaves. Put the same address in
`spfn.harness.serverBaseUrl` and rebuild.

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
