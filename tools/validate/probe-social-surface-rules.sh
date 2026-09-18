#!/bin/sh
# SPFN Mobile — proves the adapter surface rules (validate.sh section 23) refuse what they
# must.
#
# Section 23 is a SCAN, and a scan is the shape of check that reports the same green
# whether it found nothing or read nothing (docs/IMPLEMENTATION-PITFALLS.md P7). It also
# took over two rows that used to live in a Swift XCTest guarded on an Apple framework, so
# "it covers Android now" is a claim worth biting on rather than believing:
#
#   a. the unmodified tree passes both rows;
#   b. a Swift logging call in an adapter source fails;
#   c. a Kotlin logging call in the Android adapter fails — the half the XCTest never read;
#   d. a provider profile field fails;
#   e. a non-empty `requestedScopes` fails, while the empty literal stays admitted;
#   f. a logging call written inside a comment does NOT fail, and the same text uncommented
#      does — the exclusion is the rule's own escape hatch, so both of its ends are pinned;
#   g. a source tree the scan cannot read fails as "did not run" rather than as clean.
#
# It judges the ROWS rather than the validator's exit code. The exit code today also
# carries the device-receipt rows, which are red for a reason that has nothing to do with
# this section, and a probe reading it would report green for the wrong reason — which is
# the same mistake it exists to catch.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-social-surface-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-surface-probe.XXXXXX")

APPLE_SOURCE=Sources/SPFNSocialApple/SPFNSocialApple.swift
GOOGLE_SOURCE=Sources/SPFNSocialGoogle/SPFNSocialGoogle.swift
ANDROID_SOURCE=android/spfn-social-google/src/main/kotlin/xyz/superfunction/spfn/social/google/SpfnSocialGoogle.kt

cp "$APPLE_SOURCE" "$TMP/apple.bak"
cp "$GOOGLE_SOURCE" "$TMP/google.bak"
cp "$ANDROID_SOURCE" "$TMP/android.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/apple.bak" "$APPLE_SOURCE"
        cp "$TMP/google.bak" "$GOOGLE_SOURCE"
        cp "$TMP/android.bak" "$ANDROID_SOURCE"
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
    cp "$TMP/google.bak" "$GOOGLE_SOURCE"
    cp "$TMP/android.bak" "$ANDROID_SOURCE"
}

# The two rows this section answers with, as the validator prints them when they hold.
CLEAN_C8='ok    C8: no adapter source carries a logging call'
CLEAN_C9='ok    C9: no adapter source reads a provider profile field'

# Runs the validator and expects a specific line in its output, then restores every file.
expect_row()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    if grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator never printed that row"
    fi
    restore_files
}

# The same, against a ROOT-pinned copy whose own input has been taken away: the one
# condition that cannot be produced by editing the tree without destroying it.
expect_row_without_input()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1
    if grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator never printed that row"
    fi
    rm -f "$COPY"
}

printf 'adapter surface rules probe\n'

# --- a. the tree as committed ------------------------------------------------------
expect_row 'the unmodified tree passes C8' "$CLEAN_C8"
expect_row 'the unmodified tree passes C9' "$CLEAN_C9"

# --- b, c. one logging line, on each platform --------------------------------------
printf 'let leaked = print("token")\n' >> "$GOOGLE_SOURCE"
expect_row 'a Swift logging call in an adapter source fails' \
    'FAIL  C8: an adapter that can log is an adapter that can log a token'

printf 'val leaked = android.util.Log.d("spfn", "token")\n' >> "$ANDROID_SOURCE"
expect_row 'a Kotlin logging call in the Android adapter fails' \
    'FAIL  C8: an adapter that can log is an adapter that can log a token'

# --- d, e. what an adapter may read ------------------------------------------------
printf 'let name = credential.fullName\n' >> "$APPLE_SOURCE"
expect_row 'a provider profile field fails' \
    'FAIL  C9: the identity token is the whole of what an adapter reads'

printf 'request.requestedScopes = [.email]\n' >> "$APPLE_SOURCE"
expect_row 'a non-empty requestedScopes fails' \
    'FAIL  C9: the identity token is the whole of what an adapter reads'

printf 'request.requestedScopes = []\n' >> "$APPLE_SOURCE"
expect_row 'the empty scope literal stays admitted' "$CLEAN_C9"

# --- f. both ends of the comment exclusion -----------------------------------------
printf '// describing the refusal: print("token") is what this adapter may not do\n' >> "$GOOGLE_SOURCE"
expect_row 'a logging call inside a comment is not a logging call' "$CLEAN_C8"

printf 'print("token")\n' >> "$GOOGLE_SOURCE"
expect_row 'the same text uncommented fails' \
    'FAIL  C8: an adapter that can log is an adapter that can log a token'

# --- g. a scan that reads nothing is not a clean scan -------------------------------
expect_row_without_input 'a source tree the scan cannot read fails instead of reporting none' \
    'it did not run' \
    "s#^SOCIAL_SURFACE_DIRS=.*#SOCIAL_SURFACE_DIRS='$TMP/no-such-adapter-tree'#"

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
