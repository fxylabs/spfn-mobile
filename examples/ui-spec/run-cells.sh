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
# One flow at a time, and why it is worth the driver restarts
# ---------------------------------------------------------------------------
#
# Every generated flow opens with `launchApp: clearState: true`, and clearState is a wipe
# of the app's whole store — the data container on iOS, `pm clear` on Android, which also
# takes `/sdcard/Android/data/<pkg>/files` with it. On iOS it goes one step further than an
# emptied directory: the data container is REPLACED, new uuid and all, so a path looked up
# before the run stops existing at the first flow. A receipt lives in that store, wherever
# the store has moved to since. So each cell's own first step deletes the receipt the cell
# before it wrote, and a run that drove all fourteen flows under one maestro and pulled
# once at the end pulled whatever survived the LAST wipe: on a Mac on 2026-09-03, nine of
# fourteen flows passed and the pull found `Documents/receipts` gone and `0 of 14 cells
# left a receipt`.
#
# An in-app receipt and a single end-of-run pull cannot both be right. This script keeps
# the receipt and moves the pull: one `maestro test` per flow file, and that cell's receipt
# copied off the device before the next flow's launch can wipe it. What it costs is a
# driver reinstall per flow, which is most of the wall clock — the harness pays it once and
# derives its per-case verdict from the JUnit report instead (run-harness.sh section 4).
# The example run buys evidence with that time rather than speed, because the receipt is
# written by the flow's own last step and so says more than an exit code does.
#
# ---------------------------------------------------------------------------
# What it refuses to do
# ---------------------------------------------------------------------------
#
# A skipped flow is a passed flow as far as an exit code is concerned, so the receipts are
# the safety and not the exit code: every cell whose runner is `both` must have left a
# `receipt-<cell>-<millis>.json` behind, and a run missing one fails no matter what maestro
# said. The per-flow exit status is printed BESIDE that verdict rather than in place of it,
# because "the flow failed" and "the flow passed and left nothing behind" are two faults
# with two different fixes and one line used to say neither.
#
# The gate has a floor under it as well: a table it can read no `both` cells out of is a
# refusal, and so is a flow directory holding fewer flows than this repository has, because
# a gate that expected nothing would pass on an empty device
# (docs/IMPLEMENTATION-PITFALLS.md P7, P23). `--probe` proves all of it without a device.
#
# ---------------------------------------------------------------------------
# --flow-runner <cmd>: the seam the probe drives
# ---------------------------------------------------------------------------
#
# `<cmd> <cell> <flow file> <destination directory>` replaces the maestro-and-pull step for
# one cell. It drives that cell however it likes, copies the receipts the cell wrote into
# the destination directory, and exits with the flow's own status. `--probe` points it at a
# fixture that models a wipe before every flow, which is how the per-flow pull is proven on
# a host with no device at all.
#
# It is announced in the output when it is used, and the announcement is the point: a run
# maestro did not drive is not a device result, and the line above the receipts is where a
# reader decides that (P12 — a probe convenience must not be a quiet bypass).
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

# Every cell of the table that a device runner drives, and every flow file that drives one.
# The floor below is stated as a number rather than derived, so a table or a flow directory
# that lost its entries cannot lower its own bar.
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

