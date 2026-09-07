#!/bin/sh
# SPFN Mobile — proves the blanket pointer-consumption check refuses what it must.
#
# Section 17 of the validator guards the defect this repository has no other way to see. A
# Compose modifier that consumes every pointer change on the Main pass cancels the press of
# every control under it on the FINAL pass — and only for a PERSON. Every runner here
# synthesises a DOWN and an UP with nothing between them, so all 35 device cells stay green,
# Maestro stays green, `adb shell input tap` stays green, and the screen is dead under a
# thumb (docs/IMPLEMENTATION-PITFALLS.md P36).
#
# Which means the check is only worth what its fail-closed proof is worth (P7). This asks:
#
#   a. the block spelling planted in `FlowHost.kt` fails, naming that file;
#   b. the call spelling planted in `Sheet.kt` fails, naming that file — a second file,
#      because a check that hard-coded the one file the defect came from would pass this
#      probe's first half and cover one file of twenty-two;
#   c. the block spelling written across three LINES fails. It is the same defect and the
#      same code, and a reader that matched line by line would report the module clean;
#   d. the spelling written only inside a COMMENT fails as well. That is deliberate rather
#      than incidental: section 17 reads the file as text, because a Kotlin comment-stripper
#      that is wrong about nesting or string literals hides code, and the price is that the
#      module may not quote the forbidden line in prose either;
#   e. a source root that resolves to nothing fails instead of reporting a clean module —
#      the floor, which is the half of a reader that goes quiet rather than red.
#
# e runs a ROOT-pinned copy of the validator whose own input has been taken away, because
# its subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-pointer-consumption-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-pointer-probe.XXXXXX")

FLOW_HOST=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowHost.kt
SHEET=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Sheet.kt

cp "$FLOW_HOST" "$TMP/flow-host.bak"
cp "$SHEET" "$TMP/sheet.bak"

restore_files()
{
    cp "$TMP/flow-host.bak" "$FLOW_HOST"
    cp "$TMP/sheet.bak" "$SHEET"
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

# Section 17 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate
# — and a probe that keyed on the validator's exit status would report those as its own
# evidence.
POINTER_SECTION='/^17\. no pointer input/,$p'

# Plants a line at the top of a file, after the shebang-free Kotlin header, without needing a
# newline in a sed replacement (docs/IMPLEMENTATION-PITFALLS.md P28).
plant()
{
    BACKUP=$1
    TARGET=$2
    LINE=$3
    awk -v line="$LINE" 'NR == 1 { print line } { print }' "$BACKUP" > "$TARGET"
}

# Runs the validator and expects section 17 to refuse, on the named rule.
expect_pointer_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$POINTER_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the pointer consumption section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs the validator and expects section 17 to be clean.
expect_pointer_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$POINTER_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and expects
# the check to say so rather than to report a clean read.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$POINTER_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

printf 'blanket pointer consumption probe\n'

# --- a, b. each spelling, each in a file of its own ---------------------------------
# The block form is what the modal cover carried and the call form is what the same
# intention looks like written as a method reference. One file each, because a check written
# against a single hard-coded path passes whichever of these happens to be the one it reads.
plant "$TMP/flow-host.bak" "$FLOW_HOST" 'private val planted = { e: Any -> e.changes.forEach { it.consume() } };'
expect_pointer_fail 'the block spelling planted in FlowHost fails, naming that file' \
    "$FLOW_HOST:blanket-block"

plant "$TMP/sheet.bak" "$SHEET" 'private val planted = { e: Any -> e.changes.forEach(PointerInputChange::consume) };'
expect_pointer_fail 'the call spelling planted in Sheet fails, naming that file' \
    "$SHEET:blanket-call"

# --- c. the spelling is not a line ---------------------------------------------------
# The same code with its lambda opened and closed on lines of its own, which is how a
# formatter or a longer body would leave it. A line-based reader finds nothing here.
awk 'NR == 1 {
        print "private val planted = { e: Any ->";
        print "    e.changes.forEach {";
        print "        it.consume()";
        print "    }";
        print "};"
     }
     { print }' "$TMP/flow-host.bak" > "$FLOW_HOST"
expect_pointer_fail 'the block spelling written across three lines fails' \
    "$FLOW_HOST:blanket-block"

# --- d. the module carries no copy of the spelling, prose included -------------------
plant "$TMP/sheet.bak" "$SHEET" '// Never write changes.forEach { it.consume() } over a control.'
expect_pointer_fail 'the spelling written only inside a comment fails, which is what reading the file as text costs' \
    "$SHEET:blanket-block"

# --- e. a reader that read nothing is not a reader that found a clean module ---------
expect_unrunnable 'a source root that resolves to nothing fails instead of reporting a clean module' \
    'it did not run' \
    's#^POINTER_SOURCE_ROOT=.*#POINTER_SOURCE_ROOT=android/spfn-ui/src/no-such-directory#'

# --- the unmodified tree still reads clean -------------------------------------------
expect_pointer_clean 'the pointer consumption section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
