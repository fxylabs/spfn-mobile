# Security

## Status

This repository is a scaffold with one vertical slice. **There is no shipped software
and no released version, so there is nothing deployed to report a vulnerability
against.** No reporting channel, disclosure policy or embargo process has been approved
yet; that is an open decision.

If you found something in this repository, raise it through whatever channel you
already use with the SPFN maintainers.

## Boundaries that exist today, and are enforced

| Boundary | Enforcement |
| --- | --- |
| `clientProofV1` is the only auth profile | `SPFNAuthProfile` has exactly one case; the validator asserts the count, both allowlists, and that every generated operation names that profile |
| No redirect-based browser auth surface | the validator fails on redirect-auth vocabulary anywhere in `Sources/`, `Tests/`, `android/`, `Contracts/`, `examples/` or `.github/` |
| No unknown-profile fallback | `SPFNAuthPolicy.resolve` throws; the lock records `unknownProfilePolicy: reject` |
| No unknown-error-code fallback | the generated error enum refuses an unrecognised code and preserves the raw string instead of rounding it to a neighbour |
| No generic JavaScript bridge | the hybrid bridge allowlist is empty on both platforms |
| No credential ever reaches web content | there is no bridge and no credential to inject |
| Proof comparison does not leak position | `constantTimeEquals` on both platforms |
| Revocation is decided before verification | `SPFNProofAcceptance` and `SpfnProofAcceptance` check revocation first, so a revoked key never reports `PROOF_INVALID`; pinned by `Contracts/fixtures/revoke/` |
| A nonce is spendable once per window | replay state is keyed on `(clientId, nonce)`; pinned by `Contracts/fixtures/replay/` |
| Proof input cannot be forged by separator injection | a C0 control character in any proof field is refused rather than escaped |
| No fabricated contract digest or provenance | the lock's digest is recomputed from the file it names, and an upstream-export claim requires upstream evidence that does not exist |
| Generated code is traceable | every generated file names the bundle digest it was produced from, and the validator checks that against the lock |
| No credentials, keystores or signing identities in the tree | the validator scans for credential-shaped files and private key material |
| Publication disabled | `spfn.publishing.enabled=false`, no registry declared, no publication block, no trunk publication command |
| Dependency verification | `gradle/verification-metadata.xml` carries real SHA-256 checksums for every resolved artifact, fetched at generation time and never invented |
| Only three dependency repositories | the validator fails on any `maven { url … }` or other repository beyond `google()`, `mavenCentral()` and `gradlePluginPortal()` |
| Only one binary in the tree | the Gradle wrapper jar, and only because its digest equals the one gradle.org publishes for that version |

## Boundaries that do not exist yet

Key custody, Keychain and Secure Enclave handling, Android Keystore handling, transport
and certificate pinning, the WebView adapter itself, and the fuzzing and SBOM gates are
all unimplemented. Nothing here has been independently security reviewed; that is Step 3.

Two components now parse untrusted bytes and deserve fuzzing before any release: the
canonical JSON reader and the error envelope decoder.

## Contract data

The pinned bundle carries canonical proof, request, error, replay and revoke fixtures
and **never** carries secrets or real private keys. The proof fixtures use a synthetic
key string that authenticates nothing.

The bundle itself is generated and published by SPFN primitives and copied here at an
exact commit (D17, resolved 2026-08-02). The lock's upstream claim is checked against the
exporter's own evidence file rather than taken at its word. What that does not establish
is that the pinned commit exists upstream: nothing here reaches the primitives
repository, so the digest proves which bytes were read, not who published them.
