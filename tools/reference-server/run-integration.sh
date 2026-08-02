#!/bin/sh
# SPFN Mobile — the two-platform integration run.
#
#   sh tools/reference-server/run-integration.sh
#
# Starts the local reference server, drives the Swift SDK against it from one process and
# the Android SDK against it from another, and stops the server. One command, one exit
# code, and no way to end green without both suites having really run.
#
# That last part is the whole reason this script is not three commands in a README. A
# skipped XCTest is reported as a passing XCTest, and an integration suite that quietly
# skips is the most expensive kind of green there is: it says the round trip works when
# nothing was ever sent. So each case writes a receipt file, and this script fails unless
# every receipt it expects is on disk afterwards.
#
# Requires: a JDK (through ./gradlew), swift, and curl for the readiness probe.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

MAIN_CLASS=xyz.superfunction.spfn.reference.SpfnReferenceMainKt
LAUNCH_INFO=tools/reference-server/build/reference-server-launch.txt

# Every case both suites are required to have run. Read as "platform-case"; the letters
# are the matrix in the change set brief: round trip, expiry, revocation, replay, and
# cancellation or timeout.
EXPECTED_RECEIPTS='swift-a swift-b swift-c swift-d swift-e kotlin-a kotlin-b kotlin-c kotlin-d kotlin-e'

WORK=$(mktemp -d)
RECEIPTS="$WORK/receipts"
LAUNCH_FILE="$WORK/reference-server.json"
SERVER_LOG="$WORK/reference-server.log"
SWIFT_LOG="$WORK/swift-integration.log"
KOTLIN_LOG="$WORK/kotlin-integration.log"
SERVER_PID=''

mkdir -p "$RECEIPTS"

