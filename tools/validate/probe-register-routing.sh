#!/bin/sh
# SPFN Mobile — proves the pitfall register's routing check bites.
#
# docs/IMPLEMENTATION-PITFALLS.md is only a device if every entry is reachable from its
# trigger table. tools/validate/validate.sh holds that shut by counting three numbers —
# entry headings, headings carrying an anchor, distinct anchors — and by extracting
# routes from table rows only. Each of those was added because a review found the check
# passing while the document was already broken, so each is held to both sides here.
#
# Run it from the repository root. Zero dependencies, offline.
#
#   sh tools/validate/probe-register-routing.sh
#
# This probe MUTATES the register in place and restores it from its own copy, because
# validate.sh reads a fixed path and an environment override would be a hole in the gate
# it is meant to prove. The restore is a trap on EXIT, INT and TERM and it copies from
# the backup — never from git, which would take uncommitted work with it.

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

REGISTER=docs/IMPLEMENTATION-PITFALLS.md
VALIDATOR=tools/validate/validate.sh

for path in "$REGISTER" "$VALIDATOR"
do
    if [ ! -f "$path" ]
    then
        printf 'probe cannot run: missing %s\n' "$path" >&2
        exit 2
    fi
done

WORK=${TMPDIR:-/tmp}/spfn-register-probe.$$
mkdir -p "$WORK" || exit 2
BACKUP="$WORK/register.original"
cp "$REGISTER" "$BACKUP" || exit 2
trap 'cp "$BACKUP" "$REGISTER" 2>/dev/null; rm -rf "$WORK"' EXIT INT TERM

# The one line every case shares: run the real validator and report only the register's
# own check, so a probe cannot pass on some unrelated failure elsewhere in the build.
register_verdict()
{
    sh "$VALIDATOR" 2>&1 | grep -i 'pitfall register' | head -1
}

expect_fail()
{
    verdict=$(register_verdict)
    case "$verdict" in
        *FAIL*) pass "$1" ;;
        *)      fail "$1 (validator said: ${verdict:-<no register line at all>})" ;;
    esac
}

expect_pass()
{
    verdict=$(register_verdict)
    case "$verdict" in
        *ok*) pass "$1" ;;
        *)    fail "$1 (validator said: ${verdict:-<no register line at all>})" ;;
    esac
}

printf 'SPFN Mobile — pitfall register routing probe\n\n'

expect_pass 'the register as committed passes its own routing check'

# 1. An entry a reader can see and the table cannot reach.
printf '\n## P90. probe: an entry with no anchor\n\nprobe body.\n' >> "$REGISTER"
expect_fail 'an entry heading without an anchor fails'
cp "$BACKUP" "$REGISTER"

# 2. Two entries behind one anchor. Anchors are collected as map keys, so this is the
#    case that collapses into a single key and passes every reachability test.
printf '\n## P90. probe: an entry stealing P1 {#p1}\n\nprobe body.\n' >> "$REGISTER"
expect_fail 'two entries sharing one anchor fails'
cp "$BACKUP" "$REGISTER"

# 3. A route that lives in prose rather than a table row. The row is gone; the region
#    still mentions the anchor.
awk '
/^\| 이 문서 자체 수정/ {
    sub(/\[P14\]\(#p14\) /, "")
    print
    print ""
    print "덧붙임 산문: [P14](#p14) 도 관련이 있다."
    next
}
{ print }
' "$BACKUP" > "$REGISTER"
expect_fail 'a route in prose rather than a table row fails'
cp "$BACKUP" "$REGISTER"

# 4. The same, inside a fenced block that happens to start with a pipe. A fenced line is
#    an example, and an example must not satisfy structure.
awk '
/^\| 이 문서 자체 수정/ {
    sub(/\[P14\]\(#p14\) /, "")
    print
    print ""
    print "```"
    print "| 예시 행 | [P14](#p14) |"
    print "```"
    next
}
{ print }
' "$BACKUP" > "$REGISTER"
expect_fail 'a route inside a fenced example fails'
cp "$BACKUP" "$REGISTER"

# 5. The relation is many-to-one on purpose: an entry reachable from several trigger
#    rows is correct, and P2 is deliberately routed from three. A check that forbade
#    this would distort the table it protects.
sed 's|\[P11\](#p11) \[P12\](#p12)|[P11](#p11) [P12](#p12) [P2](#p2)|' "$BACKUP" > "$REGISTER"
expect_pass 'an entry routed from an extra trigger row still passes'
cp "$BACKUP" "$REGISTER"

# 6. The scan must not report clean when it could not read the document at all.
: > "$REGISTER"
expect_fail 'an empty register fails rather than reporting nothing wrong'
cp "$BACKUP" "$REGISTER"

expect_pass 'the register is byte-identical to where it started'

printf '\n%d checks, %d failures\n' "$CHECKS" "$FAILURES"
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
