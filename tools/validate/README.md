# Offline validator

```sh
sh tools/validate/validate.sh
```

Needs POSIX `sh`, `grep`, `sed`, `awk`, `find` and a SHA-256 utility (`shasum` or
`sha256sum`). No network, no package manager, no toolchain. Exit code 0 means every
check passed.

## What it checks

The section numbers are stable identifiers, so the gaps are real: a missing number is a
check that moved to a stronger home or was dropped (see "Where the other checks went").

| # | Check |
| --- | --- |
| 1 | the documents a public repository owes its readers exist, and every `sh tools/…` script a workflow runs exists |
| 2 | the committed Gradle wrapper jar and distribution match the checksums gradle.org publishes |
| 3 | no fabricated binaries, credentials, keystores or private keys |
| 4 | `VERSION` agrees with Swift, Kotlin, Gradle, the podspec and the changelog |
| 5 | contract lock discipline, in every direction |
| 6 | the auth allowlist is exactly `clientProofV1`; no WebView or JavaScript-bridge surface |
| 7 | publication disabled; dependency sources limited to the approved three repositories, the gated staging target, verified checksums and graph-declared Swift packages |
| 7a | build scripts hold no credential, literal username or password, signing outside the root or remote URL, and the root's signing and staging-gate pins hold; every workflow triggers only as declared, a gate runs only `tools/ci` scripts, only `publish-central.yml` names secrets — allowlisted ones, towards allowlisted hosts — and inputs reach the shell only through `env` |
| 8 | module graph coherence: SwiftPM products and edges, Gradle mappings and edges, iOS-only and Linux-absent declarations, module counts, no stubs, no silenced deprecations |
| 9 | generated sources are traceable: every one declares itself generated and names the digest the lock pins; the CocoaPods fixture is what its generator writes from the graph |
| 10 | the D5 toolchain baseline is declared explicitly rather than inherited |
| 13 | the `ui` module's state, flow, host, paged and form vocabularies are the same names on both platforms, and `SPFNUI` never reaches the SwiftUI dismiss environment value |
| 14 | the apps that consume the scaffold hold the generated boundary: descriptors only in generated services, no `dismiss`, every cell covered, every cell seedable |
| 15 | the visual vocabulary — tokens, strings, components, the minimum touch target and the theme keys — is the same set on both platforms, and no UI source reads the tokens past the theme |
| 20 | no Button in `SPFNUI` is styled `.plain` |
| 23 | no provider adapter logs a token or reads a profile field |
| 24 | every action a workflow uses is on the SHA-pinned list |

## What it does not check

It compiles nothing, and it never reports a result it did not produce. These are
separate commands with separate evidence, and a check one of them makes is not repeated
here:

```sh
swift build && swift test                 # tools/ci/swift.sh on Linux
sh tools/ci/android.sh                    # unit tests, lint (tools/ui-lint included), codegen verify
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
sh tools/device-receipts/receipt-gate.sh  # before a release, by a person (COMPATIBILITY.md)
```

## Where the other checks went

On 2026-09-25 the validator was cut back to the checks nothing else can make. Most of what
left were regular expressions over UI code, which broke whenever that code changed shape;
each went to the strongest home that would hold it.

