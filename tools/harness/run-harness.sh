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
# emulator or an Android device, and runs the Maestro flows against it. Ten flows for the
# lifecycle's case table, and three more for the generated approval screens; both tables
# are in tools/harness/README.md.
#
# For those last three this script is also the SECOND DEVICE. It parks a device request
# with the reference server over curl, hands the flow the user code that request was
# issued, and afterwards polls with the device code to see what the phone's decision
# actually did on the server — so each of the three is asserted on the screen and on the
# wire, and a cell needs both to earn its receipt.
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
ANDROID_NETWORK_SECURITY_CONFIG=tools/harness/android/build/generated/spfn-harness-res/xml/spfn_harness_network_security_config.xml

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

# The three cells that drive the GENERATED approval screens against a live server. They
# are not lifecycle cells and they do not run in the batch above, for one reason: each one
# needs a device request that exists on the server before the flow launches, and the user
# code the server issues for it is different every time.
#
# So this runner plays the WAITING DEVICE. Before each cell it parks a key over curl —
# `auth.device.start`, whose body carries a real P-256 key from
# :reference-server:spfnReferenceDeviceStartBody, because the server checks the fingerprint
# against the decoded public key and the poll that collects an approval parses those bytes
# as a key. It runs the flow with that request's user code, and then polls with the device
# code to see what the phone's decision actually did on the server.
#
# Two assertions per cell, and both are needed. The flow says what the phone showed; the
# poll says what the server did. A screen that closed its flow without sending would pass
# the first and fail the second, and a cell that only read the screen would be evidence
# about a screen rather than about the SDK (docs/IMPLEMENTATION-PITFALLS.md P7).
APPROVAL_CASES='d1-approve
d2-deny
d3-unknown-code'

# What d3 types, which is deliberately NOT the code the runner parked. Every character is
# in the server's own user-code alphabet, so this is a code that could have been issued
# and was not — a malformed one would exercise the server's shape check instead of its
# lookup, which is a different cell.
UNKNOWN_USER_CODE=ZZZZ-ZZZZ

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
TARGET_DESCRIPTION='the target was never resolved'

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

# One line naming what this run actually drove, model and OS version included.
#
# A result is a fact about a target, not about the SDK alone, and the two are easy to
# confuse afterwards. The clearest case is key custody: `secureEnclave` means the phone's
# own enclave on a phone, and the host Mac's on a simulator, because an Apple silicon Mac
# lends its enclave to the simulator it hosts. A transcript saying only `ios` cannot tell
# those apart later, and the receipts do not survive the run to say it instead — they live
# under a mktemp directory this script deletes on the way out, because their job is to
# prove within one run that every case really ran.
#
# So the run says it out loud, at the top and again at the end, where whoever pastes the
# result into a report or a COMPATIBILITY row will carry it along.
#
# The device list goes through a file rather than a pipe, for the same reason
# `case_status` takes a path: the heredoc below IS this python's stdin, so anything piped
# in would be swallowed by the script and the lookup would silently find nothing. It did,
# the first time this ran.
describe_ios_target()
{
    xcrun simctl list devices -j > "$WORK/simctl-devices.json" 2> /dev/null || true
    python3 - "$WORK/simctl-devices.json" "$1" <<'DESCRIBE'
import json
import sys

path, udid = sys.argv[1], sys.argv[2]
try:
    with open(path) as handle:
        devices = json.load(handle)["devices"]
except Exception:
    devices = {}

for runtime, entries in devices.items():
    for entry in entries:
        if entry.get("udid") == udid:
            print("iOS simulator, %s on %s, udid %s" % (
                entry.get("name", "unnamed"),
                runtime.rsplit(".", 1)[-1],
                udid,
            ))
            sys.exit(0)
# Unreachable through run-harness.sh, which refuses a target the list does not hold.
# Named anyway rather than left blank: a description that quietly says nothing is worse
# than one that says it could not look.
print("iOS target %s, not described (the simulator list could not be read)" % udid)
DESCRIBE
}

describe_android_target()
{
    MODEL=$(adb -s "$1" shell getprop ro.product.model 2> /dev/null | tr -d '\r' || true)
    RELEASE=$(adb -s "$1" shell getprop ro.build.version.release 2> /dev/null | tr -d '\r' || true)
    API=$(adb -s "$1" shell getprop ro.build.version.sdk 2> /dev/null | tr -d '\r' || true)
    case "$1" in
        emulator-*) KIND=emulator ;;
        *) KIND=device ;;
    esac
    printf 'Android %s, %s on Android %s (API %s), serial %s\n' \
        "$KIND" "${MODEL:-unnamed}" "${RELEASE:-unknown}" "${API:-unknown}" "$1"
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

