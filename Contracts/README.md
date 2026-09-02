# Contracts

**One bundle is pinned here, and SPFN primitives wrote it.** It is generated there from
`packages/auth/src/server/client-proof` and published at an exact commit; this directory
holds a byte copy, its digest, and the evidence file the exporter produced alongside it.

Until 2026-08-02 the opposite was true: Step 2 authored a development bundle here because
upstream had no export, and the lock said so in as many words. That bundle proved the
generator and both SDKs read the same local bytes — self-verification, and nothing about
the server. The distinction is still the whole point of this directory; it is simply on
the other side of it now.

| File | Purpose | Current state |
| --- | --- | --- |
| `upstream.lock.json` | what is pinned, and where it came from | `RESOLVED_UPSTREAM`, `origin: spfn-primitives-ci-export`, commit `77fe6246`, contract `0.10.0`, exporter `@spfn/auth/contract-bundle@6.0.0` |
| `spfn-mobile-contract.json` | the vendored bundle, copied not edited | 16 operations (3 proven dev-surface, 13 REST: register, login, native social enrollment, key rotation, key list, key revoke, key revoke-all, the server clock `core.time`, and the five device-code operations `auth.device.start`, `auth.device.poll`, `auth.device.info`, `auth.device.approve`, `auth.device.deny`), 31 types, 3 enums (`KeyAlgorithm`, `KeyPlatform`, `DeviceAuthPollStatus`), 22 error codes, the canonical JSON and proof-input algorithms, the `signature` clause (ECDSA P-256, raw r‖s base16-lower, SPKI DER public keys), and the sections a 0.10.x bundle carries: `operationAuthClasses`, `operationAvailability`, `keyPolicy` (ttlDays 90), `clockSynchronization`, `nativeEnrollment` (the rule binding an enrollment's nonce to its fingerprint), `deviceAuthorization` (new in 0.10.0 — the device-code flow the five operations serve), `restOperations`, `canonicalJson`, `clientProofV1.clientIdRule`, `wireMapping`, `compatibilityPolicy` and `typeGrammar`. `auth.device.deny` declares no `responseType`: `restOperations.responseBody` states that such an operation "answers 204 with an empty body and there is nothing to decode" |
| `upstream-provenance.json` | the exporter's own evidence, copied unmodified | names the repository, exporter version and bundle digest the lock is checked against |
| `auth-profiles/clientProofV1.schema.json` | profile schema | shape placeholder |
| `fixtures/` | deterministic conformance vectors | 10 files, consumed unchanged by both SDK test suites |

## Lock states

| Status | Means | What the validator requires |
| --- | --- | --- |
| `UNRESOLVED_PLACEHOLDER` | nothing is pinned | no digest-shaped string anywhere, no fixture vectors |
| `RESOLVED_DEV_BUNDLE` | a locally authored bundle is pinned | `origin` is the dev-bundle name, `exportedByUpstreamCI` is false, no 40-hex commit, `manifestSha256` is the real digest of the file, fixtures exist and match `MANIFEST.json` |
| `RESOLVED_UPSTREAM` | an SPFN primitives export is pinned | `exportedByUpstreamCI` is true, a 40-hex source commit, the bundle labels itself `UPSTREAM_EXPORT`, and every claim agrees with `Contracts/upstream-provenance.json` — origin, digest, exporter version, repository, version and range |

The third row is the state this repository is in, and it changed shape when it started
being used. While no export existed the rule was "refuse a claim that carries no
evidence". Now the rule is "check the claim against the evidence": a lock that agrees
only with itself proves nothing, because a fabricated provenance record reads exactly
like a real one to every downstream reader. The validator also refuses evidence that
names this repository as the source, which is what a dev bundle dressed up as an export
would look like.

## Re-pinning a new export

A contract change is made in SPFN primitives, re-exported there, and re-pinned here.
Nothing in this directory is edited to make a change appear upstream.

1. Copy `contracts/mobile/spfn-mobile-contract.json` and `contracts/mobile/upstream-provenance.json`
   from the primitives commit you intend to pin.
2. Update `upstream.lock.json`: `source.commit`, `contract.version`, `major`, `minor`,
   `supportedRange` and `manifestSha256`.
3. `./gradlew :contract-codegen:spfnGenerateClients` then `:contract-codegen:spfnCodegenVerify`.
4. `python3 Contracts/fixtures/derive-expected-values.py --write`.
5. `sh tools/validate/validate.sh`, both platform suites, then the integration matrix in
   external-target mode against a primitives dev server.

## The supported range is not the major alone

The contract line is `0.x`, where SemVer puts breaking changes in the minor. A server on
`0.2.0` is as incompatible with `0.1.0` as `2.0.0` is with `1.0.0`, so the SDK compares
major **and** minor while the major is 0. Comparing majors alone would accept a contract
the declared range excludes, and the check would be weaker than the range it prints.

## Rules that survive every step

- Floating branches or URLs are never a valid lock source.
- A published contract version and digest are never modified; a mistake becomes a new version.
- The bundle carries no secrets and no real private keys.
- A contract outside the SDK's declared supported range raises an explicit upgrade error.
  There is no unknown-profile fallback and no unknown-error-code fallback.

## Verifying by hand

```sh
shasum -a 256 Contracts/spfn-mobile-contract.json
grep manifestSha256 Contracts/upstream.lock.json
```

Those two must agree. `tools/validate/validate.sh`, `tools/contract-codegen` and both
conformance suites each check it independently.
