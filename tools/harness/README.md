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
    -derivedDataPath /tmp/spfn-harness DEVELOPMENT_TEAM=<your team id> build

# 2. install and launch it
xcrun devicectl device install app --device <device udid> \
    /tmp/spfn-harness/Build/Products/Debug-iphoneos/SPFNHarness.app
xcrun devicectl device process launch --device <device udid> xyz.superfunction.spfn.harness
```

Then tap `custody-probe` and read the `custody=` label.

| Reading | What it means |
| --- | --- |
| `custody=secureEnclave` | the phone's enclave generated and holds the key |
| `custody=softwareKeychain` | it fell back. The SDK is behaving correctly and saying so, but the enclave path did not happen on this phone — and a simulator on this machine does reach it, so the fallback is the phone's own answer |

The probe generates a key through the same call `SPFNKeyLifecycle` uses, reads which
custody it landed in, and drops it. It stores nothing and sends nothing, which is what
makes it runnable on a phone with no route to the reference server. `xcrun devicectl list
devices` names the udid; a device that reads `unavailable` is paired but not currently
reachable, so unlock it and put it on this machine's network.

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

## Running against something other than the reference server

```sh
SPFN_HARNESS_TARGET_URL=http://192.168.1.10:8790 \
SPFN_HARNESS_ID_TOKEN=<a real provider token> \
    sh tools/harness/run-harness.sh android
```

The runner starts nothing and stops nothing in this mode. It never falls back to the local
server: a run that checked the reference server while reporting a real one would be the
most expensive kind of green there is.

## Picking a target

The runner uses the one booted simulator or the one attached device. Two of either and it
refuses rather than guessing — name one with `SPFN_HARNESS_TARGET`.

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
