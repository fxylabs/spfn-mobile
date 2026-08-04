#!/usr/bin/env python3
"""Derives the expected values in this fixture directory.

Why this exists
---------------
A conformance fixture is only evidence if its expected values were produced by
something other than the implementations under test. If the Swift SDK wrote the
expected bytes and the Kotlin SDK were then checked against them, the two agreeing
would prove only that one copied the other.

So this file is a third, independent implementation of SPFN-CANON-JSON-1,
SPFN-PROOF-INPUT-1 and P-256 ECDSA, written against the contract text in
Contracts/spfn-mobile-contract.json and using nothing but the Python standard
library. It is a development aid: no build step, test or validator runs it.

Signatures under contract 0.2.0
-------------------------------
The proof is an ECDSA P-256 signature, and a platform signer uses a random
per-signature nonce, so an SDK's own proof bytes cannot be pinned here. The
fixtures are therefore two-tier:

- the canonical proof-input bytes and every bodySha256 stay byte-pinned, exactly
  as before;
- each `signatureRsHex` / `proof` value in this directory is a signature this
  script produced with the fixed test keypair and an RFC 6979 deterministic
  nonce, so the file itself stays byte-reproducible. A platform test verifies
  these fixture signatures with the fixture public key (its verifier accepts an
  externally produced signature), and judges its own signer by verification
  rather than byte equality.

Usage
-----
    python3 Contracts/fixtures/derive-expected-values.py --write

Every emitted value is a pure function of the vector inputs below, so rerunning it
reproduces the fixture directory byte for byte.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

PROFILE = "clientProofV1"
ABSENT_BODY_DIGEST = "0" * 64
REPLAY_WINDOW_MILLIS = 300000

BUNDLE_PATH = os.path.join(HERE, "..", "spfn-mobile-contract.json")
LOCK_PATH = os.path.join(HERE, "..", "upstream.lock.json")

# --------------------------------------------------------------------------
# The fixed test keypairs.
#
# TEST ONLY — NOT SECRETS. Both keypairs are restated byte for byte from SPFN
# primitives packages/auth/src/server/client-proof/__tests__/test-keys.ts, where
# they were generated once and frozen. They authenticate nothing, were never
# issued by anything, and publishing the private halves is intentional: the
# upstream dev server pre-registers the primary public half, which is what lets
# the integration matrix run against it without provisioning a secret.
# --------------------------------------------------------------------------

TEST_KEY_ID = "key-test-0001"

TEST_PUBLIC_KEY_SPKI_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAES7xktjK+fMydT7UZcfuW/vzU9rU/"
    "+RPVVQKKgxrB1sd9bh6N1bqiBwU/zuw9/LaQ91lWPeWSN9OlT8OlDYXIYg=="
)

TEST_PRIVATE_KEY_PKCS8_B64 = (
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgMv3D4UvmGKjFeG3m"
    "yLLfwlcOAQ9n8qoFmwrgGWBErsShRANCAARLvGS2Mr58zJ1PtRlx+5b+/NT2tT/5"
    "E9VVAoqDGsHWx31uHo3VuqIHBT/O7D38tpD3WVY95ZI306VPw6UNhchi"
)

WRONG_KEY_ID = "key-test-0002"

WRONG_PUBLIC_KEY_SPKI_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEvA1Qe3c98K4+u/Gb5ORnGhqRGUU"
    "J6oCVYoxRdp5b0OiRS75v5ULruknszTl9+zd8yQ817hOPjWzdJiijXSQzw=="
)

WRONG_PRIVATE_KEY_PKCS8_B64 = (
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgcBSWaGkYFpu+WAjD"
    "NOwFXF1ubNfelYWjFmMRn97+69OhRANCAAQS8DVB7dz3wrj678Zvk5GcaGpEZRQn"
    "qgJVijFF2nlvQ6JFLvm/lQuu6SezNOX37N3zJDzXuE4+NbN0mKKNdJDP"
)


def load_bundle() -> dict:
    """The contract text these vectors are derived from.

    The wire vectors below read their header names from here rather than restating
    them, so a header renamed in the bundle changes the fixture instead of quietly
    disagreeing with it.
    """
    with open(BUNDLE_PATH, encoding="utf-8") as handle:
        return json.load(handle)


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


def spki_sha256_hex(spki_b64: str) -> str:
    """The contract's key fingerprint: lowercase base16 SHA-256 of the SPKI DER bytes."""
    return hashlib.sha256(base64.b64decode(spki_b64)).hexdigest()


