# `:contract-codegen`

Generates the Swift and Kotlin clients from the pinned contract bundle, in one run.

```sh
./gradlew :contract-codegen:spfnGenerateClients   # rewrite the generated sources
./gradlew :contract-codegen:spfnCodegenVerify     # fail if they are not up to date
```

`spfnCodegenVerify` is wired into `check`, so `./gradlew build` fails when a generated
file has been hand-edited or the bundle changed without regeneration.

## Boundary

A non-published Kotlin/JVM Gradle module. It lives inside the JDK/Gradle toolchain
Android already requires, so the repository does not acquire a second toolchain — Node,
pnpm and Turbo are deliberately not the root build system here. Its own JSON reader is
hand-written for the same reason: zero external dependencies.

## Contract

- **Input:** the bundle named by `Contracts/upstream.lock.json`, and nothing else.
- **Output:** `Sources/SPFNGenerated/Generated/` (Swift) and
  `android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated/` (Kotlin),
  produced in the same run from the same input.
- **Digest gate:** the generator recomputes the bundle's SHA-256 and refuses to run when
  it does not match the lock. A generated header that names a digest was therefore
  demonstrably produced from a file with that digest.
- **Zero network:** generation reads one file from disk. Nothing is fetched.
- **Deterministic:** output is a pure function of the bundle bytes. No timestamp, no
  host name, no absolute path, no unordered map iteration. Two consecutive runs produce
  byte-identical files.

## What each output file holds

| File | Contents |
| --- | --- |
| `SPFNGeneratedContract.swift` / `SpfnGeneratedContract.kt` | the contract binding: version, bundle digest, supported range, origin, operation ids, replay window, proof-input field order |
| `SPFNGeneratedTypes.swift` / `SpfnGeneratedTypes.kt` | one struct per contract type, with canonical encoding and strict decoding |
| `SPFNGeneratedOperations.swift` / `SpfnGeneratedOperations.kt` | one descriptor per operation, plus lookup by contract id |
| `SPFNGeneratedErrors.swift` / `SpfnGeneratedErrors.kt` | every contract error code with its HTTP status and retryability |

Generated directories hold generated files only. A leftover from an earlier contract is
deleted on the next write and reported as stale by `spfnCodegenVerify`, because a
compiling ghost from a previous contract is worse than a missing file.

## Why the generated code is thin

Field readers, canonical serialization and digests live in `SPFNCore` / `spfn-core`,
hand-written and reviewed once. The generated files are a listing of the contract, not
a place where logic hides. Reviewing a contract change should mean reading a diff of
names and types.
