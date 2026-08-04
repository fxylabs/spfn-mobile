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

Three more read it rather than restate it, so they cannot drift at all: the podspec
fixture generator, `tools/rc-verify/rc-verify.sh` and
`tools/rc-verify/verify-published.sh` derive their consumer dependency lists, imports
and Maven coordinate lists from the graph.

| Swift target | Android module | Depends on |
| --- | --- | --- |
| `SPFNCore` | `spfn-core` | — |
| `SPFNGenerated` | `spfn-generated` | core |
| `SPFNAuth` | `spfn-auth` | core |
| `SPFNClient` | `spfn-client` | core, auth, generated |

A module appears here only once it carries an implementation. `SPFNPersistence` /
`spfn-sync` and `SPFNHybrid` / `spfn-hybrid` were declared in the Step 1 scaffold from
the approved layout, never implemented, and published as empty coordinates through
`0.1.0-alpha.3`; they were dropped rather than kept as reservations. That also settles
the `SPFNPersistence` / `spfn-sync` name asymmetry (D10) by removing both names. One
edge moved since Step 2: the client module
gained auth and generated when the session arrived, because a session signs a
`clientProofV1` proof over a generated operation and its generated request type. That is
the revision D13 left room for. The conformance suite needs the generated client from
inside the auth module, but only at test time; every main edge is still exactly what
`tools/module-graph.json` declares.

## How a module is added

Five rules, confirmed 2026-08-04 after the empty persistence and hybrid coordinates were
dropped. They exist so the next capability arrives as behaviour rather than as a name.

**A module is added with an implementation or not at all.** No reservations, no stub
entry points that throw. `validate.sh` refuses unimplemented-entry-point vocabulary
(`notImplemented`, `TODO`, `plannedStep`, …) anywhere under `Sources` or
`android/*/src/main`; examples and tools are excluded, because a placeholder example is
honest about being one.

**The default shape of an extension is an injected protocol, not a module.** A new
capability starts as a protocol plus a default implementation inside the module that
uses it — `SPFNKeyProvider`, `SPFNKeyStore`, `SPFNTransport` and `SPFNClock` all live in
`SPFNClient` for exactly this reason. A separate module is justified when it drags in a
heavy or optional dependency, or when most consumers demonstrably will not link it. That
is why `@spfn/storage` is its own package upstream: the AWS and GCS SDKs are optional.

**One release train stays one release train.** Every module shares the `VERSION` value
in lockstep (D9), so an untouched module still ships a new version. SwiftPM is what makes
this the simple choice — one repository is one package is one version, and per-module
versions would mean splitting the repository. Whether to split later is not decided in
advance: the condition would be a guess, and a documented condition reads as a promise.

**`tools/module-graph.json` is the only place a module is named.** Everything else
derives from it: the SwiftPM manifest and Gradle settings are checked against it, and the
podspec generator, `rc-verify.sh` and `verify-published.sh` read it directly. Adding a
module leaves exactly two hand edits — one symbol touch in each consumer smoke, since
only a person knows which symbol proves a module non-empty, and the CODEOWNERS sample
block.

**An extension that needs a server round trip waits for the contract.** Local persistence
with server sync and push both need operations that the pinned bundle does not declare,
so the module cannot exist before the operation does. Upstream is the same story from the
other side: `@spfn/notification` names `push` in its channel union with no channel behind
it.

## Contract import model

SPFN primitives owns the canonical route DSL, schemas and the `clientProofV1` server
invariant. It exports an immutable, digest-pinned bundle. This repository imports that
bundle and generates Swift and Kotlin clients from it in one run.

**That export exists as of 2026-08-02.** Step 2 needed something to generate from, so it
authored a development bundle here and pinned its real digest; SPFN primitives has since
built the exporter, and the bundle is now a byte copy taken at an exact commit (D17,
resolved). `tools/validate/validate.sh` no longer refuses upstream claims — it checks them
against `Contracts/upstream-provenance.json`, the file the exporter wrote, field by
field, and refuses evidence naming this repository as the source.

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

`tools/reference-server` is registered the same way, as `:reference-server`, and for the
same reason: it is a test fixture that implements the pinned contract so both SDKs can be
driven over real HTTP, not something anyone links against. Its `main` source set has zero
external dependencies — the HTTP layer is the JDK's own `com.sun.net.httpserver` — and it
compiles the canonical serializer, the proof input and the generated contract listing
straight out of the Android modules' source directories rather than reimplementing them.
An Android library cannot be a dependency of a JVM module, and a second copy of
SPFN-CANON-JSON-1 would turn the round trip into two copies agreeing with each other.

One tool sits outside that boundary on purpose:
`Contracts/fixtures/derive-expected-values.py` derives the expected conformance values
using only the Python standard library. It is not part of any build, and its
independence from both SDKs is precisely what makes the fixtures evidence rather than a
restatement.

## Where the independence in the integration run comes from

The reference server shares source with the Android modules, so a round trip against it
cannot be evidence that the two ends agree about the contract — they were built from one
reading of it. Three things supply the independence instead, in ascending order of what
they are worth:

| Source | What it is independent of |
| --- | --- |
| `Contracts/fixtures/derive-expected-values.py` | both SDKs; it is a third implementation of the algorithms |
| the Swift integration suite | the server's codebase; it crosses the same wire from another language and another process |
| an external target given to `run-integration.sh` | this repository; the server is a reading of the contract nobody here wrote |

The third is why the runner takes `SPFN_INTEGRATION_TARGET_URL`, or a launch file, and
runs the same ten cases against a server it did not start. The one thing the mode must
never do is fall back to the local server when the target cannot be used: the run would
report the strongest evidence available here while producing the weakest. So every way of
naming a target that cannot be reached is a failure, in the runner and in the suite alike.

## Where the algorithms live

Canonical serialization (SPFN-CANON-JSON-1), digests and the proof input
(SPFN-PROOF-INPUT-1) are hand-written once per platform in `SPFNCore`/`spfn-core` and
`SPFNAuth`/`spfn-auth`. Generated code is a listing of the contract — types, operations,
error codes — and contains no logic. Reviewing a contract change is reading a diff of
names and types; reviewing an algorithm change is reading four files that rarely move.

The wire mapping is the one place a contract detail is restated by hand rather than
generated: the generator emits types and operations, and a session has to name an HTTP
header at compile time. `SPFNWireHeaders`/`SpfnWireHeaders` holds that table, and both
conformance suites read `wireMapping` out of the pinned bundle and fail if the table
drifts from it — so the restatement is checked rather than trusted.

## Three layers in the client module

| Layer | Knows | Does not know |
| --- | --- | --- |
| transport | how to send one HTTP request | what a 401 means, whether to retry |
| session | how to open a session and prove a request | what a server answer to an operation means |
| execute | what an answer means and what a refusal is worth retrying | how either of the two below works |

Each layer refuses the vocabulary of the one above it, and that is what makes the rules
checkable. The transport cannot retry, so an attempt count is exactly the number of calls
the layers above chose to make. The session does not classify, so every refusal is
classified in one place. And because `execute` is the only way to send a request, a rule
it states holds for every operation rather than for the ones somebody remembered.

Retry lives at the top for the same reason. Re-sending needs two things no lower layer
has: knowing that the request was refused rather than lost, and knowing what changed
since. An auth refusal is the only case where both are true, so it is the only case that
is retried, and it is retried once.
