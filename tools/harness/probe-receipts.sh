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
#
# `case_status` takes the case name as an argument, so the approval cells d1-d3 are
# covered by it without naming them. What those three add is a SECOND gate — the server's
# own answer, read out of a poll response with `json_string` — and that reader gets a probe
# of its own below. Its fail-closed property is one line: an answer nothing could be read
# out of is the empty string, and the empty string is never equal to `approved`,
# `DeviceAuthDeniedError` or `pending`, so an unreadable poll withholds a receipt exactly
# as a failed flow does (docs/IMPLEMENTATION-PITFALLS.md P7).

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$ROOT/tools/harness/run-harness.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

sed -n '/^case_status()/,/^}/p' "$RUNNER" > "$WORK/case_status.sh"
sed -n '/^json_string()/,/^}/p' "$RUNNER" >> "$WORK/case_status.sh"
if [ ! -s "$WORK/case_status.sh" ]
then
    printf 'FAIL  case_status could not be extracted from run-harness.sh\n'
    printf '      the probe cannot pass by failing to find what it tests\n'
    exit 1
fi
# shellcheck source=/dev/null
. "$WORK/case_status.sh"

if ! command -v json_string > /dev/null 2>&1
then
    printf 'FAIL  json_string could not be extracted from run-harness.sh\n'
    printf '      the probe cannot pass by failing to find what it tests\n'
    exit 1
fi

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
    <!-- Two of the approval cells by name, so the reader is shown covering them rather
         than assumed to: `case_status` takes the case as an argument and knows no case
         names of its own, and this is what says so out loud. -->
    <testcase name="d1-approve" classname="Flow"/>
    <testcase name="d2-deny" classname="Flow">
      <failure>Assertion is false: "http=204" is visible</failure>
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
check "$WORK/report.xml" d1-approve receipt
check "$WORK/report.xml" d2-deny none
check "$WORK/report.xml" d3-unknown-code none

printf '\nthe answer the server itself gave, read out of a poll response\n\n'

# The three answers the approval cells assert on, and the three ways an answer can be
# absent. The last three are the fail-closed cases: what comes back is the empty string,
# which equals none of the three expectations, so no receipt is written.
answers()
{
    if [ "$(json_string "$2" "$3")" = "$4" ]
    then
        printf 'ok    %-22s -> %s\n' "$1" "${4:-<empty>}"
    else
        printf 'FAIL  %-22s -> %s, expected %s\n' "$1" "$(json_string "$2" "$3")" "${4:-<empty>}"
        STATUS=1
    fi
}

answers 'a pending poll' '{"intervalMillis":200,"status":"pending"}' status pending
answers 'an approved poll' '{"publicId":"public-u","status":"approved","userId":"u"}' status approved
answers 'a denial envelope' \
    '{"error":{"code":"DeviceAuthDeniedError","message":"the device request was denied","requestId":"r"}}' \
    code DeviceAuthDeniedError
answers 'an empty body' '' status ''
answers 'a body that is not JSON' 'Bad Gateway' status ''
answers 'a poll with no status' '{"error":{"code":"DeviceAuthNotFoundError"}}' status ''

printf '\n'
if [ "$STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
