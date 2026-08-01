# Compatibility matrix

**Every support row is UNRESOLVED.** No release exists and nothing has run on a device,
so no compatibility has been established. This file fixes the shape each future release
must fill in, and the gate that must pass before a row may claim a value.

A build baseline is not a support commitment. Decision D5 fixed the toolchain this
repository compiles with; that says what the code was built against, not what it works
on. Those are different claims, and only the second is one an integrator can rely on.

| Field | Value to record per release | Gate | Current state |
| --- | --- | --- | --- |
| Mobile SDK | version, source commit, tag digest | `VERSION`, tag, changelog and Maven POM agree | UNRESOLVED — `0.1.0-alpha.1` decided (D9), no commit, no tag, no POM |
| Contract | exact imported digest and supported SemVer range | a contract outside the range raises an explicit upgrade error; no unknown-profile fallback | partially enforced — `1.0.0-dev.1` is pinned by real digest and the range check is tested, but the bundle is locally authored, not exported by SPFN primitives (D17) |
| Auth | `allowed=[clientProofV1]`, default profile, mixing prohibited | any redirect-based auth symbol, endpoint or fixture in the public surface fails the build | **enforced** — the allowlist is exactly `clientProofV1` on both platforms and in the generated clients |
| Server | minimum verified SPFN primitives release/commit and endpoint capability set | server fixtures and both SDK conformance suites pass | UNRESOLVED — no server has been contacted and no upstream contract exists |
| iOS | Swift tools, Xcode, minimum iOS/macOS, tested device matrix | Linux Swift core + macOS Apple API + real-device release evidence | UNRESOLVED — *builds* with swift-tools 6.0 and Xcode 26.2 against iOS 16 and macOS 13; zero devices tested |
| Android | Kotlin, AGP, Gradle, JDK, min/target/compile SDK, tested API and ABI matrix | unit, lint, assemble + emulator + real-device release evidence | UNRESOLVED — *builds* with AGP 9.2.1, Kotlin 2.4.10, Gradle 9.5.1 and JDK 21 at minSdk 24 / compileSdk 36; zero devices and zero emulators tested |
| Hybrid | supported WebView journeys, allowlist and bridge schema version | absence of redirect auth routes, credential injection and any generic JS bridge is verified | partially enforced — the bridge allowlist is empty and no bridge exists |

## Why "UNRESOLVED" and not a guess

A compatibility matrix is read as a support commitment. Writing "iOS 16+" here because
the package declares an iOS 16 deployment target would tell an integrator that iOS 16
was tested. Nothing has run on any device. The rows stay unresolved until real evidence
exists, and `tools/validate/validate.sh` fails if the iOS or Android row ever loses the
word UNRESOLVED.

Open decisions behind these rows are tracked in [docs/OPEN-DECISIONS.md](docs/OPEN-DECISIONS.md).