# First string value of a JSON key — the FIRST occurrence, wherever on the body it sits.
#
# Not a `sed` one-liner, and `head -1` was never enough to make it one. A greedy `.*` before
# the key matches as much as it can, so `sed` reports the LAST occurrence of the key ON A
# LINE, and `head -1` only ever chose between LINES — a JSON body arrives as one.
# `{"userCode":"first","nested":{"userCode":"last"}}` answered `last`, which is the wrong
# one for every caller here: a poll's own `status` is not one nested inside an error object
# (docs/IMPLEMENTATION-PITFALLS.md P5).
#
# `awk` walks to the first `"<key>":` by index and stops there, so first-hit is what the
# code does and not only what the comment claims. Everything the old expression accepted is
# still accepted — a colon with or without spaces after it, a value with anything but a
# quote in it — and anything it cannot read is the empty string, which is what the callers
# treat as a failure.
json_string()
{
    printf '%s' "$1" | awk -v key="\"$2\":" '
        {
            at = index($0, key);
            if (at == 0) next;
            rest = substr($0, at + length(key));
            sub(/^ +/, "", rest);
            if (substr(rest, 1, 1) != "\"") next;
            rest = substr(rest, 2);
            end = index(rest, "\"");
            if (end == 0) next;
            print substr(rest, 1, end - 1);
            exit;
        }
    '
}

# The hosts the generated network security config at $1 permits cleartext to, one per line.
#
# A missing file prints nothing, and the caller reads that as no host at all — which is
# what a check that could not read its input is entitled to answer.
cleartext_hosts()
{
    if [ ! -f "$1" ]
    then
        return 0
    fi
    sed -n 's|.*<domain[^>]*>\([^<]*\)</domain>.*|\1|p' "$1"
}

# The host of the URL $1: scheme stripped, port and path dropped.
url_host()
{
    printf '%s' "$1" | sed -e 's|^[a-z][a-z0-9+.-]*://||' -e 's|/.*$||' -e 's|:[0-9]*$||'
}

# Whether the app built with the config at $1 may reach the URL $2 in the clear.
#
# Two values that come from two different places and nothing holds together. The config is
# GENERATED at build time from the local.properties key `spfn.harness.serverBaseUrl`; $2 is
# derived from the address the reference server actually bound. On the 2d run they had
# drifted apart — the runner handed the app the emulator's alias for the host loopback
# while the build permitted a LAN address — and every cell that touches the network came
# back `err:connectivity`. The two that never call anything passed, which is the worst
# possible shape for a wrong answer: it looks like a flow bug.
#
# The alias is gone since 2f and both values are now spellings of the host loopback, which
# narrows what can drift but does not remove the drift: the key is still hand-written per
# machine and the port is still whatever the server bound.
#
# A missing file answers no. That file IS the cleartext exception, so a run that cannot
# find it has not learned the app is configured; it has learned nothing
# (docs/IMPLEMENTATION-PITFALLS.md P7).
cleartext_permits()
{
    if [ ! -f "$1" ]
    then
        return 1
    fi
    WANTED_HOST=$(url_host "$2")
    if [ -z "$WANTED_HOST" ]
    then
        return 1
    fi
    cleartext_hosts "$1" | grep -qxF "$WANTED_HOST"
}

# ---------------------------------------------------------------------------
# preflight: the three ways every flow fails at once, read before any is spent
# ---------------------------------------------------------------------------
# On 2026-09-02 four device runs died 15 to 30 minutes in, all four on the same message —
# `Element not found: Id matching regex: btn_wipe` — and none of the four was about the
# flow that reported it. tools/harness/README.md "Picking a target" names all three
# causes: something of the system's is drawn over the app, a control is below the fold and
# therefore not in the accessibility tree at all (docs/IMPLEMENTATION-PITFALLS.md P25), or
# an ANR dialog is up. The tree says which, and reading it once costs seconds.
#
# So the four functions below are the preflight's judgments, and each one takes FILES
# rather than a device. That is what lets tools/harness/probe-preflight.sh drive them on a
# machine with no emulator, no simulator and no maestro: the dump is the input, and every
# named refusal is reproducible from a fixture.

