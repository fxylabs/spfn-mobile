#!/bin/sh
# SPFN Mobile — proof that tools/verify-server/run.sh refuses what it says it refuses.
#
#   sh tools/verify-server/probe-refusals.sh
#
# run.sh exists to stop a run that would report real-server coverage it did not have.
# Every one of its guards is therefore a claim, and a guard nobody exercises is a claim
# nobody checked. This probe builds a fixture app for each way the setup can be wrong
# and asserts that run.sh exits non-zero for that reason and no other.
#
# It asserts the reason, not only the exit code. A guard that fires for the wrong reason
# passes an exit-code check while protecting nothing: the version comparison could be
# refusing because it cannot find a file, and the run would still be red.
#
# The last case is the one that matters most. It sets every earlier condition correctly
# and breaks only the database, because that is the state a real machine reaches — the
# app installed, the pins agreeing, and PostgreSQL not up yet. If that case ever passed,
# a green run would mean nothing.
#
# Requires: node, curl, nc — the same tools run.sh requires.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

RUNNER=tools/verify-server/run.sh
APP_PACKAGE=@spfn/auth
PINNED_VERSION=0.0.0-probe.1

WORK=$(mktemp -d)
FAILURES=0

cleanup()
{
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

fail()
{
    FAILURES=$((FAILURES + 1))
    printf 'FAIL  %s\n' "$1"
}

pass()
{
    printf 'ok    %s\n' "$1"
}

# A port nothing is listening on, used to make the database unreachable on purpose.
# Chosen from the dynamic range and confirmed closed rather than assumed: a probe that
# quietly ran against something else would prove the opposite of what it claims.
CLOSED_PORT=59517
while nc -z 127.0.0.1 "$CLOSED_PORT" > /dev/null 2>&1
do
    CLOSED_PORT=$((CLOSED_PORT + 1))
    if [ "$CLOSED_PORT" -gt 59600 ]
    then
        printf 'probe-refusals.sh could not find a closed port to test against\n' >&2
        exit 1
    fi
done

# A lock that names a published version for the package run.sh compares.
write_lock_with_pin()
{
    cat > "$1" <<EOF
{
  "publishedPackages": {
    "$APP_PACKAGE": "$PINNED_VERSION"
  }
}
EOF
}

# A lock shaped like the pin as it stands before the re-pin adds the field.
write_lock_without_pin()
{
    printf '{ "contract": { "version": "0.4.1" } }\n' > "$1"
}

# An app directory, built up to whichever step the case under test is meant to reach.
#   $1 the directory
#   $2 the installed @spfn/auth version, or '' to leave it uninstalled
#   $3 the DATABASE_URL to write into .env.server, or '' to leave the file out
make_app()
{
    mkdir -p "$1"
    printf '{ "name": "probe-verify-app", "private": true }\n' > "$1/package.json"

    if [ -n "$2" ]
    then
        mkdir -p "$1/node_modules/$APP_PACKAGE"
        printf '{ "name": "%s", "version": "%s" }\n' "$APP_PACKAGE" "$2" \
            > "$1/node_modules/$APP_PACKAGE/package.json"
    fi

    if [ -n "$3" ]
    then
        printf 'DATABASE_URL=%s\n' "$3" > "$1/.env.server"
    fi
}

# Runs the runner in check-only mode and asserts it refused for the stated reason.
#   $1 the case name
#   $2 the app path to hand it
#   $3 the lock path to hand it
#   $4 a fixed string the output must carry
expect_refusal()
{
    OUT="$WORK/out.txt"

    set +e
    SPFN_VERIFY_CHECK_ONLY=1 \
        SPFN_VERIFY_APP="$2" \
        SPFN_VERIFY_LOCK="$3" \
        sh "$RUNNER" > "$OUT" 2>&1
    STATUS=$?
    set -e

    if [ "$STATUS" -eq 0 ]
    then
        fail "$1: run.sh exited 0; this setup must never be allowed to run"
        return
    fi

    if ! grep -qF "$4" "$OUT"
    then
        fail "$1: refused with exit $STATUS but not for the stated reason"
        printf '      wanted: %s\n' "$4"
        sed 's/^/      /' "$OUT"
        return
    fi

    pass "$1"
}

printf 'SPFN Mobile — verify-server refusal probe\n'
printf 'root: %s\n\n' "$ROOT"

LOCK_WITH_PIN=$WORK/lock-with-pin.json
LOCK_WITHOUT_PIN=$WORK/lock-without-pin.json
write_lock_with_pin "$LOCK_WITH_PIN"
write_lock_without_pin "$LOCK_WITHOUT_PIN"

GOOD_DB=postgresql://probe:secret@127.0.0.1:$CLOSED_PORT/probe

# 1. The app is not there at all. The refusal has to name the scaffold command, because
#    a session that meets this message must not have to search for what to do next.
expect_refusal 'an absent app is refused' \
    "$WORK/nowhere" "$LOCK_WITH_PIN" 'npx spfn create spfn-verify-app'

# 2. A directory that is not an app. Without this, a stray empty directory at the
#    conventional path would take the run past the discovery layer.
mkdir -p "$WORK/empty"
expect_refusal 'a directory with no package.json is refused' \
    "$WORK/empty" "$LOCK_WITH_PIN" 'is not an SPFN app'

# 3. A lock with no published version. Fails closed on purpose: before the re-pin there
#    is nothing to compare against, and an uncomparable pin must not read as a matching
#    one.
make_app "$WORK/no-pin" "$PINNED_VERSION" "$GOOD_DB"
expect_refusal 'a lock naming no published version is refused' \
    "$WORK/no-pin" "$LOCK_WITHOUT_PIN" 'names no publishedPackages'

# 3b. A lock that will not parse. Reported as its own problem: a reader sent looking for
#     a missing field in a file that is actually malformed fixes neither.
LOCK_BROKEN=$WORK/lock-broken.json
printf '{ "publishedPackages": \n' > "$LOCK_BROKEN"
make_app "$WORK/broken-lock" "$PINNED_VERSION" "$GOOD_DB"
expect_refusal 'an unparseable lock is refused as unreadable, not as absent' \
    "$WORK/broken-lock" "$LOCK_BROKEN" 'is not readable JSON'

# 4. The package is not installed.
make_app "$WORK/uninstalled" '' "$GOOD_DB"
expect_refusal 'an app without the package installed is refused' \
    "$WORK/uninstalled" "$LOCK_WITH_PIN" 'is not installed in'

# 4b. The installed manifest will not parse. The same distinction on the other side of
#     the comparison — a half-written package.json is a broken install, not a drift.
make_app "$WORK/broken-manifest" "$PINNED_VERSION" "$GOOD_DB"
printf '{ "name": "%s", "version": \n' "$APP_PACKAGE" \
    > "$WORK/broken-manifest/node_modules/$APP_PACKAGE/package.json"
expect_refusal 'an unparseable installed manifest is refused as unreadable' \
    "$WORK/broken-manifest" "$LOCK_WITH_PIN" 'is not readable JSON'

# 5. The installed version is not the pinned one. This is the drift the scoping brief
#    named: two pins that disagree make a passing run meaningless.
make_app "$WORK/drifted" '0.0.0-probe.2' "$GOOD_DB"
expect_refusal 'a version other than the pinned one is refused' \
    "$WORK/drifted" "$LOCK_WITH_PIN" 'the contract lock names'

# 6. No .env.server, so no database is configured.
make_app "$WORK/no-env" "$PINNED_VERSION" ''
expect_refusal 'an app with no .env.server is refused' \
    "$WORK/no-env" "$LOCK_WITH_PIN" 'is missing, so no database is configured'

# 7. An .env.server that names no DATABASE_URL.
make_app "$WORK/no-url" "$PINNED_VERSION" "$GOOD_DB"
printf 'REDIS_URL=redis://127.0.0.1:6379\n' > "$WORK/no-url/.env.server"
expect_refusal 'an .env.server naming no DATABASE_URL is refused' \
    "$WORK/no-url" "$LOCK_WITH_PIN" 'names no DATABASE_URL'

# 8. Everything right except the database. The state a real machine reaches, and the one
#    refusal whose absence would make every green run meaningless.
make_app "$WORK/no-db" "$PINNED_VERSION" "$GOOD_DB"
expect_refusal 'an unreachable database is refused' \
    "$WORK/no-db" "$LOCK_WITH_PIN" "nothing is listening on 127.0.0.1:$CLOSED_PORT"

# 9. The same refusal must never mention the password the URL carried. run.sh reads
#    DATABASE_URL into a variable and shows only host and port; this asserts it.
OUT="$WORK/secret-check.txt"
set +e
SPFN_VERIFY_CHECK_ONLY=1 \
    SPFN_VERIFY_APP="$WORK/no-db" \
    SPFN_VERIFY_LOCK="$LOCK_WITH_PIN" \
    sh "$RUNNER" > "$OUT" 2>&1
set -e

if grep -qF 'secret' "$OUT"
then
    fail 'the refusal printed the database password'
    sed 's/^/      /' "$OUT"
else
    pass 'the refusal names host and port and never the credential'
fi

# 10. The checks pass together when nothing is wrong. Without this the probe would be
#     satisfied by a runner that refused everything, which protects nothing and blocks
#     every real run.
#
#     The listener is opened with the same nc the probe already requires, in both of the
#     spellings that exist: BSD nc takes `-l <port>`, GNU netcat takes `-l -p <port>`.
#     Each is tried and then confirmed with a connect rather than assumed to have worked,
#     because an unopened listener would turn this case into a false failure.
#
#     `-k` matters more than it looks. Without it the listener serves one connection and
#     exits, and the connection it would serve is this probe's own readiness check — so
#     the port would be closed again by the time run.sh looked at it, and the case would
#     fail for a reason that has nothing to do with run.sh.
LISTENER_PORT=$CLOSED_PORT
LISTENER_PID=''

for spelling in bsd gnu
do
    if [ "$spelling" = bsd ]
    then
        nc -k -l "$LISTENER_PORT" < /dev/null > /dev/null 2>&1 &
    else
        nc -k -l -p "$LISTENER_PORT" < /dev/null > /dev/null 2>&1 &
    fi
    CANDIDATE=$!

    ATTEMPT=0
    while [ "$ATTEMPT" -lt 30 ] && ! nc -z 127.0.0.1 "$LISTENER_PORT" > /dev/null 2>&1
    do
        ATTEMPT=$((ATTEMPT + 1))
        sleep 0.1
    done

    if nc -z 127.0.0.1 "$LISTENER_PORT" > /dev/null 2>&1
    then
        LISTENER_PID=$CANDIDATE
        break
    fi

    kill "$CANDIDATE" 2> /dev/null || true
    wait "$CANDIDATE" 2> /dev/null || true
done

if [ -n "$LISTENER_PID" ]
then
    set +e
    SPFN_VERIFY_CHECK_ONLY=1 \
        SPFN_VERIFY_APP="$WORK/no-db" \
        SPFN_VERIFY_LOCK="$LOCK_WITH_PIN" \
        sh "$RUNNER" > "$WORK/green.txt" 2>&1
    GREEN_STATUS=$?
    set -e

    if [ "$GREEN_STATUS" -eq 0 ]
    then
        pass 'a correct setup passes every check'
    else
        fail "a correct setup was refused with exit $GREEN_STATUS"
        sed 's/^/      /' "$WORK/green.txt"
    fi
else
    fail 'the probe could not open a listener, so the passing case was not proved'
fi

kill "$LISTENER_PID" 2> /dev/null || true
wait "$LISTENER_PID" 2> /dev/null || true

printf '\n'
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL (%s)\n' "$FAILURES"
exit 1
