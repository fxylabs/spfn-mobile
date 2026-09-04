#!/bin/sh
# SPFN Mobile — put the showcase on a real phone, and open it.
#
#   sh examples/ui-spec/install-device.sh ios
#   sh examples/ui-spec/install-device.sh android --fixture sheetHalf-close
#   sh examples/ui-spec/install-device.sh --probe    # no phone; proves the refusals bite
#
# `run-cells.sh` builds nothing and installs nothing on purpose — it is pointed at an app
# somebody already put on a device. This is that somebody, written down. It is the one
# command between a checkout and a phone showing the menu, and with `--fixture` it is also
# how a `manual` cell is reached: that argument opens the cell's own flow, which is what the
# checklist in `examples/ui-spec/generated/device-approval.cases.md` asks a person to do.
#
# It drives no runner and asserts nothing. Maestro cannot drive a physical iPhone at all
# (`tools/harness/README.md` records why, in detail), and a device round is a person looking
# at a screen — so this script's whole job is to get the right build onto the right phone and
# start it on the right flow.
#
# ---------------------------------------------------------------------------
# Every target comes from the environment, and a missing one is named
# ---------------------------------------------------------------------------
#
# A phone id is a fact about somebody's desk. A team id is a credential-adjacent value this
# repository is not allowed to hold, and the validator forbids one in the tree. So both are
# environment variables, and an absent one stops the run with its NAME and nothing else
# printed — never a guess at a default, never the first device it can find
# (docs/IMPLEMENTATION-PITFALLS.md P7).
#
#   SPFN_IOS_TEAM             the Apple Developer team id the build signs with
#   SPFN_IOS_DEVICE           the iPhone's hardware id, for xcodebuild's -destination
#   SPFN_IOS_DEVICECTL_UDID   the same phone's CoreDevice udid, for devicectl (see below)
#   SPFN_ANDROID_SERIAL       adb's serial, `adb devices`. A wireless one is `host:port`
#
# ---------------------------------------------------------------------------
# The two iOS ids are two ids, and that cost an afternoon
# ---------------------------------------------------------------------------
#
# `xcodebuild -destination "platform=iOS,id=…"` wants the HARDWARE identifier — the long
# dashed form, `00008120-000C4D8E0A38401E` — and `xcrun devicectl device install app
# --device …` wants CoreDevice's own udid for the same phone, which is an unrelated UUID.
# Passing either where the other belongs fails, and neither failure says the id was the wrong
# KIND of id: xcodebuild answers that the destination is unavailable and devicectl answers
# that it found no matching device, which both read as "the phone is not plugged in".
#
#   xcrun devicectl list devices
#
# prints both for every paired phone — the `Identifier` column is devicectl's and the
# `Hardware Identifier` under `devicectl list devices --verbose` is xcodebuild's. Set
# SPFN_IOS_DEVICECTL_UDID from the first and SPFN_IOS_DEVICE from the second. If only one
# variable is set this script says which of the two it is missing rather than trying the one
# it has in both places.
#
# Requires: xcodegen, xcodebuild and xcrun on macOS; adb and a JDK for Android.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

APP_ID=xyz.superfunction.spfn.example
IOS_PROJECT=examples/ios-swiftui/SPFNExample.xcodeproj
IOS_SPEC=examples/ios-swiftui/project.yml
IOS_SCHEME=SPFNExample
IOS_DERIVED=/tmp/spfn-example-device
ANDROID_APK=examples/android-compose/build/outputs/apk/debug/example-compose-debug.apk

# The launch argument's name, which is the same word on both platforms and in every flow
# file: `SPFN_UI_FIXTURE`. iOS takes it as `-NAME value` and Android as an intent extra.
FIXTURE_KEY=SPFN_UI_FIXTURE

die()
{
    printf '%s\n' "$1" >&2
    exit 1
}

usage()
{
    printf 'usage: sh examples/ui-spec/install-device.sh ios|android [--fixture <cell>]\n' >&2
    printf '       sh examples/ui-spec/install-device.sh --probe\n' >&2
}

