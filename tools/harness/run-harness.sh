#!/bin/sh
# SPFN Mobile — the run that drives the SDK through a screen.
#
#   sh tools/harness/run-harness.sh ios
#   sh tools/harness/run-harness.sh android
#
# Everything else this repository proves, it proves without an app. Hundreds of unit
# tests, a reference server, integration cases with receipts — and not one of them ever
# launches anything. That is fine for the proof algorithm and the state machine. It stops
# being fine the moment the question is whether a person can get through enrolment.
#
# So this script builds the harness app, installs it on an iOS simulator, an Android
# emulator or an Android device, and runs the Maestro flows against it. Ten flows, one per
# cell of the lifecycle's case table; tools/harness/README.md carries the table.
#
# A physical iPhone is not on that list and cannot be: maestro ships no driver for one.
# tools/harness/README.md carries the manual procedure that covers what a phone is for.
#
# ---------------------------------------------------------------------------
# What this script refuses to do
# ---------------------------------------------------------------------------
#
# A skipped flow is a passed flow as far as an exit code is concerned, so every flow that
# really runs writes a receipt and this script fails unless every receipt it expects is on
# disk afterwards. The same rule tools/reference-server/run-integration.sh has, for the
# same reason: a run that reported coverage it did not have would be the most expensive
# kind of green there is.
#
# Every missing prerequisite is an exit, never a substitution:
#
#   - maestro is not installed          -> print the install command, exit non-zero
#   - no target is running              -> print how to start one, exit non-zero
#   - two targets and no choice made    -> exit; a guessed target is a guessed result
#   - a physical iPhone                 -> exit; maestro ships no iOS device driver
#
# It also never prints a secret. The reference server's control token is read into a
# variable and never echoed, and the id token the flows use is a test-user name rather
# than a credential.
#
# Requires: maestro, and per platform xcodegen + xcodebuild + xcrun, or the Android SDK
# through ANDROID_HOME plus adb. A prober that is missing is a refusal too: an unrun check
# must never read as a passed one.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

FLOWS=tools/harness/flows
MAIN_CLASS=xyz.superfunction.spfn.reference.SpfnReferenceMainKt
LAUNCH_INFO=tools/reference-server/build/reference-server-launch.txt

IOS_BUNDLE_ID=xyz.superfunction.spfn.harness
ANDROID_APPLICATION_ID=xyz.superfunction.spfn.harness
ANDROID_APK=tools/harness/android/build/outputs/apk/debug/harness-android-debug.apk

# The user the reference server's test id_token names. Not a credential: that server's
# token grammar is `spfn-test-idtoken.<provider>.<user>.<nonce>` and it verifies nothing
# but the shape and the nonce. A real server would refuse it outright, which is correct.
TEST_USER=${SPFN_HARNESS_TEST_USER-harness-user}
PROVIDER=${SPFN_HARNESS_PROVIDER-google}

# The nine cells of the case table in tools/harness/README.md: three lifecycle states by
# three operations. Every target runs all nine — they need enrolment and rotation, which
# is exactly what tools/reference-server implements.
LIFECYCLE_CASES='c1-enroll-from-unenrolled
c2-rotate-from-unenrolled
c3-resume-from-unenrolled
c4-enroll-while-enrolled
c5-rotate-while-enrolled
c6-resume-while-enrolled
c7-enroll-while-rotation-pending
c8-rotate-while-rotation-pending
c9-resume-while-rotation-pending'

# The revocation sequence, which is not a lifecycle operation and not a cell of that
# table. It needs `auth.keys.revoke` and `auth.keys.list`, and tools/reference-server
# implements NEITHER — its own header says so: of the four operations under /_auth it
# carries the two an SDK flow uses, native enrolment and rotation, and refuses the rest
# with CONTRACT_UNSUPPORTED. The first full run found this by failing on it.
#
# So this case is out of scope against the local server, announced rather than skipped
# quietly, and in scope against a target the caller says implements it. That is the shape
# tools/reference-server/run-integration.sh already uses for its case f, for the same
# reason: what a target implements is a fact about the target, not a preference.
REVOCATION_CASES='c10-revoke-then-proven-call'
REVOCATION_OPS=${SPFN_HARNESS_REVOCATION_OPS-}

WORK=$(mktemp -d)
RECEIPTS="$WORK/receipts"
LAUNCH_FILE="$WORK/reference-server.json"
SERVER_LOG="$WORK/reference-server.log"
SERVER_PID=''

