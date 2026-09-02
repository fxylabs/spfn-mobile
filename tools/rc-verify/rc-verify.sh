#!/bin/sh
# SPFN Mobile — release-candidate verification harness.
#
# Decision D3 (resolved 2026-08-03): the release candidate is verified WITHOUT
# publishing anything. No registry, no account, no remote tag. This script produces the
# whole of that evidence in one reproducible run:
#
#   0. Receipts — the fifteen-cell social sign-in case table, proven on real phones
#                 against the contract this repository pins. No build can stand in for
#                 it: tools/device-receipts/receipt-gate.sh judges the committed device
#                 receipts and refuses a candidate that has none for the pinned contract.
#   1. SwiftPM  — creates the prefix-free local tag `<VERSION>` (D9), resolves it from
#                 a throwaway consumer package via `.package(url: "file://…", exact:)`,
#                 builds it, and runs a smoke executable that imports every public
#                 product and touches one symbol in each.
#   2. Maven    — stages every Android module (AAR + POM + sources) to a $TMPDIR
#                 directory through the publication gate in the root build script, then
#                 compiles a throwaway Android consumer project against the staged
#                 coordinates `<group>:<module>:<VERSION>`.
#   3. SBOM     — CycloneDX for both platforms (D7): the Gradle plugin for Android,
#                 static generation for iOS where the dependency set is empty by design.
#   4. Manifest — SHA256SUMS over every candidate artifact plus manifest.json binding
#                 the source commit, the pinned contract digest, the resolved tag and
#                 every artifact hash into one candidate identity (D7: alpha is
#                 unsigned; commit + digests + SBOM are the provenance).
#
# Everything lands under one $TMPDIR output directory, printed at the end and NEVER
# inside the repository. The local tag and every intermediate directory are removed by
# a trap on every exit path, success or failure; only the output directory survives.
#
#   ANDROID_HOME=~/Library/Android/sdk sh tools/rc-verify/rc-verify.sh
#
# Requirements: macOS with the Swift toolchain, an Android SDK, network access for
# Gradle dependency resolution, a clean working tree, and no existing `<VERSION>` tag.
# Any step failing exits non-zero.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

VERSION=$(tr -d '[:space:]' < VERSION)
GRADLE="./gradlew --console=plain"
CREATED_TAG=0

die()
{
    printf 'rc-verify FAIL: %s\n' "$1" >&2
    if [ -n "${OUT:-}" ]
    then
        printf 'evidence kept at: %s\n' "$OUT" >&2
    fi
    exit 1
}

step()
{
    printf '\n== %s\n' "$1"
}

# --- preconditions -----------------------------------------------------------------

command -v swift > /dev/null 2>&1 || die 'swift toolchain not found'
[ -x ./gradlew ] || die 'gradlew not found at the repository root'

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]
then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi
[ -n "${ANDROID_HOME:-}" ] || die 'ANDROID_HOME is not set and no default SDK exists'

# The candidate is a commit, so the tree must BE that commit. A dirty tree would tag
# one thing and stage another: SwiftPM consumes the committed state through the tag
# while Gradle stages the working tree.
if [ -n "$(git status --porcelain)" ]
then
    die 'working tree is not clean; a candidate is verified from a commit, not a diff'
fi

if [ -n "$(git tag -l "$VERSION")" ]
then
    die "tag $VERSION already exists; this harness creates and removes its own local tag"
fi

