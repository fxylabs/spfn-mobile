# SPFN Mobile

> **Step 2 scaffold with one vertical slice. This is not an SDK yet.**
>
> There is no transport, no persistence, no bridge, no release, no package on any
> registry, and **no public support** of any distribution channel is promised. Nothing
> here has been committed, pushed or published. The pinned contract bundle was written
> in this repository, not exported by SPFN primitives. Read
> [docs/SCAFFOLD-STATUS.md](docs/SCAFFOLD-STATUS.md) before assuming any capability
> exists.

SPFN Mobile is the polyglot repository for the SPFN iOS (Swift) and Android (Kotlin)
native SDKs and the optional hybrid WebView adapter. Both platforms share one release
train and one contract import, so the security model, proof canonicalization and error
model cannot drift apart between them.

## What exists right now

| Area | State |
| --- | --- |
| Swift module graph | 6 targets, building in Swift 6 language mode |
| Android module graph | 6 modules, compiling as AAR libraries |
| Contract | one bundle pinned by real SHA-256 — **hand-authored here**, not an upstream export |
| Codegen | `tools/contract-codegen` produces both clients from that bundle, deterministically |
| `clientProofV1` | canonical proof input, SHA-256 digest, HMAC proof, replay and revocation rules |
| Canonical JSON | SPFN-CANON-JSON-1, implemented independently on both platforms |
| Conformance | one fixture directory, two suites, cross-platform digest parity |
| Offline validator | working, and still the cheapest gate in the repository |
| CI | five inert manual-only workflow files; none is a gate |
| Release / publication | disabled by default, and no channel is configured |

## Layout

```
Package.swift            SwiftPM manifest (repo root, required by SwiftPM)
settings.gradle.kts      Android multi-project root
Contracts/               pinned contract bundle, lock, schemas, conformance fixtures
Sources/                 Swift targets: SPFNCore, SPFNGenerated, SPFNAuth,
                         SPFNClient, SPFNPersistence, SPFNHybrid
Tests/                   Swift unit, repository and conformance tests
android/                 Kotlin modules: spfn-core, spfn-generated, spfn-auth,
                         spfn-client, spfn-sync, spfn-hybrid
tools/                   module graph, offline validator, contract codegen,
                         CocoaPods fixture
examples/                reference apps (placeholders)
docs/                    architecture, migration, security, open decisions
```

## Distribution

Swift Package Manager is the primary iOS channel. Android modules are Gradle/Maven
modules. **CocoaPods is not supported**; `tools/cocoapods-compat/` holds an internal,
unpublished fixture whose only job is to prove the module graph stays single-sourced.

## Running the checks

```sh
sh tools/validate/validate.sh                        # offline structural validation
swift build && swift test                            # Swift targets and conformance
./gradlew build                                      # Android modules and conformance
./gradlew :contract-codegen:spfnCodegenVerify        # generated sources are up to date
./gradlew spfnToolchainReport                        # the effective D5 baseline
pod ipc spec tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
```

The Gradle commands need an Android SDK; point `ANDROID_HOME` at it. Each command is
separate evidence, and the validator never infers or reports the result of the others.

## Where the decisions live

The approved repository and release topology is the decision artifact
`2026-07-27-spfn-mobile-sdk-repository-topology-decision.html`. Everything still open is
listed in [docs/OPEN-DECISIONS.md](docs/OPEN-DECISIONS.md) — including the Maven
namespace, ownership, signing, the license, and D17, the missing upstream contract
export tooling that has to replace the local bundle before any release.
