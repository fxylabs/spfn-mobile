#!/bin/sh
# SPFN Mobile — proves the shared-transition check refuses what it must.
#
# Section 18 of the validator guards a defect nothing else in this repository can see. A
# `NavDisplay` that is not handed a transition spec gets the library's, and the library's is a
# fade forward and a scale-down back (navigation3-ui 1.1.7, read with javap). Every generated
# cell passes either way — a runner asserts what a screen SAYS, never how it arrived — so the
# only thing between a modal flow that moves like the rest of the app and one that does not is
# this check and a person watching a phone (docs/IMPLEMENTATION-PITFALLS.md P37).
#
# Which means it is worth what its fail-closed proof is worth (P7). This asks:
#
#   a. a NavDisplay call planted with no transition specs fails, naming the file it is in.
#      Planted BEFORE the module's real call on purpose: section 18 reads each call's
#      arguments as the text up to the next call, so a planted call that borrowed the real
#      one's arguments would prove nothing;
#   b. a source root that resolves to nothing fails instead of reporting a clean module — the
#      call floor, which is the half of a reader that goes quiet rather than red.
#
# b runs a ROOT-pinned copy of the validator whose own input has been taken away, because its
# subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-flow-transitions-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-transitions-probe.XXXXXX")

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

# Section 18 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate —
# and a probe that keyed on the validator's exit status would report those as its own evidence.
TRANSITION_SECTION='/^18\. every navigator/,$p'

# Runs the validator and expects section 18 to refuse, on the named rule.
expect_transition_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$TRANSITION_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the shared transition section passed"
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
    sed -n "$TRANSITION_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# Runs the validator and expects section 18 to be clean.
expect_transition_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$TRANSITION_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

printf 'shared flow transitions probe\n'

# --- a. a call that states nothing is a call that took the library's opinion --------------
# Written across lines of its own, which is how a real call is written and what a reader that
# matched line by line would miss.
awk 'NR == 1 {
        print "// planted by probe-flow-transitions-rules.sh";
        print "private val planted = {";
        print "    NavDisplay(";
        print "        backStack = listOf(1),";
        print "        entryProvider = { key -> key }";
        print "    );";
        print "};"
     }
     { print }' "$TMP/flow-host.bak" > "$FLOW_HOST"
expect_transition_fail 'a NavDisplay call that states no transition fails, naming the file it is in' \
    "$FLOW_HOST"

# --- b. a reader that read nothing is not a reader that found a clean module --------------
expect_unrunnable 'a source root that resolves to nothing fails instead of reporting a clean module' \
    'it did not run' \
    's#^TRANSITION_SOURCE_ROOT=.*#TRANSITION_SOURCE_ROOT=android/spfn-ui/src/no-such-directory#'

# --- the unmodified tree still reads clean ------------------------------------------------
expect_transition_clean 'the shared transition section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