# Stops the server whatever happens next, including a failure between here and the end.
# The server also watches this script's PID and the JVM installs its own shutdown hook,
# so a run killed outright still does not leave a process holding the port.
cleanup()
{
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2> /dev/null
    then
        kill "$SERVER_PID" 2> /dev/null || true
        wait "$SERVER_PID" 2> /dev/null || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

fail()
{
    printf 'FAIL  %s\n' "$1"
}

pass()
{
    printf 'ok    %s\n' "$1"
}

require()
{
    if ! command -v "$1" > /dev/null 2>&1
    then
        printf 'run-integration.sh needs %s and cannot find it.\n' "$1" >&2
        exit 1
    fi
}

require curl
require swift

printf 'SPFN Mobile — two-platform integration run\n'
printf 'root: %s\n\n' "$ROOT"

# ---------------------------------------------------------------------------
printf '1. starting the reference server\n'
# ---------------------------------------------------------------------------
./gradlew --console=plain -q :reference-server:spfnReferenceServerLaunchInfo

if [ ! -f "$LAUNCH_INFO" ]
then
    fail "the launch classpath was not written to $LAUNCH_INFO"
    exit 1
fi

# A plain java process rather than a Gradle JavaExec: this script needs the server's own
# PID so the trap above can stop it, and a forked JavaExec outlives the Gradle client that
# started it. --parent-pid gives the server a second way to notice this run is over.
java -cp "$(cat "$LAUNCH_INFO")" "$MAIN_CLASS" \
    --port-file "$LAUNCH_FILE" \
    --parent-pid "$$" > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Polled, never slept on for a fixed interval: a fixed sleep is either too short on a cold
# machine or wasted on a warm one, and the first kind fails for a reason nobody can see.
ATTEMPT=0
while [ "$ATTEMPT" -lt 300 ]
do
    if [ -f "$LAUNCH_FILE" ]
    then
        break
    fi
    if ! kill -0 "$SERVER_PID" 2> /dev/null
    then
        fail 'the reference server exited before it was ready'
        sed 's/^/      /' "$SERVER_LOG"
        exit 1
    fi
    ATTEMPT=$((ATTEMPT + 1))
    sleep 0.1
done

if [ ! -f "$LAUNCH_FILE" ]
then
    fail 'the reference server did not report a port within 30 seconds'
    sed 's/^/      /' "$SERVER_LOG"
    exit 1
fi

BASE_URL=$(sed -n 's/.*"baseUrl":"\([^"]*\)".*/\1/p' "$LAUNCH_FILE")
# Read into a variable and never printed: the control surface can revoke keys and drop
# sessions, and a token echoed here would end up in every terminal scrollback and CI log.
CONTROL_TOKEN=$(sed -n 's/.*"controlToken":"\([^"]*\)".*/\1/p' "$LAUNCH_FILE")

if [ -z "$BASE_URL" ] || [ -z "$CONTROL_TOKEN" ]
then
    fail 'the reference server wrote a launch file this script cannot read'
    exit 1
fi

ATTEMPT=0
READY=0
while [ "$ATTEMPT" -lt 300 ]
do
    if curl -sS -o /dev/null -f "$BASE_URL/control/health" 2> /dev/null
    then
        READY=1
        break
    fi
    ATTEMPT=$((ATTEMPT + 1))
    sleep 0.1
done

if [ "$READY" -ne 1 ]
then
    fail "the reference server never answered $BASE_URL/control/health"
    sed 's/^/      /' "$SERVER_LOG"
    exit 1
fi

pass "reference server ready at $BASE_URL (pid $SERVER_PID)"

# ---------------------------------------------------------------------------
printf '\n2. Swift integration suite\n'
# ---------------------------------------------------------------------------
# No pipe: a pipeline reports the exit status of its last command, and `| tee` would turn
# every failing suite into a passing run.
set +e
SPFN_REFERENCE_SERVER_URL="$BASE_URL" \
    SPFN_REFERENCE_CONTROL_TOKEN="$CONTROL_TOKEN" \
    SPFN_INTEGRATION_RECEIPTS="$RECEIPTS" \
    swift test --filter SPFNIntegrationTests > "$SWIFT_LOG" 2>&1
SWIFT_STATUS=$?
set -e

if [ "$SWIFT_STATUS" -eq 0 ]
then
    pass 'swift test --filter SPFNIntegrationTests'
else
    fail "swift test --filter SPFNIntegrationTests exited $SWIFT_STATUS"
    tail -40 "$SWIFT_LOG" | sed 's/^/      /'
fi

# ---------------------------------------------------------------------------
printf '\n3. Android integration suite\n'
# ---------------------------------------------------------------------------
set +e
./gradlew --console=plain :reference-server:spfnIntegrationTest \
    "-Pspfn.integrationReceipts=$RECEIPTS" > "$KOTLIN_LOG" 2>&1
KOTLIN_STATUS=$?
set -e

if [ "$KOTLIN_STATUS" -eq 0 ]
then
    pass './gradlew :reference-server:spfnIntegrationTest'
else
    fail "./gradlew :reference-server:spfnIntegrationTest exited $KOTLIN_STATUS"
    tail -40 "$KOTLIN_LOG" | sed 's/^/      /'
fi

# ---------------------------------------------------------------------------
printf '\n4. every case really ran\n'
# ---------------------------------------------------------------------------
MISSING=''
for receipt in $EXPECTED_RECEIPTS
do
    if [ ! -f "$RECEIPTS/$receipt" ]
    then
        MISSING="$MISSING $receipt"
    fi
done

RECEIPT_STATUS=0
if [ -z "$MISSING" ]
then
    pass 'all 10 integration cases recorded a receipt'
else
    RECEIPT_STATUS=1
    fail "integration cases that did not run:$MISSING"
    fail 'a suite that skips is reported as a suite that passes, so this is a failure'
fi

# ---------------------------------------------------------------------------
printf '\n5. no server process left behind\n'
# ---------------------------------------------------------------------------
kill "$SERVER_PID" 2> /dev/null || true
wait "$SERVER_PID" 2> /dev/null || true
SERVER_PID=''

ORPHAN_STATUS=0
if command -v pgrep > /dev/null 2>&1
then
    ATTEMPT=0
    while [ "$ATTEMPT" -lt 50 ] && pgrep -f "$MAIN_CLASS" > /dev/null 2>&1
    do
        ATTEMPT=$((ATTEMPT + 1))
        sleep 0.1
    done
    if pgrep -f "$MAIN_CLASS" > /dev/null 2>&1
    then
        ORPHAN_STATUS=1
        fail 'a reference server process is still running'
    else
        pass 'no reference server process survived the run'
    fi
else
    printf '  --    pgrep is unavailable, so the orphan check was not run\n'
fi

# ---------------------------------------------------------------------------
printf '\n'
if [ "$SWIFT_STATUS" -eq 0 ] && [ "$KOTLIN_STATUS" -eq 0 ] &&
    [ "$RECEIPT_STATUS" -eq 0 ] && [ "$ORPHAN_STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