COMMIT=$(git rev-parse HEAD)
CONTRACT_DIGEST=$(sed -n 's/.*"manifestSha256": *"\([0-9a-f]\{64\}\)".*/\1/p' Contracts/upstream.lock.json | head -1)
[ -n "$CONTRACT_DIGEST" ] || die 'could not read manifestSha256 from Contracts/upstream.lock.json'
MAVEN_GROUP=$(sed -n 's/^spfn.maven.group=\(.*\)$/\1/p' gradle.properties)
[ -n "$MAVEN_GROUP" ] || die 'could not read spfn.maven.group from gradle.properties'
AGP_VERSION=$(sed -n 's/^agp = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
KOTLIN_VERSION=$(sed -n 's/^kotlin = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
COMPILE_SDK=$(sed -n 's/^compile-sdk = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
MIN_SDK=$(sed -n 's/^min-sdk = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
[ -n "$AGP_VERSION" ] && [ -n "$KOTLIN_VERSION" ] && [ -n "$COMPILE_SDK" ] && [ -n "$MIN_SDK" ] \
    || die 'could not read the toolchain baseline from gradle/libs.versions.toml'

# --- workspace ---------------------------------------------------------------------

# OUT survives the run: it is the candidate evidence. WORK never does. SPFN_RC_OUT
# lets a caller (the publish workflow) name the evidence directory. It is held to the
# same rule the Gradle staging gate enforces for spfn.staging.dir — absolute, and
# canonically OUTSIDE this repository, so a symlink pointing back into the tree is
# refused too — and it must be empty, so one run can never mix its artifacts into
# another's.
if [ -n "${SPFN_RC_OUT:-}" ]
then
    case "$SPFN_RC_OUT" in
        /*) ;;
        *) die "SPFN_RC_OUT '$SPFN_RC_OUT' is not an absolute path" ;;
    esac
    # Checked twice on purpose: on the literal path before anything is created, and
    # canonically (pwd -P) after, so a symlink that points back into the tree is
    # refused as well. A directory this refusal itself created is removed again.
    case "$SPFN_RC_OUT" in
        "$ROOT" | "$ROOT"/*)
            die "SPFN_RC_OUT '$SPFN_RC_OUT' is inside the repository; evidence never enters the tree"
            ;;
    esac
    OUT_CREATED=0
    if [ ! -d "$SPFN_RC_OUT" ]
    then
        OUT_CREATED=1
    fi
    mkdir -p "$SPFN_RC_OUT"
    OUT=$(CDPATH= cd -- "$SPFN_RC_OUT" 2>/dev/null && pwd -P) \
        || die "SPFN_RC_OUT '$SPFN_RC_OUT' cannot be resolved"
    ROOT_REAL=$(CDPATH= cd -- "$ROOT" && pwd -P)
    case "$OUT" in
        "$ROOT_REAL" | "$ROOT_REAL"/*)
            if [ "$OUT_CREATED" = "1" ]
            then
                rmdir "$SPFN_RC_OUT" 2>/dev/null || true
            fi
            die "SPFN_RC_OUT '$SPFN_RC_OUT' resolves inside the repository; evidence never enters the tree"
            ;;
    esac
    if [ -n "$(find "$OUT" -mindepth 1 -print -quit)" ]
    then
        die "SPFN_RC_OUT '$OUT' is not empty; refusing to mix candidate evidence"
    fi
else
    OUT=$(mktemp -d "${TMPDIR:-/tmp}/spfn-rc-${VERSION}.XXXXXX")
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/spfn-rc-work.XXXXXX")
STAGING="$OUT/staging"
SBOM_DIR="$OUT/sbom"
LOGS="$OUT/logs"
mkdir -p "$STAGING" "$SBOM_DIR" "$LOGS"

# Idempotent: the signal path and the EXIT path may both reach it, and a second
# signal arriving mid-sweep must find nothing half-done to redo. Deleting the tag
# resets CREATED_TAG so a re-entry cannot delete a tag this run did not create.
sweep()
{
    if [ "$CREATED_TAG" = "1" ]
    then
        git -C "$ROOT" tag -d "$VERSION" > /dev/null 2>&1 || true
        CREATED_TAG=0
    fi
    rm -rf "$WORK"
    # SwiftPM drops zero-byte advisory .lock markers directly in $TMPDIR, named after
    # the flattened path of the directory they guarded. The directory is gone; sweep
    # the markers that name it too.
    find "${TMPDIR:-/tmp}" -maxdepth 1 -type f -name "*$(basename "$WORK")*" -delete 2>/dev/null || true
}

cleanup()
{
    status=$?
    sweep
    exit "$status"
}

# On a signal, `$?` inside the trap is the status of the last COMPLETED command —
# usually 0 — not the interruption, so a killed run routed through the EXIT trap
# alone would clean up perfectly and then lie with exit 0. The signal traps exit
# with the conventional 128+N themselves, and disarm every trap first so the exit
# cannot re-enter cleanup and a second signal cannot re-enter the sweep.
on_signal()
{
    trap '' EXIT INT TERM
    sweep
    exit "$1"
}

trap cleanup EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

printf 'SPFN Mobile RC verification — candidate %s at commit %s\n' "$VERSION" "$COMMIT"
printf 'output: %s\n' "$OUT"

# --- 0. Device receipts: real phones proved the social sign-in case table ----------
#
# Everything else this script does can be done by a machine with no phone attached, and
# that is exactly the gap: the provider sheet, the platform key store and a real server
# only meet on a device, so no build, no simulator and no emulator can stand in for the
# evidence. A candidate is therefore not verifiable without receipts from a real device
# run against the contract this repository currently pins — and re-pinning the contract
# retires that evidence, because proof about a 0.9.0 wire contract says nothing about
# the next one. The 2026-09-02 re-pin to 0.10.0 did exactly that, so this step refuses
# every candidate until a person re-runs the fifteen cells against a 0.3.0-beta.8 server.
#
# It runs first because it is the cheapest step and the one most likely to be the reason
# a candidate is not ready: failing here costs a second and creates no tag.

step 'device receipts: the fifteen-cell social sign-in case table, proven on real phones'

# The gate takes two environment overrides so its own probe can point it at fixtures.
# On the publication path they are exactly what an operator must not be able to set: a
# stray SPFN_RECEIPT_ROOT in a shell would send the gate to a directory somebody wrote
# by hand and let a candidate out with no device evidence at all. Cleared here, so this
# run always judges the committed receipts against the committed lock.
unset SPFN_RECEIPT_ROOT SPFN_RECEIPT_LOCK

if sh tools/device-receipts/receipt-gate.sh > "$LOGS/receipt-gate.log" 2>&1
then
    sed 's/^/  /' "$LOGS/receipt-gate.log"
else
    sed 's/^/  /' "$LOGS/receipt-gate.log" >&2
    die 'the device receipt gate refused; a candidate is not verified without device evidence for the pinned contract'
fi

# The manifest records what the gate counted, so the candidate's provenance names its
# device evidence. Read from the gate's own summary line rather than recomputed here: two
# counts of the same thing drift, and the one in the manifest must be the one that passed.
if ! RECEIPT_SUMMARY=$(grep '^RECEIPT-GATE-SUMMARY ' "$LOGS/receipt-gate.log")
then
    die 'the device receipt gate passed without printing its summary line; the manifest would claim evidence nobody counted'
fi
RECEIPT_REQUIRED=$(printf '%s' "$RECEIPT_SUMMARY" | sed -n 's/.*cells=\([0-9][0-9]*\).*/\1/p')
RECEIPT_PROVEN=$(printf '%s' "$RECEIPT_SUMMARY" | sed -n 's/.*proven=\([0-9][0-9]*\).*/\1/p')
RECEIPT_FILES=$(printf '%s' "$RECEIPT_SUMMARY" | sed -n 's/.*scanned=\([0-9][0-9]*\).*/\1/p')
RECEIPT_CONTRACT=$(printf '%s' "$RECEIPT_SUMMARY" | sed -n 's/.*contract=\([^ ]*\).*/\1/p')
[ -n "$RECEIPT_REQUIRED" ] && [ -n "$RECEIPT_PROVEN" ] && [ -n "$RECEIPT_FILES" ] \
    && [ -n "$RECEIPT_CONTRACT" ] \
    || die 'the device receipt gate summary line could not be parsed'
[ "$RECEIPT_PROVEN" = "$RECEIPT_REQUIRED" ] \
    || die "the device receipt gate exited 0 having proven only $RECEIPT_PROVEN of $RECEIPT_REQUIRED cells"
[ "$RECEIPT_CONTRACT" = "$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' Contracts/upstream.lock.json | head -1)" ] \
    || die "the device receipt gate judged against contract $RECEIPT_CONTRACT, which is not the version this candidate's lock pins"

# --- 1. SwiftPM: local tag resolved by a throwaway consumer ------------------------

step "SwiftPM: tag $VERSION, exact-version resolution, build, smoke"

git tag "$VERSION"
CREATED_TAG=1

SWIFT_CONSUMER="$WORK/swift-consumer"
mkdir -p "$SWIFT_CONSUMER/Sources/SPFNRCConsumer"

# The consumer's product list and imports are derived from the module graph, so adding
# or dropping a module never leaves a hand-maintained list behind. The symbol touches
# below stay hand-written: only a person knows which symbol proves a module non-empty.
SWIFT_TARGETS=$(sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p' tools/module-graph.json)
SWIFT_PRODUCT_LINES=$(printf '%s\n' "$SWIFT_TARGETS" \
    | sed 's/.*/                .product(name: "&", package: "spfn-mobile"),/')
SWIFT_IMPORT_LINES=$(printf '%s\n' "$SWIFT_TARGETS" | sort | sed 's/^/import /')

cat > "$SWIFT_CONSUMER/Package.swift" <<EOF
// swift-tools-version: 6.1
// Throwaway RC consumer, generated by tools/rc-verify/rc-verify.sh. Never committed.
// 6.1 matches the package under test: a consumer manifest below the traits baseline
// cannot say anything about traits, including that it wants none of them.
import PackageDescription

let package = Package(
    name: "SPFNRCConsumer",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "file://$ROOT", exact: "$VERSION")
    ],
    targets: [
        .executableTarget(
            name: "SPFNRCConsumer",
            dependencies: [
$SWIFT_PRODUCT_LINES
            ]
        )
    ]
)
EOF

cat > "$SWIFT_CONSUMER/Sources/SPFNRCConsumer/main.swift" <<EOF
// Throwaway RC smoke, generated by tools/rc-verify/rc-verify.sh. One symbol from every
// public product: an import alone can succeed against an empty module.
$SWIFT_IMPORT_LINES

guard SPFNVersion.current == "$VERSION" else
{
    fatalError("resolved SPFNCore reports \(SPFNVersion.current), expected $VERSION")
}
precondition(SPFNAuthPolicy.allowedProfiles == [SPFNAuthProfile.clientProofV1])
precondition(!SPFNGeneratedContract.operationIDs.isEmpty)
_ = SPFNWireHeaders.self
_ = SPFNClient.self
_ = SPFNSocialApple.self
_ = SPFNSocialGoogle.self
print("SPFNRCConsumer smoke OK: \(SPFNVersion.current)")
EOF

# Every run gets its own cache and config paths, so no resolution, no cloned
# repository and no manifest cache can leak in from a previous run: a re-tagged
# candidate must be re-resolved from the repository, never replayed from a cache.
SPM_FLAGS="--package-path $SWIFT_CONSUMER --cache-path $WORK/spm-cache --config-path $WORK/spm-config"

swift package $SPM_FLAGS resolve > "$LOGS/swiftpm-resolve.log" 2>&1 \
    || die "SwiftPM resolution failed (see $LOGS/swiftpm-resolve.log)"
swift run $SPM_FLAGS SPFNRCConsumer > "$LOGS/swiftpm-build-run.log" 2>&1 \
    || die "SwiftPM consumer build/smoke failed (see $LOGS/swiftpm-build-run.log)"

grep -q "SPFNRCConsumer smoke OK: $VERSION" "$LOGS/swiftpm-build-run.log" \
    || die 'the SwiftPM smoke executable did not report the candidate version'

cp "$SWIFT_CONSUMER/Package.resolved" "$LOGS/Package.resolved"
RESOLVED_VERSION=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$LOGS/Package.resolved" | head -1)
RESOLVED_REVISION=$(sed -n 's/.*"revision" *: *"\([0-9a-f]\{40\}\)".*/\1/p' "$LOGS/Package.resolved" | head -1)
[ "$RESOLVED_VERSION" = "$VERSION" ] \
    || die "SwiftPM resolved version '$RESOLVED_VERSION', expected '$VERSION'"
[ "$RESOLVED_REVISION" = "$COMMIT" ] \
    || die "SwiftPM resolved revision $RESOLVED_REVISION, but the tag points at $COMMIT"
printf '  resolved %s at revision %s\n' "$RESOLVED_VERSION" "$RESOLVED_REVISION"

# --- 2. Maven: stage through the gate, consume from the staging repository ---------

step "Maven: staging $MAVEN_GROUP:*:$VERSION and compiling a consumer against it"

$GRADLE -Pspfn.publishing.enabled=true -Pspfn.staging.dir="$STAGING" publish \
    > "$LOGS/maven-publish.log" 2>&1 \
    || die "Maven staging publication failed (see $LOGS/maven-publish.log)"

GROUP_PATH=$(printf '%s' "$MAVEN_GROUP" | tr '.' '/')
# Android-backed modules only: since schemaVersion 3 a module may declare
# `androidModule: null`, which means it has no Android artifact to stage at all. The
# quoted-name extraction drops those by itself — and would also drop everything if it
# broke, so the count is checked against the graph's own two buckets before it is used.
MODULES=$(sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p' tools/module-graph.json | tr '\n' ' ')
MODULE_LINES=$(grep -c '"swiftTarget"' tools/module-graph.json)
IOS_ONLY_LINES=$(grep -c '"androidModule": null' tools/module-graph.json || true)
STAGED_COUNT=$(printf '%s\n' $MODULES | grep -c .)
[ "$STAGED_COUNT" -eq "$((MODULE_LINES - IOS_ONLY_LINES))" ] \
    || die "the module graph yields $STAGED_COUNT Android modules, expected $((MODULE_LINES - IOS_ONLY_LINES))"
[ "$STAGED_COUNT" -ge 4 ] || die "only $STAGED_COUNT Android modules were read from the module graph"
for module in $MODULES
do
    BASE="$STAGING/$GROUP_PATH/$module/$VERSION/$module-$VERSION"
    for artifact in "$BASE.aar" "$BASE.pom" "$BASE-sources.jar"
    do
        [ -f "$artifact" ] || die "staged artifact missing: $artifact"
    done
    grep -q "<groupId>$MAVEN_GROUP</groupId>" "$BASE.pom" \
        || die "$module POM does not carry groupId $MAVEN_GROUP"
    grep -q "<version>$VERSION</version>" "$BASE.pom" \
        || die "$module POM does not carry version $VERSION"
    # Central refuses a bundle whose POMs miss any of these; catch it at staging time
    # rather than at upload time.
    for element in '<name>' '<description>' '<url>' '<license>' '<developer>' '<scm>'
    do
        grep -q "$element" "$BASE.pom" \
            || die "$module POM misses Central-required element $element"
    done
    grep -q 'github.com/fxylabs/spfn-mobile' "$BASE.pom" \
        || die "$module POM does not name the public repository github.com/fxylabs/spfn-mobile"
    if grep -q 'PROPOSED' "$BASE.pom"
    then
        die "$module POM still calls the group proposed; D4 resolved it on 2026-08-03"
    fi
    # Signing is per-run and optional locally (D7): when this run carries an in-memory
    # key, every staged artifact must have its detached signature; when it does not,
    # none may — a stray .asc would be a signature nobody injected.
    for artifact in "$BASE.aar" "$BASE.pom" "$BASE-sources.jar"
    do
        if [ -n "${ORG_GRADLE_PROJECT_spfnSigningInMemoryKey:-}" ]
        then
            [ -f "$artifact.asc" ] || die "signing key was injected but $artifact.asc is missing"
        elif [ -f "$artifact.asc" ]
        then
            die "$artifact.asc exists but no signing key was injected this run"
        fi
    done
done
printf '  staged 6 modules (AAR + POM + sources), POMs carry the full Central metadata set\n'

ANDROID_CONSUMER="$WORK/android-consumer"
mkdir -p "$ANDROID_CONSUMER/src/main/kotlin/xyz/superfunction/spfn/rcconsumer"

# Derived from the module graph for the same reason the SwiftPM product list is.
MAVEN_DEPENDENCY_LINES=$(printf '%s\n' $MODULES \
    | sed "s|.*|    implementation(\"$MAVEN_GROUP:&:$VERSION\")|")

cat > "$ANDROID_CONSUMER/settings.gradle.kts" <<EOF
// Throwaway RC consumer, generated by tools/rc-verify/rc-verify.sh. Never committed.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The staged candidate is the ONLY source for SPFN coordinates; google() and
        // mavenCentral() carry the toolchain and transitive dependencies only.
        maven { url = uri("file://$STAGING") }
        google()
        mavenCentral()
    }
}

rootProject.name = "spfn-rc-consumer"
EOF

cat > "$ANDROID_CONSUMER/build.gradle.kts" <<EOF
// Throwaway RC consumer, generated by tools/rc-verify/rc-verify.sh. Never committed.

// The AARs carry Kotlin $KOTLIN_VERSION binary metadata, which AGP $AGP_VERSION's
// bundled Kotlin 2.2.x compiler cannot read. A real consumer needs the same explicit
// Kotlin Gradle plugin upgrade this repository's own root build script performs —
// that is a fact about consuming these artifacts, not a harness convenience, and it
// is exactly what this consumer exists to surface.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$KOTLIN_VERSION")
    }
}

