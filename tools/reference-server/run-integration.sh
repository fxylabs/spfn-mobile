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
# ---------------------------------------------------------------------------
# Against a server this script did not start
# ---------------------------------------------------------------------------
#
#   SPFN_INTEGRATION_TARGET_URL=http://127.0.0.1:8791 \
#   SPFN_INTEGRATION_CONTROL_TOKEN=... \
#       sh tools/reference-server/run-integration.sh
#
#   SPFN_INTEGRATION_LAUNCH_FILE=/path/to/launch.json \
#       sh tools/reference-server/run-integration.sh
#
# Same ten cases, same receipts, same exit rules — against whatever is on the other end.
# The point is the canonical implementation: everything this repository proves today it
# proves against its own reference server, which is two ends built from one reading of the
# contract. The launch file is the shape SpfnReferenceMain writes and the SPFN primitives
# mobile contract surface writes too: an object with `baseUrl` and `controlToken`.
#
# In this mode the script starts nothing and stops nothing. It probes the target for
# readiness, checks the control token before running anything, and checks at the end that
# the target is still up — which is how "no server left behind" is stated when the server
# was never this run's to leave. A named target that cannot be used is a failure and never
# a quiet fall back to the local server: a run that checked the local server while
# reporting the external one would be the most expensive kind of green there is.
#
# Requires: a JDK (through ./gradlew), swift, and curl for the readiness probe.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

MAIN_CLASS=xyz.superfunction.spfn.reference.SpfnReferenceMainKt
LAUNCH_INFO=tools/reference-server/build/reference-server-launch.txt
TOKEN_HEADER=x-spfn-reference-control

# Every case both suites are required to have run. Read as "platform-case"; the letters
# are the matrix in the change set brief: round trip, expiry, revocation, replay,
# cancellation or timeout — and, where the target carries the REST surface, the
# enrollment-rotation end-to-end (case f).
EXPECTED_RECEIPTS='swift-a swift-b swift-c swift-d swift-e kotlin-a kotlin-b kotlin-c kotlin-d kotlin-e'

# Whether the target implements the contract 0.3.0 REST operations (/_auth). The local
# reference server always does. An external target usually does NOT — the primitives
# dev server carries the three dev operations only — so case f is out of scope there
# unless the caller states otherwise by exporting SPFN_INTEGRATION_REST_OPS=1.
REST_OPS=${SPFN_INTEGRATION_REST_OPS-}

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
#
# SERVER_PID is only ever set for a server this script started, so a run against an
# external target has nothing here to kill.
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

# Polls a base URL's readiness route. Never a fixed sleep: a fixed sleep is either too
# short on a cold machine or wasted on a warm one, and the first kind fails invisibly.
wait_for_health()
{
    ATTEMPT=0
    while [ "$ATTEMPT" -lt 300 ]
    do
        if curl -sS -o /dev/null -f "$1/control/health" 2> /dev/null
        then
            return 0
        fi
        ATTEMPT=$((ATTEMPT + 1))
        sleep 0.1
    done
    return 1
}

require curl
require swift

# ---------------------------------------------------------------------------
# Which server, and is it this script's to start
# ---------------------------------------------------------------------------
TARGET_URL=${SPFN_INTEGRATION_TARGET_URL-}
TARGET_TOKEN=${SPFN_INTEGRATION_CONTROL_TOKEN-}
TARGET_LAUNCH_FILE=${SPFN_INTEGRATION_LAUNCH_FILE-}

MODE=local
if [ -n "$TARGET_URL" ] || [ -n "$TARGET_LAUNCH_FILE" ]
then
    MODE=external
fi

printf 'SPFN Mobile — two-platform integration run\n'
printf 'root: %s\n' "$ROOT"
printf 'mode: %s\n\n' "$MODE"

BASE_URL=''
CONTROL_TOKEN=''
KOTLIN_LAUNCH_FILE=''