# Both are read by cleanup, which is armed before either is decided. Under `set -u` an
# unset one would turn any early exit into an error about the cleanup rather than about
# whatever actually went wrong.
TARGET=''
REVERSED_PORT=''

mkdir -p "$RECEIPTS"

cleanup()
{
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2> /dev/null
    then
        kill "$SERVER_PID" 2> /dev/null || true
        wait "$SERVER_PID" 2> /dev/null || true
    fi
    # A reverse route outlives this script and the port it names is reused by the next
    # run, so leaving one behind points a later run's app at a server that is gone.
    if [ -n "$REVERSED_PORT" ]
    then
        adb -s "$TARGET" reverse --remove "tcp:$REVERSED_PORT" > /dev/null 2>&1 || true
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
        printf 'run-harness.sh needs %s and cannot find it.\n' "$1" >&2
        if [ $# -gt 1 ]
        then
            printf '  %s\n' "$2" >&2
        fi
        exit 1
    fi
}

# Whether $1 names a simulator, reading `xcrun simctl list devices -j` from stdin.
#
# The membership test is whole-line and literal on purpose. A udid that is a prefix of
# another one must not match, and `grep` without -x would let it — which would hand a
# physical iPhone the run that the caller above refuses it.
names_a_simulator()
{
    sed -n 's/.*"udid" : "\([^"]*\)".*/\1/p' | grep -qxF "$1"
}

# Whether the JUnit report says this case ran and did not fail.
#
# Written with python rather than sed because the two answers this has to tell apart —
# "the report has no such case" and "the report has it with a failure" — are one grep away
# from each other, and getting them confused would hand a receipt to a case that failed.
# A parse that cannot run is a refusal too, so an unreadable report exits non-zero.
case_status()
{
    python3 - "$1" "$2" <<'PARSE'
import sys
import xml.etree.ElementTree as ET

report, name = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(report).getroot()
except Exception:
    sys.exit(2)

for case in root.iter("testcase"):
    if case.get("name") != name:
        continue
    bad = list(case.iter("failure")) + list(case.iter("error")) + list(case.iter("skipped"))
    sys.exit(1 if bad else 0)
sys.exit(1)
PARSE
}

# Polls the server's readiness route. Never a fixed sleep: a fixed sleep is either too
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

PLATFORM=${1-}
case "$PLATFORM" in
    ios | android) ;;
    *)
        printf 'usage: sh tools/harness/run-harness.sh ios|android\n' >&2
        printf 'the platform is named rather than detected: a guessed platform is a guessed result.\n' >&2
        exit 1
        ;;
esac

printf 'SPFN Mobile — the harness run\n'
printf 'root: %s\n' "$ROOT"
printf 'platform: %s\n\n' "$PLATFORM"

require curl
require python3 'it reads the run report; see case_status above for why sed will not do'
require maestro 'install it with: brew install mobile-dev-inc/tap/maestro'

# ---------------------------------------------------------------------------
printf '1. the target\n'
# ---------------------------------------------------------------------------
TARGET=${SPFN_HARNESS_TARGET-}
IS_PHYSICAL_DEVICE=0

if [ "$PLATFORM" = ios ]
then
    require xcodegen 'install it with: brew install xcodegen'
    require xcrun

    if [ -z "$TARGET" ]
    then
        # Every simulator currently booted, one UDID per line. A run picks one only when
        # there is exactly one: two booted simulators and no choice made is a result
        # nobody can attribute to a device.
        BOOTED=$(xcrun simctl list devices booted -j 2> /dev/null \
            | sed -n 's/.*"udid" : "\([^"]*\)".*/\1/p')
        BOOTED_COUNT=$(printf '%s' "$BOOTED" | grep -c . || true)
        if [ "$BOOTED_COUNT" -eq 0 ]
        then
            fail 'no simulator is booted'
            fail 'boot one, or name it: SPFN_HARNESS_TARGET=<udid> sh tools/harness/run-harness.sh ios'
            exit 1
        fi
        if [ "$BOOTED_COUNT" -gt 1 ]
        then
            fail "$BOOTED_COUNT simulators are booted and none was named"
            fail 'name one: SPFN_HARNESS_TARGET=<udid> sh tools/harness/run-harness.sh ios'
            exit 1
        fi
        TARGET=$BOOTED
    fi

    # A named target is whatever the caller typed, and an iPhone is the one thing this
    # script cannot drive. Maestro has no physical-device driver: in maestro-ios.jar,
    # every method of `ios/devicectl/DeviceControlIOSDevice` throws NotImplementedError
    # except uninstall, stop and setPermissions. It cannot install an app, launch one,
    # read a view hierarchy or tap. Upstream's own attempt (mobile-dev-inc/Maestro#2856)
    # was closed unmerged on 2026-06-15.
    #
    # So the refusal belongs here, at the target, and not later at the server: no base
    # URL and no cable changes it. Verifying the SDK on a real iPhone is a manual
    # procedure and tools/harness/README.md carries it.
    if ! xcrun simctl list devices -j 2> /dev/null | names_a_simulator "$TARGET"
    then
        fail "$TARGET is not a simulator this machine knows about"
        fail 'if it is a physical iPhone, maestro cannot drive one: it ships no device driver'
        fail 'iOS device verification is manual — see tools/harness/README.md'
        exit 1
    fi
    pass "iOS target $TARGET"
