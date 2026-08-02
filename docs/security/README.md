# Security boundary

See [../../SECURITY.md](../../SECURITY.md) for the current status and the list of
boundaries that are enforced today. This page explains why the boundary is shaped the
way it is.

## Single auth profile

v1 exposes exactly one auth profile, `clientProofV1`. The second profile that was
considered — a redirect-based browser flow — is not implemented and is not present as a
symbol, endpoint, schema or fixture. It waits for verified interoperability demand and a
separate approval.

That decision is enforced mechanically rather than by review habit. The validator scans
`Sources/`, `Tests/`, `android/`, `Contracts/`, `examples/` and `.github/` for
redirect-auth vocabulary and fails on any hit. Documentation is excluded from that scan
precisely so pages like this one can describe the prohibition.

## Why an allowlist rather than a denylist

A denylist grows one entry behind each new idea. The allowlist is the enum itself:
`SPFNAuthProfile` has one case, `SPFNAuthPolicy.allowedProfiles` must equal
`allCases`, and an unrecognised profile name throws instead of resolving. Adding a
profile is therefore a visible, single-line security change that no reviewer can miss.

## Hybrid

The WebView adapter exposes no bridge. `allowedBridgeMessageNames` is an empty explicit
list on both platforms rather than an absent check, so a future generic passthrough
bridge has to delete a guarantee rather than merely forget one. No credential crosses
into web content, because no path exists for one to travel.

## Fail-closed defaults

Three defaults are chosen to break loudly rather than quietly:

- Gradle dependency verification is on, and every artifact the build resolves carries a
  real SHA-256 in `gradle/verification-metadata.xml`. The first artifact without one
  fails the build instead of slipping in.
- Every unimplemented entry point throws rather than returning an empty success, so a
  partial build cannot be mistaken for a working one.
- The generator refuses to run when the bundle digest does not match the lock, so a
  tampered bundle produces no clients rather than plausible ones.

## Why revocation is checked before the proof

`SPFNProofAcceptance` decides revocation, then expiry, then replay, then the MAC. The
order is part of the contract and is pinned by fixtures, because collapsing revocation
into `PROOF_INVALID` would make a revoked key indistinguishable from a bad one. An
operator reading logs needs to tell those apart; so does an incident responder.

The same reasoning applies to the unknown error code. Rounding an unrecognised code to
the nearest known one turns a contract mismatch into a silently wrong error path, so the
generated enum refuses and preserves the raw string.

## Why the proof input refuses control characters

The canonical proof input joins eight fields with a newline. Any field able to contain a
newline could forge the field boundaries, so a C0 control character in any field is an
error rather than something to escape. An escaping scheme would be one more thing two
platforms can implement differently, and a divergence there is a forgeable proof.

## Provenance discipline as a security property

A fabricated contract digest reads exactly like a verified one, and a fabricated
provenance record reads exactly like a real one. The validator therefore recomputes the
pinned digest from the file it names, and refuses any lock claiming an upstream CI
export unless upstream evidence is present on disk. That evidence now exists, so the
rule turned around: the claim is checked against `Contracts/upstream-provenance.json`
field by field — origin, digest, exporter version, repository, version and range — and
evidence naming this repository as the source fails, because that is what a locally
authored bundle dressed up as an export looks like.

The bundle's origin is stated in three places: its own text, the lock, and the header of
every generated source file. This is a supply-chain control, not bookkeeping. What it
does not do is prove the pinned commit exists upstream; that check needs a runner able to
reach the primitives repository, and nothing here claims otherwise.
