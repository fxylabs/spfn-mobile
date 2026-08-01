# Conformance fixtures

One directory, two SDK test suites, no second copy of any expected value.

`Tests/SPFNConformanceTests` (Swift) and
`android/spfn-auth/src/test/kotlin/xyz/superfunction/spfn/conformance` (Kotlin) read
these exact files. A vector cannot drift on one platform without the other noticing,
because there is nothing to drift from.

| File | What it pins |
| --- | --- |
| `canonical/serialization.json` | canonical JSON bytes and their SHA-256 for eight inputs, including one whose key order differs between UTF-8 and UTF-16 ordering |
| `canonical/rejects.json` | inputs that must be refused, and the exact error code each must report |
| `proof/proof-input.json` | the canonical `clientProofV1` proof input string, its digest, and the HMAC over it |
| `proof/rejects.json` | proof fields carrying control characters, which must be refused rather than escaped |
| `request/operations.json` | canonical request and response bytes for every generated operation type |
| `error/envelopes.json` | the canonical error envelope for every contract code, plus an unknown code that must not be mapped to a neighbour |
| `replay/replay.json` | nonce reuse, window expiry and a failing proof, as ordered sequences |
| `revoke/revoke.json` | revocation, including the ordering rule that a revoked key is refused before the proof is checked |

## Where the expected values came from

`derive-expected-values.py`, a third implementation of SPFN-CANON-JSON-1 and
SPFN-PROOF-INPUT-1 written against the contract text using only the Python standard
library. It is a development aid; no build step, test or validator runs it.

The reason it exists: if the Swift SDK had produced the expected bytes and the Kotlin
SDK were then checked against them, the two agreeing would prove only that one copied
the other. Agreeing with an outside implementation is the actual evidence.

```sh
python3 Contracts/fixtures/derive-expected-values.py --write
```

Rerunning it reproduces this directory byte for byte, and `MANIFEST.json` records the
SHA-256 of every file so drift is caught by the validator and by both suites.

## Secrets

There are none. `proof/proof-input.json` carries a synthetic key string
(`spfn-test-key-not-a-secret-0001`) that authenticates nothing, was never issued by
anything, and must never be presented to a real endpoint. No real key, token or
credential appears in this directory.
