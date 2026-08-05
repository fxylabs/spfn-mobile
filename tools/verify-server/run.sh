#!/bin/sh
# SPFN Mobile — the run against a real SPFN server.
#
#   sh tools/verify-server/run.sh
#
# Everything else this repository proves, it proves against `tools/reference-server`:
# a Kotlin server built from one reading of the contract, checked by two SDKs built
# from the same reading. That agreement is real, and it is also self-verification. It
# cannot catch a contract this repository reads correctly and no SPFN server implements.
#
# This script closes that gap by pointing the SDK at a scaffolded SPFN app running the
# published `@spfn/auth` on a real PostgreSQL — the same server an SPFN application
# deploys. The app lives OUTSIDE this repository, at `workspaces/spfn-verify-app` by
# default, because a gitignored in-repo scaffold answers no discovery question and its
# node_modules and .env break three whole-tree checks in tools/validate/validate.sh.
# Decision 01kz6nq4ga records that in full.
#
#   SPFN_VERIFY_APP=/somewhere/else sh tools/verify-server/run.sh
#
# The path is a convention, not a contract: a machine that keeps the app elsewhere
# says so with the variable rather than editing a tracked file.
#
# ---------------------------------------------------------------------------
# What this script refuses to do
# ---------------------------------------------------------------------------
#
# It never falls back to the reference server. A run that checked the local fake while
# reporting real-server coverage would be the most expensive kind of green there is, so
# every reason the real server cannot be used is an exit, never a substitution:
#
#   - the app is not there            -> print the scaffold command, exit non-zero
#   - the lock names no package pin   -> exit; the contract pin predates this runner
#   - the app installed another pin   -> exit; two pins that drift make a pass meaningless
#   - PostgreSQL is not reachable     -> exit; a real server needs a real database
#
# It also never prints a secret. The app's DATABASE_URL carries a password, so the URL
# is read into a variable and only its host and port are ever shown — those are what a
# reader needs to act on the failure, and neither is a credential.
#
# Requires: node and curl, a package manager the app declares, and nc for the database
# probe. A prober that is missing is a refusal too: an unrun check must never read as
# a passed one.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

READY_RELATIVE=.spfn/server-ready
APP_PACKAGE=@spfn/auth

# The tracked lock, unless a caller names another one. The seam exists so
# tools/verify-server/probe-refusals.sh can drive the version refusals against fixture
# locks instead of a second copy of the comparison. It cannot be used quietly: section 2
# prints the path it read on every run, so a substituted lock says so in the output.
DEFAULT_LOCK=Contracts/upstream.lock.json
LOCK=${SPFN_VERIFY_LOCK-$DEFAULT_LOCK}

# Every case the real-server suite is required to have run. Read as "platform-case".
# The letters are the case table: enrolment by login, a proven call under the enrolled
# key, rotation, a proven call under the new key while the replaced one is refused, and
# revocation. There is no `/control` surface here and no fake identity — every one of
# these is an operation the contract declares and a deployed SPFN server serves.
EXPECTED_RECEIPTS='swift-r1 swift-r2 swift-r3 swift-r4 swift-r5'

# Stops after the checks, without starting anything. This is how
# tools/verify-server/probe-refusals.sh exercises the refusals: the probe drives the
# real code path rather than a second copy of it that could drift out of agreement.
CHECK_ONLY=${SPFN_VERIFY_CHECK_ONLY-}

WORK=$(mktemp -d)
SERVER_LOG="$WORK/verify-server.log"
SWIFT_LOG="$WORK/swift-verify.log"
RECEIPTS="$WORK/receipts"
SERVER_PID=''

mkdir -p "$RECEIPTS"

# Stops the app whatever happens next, including a failure between here and the end.
# SERVER_PID is only ever set for a server this script started.
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
        fail "run.sh needs $1 and cannot find it"
        fail "$2"
        exit 1
    fi
}