# --------------------------------------------------------------------------
# P-256 ECDSA, pure standard library.
#
# Implemented from the curve constants in FIPS 186-4 / SEC 2 (secp256r1) so the
# signatures here are independent of both platform signers: CryptoKit and the
# JCA are judged against this, never against each other. Affine arithmetic with
# modular inversion — slow and fine for a development aid.
# --------------------------------------------------------------------------

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def _point_add(left, right):
    if left is None:
        return right
    if right is None:
        return left
    (x1, y1), (x2, y2) = left, right
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if left == right:
        slope = (3 * x1 * x1 + A) * pow(2 * y1, -1, P) % P
    else:
        slope = (y2 - y1) * pow(x2 - x1, -1, P) % P
    x3 = (slope * slope - x1 - x2) % P
    y3 = (slope * (x1 - x3) - y1) % P
    return (x3, y3)


def _point_mul(scalar: int, point):
    result = None
    addend = point
    while scalar:
        if scalar & 1:
            result = _point_add(result, addend)
        addend = _point_add(addend, addend)
        scalar >>= 1
    return result


def _on_curve(point) -> bool:
    if point is None:
        return False
    x, y = point
    return (y * y - (x * x * x + A * x + B)) % P == 0


def _rfc6979_nonce(scalar: int, digest: bytes) -> int:
    """Deterministic per-signature nonce (RFC 6979, SHA-256, q of 256 bits)."""
    x = scalar.to_bytes(32, "big")
    h1 = (int.from_bytes(digest, "big") % N).to_bytes(32, "big")
    v = b"\x01" * 32
    k = b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        candidate = int.from_bytes(v, "big")
        if 1 <= candidate < N:
            return candidate
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def ecdsa_sign_raw(scalar: int, message: bytes) -> bytes:
    """Raw r||s, two 32-byte big-endian integers — the contract's wire encoding."""
    digest = hashlib.sha256(message).digest()
    z = int.from_bytes(digest, "big")
    k = _rfc6979_nonce(scalar, digest)
    while True:
        point = _point_mul(k, (GX, GY))
        r = point[0] % N
        s = pow(k, -1, N) * (z + r * scalar) % N
        if r != 0 and s != 0:
            return r.to_bytes(32, "big") + s.to_bytes(32, "big")
        k = (k + 1) % N or 1


def ecdsa_verify_raw(public_point, message: bytes, signature: bytes) -> bool:
    if len(signature) != 64 or not _on_curve(public_point):
        return False
    r = int.from_bytes(signature[:32], "big")
    s = int.from_bytes(signature[32:], "big")
    if not (1 <= r < N and 1 <= s < N):
        return False
    z = int.from_bytes(hashlib.sha256(message).digest(), "big")
    w = pow(s, -1, N)
    point = _point_add(
        _point_mul(z * w % N, (GX, GY)),
        _point_mul(r * w % N, public_point),
    )
    if point is None:
        return False
    return point[0] % N == r