die()
{
    printf '%s\n' "$1" >&2
    exit 1
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

# The flow files, sorted, space separated. Refuses a directory holding fewer than the floor
# rather than driving what it found: the loop below takes its work from this list, so a
# list that shrank would quietly shrink the run and still reach the gate with a full table.
flow_files()
{
    FOUND=$(find "$FLOWS" -name '*.yaml' | sort)
    if [ "$(printf '%s' "$FOUND" | grep -c . || true)" -lt "$EXPECTED_FLOOR" ]
    then
        die "$FLOWS holds fewer than $EXPECTED_FLOOR flow files; the run would prove less than it claims"
    fi
    printf '%s' "$FOUND" | tr '\n' ' '
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
# Driving one cell
# ---------------------------------------------------------------------------

# Cell $1's receipts, off the device and into $2.
#
# Matched by the cell's own name rather than swept wholesale, because a receipt is that
# cell's evidence and one carrying another cell's name is not it — that is what makes a
# receipt prove the launch argument arrived, which the generated flows' own comment relies
# on. Failure here is silent on purpose: this step collects, and what decides is the gate,
# which counts what arrived and names every cell that brought nothing.
pull_receipts()
{
    if [ "$PLATFORM" = ios ]
    then
        # Looked up again for THIS cell, and never kept. clearState on iOS does not empty
        # the data container in place, it RECREATES it under a new uuid, so a path read
        # once before the loop names a directory the first flow's launch already replaced.
        # One run of these fourteen flows on a Mac on 2026-09-03 had three container ids
        # (FDE707DA… before it, 961ED257… during, 3D0787B5… after) and the receipt was in
        # the third, while the run pulled from the first and reported 0 of 14
        # (docs/IMPLEMENTATION-PITFALLS.md P23).
        CONTAINER=$(xcrun simctl get_app_container "$TARGET" "$APP_ID" data 2> /dev/null || true)
        if [ -z "$CONTAINER" ]
        then
            # Loud, unlike the copy below. No container after a flow means the app is not
            # on the device — "it was never launched", not "it ran and wrote nothing" — and
            # a lookup that shrugged here would hand the reader the second diagnosis for
            # the first fault (P6, P7).
            fail "$1 — $APP_ID has no data container on $TARGET; the app is not on the device, so no receipt could be pulled"
            return 1
        fi
        SOURCE=$CONTAINER/Documents/receipts
    else
        # The external files directory, which is the one an `adb pull` reaches without root
        # and the one examples/android-compose/README.md names. It needs no lookup and must
        # not grow one: the path is fixed by the platform and `pm clear` empties it in
        # place, so every cell reads the same directory. Do not symmetrise this branch with
        # the iOS one above — the recreation worked around there is an iOS behaviour with
        # no Android half.
        #
        # Cleared before the pull: adb replaces nothing it does not find, so a cell whose
        # wipe left no receipts directory at all would otherwise be judged against the cell
        # before it.
        rm -rf "$WORK/receipts"
        adb -s "$TARGET" pull "/sdcard/Android/data/$APP_ID/files/receipts" "$WORK" \
            > /dev/null 2>&1 || true
        SOURCE=$WORK/receipts
    fi
    cp "$SOURCE"/receipt-"$1"-*.json "$2"/ 2> /dev/null || true
}

# One flow, then the pull, before the next flow's launch can wipe what it wrote. Answers
# with the flow's own exit status. $FLOW_RUNNER stands in for both halves when set.
drive_flow()
{
    if [ -n "$FLOW_RUNNER" ]
    then
        "$FLOW_RUNNER" "$1" "$2" "$3"
        return $?
    fi

    FLOW_OUTCOME=0
    maestro --device "$TARGET" test "$2" \
        --format junit \
        --output "$3/$1.xml" \
        -e APP_ID="$APP_ID" \
        > "$3/maestro-$1.log" 2>&1 || FLOW_OUTCOME=1
    pull_receipts "$1" "$3" || true
    return "$FLOW_OUTCOME"
}

# One line per cell carrying both facts. The verdict stays the receipt — the reason the
# earlier run chose it stands, since a skipped flow is a passed flow to an exit code — and
# the flow's status rides beside it so a cell that asserted everything and left nothing is
# reported as exactly that rather than as a bare missing receipt.
report_cell()
{
    if ls "$3"/receipt-"$1"-*.json > /dev/null 2>&1
    then
        RECEIPTS_PULLED=$((RECEIPTS_PULLED + 1))
        if [ "$2" -eq 0 ]
        then
            pass "$1 — flow passed, receipt pulled"
        else
            pass "$1 — receipt pulled, but the flow itself failed; see $3/maestro-$1.log"
        fi
        return 0
    fi

    if [ "$2" -eq 0 ]
    then
        fail "$1 — flow passed and left NO receipt: it never reached its last step, or wrote another cell's name"
    else
        fail "$1 — flow failed and left no receipt; see $3/maestro-$1.log"
    fi
    return 1
}

# Section 3's loop: every flow driven and pulled on its own, receipts into $1.
#
# Answers non-zero when any flow failed. The receipts are judged by the gate afterwards and
# not here, so that one loop cannot both collect the evidence and decide about it.
collect_cells()
{
    CELLS_RUN=0
    FLOWS_PASSED=0
    RECEIPTS_PULLED=0
    COLLECT_STATUS=0

    for FLOW in $FLOW_FILES
    do
        CELL=$(basename "$FLOW" .yaml)
        CELLS_RUN=$((CELLS_RUN + 1))
        FLOW_STATE=0
        drive_flow "$CELL" "$FLOW" "$1" || FLOW_STATE=1
        if [ "$FLOW_STATE" -eq 0 ]
        then
            FLOWS_PASSED=$((FLOWS_PASSED + 1))
        else
            COLLECT_STATUS=1
        fi
        report_cell "$CELL" "$FLOW_STATE" "$1" || true
    done

    printf '      %s flows run, %s passed, %s receipts pulled\n' \
        "$CELLS_RUN" "$FLOWS_PASSED" "$RECEIPTS_PULLED"
    return "$COLLECT_STATUS"
}

# ---------------------------------------------------------------------------
# --probe: the gate and the per-flow pull, driven against fixtures
# ---------------------------------------------------------------------------
# Runs on any host, device or no device, because what it tests is every part of this script
# that decides anything: the gate, the order the receipts are collected in, and the lookup
# that says where to collect them from. The three that matter are a receipt taken away
# turning a pass into a failure, a wipe before every flow costing the run everything if the
# pull waits until the end, and a container that MOVES costing it everything if the path
# was read before the loop.

# The fake device: one launch, one NEW container.
#
# `launchApp: clearState: true` on iOS does not empty the app's data container in place, it
# recreates it under a fresh uuid — so this fixture gives every launch a directory the
# launch before it never used and deletes the old one, which is the shape a path read once
# before the loop cannot survive. $PROBE_POINTER names the container a lookup would find
# right now, and is the whole of what the fake `xcrun` below knows.
#
# Every cell writes its receipt and exits 0; the one named in $PROBE_SILENT_CELL launches —
# and so moves the container — and then exits 1 without writing, which is the flow that
# failed before its last step, the shape that left the Mac's tree empty.
write_probe_device()
{
    cat > "$PROBE_LAUNCH" <<'LAUNCH'
#!/bin/sh
set -eu
SERIAL=$(( $(cat "$PROBE_SERIAL") + 1 ))
printf '%s' "$SERIAL" > "$PROBE_SERIAL"
rm -rf "$PROBE_CONTAINERS"
CONTAINER=$PROBE_CONTAINERS/container-$SERIAL
mkdir -p "$CONTAINER/Documents/receipts"
printf '%s' "$CONTAINER" > "$PROBE_POINTER"
if [ "$1" = "$PROBE_SILENT_CELL" ]
then
    exit 1
fi
printf '{"cell": "%s"}\n' "$1" > "$CONTAINER/Documents/receipts/receipt-$1-1756800000000.json"
LAUNCH
    chmod +x "$PROBE_LAUNCH"
}

# A stand-in for `xcrun simctl get_app_container`, answering with the container the fixture
# made last — and non-zero with nothing on stdout, as the real one does, when the app has
# no container at all.
write_probe_xcrun()
{
    mkdir -p "$WORK/bin"
    cat > "$WORK/bin/xcrun" <<'XCRUN'
#!/bin/sh
set -eu
if [ "$1" != simctl ] || [ "$2" != get_app_container ]
then
    exit 1
fi
if [ ! -s "$PROBE_POINTER" ]
then
    exit 1
fi
cat "$PROBE_POINTER"
XCRUN
    chmod +x "$WORK/bin/xcrun"
}

# The fixture device before any launch: no containers, and no container path to find.
probe_device_reset()
{
    rm -rf "$PROBE_CONTAINERS"
    printf '0' > "$PROBE_SERIAL"
    : > "$PROBE_POINTER"
}

# A stand-in for maestro AND the pull together, which is what --flow-runner replaces. It
# launches the fixture device and copies out of the container that launch made, which is
# the per-flow lookup written as a fixture.
write_fixture_runner()
{
    cat > "$WORK/fixture-runner.sh" <<'RUNNER'
#!/bin/sh
set -eu
FLOW_STATE=0
"$PROBE_LAUNCH" "$1" || FLOW_STATE=1
cp "$(cat "$PROBE_POINTER")"/Documents/receipts/receipt-"$1"-*.json "$3"/ 2> /dev/null || true
exit "$FLOW_STATE"
RUNNER
    chmod +x "$WORK/fixture-runner.sh"
}

# The receipts a full run of the fixture leaves under the two collection orders. $1 is
# where the per-flow pull puts them; $2 is where one pull after the last flow would.
run_fixture()
{
    mkdir -p "$1" "$2"
    probe_device_reset
    write_fixture_runner
    FLOW_RUNNER=$WORK/fixture-runner.sh
    collect_cells "$1" > "$WORK/collect.log" 2>&1 || true
    FLOW_RUNNER=''

    # The old spelling, and nothing more than it: whatever is on the device once the last
    # flow has run. Everything earlier went with the containers that flow's launch replaced.
    cp "$(cat "$PROBE_POINTER")"/Documents/receipts/receipt-*.json "$2"/ 2> /dev/null || true
}

# How many receipts landed in $1. Counted over a glob rather than a listing, so a name
# nothing expected is still one file and not two.
receipt_count()
{
    COUNT=0
    for RECEIPT in "$1"/receipt-*.json
    do
        if [ -f "$RECEIPT" ]
        then
            COUNT=$((COUNT + 1))
        fi
    done
    printf '%s' "$COUNT"
}

probe_gate_cases()
{
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
}

# The case this script was rewritten for. Same fixture, same fourteen flows, two collection
# orders: the per-flow pull keeps every receipt but the one no flow wrote, and one pull
# after the last flow keeps none of them at all.
probe_pull_case()
{
    run_fixture "$WORK/per-flow" "$WORK/end-of-run"

    if [ "$(receipt_count "$WORK/per-flow")" -eq "$WROTE" ]
    then
        pass "a pull after every flow keeps all $WROTE receipts the flows wrote"
    else
        fail "a pull after every flow kept $(receipt_count "$WORK/per-flow") receipts, not $WROTE"
        PROBE_STATUS=1
    fi

    if [ "$(receipt_count "$WORK/end-of-run")" -eq 0 ]
    then
        pass 'one pull after the last flow keeps none of them — the 2026-09-03 tree'
    else
        fail "one pull after the last flow kept $(receipt_count "$WORK/end-of-run") receipts; the fixture models no wipe"
        PROBE_STATUS=1
    fi

    # Both orders fail the gate here, and they have to fail it differently: the per-flow
    # pull names the one cell that wrote nothing, the end-of-run pull names all fourteen.
    receipt_gate "$WORK/per-flow" "$CASES" > "$WORK/per-flow.log" 2>&1 || true
    receipt_gate "$WORK/end-of-run" "$CASES" > "$WORK/end-of-run.log" 2>&1 || true
    if grep -q "cells with no receipt: $PROBE_SILENT_CELL\$" "$WORK/per-flow.log" \
        && grep -q "0 of $EXPECTED_FLOOR cells left a receipt" "$WORK/end-of-run.log"
    then
        pass "the gate blames one cell after the per-flow pull and all $EXPECTED_FLOOR after the end-of-run pull"
    else
        fail 'the two collection orders did not reach the gate differently'
        sed 's/^/      per-flow: /' "$WORK/per-flow.log"
        sed 's/^/      end-of-run: /' "$WORK/end-of-run.log"
        PROBE_STATUS=1
    fi
}

# The case this change was made for: the container the receipts are in MOVES under the run,
# so WHERE the pull looks it up decides whether it finds anything. Same fixture and the same
# fourteen flows as above, but driving the real pull_receipts against a fake `xcrun`, and
# two lookups — the one it does now, after every flow, against the one this change replaced,
# read once before the loop.
probe_container_case()
{
    probe_device_reset
    write_probe_xcrun
    PATH=$WORK/bin:$PATH
    PLATFORM=ios
    TARGET='(fixture simulator)'
    mkdir -p "$WORK/fresh-lookup" "$WORK/stale-lookup" "$WORK/no-container"

    # The container a run finds before its first flow — section 3's "was it ever installed"
    # question, whose answer the old spelling kept and pulled from fourteen times.
    "$PROBE_LAUNCH" warm-up || true
    STALE=$(cat "$PROBE_POINTER")

    for FLOW in $FLOW_FILES
    do
        CELL=$(basename "$FLOW" .yaml)
        "$PROBE_LAUNCH" "$CELL" || true
        pull_receipts "$CELL" "$WORK/fresh-lookup" > /dev/null 2>&1 || true
        cp "$STALE"/Documents/receipts/receipt-"$CELL"-*.json "$WORK/stale-lookup"/ \
            2> /dev/null || true
    done

    if [ "$(receipt_count "$WORK/fresh-lookup")" -eq "$WROTE" ]
    then
        pass "a container looked up after every flow keeps all $WROTE receipts"
    else
        fail "a container looked up after every flow kept $(receipt_count "$WORK/fresh-lookup") receipts, not $WROTE"
        PROBE_STATUS=1
    fi

    if [ "$(receipt_count "$WORK/stale-lookup")" -eq 0 ]
    then
        pass 'a container looked up once before the loop keeps none of them — the 2026-09-03 tree'
    else
        fail "a container looked up once before the loop kept $(receipt_count "$WORK/stale-lookup") receipts; the fixture models no recreation"
        PROBE_STATUS=1
    fi

    # And a lookup that finds no container at all says so, out loud. It is the one part of
    # the pull that is not allowed to fail quietly: an absent container means the app is not
    # on the device, and leaving the gate to report that as a cell which wrote nothing is
    # the wrong diagnosis for the fault (P6, P7).
    probe_device_reset
    if pull_receipts u1 "$WORK/no-container" > "$WORK/no-container.log" 2>&1
    then
        fail 'a pull with no container on the device succeeded instead of refusing'
        PROBE_STATUS=1
    elif grep -q 'no data container' "$WORK/no-container.log"
    then
        pass 'a pull with no container on the device fails, naming the app and not the flow'
    else
        fail 'a pull with no container failed without saying the container was missing'
        sed 's/^/      /' "$WORK/no-container.log"
        PROBE_STATUS=1
    fi
}

probe()
{
    printf 'SPFN Mobile — the example cell receipt gate and per-flow pull, probed\n\n'
    PROBE_STATUS=0

    # One fixture device for both collection cases, and the one cell that leaves it nothing:
    # the last flow, which launches and then fails before its last step.
    PROBE_CONTAINERS=$WORK/containers
    PROBE_POINTER=$WORK/container-now
    PROBE_SERIAL=$WORK/container-serial
    PROBE_LAUNCH=$WORK/launch.sh
    PROBE_SILENT_CELL=$(basename "$(printf '%s' "$FLOW_FILES" | awk '{print $NF}')" .yaml)
    WROTE=$(( $(printf '%s' "$FLOW_FILES" | wc -w) - 1 ))
    export PROBE_CONTAINERS PROBE_POINTER PROBE_SERIAL PROBE_LAUNCH PROBE_SILENT_CELL
    write_probe_device

    probe_gate_cases
    probe_pull_case
    probe_container_case

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
PLATFORM=${1-}
TARGET=''
FLOW_RUNNER=''

if [ "$PLATFORM" = '--probe' ]
then
    require python3 'the case table is read with it; see expected_cells'
    FLOW_FILES=$(flow_files)
    probe
fi

case "$PLATFORM" in
    ios | android) shift ;;
    *)
        printf 'usage: sh examples/ui-spec/run-cells.sh ios|android [--device <id>] [--flow-runner <cmd>]\n' >&2
        printf '       sh examples/ui-spec/run-cells.sh --probe\n' >&2
        printf 'the platform is named rather than detected: a guessed platform is a guessed result.\n' >&2
        exit 1
        ;;
esac

while [ $# -gt 0 ]
do
    case "$1" in
        --device)
            TARGET=${2-}
            [ -n "$TARGET" ] || die '--device needs a simulator udid or an adb serial.'
            shift 2
            ;;
        --flow-runner)
            FLOW_RUNNER=${2-}
            [ -n "$FLOW_RUNNER" ] || die '--flow-runner needs a command; its contract is in the header of this file.'
            shift 2
            ;;
        *)
            die "run-cells.sh does not understand: $1"
            ;;
    esac