# Reads one string out of a JSON file without a sed pattern that would match the same
# key name at any depth. The lock warns about exactly that hazard in its own notes.
#
# Two failures, two exit codes. A file that will not parse and a key that is not there
# are different problems with different fixes, and collapsing them would send a reader
# looking for a missing field in a file that is actually malformed.
JSON_UNREADABLE=3
JSON_ABSENT=4

json_string()
{
    node -e '
        const fs = require("node:fs");
        const [file, ...path] = process.argv.slice(1);
        let value;
        try { value = JSON.parse(fs.readFileSync(file, "utf8")); }
        catch { process.exit(3); }
        for (const key of path)
        {
            if (value === null || typeof value !== "object") { process.exit(4); }
            value = value[key];
        }
        if (typeof value !== "string" || value === "") { process.exit(4); }
        process.stdout.write(value);
    ' "$@"
}

printf 'SPFN Mobile — the run against a real SPFN server\n'
printf 'root: %s\n\n' "$ROOT"

require node 'the verify app is a Node project, so its package metadata is read with node'
require curl 'the app is probed over HTTP before any suite runs'

# ---------------------------------------------------------------------------
printf '1. the verify app\n'
# ---------------------------------------------------------------------------
APP_DIR=${SPFN_VERIFY_APP-$ROOT/../spfn-verify-app}

if [ ! -d "$APP_DIR" ]
then
    fail "no verify app at $APP_DIR"
    fail 'this run stops here rather than falling back to tools/reference-server:'
    fail 'the reference server is this repository checking its own reading of the contract.'
    printf '\n'
    printf 'To create it, from the directory that should hold it:\n\n'
    printf '    npx spfn create spfn-verify-app\n\n'
    printf 'then pin the packages the contract lock names (section 2 prints them),\n'
    printf 'and see tools/verify-server/README.md for the rest of the setup.\n\n'
    printf 'If the app is somewhere else, name it:\n\n'
    printf '    SPFN_VERIFY_APP=/path/to/app sh tools/verify-server/run.sh\n'
    exit 1
fi

# Resolved so every later message names one path rather than a path with `..` in it.
APP_DIR=$(CDPATH= cd -- "$APP_DIR" && pwd)

if [ ! -f "$APP_DIR/package.json" ]
then
    fail "$APP_DIR holds no package.json, so it is not an SPFN app"
    exit 1
fi

pass "verify app at $APP_DIR"

# ---------------------------------------------------------------------------
printf '\n2. the pinned packages\n'
# ---------------------------------------------------------------------------
if [ "$LOCK" != "$DEFAULT_LOCK" ]
then
    printf '  --    reading %s instead of the tracked %s\n' "$LOCK" "$DEFAULT_LOCK"
fi

if [ ! -f "$LOCK" ]
then
    fail "$LOCK is not a file"
    exit 1
fi

# The contract lock names a primitives commit. A commit is not something npm can
# install, so the lock also names the versions published from it, and that is what the
# app's installed tree is compared against. Equality, not a floor: a newer package may
# serve a contract this SDK was not generated from, and the point of the run is that
# both ends read the same one.
set +e
EXPECTED_VERSION=$(json_string "$LOCK" publishedPackages "$APP_PACKAGE")
LOCK_READ=$?
set -e

if [ "$LOCK_READ" -eq "$JSON_UNREADABLE" ]
then
    fail "$LOCK is not readable JSON"
    exit 1
fi

if [ "$LOCK_READ" -ne 0 ]
then
    fail "$LOCK names no publishedPackages.$APP_PACKAGE"
    fail 'the contract pin predates this runner, so there is nothing to compare against.'
    fail 'Re-pin the contract first — that change adds the field (work unit w-pq7t5).'
    exit 1
fi

INSTALLED_MANIFEST=$APP_DIR/node_modules/$APP_PACKAGE/package.json

if [ ! -f "$INSTALLED_MANIFEST" ]
then
    fail "$APP_PACKAGE is not installed in $APP_DIR"
    fail "install the app's dependencies, pinning $APP_PACKAGE@$EXPECTED_VERSION"
    exit 1
fi

set +e
INSTALLED_VERSION=$(json_string "$INSTALLED_MANIFEST" version)
MANIFEST_READ=$?
set -e