# Every id a flow may tap on the app's FIRST screen, one per line, sorted and unique.
#
# Read out of $1/*.yaml rather than written down here, because a list written down here
# would be a second copy of the flows and would go on agreeing with itself after they
# changed. `HarnessRunnerBlockTest` reads the same directory for the same reason.
#
# Up to and including each file's FIRST `tapOn`, and no further. Everything a flow taps
# after that is on a screen this dump cannot show — `enterCode.submit` and
# `reviewDevice.approve` belong to the generated approval screens, which open over this
# one — and a preflight that demanded them would refuse a run that was about to pass. What
# the cut leaves is the harness's own runner grid, which the README's screen table and
# `RunnerBlockTags` both place inside the first viewport.
#
# A file whose first tap sits behind `runFlow: prelude-clean.yaml` still contributes that
# tap: the prelude taps `btn_wipe` on this same screen and returns to it, so both ids are
# first-viewport ids and the union is what the preflight has to find.
preflight_first_viewport_ids()
{
    awk '
        FNR == 1 { seen = 0; stop = 0 }
        stop { next }
        /^[[:space:]]*-[[:space:]]*tapOn:/ { seen = 1; next }
        seen && /^-/ { stop = 1; next }
        {
            # `id:` with a word in front of it is somebody else`s key — `appId:` is the
            # one this file really holds — so the character before it is checked.
            if (match($0, /(^|[^A-Za-z_])id:[[:space:]]*"[^"]+"/))
            {
                value = substr($0, RSTART, RLENGTH);
                sub(/^[^"]*"/, "", value);
                sub(/"$/, "", value);
                print value;
            }
        }
    ' "$1"/*.yaml | sort -u
}

# Whether the listing at $1 says the app $2 is installed on the target.
#
# Two spellings, one per platform, and both are whole-value matches. `pm list packages`
# takes its argument as a FILTER and answers every package the string is a substring of,
# so a `<id>.test` left behind by an instrumentation run would answer this question yes
# for an app that is not there. iOS lists a plist, where the bundle id is a quoted value.
#
# A listing that could not be read answers no. An unrun check must never read as a passed
# one (docs/IMPLEMENTATION-PITFALLS.md P7), and here that is the whole point: the run is
# about to spend twenty minutes on the answer.
preflight_installed()
{
    if [ ! -f "$1" ]
    then
        return 1
    fi
    if grep -qxF "package:$2" "$1"
    then
        return 0
    fi
    grep -qF "CFBundleIdentifier = \"$2\"" "$1"
}

# Whether the state at $1 says the app $2 is the process the target is showing.
#
# Three spellings, because two platforms answer this three ways and none of them is a bare
# pid: Android names the package on the `topResumedActivity` line of
# `dumpsys activity activities`, and iOS names it either as a `launchctl` job with a real
# pid or as `simctl launch`'s own `<bundle>: <pid>` answer.
#
# Installed and running are different failures with different fixes — one is a build that
# never landed, the other an app that crashed on launch — so they are checked apart and
# named apart.
preflight_running()
{
    if [ ! -f "$1" ]
    then
        return 1
    fi
    if grep -F topResumedActivity "$1" | grep -qF "$2"
    then
        return 0
    fi
    if grep -qF "UIKitApplication:$2" "$1"
    then
        return 0
    fi
    grep -qE "^$2: [0-9]+$" "$1"
}

# Whether the tree at $1 holds an ANR dialog.
#
# `android:id/aerr_wait`, `android:id/aerr_close`: the dialog is the system's, it is drawn
# over everything including the harness, and a dump that holds it holds nothing of the app
# at all. The substring is deliberately shorter than the full resource id — nothing this
# repository draws, generates or names contains `aerr_`, and a false positive costs one
# re-run while a false negative costs the twenty minutes this whole section exists to save.
#
# iOS has no equivalent and this is never called there; the preflight reports that as `not
# applicable` rather than as a pass, because an unrun check is not a passed one.
#
# A tree that could not be read answers no here, and is refused by `preflight_ids_present`
# below, which is the one place an unreadable tree gets a name.
preflight_anr_present()
{
    if [ ! -f "$1" ]
    then
        return 1
    fi
    grep -qF 'aerr_' "$1"
}

# Whether every id in the file $2 is in the accessibility tree dumped to $1, and which
# ones are not.
#
#   exit 0   all of them, nothing printed
#   exit 1   some are missing, and the missing ones are printed one per line
#   exit 2   the tree or the id list could not be read, and the reason is printed
#
# Both dumps go through here, because both spell an id the same way. Android's
# `uiautomator dump` writes XML whose nodes carry `resource-id`, and `maestro hierarchy`
# writes JSON whose nodes carry `"resource-id"` — the two commands
# docs/IMPLEMENTATION-PITFALLS.md P25 names for exactly this question. The other three
# keys are what an iOS hierarchy has been seen to spell the same field as; a key that is
# never there costs nothing, and a run on a Mac is what settles which one it is.
#
# The suffix after a `/` counts as the id too. A Compose test tag published through
# `testTagsAsResourceId` arrives bare, and a View's arrives as `<package>:id/<name>`, and
# a preflight that knew only one of those would refuse a screen that is perfectly correct.
#
# python3 rather than grep for the same reason `case_status` is python: the two answers
# that matter — "the tree does not have it" and "the tree could not be parsed" — are one
# quiet mistake apart, and this is the check that has to tell them apart.
preflight_ids_present()
{
    python3 - "$1" "$2" <<'PRESENT'
import json
import sys
import xml.etree.ElementTree as ET

tree_path, ids_path = sys.argv[1], sys.argv[2]

# What each dump calls the field a Maestro `id:` selector matches on.
KEYS = ("resource-id", "resourceId", "accessibilityIdentifier", "identifier")


def from_xml(text):
    found = set()
    for node in ET.fromstring(text).iter():
        for key in KEYS:
            value = node.get(key)
            if value:
                found.add(value)
    return found


def from_json(text):
    found = set()

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in KEYS and isinstance(value, str) and value:
                    found.add(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(json.loads(text))
    return found


def refuse(reason):
    print(reason)
    sys.exit(2)


try:
    with open(tree_path, encoding="utf-8", errors="replace") as handle:
        text = handle.read().strip()
except OSError:
    refuse("the tree at %s could not be read" % tree_path)

if not text:
    refuse("the tree at %s is empty" % tree_path)

# A dump is XML or it is JSON, and maestro has been known to print a line of its own
# before the JSON, so the JSON is looked for from its first brace rather than at offset 0.
try:
    if text.startswith("<"):
        found = from_xml(text)
    else:
        at = min(x for x in (text.find("{"), text.find("[")) if x >= 0)
        found = from_json(text[at:])
except (ET.ParseError, ValueError, json.JSONDecodeError):
    refuse("the tree at %s is neither readable XML nor readable JSON" % tree_path)

found |= {value.rsplit("/", 1)[-1] for value in found}

try:
    with open(ids_path, encoding="utf-8") as handle:
        wanted = [line.strip() for line in handle if line.strip()]
except OSError:
    refuse("the id list at %s could not be read" % ids_path)

if not wanted:
    refuse("the id list at %s is empty, so nothing was checked" % ids_path)

missing = [name for name in wanted if name not in found]
for name in missing:
    print(name)
sys.exit(1 if missing else 0)
PRESENT
}

# Parks a device request with the target and answers `<userCode> <deviceCode>`.
#
# The body carries a freshly generated P-256 key, because the server checks that
# `fingerprint` is the SHA-256 of the decoded `publicKey` and the poll that collects an
# approval registers those bytes as a key — twice measured, once in
# `SpfnReferenceServer.deviceStart` and once in `SpfnReferenceState.enrollKey`. A constant
# body satisfies neither, and a constant keyId could be registered once where a run needs
# three.
#
# Empty output is the failure, and the caller treats it as one: a cell whose request was
# never parked would otherwise run against a code nobody issued and report d3's result.
park_device_request()
{
    BODY=$(./gradlew --console=plain -q :reference-server:spfnReferenceDeviceStartBody 2> /dev/null)
    if [ -z "$BODY" ]
    then
        return 1
    fi
    STARTED=$(curl -sS -X POST -H 'content-type: application/json' \
        -d "$BODY" "$1/_auth/device/start" 2> /dev/null)
    USER_CODE=$(json_string "$STARTED" userCode)
    DEVICE_CODE=$(json_string "$STARTED" deviceCode)
    if [ -z "$USER_CODE" ] || [ -z "$DEVICE_CODE" ]
    then
        return 1
    fi
    printf '%s %s\n' "$USER_CODE" "$DEVICE_CODE"
}

# Polls $1 for device code $2 until the answer is $3, and prints what it last read.
#
# Up to ten attempts a second apart. An approval is applied before the phone's flow closes,
# but the poll is a separate request from a separate process, and a single poll taken the
# instant maestro returned read `pending` on a machine that was merely busy.
#
# The EXPECTED answer is what the loop stops on rather than "anything but pending", and
# that is what makes d3 cheap as well as correct: `pending` is the answer that cell wants,
# so it settles on the first attempt instead of spending ten seconds proving a negative.
#
# What is printed is the last answer read, whether or not it is the expected one, so a
# caller that fails reports the value it failed on rather than the word "timeout".
#
# The three answers are told apart by the status as well as the body: `pending` and
# `approved` are 200 with a `status` field, and a denial is a 403 refusal envelope whose
# `code` is `DeviceAuthDeniedError`. Reading only the body would make a denial and an
# answer nothing could be read out of into the same event.
poll_device_request()
{
    ATTEMPT=0
    ANSWER=''
    while [ "$ATTEMPT" -lt 10 ]
    do
        POLL_STATUS=$(curl -sS -o "$WORK/poll.json" -w '%{http_code}' \
            -X POST -H 'content-type: application/json' \
            -d "{\"deviceCode\":\"$2\"}" "$1/_auth/device/poll" 2> /dev/null || true)
        POLL_BODY=$(cat "$WORK/poll.json" 2> /dev/null || true)
        if [ "$POLL_STATUS" = 200 ]
        then
            ANSWER=$(json_string "$POLL_BODY" status)
        else
            ANSWER=$(json_string "$POLL_BODY" code)
        fi
        if [ "$ANSWER" = "$3" ]
        then
            break
        fi
        ATTEMPT=$((ATTEMPT + 1))
        sleep 1
    done
    printf '%s\n' "${ANSWER:-unreadable}"
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
    TARGET_DESCRIPTION=$(describe_ios_target "$TARGET")
    pass "$TARGET_DESCRIPTION"
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
    TARGET_DESCRIPTION=$(describe_android_target "$TARGET")
    pass "$TARGET_DESCRIPTION"

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

# The address the APP uses, which is not always the address this script uses. Every
# Android target — emulator and phone alike — is given the same route to the host's
# loopback, and the app keeps the address this script already has:
#
#   iOS simulator     shares the host's network stack, so its 127.0.0.1 is already right
#   Android target    reaches nothing by itself; `adb reverse` gives it a route
#
# The emulator has an alias of its own for the host loopback, 10.0.2.2, and this script
# used to rewrite the base URL to it. That alias cannot be used, because the SDK will not
# synchronize its proof clock over plain HTTP to anything but loopback — `isTrusted` in
# android/spfn-client/.../SpfnClock.kt admits https, `localhost`, `::1` and `127.*` and
# nothing else. On the 2d run every cell that needs a proof came back
# `err:clockSynchronization:untrustedBaseURL` while registration and resume passed, which
# reads as a flow bug and is not one. The SDK's rule is the right one; the alias was the
# wrong route, and `adb reverse` — which an emulator supports exactly as a phone does —
# is the one that keeps the address loopback at both ends.
#
# An external target is none of these — the caller named an address that is already
# reachable — so nothing is rewritten and no route is opened.
APP_BASE_URL=$BASE_URL

if [ -z "$TARGET_URL" ] && [ "$PLATFORM" = android ]
then
    # `adb reverse tcp:N tcp:N` makes the TARGET's own 127.0.0.1:N arrive at the host's
    # 127.0.0.1:N over the debugging connection. So the app keeps the base URL this script
    # already has, and the loopback-bound reference server needs no second interface and no
    # exposure to the network.
    REVERSED_PORT=$(printf '%s' "$BASE_URL" | sed -n 's|.*:\([0-9][0-9]*\)$|\1|p')
    if [ -z "$REVERSED_PORT" ]
    then
        fail "no port could be read from $BASE_URL, so no route to it can be opened"
        exit 1
    fi
    if ! adb -s "$TARGET" reverse "tcp:$REVERSED_PORT" "tcp:$REVERSED_PORT" > /dev/null 2>&1
    then
        REVERSED_PORT=''
        fail "the target refused a reverse route for port $REVERSED_PORT"
        fail 'without it the target reaches nothing on this machine'
        fail 'name a server it can reach instead: SPFN_HARNESS_TARGET_URL=http://<host>:<port>'
        exit 1
    fi
    pass "the target reaches $APP_BASE_URL through an adb reverse route on port $REVERSED_PORT"
fi

# Which cases this run is required to complete, decided here where both the target and
# the caller's declaration are known. Announced either way: a case that silently left the
# list is the failure this whole script is built to refuse.
BATCH_CASES=$LIFECYCLE_CASES
if [ "$REVOCATION_OPS" = "1" ]
then
    BATCH_CASES="$BATCH_CASES
$REVOCATION_CASES"
    pass 'the caller declared the target implements revocation; c10 is expected'
else
    printf '  --    revocation (c10) is out of scope for this run. tools/reference-server\n'
    printf '  --    implements native enrolment and rotation only and refuses auth.keys.revoke\n'
    printf '  --    with CONTRACT_UNSUPPORTED. Export SPFN_HARNESS_REVOCATION_OPS=1 against a\n'
    printf '  --    target that really implements it.\n'
fi

# Every case this run must complete, batched and per-cell alike. Section 5 checks a
# receipt for each of these, so a cell that quietly left the list is the exact failure
# this script is built to refuse.
#
# The approval cells are in the list and NOT in the batch: each needs a device request
# parked on the server before its own launch, and the user code that request is issued is
# different every time.
CASES="$BATCH_CASES
$APPROVAL_CASES"

# ---------------------------------------------------------------------------
printf '\n3a. building and installing the harness\n'
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

    # The one host this build lets the app reach in the clear, held against the one this
    # run is about to hand it. Here because the config is written by the build and read by
    # the first flow, so this is the only point where both values exist and nothing has
    # been spent yet: a mismatch costs a whole run to diagnose from the other end.
    PERMITTED_HOSTS=$(cleartext_hosts "$ANDROID_NETWORK_SECURITY_CONFIG" | tr '\n' ' ' | sed 's/ *$//')
    if ! cleartext_permits "$ANDROID_NETWORK_SECURITY_CONFIG" "$APP_BASE_URL"
    then
        if [ ! -f "$ANDROID_NETWORK_SECURITY_CONFIG" ]
        then
            fail "the build wrote no cleartext exception at $ANDROID_NETWORK_SECURITY_CONFIG"
        else
            fail "this build permits cleartext to ${PERMITTED_HOSTS:-no host at all}"
            fail "and the app will be pointed at $(url_host "$APP_BASE_URL")"
        fi
        fail 'every call to any other host is refused before it leaves the app, and arrives'
        fail 'at a receipt as err:connectivity on each cell that touches the network'
        fail 'the permitted host is the local.properties key spfn.harness.serverBaseUrl'
        exit 1
    fi
    pass "the build permits cleartext to $PERMITTED_HOSTS, which is where the app is pointed"
fi

# ---------------------------------------------------------------------------
printf '\n3b. preflight\n'
# ---------------------------------------------------------------------------
# One tree, read before the first flow is spent.
#
# Every failure this refuses looks identical from the other end: `Element not found: Id
# matching regex: btn_wipe`, on every cell, fifteen to thirty minutes in. Four device runs
# went that way on 2026-09-02 and none of them was about `btn_wipe`. The three causes are
# in tools/harness/README.md "Picking a target", and one dump separates all three — so
# this section names which one it is in seconds instead of leaving a run to discover it
# in minutes and misattribute it.
#
# The budget is thirty seconds, measured and printed. The dump is taken once and retried
# at most twice, because the one thing a retry buys is a screen that was still being drawn
# — nothing here gets better by being asked a fourth time. Everything else is refused on
# the first answer: an app that is not installed will not install itself, and an ANR
# dialog does not go away while a script watches it.
#
# The app is launched here because nothing before this point launches it. Section 3a
# installs, and the first thing the prelude does is `launchApp` with `clearState`, so this
# launch costs the run nothing and is thrown away by the flow that follows it.
PREFLIGHT_BUDGET=30
PREFLIGHT_STARTED=$(date +%s)
PREFLIGHT_IDS="$WORK/first-viewport-ids.txt"
PREFLIGHT_TREE="$WORK/preflight-tree"
PREFLIGHT_STATE="$WORK/preflight-state"
PREFLIGHT_APPS="$WORK/preflight-apps"

preflight_elapsed()
{
    printf '%s' "$(( $(date +%s) - PREFLIGHT_STARTED ))"
}

# The named refusal, with the elapsed time on it: a preflight that ran out of budget and
# one that read a real answer are different events and the seconds say which.
preflight_refuse()
{
    fail "preflight: $1 ($(preflight_elapsed)s of ${PREFLIGHT_BUDGET}s)"
    shift
    for LINE in "$@"
    do
        printf '      %s\n' "$LINE"
    done
    exit 1
}

preflight_first_viewport_ids "$FLOWS" > "$PREFLIGHT_IDS"
PREFLIGHT_ID_COUNT=$(grep -c . "$PREFLIGHT_IDS" || true)
if [ "$PREFLIGHT_ID_COUNT" -eq 0 ]
then
    preflight_refuse 'no id could be read out of the flows, so nothing would be checked' \
        "the ids are read from $FLOWS/*.yaml, up to each file's first tapOn" \
        'a preflight that checked nothing would pass every screen, including a blank one'
fi

# 1. installed, and running.
if [ "$PLATFORM" = ios ]
then
    xcrun simctl listapps "$TARGET" > "$PREFLIGHT_APPS" 2> /dev/null || true
else
    adb -s "$TARGET" shell pm list packages "$APP_ID" 2> /dev/null | tr -d '\r' > "$PREFLIGHT_APPS" || true
fi

if ! preflight_installed "$PREFLIGHT_APPS" "$APP_ID"
then
    preflight_refuse "not installed — the target does not list $APP_ID" \
        'section 3a reported an install, so the target lost it or installed it for another user' \
        'nothing below this line can be true of an app that is not there'
fi

if [ "$PLATFORM" = ios ]
then
    xcrun simctl launch "$TARGET" "$APP_ID" > "$WORK/preflight-launch" 2>&1 || true
else
    adb -s "$TARGET" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 \
        > /dev/null 2>&1 || true
fi

# 2. and 3., against one tree per attempt. Three attempts at most and thirty seconds at
# most, whichever runs out first.
PREFLIGHT_ATTEMPT=0
PREFLIGHT_MISSING=''
PREFLIGHT_READY=0
while [ "$PREFLIGHT_ATTEMPT" -lt 3 ]
do
    PREFLIGHT_ATTEMPT=$((PREFLIGHT_ATTEMPT + 1))

    if [ "$PLATFORM" = ios ]
    then
        # Two answers concatenated: `simctl launch` printed `<bundle>: <pid>` above, and
        # launchctl names the job. `preflight_running` reads either spelling.
        cat "$WORK/preflight-launch" > "$PREFLIGHT_STATE" 2> /dev/null || true
        xcrun simctl spawn "$TARGET" launchctl list >> "$PREFLIGHT_STATE" 2> /dev/null || true
        maestro --device "$TARGET" hierarchy > "$PREFLIGHT_TREE" 2> /dev/null || true
    else
        adb -s "$TARGET" shell dumpsys activity activities 2> /dev/null | tr -d '\r' \
            > "$PREFLIGHT_STATE" || true
        adb -s "$TARGET" shell uiautomator dump /sdcard/spfn-preflight.xml > /dev/null 2>&1 || true
        adb -s "$TARGET" shell cat /sdcard/spfn-preflight.xml 2> /dev/null | tr -d '\r' \
            > "$PREFLIGHT_TREE" || true
    fi

    # An ANR dialog is refused on the first sight of it. Retrying is for an app that is
    # still drawing, and this is not that: the dialog is the system's, it is over
    # everything, and it stays until somebody dismisses it.
    if [ "$PLATFORM" = android ] && preflight_anr_present "$PREFLIGHT_TREE"
    then
        preflight_refuse 'anr dialog — the tree holds android:id/aerr_ nodes and nothing of the app' \
            'a system dialog is drawn over the harness, so every tap by id misses' \
            'dismiss it for one run; cold-boot the target (emulator -avd <name> -no-snapshot-load)' \
            'to stop it coming back — tools/harness/README.md "Picking a target"'
    fi

    PREFLIGHT_MISSING=''
    if preflight_running "$PREFLIGHT_STATE" "$APP_ID"
    then
        PREFLIGHT_MISSING=$(preflight_ids_present "$PREFLIGHT_TREE" "$PREFLIGHT_IDS" || true)
        if [ -z "$PREFLIGHT_MISSING" ]
        then
            PREFLIGHT_READY=1
            break
        fi
    fi

    if [ "$PREFLIGHT_ATTEMPT" -ge 3 ] || [ "$(preflight_elapsed)" -ge "$PREFLIGHT_BUDGET" ]
    then
        break
    fi
    sleep 2
done

if [ "$PREFLIGHT_READY" -ne 1 ]
then
    if ! preflight_running "$PREFLIGHT_STATE" "$APP_ID"
    then
        preflight_refuse "not running — $APP_ID is installed and is not the app on top" \
            'it was launched here and did not come up, or it came up and died' \
            'a crash on launch reads from a flow exactly as a missing button does'
    fi
    # Named, one per line. Which id is missing is the whole answer: `btn_wipe` alone is a
    # control below the fold (P25), and every id at once is something drawn over the app.
    preflight_refuse "ids missing from first viewport — $(printf '%s' "$PREFLIGHT_MISSING" | tr '\n' ' ')" \
        'the runner taps these on the first screen and the accessibility tree does not have them' \
        'a scroll container publishes only what overlaps the viewport, so a control below the' \
        'fold is absent rather than dim (docs/IMPLEMENTATION-PITFALLS.md P25); a system alert' \
        'over the app removes all of them at once'
fi

if [ "$PLATFORM" = ios ]
then
    PREFLIGHT_ANR='anr not applicable'
else
    PREFLIGHT_ANR='anr none'
fi
pass "preflight: installed ok · $PREFLIGHT_ANR · ids $PREFLIGHT_ID_COUNT/$PREFLIGHT_ID_COUNT present ($(preflight_elapsed)s)"

# ---------------------------------------------------------------------------
printf '\n4a. the lifecycle flows\n'
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
for CASE in $BATCH_CASES
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
    for CASE in $BATCH_CASES
    do
        # A case earns a receipt only when the report names it AND records no failure for
        # it. `case_status` exits non-zero for both "not in the report" and "failed",
        # which are different events with the same correct outcome here: no receipt.
        if case_status "$REPORT" "$CASE"
        then
            printf '%s\n%s\n' "$PLATFORM" "$TARGET_DESCRIPTION" > "$RECEIPTS/$PLATFORM-$CASE"
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
printf '\n4b. the approval cells, with this runner as the waiting device\n'
# ---------------------------------------------------------------------------
# One maestro invocation per cell here, and the batch's reason for not doing that does not
# apply: these three cells cannot share a launch. Each needs a device request parked on the
# server first, and the user code that request is issued is different every time, so the
# code has to reach maestro as a variable of that invocation.
#
# The receipt is earned exactly as the batch's is — through `case_status` on this cell's
# own JUnit report — and then a second time by the SERVER. Both must hold: a flow that
# passed every on-screen assertion while the server still held the request `pending` has
# proved something about a screen and nothing about the SDK, and a receipt for it would be
# the most expensive kind of green there is.
for CASE in $APPROVAL_CASES
do
    # What each cell types, and what the server must report afterwards. d3 types a code
    # nobody was issued, so the request this runner parked has to be untouched by it —
    # which is a stronger claim than "the screen showed an error", and the only one that
    # rules out a phone acting on somebody else's request.
    case "$CASE" in
        d1-approve) TYPED='' ; EXPECTED_POLL=approved ;;
        d2-deny) TYPED='' ; EXPECTED_POLL=DeviceAuthDeniedError ;;
        d3-unknown-code) TYPED=$UNKNOWN_USER_CODE ; EXPECTED_POLL=pending ;;
        *)
            FLOW_STATUS=1
            fail "$CASE has no declared user code or poll expectation"
            continue
            ;;
    esac

    PARKED=$(park_device_request "$BASE_URL" || true)
    if [ -z "$PARKED" ]
    then
        FLOW_STATUS=1
        fail "$CASE — no device request could be parked, so the cell was not run"
        continue
    fi
    PARKED_USER_CODE=${PARKED% *}
    PARKED_DEVICE_CODE=${PARKED#* }
    if [ -z "$TYPED" ]
    then
        TYPED=$PARKED_USER_CODE
    fi

    CASE_REPORT="$WORK/report-$CASE.xml"
    maestro --device "$TARGET" test "$FLOWS/$CASE.yaml" \
        --format junit \
        --output "$CASE_REPORT" \
        -e APP_ID="$APP_ID" \
        -e BASE_URL="$APP_BASE_URL" \
        -e PROVIDER="$PROVIDER" \
        -e TEST_USER="$TEST_USER" \
        -e ID_TOKEN="${SPFN_HARNESS_ID_TOKEN-}" \
        -e USER_CODE="$TYPED" \
        > "$WORK/maestro-$CASE.log" 2>&1 || true

    if [ ! -f "$CASE_REPORT" ] || ! case_status "$CASE_REPORT" "$CASE"
    then
        FLOW_STATUS=1
        fail "$CASE — the flow did not pass"
        sed 's/^/      /' "$WORK/maestro-$CASE.log" | tail -20
        continue
    fi

    ANSWERED=$(poll_device_request "$BASE_URL" "$PARKED_DEVICE_CODE" "$EXPECTED_POLL")
    if [ "$ANSWERED" != "$EXPECTED_POLL" ]
    then
        # No receipt. The flow passed and the server disagrees with it, which is the one
        # disagreement these three cells exist to find.
        FLOW_STATUS=1
        fail "$CASE — the flow passed but the server answered '$ANSWERED', expected '$EXPECTED_POLL'"
        continue
    fi

    printf '%s\n%s\n' "$PLATFORM" "$TARGET_DESCRIPTION" > "$RECEIPTS/$PLATFORM-$CASE"
    pass "$CASE (server answered $ANSWERED)"
done

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
# Repeated here rather than left at the top, because this is the part that gets pasted.
printf 'target: %s\n' "$TARGET_DESCRIPTION"
if [ "$FLOW_STATUS" -eq 0 ] && [ "$RECEIPT_STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
