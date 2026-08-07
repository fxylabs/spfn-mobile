#!/bin/sh
# SPFN Mobile — proof that a receipt cannot be earned by a case that did not pass.
#
#   sh tools/harness/probe-receipts.sh
#
# run-harness.sh runs every case in ONE maestro invocation, because a per-case invocation
# reinstalls the XCUITest driver each time and that setup was most of the roughly seven
# minutes each flow took. That trade gives up the per-case exit code, and the receipts —
# the only thing standing between this repository and a run that reports coverage it did
# not have — are then derived from the run's JUnit report instead.
#
# Deriving them is new code, and new code that guards a safety is exactly the code worth
# doubting. So this probe drives `case_status` from the runner itself against a report
# holding one of every shape, and checks it fails closed on all four ways a case can fail
# to deserve a receipt:
#
#   passed          -> receipt
#   failed          -> no receipt
#   skipped         -> no receipt
#   not in report   -> no receipt
#   report unreadable -> no receipt
#
# The function is EXTRACTED from run-harness.sh rather than copied here. A copy would go
# on passing after the original changed, which is the failure mode a probe exists to
# prevent.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$ROOT/tools/harness/run-harness.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

sed -n '/^case_status()/,/^}/p' "$RUNNER" > "$WORK/case_status.sh"
if [ ! -s "$WORK/case_status.sh" ]
then
    printf 'FAIL  case_status could not be extracted from run-harness.sh\n'
    printf '      the probe cannot pass by failing to find what it tests\n'
    exit 1
fi
# shellcheck source=/dev/null
. "$WORK/case_status.sh"

cat > "$WORK/report.xml" <<'REPORT'
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="Test Suite">
    <testcase name="passing-case" classname="Flow"/>
    <testcase name="failing-case" classname="Flow">
      <failure>Assertion is false: id: state-label:unenrolled is visible</failure>
    </testcase>
    <testcase name="erroring-case" classname="Flow">
      <error>Device became unreachable</error>
    </testcase>
    <testcase name="skipped-case" classname="Flow">
      <skipped/>
    </testcase>
  </testsuite>
</testsuites>
REPORT

STATUS=0

check()
{
    if case_status "$1" "$2"
    then
        ACTUAL=receipt
    else
        ACTUAL=none
    fi

    if [ "$ACTUAL" = "$3" ]
    then
        printf 'ok    %-16s -> %s\n' "$2" "$ACTUAL"
    else
        printf 'FAIL  %-16s -> %s, expected %s\n' "$2" "$ACTUAL" "$3"
        STATUS=1
    fi
}

printf 'SPFN Mobile — the receipt derivation, probed\n\n'

check "$WORK/report.xml" passing-case receipt
check "$WORK/report.xml" failing-case none
check "$WORK/report.xml" erroring-case none
check "$WORK/report.xml" skipped-case none
check "$WORK/report.xml" absent-case none
check "$WORK/no-such-report.xml" passing-case none

printf '\n'
if [ "$STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
