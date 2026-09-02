#!/bin/sh
# SPFN Mobile — proves the provider-adapter validator rules refuse what they must.
#
# The adapter change set relaxed one rule that still stands: "zero external dependencies"
# became "only what tools/module-graph.json declares". A relaxation is where holes are
# born, and this rule is a SCAN — a scan that cannot run reports the same green as a scan
# that found nothing (docs/IMPLEMENTATION-PITFALLS.md P7). So the probe asks two separate
# questions of it: does it bite, and does it fail red when it cannot run.
#
# It also once covered an interactive-browser vocabulary ban and that ban's adapter
# exception. Both are gone: the ban never caught anything, matched spelling rather than
# meaning, and was removed along with the exceptions that had accumulated around it.
#
#   e. an Android dependency the graph does not declare fails;
#   f. a graph allowance no module uses fails;
#   g. a coordinate string instead of a catalogue alias fails;
#   h. a Swift package the graph does not declare fails;
#   k. a module graph that cannot be read fails instead of passing.
#
# Since schemaVersion 3 a module may declare `androidModule: null` — no Android half at
# all — and every Android-side check skips those. A skip is the other way a check stops
# checking, so it gets the same treatment:
#
#   l. an iOS-only entry that claims an Android module is still checked, and fails;
#   m. a graph with every androidModule removed fails instead of skipping everything;
#   n. widening the skip so no module is Android-checked fails on the visit count.
#
# The Google adapter was written on a deprecated API once and migrated to Credential
# Manager afterwards. What keeps that from recurring is a refusal rather than a memory,
# and a refusal is only worth its line if it bites:
#
#   o. a deprecation suppression anywhere in SDK sources fails;
#   p. dropping one of the three declared Android coordinates fails.
#
# schemaVersion 4 added `linux`, which is absent on a module that builds on Linux and
# the literal false on a module that has no Linux half at all. SwiftPM cannot condition
# a target on a platform, so what makes `linux: false` true in the build is a guard on
# every one of that module's files — a mechanism a validator can only check by reading
# the files. Each half of that reading gets a case, and so does the relaxation the same
# change made to the section 8 dependency rule:
#
#   q. a source of a declared-absent module that opens with unguarded code fails;
#   r. a guard closed before the end of such a file fails;
#   s. an Apple-only framework imported unconditionally in a Linux-capable module fails;
#   t. CryptoKit imported outside a canImport guard fails;
#   u. a `linux` key that is neither absent nor false fails as unread;
#   v. an unconditional external product on a target with no graph edges fails;
#   w. a product allowed to another module fails on the target it was not allowed to;
#   x. an import scan that reads no source at all fails instead of reporting none.
#
# The last three mutate a copy of the validator with its ROOT pinned, because their
# subject is what the check does when its own input is unavailable — the one condition
# that cannot be produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on
# every exit path; `git checkout --` is never used, because it restores from HEAD and
# eats uncommitted work.
#
#   sh tools/validate/probe-social-adapter-rules.sh

set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

CHECKS=0
FAILURES=0

pass()
{
    CHECKS=$((CHECKS + 1))
    printf '  ok    %s\n' "$1"
}

fail()
{
    CHECKS=$((CHECKS + 1))
    FAILURES=$((FAILURES + 1))
    printf '  FAIL  %s\n' "$1"
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-adapter-probe.XXXXXX")

APPLE_SOURCE=Sources/SPFNSocialApple/SPFNSocialApple.swift
MANIFEST=Package.swift
GRAPH=tools/module-graph.json
CLIENT_BUILD=android/spfn-client/build.gradle.kts
GOOGLE_BUILD=android/spfn-social-google/build.gradle.kts
GOOGLE_SOURCE=android/spfn-social-google/src/main/kotlin/xyz/superfunction/spfn/social/google/SpfnSocialGoogle.kt
DIGEST_SOURCE=Sources/SPFNCore/SPFNDigest.swift
KEY_STORE_SOURCE=Sources/SPFNClient/SPFNKeyStore.swift

cp "$APPLE_SOURCE" "$TMP/apple.bak"
cp "$MANIFEST" "$TMP/manifest.bak"
cp "$GRAPH" "$TMP/graph.bak"
cp "$CLIENT_BUILD" "$TMP/client-build.bak"
cp "$GOOGLE_BUILD" "$TMP/google-build.bak"
cp "$GOOGLE_SOURCE" "$TMP/google-source.bak"
cp "$DIGEST_SOURCE" "$TMP/digest-source.bak"
cp "$KEY_STORE_SOURCE" "$TMP/key-store-source.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/apple.bak" "$APPLE_SOURCE"
        cp "$TMP/manifest.bak" "$MANIFEST"
        cp "$TMP/graph.bak" "$GRAPH"
        cp "$TMP/client-build.bak" "$CLIENT_BUILD"
        cp "$TMP/google-build.bak" "$GOOGLE_BUILD"
        cp "$TMP/google-source.bak" "$GOOGLE_SOURCE"
        cp "$TMP/digest-source.bak" "$DIGEST_SOURCE"
        cp "$TMP/key-store-source.bak" "$KEY_STORE_SOURCE"
        rm -rf "$TMP"
    fi
}

