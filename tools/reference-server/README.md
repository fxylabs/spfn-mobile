# `:reference-server`

A local server that implements the pinned contract, so both SDKs can be driven over real
HTTP instead of against a stand-in transport.

```sh
sh tools/reference-server/run-integration.sh      # start it, run both suites, stop it
```

That one command is the gate. It starts the server, runs the Swift integration suite in
one process and the Android integration suite in another, checks that every case really
ran, stops the server, and fails if anything is left holding a port.

## Launching it by hand

`SpfnReferenceMain` is the entry point the runner starts, and the only way to reach this
server from another process.

```
usage: SpfnReferenceMain [--port <n>] [--port-file <path>] [--session-ttl-millis <n>]
                         [--parent-pid <n>] [--test-clock <startMillis>]
```

| Flag | Effect |
| --- | --- |
| `--port` | the port to bind; 0, the default, asks the operating system for a free one |
| `--port-file` | where to write the launch object — `baseUrl`, `controlToken`, `port` |
| `--session-ttl-millis` | the TTL of sessions this server opens |
| `--parent-pid` | a process to watch: the server exits when it is gone |
| `--test-clock` | run on a clock `/control/advance-clock` can move, starting at this instant |

Every flag needs a value, and one without a value is a usage error rather than a default.

Without `--test-clock` the launch runs on the wall clock, `/control/advance-clock` answers
409, and any case that has to reach an expiry is out of scope. `run-integration.sh` passes
`--test-clock 1750000000000` — `SpfnReferenceTestClock.DEFAULT_START_MILLIS` written out,
because a shell script cannot read a Kotlin constant — exactly when it tells the suites
the clock moves. It is one clock: session expiry, the proof replay window, device-code
expiry and the `core.time` answer the SDKs anchor their proof clock to all read it, so a
suite on a launched test clock and the server sit at the same instant.

## Against a server this repository did not write

```sh
SPFN_INTEGRATION_TARGET_URL=http://127.0.0.1:8791 \
SPFN_INTEGRATION_CONTROL_TOKEN=... \
    sh tools/reference-server/run-integration.sh

SPFN_INTEGRATION_LAUNCH_FILE=/path/to/launch.json \
    sh tools/reference-server/run-integration.sh
```

Same ten cases, same receipts, same exit rules. The target has to be running already and
has to expose the `/control` routes below; the launch file is the object `SpfnReferenceMain`
writes and the SPFN primitives mobile contract surface writes too, holding `baseUrl` and
`controlToken`.

Why bother: everything the run proves today it proves against the server in this
directory, which is two ends built from one reading of the contract. A server nobody here
wrote is a second reading, and the disagreements it finds are the ones a shared codebase
cannot.

In this mode the script starts nothing and stops nothing. It probes the target, checks the
control token before a suite runs, and ends by checking the target is still up — the
orphan sweep is skipped on purpose, because a target may be a reference server of its own
and killing somebody else's process is the script reaching outside its own run.

A named target that cannot be used stops the run. There is no fall back to a local server:
a run that checked this server while reporting the other one would claim the strongest
evidence here while producing none of it.

The Android suite takes the same target directly, for a run without the script:

```sh
./gradlew :reference-server:spfnIntegrationTest \
    -Pspfn.integrationReceipts=/tmp/receipts \
    -Pspfn.integrationTargetUrl=http://127.0.0.1:8791 \
    -Pspfn.integrationLaunchFile=/path/to/launch.json
```

## This is a test fixture

Not a deployment, not a mock service, and not an SPFN endpoint. It binds the loopback
interface only, it keeps nothing on disk, and the only key it pre-registers is the
public half of the fixed test keypair `Contracts/fixtures/proof/proof-input.json`
already publishes as `testKeyPair` (`key-test-0001`), marked TEST KEYPAIR ONLY there
and here — the private half is published on purpose and authenticates nothing. Other
public keys arrive only through `/control/register-key`, and only the public half
ever crosses that wire.

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
| `POST /control/advance-clock` | moves an injected test clock; refused with 409 on the system clock |

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

Every case arranges its state through one control surface, which is a method call when the
server is in process and `/control` over HTTP when it is not, so the ten case bodies are
the same code whichever server they run against. Two things a case cannot assert against
an external server, because they are properties of the injected test clock rather than of
the contract: the exact instant `echo.send` answers with, and a session dying at the
expiry the server advertised. The first is asserted exactly in process and as "a real
instant" otherwise; the second moved to `SpfnReferenceServerTest`, where the clock is
injected and a test moves it by hand instead of sleeping through a TTL.

## What it does not prove

This server is not a real SPFN server, and a passing local run proves only that the two
SDKs and this server agree about the contract. That contract is no longer one this
repository invented — it is the SPFN primitives export pinned in `Contracts/` — but
implementing the same bundle correctly twice is still not evidence about a deployed
service.

The gap closes through external-target mode, and it has been closed once: the full matrix
passes against the primitives `04-mobile-contract-dev` server, which is the canonical
implementation rather than a second reading of the same text. Against a deployed service
it remains untested.

## Logs

One line per request: method, path, status. No nonce, no proof, no session identifier, no
body, no key. `SpfnReferenceServerTest` runs a full exchange and fails if any value the
request carried turns up in the log.
