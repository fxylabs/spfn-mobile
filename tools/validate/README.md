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

So the rules are asymmetric on purpose. A locally authored bundle may be pinned, as
long as it says so: `origin: spfn-mobile-step2-dev-bundle`, `exportedByUpstreamCI:
false`, no 40-hex commit, and a `manifestSha256` that is the real digest of the file it
names. A lock claiming an upstream export must produce `Contracts/upstream-provenance.json`
and a real source commit. No such file exists today, so that claim fails immediately —
which is the point.

## Check 11 pins a decision rather than blocklisting its opposite

D11 settled that CocoaPods is not supported and recorded no activation condition on
purpose. The first attempt to hold that shut was a list of forbidden phrasings, and
review took it apart twice: "may be enabled after a separate approval", then "could
return as an optional distribution channel". A policy sentence can be reopened in
unbounded ways, so no enumeration converges.

The gate is therefore a digest. `d11-policy.lock.json` pins the exact text of the
policy section in `tools/cocoapods-compat/README.md`, and any edit to it fails the build
until somebody updates the lock — which makes reopening the decision a visible act
instead of a sentence nobody noticed. `d11-forbidden.ere` survives as a second,
best-effort net over the rest of that file, where prose is legitimate and a digest would
be too rigid.

`probe-d11-guardrail.sh` holds both to their claims: the pinned digest must move on a
one-word rewording, the extraction must stop at the next heading rather than swallowing
the document, fourteen reopening sentences must be caught, twelve descriptive ones must
be spared, and the validator must still read both files instead of carrying its own
copy. The validator runs the probe as part of check 11.

```sh
sh tools/validate/probe-d11-guardrail.sh                  # prove the guardrail
sh tools/validate/probe-d11-guardrail.sh --print-digest   # after an approved edit
```

## Check 2 replaced a Step 1 prohibition

Step 1 failed if a Gradle wrapper existed at all, because the baseline was undecided and
a wrapper jar or checksum would have been fabricated. D5 decided the baseline, so the
rule became stronger rather than weaker: the committed jar must be byte-identical to the
artifact gradle.org publishes for Gradle 9.5.1, and the distribution must carry the
published checksum. `gradle/wrapper/WRAPPER-PINS.json` records where each checksum came
from.
