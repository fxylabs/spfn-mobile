# Compatibility matrix

**Every device-support row is UNRESOLVED.** Two releases exist (`0.1.0-alpha.2`,
superseded, and `0.1.0-alpha.3`, current). Device probes now exist, but neither platform
has met its whole gate, so no device compatibility has been established. This file fixes
the shape each future release must fill in, and the gate that must pass before a row may
claim a value.

A build baseline is not a support commitment. Decision D5 fixed the toolchain this
repository compiles with; that says what the code was built against, not what it works
on. Those are different claims, and only the second is one an integrator can rely on.

| Field | Value to record per release | Gate | Current state |
| --- | --- | --- | --- |
| Mobile SDK | version, source commit, tag digest | `VERSION`, tag, changelog and Maven POM agree | published — `0.1.0-alpha.3` (source commit `70781e4`) reached Maven Central via `publish-central.yml` (deployment `b7ae0261`) with the matching SwiftPM tag. Consumption was verified 2026-08-04 from the published coordinates alone by `tools/rc-verify/verify-published.sh`: six modules on repo1.maven.org with matching sha256 sidecars and PGP signatures under key `1CC7BD2E870BC4B2A279EB5BCB666532EB4E568A`, an Android consumer compiled against `mavenCentral()` on a refreshed cache, and the SwiftPM tag resolving to that commit. `0.1.0-alpha.2` (deployment `d0ca11b5`) is superseded: it predates the asymmetric clientProofV1 revision and cannot authenticate against a contract `0.2.0`+ server |
| Contract | exact imported digest and supported SemVer range | a contract outside the range raises an explicit upgrade error; no unknown-profile fallback | enforced — `0.1.0` is pinned by real digest from the SPFN primitives export (D17 resolved), and the range check is driven by a shared cross-platform vector table |
| Auth | `allowed=[clientProofV1]`, default profile, mixing prohibited | any redirect-based auth symbol, endpoint or fixture in the public surface fails the build | **enforced** — the allowlist is exactly `clientProofV1` on both platforms and in the generated clients |
| Server | minimum verified SPFN primitives release/commit and endpoint capability set | server fixtures and both SDK conformance suites pass | UNRESOLVED — the contract is an upstream export and both suites round-trip against the SPFN primitives dev server on localhost, but no deployed service has been contacted and no minimum release is fixed |
| iOS | Swift tools, Xcode, minimum iOS/macOS, tested device matrix | Linux Swift core + macOS Apple API + real-device release evidence | UNRESOLVED — *builds* with swift-tools 6.0 and Xcode 26.2 against iOS 16 and macOS 13. On 2026-08-07 an iPhone 14 Pro (iPhone15,2) on iOS 26.5.2 reported `custody=secureEnclave`; the nine lifecycle cells did not run on that phone because Maestro has no physical-iOS driver, so the gate is still unmet |
| Android | Kotlin, AGP, Gradle, JDK, min/target/compile SDK, tested API and ABI matrix | unit, lint, assemble + emulator + real-device release evidence | UNRESOLVED — *builds* with AGP 9.2.1, Kotlin 2.4.10, Gradle 9.5.1 and JDK 21 at minSdk 24 / compileSdk 36. On 2026-08-10 an arm64-v8a Samsung SM-F721N on Android 15 (API 35) reported `custody=strongBox`. Two corrected harness runs passed eight and seven of nine lifecycle cells; c9 (`rotationPending` → `resumeRotation`) failed both times, and the second run also failed c5 (`enrolled` → `rotate`). The observed c9 outcome was `PROOF_EXPIRED`: the device clock measured about 2.0–2.3 seconds ahead of the reference-server host and clientProofV1 admits no future skew (D24). The real-device gate is therefore unmet; zero emulators have been tested. Consumer condition: the published AARs carry Kotlin 2.4 metadata, which the Kotlin 2.2.x compiler bundled by an AGP 9 default toolchain cannot read — a consumer must build with Kotlin 2.4+ (the rc-verify consumer template pins this explicitly) |
| Hybrid | supported WebView journeys, allowlist and bridge schema version | absence of redirect auth routes, credential injection and any generic JS bridge is verified | **enforced** — the hybrid module was dropped from the train after `0.1.0-alpha.3`; the validator now refuses WebView and JavaScript-bridge vocabulary anywhere in the surface, which an empty allowlist literal could not do |

## Why "UNRESOLVED" and not a guess

A compatibility matrix is read as a support commitment. A custody reading or a partial
lifecycle run is narrower than the platform gate and cannot establish an OS support
range. The rows stay unresolved until the whole named gate passes, and
`tools/validate/validate.sh` fails if the iOS or Android row ever loses the word
UNRESOLVED.

Open decisions behind these rows are tracked in [docs/OPEN-DECISIONS.md](docs/OPEN-DECISIONS.md).
