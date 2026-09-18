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
| `upstream.lock.json` | what only this repository can know: which commit was read, which npm versions came from it, where the vendored copy sits | `lockVersion` 3, `RESOLVED_UPSTREAM`, `origin: spfn-primitives-ci-export`, commit `77fe6246`, exporter `@spfn/auth/contract-bundle@6.0.0` |
| `spfn-mobile-contract.json` | the vendored bundle, copied not edited | 16 operations (3 proven dev-surface, 13 REST: register, login, native social enrollment, key rotation, key list, key revoke, key revoke-all, the server clock `core.time`, and the five device-code operations `auth.device.start`, `auth.device.poll`, `auth.device.info`, `auth.device.approve`, `auth.device.deny`), 31 types, 3 enums (`KeyAlgorithm`, `KeyPlatform`, `DeviceAuthPollStatus`), 22 error codes, the canonical JSON and proof-input algorithms, the `signature` clause (ECDSA P-256, raw r‖s base16-lower, SPKI DER public keys), and the sections a 0.10.x bundle carries: `operationAuthClasses`, `operationAvailability`, `keyPolicy` (ttlDays 90), `clockSynchronization`, `nativeEnrollment` (the rule binding an enrollment's nonce to its fingerprint), `deviceAuthorization` (new in 0.10.0 — the device-code flow the five operations serve), `restOperations`, `canonicalJson`, `clientProofV1.clientIdRule`, `wireMapping`, `compatibilityPolicy` and `typeGrammar`. `auth.device.deny` declares no `responseType`: `restOperations.responseBody` states that such an operation "answers 204 with an empty body and there is nothing to decode" |
| `upstream-provenance.json` | the exporter's own evidence, copied unmodified — and the only source of what the contract IS | `name`, `version` `0.10.0`, `major`, `supportedRange` `>=0.10.0 <0.11.0`, `bundleSha256` `29c26160…`, plus the repository and exporter version. Its `source.commit` is the exporter's `RECORDED_BY_CONSUMER` placeholder: a file cannot carry the commit it was read at, so that one is the lock's |
| `auth-profiles/clientProofV1.schema.json` | profile schema | shape placeholder |
| `fixtures/` | deterministic conformance vectors | 10 files, consumed unchanged by both SDK test suites |

## Lock states

| Status | Means | What the validator requires |
| --- | --- | --- |
| `UNRESOLVED_PLACEHOLDER` | nothing is pinned | no digest-shaped string anywhere, no fixture vectors |
| `RESOLVED_DEV_BUNDLE` | a locally authored bundle is pinned | `origin` is the dev-bundle name, `exportedByUpstreamCI` is false, no 40-hex commit, fixtures exist and match `MANIFEST.json` |
| `RESOLVED_UPSTREAM` | an SPFN primitives export is pinned | `exportedByUpstreamCI` is true, a 40-hex source commit, the bundle labels itself `UPSTREAM_EXPORT`, `upstream-provenance.json` exists, names the same exporter, still carries the exporter's own `RECORDED_BY_CONSUMER` placeholder, and names a source repository other than this one |

The third row is the state this repository is in, and it has changed shape twice. While
no export existed the rule was "refuse a claim that carries no evidence". When the export
arrived the rule became "check the claim against the evidence", value by value — and that
comparison is what `lockVersion` 3 removed on 2026-09-18, by removing the second copy it
compared. The contract's own facts now live in `upstream-provenance.json` and nowhere
else, so they cannot drift and there is nothing to hold equal; what the validator still
refuses is evidence that is missing, that disagrees with itself, that was edited on the
way here, or that names this repository as the source — which is what a dev bundle
dressed up as an export would look like.

## Which file answers which question

A value with two homes is a value that can disagree with itself, and the checks written to
catch that disagreement had two gaps of their own by 2026-08-04. So each fact has one home:

| Question | Answered by |
| --- | --- |
| What is the contract called, what version is it, what range does it support, what does its bundle hash to? | `upstream-provenance.json`, `contract` |
| Which primitives commit was this copy read from? | `upstream.lock.json`, `source.commit` |
| Which npm package versions were published from that commit? | `upstream.lock.json`, `publishedPackages` |
| Where does the vendored copy live in THIS repository? | `upstream.lock.json`, `contract.bundlePath` |
| Which auth profiles may this SDK expose? | `SPFNAuthProfile` / `SpfnAuthProfile`, checked by validator section 6 — an SDK policy, never a contract pin |

## Re-pinning a new export

A contract change is made in SPFN primitives, re-exported there, and re-pinned here.
Nothing in this directory is edited to make a change appear upstream.

1. Copy `contracts/mobile/spfn-mobile-contract.json` and `contracts/mobile/upstream-provenance.json`
   from the primitives commit you intend to pin.
2. Update `upstream.lock.json`: `source.commit`, and `publishedPackages` if the npm
   releases moved with it. Nothing else — the version, major, supported range and digest
   arrive in the provenance file you copied in step 1 and are not restated here. Until
   that digest matches the bundle, the generator refuses to run.
3. `./gradlew :contract-codegen:spfnGenerateClients` then `:contract-codegen:spfnCodegenVerify`.
4. `python3 Contracts/fixtures/derive-expected-values.py --write`.
5. `sh tools/validate/validate.sh`, `sh tools/validate/probe-contract-lock-rules.sh`,
   both platform suites, then the integration matrix in external-target mode against a
   primitives dev server.

## The supported range is not the major alone

The contract line is `0.x`, where SemVer puts breaking changes in the minor. A server on
`0.2.0` is as incompatible with `0.1.0` as `2.0.0` is with `1.0.0`, so the SDK compares
major **and** minor while the major is 0. Comparing majors alone would accept a contract
the declared range excludes, and the check would be weaker than the range it prints.

The evidence records the version and the major and stops there, so every reader derives
the minor from the version rather than reading a number written beside it. A minor spelled
out next to a version it must agree with is a second chance to be wrong about one number.

## Rules that survive every step

- Floating branches or URLs are never a valid lock source.
- A published contract version and digest are never modified; a mistake becomes a new version.
- The bundle carries no secrets and no real private keys.
- A contract outside the SDK's declared supported range raises an explicit upgrade error.
  There is no unknown-profile fallback and no unknown-error-code fallback.

## Verifying by hand

```sh
shasum -a 256 Contracts/spfn-mobile-contract.json
grep bundleSha256 Contracts/upstream-provenance.json
```

Those two must agree. `tools/validate/validate.sh`, `tools/contract-codegen` and both
conformance suites each check it independently, and
`sh tools/validate/probe-contract-lock-rules.sh` proves those rules still refuse a lock
that grows a second copy of a contract value and evidence that names a digest the bundle
does not have.
