#!/bin/sh
# SPFN Mobile — proof that the preflight names the three failures rather than surviving them.
#
#   sh tools/harness/probe-preflight.sh
#
# On 2026-09-02 four device runs failed after fifteen to thirty minutes, all four on
# `Element not found: Id matching regex: btn_wipe`, and not one of them was about that
# button. tools/harness/README.md "Picking a target" names the three causes — something of
# the system's drawn over the app, a control below the fold and therefore absent from the
# accessibility tree (docs/IMPLEMENTATION-PITFALLS.md P25), and an ANR dialog — and
# run-harness.sh section 3b now reads one tree before the first flow to tell them apart.
#
# That section is new code guarding an expensive failure, which is exactly the code worth
# doubting. So this probe drives its judgments against fixtures holding one of each:
#
#   an empty package listing        -> not installed
#   a tree with android:id/aerr_    -> anr dialog
#   a tree missing one id           -> ids missing, and btn_wipe is named
#   a whole Android dump            -> pass
#   a whole iOS hierarchy           -> pass
#   an unreadable or empty tree     -> refused, and not as "all present"
#
# The functions are EXTRACTED from run-harness.sh rather than copied here. A copy would go
# on passing after the original changed, which is the failure mode a probe exists to
# prevent. tools/harness/probe-receipts.sh and probe-target-refusal.sh do the same.
#
# THIS VM HAS NO DEVICE, and that is the reason every judgment takes a file rather than a
# target: the dump is the input. What this probe cannot answer is what a real
# `maestro hierarchy` prints on a Mac — the iOS fixture below is the shape P25 records,
# `"resource-id" : "<id>"`, and a run against a simulator is what settles it.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUNNER="$ROOT/tools/harness/run-harness.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

for FUNCTION in preflight_first_viewport_ids preflight_installed preflight_running \
    preflight_anr_present preflight_ids_present
do
    sed -n "/^$FUNCTION()/,/^}/p" "$RUNNER" >> "$WORK/preflight.sh"
    if ! grep -q "^$FUNCTION()" "$WORK/preflight.sh"
    then
        printf 'FAIL  %s could not be extracted from run-harness.sh\n' "$FUNCTION"
        printf '      the probe cannot pass by failing to find what it tests\n'
        exit 1
    fi
done
# shellcheck source=/dev/null
. "$WORK/preflight.sh"

STATUS=0

result()
{
    if [ "$2" = "$3" ]
    then
        printf 'ok    %-34s -> %s\n' "$1" "$2"
    else
        printf 'FAIL  %-34s -> %s, expected %s\n' "$1" "$2" "$3"
        STATUS=1
    fi
}

# ---------------------------------------------------------------------------
printf 'SPFN Mobile — the preflight, probed\n\n'
printf 'the ids, read off the flows rather than written down\n\n'
# ---------------------------------------------------------------------------
# The set this preflight demands is derived, so the probe states what the derivation
# currently answers. Every name here is in `RunnerBlockTags` — the block the harness
# screen draws inside the first viewport — and none is a generated screen's
# `enterCode.…` or `reviewDevice.…`, which open OVER this screen and cannot be in a dump
# taken before the first tap. A preflight that demanded those would refuse a run that was
# about to pass.
IDS="$WORK/ids.txt"
preflight_first_viewport_ids "$ROOT/tools/harness/flows" > "$IDS"

result 'ids read from the flow files' "$(tr '\n' ' ' < "$IDS" | sed 's/ *$//')" \
    'btn_enroll btn_open_approve btn_resume btn_revoke btn_rotate btn_wipe'

# The cut is at the first `tapOn` for a reason, and this is the half that says so: what
# the d-cells tap after `btn_open_approve` belongs to a screen the dump cannot show.
result 'no generated-screen id is demanded' "$(grep -cE '^(enterCode|reviewDevice)\.' "$IDS" || true)" 0

# ---------------------------------------------------------------------------
printf '\nthe app is there, and it is the app on top\n\n'
# ---------------------------------------------------------------------------
APP_ID=xyz.superfunction.spfn.harness

