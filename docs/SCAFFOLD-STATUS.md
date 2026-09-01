# Scaffold status

**Step 2 of a staged bootstrap. A vertical slice on a scaffold, not an SDK.**

## What this repository is

A local, uncommitted repository that now compiles on both platforms and proves one
narrow thing end to end: a pinned contract bundle generates Swift and Kotlin clients in
one run, and both platforms produce identical canonical bytes, digests and errors for
the same fixture vectors.

## What it is not

- Not an SDK. There is a transport boundary, a session, one execute path, key custody
  above them, and the two provider adapters that obtain a native sign-in token for
  enrollment — and nothing above that: local persistence and a hybrid bridge do not
  exist at all, their modules having been dropped from the train after `0.1.0-alpha.3`
  rather than kept as empty reservations.
- **Not verified against a deployed service.** Both SDKs complete a real HTTP round trip
  against `tools/reference-server` and against the SPFN primitives `04-mobile-contract-dev`
  server, both running the pinned upstream export. The second is the canonical
  implementation rather than a second reading of the same text, which is stronger evidence
  than this repository could produce alone — but it ran on localhost from a developer
  checkout. No deployed service has been contacted.
- Not released. No commit, no tag, no artifact, no registry entry, no account.
- Not supported. No distribution channel — SwiftPM, Maven or CocoaPods — is promised.
- Not reviewed. Step 3 is a fresh independent review; it has not happened.

## What Step 2 added

| Area | State |
| --- | --- |
| Toolchain baseline (D5) | applied: Swift 6 language mode, iOS 16 / macOS 13, Gradle 9.5.1, AGP 9.2.1, Kotlin 2.4.10, JDK 21, minSdk 24 / compileSdk 36 |
| Gradle wrapper | committed, distribution and jar pinned to checksums published by gradle.org |
| Android modules | five modules — the Apple adapter is iOS-only — all compiling, four running unit tests |
| Contract bundle | an SPFN primitives export, pinned by real SHA-256 at commit `d31aa9a1`, with the exporter's evidence vendored beside it |
| Codegen | `tools/contract-codegen` generates both clients from the bundle, deterministically |
| Conformance | shared fixtures under `Contracts/fixtures/`, consumed by both test suites |
| Reference server | `tools/reference-server` implements the pinned contract locally; both SDKs complete a real HTTP round trip against it |
| Integration gate | `sh tools/reference-server/run-integration.sh` runs the same five-case matrix on both platforms and fails when a suite skipped instead of running |
| Dependency verification | 2400+ lines of real, network-fetched SHA-256 checksums |

## Deliberate absences

Each of these is missing because supplying it would mean inventing an approval.

| Absent | Why |
| --- | --- |
| Any exchange with a deployed service | the round trip is proved against `tools/reference-server` and against the SPFN primitives `04-mobile-contract-dev` server, both running the pinned upstream export. Pointing either SDK at a deployed SPFN service is separate work |
| Generated per-operation call descriptors | the execute path is generic over request and response. The three operations are described by hand in the test suites, so what the generator will own stays visible instead of being pre-empted here |
| Real CODEOWNERS handles | teams and required-review enforcement are undecided |
| A license | not selected |
| Pinned CI action SHAs | pinning by SHA needs a network lookup per action; workflows use no third-party action at all |
| Filled-in `COMPATIBILITY.md` support rows | custody probes and partial Android lifecycle runs are device evidence, not the complete gates a support commitment requires |
| Publication configuration | no registry, coordinate, credential or signing identity exists |

## How honesty is enforced

`tools/validate/validate.sh` fails if any of the following becomes untrue:

- the committed Gradle wrapper jar differs from the artifact gradle.org publishes for
  the pinned version, or the distribution checksum is not the published one
- the contract lock claims an upstream CI export without upstream evidence on disk
- the pinned digest is not the real SHA-256 of the bundle it names
- a fixture digest drifts from `Contracts/fixtures/MANIFEST.json`
- a generated source file names a digest the lock does not pin, or a hand-written file
  appears in a generated directory
- publication is enabled, or a dependency repository outside the approved three appears
- redirect-based auth vocabulary appears in the API surface, contract data, examples or CI
- the generated CocoaPods fixture drifts from its generator
- the Swift targets, Android modules and podspec subspecs disagree with `tools/module-graph.json`
- an iOS or Android compatibility row claims support
- CODEOWNERS gains an invented owner, or a binary appears that is not the verified wrapper jar

`sh tools/reference-server/run-integration.sh` adds one more, which the validator cannot
reach because it needs a socket: an integration suite that skipped every case is reported
as a failure rather than as a pass.

## Step boundaries

| Step | Scope | State |
| --- | --- | --- |
| 0 | topology and contract baseline | done, approved |
| 1 | local uncommitted skeleton and offline validation harness | done |
| 2 | first pinned contract bundle, `clientProofV1` vertical slice, dual codegen, conformance | **this** |
| 3 | fresh independent review and fix iterations | not started |
| 4 | first commit and push, after a person approves the exact diff and evidence | not started |
| 5 | release candidate validation and distribution, under separate approval. D17 is resolved: the bundle is an SPFN primitives export pinned at `d31aa9a1` | not started |
