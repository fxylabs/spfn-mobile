#!/bin/sh
# SPFN Mobile — the CI referee over the offline validator.
#
# `tools/validate/validate.sh` exits non-zero whenever it counts any failure, and this
# repository has failures it has decided to keep: the device-receipt gate is red on
# purpose until a person re-runs fifteen cells on two real phones. A CI job that ran the
# validator directly would be red forever and therefore ignored, and a job that ran it
# with `|| true` would be green forever and therefore worthless.
#
# So this script judges the OUTPUT and decides its own exit code. The validator's exit
# code is read and reported, never obeyed and never discarded.
#
# The rule, exactly:
#   - A row the validator failed is a line beginning `  FAIL  ` — that is what fail()
#     prints, and nothing else in the output has that shape.
#   - A deeper-indented FAIL line is the captured output of a sub-tool that one of those
#     rows ran (the receipt gate prints its own per-cell table, which validate.sh
#     re-indents under the row). It belongs to the row above it and is admitted with it,
#     so only the `  FAIL  ` rows are judged.
#   - Every judged row must appear verbatim in tools/ci/validate-known-red.txt.
#   - Every line in that file must appear as a judged row. A known failure that stopped
#     failing means the list is stale, and a stale allowlist is how a real failure gets
#     admitted six months later.
#
#   sh tools/ci/validate.sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

ALLOWLIST=tools/ci/validate-known-red.txt
TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-ci-validate.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

if [ ! -f "$ALLOWLIST" ]
then
    printf 'CI-VALIDATE: %s is missing; there is no allowlist to judge against.\n' "$ALLOWLIST" >&2
    exit 1
fi

# `set -e` would end the run on the validator's own non-zero exit, which is the exit this
# script exists to interpret. Captured explicitly instead of swallowed with `|| true`.
set +e
sh tools/validate/validate.sh > "$TMP/validate.txt" 2>&1
VALIDATE_STATUS=$?
set -e

cat "$TMP/validate.txt"
printf '\nCI-VALIDATE: tools/validate/validate.sh exited %s\n' "$VALIDATE_STATUS"

sed -n 's/^  FAIL  //p' "$TMP/validate.txt" | sort > "$TMP/observed.txt"
grep -vE '^[[:space:]]*(#|$)' "$ALLOWLIST" | sort > "$TMP/allowed.txt"

UNEXPECTED=$(comm -23 "$TMP/observed.txt" "$TMP/allowed.txt")
STALE=$(comm -13 "$TMP/observed.txt" "$TMP/allowed.txt")

# The validator's own count of failed rows must match what was extracted. If it does not,
# a row failed in a shape this script cannot see, and judging the ones it can see would be
# a false green.
COUNTED=$(sed -n 's/^[0-9][0-9]* checks, \([0-9][0-9]*\) failures$/\1/p' "$TMP/validate.txt" | tail -1)
EXTRACTED=$(wc -l < "$TMP/observed.txt" | tr -d ' ')

STATUS=0

if [ -n "$UNEXPECTED" ]
then
    printf 'CI-VALIDATE: the validator failed rows that are not in %s:\n' "$ALLOWLIST" >&2
    printf '%s\n' "$UNEXPECTED" | sed 's/^/  /' >&2
    STATUS=1
fi

if [ -n "$STALE" ]
then
    printf 'CI-VALIDATE: %s lists failures that no longer happen; remove them:\n' "$ALLOWLIST" >&2
    printf '%s\n' "$STALE" | sed 's/^/  /' >&2
    STATUS=1
fi

if [ "$COUNTED" != "$EXTRACTED" ]
then
    printf 'CI-VALIDATE: the validator counted %s failures but %s rows were readable; a failure was reported in a shape this script cannot judge.\n' \
        "${COUNTED:-no}" "$EXTRACTED" >&2
    STATUS=1
fi

if [ "$STATUS" -eq 0 ]
then
    printf 'CI-VALIDATE: PASS — %s known failure(s), no unexpected one, no stale entry.\n' "$EXTRACTED"
fi

exit "$STATUS"