plugins {
    id("com.android.library") version "$AGP_VERSION"
}

android {
    namespace = "xyz.superfunction.spfn.rcconsumer"
    compileSdk = $COMPILE_SDK
    defaultConfig {
        minSdk = $MIN_SDK
    }
}

dependencies {
$MAVEN_DEPENDENCY_LINES
}
EOF

cat > "$ANDROID_CONSUMER/src/main/kotlin/xyz/superfunction/spfn/rcconsumer/RcConsumerSmoke.kt" <<EOF
// Throwaway RC smoke, generated by tools/rc-verify/rc-verify.sh. One symbol from every
// staged module: resolution alone would pass with an empty AAR.
package xyz.superfunction.spfn.rcconsumer

import xyz.superfunction.spfn.auth.SpfnAuthPolicy
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.core.SpfnVersion
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.social.google.SpfnSocialGoogle

object RcConsumerSmoke
{
    val version: String = SpfnVersion.CURRENT
    val allowedProfiles: Int = SpfnAuthPolicy.ALLOWED_PROFILES.size
    val operations: Int = SpfnGeneratedContract.OPERATION_IDS.size
    val clientType: Class<SpfnClient> = SpfnClient::class.java
    // No Apple adapter symbol here: SPFNSocialApple is iOS-only and stages no Android
    // artifact, so a touch would name a class that is not on this consumer's classpath.
    val googleAdapterType: Class<SpfnSocialGoogle> = SpfnSocialGoogle::class.java
}
EOF