| Was | Rule | Now |
| --- | --- | --- |
| 1 | a Swift/Gradle manifest, build script, source root or generated directory exists | `swift build` and any `./gradlew` run fail without it |
| 1 | the wrapper, its pins, `VERSION`, the lock, the bundle, the module graph, the catalogue and the verification metadata exist | the section that reads each one (2, 4, 5, 7, 8) fails when it is missing |
| 1 | the tool scripts, probes, CI scripts, docs, examples READMEs and receipt runs exist | the check that a workflow's scripts exist; the rest dropped — a missing tool fails when it is run |
| 7 | the Maven group is recorded as Central-verified | the root build `require`s it on every run |
| 7 | no credential-shaped key, credential block, literal username/password, signing configuration, URL literal or `setUrl` in a build script; root signing is in-memory only | kept, in section 7a |
| 7 | the root staging gate's four load-bearing lines | kept: section 7 holds the committed-flag line, section 7a the other three; `tools/validate/probe-publishing-gate.sh` also runs the gate itself |
| 7 | Android dependencies are exactly the graph's per-module allowlist; Swift traits; graph allowances nobody uses | dropped; Gradle dependency verification refuses any artifact `verification-metadata.xml` does not record, and section 7 keeps the Swift package rule |
| 7 | workflow triggers, gate `run:` lines, publish-workflow secrets, hosts, network commands and pushes, no secret in any other workflow, inputs only through `env` | kept, in section 7a; section 1 also holds that every script a workflow runs exists |
| 7 | workflow timeouts, runner images, the "required check" / "NOT A GATE" prose, and the held-for-confirmation upload | dropped (the commit-input validation is kept in 7a); section 24 still holds every action to the SHA-pinned list |
| 8 | every graph target has a Swift source directory; settings `include`s each Android module; each has a build script | `swift build`; Gradle configuration; the edge check fails on a missing script |
| 8 | the podspec's subspecs and edges match the graph | section 9 regenerates the podspec from the graph and refuses any difference |
| 8 | no Apple-only framework (CryptoKit included) is imported unguarded in a Linux-capable module | the Linux `swift build` in `tools/ci/swift.sh` |
| 11 | CODEOWNERS, LICENSE, OPEN-DECISIONS rows, the D11 policy digest and blocklist, COMPATIBILITY rows stay UNRESOLVED | dropped — ownership and status prose (operator decision 2026-09-25) |
| 11 | `tools/verify-server/probe-refusals.sh` passes | dropped from the validator; it is the verify-server's own probe, run with that tool |
| 11 | the device-receipt gate and its probe pass; rc-verify calls the gate | the gate is a manual pre-release command (`COMPATIBILITY.md`), and `tools/rc-verify/rc-verify.sh` still runs it before it verifies a candidate |
| 12 | docs, both libraries and `RELEASE.md` state scaffold status | dropped — status prose |
| 16 | both Android apps declare `android:enableOnBackInvokedCallback="true"` | `PredictiveBackManifestTest` in `:example-compose` and `:harness-android`, which parse the manifest |
| 17 | no pointer input consumes every change it is handed | Android Lint `SpfnBlanketPointerConsumption` (`tools/ui-lint`) |
| 18 | every `NavDisplay` is handed the three `FlowTransitions` | Android Lint `SpfnNavDisplayTransitions` |
| 19 | a closing sheet is still drawn | `flowDrawing` in `FlowHost.kt` is a pure function, held by `FlowDrawingTest` |
| 20 | every plain-styled Button gives its label a hit shape | `RoleButtonStyle` and `HeaderControlStyle` set the hit shape themselves; section 20 keeps only the ban on `.plain` |
| 21 | authored views are written by hand, reference views carry the header | `:ui-codegen:spfnUiVerify` / `spfnHarnessUiVerify` (`handWrittenProblems`), tested in `InvocationTest` |
| 22 | a screen that draws a `PagedView` passes `scroll = false` | Android Lint `SpfnPagedViewInScrollingScreen`, run on `:spfn-ui` and both apps |

## Check 7a is security, so it stays

A build script holding a literal password still builds, and a workflow with an extra
trigger or an input interpolated into its script only misbehaves on the CI service —
after the secret has left. No build, test or lint on this host reads either, so these
rules stayed when the validator was cut back. Each refusal is exercised, in the spellings
that would otherwise slip past, by:

```sh
sh tools/validate/probe-publication-rules.sh   # prove each refusal bites
```

## Check 5 is the reason this validator exists

A placeholder lock can only fail by inventing a value. A **resolved** lock can fail by
inventing provenance, which is worse: a fabricated "exported by upstream CI" record
reads exactly like a real one.

So the rules are asymmetric on purpose. A locally authored bundle may be pinned as long
as it says so: `origin: spfn-mobile-step2-dev-bundle`, `exportedByUpstreamCI: false` and
no 40-hex commit.

