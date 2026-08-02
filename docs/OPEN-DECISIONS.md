# Open decisions

Everything listed as OPEN is undecided. The repository represents each one explicitly
rather than picking a plausible default, because a plausible default is
indistinguishable from an approved one once it is written down.

Legend: **OPEN** — no decision exists. **PROPOSED** — a recommendation exists and is
awaiting human confirmation. **RESOLVED** — a person confirmed it, with the date.

## Carried from the approved topology artifact §11

| # | Decision | State | Recommended default | Why it is not decided here |
| --- | --- | --- | --- | --- |
| D1 | Repository visibility and public source disclosure | OPEN | keep current visibility; disclose only under separate approval | the repository existing is not approval to publish its source |
| D2 | CI runner strategy | OPEN | hosted Linux + macOS, release environment with a required reviewer | macOS cost, device farm and attestation plan limits are unknown |
| D3 | Registry staging | OPEN | no-publish candidate first, then private alpha, then public under separate approval | no authority to create or use accounts, namespaces or signing identities |
| D4 | Maven group | PROPOSED | `xyz.superfunction.spfn` | control of the `superfunction.xyz` namespace and Central verification eligibility are unconfirmed; recorded in `gradle.properties` as `spfn.maven.group.proposed` with `verified=false` |
| D5 | OS/toolchain baseline (Swift tools, Xcode, min iOS/macOS, Kotlin, AGP, Gradle, JDK, min/target/compile SDK) | **RESOLVED 2026-08-01** | see the table below | confirmed as a **build and parity baseline only**; it is not a support commitment, and `COMPATIBILITY.md` support rows stay UNRESOLVED until real-device evidence exists |
| D6 | Real CODEOWNERS subjects | OPEN | split contract / iOS / Android / security / release roles | actual teams, handles and required-review enforcement are undetermined |
| D7 | Contract bundle signing | OPEN | commit + SHA-256 always; provenance and signature added for public releases | attestation availability and long-term key custody policy are undetermined |

### D5 as confirmed

| Item | Pinned value | Where it lives |
| --- | --- | --- |
| Swift tools version | 6.0 (Swift 6 language mode, strict concurrency) | `Package.swift` |
| Platforms | iOS 16, macOS 13 | `Package.swift` |
| Xcode used to build | 26.2 (Swift 6.2.3) | recorded, not pinned in-repo |
| Gradle | 9.5.1, distribution and wrapper jar pinned by published SHA-256 | `gradle/wrapper/` (also resolves D12) |
| AGP | 9.2.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.10 | `gradle/libs.versions.toml`, upgraded from AGP's bundled 2.2.10 in the root build script |
| JDK toolchain | 21, provisioned by the foojay resolver | `settings.gradle.kts`, each module's `jvmToolchain` |
| minSdk / compileSdk | 24 / 36 | each Android module |
| targetSdk | 36 | recorded in the version catalogue; applies to example applications, of which none exist yet |
| Kotlin `apiVersion` | 2.2 | see D16 |
| AAR bytecode target | Java 11 | see D18 |