done

printf 'SPFN Mobile — the example cell run\n'
printf 'root: %s\n' "$ROOT"
printf 'platform: %s\n\n' "$PLATFORM"

require python3 'the case table is read with it; see expected_cells'
FLOW_FILES=$(flow_files)

# ---------------------------------------------------------------------------
printf '1. the target\n'
# ---------------------------------------------------------------------------
# One target or none. Two attached and no choice made is a result nobody can attribute to
# a device, which is the rule tools/harness/run-harness.sh states at greater length.
if [ -n "$FLOW_RUNNER" ]
then
    pass "flows driven by $FLOW_RUNNER, not maestro — this run is not a device result"
    TARGET='(none)'
elif [ "$PLATFORM" = ios ]
then
    require maestro 'install it with: brew install mobile-dev-inc/tap/maestro'
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
    require maestro 'install it with: brew install mobile-dev-inc/tap/maestro'
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
WARM_UP_LOG="$RUN_DIRECTORY/warm-up.log"
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

if [ -n "$FLOW_RUNNER" ]
then
    pass 'skipped: there is no app to warm up when maestro is not driving'
elif maestro --device "$TARGET" test "$WORK/warm-up.yaml" -e APP_ID="$APP_ID" \
    > "$WARM_UP_LOG" 2>&1
