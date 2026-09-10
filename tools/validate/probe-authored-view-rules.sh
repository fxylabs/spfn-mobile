#!/bin/sh
# SPFN Mobile — proves the authored-view check refuses what it must.
#
# Section 21 of the validator reads every flow's `views` out of the contract documents and
# holds each screen's two view files to it: an `authored` flow's views are written by hand
# and must NOT carry the generated header, a `reference` flow's must. It is a READER, which
# is the shape of check that goes quiet rather than red — a reader that read nothing finds
# no offenders, and no offenders is what a tree in order also produces
# (docs/IMPLEMENTATION-PITFALLS.md P7). So the probe asks it both questions.
#
#   a. an authored flow whose view still carries the generated header fails, naming the file;
#   b. an authored flow whose view file is gone fails, naming the file;
#   c. the same flow with its four views written by hand passes, so the rule is about WHO
#      wrote the file and not about the switch being thrown;
#   d. a reference flow whose view lost the generated header fails, which is the other
#      direction and the one that says the reader is reading the header rather than the key;
#   e. a reader that can read no screen fails instead of reporting every view in order;
#   f. the unmodified tree reads clean again after every restoration.
#
# Every flow in this repository is `reference` today — `authored` is first written in 2c —
# so a, b and c switch `approveDevice` over on a COPY of its contract document. That is the
# state the section exists for, and a probe that waited for the tree to reach it would be a
# check nobody had run.
#
# e runs a ROOT-pinned copy of the validator whose own input has been taken away, because
# its subject is what the check does when it cannot read: the one condition that cannot be
# produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-authored-view-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-authored-probe.XXXXXX")

DOCUMENT=examples/ui-spec/contracts/approveDevice.md
KOTLIN_VIEWS=examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/generated/views
SWIFT_VIEWS=examples/ios-swiftui/Generated/Views

# The four files `approveDevice` owns in the example app, which are what a switched-over
# flow makes a person's.
ENTER_KOTLIN=$KOTLIN_VIEWS/EnterCodeScreen.kt
REVIEW_KOTLIN=$KOTLIN_VIEWS/ReviewDeviceScreen.kt
ENTER_SWIFT=$SWIFT_VIEWS/EnterCodeView.swift
REVIEW_SWIFT=$SWIFT_VIEWS/ReviewDeviceView.swift

cp "$DOCUMENT" "$TMP/document.bak"
cp "$ENTER_KOTLIN" "$TMP/enter-kotlin.bak"
cp "$REVIEW_KOTLIN" "$TMP/review-kotlin.bak"
cp "$ENTER_SWIFT" "$TMP/enter-swift.bak"
cp "$REVIEW_SWIFT" "$TMP/review-swift.bak"

restore_files()
{
    cp "$TMP/document.bak" "$DOCUMENT"
    cp "$TMP/enter-kotlin.bak" "$ENTER_KOTLIN"
    cp "$TMP/review-kotlin.bak" "$REVIEW_KOTLIN"
    cp "$TMP/enter-swift.bak" "$ENTER_SWIFT"
    cp "$TMP/review-swift.bak" "$REVIEW_SWIFT"
}

restore()
{
    if [ -d "$TMP" ]
    then
        restore_files
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

# Section 21 begins at its own heading and runs to the end of the report. Every assertion
# below is scoped to it: this repository's validator has failing checks in other sections
# for reasons that have nothing to do with who wrote a view, and a probe that keyed on the
# validator's exit status would report those as its own evidence.
AUTHORED_SECTION='/^21\. authored views/,$p'

expect_authored_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$AUTHORED_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the authored view section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

expect_authored_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$AUTHORED_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$AUTHORED_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the section passed while the check could not run"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    rm -f "$COPY"
}

# `approveDevice`, switched over to views a person writes, in the document that says so.
switch_to_authored()
{
    sed 's#"approveDevice": { "entry": "modal", "start": "enterCode" }#"approveDevice": { "entry": "modal", "start": "enterCode", "views": "authored" }#' \
        "$TMP/document.bak" > "$DOCUMENT"
}

# A view as a person would have left it: the same file with the generated header taken off.
write_by_hand()
{
    grep -v 'GENERATED FILE' "$1" > "$TMP/by-hand.txt"
    cp "$TMP/by-hand.txt" "$1"
}

# --- a. the switch thrown and the skeleton left in place ----------------------
# The failure this section exists for. Nothing else in the repository sees it: the generator
# stops emitting those four files the moment the key changes, so `spfnUiVerify` has nothing
# to compare and both apps still compile against the skeleton that is sitting there.
switch_to_authored
expect_authored_fail 'an authored view still carrying the generated header fails, naming the file' \
    'EnterCodeScreen.kt:still-generated'

# --- b. the switch thrown and nothing written ---------------------------------
# The other half, and the one the generator is blind to in the other direction: an authored
# path is not emitted, so an absent file is not a stale file and not a missing one either.
switch_to_authored
rm -f "$REVIEW_SWIFT"
expect_authored_fail 'an authored view that is not there at all fails, naming the file' \
    'ReviewDeviceView.swift:absent'

# --- c. the switch thrown and the views written -------------------------------
switch_to_authored
write_by_hand "$ENTER_KOTLIN"
write_by_hand "$REVIEW_KOTLIN"
write_by_hand "$ENTER_SWIFT"
write_by_hand "$REVIEW_SWIFT"
expect_authored_clean 'an authored flow whose four views are written by hand reads clean'

# --- d. the reference direction ------------------------------------------------
# A generated view whose header was edited off is the mirror image, and it is what says the
# reader reads the FILE rather than trusting the key: without this half, a check that only
# looked at authored flows would pass a tree where every generated view had been edited.
write_by_hand "$ENTER_KOTLIN"
expect_authored_fail 'a reference flow whose view lost the generated header fails, naming the file' \
    'EnterCodeScreen.kt:no-generated-header'

# --- e. the floor ---------------------------------------------------------------
expect_unrunnable 'a reader that can read no screen fails instead of reporting every view in order' \
    'it did not run' \
    's#^AUTHORED_SPEC=.*#AUTHORED_SPEC=/nonexistent-ui-spec#'

# --- f. the unmodified tree still reads clean ------------------------------------
expect_authored_clean 'the authored view section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