"$ROOT/gradlew" --console=plain -p "$ANDROID_CONSUMER" compileReleaseKotlin \
    > "$LOGS/maven-consume.log" 2>&1 \
    || die "Android consumer failed to compile against the staged coordinates (see $LOGS/maven-consume.log)"
printf '  consumer compiled against the staging repository\n'

# --- 3. SBOM: CycloneDX for both platforms -----------------------------------------

step 'SBOM: CycloneDX (Gradle plugin for Android, static for iOS)'

$GRADLE cyclonedxBom -Pspfn.sbom.dir="$SBOM_DIR" > "$LOGS/sbom-android.log" 2>&1 \
    || die "Android SBOM generation failed (see $LOGS/sbom-android.log)"
[ -f "$SBOM_DIR/spfn-mobile-android-$VERSION.cdx.json" ] \
    || die 'Android SBOM was not written to the output directory'

sh tools/rc-verify/generate-ios-sbom.sh "$SBOM_DIR/spfn-mobile-ios-$VERSION.cdx.json" \
    > "$LOGS/sbom-ios.log" 2>&1 \
    || die "iOS SBOM generation failed (see $LOGS/sbom-ios.log)"

# The SBOM tasks are on-demand only (D7). Hold that: the default build graph must not
# contain them, and with publishing disabled it must not contain a publish task either.
$GRADLE build --dry-run > "$LOGS/build-dry-run.log" 2>&1 \
    || die 'gradlew build --dry-run failed'
