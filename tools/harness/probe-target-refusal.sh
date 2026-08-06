#!/bin/sh
# SPFN Mobile — proof that a physical iPhone cannot be mistaken for a simulator.
#
#   sh tools/harness/probe-target-refusal.sh
#
# Maestro ships no driver for a real iOS device: in maestro-ios.jar, every method of
# `ios/devicectl/DeviceControlIOSDevice` throws NotImplementedError except uninstall, stop
# and setPermissions. So run-harness.sh refuses an iPhone at the target, and that refusal
# is the only thing between a caller who names one and a run that fails eight minutes
# later with an unrelated message.
#
# The refusal is new code guarding a safety, which is exactly the code worth doubting, so
# this probe drives `names_a_simulator` from the runner itself against a fixture holding
# both kinds of udid, and checks it fails closed:
#
#   a booted simulator udid   -> a simulator
#   a shut-down simulator udid -> a simulator; being off is not being absent
#   a physical device udid    -> not a simulator
#   a udid that is a prefix of a simulator's -> not a simulator
#   nothing on stdin          -> not a simulator
#
# The function is EXTRACTED from run-harness.sh rather than copied here. A copy would go
# on passing after the original changed, which is the failure mode a probe exists to
# prevent. tools/harness/probe-receipts.sh does the same for receipt derivation.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$ROOT/tools/harness/run-harness.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

sed -n '/^names_a_simulator()/,/^}/p' "$RUNNER" > "$WORK/names_a_simulator.sh"
if [ ! -s "$WORK/names_a_simulator.sh" ]
then
    printf 'FAIL  names_a_simulator could not be extracted from run-harness.sh\n'
    printf '      the probe cannot pass by failing to find what it tests\n'
    exit 1
fi
# shellcheck source=/dev/null
. "$WORK/names_a_simulator.sh"

# The shape `xcrun simctl list devices -j` really prints, trimmed to the fields the
# extraction reads. A physical device never appears in it at all — that is the whole
# basis of the check — so the device udid below is present only in this comment's sense:
# it is what a caller types, not what the list holds.
cat > "$WORK/devices.json" <<'DEVICES'
{
  "devices" : {
    "com.apple.CoreSimulator.SimRuntime.iOS-18-1" : [
      {
        "name" : "iPhone 16 Pro",
        "udid" : "231BDEDA-940F-4BFD-AE9D-365936A8D661",
        "state" : "Booted"
      },
      {
        "name" : "iPhone SE (3rd generation)",
        "udid" : "6C1A0B77-2E4D-4F91-9A3C-77B0E5D21A48",
        "state" : "Shutdown"
      }
    ]
  }
}
DEVICES

STATUS=0

check()
{
    if names_a_simulator "$2" < "$1"
    then
        ACTUAL=simulator
    else
        ACTUAL=refused
    fi

    if [ "$ACTUAL" = "$3" ]
    then
        printf 'ok    %-40s -> %s\n' "$4" "$ACTUAL"
    else
        printf 'FAIL  %-40s -> %s, expected %s\n' "$4" "$ACTUAL" "$3"
        STATUS=1
    fi
}

printf 'SPFN Mobile — the iOS target check, probed\n\n'

check "$WORK/devices.json" 231BDEDA-940F-4BFD-AE9D-365936A8D661 simulator 'a booted simulator'
check "$WORK/devices.json" 6C1A0B77-2E4D-4F91-9A3C-77B0E5D21A48 simulator 'a shut-down simulator'
check "$WORK/devices.json" 00008130-001C70261489001C refused 'a physical iPhone'
check "$WORK/devices.json" 231BDEDA-940F refused 'a prefix of a simulator udid'
check /dev/null 231BDEDA-940F-4BFD-AE9D-365936A8D661 refused 'an unreadable device list'

printf '\n'
if [ "$STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
