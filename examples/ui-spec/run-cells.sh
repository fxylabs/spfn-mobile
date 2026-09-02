#!/bin/sh
# SPFN Mobile — the run that drives the example app through the case table.
#
#   sh examples/ui-spec/run-cells.sh ios
#   sh examples/ui-spec/run-cells.sh android --device emulator-5554
#   sh examples/ui-spec/run-cells.sh --probe        # no device; proves the gate bites
#
# tools/harness/run-harness.sh is the exemplar and this is its smaller sibling: that one
# asks whether a person can get through enrolment, this one asks whether the generated
# screen scaffolds behave the way the case table says they do. One flow per cell whose
# runner is `both`, one receipt per flow, and a refusal if any receipt is missing.
#
# ---------------------------------------------------------------------------
# What this script does NOT do
# ---------------------------------------------------------------------------
#
# It builds nothing and installs nothing. The harness builds because it owns its app; this
# one is pointed at an app a person already put on the device, because the two builds need
# two toolchains and neither belongs inside a runner. The commands are:
#
#   iOS      ./gradlew :ui-codegen:spfnGenerateUi
#            xcodegen generate --spec examples/ios-swiftui/project.yml
#            xcodebuild -project examples/ios-swiftui/SPFNExample.xcodeproj \
#                -scheme SPFNExample -destination "id=<udid>" \
#                CODE_SIGNING_ALLOWED=NO build
#            xcrun simctl install <udid> <path to SPFNExample.app>
#
#   Android  ./gradlew :ui-codegen:spfnGenerateUi
#            ./gradlew :example-compose:assembleDebug
#            adb -s <serial> install -r \
#                examples/android-compose/build/outputs/apk/debug/example-compose-debug.apk
#
# ---------------------------------------------------------------------------
# What it refuses to do
# ---------------------------------------------------------------------------
#
# A skipped flow is a passed flow as far as an exit code is concerned, so the receipts are
# the safety and not the exit code: every cell whose runner is `both` must have left a
# `receipt-<cell>-<millis>.json` behind, and a run missing one fails no matter what maestro
# said. The receipt is written by the LAST step of every generated flow, so a flow that
# failed anywhere earlier leaves none — which is what makes a receipt a per-cell verdict
# rather than a suite-level one.
#
# The gate has a floor under it as well: a table it can read no `both` cells out of is a
# refusal, because a gate that expected nothing would pass on an empty device
# (docs/IMPLEMENTATION-PITFALLS.md P7). `--probe` proves both halves without a device.
#
# Requires: maestro, python3, and per platform xcrun or adb.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

FLOWS=examples/ui-spec/generated/flows
CASES=examples/ui-spec/generated/device-approval.cases.json
RECEIPT_ROOT=examples/ui-spec/receipts

# One app id, both platforms — examples/android-compose and examples/ios-swiftui are two
# halves of one example and neither is the harness, whose id ends in `.harness`.
APP_ID=xyz.superfunction.spfn.example

# Every cell of the table that a device runner drives. The floor below is stated as a
# number rather than derived, so a table that lost its cells cannot lower its own bar.
EXPECTED_FLOOR=14

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

pass()
{
    printf 'ok    %s\n' "$1"
}

fail()
{
    printf 'FAIL  %s\n' "$1"
}

