#!/bin/sh
# SPFN Mobile — proves the closing-sheet check refuses what it must.
#
# Section 19 guards a defect nothing else in this repository can see. `FlowHost`'s `when` has
# no subject, so its branches are read top to bottom, and two of them are true at once for a
# sheet whose flow has just closed: `entry is FlowEntry.Sheet` and `routes.isEmpty()`. Put the
# empty-stack line first and the sheet leaves the composition on the frame its stack empties —
# it VANISHES rather than sliding away, which is what a person saw on a Z Flip4
# (docs/IMPLEMENTATION-PITFALLS.md P38). Every generated cell passes either way, because a
# runner waits for an element to appear or to go and never asks how it moved.
#
# Which means it is worth what its fail-closed proof is worth (P7). This asks:
#
#   a. a `FlowHost` whose empty-stack branch is read first fails, naming the file it is in;
#   b. a source file that resolves to nothing fails instead of reporting a clean order — the
#      call floor, which is the half of a reader that goes quiet rather than red.
#
# b runs a ROOT-pinned copy of the validator whose own input has been taken away, because its
# subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# The mutation is made with awk rather than sed: moving a whole line is a delete and an insert,
# and a sed replacement carrying a newline is the spelling that differs between GNU and BSD
# (docs/IMPLEMENTATION-PITFALLS.md P28). Mutations are made on a cp copy and restored by a trap
# on every exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-sheet-exit-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-sheet-exit-probe.XXXXXX")

FLOW_HOST=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowHost.kt

cp "$FLOW_HOST" "$TMP/flow-host.bak"

restore_files()
{
    cp "$TMP/flow-host.bak" "$FLOW_HOST"
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

# Section 19 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate —
# and a probe that keyed on the validator's exit status would report those as its own evidence.
SHEET_SECTION='/^19\. a sheet that is closing/,$p'

# Runs the validator and expects section 19 to refuse, on the named rule.
expect_sheet_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$SHEET_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the closing-sheet section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and expects the
# check to say so rather than to report a clean read.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$SHEET_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# Runs the validator and expects section 19 to be clean.
expect_sheet_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$SHEET_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

printf 'closing sheet order probe\n'

# --- a. the empty stack answered before the sheet is a sheet that vanishes -----------------
# The line is not rewritten, it is MOVED: deleted where it stands and printed again above the
# sheet branch, which is the shape the defect actually took.
awk '
    /^ *routes\.isEmpty\(\) -> Unit/ { next }
    /^ *entry is FlowEntry\.Sheet ->/ { print "        routes.isEmpty() -> Unit" }
    { print }
' "$TMP/flow-host.bak" > "$FLOW_HOST"
expect_sheet_fail 'a FlowHost that answers an empty stack before the sheet branch fails, naming the file it is in' \
    "$FLOW_HOST"

# --- b. a reader that read nothing is not a reader that found a clean order ----------------
expect_unrunnable 'a source file that resolves to nothing fails instead of reporting a clean order' \
    'it did not run' \
    's#^SHEET_ORDER_SOURCE=.*#SHEET_ORDER_SOURCE=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/NoSuchHost.kt#'

# --- the unmodified tree still reads clean -------------------------------------------------
expect_sheet_clean 'the closing-sheet section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
