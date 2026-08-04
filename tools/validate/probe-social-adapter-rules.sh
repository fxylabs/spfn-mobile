#!/bin/sh
# SPFN Mobile — proves the provider-adapter validator rules refuse what they must.
#
# The adapter change set relaxed two rules, and a relaxation is where holes are born:
#
#   - the interactive-browser vocabulary ban gained an exception, scoped to the two
#     adapter module trees and to the provider-token words alone;
#   - "zero external dependencies" became "only what tools/module-graph.json declares".
#
# Both new rules are also SCANS, and a scan that cannot run reports the same green as a
# scan that found nothing (docs/IMPLEMENTATION-PITFALLS.md P7). So this probe asks two
# separate questions of each: does it bite, and does it fail red when it cannot run.
#
#   a. redirect/PKCE vocabulary in a non-adapter module still fails;
#   b. provider-token vocabulary in a non-adapter module still fails;
#   c. redirect/PKCE vocabulary INSIDE an adapter fails — the exception is by term too;
#   d. provider-token vocabulary inside an adapter passes, or the exception is a lie;
#   e. an Android dependency the graph does not declare fails;
#   f. a graph allowance no module uses fails;
#   g. a coordinate string instead of a catalogue alias fails;
#   h. a Swift package the graph does not declare fails;
#   i. the vocabulary scan reaching no files fails instead of passing;
#   j. an adapter scope that no longer holds sources fails instead of passing;
#   k. a module graph that cannot be read fails instead of passing.
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

CORE_SOURCE=Sources/SPFNCore/SPFNDigest.swift
APPLE_SOURCE=Sources/SPFNSocialApple/SPFNSocialApple.swift
MANIFEST=Package.swift
GRAPH=tools/module-graph.json
CLIENT_BUILD=android/spfn-client/build.gradle.kts
GOOGLE_BUILD=android/spfn-social-google/build.gradle.kts

cp "$CORE_SOURCE" "$TMP/core.bak"
cp "$APPLE_SOURCE" "$TMP/apple.bak"
cp "$MANIFEST" "$TMP/manifest.bak"
cp "$CLIENT_BUILD" "$TMP/client-build.bak"
cp "$GOOGLE_BUILD" "$TMP/google-build.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/core.bak" "$CORE_SOURCE"
        cp "$TMP/apple.bak" "$APPLE_SOURCE"
        cp "$TMP/manifest.bak" "$MANIFEST"
        cp "$TMP/client-build.bak" "$CLIENT_BUILD"
        cp "$TMP/google-build.bak" "$GOOGLE_BUILD"
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
    cp "$TMP/core.bak" "$CORE_SOURCE"
    cp "$TMP/apple.bak" "$APPLE_SOURCE"
    cp "$TMP/manifest.bak" "$MANIFEST"
    cp "$TMP/client-build.bak" "$CLIENT_BUILD"
    cp "$TMP/google-build.bak" "$GOOGLE_BUILD"
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

# --- a, b. the vocabulary ban is unchanged outside the adapter trees ----------------
printf '// probe: pkce\n' >> "$CORE_SOURCE"
expect_fail 'redirect vocabulary in a non-adapter module still fails' \
    'interactive-browser auth vocabulary found'

printf '// probe: id_token\n' >> "$CORE_SOURCE"
expect_fail 'provider-token vocabulary in a non-adapter module still fails' \
    'interactive-browser auth vocabulary found'

# --- c. the exception is scoped by term, not only by path --------------------------
printf '// probe: pkce inside the adapter\n' >> "$APPLE_SOURCE"
expect_fail 'redirect vocabulary inside an adapter module fails' \
    'interactive-browser auth vocabulary found'

# --- d. and it really is an exception ----------------------------------------------
printf '// probe: the adapter names an id_token, which is what it is for\n' >> "$APPLE_SOURCE"
if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    pass 'provider-token vocabulary inside an adapter module is admitted'
else
    fail 'the adapter exception refuses the vocabulary it exists to admit'
fi
restore_files

# --- e, f, g. the Android allowlist, in both directions and in every shape ----------
printf 'dependencies { implementation(libs.play.services.auth) }\n' >> "$CLIENT_BUILD"
expect_fail 'an Android dependency the module graph does not declare fails' \
    'undeclared(play-services-auth)'

sed '/api(libs.play.services.auth)/d' "$TMP/google-build.bak" > "$GOOGLE_BUILD"
expect_fail 'a graph allowance no module actually uses fails' \
    'declared-but-unused(play-services-auth)'

sed 's#api(libs.play.services.auth)#api("com.google.android.gms:play-services-auth:21.6.0")#' \
    "$TMP/google-build.bak" > "$GOOGLE_BUILD"
expect_fail 'a coordinate string instead of a catalogue alias fails' \
    'unrecognised-dependency-form'

# --- h. the Swift allowlist ---------------------------------------------------------
sed 's#^    dependencies: \[#    dependencies: [\n        .package(url: "https://example.invalid/UnreviewedPackage", from: "1.0.0"),#' \
    "$TMP/manifest.bak" > "$MANIFEST"
expect_fail 'a Swift package the module graph does not declare fails' \
    'does not declare: UnreviewedPackage'

# --- i, j, k. every new scan fails red when it cannot run --------------------------
: > "$TMP/empty-graph.json"
expect_unrunnable 'a vocabulary scan that reaches no files fails instead of passing' \
    'it did not run over the surface' \
    "s#^SURFACE_DIRS='.*'#SURFACE_DIRS='probe-no-such-surface-dir'#"

expect_unrunnable 'an adapter exception scoped to paths with no sources fails' \
    'the scope is stale' \
    's#^find Sources/SPFNSocialApple Sources/SPFNSocialGoogle \\#find probe-no-apple probe-no-google \\#'

expect_unrunnable 'an unreadable module graph fails instead of allowing nothing quietly' \
    'it could not run' \
    "s#^GRAPH=tools/module-graph.json#GRAPH=$TMP/empty-graph.json#"

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