if [ "$MANIFEST_READ" -eq "$JSON_UNREADABLE" ]
then
    fail "$INSTALLED_MANIFEST is not readable JSON"
    exit 1
fi

if [ "$MANIFEST_READ" -ne 0 ]
then
    fail "$INSTALLED_MANIFEST declares no version"
    exit 1
fi

if [ "$INSTALLED_VERSION" != "$EXPECTED_VERSION" ]
then
    fail "the app installed $APP_PACKAGE@$INSTALLED_VERSION"
    fail "the contract lock names $APP_PACKAGE@$EXPECTED_VERSION"
    fail 'two pins that drift apart make a passing run meaningless, so this is a failure'
    fail 'and not a warning. Re-pin the app, or re-pin the contract — not neither.'
    exit 1
fi

pass "$APP_PACKAGE@$INSTALLED_VERSION matches the contract lock"

# ---------------------------------------------------------------------------
printf '\n3. PostgreSQL\n'
# ---------------------------------------------------------------------------
# A real server needs a real database, and the answer to an absent one is an exit with
# the reason. Starting the app's docker compose here would be this script reaching into
# state it does not own; reporting a pass without a database would be worse.
ENV_SERVER=$APP_DIR/.env.server

if [ ! -f "$ENV_SERVER" ]
then
    fail "$APP_DIR/.env.server is missing, so no database is configured"
    fail 'the scaffold generates it; see tools/verify-server/README.md'
    exit 1
fi

