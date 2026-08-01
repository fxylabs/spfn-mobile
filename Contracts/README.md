# Contracts

**One bundle is pinned here, and it was written here.** SPFN primitives has no mobile
contract export tooling yet, so Step 2 authored a minimal `clientProofV1` development
bundle inside this repository, computed its real SHA-256, and recorded exactly that in
`upstream.lock.json`.

That distinction is the whole point of this directory. A pinned digest proves the
generator and both SDKs read the same bytes. It does not prove those bytes came from
the server team, and nothing here pretends otherwise.

| File | Purpose | Current state |
| --- | --- | --- |
| `upstream.lock.json` | what is pinned, and where it came from | `RESOLVED_DEV_BUNDLE`, `origin: spfn-mobile-step2-dev-bundle`, `exportedByUpstreamCI: false` |
| `spfn-mobile-contract.v1.json` | the vendored bundle | 3 operations, 7 types, 6 error codes, the canonical JSON and proof-input algorithms |
| `auth-profiles/clientProofV1.schema.json` | profile schema | shape placeholder; the authoritative schema arrives with the upstream bundle |
| `fixtures/` | deterministic conformance vectors | 8 files, consumed unchanged by both SDK test suites |

## Lock states

| Status | Means | What the validator requires |
| --- | --- | --- |
| `UNRESOLVED_PLACEHOLDER` | nothing is pinned | no digest-shaped string anywhere, no fixture vectors |
| `RESOLVED_DEV_BUNDLE` | a locally authored bundle is pinned | `origin` is the dev-bundle name, `exportedByUpstreamCI` is false, no 40-hex commit, `manifestSha256` is the real digest of the file, fixtures exist and match `MANIFEST.json` |
| `RESOLVED_UPSTREAM` | an SPFN primitives export is pinned | `exportedByUpstreamCI` is true, a 40-hex source commit, and `Contracts/upstream-provenance.json` present on disk |

The third row is the one that matters. A lock cannot claim an upstream export without
upstream evidence, because a fabricated provenance record reads exactly like a real one
to every downstream reader. No such evidence exists today, so a lock that claimed it
would fail immediately.

## Replacing the dev bundle

Open decision D17: upstream export tooling must exist and replace this bundle before
Step 5. When it does, the exported bundle replaces `spfn-mobile-contract.v1.json`
wholesale, the lock moves to `RESOLVED_UPSTREAM`, and the clients are regenerated. The
dev bundle is deleted, not edited into looking upstream-exported.

## Rules that survive every step

- Floating branches or URLs are never a valid lock source.
- A published contract version and digest are never modified; a mistake becomes a new version.
- The bundle carries no secrets and no real private keys.
- A contract outside the SDK's declared supported range raises an explicit upgrade error.
  There is no unknown-profile fallback and no unknown-error-code fallback.

## Verifying by hand

```sh
shasum -a 256 Contracts/spfn-mobile-contract.v1.json
grep manifestSha256 Contracts/upstream.lock.json
```

Those two must agree. `tools/validate/validate.sh`, `tools/contract-codegen` and both
conformance suites each check it independently.
