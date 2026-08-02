#!/bin/sh
# SPFN Mobile — proves the D11 documentation guardrail.
#
# D11 (resolved 2026-08-02) settled that Swift Package Manager is the only iOS
# distribution channel and that CocoaPods is not supported, with no activation condition
# recorded on purpose. tools/validate/validate.sh enforces that with two assertions: a
# fixed-string check on the D11 row in docs/OPEN-DECISIONS.md, and a negative match of
# tools/validate/d11-forbidden.ere against tools/cocoapods-compat/README.md.
#
# A negative check is only worth its line if it bites. This probe holds the pattern to
# both sides: sentences that must be caught, and the README's real wording, which must
# not be. Run it from the repository root. Zero dependencies, offline.
#
#   sh tools/validate/probe-d11-guardrail.sh

set -u

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

PATTERN_FILE=tools/validate/d11-forbidden.ere
README=tools/cocoapods-compat/README.md
DECISIONS=docs/OPEN-DECISIONS.md
D11_ROW='| D11 | iOS distribution channel and the CocoaPods fixture | **RESOLVED 2026-08-02** |'

for path in "$PATTERN_FILE" "$README" "$DECISIONS"
do
    if [ ! -f "$path" ]
    then
        printf 'probe cannot run: missing %s\n' "$path" >&2
        exit 2
    fi
done

PATTERN=$(grep -v '^#' "$PATTERN_FILE" | grep -v '^$')
if [ -z "$PATTERN" ]
then
    printf 'probe cannot run: %s carries no pattern\n' "$PATTERN_FILE" >&2
    exit 2
fi

# Sentences that reopen D11. Each must match, or the guardrail has a hole.
# The first three are the wording this repository actually carried before the decision
# landed; the rest are the reformulations a later edit is most likely to reach for.
printf '\nsentences the guardrail must catch\n'
MISSED=0
CAUGHT=0
while IFS= read -r line
do
    [ -n "$line" ] || continue
    if printf '%s\n' "$line" | grep -qiE "$PATTERN"
    then
        CAUGHT=$((CAUGHT + 1))
    else
        printf '  --    slipped through: %s\n' "$line"
        MISSED=$((MISSED + 1))
    fi
done <<'FORBIDDEN'
The CocoaPods compatibility tier is a proposal awaiting human confirmation.
If a real customer requirement appears, the supported paths are a Git-tag podspec or a private specs repository.
Activated only after separate approval, and only if the result passes the same gates as SwiftPM.
CocoaPods support may be enabled after a separate approval.
The tier can be activated once a customer asks for it.
We will publish a podspec if demand appears.
A pod would be supported after the release train stabilises.
Consumers may opt in to the CocoaPods channel.
The compatibility tier is still awaiting confirmation.
FORBIDDEN

if [ "$MISSED" -eq 0 ]
then
    pass "every reopening sentence is caught ($CAUGHT of $CAUGHT)"
else
    fail "$MISSED reopening sentence(s) slipped through the guardrail"
fi

# The README's own wording denies the tier rather than offering it. A pattern that
# matched these would fail the build on correct documentation, which is worse than a
# hole: it teaches the next author to weaken the check.
printf '\nwording the guardrail must not catch\n'
FALSE_POSITIVES=0
KEPT=0
while IFS= read -r line
do
    [ -n "$line" ] || continue
    if printf '%s\n' "$line" | grep -qiE "$PATTERN"
    then
        printf '  --    false positive: %s\n' "$line"
        FALSE_POSITIVES=$((FALSE_POSITIVES + 1))
    else
        KEPT=$((KEPT + 1))
    fi
done <<'ALLOWED'
Swift Package Manager is the only iOS distribution channel for SPFN Mobile v1 and CocoaPods is not supported.
Nothing in this directory is published, and the existence of a podspec here is not a commitment to publish one.
No activation condition is written down here, and that omission is deliberate.
A condition on the page would read as a route anyone could ask for.
If a real requirement ever appears, it is judged as a separate decision at that time.
Upstream CocoaPods trunk is in maintenance mode with a stated read-only target date, so trunk could not be a long-term channel in any case.
This directory exists to prove the single-source claim above; it is not preparation for a publication path.
ALLOWED

if [ "$FALSE_POSITIVES" -eq 0 ]
then
    pass "the decided wording is not caught ($KEPT of $KEPT)"
else
    fail "$FALSE_POSITIVES line(s) of correct documentation would fail the build"
fi

# The live documents, as they stand right now.
if grep -qiE "$PATTERN" "$README"
then
    fail "$README currently matches the guardrail pattern"
else
    pass "$README currently carries no reopening wording"
fi

if grep -qF -- "$D11_ROW" "$DECISIONS"
then
    pass "$DECISIONS carries the resolved D11 row the validator asserts"
else
    fail "$DECISIONS no longer carries the exact D11 row the validator asserts"
fi

# The validator must read this pattern file rather than carry a second copy of the
# pattern, otherwise the probe proves something the build does not use.
if grep -qF 'tools/validate/d11-forbidden.ere' tools/validate/validate.sh
then
    pass 'validate.sh reads the guardrail pattern from this file'
else
    fail 'validate.sh does not read tools/validate/d11-forbidden.ere'
fi

printf '\n%s checks, %s failures\n' "$CHECKS" "$FAILURES"
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