if [ "$MODE" = external ]
then
    # -----------------------------------------------------------------------
    printf '1. the target server\n'
    # -----------------------------------------------------------------------
    if [ -n "$TARGET_LAUNCH_FILE" ]
    then
        if [ ! -f "$TARGET_LAUNCH_FILE" ]
        then
            fail "SPFN_INTEGRATION_LAUNCH_FILE names $TARGET_LAUNCH_FILE, which is not a file"
            exit 1
        fi
        # Read into variables and never printed: the control surface can revoke keys and
        # drop sessions, and a token echoed here would end up in every terminal scrollback.
        if [ -z "$TARGET_URL" ]
        then
            TARGET_URL=$(sed -n 's/.*"baseUrl":"\([^"]*\)".*/\1/p' "$TARGET_LAUNCH_FILE")
        fi
        if [ -z "$TARGET_TOKEN" ]
        then
            TARGET_TOKEN=$(sed -n 's/.*"controlToken":"\([^"]*\)".*/\1/p' "$TARGET_LAUNCH_FILE")
        fi
        KOTLIN_LAUNCH_FILE=$TARGET_LAUNCH_FILE
    fi

    # `https://host/` and `https://host` name the same server, and only one of them turns
    # `/control/health` into `//control/health`, which is a different path and a 404 that
    # reads as an unreachable server.
    BASE_URL=$(printf '%s' "$TARGET_URL" | sed 's:/*$::')
    CONTROL_TOKEN=$TARGET_TOKEN

    if [ -z "$BASE_URL" ]
    then
        fail 'a target was named but no base URL could be read from it'
        exit 1
    fi

    case "$BASE_URL" in
        http://* | https://*) ;;
        *)
            fail "the target base URL must be absolute http(s), got '$BASE_URL'"
            exit 1
            ;;
    esac

    if [ -z "$CONTROL_TOKEN" ]
    then
        fail 'a target was named but no control token was'
        fail 'set SPFN_INTEGRATION_CONTROL_TOKEN or SPFN_INTEGRATION_LAUNCH_FILE'
        fail 'this run will not fall back to a local server: that would check the wrong one'
        exit 1
    fi

    # Checked here, where every way of naming a token has already been resolved into one
    # variable — an environment variable, a launch file, or a launch file overridden by an
    # environment variable. Checking it at the place each one is written instead is how a
    # rule ends up holding for one door and not the others.
    #
    # The set is narrow on purpose. The token is written into a header field value, and a
    # colon, a space or a line break there is not a bad token but a second header field,
    # which is a request nobody wrote. Every token this repository generates is hex.
    # `SpfnReferenceTarget.kt` and `SPFNIntegrationEnvironment.swift` enforce the same set,
    # because both suites can be run without this script. Change it in all three or in none.
    # Counted in bytes with `tr` rather than matched with a `case` glob. A negated bracket
    # expression is locale-dependent: under a UTF-8 locale `[!A-Za-z0-9._-]` reads a
    # multi-byte character as one character that some shells decline to call a non-match, so
    # a non-ASCII token walked straight through it. `LC_ALL=C tr` deletes the allowed bytes
    # and counts what is left, which cannot be talked out of noticing a byte.
    LEFTOVER_BYTES=$(printf '%s' "$CONTROL_TOKEN" | LC_ALL=C tr -d 'A-Za-z0-9._-' | wc -c | tr -d ' ')
    if [ "$LEFTOVER_BYTES" -ne 0 ]
    then
        fail 'the control token holds characters that cannot be carried in an HTTP header field value'
        fail 'allowed: A-Z a-z 0-9 . _ -'
        exit 1
    fi

    if ! wait_for_health "$BASE_URL"
    then
        fail "nothing answered $BASE_URL/control/health within 30 seconds"
        fail 'the target has to be running before this script is: it starts nothing here'
        exit 1
    fi

    # The token, checked before a suite runs rather than after five cases failed for a
    # reason that looks like a broken server. Sent through a curl config file so it is
    # never an argument, because arguments are readable by every process on the machine.
    CONTROL_CURL="$WORK/control-header.conf"
    printf 'header = "%s: %s"\n' "$TOKEN_HEADER" "$CONTROL_TOKEN" > "$CONTROL_CURL"
    if ! curl -sS -o /dev/null -f -K "$CONTROL_CURL" "$BASE_URL/control/stats" 2> /dev/null
    then
        fail "$BASE_URL refused the control token, so three of the five cases could not run"
        exit 1
    fi

    if [ -z "$KOTLIN_LAUNCH_FILE" ]
    then
        # The Android suite is given a file rather than a property, for the same reason as
        # the curl config above. The token is already known to be free of anything that
        # would need escaping on the way into this JSON: the charset check above ran first.
        KOTLIN_LAUNCH_FILE="$WORK/target.json"
        printf '{"baseUrl":"%s","controlToken":"%s"}' "$BASE_URL" "$CONTROL_TOKEN" > "$KOTLIN_LAUNCH_FILE"
    fi

    if [ "$REST_OPS" = "1" ]
    then
        printf '  --    the caller declared the target implements the REST operations; case f is expected\n'
    else
        printf '  --    external target: the REST operations (case f) are out of scope — the primitives\n'
        printf '  --    dev surface carries the three dev operations only. Export SPFN_INTEGRATION_REST_OPS=1\n'
        printf '  --    when the target really implements /_auth.\n'
    fi

    pass "target ready at $BASE_URL (started by something other than this script)"
