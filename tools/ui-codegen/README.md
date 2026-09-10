# `ui-codegen` — the screen scaffold generator

One screen spec in, one app scaffold out per **target**.

    ./gradlew :ui-codegen:spfnGenerateUi          # the example apps, the case table and the flows
    ./gradlew :ui-codegen:spfnGenerateHarnessUi   # the harness apps
    ./gradlew :ui-codegen:spfnUiVerify            # fail if the example half is not up to date
    ./gradlew :ui-codegen:spfnHarnessUiVerify     # the same for the harness half

Both verify tasks are wired into `check`, and they are two tasks rather than one so a
failure names WHICH app's scaffold drifted.

Inputs — a place, the bundle, the repository-relative path of the place, and the lock
that chooses the bundle:

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
- `Contracts/upstream.lock.json`'s `contract` block — it names the bundle file and the
  digest the bundle's bytes must have, so it decides which bytes the run reads and whether
  the run happens at all. Nothing of it reaches the output directly.

## Targets

A **target** is which app a run writes into: a name, a Swift output root, a Kotlin output
root, a Kotlin package, an application id, and — optionally — a root for the case table
and the flows. Nothing under `src/main` names an app; the two shipped targets are argument
lists in `build.gradle.kts` and a third would be a third task there.

| Target | Swift root | Kotlin root and package | Table and flows |
| --- | --- | --- | --- |
| `example` | `examples/ios-swiftui/Generated/` | `examples/android-compose/src/main/kotlin/…/example/generated/` | `examples/ui-spec/generated/` |
| `harness` | `tools/harness/ios/GeneratedUI/` | `tools/harness/android/src/main/kotlin/…/harness/generated/` | none |

Each target gets the same nine files per platform: the service protocol and its default
implementation, the route enum with its flow and flow host, one model per screen plus any
use case, the shared screen failure, one view skeleton per screen, and the container.

The view skeleton is the one file a flow can take back. A flow whose `views` are
`authored` has its screens written by hand from its contract document, and this generator
then neither writes those files nor deletes them as stale — the deletion rule below would
otherwise eat the work on the next run. Section 21 of `tools/validate/validate.sh` is what
holds an authored view to being written and a reference view to being generated; nothing
here can, because an authored path is not a file this generator emits.

The case table and the Maestro flows go to the ONE target that declares a table root. They
name cells, fixtures and expectations, and `examples/` holds the only app that installs
those fixtures; the harness drives the same screens against a live reference server
through `tools/harness/flows/d1-approve.yaml` and its two siblings, so a second copy of
the table under `tools/harness/` would be claiming coverage nothing provides.

The harness's Swift root is `GeneratedUI/` and not `Generated/` on purpose:
`tools/harness/ios/Generated/` is XcodeGen's, holding the Info.plist and the entitlements,
and this generator DELETES every file it did not emit from a directory it owns.

## What holds it together

**The digest gate.** The bundle's sha256 is recomputed and compared with the lock AND
with the spec's own `contract.manifestSha256`. Both, because they are different mistakes:
a bundle edited without re-pinning, and a spec written against a bundle that is no longer
the pinned one. This generator is the fourth reader of that digest and, like the contract
generator, it is a consumer that recomputes and compares — never a place the value is
edited (`docs/IMPLEMENTATION-PITFALLS.md` P2).

**The operation gate.** An operation named in `services` must be one of the descriptor
names the contract generator emits, derived with `Names.lowerCamel` — the same function
`SwiftEmitter` and `KotlinEmitter` name their descriptors with. Two copies of that rule
would drift, and the drift would arrive as a compile error in a file nobody wrote.

**Determinism.** Output is a pure function of the spec bytes, the bundle bytes, the spec's
repository-relative path and the lock's contract block: sorted iteration, no timestamp, no
host name, no absolute path. For a directory the spec bytes are the pieces, in name order,
each framed with its own name inside the directory — a walk in the filesystem's order would
hash differently on a Mac than on the CI runner. `SpecRefusalTest` generates twice and
compares.

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
