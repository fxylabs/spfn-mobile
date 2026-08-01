# Scaffold status

**Step 2 of a staged bootstrap. A vertical slice on a scaffold, not an SDK.**

## What this repository is

A local, uncommitted repository that now compiles on both platforms and proves one
narrow thing end to end: a pinned contract bundle generates Swift and Kotlin clients in
one run, and both platforms produce identical canonical bytes, digests and errors for
the same fixture vectors.

## What it is not

- Not an SDK. There is no transport, no persistence, no bridge, no key custody.
- Not released. No commit, no tag, no artifact, no registry entry, no account.
- Not supported. No distribution channel — SwiftPM, Maven or CocoaPods — is promised.
- Not reviewed. Step 3 is a fresh independent review; it has not happened.
- **Not connected to a real server contract.** The pinned bundle was written here.

## What Step 2 added

| Area | State |
| --- | --- |
| Toolchain baseline (D5) | applied: Swift 6 language mode, iOS 16 / macOS 13, Gradle 9.5.1, AGP 9.2.1, Kotlin 2.4.10, JDK 21, minSdk 24 / compileSdk 36 |
| Gradle wrapper | committed, distribution and jar pinned to checksums published by gradle.org |
| Android modules | all five compile, and two run unit tests |
| Contract bundle | pinned by real SHA-256, with provenance recorded as locally authored |
| Codegen | `tools/contract-codegen` generates both clients from the bundle, deterministically |
| Conformance | shared fixtures under `Contracts/fixtures/`, consumed by both test suites |
| Dependency verification | 2400+ lines of real, network-fetched SHA-256 checksums |

## Deliberate absences

Each of these is missing because supplying it would mean inventing an approval.

| Absent | Why |
| --- | --- |
| An upstream-exported contract | SPFN primitives has no mobile contract export tooling yet. The bundle here is hand-authored, says so in its own text, and is tracked as open decision D17 |
| Real CODEOWNERS handles | teams and required-review enforcement are undecided |
| A license | not selected |
| Pinned CI action SHAs | pinning by SHA needs a network lookup per action; workflows use no third-party action at all |
| Filled-in `COMPATIBILITY.md` support rows | a build baseline is not device evidence, and a compatibility matrix is read as a support commitment |
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

## Step boundaries

| Step | Scope | State |
| --- | --- | --- |
| 0 | topology and contract baseline | done, approved |
| 1 | local uncommitted skeleton and offline validation harness | done |
| 2 | first pinned contract bundle, `clientProofV1` vertical slice, dual codegen, conformance | **this** |
| 3 | fresh independent review and fix iterations | not started |
| 4 | first commit and push, after a person approves the exact diff and evidence | not started |
| 5 | release candidate validation and distribution, under separate approval. **Blocked on D17**: upstream export tooling must replace the dev bundle first | not started |
