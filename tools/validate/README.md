# Offline validator

```sh
sh tools/validate/validate.sh
```

Needs POSIX `sh`, `grep`, `sed`, `awk`, `find` and a SHA-256 utility (`shasum` or
`sha256sum`). No network, no package manager, no toolchain. Exit code 0 means every
check passed.

## What it checks

| # | Check |
| --- | --- |
| 1 | required files and directories exist |
| 2 | the committed Gradle wrapper jar and distribution match the checksums gradle.org publishes |
| 3 | no fabricated binaries, credentials, keystores or private keys |
| 4 | `VERSION` agrees with Swift, Kotlin, Gradle, the podspec and the changelog |
| 5 | contract lock discipline, in every direction |
| 6 | no redirect-based browser auth surface; the allowlist is exactly `clientProofV1`; no JS bridge |
| 7 | publication disabled, dependency repositories limited to the approved three, verification metadata populated, workflows inert |
| 8 | module graph coherence across `module-graph.json`, SwiftPM, Gradle settings, module directories and the podspec |
| 9 | generated sources are traceable: every one declares itself generated and names the digest the lock pins |
| 10 | the D5 toolchain baseline is declared explicitly rather than inherited |
| 11 | ownership, license, resolved decisions and every compatibility support row are represented honestly |
| 12 | the repository declares its own status, in docs and in both built libraries |
| 13 | the `ui` module's `Loadable`, `Busy` and `Flow` names are the same on both platforms, and `SPFNUI` never reaches the SwiftUI dismiss environment value |
| 14 | the apps that consume the scaffold hold the generated boundary: descriptors only in generated services, no `dismiss`, every cell covered, every cell seedable |
| 15 | the visual vocabulary — tokens, strings, components and the minimum touch target — is the same set on both platforms |
| 16 | both Android apps declare `android:enableOnBackInvokedCallback`, which is what gives the SDK's predictive-back transition any progress to animate |
| 17 | no pointer input under `android/spfn-ui/src/main` consumes every change it is handed, which is what cancels a finger's press on the controls underneath it |
| 18 | every `NavDisplay` under `android/spfn-ui/src/main` states its three transitions from `FlowTransitions`, so a modal flow and a pushed flow move the same way |

## What it does not check

It compiles nothing, and it never reports a result it did not produce. These are
separate commands with separate evidence:

```sh
swift build && swift test
./gradlew build
./gradlew :contract-codegen:spfnCodegenVerify
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
```

## Check 5 is the reason this validator exists

A placeholder lock can only fail by inventing a value. A **resolved** lock can fail by
inventing provenance, which is worse: a fabricated "exported by upstream CI" record
reads exactly like a real one.

So the rules are asymmetric on purpose. A locally authored bundle may be pinned as long
as it says so: `origin: spfn-mobile-step2-dev-bundle`, `exportedByUpstreamCI: false`, no
40-hex commit, and a `manifestSha256` that is the real digest of the file it names.

An upstream claim is held to more. Until 2026-08-02 there was no export to make, so the
rule was simply to refuse a claim with no evidence beside it. Now that the lock is
`RESOLVED_UPSTREAM`, the check turned around: the claim is compared against
`Contracts/upstream-provenance.json`, the file the exporter itself wrote — same origin,
same digest, same exporter version, same repository, same version and range — plus an
exact 40-hex commit and a bundle that labels itself `UPSTREAM_EXPORT`. Evidence naming
this repository as the source fails, because that is what a dev bundle dressed up as an
export looks like. A lock that agrees only with itself is not evidence of anything.

The digest and fixture checks are shared by both resolved states rather than living
inside the dev-bundle branch, so moving the lock upstream cannot quietly drop them.

## Check 11 pins a decision rather than blocklisting its opposite

D11 settled that CocoaPods is not supported and recorded no activation condition on
purpose. The first attempt to hold that shut was a list of forbidden phrasings, and
review took it apart twice: "may be enabled after a separate approval", then "could
return as an optional distribution channel". A policy sentence can be reopened in
unbounded ways, so no enumeration converges.

The gate is therefore a digest. `d11-policy.lock.json` pins the exact text of the
decision in both places it is written down — the policy section of
`tools/cocoapods-compat/README.md` and the D11 row of `docs/OPEN-DECISIONS.md` — and any
edit to either fails the build until somebody updates the lock. Reopening the decision
becomes a visible act instead of a sentence nobody noticed. The row is pinned whole
because the check that preceded it read only the row's first three cells: review showed
a row could keep the word RESOLVED and say "CocoaPods is supported through an approved
release path" in the cells after it. `d11-forbidden.ere` survives as a second,
best-effort net over the rest of the fixture README, where prose is legitimate and a
digest would be too rigid.