on_signal()
{
    trap '' EXIT INT TERM
    restore
    exit "$1"
}

trap restore EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

restore_files()
{
    cp "$TMP/apple.bak" "$APPLE_SOURCE"
    cp "$TMP/manifest.bak" "$MANIFEST"
    cp "$TMP/graph.bak" "$GRAPH"
    cp "$TMP/client-build.bak" "$CLIENT_BUILD"
    cp "$TMP/google-build.bak" "$GOOGLE_BUILD"
    cp "$TMP/google-source.bak" "$GOOGLE_SOURCE"
    cp "$TMP/digest-source.bak" "$DIGEST_SOURCE"
    cp "$TMP/key-store-source.bak" "$KEY_STORE_SOURCE"
}

# Runs the validator expecting failure on a specific rule, then restores every file.
expect_fail()
{
    LABEL=$1
    MARKER=$2
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        fail "$LABEL — the validator passed"
    elif grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator failed, but not on the expected rule"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and
# expects the check to say so rather than to report a clean scan.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    if sh "$COPY" > "$TMP/run.log" 2>&1
    then
        fail "$LABEL — the validator passed while the check could not run"
    elif grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator failed, but not on the expected rule"
    fi
    rm -f "$COPY"
}

printf 'provider adapter rules probe\n'

# --- e, f, g. the Android allowlist, in both directions and in every shape ----------
printf 'dependencies { implementation(libs.googleid) }\n' >> "$CLIENT_BUILD"
expect_fail 'an Android dependency the module graph does not declare fails' \
    'undeclared(googleid)'

sed '/api(libs.googleid)/d' "$TMP/google-build.bak" > "$GOOGLE_BUILD"
expect_fail 'a graph allowance no module actually uses fails' \
    'declared-but-unused(googleid)'

sed 's#api(libs.googleid)#api("com.google.android.libraries.identity.googleid:googleid:1.2.0")#' \
    "$TMP/google-build.bak" > "$GOOGLE_BUILD"
expect_fail 'a coordinate string instead of a catalogue alias fails' \
    'unrecognised-dependency-form'

# --- h. the Swift allowlist ---------------------------------------------------------
sed 's#^    dependencies: \[#    dependencies: [\n        .package(url: "https://example.invalid/UnreviewedPackage", from: "1.0.0"),#' \
    "$TMP/manifest.bak" > "$MANIFEST"
expect_fail 'a Swift package the module graph does not declare fails' \
    'does not declare: UnreviewedPackage'

# --- k. the graph scan fails red when it cannot run --------------------------------
: > "$TMP/empty-graph.json"
expect_unrunnable 'an unreadable module graph fails instead of allowing nothing quietly' \
    'it could not run' \
    "s#^GRAPH=tools/module-graph.json#GRAPH=$TMP/empty-graph.json#"

# --- l, m, n. the skip an iOS-only module gets is narrow ---------------------------
# `androidModule: null` takes a module out of every Android-side check. That is a skip,
# and a skip is the other way a check stops checking, so all three of its failure modes
# are probed: an entry that claims an Android half it does not have, a graph where the
# key is gone entirely, and a validator whose skip has been widened to cover everything.
sed 's#"androidModule": null#"androidModule": "spfn-social-apple"#' "$TMP/graph.bak" > "$GRAPH"
expect_fail 'an iOS-only entry that claims an Android module is still checked' \
    'android/spfn-social-apple missing build script or Kotlin sources'

sed 's#"androidModule": "[a-z-]*"#"androidModule": ""#; s#"androidModule": null#"androidModule": ""#' \
    "$TMP/graph.bak" > "$GRAPH"
expect_fail 'a graph with no readable androidModule at all fails instead of skipping everything' \
    'declares neither an androidModule nor null'