An upstream claim is held to more: an exact 40-hex commit, a bundle that labels itself
`UPSTREAM_EXPORT`, and `Contracts/upstream-provenance.json` on disk, naming the same
exporter, still carrying the exporter's own `RECORDED_BY_CONSUMER` placeholder — proof it
was copied rather than edited on the way here — and naming a source repository other than
this one, because a dev bundle dressed up as an export is what that would be.

**The source of a contract value is provenance, and only provenance.** Between 2026-08-02
and 2026-09-18 the lock carried a second copy of the version, major, minor, supported
range and digest, and this section compared the two copies field by field. `lockVersion` 3
removed the copy, and the comparison went with it — along with the two gaps found in it on
2026-08-04, which are not worth fixing once there is nothing to compare. What replaces it
is one closed check: a lock that grows any of those keys back fails, by name.

`sh tools/validate/probe-contract-lock-rules.sh` drives that refusal and the digest rule
against the real validator on temporarily mutated, cp-backed files.

The digest and fixture checks are shared by both resolved states rather than living
inside the dev-bundle branch, so moving the lock upstream cannot quietly drop them.

## Check 13 compares two source trees rather than one

It stays a validator check because the comparison needs both trees at once, and no build,
test or lint on either platform reads the other platform's sources.

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
input away, and plants a `dismiss`, each case scoped to section 13's own output.

## Check 20 is the part of a Swift rule no type can hold

`.buttonStyle(.plain)` hit-tests a button's label over the pixels it drew, so a role button
answered only over its letters and a 20pt header glyph only over itself inside its 44pt
frame (docs/IMPLEMENTATION-PITFALLS.md P39). The fix now lives in the API: SPFNUI's two
button styles, `RoleButtonStyle` and `HeaderControlStyle`, set `.contentShape(Rectangle())`
on the label they are handed, so no call site has to remember it. What a style cannot do is
stop the next button reaching for `.plain` again, and a SwiftUI hit shape is not a value a
test can read — so that one spelling, and `PlainButtonStyle()`, are refused by name.

```sh
sh tools/validate/probe-button-hit-shape-rules.sh   # prove the refusal bites
```

## Check 23 took two rows off a test that could not run everywhere

Rows C8 and C9 of the adapter case table — no adapter logs, no adapter reads a provider's
display fields — lived only in `Tests/SPFNSocialAppleTests/SPFNSocialAdapterSurfaceTests.swift`.
That file sits beside an Apple-only module and is guarded on
`canImport(AuthenticationServices)`, so on Linux the target compiles to an empty module and
the rows reported green by not existing; and it scanned two Swift directories, never the
Android adapter, which is the half with its own logging vocabulary. The check reads all
three trees on every host. The Swift test stays, because it also proves the classification
drops a token out of an error VALUE, which is a call rather than a scan.

Every term is matched as a plain substring, which is what the Swift suite does and is the
decision worth stating: both lists are reached through a receiver — `credential.fullName`,
`android.util.Log.d` — so a word-boundary rule would refuse the bare spelling and admit the
qualified one, which is the spelling somebody reaches for after the bare one is refused.
Comment lines are dropped, because a prohibition has to be describable in the file that
obeys it, and the floor is per directory: two of the three trees hold one file each, so a
missing one would otherwise vanish into a total.

```sh
sh tools/validate/probe-social-surface-rules.sh   # prove each refusal bites
```

The probe appends one line at a time to each adapter source and reads section 23's own two
rows rather than the validator's exit code, so a failure elsewhere cannot report green for
the wrong reason.
Both ends of the comment exclusion are pinned, and the last case takes the source trees
away.

## Check 2 replaced a Step 1 prohibition

Step 1 failed if a Gradle wrapper existed at all, because the baseline was undecided and
a wrapper jar or checksum would have been fabricated. D5 decided the baseline, so the
rule became stronger rather than weaker: the committed jar must be byte-identical to the
artifact gradle.org publishes for Gradle 9.5.1, and the distribution must carry the
published checksum. `gradle/wrapper/WRAPPER-PINS.json` records where each checksum came
from.