def der_signature(signature: bytes) -> bytes:
    """The DER SEQUENCE{INTEGER r, INTEGER s} form — used only as a reject vector."""

    def integer(value: int) -> bytes:
        body = value.to_bytes((value.bit_length() + 7) // 8 or 1, "big")
        if body[0] & 0x80:
            body = b"\x00" + body
        return b"\x02" + bytes([len(body)]) + body

    payload = integer(int.from_bytes(signature[:32], "big")) + integer(int.from_bytes(signature[32:], "big"))
    return b"\x30" + bytes([len(payload)]) + payload


# --------------------------------------------------------------------------
# Minimal DER reading, only what the two fixed key encodings need.
# --------------------------------------------------------------------------

def _der_elements(blob: bytes):
    """The (tag, value) pairs at one DER level."""
    elements = []
    index = 0
    while index < len(blob):
        tag = blob[index]
        length = blob[index + 1]
        index += 2
        if length & 0x80:
            count = length & 0x7F
            length = int.from_bytes(blob[index:index + count], "big")
            index += count
        elements.append((tag, blob[index:index + length]))
        index += length
    return elements


def scalar_from_pkcs8(b64: str) -> int:
    """The private scalar inside a PKCS#8-wrapped SEC1 ECPrivateKey."""
    outer = _der_elements(base64.b64decode(b64))
    assert outer[0][0] == 0x30, "not a PKCS#8 PrivateKeyInfo"
    fields = _der_elements(outer[0][1])
    assert fields[2][0] == 0x04, "PKCS#8 carries no privateKey OCTET STRING"
    ec_private_key = _der_elements(_der_elements(fields[2][1])[0][1])
    assert ec_private_key[1][0] == 0x04 and len(ec_private_key[1][1]) == 32, \
        "ECPrivateKey.privateKey is not 32 bytes"
    return int.from_bytes(ec_private_key[1][1], "big")


def point_from_spki(b64: str):
    """The uncompressed public point inside an SPKI SubjectPublicKeyInfo."""
    outer = _der_elements(base64.b64decode(b64))
    fields = _der_elements(outer[0][1])
    assert fields[1][0] == 0x03, "SPKI carries no BIT STRING"
    bits = fields[1][1]
    assert bits[0] == 0x00 and bits[1] == 0x04 and len(bits) == 66, \
        "SPKI public key is not an uncompressed P-256 point"
    return (int.from_bytes(bits[2:34], "big"), int.from_bytes(bits[34:66], "big"))


TEST_SCALAR = scalar_from_pkcs8(TEST_PRIVATE_KEY_PKCS8_B64)
TEST_PUBLIC_POINT = point_from_spki(TEST_PUBLIC_KEY_SPKI_B64)
WRONG_SCALAR = scalar_from_pkcs8(WRONG_PRIVATE_KEY_PKCS8_B64)
WRONG_PUBLIC_POINT = point_from_spki(WRONG_PUBLIC_KEY_SPKI_B64)

# The two halves of each fixed keypair must actually be halves of one keypair,
# or every "verification passed" below would be a statement about nothing.
assert _point_mul(TEST_SCALAR, (GX, GY)) == TEST_PUBLIC_POINT, \
    "the test private key does not derive the test public key"
assert _point_mul(WRONG_SCALAR, (GX, GY)) == WRONG_PUBLIC_POINT, \
    "the wrong-key private key does not derive its public key"


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


def proof_signature(fields: dict, scalar: int = TEST_SCALAR, public_point=TEST_PUBLIC_POINT) -> str:
    """A proof over `fields`: raw r||s as base16-lower, self-verified before emission."""
    message = proof_input_string(fields).encode("utf-8")
    signature = ecdsa_sign_raw(scalar, message)
    assert ecdsa_verify_raw(public_point, message, signature), \
        "a signature this script produced did not verify against its own public key"
    assert not ecdsa_verify_raw(public_point, message + b"x", signature), \
        "the verifier accepted a tampered message, so it discriminates nothing"
    return signature.hex()


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
    ("unicode-literal", '{"k":"한국어 ✓"}',
     "non-ASCII is emitted literally as UTF-8, never re-escaped"),
    ("int64-bounds", '{"min":-9223372036854775808,"max":9223372036854775807}',
     "the full signed 64-bit range survives a round trip"),
    ("key-order-utf8-vs-utf16", '{"\U00010000":1,"Ａ":2}',
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

# Each entry is one fully assembled outbound request: the exact header names, the exact
# header values and the exact body bytes an SDK must put on the wire. `sessionId` is the
# session an earlier handshake returned; a vector without one is a request that must
# carry no session header at all. The `proof` header is the one value an SDK cannot
# reproduce byte for byte — its signer draws a random nonce — so a suite compares every
# other header exactly and judges the proof by verification against `testKeyPair`.
WIRE_VECTORS = [
    {
        "name": "handshake",
        "operationId": "auth.clientProof.handshake",
        "why": "the handshake carries every proof header and no session header, and its "
               "body is digested like any other body rather than treated as absent",
        "clientId": "client-test-0001",
        "keyId": "key-test-0001",
        "nonce": "nonce-000000000001",
        "issuedAtMillis": 1750000000000,
        "sessionId": None,
        "body": {
            "clientId": "client-test-0001",
            "keyId": "key-test-0001",
            "nonce": "nonce-000000000001",
            "issuedAtMillis": 1750000000000,
        },
    },
    {
        "name": "echo-with-session",
        "operationId": "echo.send",
        "why": "a requiresSession operation carries the session header last; every other "
               "header is assembled exactly as it is for the handshake",
        "clientId": "client-test-0001",
        "keyId": "key-test-0001",
        "nonce": "nonce-000000000002",
        "issuedAtMillis": 1750000000000,
        "sessionId": "session-test-0001",
        "body": {"message": "hello", "sequence": 7},
    },
    {
        "name": "rotate-key",
        "operationId": "auth.keys.rotate",
        "why": "a proven session-free operation carries every proof header and no session "
               "header; the proof is the OLD key's while the body registers the replacement "
               "(here the second fixture keypair)",
        "clientId": "client-test-0001",
        "keyId": "key-test-0001",
        "nonce": "nonce-000000000003",
        "issuedAtMillis": 1750000000000,
        "sessionId": None,
        "body": {
            "publicKey": WRONG_PUBLIC_KEY_SPKI_B64,
            "keyId": WRONG_KEY_ID,
            "fingerprint": spki_sha256_hex(WRONG_PUBLIC_KEY_SPKI_B64),
            "algorithm": "ES256",
        },
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

# 128 hex characters that are not the signature of anything: r = s = 0 is outside
# [1, n-1] on every curve, so no verifier disagreement is possible about it.
NOT_A_SIGNATURE = "0" * 128


def replay_vectors():
    def step(nonce, issued, now, expect):
        fields = dict(BASE_PROOF, nonce=nonce, issuedAtMillis=issued)
        return {
            "nonce": nonce,
            "issuedAtMillis": issued,
            "nowMillis": now,
            "proof": proof_signature(fields),
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
                    "proof": NOT_A_SIGNATURE,
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
                    "proof": proof_signature(fields),
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
                    "proof": NOT_A_SIGNATURE,
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
                    "proof": proof_signature(dict(BASE_PROOF, nonce="nonce-revoke-03", issuedAtMillis=issued)),
                    "expect": "accept",
                },
            ],
        },
    ]


def signature_reject_vectors():
    """Presented proofs the verifier must refuse, over the handshake-no-body input.

    Format violations and a wrong-key signature in one table, because they answer one
    question: does the platform verifier refuse everything that is not a valid raw-r||s
    base16-lower signature under the named key. Discriminance is built in — the DER,
    uppercase and truncated entries are derived from a signature that DOES verify in
    its correct form, so a verifier that ignores encoding admits them and fails here.
    """
    fields = PROOF_VECTORS[0]["input"]
    valid = proof_signature(fields)
    der_hex = der_signature(bytes.fromhex(valid)).hex()
    wrong_key = proof_signature(fields, scalar=WRONG_SCALAR, public_point=WRONG_PUBLIC_POINT)
    return [
        {
            "name": "der-encoded",
            "why": "the same signature that verifies as raw r||s must be refused in DER; "
                   "a platform signer that emits DER converts before sending, never the verifier",
            "vector": "handshake-no-body",
            "presented": der_hex,
        },
        {
            "name": "uppercase-hex",
            "why": "the wire encoding is base16-lower; an uppercase digest is a different string",
            "vector": "handshake-no-body",
            "presented": valid.upper(),
        },
        {
            "name": "truncated-63-bytes",
            "why": "a raw signature is exactly 64 bytes; 126 hex characters is not one",
            "vector": "handshake-no-body",
            "presented": valid[:126],
        },
        {
            "name": "not-hex",
            "why": "128 characters that are not all hex digits never reach the curve",
            "vector": "handshake-no-body",
            "presented": "g" + valid[1:],
        },
        {
            "name": "wrong-key",
            "why": "a well-formed signature by another key must fail verification, not formatting",
            "vector": "handshake-no-body",
            "presented": wrong_key,
        },
        {
            "name": "all-zero",
            "why": "r = s = 0 is outside [1, n-1] and can never verify",
            "vector": "handshake-no-body",
            "presented": NOT_A_SIGNATURE,
        },
    ]


# --------------------------------------------------------------------------
# Emission
# --------------------------------------------------------------------------

NOTE = ("Expected values were derived by Contracts/fixtures/derive-expected-values.py, a third "
        "implementation independent of both SDKs. Two SDKs agreeing with each other would prove "
        "nothing; agreeing with an outside implementation is the actual evidence.")

SECRET_NOTE = ("TEST KEYPAIR ONLY — NOT A SECRET. The keypair is restated byte for byte from SPFN "
               "primitives __tests__/test-keys.ts, where publishing the private half is intentional. "
               "It authenticates nothing, was never issued by anything, and must never be presented "
               "to a real endpoint.")

SIGNATURE_NOTE = ("Every proof value in this directory was signed by derive-expected-values.py with "
                  "the test keypair and an RFC 6979 deterministic nonce, so the files reproduce byte "
                  "for byte. A platform signer draws a random nonce, so its proofs are judged by "
                  "verification against the test public key rather than byte equality.")


def test_keypair_block() -> dict:
    return {
        "note": SECRET_NOTE,
        "origin": "SPFN primitives packages/auth/src/server/client-proof/__tests__/test-keys.ts",
        "keyId": TEST_KEY_ID,
        "publicKeySpkiBase64": TEST_PUBLIC_KEY_SPKI_B64,
        "privateKeyPkcs8Base64": TEST_PRIVATE_KEY_PKCS8_B64,
    }


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
            "signatureRsHex": proof_signature(vector["input"]),
        })
    return {
        "algorithm": "SPFN-PROOF-INPUT-1",
        "profile": PROFILE,
        "note": NOTE,
        "signatureNote": SIGNATURE_NOTE,
        "testKeyPair": test_keypair_block(),
        "wrongKeyPair": {
            "note": SECRET_NOTE,
            "origin": "SPFN primitives packages/auth/src/server/client-proof/__tests__/test-keys.ts",
            "keyId": WRONG_KEY_ID,
            "publicKeySpkiBase64": WRONG_PUBLIC_KEY_SPKI_B64,
            "privateKeyPkcs8Base64": WRONG_PRIVATE_KEY_PKCS8_B64,
        },
        "vectors": vectors,
        "signatureRejects": signature_reject_vectors(),
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


def build_wire():
    bundle = load_bundle()
    mapping = bundle["wireMapping"]
    names = mapping["headers"]
    operations = {operation["id"]: operation for operation in bundle["operations"]}

    vectors = []
    for vector in WIRE_VECTORS:
        operation = operations[vector["operationId"]]
        canonical_body = canonical(vector["body"])
        body_sha256 = sha256_hex(canonical_body)
        proof = proof_signature({
            "method": operation["method"],
            "path": operation["path"],
            "clientId": vector["clientId"],
            "keyId": vector["keyId"],
            "nonce": vector["nonce"],
            "issuedAtMillis": vector["issuedAtMillis"],
            "bodySha256": body_sha256,
        })

        headers = [["content-type", mapping["requestContentType"]]]
        values = {
            "profile": PROFILE,
            "clientId": vector["clientId"],
            "keyId": vector["keyId"],
            "nonce": vector["nonce"],
            "issuedAtMillis": str(vector["issuedAtMillis"]),
            "proof": proof,
            "session": vector["sessionId"],
        }
        for field in mapping["headerOrder"]:
            if values[field] is not None:
                headers.append([names[field], values[field]])

        if operation["requiresSession"] != (vector["sessionId"] is not None):
            raise ValueError("vector %s disagrees with the bundle about requiresSession" % vector["name"])

        vectors.append({
            "name": vector["name"],
            "why": vector["why"],
            "operationId": operation["id"],
            "method": operation["method"],
            "path": operation["path"],
            "requiresSession": operation["requiresSession"],
            "sessionId": vector["sessionId"],
            "body": vector["body"],
            "canonicalBody": canonical_body,
            "bodySha256": body_sha256,
            "proof": proof,
            "headers": headers,
        })

    return {
        "note": NOTE,
        "profile": PROFILE,
        "signatureNote": SIGNATURE_NOTE,
        "testKeyPair": test_keypair_block(),
        "requestContentType": mapping["requestContentType"],
        "headerNames": names,
        "headerOrder": mapping["headerOrder"],
        "sessionRule": mapping["sessionRule"],
        "vectors": vectors,
    }


def build_enrollment():
    """The enrollment surface: the fingerprint rule and the exact unproven wire shape.

    Hand-derived from the contract's `restOperations` and `clientProofV1` sections and
    the fixed test keypairs (P10): nothing here was produced by either SDK. The idToken
    follows the reference server's fixed test rule — see its `SpfnReferenceRestOps` —
    so the same bytes drive the unit suites and the integration matrix.
    """
    bundle = load_bundle()
    operations = {operation["id"]: operation for operation in bundle["operations"]}
    oauth_native = operations["auth.enroll.oauthNative"]
    provider = "google"
    # The contract's `nativeEnrollment.nonceRule`: the nonce IS the fingerprint. Google
    # echoes the raw value, so the same string appears in the token, in `nonce` and in
    # `fingerprint` — one local, so the three cannot drift apart here either.
    fingerprint = spki_sha256_hex(TEST_PUBLIC_KEY_SPKI_B64)
    body = {
        "idToken": "spfn-test-idtoken.google.user-test-0001." + fingerprint,
        "nonce": fingerprint,
        "publicKey": TEST_PUBLIC_KEY_SPKI_B64,
        "keyId": TEST_KEY_ID,
        "fingerprint": fingerprint,
        "algorithm": "ES256",
    }
    canonical_body = canonical(body)
    return {
        "note": NOTE,
        "why": "both SDK enrollment flows must reproduce these bytes exactly: the body is "
               "the contract's OauthNativeRequest over the fixed test keypair, the nonce is "
               "the key's fingerprint as nativeEnrollment.nonceRule requires, and the only "
               "header an unproven request carries is the content type",
        "testKeyPair": test_keypair_block(),
        "fingerprints": {
            "rule": "lowercase base16 SHA-256 of the SPKI DER — the same bytes publicKey "
                    "carries base64-encoded. Both platforms must agree byte for byte.",
            "testKeySpkiSha256Hex": spki_sha256_hex(TEST_PUBLIC_KEY_SPKI_B64),
            "wrongKeySpkiSha256Hex": spki_sha256_hex(WRONG_PUBLIC_KEY_SPKI_B64),
        },
        "oauthNative": {
            "operationId": oauth_native["id"],
            "pathTemplate": oauth_native["path"],
            "provider": provider,
            "path": oauth_native["path"].replace("{provider}", provider),
            "headers": [["content-type", bundle["wireMapping"]["requestContentType"]]],
            "value": body,
            "canonical": canonical_body,
            "sha256": sha256_hex(canonical_body),
        },
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
        "signatureNote": SIGNATURE_NOTE,
        "replayWindowMillis": REPLAY_WINDOW_MILLIS,
        "base": BASE_PROOF,
        "vectors": replay_vectors(),
    }


def build_revoke():
    return {
        "note": NOTE,
        "profile": PROFILE,
        "signatureNote": SIGNATURE_NOTE,
        "replayWindowMillis": REPLAY_WINDOW_MILLIS,
        "base": BASE_PROOF,
        "vectors": revoke_vectors(),
    }


FILES = {
    "canonical/serialization.json": build_canonical,
    "canonical/rejects.json": build_canonical_rejects,
    "enrollment/enrollment.json": build_enrollment,
    "proof/proof-input.json": build_proof,
    "proof/rejects.json": build_proof_rejects,
    "request/operations.json": build_requests,
    "request/wire.json": build_wire,
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

    lock = load_lock()
    manifest = {
        "status": lock["status"],
        "contractVersion": lock["contract"]["version"],
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
    with open(BUNDLE_PATH, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def load_lock() -> dict:
    """Status and contract version come from the lock, never from a constant here.

    They were written out by hand until the contract moved upstream, and a hand-written
    copy of a fact recorded elsewhere is a copy that goes stale without saying so.
    """
    with open(LOCK_PATH, "r", encoding="utf-8") as handle:
        return json.load(handle)


if __name__ == "__main__":
    sys.exit(main())