## Raised by Step 1 implementation

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D8 | License | **RESOLVED 2026-08-01** | `LICENSE` is MIT, Copyright FXY Inc., matching the upstream SPFN primitives repository (decision `01kyyw6g9t`) | nothing further |
| D9 | First release version | **RESOLVED 2026-08-01** | `VERSION` is `0.1.0-alpha.1`, lockstep SwiftPM/Maven; 1.0.0 waits on Step 5 evidence (decision `01kyyw1yqy`) | nothing further |
| D10 | Swift/Android module name asymmetry: `SPFNPersistence` vs `spfn-sync` | OPEN | carried verbatim from the approved layout, recorded in `tools/module-graph.json` | whether to reconcile the names, and in which direction |
| D11 | CocoaPods compatibility tier | PROPOSED | an internal, unpublished, generated podspec fixture under `tools/cocoapods-compat/` | whether the compatibility tier is confirmed at all |
| D12 | Gradle wrapper and distribution pinning | **RESOLVED 2026-08-01 with D5** | Gradle 9.5.1 wrapper committed; distribution and wrapper jar checksums taken from gradle.org and recorded in `gradle/wrapper/WRAPPER-PINS.json` | nothing further; upgrades repeat the same pinning procedure |
| D13 | Inter-module dependency edges | PROPOSED | unchanged by the Step 2 slice: generated/auth/persistence depend on core, hybrid depends on core and auth | whether these edges survive transport and persistence work |
| D14 | CI action pinning | OPEN | workflows use no third-party action at all | which actions are needed, then pin each by commit SHA |
| D15 | Kotlin source package vs Maven coordinate | PROPOSED | Kotlin sources use `xyz.superfunction.spfn.*` while the Maven group stays unverified | whether the source package should follow the coordinate if D4 changes |

## Raised by Step 2 implementation

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D16 | Kotlin `apiVersion` floor for older consumers | **RESOLVED 2026-08-01 by compile evidence** | every Android module compiles with `apiVersion = 2.2` | nothing further for now. 2.0 was tried first and rejected by the compiler: Kotlin 2.4.10 reports *"API version 2.0 is deprecated and its support will be removed in a future version of Kotlin. Update the version to 2.2"*, and `allWarningsAsErrors` turns that into a build failure. 2.2 is therefore the lowest API version this toolchain will accept, so a consumer on Kotlin 2.0 or 2.1 cannot link the AAR |
| D17 | Upstream contract export tooling | **OPEN — blocks Step 5** | the pinned bundle is hand-authored inside this repository. `Contracts/upstream.lock.json` records `origin: spfn-mobile-step2-dev-bundle` and `exportedByUpstreamCI: false` | **upstream export tooling must exist and replace the dev bundle before Step 5.** SPFN primitives has no mobile contract export today. Until it does, the pinned digest proves only that the generator and both SDKs read the same local file; it proves nothing about the server. The validator refuses a lock that claims an upstream export without upstream evidence |
| D18 | AAR bytecode target | **RESOLVED 2026-08-01 by measurement** | `sourceCompatibility`/`targetCompatibility`/`jvmTarget` are pinned to Java 11 in every module | nothing further. Java 11 is AGP 9.2.1's own default, measured with `./gradlew spfnToolchainReport` before pinning. It is restated explicitly so a future AGP upgrade that moves the default cannot move the published AAR silently |
| D19 | Conformance fixture authorship | PROPOSED | expected values are derived by `Contracts/fixtures/derive-expected-values.py`, a third implementation independent of both SDKs | whether an independently authored derivation is acceptable evidence long term, or whether fixtures must come from SPFN primitives with the bundle (which is the D17 path) |
| D20 | Repository ownership of the Android SDK location | OPEN | the Android SDK is found through `ANDROID_HOME`; no `local.properties` is committed | how CI provides an Android SDK, and which SDK components are pinned |

## Raised by the transport change set

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D21 | Cleartext (`http://`) policy for the transport | OPEN | the transport accepts any absolute URL, `http://` included, because the reference server the SDK is verified against runs locally over cleartext | whether the shipped SDK refuses `http://` outright, allows it only for loopback, or leaves it to the host app's network security configuration. Refusing it here today would block the integration work that has to prove the client against a real server first |
| D22 | Response header fidelity on iOS | OPEN | `HTTPURLResponse.allHeaderFields` is a dictionary, so wire order and repeated response headers are already lost before the adapter sees them; the adapter sorts by lowercased name so the result is at least deterministic. OkHttp preserves both | whether anything above the transport is allowed to depend on response header order or repetition. If it ever is, iOS needs a different response reader, not a different adapter |

## Rule

If a later step needs one of these answered, it stops and asks. It does not pick one
and note the choice in a changelog.
