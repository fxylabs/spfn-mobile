#!/usr/bin/env python3
"""Derives the expected values in this fixture directory.

Why this exists
---------------
A conformance fixture is only evidence if its expected values were produced by
something other than the implementations under test. If the Swift SDK wrote the
expected bytes and the Kotlin SDK were then checked against them, the two agreeing
would prove only that one copied the other.

So this file is a third, independent implementation of SPFN-CANON-JSON-1 and
SPFN-PROOF-INPUT-1, written against the contract text in
Contracts/spfn-mobile-contract.v1.json and using nothing but the Python standard
library. It is a development aid: no build step, test or validator runs it.

Usage
-----
    python3 Contracts/fixtures/derive-expected-values.py --write

Every emitted value is a pure function of the vector inputs below, so rerunning it
reproduces the fixture directory byte for byte.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Obviously synthetic. Never a real key, never used against a real endpoint.
TEST_KEY_UTF8 = "spfn-test-key-not-a-secret-0001"

PROFILE = "clientProofV1"
ABSENT_BODY_DIGEST = "0" * 64
REPLAY_WINDOW_MILLIS = 300000


# --------------------------------------------------------------------------
# SPFN-CANON-JSON-1, implemented from the contract text.
# --------------------------------------------------------------------------

SHORT_ESCAPES = {0x08: "\\b", 0x0C: "\\f", 0x0A: "\\n", 0x0D: "\\r", 0x09: "\\t"}


def canonical_string(text: str) -> str:
    out = ['"']
    for character in text:
        code = ord(character)
        if character == '"':
            out.append('\\"')
        elif character == "\\":
            out.append("\\\\")
        elif code in SHORT_ESCAPES:
            out.append(SHORT_ESCAPES[code])
        elif code < 0x20:
            out.append("\\u%04x" % code)
        else:
            out.append(character)
    out.append('"')
    return "".join(out)


def canonical(value) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return canonical_string(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical(element) for element in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=lambda key: key.encode("utf-8"))
        return "{" + ",".join(canonical_string(key) + ":" + canonical(value[key]) for key in keys) + "}"
    raise TypeError("SPFN-CANON-JSON-1 has no encoding for %r" % (value,))


def parse_strict(text: str):
    """Only used to round-trip the `input` field of a vector, so the fixture can carry
    a non-canonical input and a canonical expectation side by side."""
    return json.loads(text, parse_float=_reject_float, object_pairs_hook=_reject_duplicate)


def _reject_float(raw):
    raise ValueError("non-integer number %s" % raw)


def _reject_duplicate(pairs):
    seen = {}
    for key, value in pairs:
        if key in seen:
            raise ValueError("duplicate key %s" % key)
        seen[key] = value
    return seen


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------
# SPFN-PROOF-INPUT-1, implemented from the contract text.
# --------------------------------------------------------------------------

def proof_input_string(fields: dict) -> str:
    ordered = [
        PROFILE,
        fields["method"],
        fields["path"],
        fields["clientId"],
        fields["keyId"],
        fields["nonce"],
        str(fields["issuedAtMillis"]),
        fields["bodySha256"],
    ]
    for value in ordered:
        if any(ord(character) < 0x20 for character in value):
            raise ValueError("control character in proof input")
    return "\n".join(ordered)


def proof_hmac(fields: dict) -> str:
    return hmac.new(
        TEST_KEY_UTF8.encode("utf-8"),
        proof_input_string(fields).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


# --------------------------------------------------------------------------
# Vectors
# --------------------------------------------------------------------------

CANONICAL_VECTORS = [
    ("key-order-ascii", '{"b":1,"a":2,"C":3}',
     "uppercase C sorts before lowercase letters because ordering is by UTF-8 bytes"),
    ("nested-object-and-array", '{"z":[1,2,{"b":true,"a":null}],"y":"x"}',
     "ordering applies at every depth; array order is preserved"),
    ("escapes", '{"k":"line\\nbreak\\ttab\\"quote\\\\slash"}',
     "the five short escapes and the two mandatory ones"),
    ("control-characters", '{"k":"\\u0001\\u001f"}',
     "C0 controls with no short form use lowercase \\u00XX"),
    ("unicode-literal", '{"k":"\ud55c\uad6d\uc5b4 \u2713"}',
     "non-ASCII is emitted literally as UTF-8, never re-escaped"),
    ("int64-bounds", '{"min":-9223372036854775808,"max":9223372036854775807}',
     "the full signed 64-bit range survives a round trip"),
    ("key-order-utf8-vs-utf16", '{"\U00010000":1,"\uff21":2}',
     "U+FF21 sorts BEFORE U+10000 by UTF-8 bytes and AFTER it by UTF-16 code units; "
     "a platform that sorted keys with its native string comparison would fail here"),
    ("empty-containers", '{"o":{},"a":[]}',
     "empty object and empty array have canonical forms"),
]

CANONICAL_REJECTS = [
    ("duplicate-key", '{"a":1,"a":2}', "DUPLICATE_KEY"),
    ("fractional-number", '{"n":1.5}', "NON_INTEGER_NUMBER"),
    ("exponent-number", '{"n":1e3}', "NON_INTEGER_NUMBER"),
    ("trailing-content", '{"a":1} x', "TRAILING_CONTENT"),
    ("unterminated-object", '{"a":', "UNEXPECTED_END"),
    ("raw-control-in-string", '{"a":"' + chr(1) + '"}', "INVALID_TOKEN"),
    ("invalid-escape", '{"a":"\\q"}', "INVALID_ESCAPE"),
    ("lone-surrogate-escape", '{"a":"\\ud800"}', "INVALID_ESCAPE"),
]

PROOF_VECTORS = [
    {
        "name": "handshake-no-body",
        "why": "an operation with no body carries the absent-body digest, not the digest of an empty string",
        "input": {
            "method": "POST",
            "path": "/v1/auth/client-proof/handshake",
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce-000000000001",
            "issuedAtMillis": 1750000000000,
            "bodySha256": ABSENT_BODY_DIGEST,
        },
    },
    {
        "name": "echo-with-canonical-body",
        "why": "bodySha256 is taken over the canonical body, so it is stable across key order",
        "input": {
            "method": "POST",
            "path": "/v1/echo",
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce-000000000002",
            "issuedAtMillis": 1750000000000,
            "bodySha256": sha256_hex(canonical({"message": "hello", "sequence": 7})),
        },
    },
    {
        "name": "unicode-in-nonce",
        "why": "proof input is UTF-8, so a non-ASCII nonce must not change length semantics",
        "input": {
            "method": "POST",
            "path": "/v1/items/list",
            "clientId": "client-test-0002",
            "keyId": "key-test-0002",
            "nonce": "nonce-한국-0003",
            "issuedAtMillis": 1750000000999,
            "bodySha256": sha256_hex(canonical({"limit": 25})),
        },
    },
]

PROOF_REJECTS = [
    {
        "name": "newline-in-nonce",
        "field": "nonce",
        "input": {
            "method": "POST",
            "path": "/v1/echo",
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce\n-injected",
            "issuedAtMillis": 1750000000000,
            "bodySha256": ABSENT_BODY_DIGEST,
        },
    },
    {
        "name": "tab-in-path",
        "field": "path",
        "input": {
            "method": "POST",
            "path": "/v1/e\tcho",
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce-000000000004",
            "issuedAtMillis": 1750000000000,
            "bodySha256": ABSENT_BODY_DIGEST,
        },
    },
]

REQUEST_VECTORS = [
    {
        "name": "handshake",
        "operationId": "auth.clientProof.handshake",
        "type": "HandshakeRequest",
        "value": {
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce-000000000001",
            "issuedAtMillis": 1750000000000,
        },
    },
    {
        "name": "echo",
        "operationId": "echo.send",
        "type": "EchoRequest",
        "value": {"message": "hello", "sequence": 7},
    },
    {
        "name": "items-list-without-cursor",
        "operationId": "items.list",
        "type": "ListItemsRequest",
        "value": {"limit": 25},
        "why": "an absent optional field is omitted from the canonical form, never written as null",
    },
    {
        "name": "items-list-with-cursor",
        "operationId": "items.list",
        "type": "ListItemsRequest",
        "value": {"limit": 25, "cursor": "cursor-000000000010"},
    },
]

RESPONSE_VECTORS = [
    {
        "name": "handshake-response",
        "type": "HandshakeResponse",
        "value": {"sessionId": "session-test-0001", "expiresAtMillis": 1750000300000},
    },
    {
        "name": "echo-response",
        "type": "EchoResponse",
        "value": {"message": "hello", "sequence": 7, "serverTimeMillis": 1750000000123},
    },
    {
        "name": "items-list-response",
        "type": "ListItemsResponse",
        "value": {
            "items": [
                {"id": "item-0001", "name": "first", "updatedAtMillis": 1750000000001},
                {"id": "item-0002", "name": "second", "updatedAtMillis": 1750000000002},
            ]
        },
        "why": "an array of nested objects plus an absent optional nextCursor",
    },
]

ERROR_VECTORS = [
    ("PROOF_INVALID", 401, False),
    ("PROOF_REPLAYED", 401, False),
    ("PROOF_EXPIRED", 401, False),
    ("SESSION_REVOKED", 401, False),
    ("PROFILE_REJECTED", 400, False),
    ("CONTRACT_UNSUPPORTED", 409, False),
]

BASE_PROOF = {
    "method": "POST",
    "path": "/v1/echo",
    "clientId": "client-test-0001",
    "keyId": "key-test-0001",
    "bodySha256": ABSENT_BODY_DIGEST,
}


def replay_vectors():
    def step(nonce, issued, now, expect):
        fields = dict(BASE_PROOF, nonce=nonce, issuedAtMillis=issued)
        return {
            "nonce": nonce,
            "issuedAtMillis": issued,
            "nowMillis": now,
            "proof": proof_hmac(fields),
            "expect": expect,
        }

    issued = 1750000000000
    return [
        {
            "name": "same-nonce-twice",
            "why": "a (clientId, nonce) pair is spendable exactly once inside the window",
            "steps": [
                step("nonce-replay-01", issued, issued + 1000, "accept"),
                step("nonce-replay-01", issued, issued + 2000, "PROOF_REPLAYED"),
            ],
        },
        {
            "name": "distinct-nonces",
            "why": "a fresh nonce is unaffected by an earlier one",
            "steps": [
                step("nonce-replay-02", issued, issued + 1000, "accept"),
                step("nonce-replay-03", issued, issued + 1000, "accept"),
            ],
        },
        {
            "name": "outside-window",
            "why": "expiry is checked before replay, so a stale proof is PROOF_EXPIRED",
            "steps": [
                step("nonce-replay-04", issued, issued + REPLAY_WINDOW_MILLIS + 1, "PROOF_EXPIRED"),
            ],
        },
        {
            "name": "issued-in-the-future",
            "why": "a negative age is refused rather than treated as fresh",
            "steps": [
                step("nonce-replay-05", issued, issued - 1, "PROOF_EXPIRED"),
            ],
        },
        {
            "name": "wrong-proof",
            "why": "a proof that does not verify is PROOF_INVALID, checked after replay",
            "steps": [
                {
                    "nonce": "nonce-replay-06",
                    "issuedAtMillis": issued,
                    "nowMillis": issued + 1000,
                    "proof": "0" * 64,
                    "expect": "PROOF_INVALID",
                },
            ],
        },
    ]


def revoke_vectors():
    issued = 1750000000000
    fields = dict(BASE_PROOF, nonce="nonce-revoke-01", issuedAtMillis=issued)
    return [
        {
            "name": "revoked-key-before-proof-check",
            "why": "revocation is decided before the proof is verified, so a revoked key with a "
                   "perfectly valid proof still reports SESSION_REVOKED rather than PROOF_INVALID",
            "revokedKeyIds": [BASE_PROOF["keyId"]],
            "steps": [
                {
                    "nonce": "nonce-revoke-01",
                    "issuedAtMillis": issued,
                    "nowMillis": issued + 1000,
                    "proof": proof_hmac(fields),
                    "expect": "SESSION_REVOKED",
                },
            ],
        },
        {
            "name": "revoked-key-with-stale-proof",
            "why": "revocation also precedes the expiry check, so the reason never depends on timing",
            "revokedKeyIds": [BASE_PROOF["keyId"]],
            "steps": [
                {
                    "nonce": "nonce-revoke-02",
                    "issuedAtMillis": issued,
                    "nowMillis": issued + REPLAY_WINDOW_MILLIS + 5000,
                    "proof": "0" * 64,
                    "expect": "SESSION_REVOKED",
                },
            ],
        },
        {
            "name": "unrevoked-key-still-accepted",
            "why": "revoking one key does not revoke the rest",
            "revokedKeyIds": ["key-test-9999"],
            "steps": [
                {
                    "nonce": "nonce-revoke-03",
                    "issuedAtMillis": issued,
                    "nowMillis": issued + 1000,
                    "proof": proof_hmac(dict(BASE_PROOF, nonce="nonce-revoke-03", issuedAtMillis=issued)),
                    "expect": "accept",
                },
            ],
        },
    ]


# --------------------------------------------------------------------------
# Emission
# --------------------------------------------------------------------------

NOTE = ("Expected values were derived by Contracts/fixtures/derive-expected-values.py, a third "
        "implementation independent of both SDKs. Two SDKs agreeing with each other would prove "
        "nothing; agreeing with an outside implementation is the actual evidence.")

SECRET_NOTE = ("TEST VECTOR ONLY. keyUtf8 is a synthetic string, not a credential. It authenticates "
               "nothing, was never issued by anything, and must never be presented to a real endpoint.")


def build_canonical():
    vectors = []
    for name, raw_input, why in CANONICAL_VECTORS:
        value = parse_strict(raw_input)
        encoded = canonical(value)
        vectors.append({
            "name": name,
            "why": why,
            "input": raw_input,
            "canonical": encoded,
            "sha256": sha256_hex(encoded),
        })
    return {"algorithm": "SPFN-CANON-JSON-1", "note": NOTE, "vectors": vectors}


def build_canonical_rejects():
    return {
        "algorithm": "SPFN-CANON-JSON-1",
        "note": "Each input must be refused with exactly the named error code, on both platforms.",
        "vectors": [
            {"name": name, "input": raw_input, "errorCode": code}
            for name, raw_input, code in CANONICAL_REJECTS
        ],
    }


def build_proof():
    vectors = []
    for vector in PROOF_VECTORS:
        text = proof_input_string(vector["input"])
        vectors.append({
            "name": vector["name"],
            "why": vector["why"],
            "input": vector["input"],
            "canonicalString": text,
            "canonicalSha256": sha256_hex(text),
            "proofHmacSha256": proof_hmac(vector["input"]),
        })
    return {
        "algorithm": "SPFN-PROOF-INPUT-1",
        "profile": PROFILE,
        "note": NOTE,
        "syntheticKey": {"note": SECRET_NOTE, "keyUtf8": TEST_KEY_UTF8},
        "vectors": vectors,
    }


def build_proof_rejects():
    return {
        "algorithm": "SPFN-PROOF-INPUT-1",
        "note": "A C0 control character in any proof field is refused, because the newline "
                "separator would otherwise be ambiguous.",
        "vectors": [
            {"name": vector["name"], "field": vector["field"], "input": vector["input"],
             "errorCode": "PROOF_INPUT_INVALID"}
            for vector in PROOF_REJECTS
        ],
    }


def build_requests():
    return {
        "note": NOTE,
        "requests": [
            {
                "name": vector["name"],
                "operationId": vector["operationId"],
                "type": vector["type"],
                "why": vector.get("why", ""),
                "value": vector["value"],
                "canonical": canonical(vector["value"]),
                "sha256": sha256_hex(canonical(vector["value"])),
            }
            for vector in REQUEST_VECTORS
        ],
        "responses": [
            {
                "name": vector["name"],
                "type": vector["type"],
                "why": vector.get("why", ""),
                "wire": canonical(vector["value"]),
                "value": vector["value"],
                "canonical": canonical(vector["value"]),
                "sha256": sha256_hex(canonical(vector["value"])),
            }
            for vector in RESPONSE_VECTORS
        ],
    }


def build_errors():
    known = []
    for code, status, retryable in ERROR_VECTORS:
        envelope = {"error": {"code": code, "message": "test vector for " + code,
                              "requestId": "req-" + code.lower().replace("_", "-")}}
        known.append({
            "name": code.lower().replace("_", "-"),
            "wire": canonical(envelope),
            "code": code,
            "httpStatus": status,
            "retryable": retryable,
            "sha256": sha256_hex(canonical(envelope)),
        })

    unknown_envelope = {"error": {"code": "SOMETHING_ELSE_V2", "message": "a code this SDK does not know",
                                 "requestId": "req-unknown"}}
    return {
        "note": NOTE,
        "known": known,
        "rejected": [{
            "name": "unknown-code-is-not-mapped-to-a-neighbour",
            "why": "an unrecognised code surfaces as an unknown-code failure carrying the raw string, "
                   "rather than being rounded to the nearest known code",
            "wire": canonical(unknown_envelope),
            "rawCode": "SOMETHING_ELSE_V2",
            "errorCode": "UNKNOWN_ERROR_CODE",
        }],
    }


def build_replay():
    return {
        "note": NOTE,
        "profile": PROFILE,
        "replayWindowMillis": REPLAY_WINDOW_MILLIS,
        "base": BASE_PROOF,
        "vectors": replay_vectors(),
    }


def build_revoke():
    return {
        "note": NOTE,
        "profile": PROFILE,
        "replayWindowMillis": REPLAY_WINDOW_MILLIS,
        "base": BASE_PROOF,
        "vectors": revoke_vectors(),
    }


FILES = {
    "canonical/serialization.json": build_canonical,
    "canonical/rejects.json": build_canonical_rejects,
    "proof/proof-input.json": build_proof,
    "proof/rejects.json": build_proof_rejects,
    "request/operations.json": build_requests,
    "error/envelopes.json": build_errors,
    "replay/replay.json": build_replay,
    "revoke/revoke.json": build_revoke,
}


def render(payload) -> str:
    return json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def main() -> int:
    if "--write" not in sys.argv:
        print(__doc__)
        return 2

    written = []
    for relative, builder in sorted(FILES.items()):
        target = os.path.join(HERE, relative)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        body = render(builder())
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(body)
        digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
        written.append({"path": "Contracts/fixtures/" + relative,
                        "sha256": digest,
                        "bytes": len(body.encode("utf-8"))})
        print("wrote %s" % relative)

    manifest = {
        "status": "RESOLVED_DEV_BUNDLE",
        "contractVersion": "1.0.0-dev.1",
        "bundleSha256": bundle_digest(),
        "fixtureCount": len(written),
        "derivedBy": "Contracts/fixtures/derive-expected-values.py",
        "note": NOTE,
        "secrets": SECRET_NOTE,
        "fixtures": written,
    }
    manifest_body = render(manifest)
    with open(os.path.join(HERE, "MANIFEST.json"), "w", encoding="utf-8") as handle:
        handle.write(manifest_body)
    print("wrote MANIFEST.json")
    return 0


def bundle_digest() -> str:
    bundle = os.path.join(HERE, "..", "spfn-mobile-contract.v1.json")
    with open(bundle, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


if __name__ == "__main__":
    sys.exit(main())