then
    pass 'the app launched and drew its root readout'
else
    fail 'the app never drew a root readout, so no cell can be driven'
    fail "is $APP_ID installed? this script installs nothing — see this file's header"
    # Three lines, not the whole trace. A maestro failure prints its own stack, and the
    # hundred lines of that buried the two lines above — which are the ones that say what
    # to do — the last time this fired.
    sed -n '1,3p' "$WARM_UP_LOG" | sed 's/^/      /'
    printf '      full log: %s\n' "$WARM_UP_LOG"
    exit 1
fi

# ---------------------------------------------------------------------------
printf '\n3. the flows, one at a time\n'
# ---------------------------------------------------------------------------
# Each flow driven and then pulled, because the next flow's launch wipes the store this
# one's receipt is in and, on iOS, moves it — see this file's header for the runs that
# measured both.
if [ "$PLATFORM" = ios ] && [ -z "$FLOW_RUNNER" ]
then
    # Asked once, and its ANSWER thrown away. This is the "is the app on this simulator at
    # all" refusal, so that a run against a device with no app fails here rather than
    # fourteen flows and one empty gate later. The path is not kept because every launch
    # replaces it: pull_receipts asks again after each flow.
    if [ -z "$(xcrun simctl get_app_container "$TARGET" "$APP_ID" data 2> /dev/null || true)" ]
    then
        fail "$APP_ID has no data container on $TARGET, so it was never installed or never ran"
        exit 1
    fi
fi

FLOW_STATUS=0
collect_cells "$RUN_DIRECTORY" || FLOW_STATUS=1

# ---------------------------------------------------------------------------
printf '\n4. every cell really ran\n'
# ---------------------------------------------------------------------------
# Unchanged by the rewrite above: the cells the table claims a device runner for, against
# the receipts that reached this run's own directory, which is where they stay. Unlike the
# harness's, these receipts outlive the run, because the case table is a claim about two
# platforms and the receipts are what a reader checks it against.
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
printf 'RESULT: FAIL\n'
exit 1
