# Release policy

**No release has been made.** No tag exists, no artifact has been built or signed, no
registry has been configured, and no account or signing identity has been created. This
repository is a scaffold with one vertical slice.

## Hard boundary

A successful candidate build is **not** approval to release. Before any tag push,
repository publication, registry upload or release creation, a person must approve all
of:

- the exact source commit and tag
- the imported contract digest
- the Swift distribution channel (Git or registry) and the Maven target and coordinates
- every artifact hash
- the SBOM and the intended disclosure scope

App Store and Play Store accounts, certificates and app submissions are a separate
approval track entirely and are not implied by any SDK release approval.

## Versioning

- One mobile release train, one `VERSION` value, shared by iOS and Android in lockstep.
- Prefix-free full SemVer tags (`0.1.0-alpha.1`, never `mobile-v…` or `ios/…`), because
  SwiftPM interprets Git tags as SemVer.
- An Android-only fix still bumps the shared version and produces a new tag. The
  changelog records that Swift sources were unchanged; the same version never means two
  different source commits.
- Pre-1.0 does not mean "break anything anytime". Breaking API or contract changes take
  a minor bump and a migration note; compatible fixes take a patch bump; alpha and beta
  channels use prerelease identifiers.
- Contract versions are independent SemVer and are never mixed with the auth profile
  name `clientProofV1`. An SDK declares the contract range it supports.
- Published versions are immutable. A mistake is fixed by a new version, never by
  replacing an existing one.

## Supply chain

The release source archive, Maven artifacts (AAR, POM, sources, docs), SBOM,
`SHA256SUMS` and the contract bundle digest belong to one candidate manifest. Dependency
caches are never treated as release provenance: a release reproduces a clean build and
is verified by source commit, contract digest and artifact hash.

## What blocks a release today, beyond the approvals

D17 no longer blocks a release. The pinned bundle is generated and published by SPFN
primitives and copied here at commit `d31aa9a1`; both platform suites and the integration
matrix pass against a primitives server running that contract, so the SDK is no longer a
client for a contract this repository invented.

What remains are the approvals themselves and the decisions Step 5 has to settle: registry
staging (D3), signing and provenance for published artifacts (D7), and the release
candidate evidence a person has to accept before anything is published.

## Current state of the machinery

`.github/workflows/release-candidate.yml` exists but is inert and manual-only. It
performs no build, holds no credential and reads no secret. `spfn.publishing.enabled`
is `false` in `gradle.properties`, and `tools/validate/validate.sh` fails if publication
is enabled, a repository outside the approved three appears, a credential block shows
up, or a CocoaPods trunk publication command is added anywhere.