if grep -qi 'cyclonedx' "$LOGS/build-dry-run.log"
then
    die 'a CycloneDX task leaked into the default build graph'
fi
if grep -qi ':publish' "$LOGS/build-dry-run.log"
then
    die 'a publish task leaked into the default build graph'
fi
printf '  SBOMs written; default build graph carries no SBOM and no publish task\n'

# --- 4. SHA256SUMS + candidate manifest --------------------------------------------

step 'candidate manifest: SHA256SUMS and manifest.json'

(
    cd "$OUT"
    find staging sbom -type f \
        \( -name '*.aar' -o -name '*.pom' -o -name '*.jar' -o -name '*.module' -o -name '*.asc' -o -name '*.cdx.json' -o -name '*.cdx.xml' \) \
        -print | sort > .rc-artifacts
    : > SHA256SUMS
    while IFS= read -r artifact
    do
        shasum -a 256 "$artifact" >> SHA256SUMS
    done < .rc-artifacts
    rm .rc-artifacts
)
[ -s "$OUT/SHA256SUMS" ] || die 'SHA256SUMS is empty'

{
    printf '{\n'
    printf '  "candidate": "%s",\n' "$VERSION"
    printf '  "generatedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "sourceCommit": "%s",\n' "$COMMIT"
    printf '  "contractDigest": "%s",\n' "$CONTRACT_DIGEST"
    printf '  "signing": "unsigned alpha candidate (decision D7): provenance is this manifest — source commit, contract digest, artifact hashes and the CycloneDX SBOMs. Signing and attestation are added for public releases.",\n'
    printf '  "publication": "none (decision D3): local tag and local staging only. The tag was deleted when this run ended.",\n'
    printf '  "deviceReceipts": {\n'
    printf '    "root": "tools/device-receipts/runs",\n'
    printf '    "contractVersion": "%s",\n' "$RECEIPT_CONTRACT"
    printf '    "cellsProven": %s,\n' "$RECEIPT_PROVEN"
    printf '    "cellsRequired": %s,\n' "$RECEIPT_REQUIRED"
    printf '    "receiptsRead": %s,\n' "$RECEIPT_FILES"
    printf '    "gate": "tools/device-receipts/receipt-gate.sh — {ios x apple, ios x google, android x google} x {first-enroll, re-login, user-cancel, network-failure, server-reject}; android x apple is exempt because no Android Apple adapter module exists"\n'
    printf '  },\n'
    printf '  "swiftpm": {\n'
    printf '    "resolvedTag": "%s",\n' "$RESOLVED_VERSION"
    printf '    "resolvedRevision": "%s",\n' "$RESOLVED_REVISION"
    printf '    "consumer": "throwaway package resolving .package(url: file://<repo>, exact: %s)"\n' "$VERSION"
    printf '  },\n'
    printf '  "maven": {\n'
    printf '    "group": "%s",\n' "$MAVEN_GROUP"
    printf '    "groupStatus": "Central Portal domain-verified (docs/OPEN-DECISIONS.md D4, resolved 2026-08-03)",\n'
    printf '    "modules": [%s]\n' "$(printf '%s' "$MODULES" | tr -s ' ' | sed 's/ $//; s/[^ ]*/"&"/g; s/ /, /g')"
    printf '  },\n'
    printf '  "artifacts": [\n'
    awk '{
        if (NR > 1) { printf ",\n" }
        printf "    { \"path\": \"%s\", \"sha256\": \"%s\" }", $2, $1
    } END { printf "\n" }' "$OUT/SHA256SUMS"
    printf '  ]\n'
    printf '}\n'
} > "$OUT/manifest.json"

ARTIFACT_COUNT=$(wc -l < "$OUT/SHA256SUMS" | tr -d ' ')

# --- summary -----------------------------------------------------------------------

printf '\nRC VERIFY SUMMARY\n'
printf '  candidate:        %s\n' "$VERSION"
printf '  source commit:    %s\n' "$COMMIT"
printf '  contract digest:  %s\n' "$CONTRACT_DIGEST"
printf '  device receipts:  %s of %s cells proven from %s receipts at contract %s\n' \
    "$RECEIPT_PROVEN" "$RECEIPT_REQUIRED" "$RECEIPT_FILES" "$RECEIPT_CONTRACT"
printf '  swiftpm:          tag %s resolved at %s\n' "$RESOLVED_VERSION" "$RESOLVED_REVISION"
printf '  maven staging:    %s (6 modules)\n' "$STAGING"
printf '  sbom:             %s\n' "$SBOM_DIR"
printf '  manifest:         %s (%s artifacts)\n' "$OUT/manifest.json" "$ARTIFACT_COUNT"
printf '  logs:             %s\n' "$LOGS"
printf '  local tag:        removed on exit by trap\n'
printf 'RESULT: PASS\n'
