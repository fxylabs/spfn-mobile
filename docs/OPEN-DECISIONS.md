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
| D3 | Registry staging | **RESOLVED 2026-08-03** | RC verification stays a no-publish candidate: a local tag plus a local `$TMPDIR` staging directory, produced by `tools/rc-verify/rc-verify.sh` through the publication gate. The follow-up track is now open as machinery: `.github/workflows/publish-central.yml` uploads a staged bundle to the Central Portal, manually dispatched only, held for human confirmation (`USER_MANAGED`) | still nothing publishes by itself: the workflow's secrets are unregistered by design, every dispatch fails until a person registers them, and the Portal holds every upload for explicit confirmation. Gradle itself never gains a remote repository |
| D4 | Maven group | **RESOLVED 2026-08-03** | `xyz.superfunction.spfn` — the `xyz.superfunction` namespace is domain-verified on the Central Portal | recorded in `gradle.properties` as `spfn.maven.group` with `verified=true`; the root build script now requires the flag to stay true, so un-verifying the group is a deliberate edit to the gate, not a property flip |
| D5 | OS/toolchain baseline (Swift tools, Xcode, min iOS/macOS, Kotlin, AGP, Gradle, JDK, min/target/compile SDK) | **RESOLVED 2026-08-01** | see the table below | confirmed as a **build and parity baseline only**; it is not a support commitment, and `COMPATIBILITY.md` support rows stay UNRESOLVED until real-device evidence exists |
| D6 | Real CODEOWNERS subjects | OPEN | split contract / iOS / Android / security / release roles | actual teams, handles and required-review enforcement are undetermined |
| D7 | Contract bundle signing | **RESOLVED 2026-08-03** | alpha candidates are unsigned: candidate identity is the source commit plus `SHA256SUMS` plus the candidate manifest, with CycloneDX SBOMs for both platforms — the Gradle plugin on Android, static generation on iOS where the external dependency set is empty by design | Central requires PGP for published artifacts, so the signing *lookup* configuration now exists: an in-memory key injected per run as `ORG_GRADLE_PROJECT_*` environment variables and consumed by `useInMemoryPgpKeys`. The key itself lives only in GitHub Actions secrets, which are not registered yet; no key identity, key file or keyring path exists in the tree, and the validator fails if one appears |

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
| D9 | First release version | **RESOLVED 2026-08-01** | `VERSION` is `0.1.0-alpha.3`, lockstep SwiftPM/Maven; 1.0.0 waits on Step 5 evidence (decision `01kyyw1yqy`). `0.1.0-alpha.2` was published to Central 2026-08-03 and is superseded: it predates the asymmetric clientProofV1 revision and cannot authenticate against a contract `0.2.0`+ server. `0.1.0-alpha.1` stays a public tag: never published, superseded because its commit predates the cold-cache verification-metadata fix, and tags are immutable here | nothing further |
| D10 | Swift/Android module name asymmetry: `SPFNPersistence` vs `spfn-sync` | OPEN | carried verbatim from the approved layout, recorded in `tools/module-graph.json` | whether to reconcile the names, and in which direction |
| D11 | iOS distribution channel and the CocoaPods fixture | **RESOLVED 2026-08-02** | Swift Package Manager is the only iOS distribution channel and CocoaPods is not supported; the internal, unpublished, generated podspec fixture under `tools/cocoapods-compat/` stays as mechanical proof that the Swift sources are single-sourced (decision `01kz0r31ya`) | nothing further. No activation condition is recorded, deliberately: a real requirement would be judged as a separate decision when it exists |
| D12 | Gradle wrapper and distribution pinning | **RESOLVED 2026-08-01 with D5** | Gradle 9.5.1 wrapper committed; distribution and wrapper jar checksums taken from gradle.org and recorded in `gradle/wrapper/WRAPPER-PINS.json` | nothing further; upgrades repeat the same pinning procedure |
| D13 | Inter-module dependency edges | PROPOSED | unchanged by the Step 2 slice: generated/auth/persistence depend on core, hybrid depends on core and auth | whether these edges survive transport and persistence work |
| D14 | CI action pinning | OPEN | exactly one action exists: `actions/upload-artifact`, pinned by commit SHA in `publish-central.yml` to carry failure logs out of the runner. Every other workflow uses none, and the validator holds both — a tag-pinned or unlisted action fails | the full action set for a real CI, each pinned by commit SHA, stays undecided |
| D15 | Kotlin source package vs Maven coordinate | PROPOSED | Kotlin sources use `xyz.superfunction.spfn.*`, and D4 (resolved 2026-08-03) fixed the Maven group to the same value, so package and coordinate agree | nothing forces a change while they agree; revisit only if the coordinate ever moves |

