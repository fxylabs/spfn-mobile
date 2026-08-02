#!/bin/sh
# SPFN Mobile — proves the D11 documentation guardrail.
#
# D11 (resolved 2026-08-02) settled that Swift Package Manager is the only iOS
# distribution channel and that CocoaPods is not supported, with no activation condition
# recorded on purpose. tools/validate/validate.sh holds that shut three ways:
#
#   1. a fixed-string check on the D11 row in docs/OPEN-DECISIONS.md;
#   2. a digest pin on the policy section of tools/cocoapods-compat/README.md, taken
#      from tools/validate/d11-policy.lock.json — this is the gate, and it refuses every
#      edit rather than the phrasings someone thought to enumerate;
#   3. a case-insensitive blocklist, tools/validate/d11-forbidden.ere, over the rest of
#      that file, where free prose is legitimate and a digest would be too rigid.
#
# A check is only worth its line if it bites. This probe holds each one to both sides.
# Run it from the repository root. Zero dependencies, offline.
#
#   sh tools/validate/probe-d11-guardrail.sh
#   sh tools/validate/probe-d11-guardrail.sh --print-digest   # after an approved edit

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
LOCK=tools/validate/d11-policy.lock.json
README=tools/cocoapods-compat/README.md
DECISIONS=docs/OPEN-DECISIONS.md
D11_ROW='| D11 | iOS distribution channel and the CocoaPods fixture | **RESOLVED 2026-08-02** |'

for path in "$PATTERN_FILE" "$LOCK" "$README" "$DECISIONS"
do
    if [ ! -f "$path" ]
    then
        printf 'probe cannot run: missing %s\n' "$path" >&2
        exit 2
    fi
done

json_string()
{
    sed -n "s/.*\"$2\": *\"\([^\"]*\)\".*/\1/p" "$1" | head -1
}

json_number()
{
    sed -n "s/.*\"$2\": *\([0-9][0-9]*\).*/\1/p" "$1" | head -1
}

