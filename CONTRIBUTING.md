# Contributing

This repository operates under a strict change policy, decided 2026-08-01
(state decision `01kyyx00a2`):

- **No direct commits to `main`.** Branch protection rejects them, including from
  administrators. Every change lands through a pull request.
- **Every change set is registered and reviewed.** A branch is registered as a
  change set in the project state (`self integration register`), receives a fresh
  cross-model review receipt (`self review ingest`) bound to the exact reviewed
  bytes, and merges only with the owner's explicit approval.
- **Delegated implementation runs as recorded attempts**, never as untracked agent
  sessions: the attempt spool records the plan, capability boundary, model and
  result envelope for every run.
- The GitHub PR is the transport; the project state holds the gates. A green PR
  with no receipt does not merge.
- **Every implementation brief cites the pitfall register.**
  [docs/IMPLEMENTATION-PITFALLS.md](docs/IMPLEMENTATION-PITFALLS.md) routes the
  surfaces a change touches to the traps this repository has already paid for. The
  brief quotes the matching entries; a recurring finding becomes a new entry, or
  sharpens an existing one rather than duplicating it. The number that matters is
  how often a review has to point at something the register already held.

The SDK itself is still in staged bootstrap — transport, persistence and the hybrid
bridge are scaffold stubs (see `docs/SCAFFOLD-STATUS.md`). Strict process, early code.

## Issues and pull requests

GitHub is the transparency surface; the project state holds the gates.

- **Issues** use the provided templates (feature / bug). An issue becomes workable
  only when a maintainer accepts it: the `status:accepted` label AND a registered
  work unit in the project state, as a pair. One without the other starts nothing,
  and no automation turns an external issue into agent work.
- **Pull requests** follow the template: a registered change set id, a fresh
  cross-model review receipt id bound to the exact diff, the verification commands'
  results, and a `Closes #N` reference. A green checklist is necessary, not
  sufficient — the owner's recorded approval is the merge gate.
- **Merges** are squash-only and `main` history is linear.

## Before you change anything

Run the checks:

```sh
sh tools/validate/validate.sh                        # structural validation, offline
swift build && swift test                            # Swift targets and conformance
./gradlew build                                      # Android modules and conformance
./gradlew :contract-codegen:spfnCodegenVerify        # generated sources are up to date
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
sh tools/reference-server/run-integration.sh         # both SDKs against a local server
```

The validator needs no network, no package manager and no credentials. The Gradle
commands need an Android SDK; point `ANDROID_HOME` at it. Gradle itself comes from the
committed wrapper, whose distribution and jar are pinned to checksums published by
gradle.org.

The integration run needs `curl` and binds a loopback port. It is the only check that
sends anything over a socket, and it is deliberately not wired into `./gradlew build`:
the unit gates stay fast, and this one is run on its own. It fails when either suite
skipped rather than ran — see `tools/reference-server/README.md`.

## Rules that the validator enforces

- **Never hand-edit a generated file.** `tools/cocoapods-compat/generated/`,
  `Sources/SPFNGenerated/Generated/` and
  `android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated/` are
  outputs. Change the generator or the input, then regenerate.
- **The module graph has one source of truth.** Adding a Swift target, an Android module
  or a podspec subspec means editing `tools/module-graph.json` and then making the
  SwiftPM manifest, Gradle settings and generated fixture agree.
- **Never invent a value to fill a placeholder.** Digests, commits, provenance, owner
  handles, licenses and signing identities stay unresolved until a person decides them.
  This is the single rule that matters most here.
- **Never claim provenance you do not have.** The pinned bundle is an SPFN primitives
  export and is never edited here. The lock's upstream claim is checked field by field
  against `Contracts/upstream-provenance.json`, the file the exporter itself wrote.
- **Never widen the auth surface.** `clientProofV1` is the only profile. Redirect-based
  browser auth vocabulary anywhere in the API surface fails the build.
- **Never enable publication.** No registry, no credential block, no signing plugin, no
  trunk publication command.
- **No new binaries.** The Gradle wrapper jar is the only one, and only because its
  digest matches the artifact gradle.org publishes.

## Changing the contract

**Not here.** `Contracts/spfn-mobile-contract.json` is a byte copy of an SPFN primitives
export, and editing it is the one thing the lock, the validator and both conformance
suites exist to catch. The contract is changed in primitives
(`packages/auth/src/server/client-proof`), re-exported there, and re-pinned here.

To pin a new export:

1. Copy `contracts/mobile/spfn-mobile-contract.json` and
   `contracts/mobile/upstream-provenance.json` from the primitives commit you intend to
   pin. Copy them, do not adapt them.
2. Update `Contracts/upstream.lock.json`: `source.commit`, `contract.version`, `major`,
   `minor`, `supportedRange`, and `manifestSha256` from
   `shasum -a 256 Contracts/spfn-mobile-contract.json`. Until the digest matches, the
   generator refuses to run — that ordering is the gate, not an obstacle.
3. Regenerate: `./gradlew :contract-codegen:spfnGenerateClients`, then
   `:contract-codegen:spfnCodegenVerify` to prove the output is deterministic.
4. Refresh the fixtures: `python3 Contracts/fixtures/derive-expected-values.py --write`.
   If only the digest moves, the contract facts did not change.
5. Run the validator, both conformance suites, and the integration matrix in
   external-target mode against a primitives dev server.

`Contracts/README.md` carries the same list with the reasoning behind each step.

## Adding a module

1. Add the entry to `tools/module-graph.json` (one object per line — the parsers depend
   on that format).
2. Add the target and product to `Package.swift` and create `Sources/<Target>/`.
3. Add the module to `settings.gradle.kts`, create `android/<module>/build.gradle.kts`
   with `spfnModuleDependsOn`, `spfnSwiftCounterpart` and the D5 toolchain block, and
   add Kotlin sources.
4. Regenerate the CocoaPods fixture:
   `sh tools/cocoapods-compat/generate-podspec.sh --write`.
5. Run the validator. It cross-checks all four representations against each other.

## Style

Follow the surrounding code. Swift and Kotlin here use Allman braces, four-space
indentation and small single-purpose declarations.