else
    if [ -z "${ANDROID_HOME-}" ] && [ -z "${ANDROID_SDK_ROOT-}" ]
    then
        fail 'neither ANDROID_HOME nor ANDROID_SDK_ROOT is set'
        fail 'the Gradle build cannot find the Android SDK without one of them'
        exit 1
    fi
    require adb

    if [ -z "$TARGET" ]
    then
        ATTACHED=$(adb devices | sed -n 's/^\([^[:space:]][^[:space:]]*\)[[:space:]][[:space:]]*device$/\1/p')
        ATTACHED_COUNT=$(printf '%s' "$ATTACHED" | grep -c . || true)
        if [ "$ATTACHED_COUNT" -eq 0 ]
        then
            fail 'no emulator or device is attached'
            fail 'start one, or name it: SPFN_HARNESS_TARGET=<serial> sh tools/harness/run-harness.sh android'
            exit 1
        fi
        if [ "$ATTACHED_COUNT" -gt 1 ]
        then
            fail "$ATTACHED_COUNT targets are attached and none was named"
            fail 'name one: SPFN_HARNESS_TARGET=<serial> sh tools/harness/run-harness.sh android'
            exit 1
        fi
        TARGET=$ATTACHED
    fi
    # An emulator serial is `emulator-5554`; anything else is a real device on the far
    # side of a cable or a network, and that distinction decides which base URL can work.
    case "$TARGET" in
        emulator-*) ;;
        *) IS_PHYSICAL_DEVICE=1 ;;
    esac
    pass "Android target $TARGET"

    # A sleeping Android target does not fail the run — it HANGS it. Maestro selects the
    # device, starts its driver, and then waits forever with nothing in its log after
    # "Selected device". The first Android run spent eight minutes there before anyone
    # asked the device whether it was awake.
    #
    # Two settings, because one is not enough: a fresh emulator often reports itself as
    # not charging, and `stay_on_while_plugged_in` does nothing at all when nothing is
    # plugged in. So the charging state is asserted first and the wake second.
    adb -s "$TARGET" shell dumpsys battery set ac 1 > /dev/null 2>&1 || true
    adb -s "$TARGET" shell dumpsys battery set status 2 > /dev/null 2>&1 || true
    adb -s "$TARGET" shell settings put global stay_on_while_plugged_in 7 > /dev/null 2>&1 || true
    adb -s "$TARGET" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
    adb -s "$TARGET" shell wm dismiss-keyguard > /dev/null 2>&1 || true

    WAKEFULNESS=$(adb -s "$TARGET" shell dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
    if [ "$WAKEFULNESS" = Awake ]
    then
        pass 'the target is awake and will stay awake'
    else
        # A refusal, not a warning. Going on from here buys a hang with no message,
        # which is the most expensive way to learn this.
        fail "the target reports mWakefulness=${WAKEFULNESS:-unreadable} and could not be woken"
        fail 'maestro does not fail on a sleeping device, it waits on one forever'
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
printf '\n2. the server\n'
# ---------------------------------------------------------------------------
# An external target is named the way run-integration.sh names one, and for the same
# reason: the local reference server is this repository's own reading of the contract, so
# a run against a real SPFN server has to be able to say so.
TARGET_URL=${SPFN_HARNESS_TARGET_URL-}

if [ -n "$TARGET_URL" ]
then
    BASE_URL=$(printf '%s' "$TARGET_URL" | sed 's:/*$::')
    case "$BASE_URL" in
        http://* | https://*) ;;
        *)
            fail "the target base URL must be absolute http(s), got '$BASE_URL'"
            exit 1
            ;;
    esac
    # A real server verifies an id_token against the provider's own keys, so the test
    # grammar cannot enrol there. The caller supplies a real one or the enrolment cases
    # fail honestly.
    if [ -z "${SPFN_HARNESS_ID_TOKEN-}" ]
    then
        printf '  --    no SPFN_HARNESS_ID_TOKEN was supplied. Against a real server the enrolment\n'
        printf '  --    cases will refuse with harness:noCannedToken rather than pass.\n'
    fi
    pass "external target at $BASE_URL (started by something other than this script)"
