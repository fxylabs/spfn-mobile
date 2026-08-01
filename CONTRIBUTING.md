# Contributing

This repository is a scaffold under staged bootstrap. It is not open for general
contribution yet, and the review, ownership and branch policies are undecided.

## Before you change anything

Run the checks:

```sh
sh tools/validate/validate.sh                        # structural validation, offline
swift build && swift test                            # Swift targets and conformance
./gradlew build                                      # Android modules and conformance
./gradlew :contract-codegen:spfnCodegenVerify        # generated sources are up to date
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
```

The validator needs no network, no package manager and no credentials. The Gradle
commands need an Android SDK; point `ANDROID_HOME` at it. Gradle itself comes from the
committed wrapper, whose distribution and jar are pinned to checksums published by
gradle.org.

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
- **Never claim provenance you do not have.** The pinned bundle is locally authored and
  says so. A lock that claims an upstream export must produce upstream evidence.
- **Never widen the auth surface.** `clientProofV1` is the only profile. Redirect-based
  browser auth vocabulary anywhere in the API surface fails the build.
- **Never enable publication.** No registry, no credential block, no signing plugin, no
  trunk publication command.
- **No new binaries.** The Gradle wrapper jar is the only one, and only because its
  digest matches the artifact gradle.org publishes.

## Changing the contract

1. Edit `Contracts/spfn-mobile-contract.v1.json`.
2. Re-pin: `shasum -a 256 Contracts/spfn-mobile-contract.v1.json`, then update
   `manifestSha256` in `Contracts/upstream.lock.json`. Until you do, the generator
   refuses to run — that ordering is the gate, not an obstacle.
3. Regenerate: `./gradlew :contract-codegen:spfnGenerateClients`.
4. Regenerate the fixtures if the algorithms changed:
   `python3 Contracts/fixtures/derive-expected-values.py --write`.
5. Run both conformance suites and the validator.

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
