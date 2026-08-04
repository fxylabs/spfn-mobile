# Release policy

**Two alpha releases have been made.** `0.1.0-alpha.3` (source commit `70781e4`) is
current and `0.1.0-alpha.2` is superseded; both are on Maven Central under
`xyz.superfunction.spfn` with matching prefix-free SwiftPM tags. Publication did not
turn this into a finished SDK: it is still a scaffold with one vertical slice, nothing
has run on a device, and every support row in `COMPATIBILITY.md` stays UNRESOLVED. What
is proven is that the published coordinates resolve, verify and compile — not that the
SDK works in the field.

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
CycloneDX SBOMs for both platforms. Maven artifacts are PGP-signed at publication
because Central requires it; contract-bundle signing and build provenance are still
open and are added for public releases.

What remains: a person accepting the RC evidence the harness produces. The registry
account track is done for Maven — the `xyz.superfunction` namespace is verified on the
Central Portal and a signing identity exists — but creating or using any further
registry account, namespace or signing identity stays a person's work under a separate
approval that no candidate run implies.

## Verifying a published release

`rc-verify.sh` answers whether a candidate *could* be published. It stages to a local
directory and resolves a local tag, so a passing candidate says nothing about what
actually reached a registry. `tools/rc-verify/verify-published.sh` closes that gap from
the other side: it reads no build output and no staging directory, only the network.

    ANDROID_HOME=~/Library/Android/sdk sh tools/rc-verify/verify-published.sh [version]

Per module it fetches the AAR, POM, Gradle module metadata and sources jar from
repo1.maven.org, recomputes each published sha256 sidecar, verifies each detached PGP
signature, checks the POM for the metadata set Central requires and the MIT license
(D8), and opens the AAR — an AAR with no `classes.jar` resolves and checksums exactly
like a real one. Then an Android consumer compiles against `mavenCentral()` as the only
source for SPFN coordinates with `--refresh-dependencies`, so a warm local cache cannot
answer for the registry, and a SwiftPM consumer resolves the public Git URL at the exact
version, compares the resolved revision against the local tag and runs a smoke
executable that touches one symbol in every product. Every check runs and every failure
is printed; the exit code is non-zero if any failed.

Artifacts are signed with RSA key `1CC7BD2E870BC4B2A279EB5BCB666532EB4E568A`
(`rayim (spfn-mobile) <rayim@fxy.global>`), published on `keyserver.ubuntu.com`. That
fingerprint is the value to compare against — a signature that verifies against a key
fetched by its own key id proves only that one key signed everything.

## Current state of the machinery

A manual path to Maven Central now exists, and everything about it fails closed.
`.github/workflows/publish-central.yml` is `workflow_dispatch` only — no push trigger,
no tag trigger — re-runs the RC verification against a person-named commit, signs with
an in-memory PGP key injected from GitHub Actions secrets, and uploads the staged
bundle to the Central Portal as `USER_MANAGED`, where it is held until a person
confirms it in the Portal UI. The three secrets it names (`CENTRAL_PORTAL_TOKEN`,
`SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_PASSWORD`) are registered in GitHub
Actions and nowhere else; a dispatch reaching `USER_MANAGED` is as far as the machinery
goes, and the Portal confirmation stays a person's click.
`.github/workflows/release-candidate.yml` stays inert.

Gradle itself never reaches Central. `spfn.publishing.enabled` is `false` in
`gradle.properties` and the root build script reads that committed value from the file
itself: a tree committed with `true` fails every build, and a per-run CLI override may
target only an absolute staging directory outside the repository —
`tools/validate/probe-publishing-gate.sh` proves each refusal.
`tools/validate/validate.sh` fails if the committed flag flips, a publication or
signing block appears outside the gated root script, a credential value or
credential-shaped property is committed, a key file enters the tree, a repository
outside the approved three appears, any workflow's parsed trigger set contains
anything but `workflow_dispatch` (flow-style and block-style alike, unknown trigger
kinds included), the publish workflow names an unlisted secret, addresses a host other
than the Central Portal with or without a URL scheme, interpolates an input into run
text, or a CocoaPods trunk publication command is added anywhere —
`tools/validate/probe-publication-rules.sh` proves each of those refusals bites.
