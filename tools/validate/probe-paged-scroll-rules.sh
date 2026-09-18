#!/bin/sh
# SPFN Mobile — proves the PagedView scroll check refuses what it must.
#
# Section 22 guards a defect that has no other reader in this repository. A `LazyColumn`
# inside `Screen(scroll = true)`'s `verticalScroll` is measured against an infinite height
# and Compose throws an IllegalStateException out of the layout pass on the frame the screen
# first appears — at RUNTIME, on a device, with nothing between the mistake and the crash.
# Nothing compiles it away, no JVM unit test in this repository composes a screen, and until
# section 22 the rule lived in one sentence of `PagedView.kt`'s header.
#
# Which means it is worth what its fail-closed proof is worth (P7). This asks:
#
#   a. a file that names `PagedView(` with no `scroll = false` in it fails, naming the file;
#   b. roots that resolve to no Kotlin file at all fail instead of reporting a clean sweep —
#      the call floor, which is the half of a reader that goes quiet rather than red.
#
# b runs a ROOT-pinned copy of the validator whose own inputs have been taken away, because
# its subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# The mutation is made with grep -v rather than sed, because deleting a whole line is what
# the defect looks like and a sed expression carrying a newline is the spelling that differs
# between GNU and BSD (docs/IMPLEMENTATION-PITFALLS.md P28). Mutations are made on a cp copy
# and restored by a trap on every exit path; `git checkout --` is never used, because it
# restores from HEAD and eats uncommitted work.
#
#   sh tools/validate/probe-paged-scroll-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-paged-scroll-probe.XXXXXX")

PAGED_VIEW=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/PagedView.kt

cp "$PAGED_VIEW" "$TMP/paged-view.bak"

restore_files()
{
    cp "$TMP/paged-view.bak" "$PAGED_VIEW"
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

# Section 22 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate —
# and a probe that keyed on the validator's exit status would report those as its own evidence.
PAGED_SECTION='/^22\. a screen that draws a PagedView/,$p'

# Runs the validator and expects section 22 to refuse, on the named rule.
expect_paged_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$PAGED_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the PagedView section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own inputs have been taken away, and expects
# the check to say so rather than to report a clean sweep.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$PAGED_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# Runs the validator and expects section 22 to be clean.
expect_paged_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$PAGED_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

printf 'PagedView scroll rule probe\n'

# --- a. a file that draws a PagedView and says nothing about scroll ------------------------
# The line is DELETED rather than rewritten, which is the shape the defect takes: a screen is
# written, the list is drawn, and nobody says `scroll = false` anywhere in the file.
grep -v 'scroll = false' "$TMP/paged-view.bak" > "$PAGED_VIEW"
expect_paged_fail 'a Kotlin file that names PagedView( with no scroll = false in it fails, naming the file' \
    "$PAGED_VIEW"

# --- b. roots that resolve to nothing are not a clean sweep --------------------------------
expect_unrunnable 'roots that hold no Kotlin file fail instead of reporting a clean sweep' \
    'this check did not run' \
    "s#^PAGED_SCROLL_ROOTS=.*#PAGED_SCROLL_ROOTS='android/no-such-module'#"

# --- the unmodified tree still reads clean -------------------------------------------------
expect_paged_clean 'the PagedView section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
