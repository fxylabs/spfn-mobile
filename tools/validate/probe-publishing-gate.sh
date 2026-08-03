#!/bin/sh
# SPFN Mobile — proves the Step 5 publication gate refuses what it must refuse.
#
# Decision D3 (resolved 2026-08-03) allows a no-publish release candidate only: staging
# to a local directory, never a registry. The root build.gradle.kts enforces that as
# four refusals, and a gate is only worth its lines if each one bites:
#
#   1. a committed gradle.properties with spfn.publishing.enabled=true fails EVERY
#      build, even `gradlew help`, and even with a CLI override back to false —
#      the committed value is read from the file, not from the property chain;
#   2. enabling publication per-run without -Pspfn.staging.dir fails;
#   3. a relative staging path fails;
#   4. a staging path inside the repository fails.
#
# And one admission: a CLI override with an absolute staging path outside the tree
# must configure. A gate that also refuses the legal run is a different bug with the
# same green checkmark, so the probe holds both sides.
#
# Unlike tools/validate/validate.sh this probe needs the Gradle toolchain (and
# ANDROID_HOME), so it is a separate command with separate evidence:
#
#   ANDROID_HOME=~/Library/Android/sdk sh tools/validate/probe-publishing-gate.sh
#
# It temporarily rewrites gradle.properties. The original is preserved with `cp` and
# restored by a trap on every exit path.

set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

CHECKS=0
FAILURES=0

pass()
{
    CHECKS=$((CHECKS + 1))
    printf '  ok    %s\n' "$1"
}

fail()
{
    CHECKS=$((CHECKS + 1))
    FAILURES=$((FAILURES + 1))
    printf '  FAIL  %s\n' "$1"
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-gate-probe.XXXXXX")
BACKUP="$TMP/gradle.properties.original"
cp gradle.properties "$BACKUP"

# Idempotent: a signal trap and the EXIT trap may both reach it, and the second pass
# must find nothing left to redo — after the first pass the backup is gone with $TMP.
restore()
{
    if [ -f "$BACKUP" ]
    then
        cp "$BACKUP" gradle.properties
    fi
    rm -rf "$TMP"
}

# A signal trap that only restores would let execution resume mid-probe; exiting here
# keeps the interruption an interruption, with the conventional 128+N status. The EXIT
# trap is disarmed first so the exit cannot run restore a second time.
on_signal()
{
    trap '' EXIT INT TERM
    restore
    exit "$1"
}

trap restore EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

GRADLE="./gradlew --console=plain"

printf 'publication gate probe\n'

# --- 1. committed spfn.publishing.enabled=true fails every build -------------------
sed 's/^spfn.publishing.enabled=false$/spfn.publishing.enabled=true/' "$BACKUP" > gradle.properties
if grep -q '^spfn.publishing.enabled=true$' gradle.properties
then
    if $GRADLE help > "$TMP/probe1.log" 2>&1
    then
        fail 'a committed spfn.publishing.enabled=true was accepted by `gradlew help`'
    elif grep -q 'committed value must stay false' "$TMP/probe1.log"
    then
        pass 'a committed spfn.publishing.enabled=true fails every build'
    else
        fail 'the build failed, but not on the committed-value gate (see gate wording)'
    fi

    if $GRADLE help -Pspfn.publishing.enabled=false > "$TMP/probe1b.log" 2>&1
    then
        fail 'a CLI override back to false laundered a committed true value'
    else
        pass 'a committed true value fails even under -Pspfn.publishing.enabled=false'
    fi
else
    fail 'probe could not rewrite the committed property; gradle.properties drifted'
fi
cp "$BACKUP" gradle.properties

# --- 2..4. per-run enablement refuses a missing, relative or in-repo target --------
if $GRADLE help -Pspfn.publishing.enabled=true > "$TMP/probe2.log" 2>&1
then
    fail 'publication enabled with no spfn.staging.dir was accepted'
else
    pass 'publication enabled with no spfn.staging.dir fails'
fi

if $GRADLE help -Pspfn.publishing.enabled=true -Pspfn.staging.dir='relative/staging' \
    > "$TMP/probe3.log" 2>&1
then
    fail 'a relative spfn.staging.dir was accepted'
else
    pass 'a relative spfn.staging.dir fails'
fi

if $GRADLE help -Pspfn.publishing.enabled=true -Pspfn.staging.dir="$ROOT/build/staging-probe" \
    > "$TMP/probe4.log" 2>&1
then
    fail 'a staging directory inside the repository was accepted'
else
    if grep -q 'resolves inside this repository' "$TMP/probe4.log"
    then
        pass 'a staging directory inside the repository fails, on the in-repo gate'
    else
        fail 'the in-repo staging path failed, but not on the in-repo gate'
    fi
fi

# --- 5. the legal run configures ---------------------------------------------------
if $GRADLE help -Pspfn.publishing.enabled=true -Pspfn.staging.dir="$TMP/staging" \
    > "$TMP/probe5.log" 2>&1
then
    pass 'a CLI-enabled run with an absolute out-of-tree staging directory configures'
else
    fail 'the gate refuses the one run it exists to admit (see probe5.log wording)'
fi

# --- 6. restoration is real --------------------------------------------------------
if cmp -s gradle.properties "$BACKUP"
then
    pass 'gradle.properties is byte-identical to the original after the probe'
else
    fail 'gradle.properties drifted during the probe'
fi

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
