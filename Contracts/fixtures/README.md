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
| `enrollment/enrollment.json` | the key fingerprint rule (SHA-256 of the SPKI DER, base16-lower) for both fixture keypairs, and the exact unproven wire shape of the native social enrollment request |
| `proof/proof-input.json` | the canonical `clientProofV1` proof input string, its digest, a deterministic ECDSA P-256 signature over it, the fixed test keypair, and the signature reject table |
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

There are none. `proof/proof-input.json`, `request/wire.json` and
`enrollment/enrollment.json` carry a fixed P-256
test keypair — private half included — restated byte for byte from SPFN primitives
`__tests__/test-keys.ts`, where publishing it is intentional. It authenticates
nothing, was never issued by anything, and must never be presented to a real
endpoint. No real key, token or credential appears in this directory.

The proof under contract 0.2.0 is an ECDSA signature, and a platform signer draws a
random per-signature nonce, so the fixtures are two-tier: proof-input bytes and every
`bodySha256` stay byte-pinned, while each recorded `proof` / `signatureRsHex` value is
a deterministic (RFC 6979) signature the derivation script produced with the test
keypair. A platform verifies those recorded signatures with the test public key, and
judges its own signer by verification rather than byte equality.