installed()
{
    if preflight_installed "$1" "$APP_ID"
    then
        printf 'installed'
    else
        printf 'not installed'
    fi
}

: > "$WORK/apps-empty.txt"
printf 'package:%s\n' "$APP_ID" > "$WORK/apps-android.txt"
# `pm list packages <id>` filters by SUBSTRING, so the instrumentation package an
# androidTest run leaves behind answers the filter for an app that is not installed.
printf 'package:%s.test\n' "$APP_ID" > "$WORK/apps-android-test-only.txt"
cat > "$WORK/apps-ios.txt" <<IOSAPPS
    "$APP_ID" =     {
        ApplicationType = User;
        Bundle = "file:///Users/x/Library/Developer/CoreSimulator/Devices/x/data/Containers/Bundle/Application/x/SPFNHarness.app/";
        CFBundleIdentifier = "$APP_ID";
        CFBundleName = SPFNHarness;
    };
IOSAPPS

result 'an empty package listing' "$(installed "$WORK/apps-empty.txt")" 'not installed'
result 'a listing that was never written' "$(installed "$WORK/apps-nothing.txt")" 'not installed'
result 'only the instrumentation package' "$(installed "$WORK/apps-android-test-only.txt")" 'not installed'
result 'the Android package listing' "$(installed "$WORK/apps-android.txt")" 'installed'
result 'the iOS app listing' "$(installed "$WORK/apps-ios.txt")" 'installed'

running()
{
    if preflight_running "$1" "$APP_ID"
    then
        printf 'running'
    else
        printf 'not running'
    fi
}

# The launcher on top and the harness merely installed: the package is in the dump — every
# `dumpsys activity activities` names a lot of packages — and it is not the resumed one.
cat > "$WORK/state-launcher.txt" <<STATE
    topResumedActivity=ActivityRecord{1a2b3c u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}
    Recent #1: TaskRecord{4d5e6f #2 A=$APP_ID U=0}
STATE
cat > "$WORK/state-android.txt" <<STATE
    mResumedActivity: ActivityRecord{1a2b3c u0 $APP_ID/.HarnessActivity t9}
    topResumedActivity=ActivityRecord{1a2b3c u0 $APP_ID/.HarnessActivity t9}
STATE
cat > "$WORK/state-ios.txt" <<STATE
$APP_ID: 41207
41207	0	UIKitApplication:$APP_ID[0x9f1c][rb-legacy]
STATE

result 'nothing dumped at all' "$(running "$WORK/state-empty.txt")" 'not running'
result 'the launcher resumed, harness behind it' "$(running "$WORK/state-launcher.txt")" 'not running'
result 'the harness resumed on Android' "$(running "$WORK/state-android.txt")" 'running'
result 'the harness launched on iOS' "$(running "$WORK/state-ios.txt")" 'running'

# ---------------------------------------------------------------------------
printf '\nan ANR dialog, which is neither the app nor the flow\n\n'
# ---------------------------------------------------------------------------
# What `uiautomator dump` writes while the dialog is up: the system's nodes, and nothing
# of the app at all. Every `tapOn` then reports the id it wanted, about a screen that is
# up and correct behind it.
cat > "$WORK/tree-anr.xml" <<'ANR'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="android:id/parentPanel" class="android.widget.LinearLayout" bounds="[42,780][1038,1140]">
    <node index="0" text="System UI isn't responding" resource-id="android:id/aerr_title" class="android.widget.TextView" bounds="[84,822][996,900]"/>
    <node index="1" text="Close app" resource-id="android:id/aerr_close" class="android.widget.Button" bounds="[84,1020][540,1116]"/>
    <node index="2" text="Wait" resource-id="android:id/aerr_wait" class="android.widget.Button" bounds="[540,1020][996,1116]"/>
  </node>
</hierarchy>
ANR

anr()
{
    if preflight_anr_present "$1"
    then
        printf 'anr dialog'
    else
        printf 'anr none'
    fi
}

result 'a tree holding android:id/aerr_' "$(anr "$WORK/tree-anr.xml")" 'anr dialog'