require()
{
    if ! command -v "$1" > /dev/null 2>&1
    then
        printf 'install-device.sh needs %s and cannot find it.\n' "$1" >&2
        if [ $# -gt 1 ]
        then
            printf '  %s\n' "$2" >&2
        fi
        exit 1
    fi
}

# The value of environment variable $1, or a refusal naming it and nothing else.
#
# Naming it and nothing else is the whole point: these hold a team id and two device ids,
# and a message that echoed the value it did find would put one in a terminal scrollback and
# in whatever captured it.
required_env()
{
    eval "VALUE=\${$1:-}"
    if [ -z "$VALUE" ]
    then
        die "install-device.sh needs $1 and it is not set. $2"
    fi
    printf '%s' "$VALUE"
}

# ---------------------------------------------------------------------------
# iOS
# ---------------------------------------------------------------------------
#
# Three build settings are not decoration, and tools/harness/README.md records the round
# that paid for them. `project.yml` pins MANUAL signing and an ad-hoc identity, because that
# is what a simulator build needs and it keeps every credential out of this repository — so a
# device build has to override both on the command line. `DEVELOPMENT_TEAM` alone leaves
# manual signing in place and fails asking for a profile it was never going to find, and
# `-allowProvisioningUpdates` is what registers the device and issues that profile.
#
# The first launch on a phone is refused — `invalid code signature, inadequate entitlements
# or its profile has not been explicitly trusted by the user` — until the certificate is
# trusted on the phone itself, under Settings, General, VPN & Device Management. That is a
# per-device consent no flag from this side can grant.
install_ios()
{
    TEAM=$(required_env SPFN_IOS_TEAM 'It is your Apple Developer team id; this repository holds none.')
    DEVICE=$(required_env SPFN_IOS_DEVICE \
        'It is the HARDWARE identifier xcodebuild takes: xcrun devicectl list devices --verbose.')
    UDID=$(required_env SPFN_IOS_DEVICECTL_UDID \
        'It is CoreDevice udid devicectl takes, which is NOT SPFN_IOS_DEVICE: xcrun devicectl list devices.')

    # The person's own inputs before the machine's, so a run on the wrong host says which
    # variable is missing rather than which tool is — the second is easy to work out and the
    # first is what this script exists to state (P6, P7).
    require xcodegen 'brew install xcodegen'
    require xcrun 'the Xcode command line tools'

    printf 'generating the project\n'
    xcodegen generate --spec "$ROOT/$IOS_SPEC"

    printf 'building and signing for the device\n'
    xcodebuild -project "$ROOT/$IOS_PROJECT" -scheme "$IOS_SCHEME" \
        -destination "platform=iOS,id=$DEVICE" \
        -derivedDataPath "$IOS_DERIVED" \
        -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$TEAM" \
        CODE_SIGN_STYLE=Automatic \
        CODE_SIGN_IDENTITY="Apple Development" \
        build

    printf 'installing\n'
    xcrun devicectl device install app --device "$UDID" \
        "$IOS_DERIVED/Build/Products/Debug-iphoneos/$IOS_SCHEME.app"

    printf 'launching\n'
    if [ -n "$FIXTURE" ]
    then
        # `--` separates devicectl's own options from the arguments the app is launched
        # with, and the app reads `-SPFN_UI_FIXTURE <cell>` out of its own argument array —
        # which is the shape Maestro's `launchApp: arguments:` produces on iOS, so a launch
        # from here and a launch from a cell reach the same code.
        xcrun devicectl device process launch --device "$UDID" --terminate-existing \
            "$APP_ID" -- "-$FIXTURE_KEY" "$FIXTURE"
    else
        xcrun devicectl device process launch --device "$UDID" --terminate-existing "$APP_ID"
    fi
}

# ---------------------------------------------------------------------------
# Android
# ---------------------------------------------------------------------------
#
# A wireless serial is `host:port` and goes in the same variable: `adb -s` takes either, and
# nothing here parses the value.
install_android()
{
    SERIAL=$(required_env SPFN_ANDROID_SERIAL \
        'It is the serial adb devices prints. A wireless device is host:port and goes here too.')

    require adb 'it ships in the Android SDK platform-tools'

    printf 'building\n'
    "$ROOT/gradlew" --console=plain -p "$ROOT" :example-compose:assembleDebug

    printf 'installing\n'
    adb -s "$SERIAL" install -r "$ROOT/$ANDROID_APK"

    printf 'launching\n'
    if [ -n "$FIXTURE" ]
    then
        adb -s "$SERIAL" shell am start -n "$APP_ID/.MainActivity" \
            --es "$FIXTURE_KEY" "$FIXTURE"
    else
        adb -s "$SERIAL" shell am start -n "$APP_ID/.MainActivity"
    fi
}

# ---------------------------------------------------------------------------
# --probe: the refusals, without a phone
# ---------------------------------------------------------------------------
#
# What is worth probing here is exactly what is not worth trusting: a script that reaches a
# phone through four environment variables fails in a way that reads like a cable problem
# when one of them is unset. So the probe unsets each in turn and requires the refusal to
# NAME it — and requires the message to carry the variable's name and not its value, which
# is the rule every message in this file is written to.
#
# It runs this script as a subprocess, because what it is checking is the refusal an
# invocation produces and not the behaviour of a function.
probe_missing()
{
    LABEL=$1
    PLATFORM=$2
    EXPECTED=$3
    shift 3
    if OUTPUT=$(env -u SPFN_IOS_TEAM -u SPFN_IOS_DEVICE -u SPFN_IOS_DEVICECTL_UDID \
        -u SPFN_ANDROID_SERIAL "$@" sh "$SELF" "$PLATFORM" 2>&1)
    then
        printf 'FAIL  %s: the run was accepted with no %s\n' "$LABEL" "$EXPECTED"
        PROBE_FAILURES=$((PROBE_FAILURES + 1))
    elif printf '%s' "$OUTPUT" | grep -q "$EXPECTED"
    then
        printf 'ok    %s: refused, naming %s\n' "$LABEL" "$EXPECTED"
    else
        printf 'FAIL  %s: refused without naming %s\n' "$LABEL" "$EXPECTED"
        printf '%s\n' "$OUTPUT" | sed 's/^/      /'
        PROBE_FAILURES=$((PROBE_FAILURES + 1))
    fi
    PROBE_CHECKS=$((PROBE_CHECKS + 1))
}

probe()
{
    PROBE_CHECKS=0
    PROBE_FAILURES=0
    printf 'SPFN Mobile — the showcase installer, probed\n\n'

    # Every variable but one is set per case, so the refusal named is the ONLY missing one
    # and the case is about that variable rather than about the order they are read in.
    probe_missing 'ios with no team' ios SPFN_IOS_TEAM SPFN_IOS_DEVICE=x SPFN_IOS_DEVICECTL_UDID=x
    probe_missing 'ios with no hardware id' ios SPFN_IOS_DEVICE SPFN_IOS_TEAM=x SPFN_IOS_DEVICECTL_UDID=x
    probe_missing 'ios with no devicectl udid' ios SPFN_IOS_DEVICECTL_UDID SPFN_IOS_TEAM=x SPFN_IOS_DEVICE=x
    probe_missing 'android with no serial' android SPFN_ANDROID_SERIAL

    # A platform this script does not know is a typo, and running the other one instead of
    # saying so would install on a phone the person did not name.
    if OUTPUT=$(sh "$SELF" iphone 2>&1)
    then
        printf 'FAIL  an unknown platform was accepted\n'
        PROBE_FAILURES=$((PROBE_FAILURES + 1))
    else
        printf 'ok    an unknown platform is refused rather than guessed\n'
    fi
    PROBE_CHECKS=$((PROBE_CHECKS + 1))

    printf '\n%s checks, %s failures\n' "$PROBE_CHECKS" "$PROBE_FAILURES"
    if [ "$PROBE_FAILURES" -ne 0 ]
    then
        printf 'RESULT: FAIL\n'
        exit 1
    fi
    printf 'RESULT: PASS\n'
}

# This script's own path, taken before anything could strand a relative $0: `--probe` runs
# it again as a subprocess, because a refusal is what one invocation produces.
SELF=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")

PLATFORM=${1:-}
FIXTURE=''

if [ -z "$PLATFORM" ]
then
    usage
    exit 1
fi
shift

while [ $# -gt 0 ]
do
    case "$1" in
        --fixture)
            [ $# -ge 2 ] || die '--fixture takes a cell id'
            FIXTURE=$2
            shift 2
            ;;
        *)
            usage
            die "unknown argument '$1'"
            ;;
    esac
done

case "$PLATFORM" in
    --probe) probe ;;
    ios) install_ios ;;
    android) install_android ;;
    *)
        usage
        die "install-device.sh does not know the platform '$PLATFORM'"
        ;;
esac