`probe-d11-guardrail.sh` holds all of it to its claims: each pinned digest must move on
the smallest edit that reverses its meaning, the section extraction must stop at the
next heading rather than swallowing the document, exactly one D11 row may exist,
fourteen reopening sentences must be caught, twelve descriptive ones must be spared, the
validator must read both files instead of carrying its own copy, and every file the
guardrail depends on must be tracked by git. The validator runs the probe as part of
check 11.

```sh
sh tools/validate/probe-d11-guardrail.sh                  # prove the guardrail
sh tools/validate/probe-d11-guardrail.sh --print-digest   # after an approved edit
```

## Check 13 compares two source trees rather than one

`Loadable`, `Busy` and `Flow` are written twice, once per platform, and nothing but a
comparison keeps the two copies one vocabulary. An app built against `Loadable.empty` on
one platform and a `Loadable` that has no empty on the other is not portable — and both
halves would compile, both suites would pass, and the divergence would surface as a
missing branch in somebody's product.

So the names are extracted from `Sources/SPFNUI/*.swift` and
`android/spfn-ui/src/main/kotlin/**/*.kt` and compared per type, lowercased: Swift's
`case loading` and Kotlin's `data object Loading` are one name, and neither spelling is
the vocabulary. Extraction is scoped to the declaring TYPE rather than to the file —
`Flow.swift` also declares `SPFNUIError`, whose `emptyStack` is not one of Flow's names.

The check is a reader, which is the shape that goes quiet rather than red: a reader that
read nothing yields an empty set, two empty sets agree, and the section would report
parity having read no code at all. Both sides of every comparison therefore have a floor,
the pass message carries the count, and an empty file list is refused rather than handed
to `awk` — which would read standard input instead.

`@Environment(\.dismiss)` is refused outright in `SPFNUI`. It closes whatever presented
the current view without telling the `Flow`, which leaves the host dismissed over a flow
that still believes it is open — the double-source-of-truth the module is built to avoid,
arriving through the one door that looks like ordinary SwiftUI.

```sh
sh tools/validate/probe-ui-vocabulary-rules.sh   # prove each refusal bites
```

The probe renames a case on each side, removes a `Flow` method from each side, writes the
Swift cases on one line and then renames one inside that line, takes each extraction's
input away, plants a `dismiss`, and drops the `canImport(SwiftUI)` guard. Twelve cases,
each scoped to section 13's own output.

## Check 16 guards something no assertion in this repository reads

`NavigationHost` states a `predictivePopTransitionSpec`: what is drawn while a back gesture
is being **held**, before the person has decided to finish it. It runs only if the system
hands this process the gesture's progress, and whether it does is a property of the
**window** — so it is declared by whoever owns the window, which is the host app:

```xml
<application android:enableOnBackInvokedCallback="true">
```

On Android 13, 14 and 15 the flag is off unless a manifest says otherwise, whatever the app
targets; the target-SDK default only flips at Android 16. Undeclared, the gesture goes down
the legacy back path, `OnBackPressedDispatcher` receives the completed back, and the flow
pops correctly — every cell that asserts a stack depth stays green. What is missing is the
progress, so the transition never runs. Nothing here asserts an animation, which is why this
is a check rather than a cell.

Both Android apps are named rather than globbed, and the count is checked against that same
list: a pattern that stopped matching an app would say nothing, and a list that resolved to
nothing would agree with a clean tree. The SDK's own file is held to the other half of the
sentence — `NavigationHost.kt` has to tell a host app what to declare, or the rule lives
only in the two manifests that already obey it.

```sh
sh tools/validate/probe-predictive-back-rules.sh   # prove each refusal bites
```

The probe takes the declaration out of each manifest separately, turns one to `"false"`,
leaves the attribute in a comment and nowhere else, takes the manifest list away, and strips
the flag from the SDK's documentation. Seven cases, each scoped to section 16's own output.

## Check 17 guards something only a person can see

A Compose modifier that answers `pointerInput` by consuming **every** change takes the press
out of the controls underneath it — but only for a finger. `clickable` does not decide a
press on the down. `ClickableNode.onPointerEvent` handles down and up on the Main pass and
calls `checkForCancellation` on the **Final** pass, which cancels the press the moment any
change other than its own down reports `isConsumed`; Final runs parent before child, so a
parent that consumed on Main arrives there as a cancel.

What makes it a check rather than a review note is who can see it. A finger always produces
MOVE events — a few pixels of tremor is a MOVE — and every runner here synthesises a DOWN
and an UP with nothing between them. A modal cover that consumed everything was green in all
35 device cells, green in Maestro, green under `adb shell input tap`, and dead under a thumb
on a Galaxy Z Flip4 (`docs/IMPLEMENTATION-PITFALLS.md` P36).