# ---------------------------------------------------------------------------
printf '\nthe ids the first flow taps, against the tree the runner will read\n\n'
# ---------------------------------------------------------------------------
# An Android dump of the harness screen, trimmed to the runner grid. `btn_wipe` carries
# the packaged spelling and the rest carry the bare one, because a Compose test tag
# published through `testTagsAsResourceId` arrives bare and a View's arrives as
# `<package>:id/<name>` — both are the same id to a Maestro selector and both have to be
# the same id here.
android_tree()
{
    cat <<TREE
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="state=unenrolled" resource-id="" class="android.widget.TextView" bounds="[0,120][1080,186]"/>
  <node index="1" text="busy=ready" resource-id="" class="android.widget.TextView" bounds="[0,186][1080,252]"/>
$(for TAG in "$@"
do
    printf '  <node index="2" text="%s" resource-id="%s" class="android.widget.Button" bounds="[0,300][540,420]"/>\n' \
        "$TAG" "$TAG"
done)
</hierarchy>
TREE
}

# `maestro hierarchy` on iOS, in the shape docs/IMPLEMENTATION-PITFALLS.md P25 records:
# JSON, whose nodes carry `"resource-id"`. Marked in the README as the one line of this
# work a Mac has to confirm — nothing on this VM can print a real one.
ios_tree()
{
    printf '{\n  "attributes": { "resource-id" : "", "text" : "" },\n  "children": [\n'
    SEPARATOR='    '
    for TAG in "$@"
    do
        printf '%s{ "attributes": { "resource-id" : "%s", "text" : "%s", "bounds" : "[0,300][540,420]" }, "children": [] }' \
            "$SEPARATOR" "$TAG" "$TAG"
        SEPARATOR=',
    '
    done
    printf '\n  ]\n}\n'
}

ids()
{
    OUTPUT=$(preflight_ids_present "$1" "$IDS" 2>&1) && CODE=0 || CODE=$?
    case "$CODE" in
        0) printf 'all present' ;;
        1) printf 'missing %s' "$(printf '%s' "$OUTPUT" | tr '\n' ' ' | sed 's/ *$//')" ;;
        *) printf 'refused' ;;
    esac
}

ALL='btn_enroll btn_open_approve btn_resume btn_revoke btn_rotate btn_wipe'

# shellcheck disable=SC2086
android_tree $ALL > "$WORK/tree-android.xml"
# shellcheck disable=SC2086
ios_tree $ALL > "$WORK/tree-ios.json"
android_tree btn_enroll btn_open_approve btn_resume btn_revoke btn_rotate > "$WORK/tree-no-wipe.xml"
android_tree > "$WORK/tree-readouts-only.xml"
printf '<hierarchy rotation="0"><node index="0"\n' > "$WORK/tree-truncated.xml"
: > "$WORK/tree-empty.xml"

result 'a whole Android dump' "$(ids "$WORK/tree-android.xml")" 'all present'
result 'a whole iOS hierarchy' "$(ids "$WORK/tree-ios.json")" 'all present'
result 'the wipe button below the fold' "$(ids "$WORK/tree-no-wipe.xml")" 'missing btn_wipe'
result 'an alert over the whole grid' "$(ids "$WORK/tree-readouts-only.xml")" \
    "missing $ALL"
result 'the ANR dialog, read for ids' "$(ids "$WORK/tree-anr.xml")" "missing $ALL"
result 'a dump that was cut short' "$(ids "$WORK/tree-truncated.xml")" 'refused'
result 'an empty dump' "$(ids "$WORK/tree-empty.xml")" 'refused'
result 'a dump that was never written' "$(ids "$WORK/tree-nothing.xml")" 'refused'

# The floor under all of it: an empty id list must be a refusal and never "all present".
# A preflight that read no ids would pass every screen ever shown to it, including a blank
# one (docs/IMPLEMENTATION-PITFALLS.md P7).
: > "$WORK/ids-empty.txt"
if preflight_ids_present "$WORK/tree-android.xml" "$WORK/ids-empty.txt" > /dev/null 2>&1
then
    result 'an empty id list' 'all present' 'refused'
else
    result 'an empty id list' 'refused' 'refused'
fi

printf '\n'
if [ "$STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