sha256_of()
{
    if command -v shasum > /dev/null 2>&1
    then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

SECTION=$(json_string "$LOCK" section)
PINNED=$(json_string "$LOCK" sha256)
PINNED_LINES=$(json_number "$LOCK" lines)

# Same extraction the validator performs, kept identical on purpose: the probe must
# measure the text the build measures, not a second reading of the document.
extract_section()
{
    awk -v heading="$SECTION" \
        '$0 == heading {f = 1; print; next} f && /^## / {f = 0} f {print}' "$1"
}

WORK=${TMPDIR:-/tmp}/spfn-d11-probe.$$
mkdir -p "$WORK" || exit 2
trap 'rm -rf "$WORK"' EXIT INT TERM

extract_section "$README" > "$WORK/section.txt"

if [ "${1:-}" = "--print-digest" ]
then
    printf '%s  %s lines\n' "$(sha256_of "$WORK/section.txt")" "$(wc -l < "$WORK/section.txt" | tr -d ' ')"
    exit 0
fi

PATTERN=$(grep -v '^#' "$PATTERN_FILE" | grep -v '^$')
if [ -z "$PATTERN" ]
then
    printf 'probe cannot run: %s carries no pattern\n' "$PATTERN_FILE" >&2
    exit 2
fi

printf '\nthe pinned policy statement\n'

if [ -s "$WORK/section.txt" ]
then
    pass "the extractor finds '$SECTION' in $README"
else
    fail "the extractor cannot find '$SECTION' in $README"
fi

# The extraction must stop at the next heading. If it ran to the end of the file it
# would still produce a stable digest, and the pin would silently cover the whole
# document — a check that passes for the wrong reason.
if [ "$(grep -c '^## ' "$WORK/section.txt")" = "1" ]
then
    pass 'the extracted section stops at the next level-2 heading'
else
    fail 'the extracted section spans more than one level-2 heading'
fi

if [ "$(wc -l < "$WORK/section.txt" | tr -d ' ')" = "$PINNED_LINES" ]
then
    pass "the extracted section is the $PINNED_LINES lines the lock declares"
else
    fail "the extracted section is not the $PINNED_LINES lines the lock declares"
fi

if [ "$(sha256_of "$WORK/section.txt")" = "$PINNED" ]
then
    pass 'the policy statement matches the pinned digest'
else
    fail 'the policy statement no longer matches the pinned digest'
fi

# The pin is the gate, so prove it moves on the smallest possible edit — one that no
# blocklist would ever catch, because nothing about it is suspicious.
sed 's/deliberate\./deliberate and settled./' "$WORK/section.txt" > "$WORK/mutated.txt"
if [ "$(sha256_of "$WORK/mutated.txt")" = "$PINNED" ]
then
    fail 'a reworded policy statement produces the pinned digest'
else
    pass 'a one-word rewording of the policy statement breaks the pinned digest'
fi

# Sentences that reopen D11. Each must match, or the second net has a hole. The first
# three are the wording this repository actually carried before the decision landed; the
# rest are reformulations raised in review, including five the reviewer wrote
# independently after the first pattern was thought to be complete.
printf '\nsentences the blocklist must catch\n'
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
CocoaPods may become available if a customer requests it.
A future release can offer CocoaPods support following a product decision.
CocoaPods could return as an optional distribution channel.
We would consider publishing a podspec in response to demand.
Customers may request CocoaPods through the release team.
FORBIDDEN

if [ "$MISSED" -eq 0 ]
then
    pass "every reopening sentence is caught ($CAUGHT of $CAUGHT)"
else
    fail "$MISSED reopening sentence(s) slipped through the blocklist"
fi

# Wording that denies the tier rather than offering it. A pattern that matched these
# would fail the build on correct documentation, which is worse than a hole: it teaches
# the next author to weaken the check. The first group is the README's own text; the
# second is written independently of it, so a pattern and a probe edited together to
# match nothing still have to survive sentences the README does not contain.
printf '\nwording the blocklist must not catch\n'
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
The generator derives every subspec from tools/module-graph.json, so the fixture cannot describe a second source tree.
The validator fails the build when the generated podspec is hand-edited.
Running pod ipc spec parses the fixture offline and contacts no specs CDN.
Each subspec points at the same Sources directory the SwiftPM manifest uses.
The fixture version is required to equal the contents of VERSION.
ALLOWED

if [ "$FALSE_POSITIVES" -eq 0 ]
then
    pass "the decided and descriptive wording is not caught ($KEPT of $KEPT)"
else
    fail "$FALSE_POSITIVES line(s) of correct documentation would fail the build"
fi

printf '\nthe documents as they stand\n'

if grep -qiE "$PATTERN" "$README"
then
    fail "$README currently matches the blocklist"
else
    pass "$README currently carries no reopening wording"
fi

if grep -qF -- "$D11_ROW" "$DECISIONS"
then
    pass "$DECISIONS carries the resolved D11 row the validator asserts"
else
    fail "$DECISIONS no longer carries the exact D11 row the validator asserts"
fi

# The validator must read both the lock and the pattern file rather than carry its own
# copy of either, otherwise this probe proves something the build does not use.
if grep -qF 'tools/validate/d11-forbidden.ere' tools/validate/validate.sh
then
    pass 'validate.sh reads the blocklist from d11-forbidden.ere'
else
    fail 'validate.sh does not read tools/validate/d11-forbidden.ere'
fi

if grep -qF 'tools/validate/d11-policy.lock.json' tools/validate/validate.sh
then
    pass 'validate.sh reads the pinned digest from d11-policy.lock.json'
else
    fail 'validate.sh does not read tools/validate/d11-policy.lock.json'
fi

# A guardrail file that .gitignore swallows passes every check on the machine that wrote
# it and does not exist in a fresh clone. `.gitignore` carries `*.lock`, which is how
# the pin file earned its `.json` suffix. The validator itself must not need git, so
# this runs here and only when git can answer.
if command -v git > /dev/null 2>&1 && git rev-parse --git-dir > /dev/null 2>&1
then
    UNTRACKED=''
    for path in "$PATTERN_FILE" "$LOCK" "$README" "$DECISIONS" tools/validate/validate.sh
    do
        git ls-files --error-unmatch "$path" > /dev/null 2>&1 || UNTRACKED="$UNTRACKED $path"
    done
    if [ -z "$UNTRACKED" ]
    then
        pass 'every file the guardrail depends on is tracked by git'
    else
        fail "the guardrail depends on untracked file(s):$UNTRACKED"
    fi
else
    printf '  --    git is unavailable; tracked-file check skipped\n'
fi

printf '\n%s checks, %s failures\n' "$CHECKS" "$FAILURES"
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
