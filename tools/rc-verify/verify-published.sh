#!/bin/sh
# SPFN Mobile — published-coordinate verification.
#
# `rc-verify.sh` answers "could this candidate be published"; this script answers
# "can a stranger consume what was published". Nothing here reads a build output,
# a staging directory or the working tree: every byte comes from the network, from
# the same coordinates an integrator would write down.
#
#   1. Central  — every module's AAR, POM, Gradle module metadata and sources jar is
#                 fetched from repo1.maven.org, its published sha256 sidecar
#                 recomputed, its detached PGP signature verified against the
#                 publishing key fetched from a public keyserver, its POM checked for
#                 the Central metadata set, and its AAR opened: an AAR with no
#                 classes.jar resolves and checksums exactly like a real one.
#   2. Android  — a throwaway consumer compiles against `mavenCentral()` as the only
#                 source for SPFN coordinates, with `--refresh-dependencies` so a warm
#                 local cache cannot answer for the registry.
#   3. SwiftPM  — a throwaway package resolves the public Git URL at the exact
#                 version, the resolved revision is compared against the release
#                 commit, and a smoke executable touches one symbol in every product.
#
#   ANDROID_HOME=~/Library/Android/sdk sh tools/rc-verify/verify-published.sh [version]
#
# The version defaults to the VERSION file and the commit to whatever that tag points
# at. Requirements: the Swift toolchain, an Android SDK, gpg, and network access.
# Any failing check is reported, all checks run, and the exit code is non-zero if any
# failed — a partial publication is more useful to see whole than one line at a time.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