else
    # -----------------------------------------------------------------------
    printf '1. starting the reference server\n'
    # -----------------------------------------------------------------------
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

    if ! wait_for_health "$BASE_URL"
    then
        fail "the reference server never answered $BASE_URL/control/health"
        sed 's/^/      /' "$SERVER_LOG"
        exit 1
    fi

    # The server this script starts is this repository's own, so the REST surface is
    # always present and case f is always expected.
    REST_OPS=1

    pass "reference server ready at $BASE_URL (pid $SERVER_PID)"
fi

if [ "$REST_OPS" = "1" ]
then
    EXPECTED_RECEIPTS="$EXPECTED_RECEIPTS swift-f kotlin-f"
fi

# ---------------------------------------------------------------------------
printf '\n2. Swift integration suite\n'
# ---------------------------------------------------------------------------
# No pipe: a pipeline reports the exit status of its last command, and `| tee` would turn
# every failing suite into a passing run.
set +e
SPFN_REFERENCE_SERVER_URL="$BASE_URL" \
    SPFN_REFERENCE_CONTROL_TOKEN="$CONTROL_TOKEN" \
    SPFN_INTEGRATION_RECEIPTS="$RECEIPTS" \
    SPFN_INTEGRATION_REST_OPS="$REST_OPS" \
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
# The target reaches the suite as a URL and a file path, never as the token itself: a
# Gradle property is a command-line argument, and arguments are readable by every process
# on the machine.
set +e
if [ "$MODE" = external ]
then
    ./gradlew --console=plain :reference-server:spfnIntegrationTest \
        "-Pspfn.integrationReceipts=$RECEIPTS" \
        "-Pspfn.integrationTargetUrl=$BASE_URL" \
        "-Pspfn.integrationRestOps=$REST_OPS" \
        "-Pspfn.integrationLaunchFile=$KOTLIN_LAUNCH_FILE" > "$KOTLIN_LOG" 2>&1
else
    ./gradlew --console=plain :reference-server:spfnIntegrationTest \
        "-Pspfn.integrationReceipts=$RECEIPTS" > "$KOTLIN_LOG" 2>&1
fi
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

EXPECTED_COUNT=$(printf '%s\n' $EXPECTED_RECEIPTS | grep -c .)
RECEIPT_STATUS=0
if [ -z "$MISSING" ]
then
    pass "all $EXPECTED_COUNT integration cases recorded a receipt"
else
    RECEIPT_STATUS=1
    fail "integration cases that did not run:$MISSING"
    fail 'a suite that skips is reported as a suite that passes, so this is a failure'
fi

# ---------------------------------------------------------------------------
printf '\n5. the server this run is responsible for\n'
# ---------------------------------------------------------------------------
ORPHAN_STATUS=0
if [ "$MODE" = external ]
then
    # Nothing was started, so nothing can be orphaned, and the pgrep sweep below must not
    # run: the target may well be a reference server of its own, and killing or even
    # reporting somebody else's process is this script reaching outside its own run.
    if curl -sS -o /dev/null -f "$BASE_URL/control/health" 2> /dev/null
    then
        pass 'the target is still up: this run neither started it nor stopped it'
    else
        ORPHAN_STATUS=1
        fail "$BASE_URL stopped answering during the run"
    fi
else
    kill "$SERVER_PID" 2> /dev/null || true
    wait "$SERVER_PID" 2> /dev/null || true
    SERVER_PID=''

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
