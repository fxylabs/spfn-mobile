#!/bin/sh
# SPFN Mobile — proves the button hit-shape check refuses what it must.
#
# Section 20 guards a defect nothing else in this repository can see. `.buttonStyle(.plain)`
# hands the tap to the LABEL, and a view's default hit shape is the part of it that drew
# something — so a plain button whose fill lives OUTSIDE its `Button` answers over its letters
# and nowhere else. A person on an iPhone 14 Pro found the coloured part of every role button
# dead (docs/IMPLEMENTATION-PITFALLS.md P39). Android is unaffected, so section 15 has nothing
# to compare; and Maestro presses the CENTRE of the element it resolved, which is the label,
# which is the one part that worked, so the 35 device cells are green either way.
#
# Which means it is worth what its fail-closed proof is worth (P7). This asks:
#
#   a. a plain-styled file whose `contentShape` rectangle has gone fails, naming that file;
#   b. a source root that resolves to nothing fails instead of reporting a clean module — the
#      floor, which is the half of a reader that goes quiet rather than red.
#
# b runs a ROOT-pinned copy of the validator whose own input has been taken away, because its
# subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# The mutation is made with grep -v rather than sed, because the line to remove is identified
# by a pattern and a `sed` that carried a newline in its replacement is the spelling that
# differs between GNU and BSD (docs/IMPLEMENTATION-PITFALLS.md P28); no expression here uses
# `?` or `+` for the same reason. Mutations are made on a cp copy and restored by a trap on
# every exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-button-hit-shape-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-button-hit-shape-probe.XXXXXX")

BUTTONS=Sources/SPFNUI/Components/Buttons.swift

cp "$BUTTONS" "$TMP/buttons.bak"

restore_files()
{
    cp "$TMP/buttons.bak" "$BUTTONS"
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

# Section 20 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate —
# and a probe that keyed on the validator's exit status would report those as its own evidence.
HIT_SHAPE_SECTION='/^20\. every plain-styled Button/,$p'

# Runs the validator and expects section 20 to refuse, on the named rule.
expect_hit_shape_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$HIT_SHAPE_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the hit-shape section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and expects the
# check to say so rather than to report a clean module.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$HIT_SHAPE_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# Runs the validator and expects section 20 to be clean.
expect_hit_shape_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$HIT_SHAPE_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

printf 'button hit shape probe\n'

# --- a. a plain style with no rectangle to match is a button only its letters answer -------
# The rectangle line is REMOVED and the `.buttonStyle(.plain)` left where it stands, which is
# the shape the defect actually took: the fill was always outside the Button, and what was
# missing was the one modifier that tells the label to answer over all of itself.
grep -vE '^ *\.contentShape\(Rectangle\(\)\)' "$TMP/buttons.bak" > "$BUTTONS"
expect_hit_shape_fail 'a plain-styled file whose contentShape rectangle has gone fails, naming the file it is in' \
    "$BUTTONS"

# --- b. a reader that read nothing is not a reader that found a clean module ---------------
expect_unrunnable 'a source root that resolves to nothing fails instead of reporting a clean module' \
    'it did not run' \
    's#^HIT_SHAPE_SOURCE_ROOT=.*#HIT_SHAPE_SOURCE_ROOT=Sources/NoSuchUIModule#'

# --- the unmodified tree still reads clean -------------------------------------------------
expect_hit_shape_clean 'the hit-shape section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