require()
{
    if ! command -v "$1" > /dev/null 2>&1
    then
        printf 'run-cells.sh needs %s and cannot find it.\n' "$1" >&2
        if [ $# -gt 1 ]
        then
            printf '  %s\n' "$2" >&2
        fi
        exit 1
    fi
}

# The ids of every cell whose runner is `both`, one per line, read from the table in $1.
#
# Read with python rather than grep for the reason the validator's own reader states: the
# id and the runner are two lines of one object, and pairing them by proximity is how a
# reader ends up confident about a cell it never saw.
expected_cells()
{
    python3 - "$1" <<'CELLS'
import json
import sys

try:
    with open(sys.argv[1]) as handle:
        table = json.load(handle)
except Exception:
    sys.exit(0)

for cell in table.get("cells", []):
    if cell.get("runner") == "both":
        print(cell["id"])
CELLS
}

# The gate. Every cell of table $2 that claims a device runner left a receipt in $1.
#
# Prints one line per cell and the count, and answers non-zero when a cell is missing or
# when the table yielded fewer cells than this repository has. It is the whole of what
# `--probe` exercises, which is why it takes both of its inputs as arguments: a gate that
# could only read the real tree could only be probed by damaging the real tree.
receipt_gate()
{
    GATE_MISSING=''
    GATE_EXPECTED=0
    GATE_FOUND=0

    for CELL in $(expected_cells "$2")
    do
        GATE_EXPECTED=$((GATE_EXPECTED + 1))
        # A glob rather than `find -quit`: this runs on a Mac, whose find is BSD's.
        if ls "$1"/receipt-"$CELL"-*.json > /dev/null 2>&1
        then
            GATE_FOUND=$((GATE_FOUND + 1))
            pass "$CELL"
        else
            GATE_MISSING="$GATE_MISSING $CELL"
            fail "$CELL — no receipt, so this cell did not pass"
        fi
    done

    printf '      %s of %s cells left a receipt\n' "$GATE_FOUND" "$GATE_EXPECTED"

    if [ "$GATE_EXPECTED" -lt "$EXPECTED_FLOOR" ]
    then
        fail "the table named $GATE_EXPECTED cells with a device runner, fewer than $EXPECTED_FLOOR; the gate did not run"
        return 1
    fi
    if [ -n "$GATE_MISSING" ]
    then
        fail "cells with no receipt:$GATE_MISSING"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# --probe: the gate, driven against a fixture directory
# ---------------------------------------------------------------------------
# Runs on any host, device or no device, because what it tests is the only part of this
# script that decides anything. Three questions, and the middle one is the one that
# matters: a receipt taken away has to turn a pass into a failure.
probe()
{
    printf 'SPFN Mobile — the example cell receipt gate, probed\n\n'
    PROBE_STATUS=0

    mkdir -p "$WORK/receipts"
    for CELL in $(expected_cells "$CASES")
    do
        printf '{"cell": "%s"}\n' "$CELL" > "$WORK/receipts/receipt-$CELL-1756800000000.json"
    done

    if receipt_gate "$WORK/receipts" "$CASES" > "$WORK/full.log" 2>&1
    then
        pass 'a receipt for every cell passes the gate'
    else
        fail 'a receipt for every cell did not pass the gate'
        sed 's/^/      /' "$WORK/full.log"
        PROBE_STATUS=1
    fi

    rm -f "$WORK/receipts/receipt-u1-1756800000000.json"
    if receipt_gate "$WORK/receipts" "$CASES" > "$WORK/short.log" 2>&1
    then
        fail 'one receipt deleted still passed the gate'
        PROBE_STATUS=1
    elif grep -q 'cells with no receipt: u1' "$WORK/short.log"
    then
        pass 'one receipt deleted fails the gate, naming the cell'
    else
        fail 'one receipt deleted failed the gate, but not on the missing cell'
        sed 's/^/      /' "$WORK/short.log"
        PROBE_STATUS=1
    fi

    printf '{"cells": []}\n' > "$WORK/no-cells.json"
    if receipt_gate "$WORK/receipts" "$WORK/no-cells.json" > "$WORK/empty.log" 2>&1
    then
        fail 'a table with no cells passed the gate instead of refusing to run'
        PROBE_STATUS=1
    else
        pass 'a table with no cells fails the gate instead of reporting full coverage'
    fi

    printf '\n'
    if [ "$PROBE_STATUS" -eq 0 ]
    then
        printf 'RESULT: PASS\n'
        exit 0
    fi
    printf 'RESULT: FAIL\n'
    exit 1
}

# ---------------------------------------------------------------------------
# The arguments
# ---------------------------------------------------------------------------
if [ "${1-}" = '--probe' ]
then
    require python3 'the case table is read with it; see expected_cells'
    probe
fi

PLATFORM=${1-}
case "$PLATFORM" in
    ios | android) shift ;;
    *)
        printf 'usage: sh examples/ui-spec/run-cells.sh ios|android [--device <id>]\n' >&2
        printf '       sh examples/ui-spec/run-cells.sh --probe\n' >&2
        printf 'the platform is named rather than detected: a guessed platform is a guessed result.\n' >&2
        exit 1
        ;;