The rule is about **blanket** consumption. A gesture detector that claims the change it
recognised is how Compose gestures work and is not this; what is refused is a loop that
hands every change in an event to `consume` before anything about the change is known.

Newlines become spaces before the match, because the spelling is not a line — written across
three it is the same defect. The file is read as text, comments and all: a Kotlin
comment-stripper that is wrong about nesting or string literals hides code, and the price of
not writing one is that this module may not quote the forbidden line in its own prose
either.

```sh
sh tools/validate/probe-pointer-consumption-rules.sh   # prove each refusal bites
```

The probe plants the block spelling in one file and the call spelling in another, spreads
the block spelling over three lines, leaves it in a comment and nowhere else, and takes the
source root away. Six cases, each scoped to section 17's own output.

## Check 18 keeps one app from holding two opinions

`NavDisplay` takes a forward, a pop and a predictive-pop transition spec and defaults all
three when they are not given. Read out of navigation3-ui 1.1.7 with javap, the defaults are
`fadeIn(tween(700)) togetherWith fadeOut(tween(700))` forward, the same again for the pop, and
`fadeIn(spring(1f, 1600f)) togetherWith scaleOut(0.7f)` for the predictive pop. Each is
reasonable for a navigator that does not know what it is drawing, and none is what a stack of
screens does on either platform this SDK ships to.

So a module that states them at one call site and not at the others ships two apps. It did:
`NavigationHost` stated its three and the flow's own inline stack — a sheet's stack, a modal's
cover, a pushed flow that found no host — did not, and on a phone `next` inside a modal faded
in where the same tap in a pushed flow slid in from the right, and a back inside that modal
shrank the screen away where a back in a push slid it off to the right
(`docs/IMPLEMENTATION-PITFALLS.md` P37).

No assertion in this repository reads it. A runner asserts what a screen says, never how it
arrived, so all 35 device cells pass either way; and `NavDisplay`'s arguments are not readable
from outside a composition, so the JVM suite can hold `FlowTransitions` to having three values
of which two are one value (`FlowTransitionsTest`) and cannot hold any stack to being handed
them. This check reads the call sites, which is the other half.

Each call's arguments are taken to run from its own `NavDisplay(` to the next one in the same
file, or to that file's end. The reader is `index`/`substr` and no regex at all, which is one
fewer BSD-versus-GNU spelling to get wrong (`docs/IMPLEMENTATION-PITFALLS.md` P28).

```sh
sh tools/validate/probe-flow-transitions-rules.sh   # prove each refusal bites
```

The probe plants a `NavDisplay` call that states nothing — before the module's real call, so
it cannot borrow that call's arguments — and takes the source root away. Three cases, each
scoped to section 18's own output.

## Check 19 keeps a closing sheet on screen while it leaves

`FlowHost`'s `when` has no subject, so its branches are read top to bottom and the first true
one wins. Two of them are true at once for a sheet whose flow has just closed:
`entry is FlowEntry.Sheet` and `routes.isEmpty()`. `Flow.close` empties the stack in one step
and the sheet drawn from it still has a slide to run, so the order of those two lines is the
difference between a sheet that slides away and a sheet that vanishes
(`docs/IMPLEMENTATION-PITFALLS.md` P38). It vanished: a person on a Galaxy Z Flip4 saw the X,
the system back and the scrim each remove the sheet instantly.

No assertion in this repository reads it either. Branch order is not a value a test can read,
the JVM suite has no Compose runtime to compose the host in, and a Maestro cell waits for an
element to appear or to go and never asks how it moved — the 35 device cells are green under
either order.

The check compares two line numbers with `grep -nE`, and neither expression uses `?` or `+`
(`docs/IMPLEMENTATION-PITFALLS.md` P28). Finding neither branch, or only one, is a failure
rather than a clean read.

```sh
sh tools/validate/probe-sheet-exit-rules.sh   # prove each refusal bites
```

The probe MOVES the empty-stack line above the sheet branch with awk — a delete and an insert,
because a sed replacement carrying a newline is the spelling that differs between GNU and BSD —
and takes the source file away. Three cases, each scoped to section 19's own output.

## Check 2 replaced a Step 1 prohibition

Step 1 failed if a Gradle wrapper existed at all, because the baseline was undecided and
a wrapper jar or checksum would have been fabricated. D5 decided the baseline, so the
rule became stronger rather than weaker: the committed jar must be byte-identical to the
artifact gradle.org publishes for Gradle 9.5.1, and the distribution must carry the
published checksum. `gradle/wrapper/WRAPPER-PINS.json` records where each checksum came
from.
