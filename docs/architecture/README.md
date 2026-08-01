# Architecture

Scaffold-level only. The approved design lives in the topology decision artifact
`2026-07-27-spfn-mobile-sdk-repository-topology-decision.html`; this page records what
the skeleton actually implements.

## Module graph

`tools/module-graph.json` is the single source of truth. Four representations must
agree with it, and the validator checks all four:

| Representation | File |
| --- | --- |
| SwiftPM targets and products | `Package.swift` |
| Gradle projects | `settings.gradle.kts`, `android/*/build.gradle.kts` |
| CocoaPods fixture subspecs | `tools/cocoapods-compat/generated/…podspec` |
| Source directories | `Sources/*`, `android/*/src/main/kotlin` |

| Swift target | Android module | Depends on |
| --- | --- | --- |
| `SPFNCore` | `spfn-core` | — |
| `SPFNGenerated` | `spfn-generated` | core |
| `SPFNAuth` | `spfn-auth` | core |
| `SPFNPersistence` | `spfn-sync` | core |
| `SPFNHybrid` | `spfn-hybrid` | core, auth |

The `SPFNPersistence` / `spfn-sync` name asymmetry comes from the approved layout and is
an open decision (D10), not an oversight. The dependency edges survived the Step 2
vertical slice unchanged, so D13 stands as proposed rather than revised. The conformance
suite needs the generated client from inside the auth module, but only at test time; the
main edges are still exactly what `tools/module-graph.json` declares.

## Contract import model

SPFN primitives owns the canonical route DSL, schemas and the `clientProofV1` server
invariant. It exports an immutable, digest-pinned bundle. This repository imports that
bundle and generates Swift and Kotlin clients from it in one run.

**That export does not exist yet.** Step 2 needed something to generate from, so it
authored a development bundle inside this repository and pinned its real digest. The
mechanism is complete and exercised end to end; only the producer is missing. Open
decision D17 tracks the replacement, and `tools/validate/validate.sh` refuses any lock
that claims an upstream export without upstream evidence on disk.

There is deliberately no cross-repository atomic commit. The model is two-phase: the
producer releases an immutable compatible contract first, then the consumer imports it
by digest. Server and deployed SDKs are never replaced simultaneously in reality, so a
protocol that pretends otherwise would be lying about the operational model.

A breaking contract never breaks an existing mobile release. Primitives publishes a new
contract major with an overlap window, mobile adds support for it, and only then is
removing the old contract approved separately.

## Toolchain boundary

Node, pnpm and Turbo are not the root build system here. Contract codegen is a
non-published Kotlin/JVM tool inside the JDK and Gradle toolchain Android already
requires, so the repository does not acquire a second toolchain. It is registered as
`:contract-codegen` and excluded from every SDK-module check, because it is a build tool
rather than something anyone links against.

One tool sits outside that boundary on purpose:
`Contracts/fixtures/derive-expected-values.py` derives the expected conformance values
using only the Python standard library. It is not part of any build, and its
independence from both SDKs is precisely what makes the fixtures evidence rather than a
restatement.

## Where the algorithms live

Canonical serialization (SPFN-CANON-JSON-1), digests and the proof input
(SPFN-PROOF-INPUT-1) are hand-written once per platform in `SPFNCore`/`spfn-core` and
`SPFNAuth`/`spfn-auth`. Generated code is a listing of the contract — types, operations,
error codes — and contains no logic. Reviewing a contract change is reading a diff of
names and types; reviewing an algorithm change is reading four files that rarely move.
