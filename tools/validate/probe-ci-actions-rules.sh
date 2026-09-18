#!/bin/sh
# SPFN Mobile — proves section 24's action pinning rule refuses what it must.
#
# D14 (resolved 2026-09-18) is a relaxation: actions went from "one, in one workflow" to
# "whatever tools/ci/actions-allowlist.txt records, pinned by commit SHA". A relaxation is
# where holes are born, so the three ways the rule can stop biting are exercised here,
# against the real validator, on real (temporarily mutated, cp-backed) files:
#
#   a. the tree as committed passes section 24;
#   b. a tag reference in place of a SHA fails, even for an action already on the list;
#   c. an action pinned by a SHA nobody listed fails;
#   d. a deleted allowlist fails as "did not run" rather than passing with nothing to read;
#   e. a tag entry inside the allowlist itself fails, so the list cannot admit a moving ref.
#
# The validator exits non-zero for reasons that have nothing to do with this section — the
# device-receipt gate is red on purpose — so every judgement here is made on section 24's
# own rows and never on the exit code.
#
# Offline, zero toolchain.
#
#   sh tools/validate/probe-ci-actions-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-ciactions-probe.XXXXXX")

WORKFLOW=.github/workflows/swift.yml
ALLOWLIST=tools/ci/actions-allowlist.txt

cp "$WORKFLOW" "$TMP/workflow.bak"
cp "$ALLOWLIST" "$TMP/allowlist.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/workflow.bak" "$WORKFLOW"
        cp "$TMP/allowlist.bak" "$ALLOWLIST"
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

# Section 24 reports exactly one row per run. Its text is what is judged, because the
# validator's exit code answers a different question.
section_24_row()
{
    sh tools/validate/validate.sh 2>&1 \
        | grep -E '^  (ok|FAIL)  .*(action reference\(s\) under|actions used by a workflow but not in|action pinning rule did not run|not 40-hex commit pins)' \
        | head -1
}

expect_fail()
{
    ROW=$(section_24_row)
    case "$ROW" in
        '  FAIL  '*"$2"*) pass "$1" ;;
        '  FAIL  '*) fail "$1 — section 24 failed, but not on the expected rule: $ROW" ;;
        *) fail "$1 — section 24 did not fail: $ROW" ;;
    esac
    cp "$TMP/workflow.bak" "$WORKFLOW"
    cp "$TMP/allowlist.bak" "$ALLOWLIST"
}

printf 'CI action pinning rules probe\n'

# --- a. the committed tree ---------------------------------------------------------
ROW=$(section_24_row)
case "$ROW" in
    '  ok  '*) pass 'the tree as committed passes the action pinning rule' ;;
    *) fail "the tree as committed does not pass the action pinning rule: $ROW" ;;
esac

# --- b. a tag where a SHA belongs --------------------------------------------------
sed -i.probe 's|uses: actions/checkout@[0-9a-f]\{40\}.*|uses: actions/checkout@v7|' "$WORKFLOW"
rm -f "$WORKFLOW.probe"
expect_fail 'a tag reference in place of the pinned SHA fails' 'not in tools/ci/actions-allowlist.txt'

# --- c. a SHA nobody listed --------------------------------------------------------
sed -i.probe 's|uses: actions/checkout@[0-9a-f]\{40\}.*|uses: actions/setup-java@0000000000000000000000000000000000000000|' "$WORKFLOW"
rm -f "$WORKFLOW.probe"
expect_fail 'an action pinned by an unlisted SHA fails' 'not in tools/ci/actions-allowlist.txt'

# --- d. no list at all -------------------------------------------------------------
rm -f "$ALLOWLIST"
expect_fail 'a deleted allowlist fails as "did not run" rather than passing' 'did not run'

# --- e. a moving ref inside the list itself ----------------------------------------
printf 'actions/setup-java@v4  # a tag, which a pin is not\n' >> "$ALLOWLIST"
expect_fail 'an allowlist entry that is not a 40-hex pin fails' 'not 40-hex commit pins'

# --- the unmodified tree still passes ----------------------------------------------
ROW=$(section_24_row)
case "$ROW" in
    '  ok  '*) pass 'the tree passes the action pinning rule again after every restoration' ;;
    *) fail "a probe leaked a mutation: $ROW" ;;
esac

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