expect_unrunnable 'a skip widened to cover every module fails on the visit count' \
    'Android-backed modules' \
    's#^    if \[ -n "$android_module" \]#    if [ -n "" ]#'

# --- o, p. the decisions this round settled are kept by a check, not by memory -----
printf '@Suppress("DEPRECATION")\n' >> "$GOOGLE_SOURCE"
expect_fail 'a deprecation suppression in an SDK source fails' \
    'deprecation suppression in SDK sources'

printf '// @Suppress("DEPRECATION") — describing the refusal, not performing it\n' >> "$APPLE_SOURCE"
if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    fail 'the refusal spares a comment that only describes it, which it must not do here'
else
    pass 'the deprecation refusal reads comments too, so it cannot be commented past'
fi
restore_files

sed 's#"androidx-credentials-play-services-auth", ##' "$TMP/graph.bak" > "$GRAPH"
expect_fail 'dropping one of the declared Android coordinates fails' \
    'undeclared(androidx-credentials-play-services-auth)'

# --- q, r. the guard is what makes `linux: false` true, so it is read file by file --
# A module that declares no Linux half compiles to an empty module only because every
# one of its files is guarded whole. Both ends of that guard are probed, because a
# guard closed early is the failure that still LOOKS guarded: the file opens with
# `#if canImport(...)` and only the tail escapes it.
sed '/^#if canImport(AuthenticationServices)$/d' "$TMP/apple.bak" > "$APPLE_SOURCE"
expect_fail 'a source of a module declaring no Linux half that opens with unguarded code fails' \
    'opens-with-unguarded-code'

printf 'extension SPFNSocialAppleError { }\n' >> "$APPLE_SOURCE"
expect_fail 'a whole-file guard closed before the end of the file fails' \
    'guard-closes-before-the-end'

# --- s, t. the other direction: what a module that DOES build on Linux may import ---
printf 'import Security\n' >> "$KEY_STORE_SOURCE"
expect_fail 'an Apple-only framework imported unconditionally in a Linux-capable module fails' \
    'an Apple-only framework is imported unconditionally'

printf 'import CryptoKit\n' >> "$DIGEST_SOURCE"
expect_fail 'CryptoKit imported outside a canImport guard fails' \
    'CryptoKit is imported outside a canImport guard'

# The guard has to be a canImport guard. `#if os(iOS)` says WHEN to compile the import,
# not whether the framework is there, so it leaves a Linux build with an import of
# something Linux does not have — and a reader that admitted any `#if` would pass it.
printf '#if os(iOS)\nimport CryptoKit\n#endif\n' >> "$DIGEST_SOURCE"
expect_fail 'a CryptoKit import behind #if os(iOS) rather than canImport still fails' \
    'CryptoKit is imported outside a canImport guard'

# --- u. the `linux` key has three states and the third is not a skip ----------------
sed 's#"linux": false#"linux": true#' "$TMP/graph.bak" > "$GRAPH"
expect_fail 'a linux key that is neither absent nor false fails as unread' \
    'they were not read'

# --- v, w. the section 8 relaxation admits a conditional product and nothing else ---
# A target with no graph edges may now carry an external product, which is exactly the
# kind of relaxation that swallows the rule it relaxed. Two ways past it are probed:
# dropping the condition, and naming a package the graph allows some OTHER module.
sed '/"SPFNCore", dependencies:/ s#, condition: \.when(platforms: \[\.linux\])##' \
    "$TMP/manifest.bak" > "$MANIFEST"
expect_fail 'an unconditional external product on a target with no graph edges fails' \
    'has no graph edges but declares'

sed '/"SPFNCore", dependencies:/ s#package: "swift-crypto"#package: "GoogleSignIn-iOS"#' \
    "$TMP/manifest.bak" > "$MANIFEST"
expect_fail 'a product the graph allows another module fails on the target it was not allowed to' \
    'has no graph edges but declares'

# --- x. an import scan that reads nothing is not a clean import scan ----------------
expect_unrunnable 'an import scan that reads no source at all fails instead of reporting none' \
    'it did not run' \
    's#^find Sources Tests -name .[*].swift. | sort#find Sources Tests -name "*.no-such-suffix" | sort#'

# --- the unmodified tree still passes ----------------------------------------------
if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    pass 'the unmodified tree passes the validator after every restoration'
else
    fail 'the tree no longer passes; a probe leaked a mutation'
fi

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
