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

D3 and D7 are now resolved (2026-08-03) and no longer block. D3: release-candidate
verification goes exactly as far as a no-publish candidate — `tools/rc-verify/rc-verify.sh`
resolves a local tag through SwiftPM, stages Maven artifacts to a local `$TMPDIR`
directory, and removes the tag when it exits. D7: alpha candidates are unsigned; the
candidate identity is the source commit, `SHA256SUMS` and the candidate manifest, with
CycloneDX SBOMs for both platforms. Signing and provenance are added for public releases.

What remains: a person accepting the RC evidence the harness produces, and the registry
account track — creating or using any registry account, namespace or signing identity is
a person's work under a separate approval that no candidate run implies.

## Current state of the machinery

A manual path to Maven Central now exists, and everything about it fails closed.
`.github/workflows/publish-central.yml` is `workflow_dispatch` only — no push trigger,
no tag trigger — re-runs the RC verification against a person-named commit, signs with
an in-memory PGP key injected from GitHub Actions secrets, and uploads the staged
bundle to the Central Portal as `USER_MANAGED`, where it is held until a person
confirms it in the Portal UI. None of the secrets (`CENTRAL_PORTAL_TOKEN`,
`SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_PASSWORD`) is registered, so every
dispatch fails today; that is the designed state until a person registers them under
their own approval. `.github/workflows/release-candidate.yml` stays inert.

Gradle itself never reaches Central. `spfn.publishing.enabled` is `false` in
`gradle.properties` and the root build script reads that committed value from the file
itself: a tree committed with `true` fails every build, and a per-run CLI override may
target only an absolute staging directory outside the repository —
`tools/validate/probe-publishing-gate.sh` proves each refusal.
`tools/validate/validate.sh` fails if the committed flag flips, a publication or
signing block appears outside the gated root script, a credential value or
credential-shaped property is committed, a key file enters the tree, a repository
outside the approved three appears, any workflow gains an automatic trigger, the
publish workflow names an unlisted secret or a host other than the Central Portal, or
a CocoaPods trunk publication command is added anywhere —
`tools/validate/probe-publication-rules.sh` proves each of those refusals bites.
