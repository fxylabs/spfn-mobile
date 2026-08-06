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
| `ios/Sources/` | the iOS harness: a screen with nine buttons and three labels |
| `android/` | the same app in Kotlin, as the one application module in this repository |
| `flows/` | the Maestro flows, one per cell of the case table below |
| `run-harness.sh` | builds, installs, runs every flow, and fails unless every case left a receipt |

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
| iOS device | nothing; name a reachable server with `SPFN_HARNESS_TARGET_URL` or the run is refused |

`adb reverse` is why an Android phone needs no extra setup: the device's own `127.0.0.1`
arrives at the host's over the debugging connection, so the server stays on loopback and
is never exposed to the network.

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

A device run leaves both out, takes the real sheet by hand, and everything on either side
of that one tap stays automatic.

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
