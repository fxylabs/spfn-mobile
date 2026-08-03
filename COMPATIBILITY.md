# Compatibility matrix

**Every device-support row is UNRESOLVED.** One release exists (`0.1.0-alpha.2`,
published 2026-08-03, superseded) but nothing has run on a device, so no device
compatibility has been established. This file fixes the shape each future release
must fill in, and the gate that must pass before a row may claim a value.

A build baseline is not a support commitment. Decision D5 fixed the toolchain this
repository compiles with; that says what the code was built against, not what it works
on. Those are different claims, and only the second is one an integrator can rely on.

| Field | Value to record per release | Gate | Current state |
| --- | --- | --- | --- |
| Mobile SDK | version, source commit, tag digest | `VERSION`, tag, changelog and Maven POM agree | published — `0.1.0-alpha.2` (source commit `be53221`) reached Maven Central via `publish-central.yml` (deployment `d0ca11b5`) with the matching SwiftPM tag, and repo1.maven.org propagation of all six modules was verified 2026-08-04; alpha.2 is superseded: it predates the asymmetric clientProofV1 revision and cannot authenticate against a contract `0.2.0`+ server. `0.1.0-alpha.3` is the current candidate |
| Contract | exact imported digest and supported SemVer range | a contract outside the range raises an explicit upgrade error; no unknown-profile fallback | enforced — `0.1.0` is pinned by real digest from the SPFN primitives export (D17 resolved), and the range check is driven by a shared cross-platform vector table |
| Auth | `allowed=[clientProofV1]`, default profile, mixing prohibited | any redirect-based auth symbol, endpoint or fixture in the public surface fails the build | **enforced** — the allowlist is exactly `clientProofV1` on both platforms and in the generated clients |
| Server | minimum verified SPFN primitives release/commit and endpoint capability set | server fixtures and both SDK conformance suites pass | UNRESOLVED — the contract is an upstream export and both suites round-trip against the SPFN primitives dev server on localhost, but no deployed service has been contacted and no minimum release is fixed |
| iOS | Swift tools, Xcode, minimum iOS/macOS, tested device matrix | Linux Swift core + macOS Apple API + real-device release evidence | UNRESOLVED — *builds* with swift-tools 6.0 and Xcode 26.2 against iOS 16 and macOS 13; zero devices tested |
| Android | Kotlin, AGP, Gradle, JDK, min/target/compile SDK, tested API and ABI matrix | unit, lint, assemble + emulator + real-device release evidence | UNRESOLVED — *builds* with AGP 9.2.1, Kotlin 2.4.10, Gradle 9.5.1 and JDK 21 at minSdk 24 / compileSdk 36; zero devices and zero emulators tested. Consumer condition: the published AARs carry Kotlin 2.4 metadata, which the Kotlin 2.2.x compiler bundled by an AGP 9 default toolchain cannot read — a consumer must build with Kotlin 2.4+ (the rc-verify consumer template pins this explicitly) |
| Hybrid | supported WebView journeys, allowlist and bridge schema version | absence of redirect auth routes, credential injection and any generic JS bridge is verified | partially enforced — the bridge allowlist is empty and no bridge exists |

## Why "UNRESOLVED" and not a guess

A compatibility matrix is read as a support commitment. Writing "iOS 16+" here because
the package declares an iOS 16 deployment target would tell an integrator that iOS 16
was tested. Nothing has run on any device. The rows stay unresolved until real evidence
exists, and `tools/validate/validate.sh` fails if the iOS or Android row ever loses the
word UNRESOLVED.

Open decisions behind these rows are tracked in [docs/OPEN-DECISIONS.md](docs/OPEN-DECISIONS.md).
