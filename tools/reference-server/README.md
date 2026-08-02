# `:reference-server`

A local server that implements the pinned contract, so both SDKs can be driven over real
HTTP instead of against a stand-in transport.

```sh
sh tools/reference-server/run-integration.sh      # start it, run both suites, stop it
```

That one command is the gate. It starts the server, runs the Swift integration suite in
one process and the Android integration suite in another, checks that every case really
ran, stops the server, and fails if anything is left holding a port.

## This is a test fixture

Not a deployment, not a mock service, and not an SPFN endpoint. It binds the loopback
interface only, it keeps nothing on disk, and the only key it will verify a proof against
is the synthetic vector `Contracts/fixtures/proof/proof-input.json` already publishes:
`key-test-0001` / `spfn-test-key-not-a-secret-0001`, marked TEST VECTOR ONLY there and
here. That value authenticates nothing and was never issued by anything.

## Boundary

A non-published Kotlin/JVM Gradle module, registered the way `:contract-codegen` is and
excluded from every SDK-module check for the same reason: it is a tool, not something
anyone links against. `main` has zero external dependencies — the HTTP layer is the JDK's
own `com.sun.net.httpserver`.

The verification primitives are not reimplemented here. The canonical serializer, the
digests, the proof input and the generated contract listing are compiled straight out of
`android/spfn-core`, `android/spfn-auth` and `android/spfn-generated` by source-directory
sharing, because an Android library cannot be a dependency of a JVM module and a second
copy of SPFN-CANON-JSON-1 would turn the round trip into two copies agreeing with each
other. The independence that makes the exchange evidence comes from elsewhere:
`Contracts/fixtures/derive-expected-values.py` is a third implementation, and the Swift
suite crosses the same wire from a different codebase.

## What the server enforces

| Rule | Outcome |
| --- | --- |
| method and path name a contract operation, with no query string | otherwise `CONTRACT_UNSUPPORTED` |
| every contract header field appears exactly once | otherwise `CONTRACT_UNSUPPORTED` |
| the body's bytes are the canonical form of the value they encode | otherwise `CONTRACT_UNSUPPORTED` |
| the session header is present exactly on the operations that require one | otherwise `CONTRACT_UNSUPPORTED` |
| the named auth profile is on the allowlist | otherwise `PROFILE_REJECTED` |
| the key is not revoked and the session is live | otherwise `SESSION_REVOKED` |
| `issuedAtMillis` is inside the 300 000 ms replay window | otherwise `PROOF_EXPIRED` |
| `(clientId, nonce)` has not been spent inside that window | otherwise `PROOF_REPLAYED` |
| the proof verifies | otherwise `PROOF_INVALID` |

The last four run in that order because the contract fixes it: a revoked key is refused
before the proof is verified, so revocation stays distinguishable from a bad proof.
`SpfnReferenceCheckOrderTest` presents every combination of the four grounds to this
server and to the SDK's own `SpfnProofAcceptance` and fails if they ever name a different
one.

`bodySha256` is taken over the bytes that arrived, never over a re-encoding of them.
Digesting what the server produced would make the digest agree with itself no matter what
the client sent.

### Why `CONTRACT_UNSUPPORTED` carries the shape failures

The contract declares six codes and forbids inventing a seventh, so every refusal has to
be one of them. A malformed request is not fixed by a new session, which rules out the
four auth-family codes: the SDK re-handshakes exactly once on those, and it would be
re-sending the same malformed bytes. `PROFILE_REJECTED` names one specific thing, and this
server uses it for exactly that thing. What is left is `CONTRACT_UNSUPPORTED`, which reads
as "the two ends do not agree about what the contract is" — which is what a non-canonical
body, an unroutable path or a missing header field is. `SpfnReferenceRefusal` states the
same reasoning next to the code it hands out.

## `/control` is not part of the contract

Nothing under `/control` appears in the bundle and no SDK knows it exists. It answers
plain objects rather than contract envelopes, so a control failure can never be read as a
contract code. It exists because two cases in the matrix — a revoked key and a dropped
session — are things only a server can cause, and the Swift suite drives the server from
another process where there is no object to call a method on.

| Route | Effect |
| --- | --- |
| `GET /control/health` | readiness; the only route that needs no token |
| `GET /control/stats` | request and per-operation counters |
| `POST /control/reset` | back to the state the server started in |
| `POST /control/expire-sessions` | drops held sessions without touching the expiry advertised |
| `POST /control/revoke-key` | revokes a key and the sessions it opened |
| `POST /control/session-ttl` | changes the TTL of sessions opened from now on |
| `POST /control/hold` | makes the next requests to one path wait, so a timeout has something to time out on |
| `POST /control/advance-clock` | moves an injected test clock; refused on the system clock |

Every route except health requires the token the launch generated. It is written to the
runner's launch file and is never printed or logged.

## What the integration matrix proves

| Case | Claim |
| --- | --- |
| a | handshake, `echo.send` and `items.list` answer their declared types with the values sent |
| b | a session the server dropped costs exactly one re-handshake, and the refused attempt was never applied |
| c | a revocation the client cannot fix surfaces as an auth failure after the re-handshake is refused too |
| d | a request replayed byte for byte is refused as `PROOF_REPLAYED` |
| e | a timeout and a cancellation both work while a real server is holding the call open |

Both suites run all five. Each case writes a receipt file, and `run-integration.sh` fails
unless all ten are on disk afterwards — because a skipped XCTest is reported as a passing
XCTest, and an integration suite that quietly skips is the most expensive kind of green
there is.

## What it does not prove

Nothing here has spoken to a real SPFN server. The contract this server implements is the
hand-authored development bundle in `Contracts/`, and the exchange proves the two SDKs and
this server agree about it — not that any deployed service does.

## Logs

One line per request: method, path, status. No nonce, no proof, no session identifier, no
body, no key. `SpfnReferenceServerTest` runs a full exchange and fails if any value the
request carried turns up in the log.