else
    # Only an Android target can be physical by the time execution reaches here, because
    # section 1 refuses a physical iOS one outright. That asymmetry is not a preference:
    # `adb reverse` gives a USB-attached Android device a route to the host's loopback,
    # which is the interface the reference server binds to, so the server never has to
    # leave the host. iOS has no equivalent — but the missing route is not why an iPhone
    # is refused, and putting the refusal here once said it was.
    require java
    ./gradlew --console=plain -q :reference-server:spfnReferenceServerLaunchInfo

    if [ ! -f "$LAUNCH_INFO" ]
    then
        fail "the launch classpath was not written to $LAUNCH_INFO"
        exit 1
    fi

    # A plain java process rather than a Gradle JavaExec: this script needs the server's
    # own PID so the trap above can stop it, and a forked JavaExec outlives the Gradle
    # client that started it.
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
    if [ -z "$BASE_URL" ]
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
    pass "reference server ready at $BASE_URL (pid $SERVER_PID)"
fi

# The address the APP uses, which is not always the address this script uses. Three cases,
# and each one is about how that particular target reaches the host's loopback:
#
#   iOS simulator     shares the host's network stack, so its 127.0.0.1 is already right
#   Android emulator  reaches the host's loopback at 10.0.2.2, its own alias for it
#   Android device    reaches nothing by itself; `adb reverse` gives it a route
#
# An external target is none of these — the caller named an address that is already
# reachable — so nothing is rewritten and no route is opened.
APP_BASE_URL=$BASE_URL

if [ -z "$TARGET_URL" ] && [ "$PLATFORM" = android ]
then
    if [ "$IS_PHYSICAL_DEVICE" -eq 0 ]
    then
        APP_BASE_URL=$(printf '%s' "$BASE_URL" | sed 's|//127\.0\.0\.1:|//10.0.2.2:|; s|//localhost:|//10.0.2.2:|')
        pass "the app will use $APP_BASE_URL (the emulator's alias for the host loopback)"
    else
        # `adb reverse tcp:N tcp:N` makes the DEVICE's own 127.0.0.1:N arrive at the
        # host's 127.0.0.1:N over the debugging connection. So the app keeps the base URL
        # this script already has, and the loopback-bound reference server needs no
        # second interface and no exposure to the network.
        REVERSED_PORT=$(printf '%s' "$BASE_URL" | sed -n 's|.*:\([0-9][0-9]*\)$|\1|p')
        if [ -z "$REVERSED_PORT" ]
        then
            fail "no port could be read from $BASE_URL, so no route to it can be opened"
            exit 1
        fi
        if ! adb -s "$TARGET" reverse "tcp:$REVERSED_PORT" "tcp:$REVERSED_PORT" > /dev/null 2>&1
        then
            REVERSED_PORT=''
            fail "the device refused a reverse route for port $REVERSED_PORT"
            fail 'without it the device reaches nothing on this machine'
            fail 'name a server it can reach instead: SPFN_HARNESS_TARGET_URL=http://<host>:<port>'
            exit 1
        fi
        pass "the device reaches $BASE_URL through an adb reverse route on port $REVERSED_PORT"
    fi
fi

# Which cases this run is required to complete, decided here where both the target and
# the caller's declaration are known. Announced either way: a case that silently left the
# list is the failure this whole script is built to refuse.
CASES=$LIFECYCLE_CASES
if [ "$REVOCATION_OPS" = "1" ]
then
    CASES="$CASES
$REVOCATION_CASES"
    pass 'the caller declared the target implements revocation; c10 is expected'
else
    printf '  --    revocation (c10) is out of scope for this run. tools/reference-server\n'
    printf '  --    implements native enrolment and rotation only and refuses auth.keys.revoke\n'
    printf '  --    with CONTRACT_UNSUPPORTED. Export SPFN_HARNESS_REVOCATION_OPS=1 against a\n'
    printf '  --    target that really implements it.\n'
fi

