# `ui-codegen` — the screen scaffold generator

One JSON spec in, two app scaffolds and a case table out.

    ./gradlew :ui-codegen:spfnGenerateUi   # rewrite everything below
    ./gradlew :ui-codegen:spfnUiVerify     # fail if any of it is not up to date (wired into `check`)

Inputs — two files, the repository-relative path of one of them, and the lock that
chooses the other:

- `examples/ui-spec/device-approval.json` — the screens, written by a person. Its fields
  and its six refusals are `examples/ui-spec/SCHEMA.md`. Its PATH is an input too: every
  generated header prints it.
- `Contracts/spfn-mobile-contract.json` — the pinned bundle, read through
  `:contract-codegen`'s own reader rather than a second copy of it.
- `Contracts/upstream.lock.json`'s `contract` block — it names the bundle file and the
  digest the bundle's bytes must have, so it decides which bytes the run reads and whether
  the run happens at all. Nothing of it reaches the output directly.

Outputs:

| Where | What |
| --- | --- |
| `examples/ios-swiftui/Generated/` | the SwiftUI app's services, flow, screen models, use case and view skeletons |
| `examples/android-compose/src/main/kotlin/…/generated/` | the same seven things in Kotlin |
| `examples/ui-spec/generated/device-approval.cases.{json,md}` | the case table both runners read |
| `examples/ui-spec/generated/flows/<cell>.yaml` | one Maestro flow per runnable cell |

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
host name, no absolute path. `SpecRefusalTest` generates twice and compares.

**Where the expectations come from.** The case table is derived from the rule table in
`src/main/kotlin/xyz/superfunction/spfn/uicodegen/Rules.kt` and the spec's shape; the
screen models are derived from the spec. The two derivations meet in the example app's
unit suite, which drives the models against the table. A table derived from the models it
checks would prove only that the code equals itself (P10).

**Stale files.** A generated directory holds generated files and nothing else. `write`
deletes what is no longer generated and says so; `verify` fails on it. The same rule
`:contract-codegen` applies to its own output.

## What it is not

Not an SDK module, never published, and not distributed. It lives inside the JDK/Gradle
toolchain Android already requires, so the repository does not acquire a second one. It is
repo-internal by decision: a consumer app writes its own spec against `SCHEMA.md` and the
generator is not something they run.