esac

TARGET=''
if [ "${1-}" = '--device' ]
then
    TARGET=${2-}
    if [ -z "$TARGET" ]
    then
        printf '--device needs a simulator udid or an adb serial.\n' >&2
        exit 1
    fi
    shift 2
fi
if [ $# -gt 0 ]
then
    printf 'run-cells.sh does not understand: %s\n' "$*" >&2
    exit 1
fi

printf 'SPFN Mobile — the example cell run\n'
printf 'root: %s\n' "$ROOT"
printf 'platform: %s\n\n' "$PLATFORM"

require python3 'the case table is read with it; see expected_cells'
require maestro 'install it with: brew install mobile-dev-inc/tap/maestro'

# ---------------------------------------------------------------------------
printf '1. the target\n'
# ---------------------------------------------------------------------------
# One target or none. Two attached and no choice made is a result nobody can attribute to
# a device, which is the rule tools/harness/run-harness.sh states at greater length.
if [ "$PLATFORM" = ios ]
then
    require xcrun
    if [ -z "$TARGET" ]
    then
        TARGET=$(xcrun simctl list devices booted -j 2> /dev/null \
            | sed -n 's/.*"udid" : "\([^"]*\)".*/\1/p')
    fi
    if [ "$(printf '%s' "$TARGET" | grep -c . || true)" -ne 1 ]
    then
        fail 'exactly one booted simulator is needed and was not found'
        fail 'name one: sh examples/ui-spec/run-cells.sh ios --device <udid>'
        fail 'maestro ships no iOS device driver, so a physical iPhone is not a target here'
        exit 1
    fi
    pass "iOS simulator $TARGET"
else
    require adb
    if [ -z "$TARGET" ]
    then
        TARGET=$(adb devices | sed -n 's/^\([^[:space:]][^[:space:]]*\)[[:space:]][[:space:]]*device$/\1/p')
    fi
    if [ "$(printf '%s' "$TARGET" | grep -c . || true)" -ne 1 ]
    then
        fail 'exactly one attached emulator or device is needed and was not found'
        fail 'name one: sh examples/ui-spec/run-cells.sh android --device <serial>'
        exit 1
    fi
    # A sleeping Android target does not fail a maestro run, it hangs one. Woken here for
    # the reason run-harness.sh records: the first run that met this spent eight minutes
    # with nothing in the log.
    adb -s "$TARGET" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
    adb -s "$TARGET" shell wm dismiss-keyguard > /dev/null 2>&1 || true
    pass "Android target $TARGET"
fi

RUN_DIRECTORY="$RECEIPT_ROOT/$PLATFORM/$(date -u +%Y-%m-%d)"
mkdir -p "$RUN_DIRECTORY"

# ---------------------------------------------------------------------------
printf '\n2. the warm-up\n'
# ---------------------------------------------------------------------------
# One launch with NO fixture, waited out before any cell runs.
#
# The first launch after an install or a device wipe draws later than every launch after
# it — long enough, on a wiped Pixel 3a emulator on 2026-09-02, to outrun cell u14's own
# first wait and be reported as a cell failure. The two answers to that are here and in
# the generated flows, whose first wait is the long one; this half means the cold start is
# paid once, outside the table, where it is a warm-up rather than a red cell.
#
# With no fixture the app installs no fake service and reaches nothing: it draws its
# unconfigured root, whose readouts are the ones waited for here.
cat > "$WORK/warm-up.yaml" <<WARMUP
appId: \${APP_ID}
name: warm-up
---
- launchApp:
    clearState: true

- extendedWaitUntil:
    visible:
      text: "stack=.*"
    timeout: 120000
WARMUP

if maestro --device "$TARGET" test "$WORK/warm-up.yaml" -e APP_ID="$APP_ID" \
    > "$WORK/warm-up.log" 2>&1
then
    pass 'the app launched and drew its root readout'
else
    fail 'the app never drew a root readout, so no cell can be driven'
    fail "is $APP_ID installed? this script installs nothing — see this file's header"
    sed 's/^/      /' "$WORK/warm-up.log" | tail -20
    exit 1
fi

# ---------------------------------------------------------------------------
printf '\n3. the flows\n'
# ---------------------------------------------------------------------------
# ONE maestro invocation for every flow, not one per flow: maestro reinstalls and
# relaunches its driver on every start, and that setup was most of the time each flow took
# when the harness measured it. What it costs is the per-flow exit code, which section 4
# recovers from the receipts — better evidence than an exit code, since a receipt is
# written by the flow's own last step.
REPORT="$RUN_DIRECTORY/report.xml"
FLOW_FILES=$(find "$FLOWS" -name '*.yaml' | sort | tr '\n' ' ')
if [ -z "$(printf '%s' "$FLOW_FILES" | tr -d ' ')" ]
then
    fail "no flow files under $FLOWS; nothing would be run and the run would report nothing"
    exit 1
fi

FLOW_STATUS=0
# shellcheck disable=SC2086
maestro --device "$TARGET" test $FLOW_FILES \
    --format junit \
    --output "$REPORT" \
    -e APP_ID="$APP_ID" \
    > "$RUN_DIRECTORY/maestro.log" 2>&1 || FLOW_STATUS=1

if [ "$FLOW_STATUS" -eq 0 ]
then
    pass 'maestro reported every flow passing'
else
    fail 'maestro reported at least one flow failing; section 4 names which'
fi
printf '      report: %s\n' "$REPORT"

# ---------------------------------------------------------------------------
printf '\n4. the receipts\n'
# ---------------------------------------------------------------------------
# Pulled off the device into this run's own directory, which is where they stay: unlike
# the harness's, these receipts outlive the run, because the case table is a claim about
# two platforms and the receipts are what a reader checks it against.
if [ "$PLATFORM" = ios ]
then
    CONTAINER=$(xcrun simctl get_app_container "$TARGET" "$APP_ID" data 2> /dev/null || true)
    if [ -z "$CONTAINER" ]
    then
        fail "$APP_ID has no data container on $TARGET, so it was never installed or never ran"
        exit 1
    fi
    SOURCE="$CONTAINER/Documents/receipts"
else
    # The external files directory, which is the one an `adb pull` reaches without root
    # and the one examples/android-compose/README.md names.
    SOURCE="$WORK/pulled"
    mkdir -p "$SOURCE"
    adb -s "$TARGET" pull "/sdcard/Android/data/$APP_ID/files/receipts" "$SOURCE" \
        > /dev/null 2>&1 || true
fi

find "$SOURCE" -name 'receipt-*.json' -exec cp {} "$RUN_DIRECTORY/" \; 2> /dev/null || true
pass "receipts collected into $RUN_DIRECTORY"

# ---------------------------------------------------------------------------
printf '\n5. every cell really ran\n'
# ---------------------------------------------------------------------------
RECEIPT_STATUS=0
receipt_gate "$RUN_DIRECTORY" "$CASES" || RECEIPT_STATUS=1

printf '\n'
printf 'target: %s (%s)\n' "$TARGET" "$PLATFORM"
printf 'receipts: %s\n' "$RUN_DIRECTORY"
if [ "$FLOW_STATUS" -eq 0 ] && [ "$RECEIPT_STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
if [ "$FLOW_STATUS" -ne 0 ]
then
    printf '  --    maestro output:\n'
    sed 's/^/      /' "$RUN_DIRECTORY/maestro.log" | tail -40
fi
printf 'RESULT: FAIL\n'
exit 1
