#!/bin/sh
# SPFN Mobile — proves the plain-button refusal (validator section 20) refuses what it must.
#
# `.buttonStyle(.plain)` leaves a button's hit shape at the pixels its label drew, so a role
# button answered only over its letters and a header glyph only over itself
# (docs/IMPLEMENTATION-PITFALLS.md P39). SPFNUI's own button styles now set the hit shape on
# the label they are handed; what section 20 refuses is the one spelling that brings the
# defect back. A refusal is worth what its proof is worth, so this asks:
#
#   a. the tree as committed passes;
#   b. `.buttonStyle(.plain)` on a SPFNUI button fails, naming the file;
#   c. the `PlainButtonStyle()` spelling fails too;
#   d. a comment that names the refused spelling is spared.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
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

SCREEN=Sources/SPFNUI/Components/Screen.swift

cp "$SCREEN" "$TMP/screen.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/screen.bak" "$SCREEN"
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

# Runs the validator, expecting it to fail naming MARKER, then restores the file.
expect_fail()
{
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        fail "$1 — the validator passed"
    elif grep -qF -- "$2" "$TMP/run.log"
    then
        pass "$1"
    else
        fail "$1 — the validator failed, but not on the expected rule"
    fi
    cp "$TMP/screen.bak" "$SCREEN"
}

# Runs the validator, expecting it to pass, then restores the file.
expect_pass()
{
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        pass "$1"
    else
        fail "$1 — the validator failed"
    fi
    cp "$TMP/screen.bak" "$SCREEN"
}

printf 'button hit-shape probe\n'

expect_pass 'the tree as committed passes'

sed 's/\.buttonStyle(HeaderControlStyle())/.buttonStyle(.plain)/' "$TMP/screen.bak" > "$SCREEN"
expect_fail 'a header control styled .plain fails, naming the file' \
    "$SCREEN"

sed 's/\.buttonStyle(HeaderControlStyle())/.buttonStyle(PlainButtonStyle())/' "$TMP/screen.bak" > "$SCREEN"
expect_fail 'the PlainButtonStyle() spelling fails too' \
    'styles a Button plain'

printf '// A header control is never `.buttonStyle(.plain)`; see HeaderControlStyle.\n' >> "$SCREEN"
expect_pass 'a comment naming the refused spelling is spared'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