# ---------------------------------------------------------------------------
printf '\n3. building and installing the harness\n'
# ---------------------------------------------------------------------------
if [ "$PLATFORM" = ios ]
then
    xcodegen generate --spec tools/harness/ios/project.yml > /dev/null
    xcodebuild \
        -project tools/harness/ios/SPFNHarness.xcodeproj \
        -scheme SPFNHarness \
        -destination "id=$TARGET" \
        -derivedDataPath "$WORK/dd" \
        build > "$WORK/build.log" 2>&1 \
        || { fail 'the harness did not build'; tail -40 "$WORK/build.log"; exit 1; }

    APP=$(find "$WORK/dd/Build/Products" -maxdepth 2 -name 'SPFNHarness.app' -print -quit)
    if [ -z "$APP" ]
    then
        fail 'the build produced no SPFNHarness.app'
        exit 1
    fi
    xcrun simctl install "$TARGET" "$APP"
    pass "installed $IOS_BUNDLE_ID on $TARGET"
    APP_ID=$IOS_BUNDLE_ID
else
    ./gradlew --console=plain -q :harness-android:assembleDebug \
        || { fail 'the harness did not build'; exit 1; }

    if [ ! -f "$ANDROID_APK" ]
    then
        fail "the build produced no APK at $ANDROID_APK"
        exit 1
    fi
    adb -s "$TARGET" install -r "$ANDROID_APK" > /dev/null
    pass "installed $ANDROID_APPLICATION_ID on $TARGET"
    APP_ID=$ANDROID_APPLICATION_ID
fi

# ---------------------------------------------------------------------------
printf '\n4. the flows\n'
# ---------------------------------------------------------------------------
# ONE maestro invocation for every case, not one per case.
#
# The per-case invocation is the obvious spelling and it is the slow one: maestro
# reinstalls and relaunches the XCUITest driver on every start, and that setup — not the
# six taps a flow performs — was most of the roughly seven minutes each flow took when
# this was measured. One invocation pays it once.
#
# What that costs is the per-case exit code, and the receipts cannot be given up with it:
# a suite-level pass says nothing about which cases ran. So the run writes a JUnit report
# and a receipt is derived from it per case — present and without a failure, or no
# receipt. Section 5 then checks the receipts exactly as before.
REPORT="$WORK/report.xml"
FLOW_FILES=''
for CASE in $CASES
do
    FLOW_FILES="$FLOW_FILES $FLOWS/$CASE.yaml"
done

FLOW_STATUS=0
# shellcheck disable=SC2086
maestro --device "$TARGET" test $FLOW_FILES \
    --format junit \
    --output "$REPORT" \
    -e APP_ID="$APP_ID" \
    -e BASE_URL="$APP_BASE_URL" \
    -e PROVIDER="$PROVIDER" \
    -e TEST_USER="$TEST_USER" \
    -e ID_TOKEN="${SPFN_HARNESS_ID_TOKEN-}" \
    > "$WORK/maestro.log" 2>&1 || FLOW_STATUS=1

if [ ! -f "$REPORT" ]
then
    fail 'maestro wrote no report, so no case can be said to have run'
    sed 's/^/      /' "$WORK/maestro.log" | tail -40
else
    for CASE in $CASES
    do
        # A case earns a receipt only when the report names it AND records no failure for
        # it. `case_status` exits non-zero for both "not in the report" and "failed",
        # which are different events with the same correct outcome here: no receipt.
        if case_status "$REPORT" "$CASE"
        then
            printf '%s\n' "$PLATFORM" > "$RECEIPTS/$PLATFORM-$CASE"
            pass "$CASE"
        else
            FLOW_STATUS=1
            fail "$CASE"
        fi
    done
fi

if [ "$FLOW_STATUS" -ne 0 ]
then
    printf '  --    maestro output:\n'
    sed 's/^/      /' "$WORK/maestro.log" | tail -40
fi

# ---------------------------------------------------------------------------
printf '\n5. every case really ran\n'
# ---------------------------------------------------------------------------
# The receipts are the safety, not the exit code above. A maestro invocation that found no
# flow, or a flow that matched nothing and ended early, can still leave a zero behind.
MISSING=''
EXPECTED_COUNT=0
for CASE in $CASES
do
    EXPECTED_COUNT=$((EXPECTED_COUNT + 1))
    if [ ! -f "$RECEIPTS/$PLATFORM-$CASE" ]
    then
        MISSING="$MISSING $PLATFORM-$CASE"
    fi
done

RECEIPT_STATUS=0
if [ -z "$MISSING" ]
then
    pass "all $EXPECTED_COUNT cases recorded a receipt"
else
    RECEIPT_STATUS=1
    fail "cases with no receipt:$MISSING"
fi

printf '\n'
if [ "$FLOW_STATUS" -eq 0 ] && [ "$RECEIPT_STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