# Read, never printed. The line may be quoted either way and may carry a password.
DATABASE_URL=$(sed -n 's/^[[:space:]]*DATABASE_URL[[:space:]]*=[[:space:]]*//p' "$ENV_SERVER" \
    | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//" | head -1)

if [ -z "$DATABASE_URL" ]
then
    fail "$ENV_SERVER names no DATABASE_URL"
    exit 1
fi

# Scheme and any credentials dropped before anything is shown. What is left is a host
# and a port, which a reader needs in order to act and which disclose nothing.
DB_AUTHORITY=$(printf '%s' "$DATABASE_URL" \
    | sed -e 's|^[a-zA-Z][a-zA-Z0-9+.-]*://||' -e 's|^[^@/]*@||' -e 's|[/?].*$||')
DB_HOST=$(printf '%s' "$DB_AUTHORITY" | sed 's/:[0-9]*$//')
DB_PORT=$(printf '%s' "$DB_AUTHORITY" | sed -n 's/.*:\([0-9][0-9]*\)$/\1/p')

if [ -z "$DB_HOST" ]
then
    fail 'DATABASE_URL carries no host this script can read'
    exit 1
fi

if [ -z "$DB_PORT" ]
then
    DB_PORT=5432
fi

require nc 'the database is probed with nc, and an unrun check must not read as a passed one'

if ! nc -z "$DB_HOST" "$DB_PORT" > /dev/null 2>&1
then
    fail "nothing is listening on $DB_HOST:$DB_PORT"
    fail 'the verify app needs its database up before this script runs:'
    fail "    cd $APP_DIR && docker compose up -d"
    fail 'this run will not continue against the reference server instead.'
    exit 1
fi

pass "PostgreSQL answering at $DB_HOST:$DB_PORT"

if [ "$CHECK_ONLY" = "1" ]
then
    printf '\nSPFN_VERIFY_CHECK_ONLY=1: the checks passed and nothing was started.\n'
    exit 0
fi

# ---------------------------------------------------------------------------
printf '\n4. starting the app\n'
# ---------------------------------------------------------------------------
# `spfn dev --server-only` runs the SPFN API without Next.js and writes the port it
# actually bound to into .spfn/server-ready. The port is read rather than assumed: a
# fixed port is either wrong on a machine already running the app or silently right
# for the wrong process.
if [ -f "$APP_DIR/pnpm-lock.yaml" ]
then
    PM=pnpm
elif [ -f "$APP_DIR/yarn.lock" ]
then
    PM=yarn
elif [ -f "$APP_DIR/bun.lockb" ]
then
    PM=bun
else
    PM=npm
fi

require "$PM" "the app declares a $PM lockfile, so $PM is what installs and runs it"

READY_FILE=$APP_DIR/$READY_RELATIVE
rm -f "$READY_FILE"

(cd "$APP_DIR" && "$PM" run spfn:server) > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

ATTEMPT=0
while [ "$ATTEMPT" -lt 900 ]
do
    if [ -f "$READY_FILE" ]
    then
        break
    fi
    if ! kill -0 "$SERVER_PID" 2> /dev/null
    then
        fail 'the verify app exited before it was ready'
        sed 's/^/      /' "$SERVER_LOG"
        exit 1
    fi
    ATTEMPT=$((ATTEMPT + 1))
    sleep 0.1
done

if [ ! -f "$READY_FILE" ]
then
    fail "the verify app did not report a port within 90 seconds"
    sed 's/^/      /' "$SERVER_LOG"
    exit 1
fi

APP_PORT=$(tr -dc '0-9' < "$READY_FILE")

if [ -z "$APP_PORT" ]
then
    fail "$READY_FILE holds no port"
    exit 1
fi

BASE_URL=http://127.0.0.1:$APP_PORT

ATTEMPT=0
while [ "$ATTEMPT" -lt 300 ]
do
    if curl -sS -o /dev/null -f "$BASE_URL/health" 2> /dev/null
    then
        break
    fi
    ATTEMPT=$((ATTEMPT + 1))
    sleep 0.1
done

if ! curl -sS -o /dev/null -f "$BASE_URL/health" 2> /dev/null
then
    fail "the verify app never answered $BASE_URL/health"
    sed 's/^/      /' "$SERVER_LOG"
    exit 1
fi

pass "verify app ready at $BASE_URL (pid $SERVER_PID)"

# ---------------------------------------------------------------------------
printf '\n5. the real-server suite\n'
# ---------------------------------------------------------------------------
# No pipe: a pipeline reports the exit status of its last command, and `| tee` would
# turn every failing suite into a passing run.
set +e
SPFN_VERIFY_SERVER_URL="$BASE_URL" \
    SPFN_INTEGRATION_RECEIPTS="$RECEIPTS" \
    swift test --filter SPFNVerifyTests > "$SWIFT_LOG" 2>&1
SWIFT_STATUS=$?
set -e

if [ "$SWIFT_STATUS" -eq 0 ]
then
    pass 'swift test --filter SPFNVerifyTests'
else
    fail "swift test --filter SPFNVerifyTests exited $SWIFT_STATUS"
    tail -40 "$SWIFT_LOG" | sed 's/^/      /'
fi

# ---------------------------------------------------------------------------
printf '\n6. every case really ran\n'
# ---------------------------------------------------------------------------
# The receipts are the safety, not the skip. A skipped XCTest is reported as a passing
# XCTest, so a suite that skipped everything leaves an empty directory that this
# section turns into a failure.
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
    pass "all $EXPECTED_COUNT real-server cases recorded a receipt"
else
    RECEIPT_STATUS=1
    fail "real-server cases that did not run:$MISSING"
    fail 'a suite that skips is reported as a suite that passes, so this is a failure'
fi

# ---------------------------------------------------------------------------
printf '\n7. the app this run is responsible for\n'
# ---------------------------------------------------------------------------
kill "$SERVER_PID" 2> /dev/null || true
wait "$SERVER_PID" 2> /dev/null || true
SERVER_PID=''

ORPHAN_STATUS=0
if curl -sS -o /dev/null -f "$BASE_URL/health" 2> /dev/null
then
    ORPHAN_STATUS=1
    fail "$BASE_URL still answers, so something this run started is still up"
else
    pass 'no verify app process survived the run'
fi

# ---------------------------------------------------------------------------
printf '\n'
if [ "$SWIFT_STATUS" -eq 0 ] && [ "$RECEIPT_STATUS" -eq 0 ] && [ "$ORPHAN_STATUS" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