VERSION=${1:-$(tr -d '[:space:]' < VERSION)}
CENTRAL=https://repo1.maven.org/maven2
KEYSERVER=https://keyserver.ubuntu.com
# Derived from the module graph, not hand-listed: a module added to or dropped from the
# train changes what this script must fetch, and a stale list would silently stop
# checking a published artifact.
MODULES=$(sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p' tools/module-graph.json | tr '\n' ' ')
SWIFT_TARGETS=$(sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p' tools/module-graph.json)
FAILURES=0

die()
{
    printf 'verify-published FAIL: %s\n' "$1" >&2
    exit 1
}

step()
{
    printf '\n== %s\n' "$1"
}

ok()
{
    printf '  ok   %s\n' "$1"
}

bad()
{
    printf '  FAIL %s\n' "$1"
    FAILURES=$((FAILURES + 1))
}

# --- preconditions -----------------------------------------------------------------

command -v swift > /dev/null 2>&1 || die 'swift toolchain not found'
command -v gpg > /dev/null 2>&1 || die 'gpg not found'
[ -x ./gradlew ] || die 'gradlew not found at the repository root'

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]
then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi
[ -n "${ANDROID_HOME:-}" ] || die 'ANDROID_HOME is not set and no default SDK exists'

COMMIT=$(git rev-list -n 1 "$VERSION" 2>/dev/null || true)
[ -n "$COMMIT" ] || die "no local tag $VERSION; fetch tags before verifying a release"

REPO_URL=$(git remote get-url origin | sed 's/\.git$//')
MAVEN_GROUP=$(sed -n 's/^spfn.maven.group=\(.*\)$/\1/p' gradle.properties)
GROUP_PATH=$(printf '%s' "$MAVEN_GROUP" | tr '.' '/')
AGP_VERSION=$(sed -n 's/^agp = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
KOTLIN_VERSION=$(sed -n 's/^kotlin = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
COMPILE_SDK=$(sed -n 's/^compile-sdk = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
MIN_SDK=$(sed -n 's/^min-sdk = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
[ -n "$MAVEN_GROUP" ] && [ -n "$AGP_VERSION" ] && [ -n "$KOTLIN_VERSION" ] \
    && [ -n "$COMPILE_SDK" ] && [ -n "$MIN_SDK" ] \
    || die 'could not read the coordinates and toolchain baseline from the repository'

# --- workspace ---------------------------------------------------------------------

# Everything is throwaway and lives outside the repository: this run must not be able
# to leave a consumer project, a downloaded artifact or a keyring behind in the tree.
WORK=$(mktemp -d "${TMPDIR:-/tmp}/spfn-published-verify.XXXXXX")
LOGS="$WORK/logs"
DL="$WORK/download"
mkdir -p "$LOGS" "$DL"

cleanup()
{
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

printf 'SPFN Mobile published-coordinate verification\n'
printf '  version: %s\n  commit:  %s\n  maven:   %s\n  swiftpm: %s\n' \
    "$VERSION" "$COMMIT" "$MAVEN_GROUP" "$REPO_URL"

# --- 1. Central: artifacts, checksums, signatures, POM metadata --------------------

step "Maven Central: $(printf '%s' "$MODULES" | wc -w | tr -d ' ') modules, fetched from $CENTRAL"

GNUPGHOME="$WORK/gnupg"
export GNUPGHOME
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
KEY_FETCHED=0

for M in $MODULES
do
    for FILE in "$M-$VERSION.aar" "$M-$VERSION.pom" "$M-$VERSION.module" "$M-$VERSION-sources.jar"
    do
        URL="$CENTRAL/$GROUP_PATH/$M/$VERSION/$FILE"
        curl -fsS -o "$DL/$FILE" "$URL" 2>>"$LOGS/curl.log" \
            || { bad "$FILE: not published"; continue; }
        curl -fsS -o "$DL/$FILE.sha256" "$URL.sha256" 2>>"$LOGS/curl.log" \
            || { bad "$FILE: no sha256 sidecar"; continue; }
        curl -fsS -o "$DL/$FILE.asc" "$URL.asc" 2>>"$LOGS/curl.log" \
            || { bad "$FILE: no detached signature"; continue; }

        COMPUTED=$(shasum -a 256 "$DL/$FILE" | cut -d' ' -f1)
        PUBLISHED=$(tr -d ' \n\r' < "$DL/$FILE.sha256")
        [ "$COMPUTED" = "$PUBLISHED" ] \
            || bad "$FILE: sha256 is $COMPUTED, the sidecar says $PUBLISHED"

        if [ "$KEY_FETCHED" -eq 0 ]
        then
            KEYID=$(gpg --verify "$DL/$FILE.asc" "$DL/$FILE" 2>&1 \
                | sed -n 's/.*using [A-Z]* key \([0-9A-F]*\).*/\1/p' | head -1)
            # Fetched over HKP-on-HTTPS rather than `gpg --recv-keys`: dirmngr does not
            # start under every sandboxed shell, and the key material is identical.
            curl -fsS "$KEYSERVER/pks/lookup?op=get&search=0x$KEYID" \
                -o "$WORK/publishing-key.asc" >>"$LOGS/gpg.log" 2>&1 \
                || bad "publishing key $KEYID is not on $KEYSERVER"
            # gpg exits non-zero when it cannot start gpg-agent even though the import
            # itself succeeded, so the keyring decides, not the exit code.
            gpg --batch --import "$WORK/publishing-key.asc" >>"$LOGS/gpg.log" 2>&1 || true
            gpg --batch --list-keys "$KEYID" > "$LOGS/publishing-key.txt" 2>&1 \
                || bad "publishing key $KEYID did not import"
            printf '  publishing key %s\n' \
                "$(gpg --batch --fingerprint "$KEYID" 2>/dev/null | sed -n '2p' | tr -d ' ')"
            sed -n 's/^uid *\[[^]]*\] *//p' "$LOGS/publishing-key.txt" | sed 's/^/  uid            /'
            KEY_FETCHED=1
        fi

        gpg --verify "$DL/$FILE.asc" "$DL/$FILE" >>"$LOGS/gpg.log" 2>&1 \
            || bad "$FILE: PGP signature does not verify"
    done

    POM="$DL/$M-$VERSION.pom"
    if [ -f "$POM" ]
    then
        grep -q "<groupId>$MAVEN_GROUP</groupId>" "$POM" || bad "$M pom: wrong groupId"
        grep -q "<artifactId>$M</artifactId>" "$POM" || bad "$M pom: wrong artifactId"
        grep -q "<version>$VERSION</version>" "$POM" || bad "$M pom: wrong version"
        for TAG in name description url licenses developers scm
        do
            grep -q "<$TAG>" "$POM" || bad "$M pom: no <$TAG>, which Central requires"
        done
        grep -q 'MIT' "$POM" || bad "$M pom: the declared license is not MIT (D8)"
    fi

    # An AAR carrying no compiled code resolves and checksums like any other.
    if [ -f "$DL/$M-$VERSION.aar" ]
    then
        unzip -l "$DL/$M-$VERSION.aar" > "$LOGS/$M-aar.txt" 2>&1 || bad "$M aar: unreadable"
        grep -q 'classes.jar' "$LOGS/$M-aar.txt" || bad "$M aar: no classes.jar"
    fi
done

[ "$FAILURES" -eq 0 ] \
    && ok 'every artifact: sha256 sidecar, PGP signature, POM metadata, non-empty AAR'

# --- 2. Android: a consumer resolving the group from Central and nowhere else -------

step "Android: consumer compiled against $MAVEN_GROUP:*:$VERSION from Central"

CONSUMER="$WORK/android-consumer"
mkdir -p "$CONSUMER/src/main/kotlin/xyz/superfunction/spfn/pubconsumer"

MAVEN_DEPENDENCY_LINES=$(printf '%s\n' $MODULES \
    | sed "s|.*|    implementation(\"$MAVEN_GROUP:&:$VERSION\")|")

cat > "$CONSUMER/settings.gradle.kts" <<EOF
// Throwaway consumer, generated by tools/rc-verify/verify-published.sh. Never committed.
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
        // SPFN coordinates may come from Central and from nowhere else: no staging
        // directory, no mavenLocal, no project-local repository.
        mavenCentral()
        google {
            content { excludeGroupByRegex("xyz\\\\.superfunction.*") }
        }
    }
}

rootProject.name = "spfn-published-consumer"
EOF

cat > "$CONSUMER/build.gradle.kts" <<EOF
// Throwaway consumer, generated by tools/rc-verify/verify-published.sh. Never committed.

// The published AARs carry Kotlin $KOTLIN_VERSION binary metadata, which AGP
// $AGP_VERSION's bundled Kotlin 2.2.x compiler cannot read. A real consumer needs this
// same explicit Kotlin Gradle plugin upgrade — COMPATIBILITY.md states it as a
// consumer condition, and this project is where that claim is exercised.
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
    namespace = "xyz.superfunction.spfn.pubconsumer"
    compileSdk = $COMPILE_SDK
    defaultConfig {
        minSdk = $MIN_SDK
    }
}

dependencies {
$MAVEN_DEPENDENCY_LINES
}
EOF

cat > "$CONSUMER/src/main/kotlin/xyz/superfunction/spfn/pubconsumer/PublishedSmoke.kt" <<EOF
// Throwaway smoke, generated by tools/rc-verify/verify-published.sh. Never committed.
// One symbol from every published module: resolution alone passes with an empty AAR.
package xyz.superfunction.spfn.pubconsumer

import xyz.superfunction.spfn.auth.SpfnAuthPolicy
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.core.SpfnVersion
import xyz.superfunction.spfn.generated.SpfnGeneratedContract

object PublishedSmoke
{
    val version: String = SpfnVersion.CURRENT
    val allowedProfiles: Int = SpfnAuthPolicy.ALLOWED_PROFILES.size
    val operations: Int = SpfnGeneratedContract.OPERATION_IDS.size
    val clientType: Class<SpfnClient> = SpfnClient::class.java
}
EOF

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$CONSUMER/local.properties"

# --refresh-dependencies: a warm cache must not be able to answer for the registry.
if ./gradlew --console=plain --project-dir "$CONSUMER" --refresh-dependencies \
    compileReleaseKotlin > "$LOGS/android-consume.log" 2>&1
then
    ok 'consumer compiled against the published coordinates'
else
    bad 'consumer failed to compile against the published coordinates'
    tail -30 "$LOGS/android-consume.log" | sed 's/^/    /'
fi

# --- 3. SwiftPM: the public Git URL at the exact version ---------------------------

step "SwiftPM: $REPO_URL resolved at exact $VERSION"

SWIFT_CONSUMER="$WORK/swift-consumer"
mkdir -p "$SWIFT_CONSUMER/Sources/SPFNPublishedConsumer"

SWIFT_PRODUCT_LINES=$(printf '%s\n' "$SWIFT_TARGETS" \
    | sed 's/.*/                .product(name: "&", package: "spfn-mobile"),/')
SWIFT_IMPORT_LINES=$(printf '%s\n' "$SWIFT_TARGETS" | sort | sed 's/^/import /')

cat > "$SWIFT_CONSUMER/Package.swift" <<EOF
// swift-tools-version: 6.0
// Throwaway consumer, generated by tools/rc-verify/verify-published.sh. Never committed.
import PackageDescription

let package = Package(
    name: "SPFNPublishedConsumer",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "$REPO_URL.git", exact: "$VERSION")
    ],
    targets: [
        .executableTarget(
            name: "SPFNPublishedConsumer",
            dependencies: [
$SWIFT_PRODUCT_LINES
            ]
        )
    ]
)
EOF

cat > "$SWIFT_CONSUMER/Sources/SPFNPublishedConsumer/main.swift" <<EOF
// Throwaway smoke, generated by tools/rc-verify/verify-published.sh. Never committed.
// One symbol from every public product: an import alone succeeds against an empty module.
$SWIFT_IMPORT_LINES

guard SPFNVersion.current == "$VERSION" else
{
    fatalError("resolved SPFNCore reports \(SPFNVersion.current), expected $VERSION")
}
precondition(SPFNAuthPolicy.allowedProfiles == [SPFNAuthProfile.clientProofV1])
precondition(!SPFNGeneratedContract.operationIDs.isEmpty)
_ = SPFNWireHeaders.self
_ = SPFNClient.self
print("SPFNPublishedConsumer smoke OK: \(SPFNVersion.current)")
EOF

# Own cache and config paths: no resolution and no clone may be replayed from a
# previous run, so the tag is re-resolved from the remote every time.
SPM_FLAGS="--package-path $SWIFT_CONSUMER --cache-path $WORK/spm-cache --config-path $WORK/spm-config"

if swift package $SPM_FLAGS resolve > "$LOGS/swiftpm-resolve.log" 2>&1
then
    ok "resolved from $REPO_URL"
else
    bad 'SwiftPM could not resolve the published tag'
    tail -20 "$LOGS/swiftpm-resolve.log" | sed 's/^/    /'
fi

if [ -f "$SWIFT_CONSUMER/Package.resolved" ]
then
    RESOLVED_VERSION=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$SWIFT_CONSUMER/Package.resolved" | head -1)
    RESOLVED_REVISION=$(sed -n 's/.*"revision" *: *"\([0-9a-f]\{40\}\)".*/\1/p' "$SWIFT_CONSUMER/Package.resolved" | head -1)
    [ "$RESOLVED_VERSION" = "$VERSION" ] \
        || bad "SwiftPM resolved version $RESOLVED_VERSION, expected $VERSION"
    if [ "$RESOLVED_REVISION" = "$COMMIT" ]
    then
        ok "the published tag points at $COMMIT"
    else
        bad "the published tag points at $RESOLVED_REVISION, but $VERSION is $COMMIT locally"
    fi
fi

if swift run $SPM_FLAGS SPFNPublishedConsumer > "$LOGS/swiftpm-run.log" 2>&1 \
    && grep -q "SPFNPublishedConsumer smoke OK: $VERSION" "$LOGS/swiftpm-run.log"
then
    ok 'the smoke executable reported the published version'
else
    bad 'the SwiftPM consumer did not build and report the published version'
    tail -20 "$LOGS/swiftpm-run.log" | sed 's/^/    /'
fi

# --- verdict -----------------------------------------------------------------------

printf '\n'
if [ "$FAILURES" -eq 0 ]
then
    printf 'PASS — %s is consumable from Maven Central and SwiftPM\n' "$VERSION"
else
    printf 'FAIL — %s check(s) failed for %s\n' "$FAILURES" "$VERSION"
    exit 1
fi