## Raised by Step 2 implementation

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D16 | Kotlin `apiVersion` floor for older consumers | **RESOLVED 2026-08-01 by compile evidence** | every Android module compiles with `apiVersion = 2.2` | nothing further for now. 2.0 was tried first and rejected by the compiler: Kotlin 2.4.10 reports *"API version 2.0 is deprecated and its support will be removed in a future version of Kotlin. Update the version to 2.2"*, and `allWarningsAsErrors` turns that into a build failure. 2.2 is therefore the lowest API version this toolchain will accept, so a consumer on Kotlin 2.0 or 2.1 cannot link the AAR |
| D17 | Upstream contract export tooling | **RESOLVED 2026-08-02** | SPFN primitives generates the bundle from its own route and contract definitions (`packages/auth/scripts/export-mobile-contract.ts`, tracked as primitives issue #48) and publishes it at `contracts/mobile/`. `Contracts/spfn-mobile-contract.json` is a byte copy pinned at commit `d31aa9a1`, contract `0.1.0`, digest `96c48f9c…`, with the exporter's own `Contracts/upstream-provenance.json` beside it. The lock is `RESOLVED_UPSTREAM` and the validator now checks its claims against that evidence instead of refusing them | nothing further. The Step 5 blocker is cleared: both platform suites and the integration matrix in external-target mode pass against a primitives dev server running this contract. Re-pinning a later export is the routine in `Contracts/README.md`, not a new decision |
| D18 | AAR bytecode target | **RESOLVED 2026-08-01 by measurement** | `sourceCompatibility`/`targetCompatibility`/`jvmTarget` are pinned to Java 11 in every module | nothing further. Java 11 is AGP 9.2.1's own default, measured with `./gradlew spfnToolchainReport` before pinning. It is restated explicitly so a future AGP upgrade that moves the default cannot move the published AAR silently |
| D19 | Conformance fixture authorship | PROPOSED | expected values are derived by `Contracts/fixtures/derive-expected-values.py`, a third implementation independent of both SDKs. D17 is resolved and the bundle now arrives from upstream, but the fixtures did not come with it | whether an independently authored derivation is acceptable evidence long term, or whether the vectors should be exported alongside the bundle. Two arguments now point in opposite directions: a third implementation is stronger evidence than one the server team wrote, and vectors that ship with the contract cannot drift from it. The re-pin of 2026-08-02 is a data point for the first — every vector reproduced byte for byte from the upstream bundle, and only the digest reference moved |
| D20 | Repository ownership of the Android SDK location | OPEN | the Android SDK is found through `ANDROID_HOME`; no `local.properties` is committed | how CI provides an Android SDK, and which SDK components are pinned |

## Raised by the transport change set

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D21 | Cleartext (`http://`) policy for the transport | OPEN | the transport accepts any absolute URL, `http://` included, because the reference server the SDK is verified against runs locally over cleartext | whether the shipped SDK refuses `http://` outright, allows it only for loopback, or leaves it to the host app's network security configuration. Refusing it here today would block the integration work that has to prove the client against a real server first |
| D22 | Response header fidelity on iOS | OPEN | `HTTPURLResponse.allHeaderFields` is a dictionary, so wire order and repeated response headers are already lost before the adapter sees them; the adapter sorts by lowercased name so the result is at least deterministic. OkHttp preserves both | whether anything above the transport is allowed to depend on response header order or repetition. If it ever is, iOS needs a different response reader, not a different adapter |

## Raised by the session change set

| # | Decision | State | What exists | What a person must decide |
| --- | --- | --- | --- | --- |
| D23 | Wire header mapping for clientProofV1 | **RESOLVED 2026-08-02 by upstream ratification** | the pinned bundle's `wireMapping` section names `x-spfn-auth-profile`, `x-spfn-client-id`, `x-spfn-key-id`, `x-spfn-nonce`, `x-spfn-issued-at`, `x-spfn-proof` and `x-spfn-session`, plus `application/json` as the request content type and the rule that only a `requiresSession` operation carries a session header. `Contracts/fixtures/request/wire.json` pins the exact bytes and both SDKs assert their constants against the bundle | nothing further on the names. SPFN primitives issue #46 adopted this mapping unchanged and shipped it in `@spfn/auth@0.2.0-beta.85`, so what was a dev-bundle extension is now the upstream wire format and a real server names these fields the same way. The export that resolved D17 carries this mapping unchanged, so the ratified names are now the exported ones rather than a dev-bundle extension that upstream happened to agree with |
| D24 | Client clock skew margin | OPEN | the session judges expiry against the injected client clock with **no skew margin at all**: a client whose clock runs ahead reopens a session early and one that runs behind presents an expired one. Carried out of D23, which resolved the header half of that entry | whether the shipped SDK carries a margin, reads a server date header, or refuses to guess. The server's replay window is 300 000 ms, which bounds how far a client clock may drift before every proof it mints is refused, but the SDK does nothing with that number today |

## Rule

If a later step needs one of these answered, it stops and asks. It does not pick one
and note the choice in a changelog.
