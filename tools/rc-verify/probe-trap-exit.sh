#!/bin/sh
# SPFN Mobile — proves the RC harness trap discipline under interruption.
#
# Review of the Step 5 harness found that a SIGINT/SIGTERM'd run cleaned up perfectly
# and then exited 0: inside a combined EXIT/INT/TERM trap, `$?` is the status of the
# last COMPLETED command, not the interruption. The fix routes signals through their
# own handlers that exit 128+N. A fix without a probe reverts silently, so this probe
# holds all of it:
#
#   1. a run killed with INT after it created the local tag exits 130, and leaves no
#      tag and no work directory behind;
#   2. the same for TERM and 143 — delivered twice, because the second signal must
#      land in a disarmed trap rather than re-entering the sweep;
#   3. a PRE-EXISTING tag survives a failing run: the sweep deletes only a tag the
#      run itself created (CREATED_TAG), interrupted or not.
#
# The killed runs are real harness runs, so this needs everything the harness needs
# (Swift toolchain, ANDROID_HOME, clean tree, no candidate tag) plus python3 for the
# signal-disposition wrapper documented at kill_probe. POSIX sh runs a trap only after
# the currently executing command completes, so each kill can take as long as the
# SwiftPM step it interrupts.
#
#   ANDROID_HOME=~/Library/Android/sdk sh tools/rc-verify/probe-trap-exit.sh

set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

VERSION=$(tr -d '[:space:]' < VERSION)
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

if [ -n "$(git status --porcelain)" ]
then
    printf 'probe cannot run: working tree is not clean\n' >&2
    exit 2
fi
if [ -n "$(git tag -l "$VERSION")" ]
then
    printf 'probe cannot run: tag %s already exists\n' "$VERSION" >&2
    exit 2
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-trap-probe.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

work_residue()
{
    find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'spfn-rc-work.*' -print 2>/dev/null | head -1
}

# Runs the harness, signals it once the tag exists, and checks the exit status and
# the residue. $1 = signal name, $2 = expected exit status, $3 = number of deliveries.
#
# The harness cannot be a plain `&` child of this shell: POSIX sets SIGINT to IGNORE
# in asynchronous children of a non-interactive shell, and a signal ignored at entry
# cannot be trapped, so the INT half of this probe would silently test nothing — the
# harness would run to completion and exit 0. A run interrupted from a terminal has a
# default SIGINT, which is exactly what the wrapper below restores: it spawns the
# harness with default dispositions, records the harness's own pid, and exits with
# the harness's exit status.
kill_probe()
{
    SIGNAL=$1
    EXPECTED=$2
    DELIVERIES=$3
    LOG="$TMP/harness-$SIGNAL.log"
    PIDFILE="$TMP/harness-$SIGNAL.pid"

    python3 - "$PIDFILE" > "$LOG" 2>&1 <<'PYEOF' &
import signal, subprocess, sys

# Python's own restore_signals covers SIGPIPE/SIGXFZ/SIGXFSZ only; the SIGINT-ignore
# this wrapper inherited as an async child must be undone by hand, in the child,
# before exec — or the harness still starts with SIGINT ignored and untrappable.
def reset_dispositions():
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)

process = subprocess.Popen(
    ["sh", "tools/rc-verify/rc-verify.sh"], preexec_fn=reset_dispositions
)
with open(sys.argv[1], "w") as handle:
    handle.write(str(process.pid))
sys.exit(process.wait())
PYEOF
    WRAPPER_PID=$!

    WAITED=0
    while [ -z "$(git tag -l "$VERSION")" ] && [ "$WAITED" -lt 120 ]
    do
        if ! kill -0 "$WRAPPER_PID" 2>/dev/null
        then
            break
        fi
        sleep 1
        WAITED=$((WAITED + 1))
    done

    if [ -z "$(git tag -l "$VERSION")" ] || [ ! -s "$PIDFILE" ]
    then
        fail "$SIGNAL probe: the harness never created the tag (see $LOG)"
        wait "$WRAPPER_PID" 2>/dev/null || true
        return
    fi

    HARNESS_PID=$(cat "$PIDFILE")
    DELIVERED=0
    while [ "$DELIVERED" -lt "$DELIVERIES" ]
    do
        kill "-$SIGNAL" "$HARNESS_PID" 2>/dev/null || true
        DELIVERED=$((DELIVERED + 1))
        [ "$DELIVERED" -lt "$DELIVERIES" ] && sleep 1
    done

    wait "$WRAPPER_PID"
    STATUS=$?

    if [ "$STATUS" = "$EXPECTED" ]
    then
        pass "a run killed with $SIGNAL (x$DELIVERIES) exits $EXPECTED"
    else
        fail "a run killed with $SIGNAL (x$DELIVERIES) exited $STATUS, expected $EXPECTED"
    fi

    if [ -z "$(git tag -l "$VERSION")" ]
    then
        pass "the $SIGNAL'd run removed its own tag"
    else
        fail "the $SIGNAL'd run left tag $VERSION behind"
        git tag -d "$VERSION" > /dev/null 2>&1 || true
    fi

    RESIDUE=$(work_residue)
    if [ -z "$RESIDUE" ]
    then
        pass "the $SIGNAL'd run left no work directory"
    else
        fail "the $SIGNAL'd run left $RESIDUE"
        rm -rf "${TMPDIR:-/tmp}"/spfn-rc-work.* 2>/dev/null || true
    fi

    # The interrupted run's output directory is probe litter, not candidate evidence.
    KILLED_OUT=$(sed -n 's/^output: \(.*\)$/\1/p' "$LOG" | head -1)
    case "$KILLED_OUT" in
        "${TMPDIR:-/tmp}"*spfn-rc-*) rm -rf "$KILLED_OUT" ;;
    esac
}

printf 'RC harness trap probe\n'

kill_probe INT 130 1
kill_probe TERM 143 2

# --- a pre-existing tag survives a failing run -------------------------------------
git tag "$VERSION"
if sh tools/rc-verify/rc-verify.sh > "$TMP/harness-pretag.log" 2>&1
then
    fail 'the harness accepted a pre-existing candidate tag'
else
    pass 'the harness refuses to run over a pre-existing candidate tag'
fi
if [ -n "$(git tag -l "$VERSION")" ]
then
    pass 'the pre-existing tag survived the refused run'
else
    fail 'the refused run deleted a tag it did not create'
fi
git tag -d "$VERSION" > /dev/null 2>&1 || true

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
