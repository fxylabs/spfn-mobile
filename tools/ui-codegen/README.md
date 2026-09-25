# `ui-codegen` — the screen scaffold generator

One screen spec in, one app scaffold out per **target**.

    ./gradlew :ui-codegen:spfnGenerateUi          # the example apps, the case table and the flows
    ./gradlew :ui-codegen:spfnGenerateHarnessUi   # the harness apps
    ./gradlew :ui-codegen:spfnUiVerify            # fail if the example half is not up to date
    ./gradlew :ui-codegen:spfnHarnessUiVerify     # the same for the harness half

Both verify tasks are wired into `check`, and they are two tasks rather than one so a
failure names WHICH app's scaffold drifted.

Inputs — a place, the bundle, the repository-relative path of the place, the pin that
chooses the bundle, and this generator's own version:

- `examples/ui-spec` — the screens, written by a person. A DIRECTORY: `device-approval.json`
  inside it holds the eight showcase flows, and each `contracts/*.md` holds one flow's part
  of the spec in the `json spfn-ui` block at its end. Every piece is read whole by the one
  reader and the pieces are merged, refusing any name two of them declare — one flow lives
  in one place. The fields and the refusals are `examples/ui-spec/SCHEMA.md`; what a
  document's other sections say is `examples/ui-spec/CONTRACT.md`. A single `.json` file is
  still a legal argument and is read as it always was. The PATH is an input too: every
  generated header prints it, and `specSha256` is the digest of the pieces.
- `Contracts/spfn-mobile-contract.json` — the pinned bundle, read through
  `:contract-codegen`'s own reader rather than a second copy of it.
- the pin — `Contracts/upstream.lock.json`'s `contract.bundlePath` names the bundle file
  and `Contracts/upstream-provenance.json`'s `contract` block states the digest those bytes
  must have, so between them they decide which bytes the run reads and whether the run
  happens at all. Nothing of either reaches the output directly.
- `gradle.properties`' `spfn.version` — this generator's version, handed to the run as
  `-Dspfn.ui-codegen.version` by the Gradle task and printed in every header. A run that is
  not given one refuses: a default would be the `0.1.0-dev` constant again, which claimed a
  generator this repository does not ship.

## Targets

A **target** is which app a run writes into: a name, a Swift output root, a Kotlin output
root, a Kotlin package, an application id, and — optionally — a root for the case table
and the flows. Nothing under `src/main` names an app; the two shipped targets are argument
lists in `build.gradle.kts` and a third would be a third task there.

| Target | Swift root | Kotlin root and package | Table and flows |
| --- | --- | --- | --- |
| `example` | `examples/ios-swiftui/Generated/` | `examples/android-compose/src/main/kotlin/…/example/generated/` | `examples/ui-spec/generated/` |
| `harness` | `tools/harness/ios/GeneratedUI/` | `tools/harness/android/src/main/kotlin/…/harness/generated/` | none |

Each target gets the same KINDS of file per platform, one per thing the spec declares: a
service protocol and its default implementation per service, a route enum with its flow and
flow host per flow, a model per screen plus a use case for each screen that asks for one,
one shared screen failure, a view skeleton per screen a flow has not taken back, and the
container. How many that is, is the spec's answer and not a constant: the example target
generates 41 files per platform from nine flows, and the harness — narrowed to one flow —
generates 9 (`spfnUiVerify` and `spfnHarnessUiVerify` read back both).

The view skeleton is the one file a flow can take back. A flow whose `views` are
`authored` has its screens written by hand from its contract document, and this generator
then neither writes those files nor deletes them as stale — the deletion rule below would
otherwise eat the work on the next run. What `verify` still asks of an authored view of a
flow the target draws is that it exists and does not carry the generated header — a
skeleton left in place is a screen nobody wrote (`handWrittenProblems` in `Main.kt`).

The case table and the Maestro flows go to the ONE target that declares a table root. They
name cells, fixtures and expectations, and `examples/` holds the only app that installs
those fixtures; the harness drives the same screens against a live reference server
through `tools/harness/flows/d1-approve.yaml` and its two siblings, so a second copy of
the table under `tools/harness/` would be claiming coverage nothing provides.

The harness's Swift root is `GeneratedUI/` and not `Generated/` on purpose:
`tools/harness/ios/Generated/` is XcodeGen's, holding the Info.plist and the entitlements,
and this generator DELETES every file it did not emit from a directory it owns.

## What holds it together

**The digest gate.** The bundle's sha256 is recomputed and compared with the upstream
evidence's `contract.bundleSha256` AND with the spec's own `contract.manifestSha256`. Both,
because they are different mistakes: a bundle edited without re-pinning, and a spec written
against a bundle that is no longer the pinned one. This generator reads that digest through
`:contract-codegen`'s `ContractPin`, the same reader the contract generator uses, and like
it is a consumer that recomputes and compares — never a place the value is edited
(`docs/IMPLEMENTATION-PITFALLS.md` P2).

**The operation gate.** An operation named in `services` must be one of the descriptor
names the contract generator emits. The legal set is derived when the spec is read, with
`Names.lowerCamel` — the contract generator's own naming function — and the emitters then
write the spec's string through unchanged (`SPFNGeneratedCalls.<operation>`). So the gate is
the only thing between a typo in the spec and a descriptor that does not exist, and the set
is derived rather than listed because a second copy of the rule would drift and the drift
would arrive as a compile error in a file nobody wrote.

**Determinism.** Output is a pure function of the spec bytes, the bundle bytes, the spec's
repository-relative path, the pinned contract and this generator's own version:
sorted iteration, no timestamp, no host name, no absolute path. The version is on that list
for the reason the path is — every header prints it — and it is read from
`gradle.properties`' `spfn.version` and handed over by the Gradle task
(`-Dspfn.ui-codegen.version`), because a version written in the generator as well was two
versions and the two disagreed. For a directory the spec bytes are the pieces, in name order,
each framed with its own name inside the directory — a walk in the filesystem's order would
hash differently on a Mac than on the CI runner. A document contributes its `json spfn-ui`
block and not its prose: the prose is not an input to the generator, the block is, so
rewording a sentence leaves every generated header where it stood. `SpecRefusalTest`
generates twice and compares.

**Where the expectations come from.** The case table is derived from the rule table in
`src/main/kotlin/xyz/superfunction/spfn/uicodegen/Rules.kt` and the spec's shape; the
screen models are derived from the spec. The two derivations meet in the example app's
unit suite, which drives the models against the table. A table derived from the models it
checks would prove only that the code equals itself (P10).

**Stale files.** A generated directory holds generated files and nothing else. `write`
deletes what is no longer generated and says so; `verify` fails on it. The same rule
`:contract-codegen` applies to its own output. The one exemption is an authored flow's
views, which are named by the emitters rather than recognised by their contents: a file
exempted for lacking a generated header would exempt a generated file somebody had edited
the header out of, which is the drift this gate exists to catch.

## What it is not

Not an SDK module, never published, and not distributed. It lives inside the JDK/Gradle
toolchain Android already requires, so the repository does not acquire a second one. It is
repo-internal by decision: a consumer app writes its own spec against `SCHEMA.md` and the
generator is not something they run.
