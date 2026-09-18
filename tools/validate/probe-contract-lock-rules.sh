#!/bin/sh
# SPFN Mobile — proves the contract-pin rules in validator section 5 refuse what they must.
#
# lockVersion 3 shrank Contracts/upstream.lock.json to the facts only this repository can
# know, and moved every contract value — version, major, supportedRange, bundleSha256 —
# into Contracts/upstream-provenance.json, which the exporter wrote. One value, one home.
# The comparison that used to police two copies is gone, so what has to be probed now is
# the pair of rules that keep it that way:
#
#   a. the control — an untouched tree passes both rules;
#   b. a lock that grows a contract value back fails, naming the key;
#   c. the same for authProfiles, which is SDK policy rather than a contract pin;
#   d. evidence whose digest is not the vendored bundle's fails.
#
# Offline, zero toolchain: it runs tools/validate/validate.sh repeatedly. Mutations are
# made on the real files and restored by a trap on every exit path.
#
# THE VALIDATOR'S EXIT CODE IS NOT THE SIGNAL HERE. It already exits non-zero on this
# tree for an unrelated reason — the committed device receipts were taken against
# contract 0.9.0 and the repository pins 0.10.0 — so a probe reading the exit code would
# report every case as refused whether or not the rule under test fired. Each case is
# judged on the line section 5 printed instead.
#
#   sh tools/validate/probe-contract-lock-rules.sh

set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

LOCK=Contracts/upstream.lock.json
EVIDENCE=Contracts/upstream-provenance.json

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-lockrules-probe.XXXXXX")

cp "$LOCK" "$TMP/lock.bak"
cp "$EVIDENCE" "$TMP/evidence.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/lock.bak" "$LOCK"
        cp "$TMP/evidence.bak" "$EVIDENCE"
        rm -rf "$TMP"
    fi
}

# A signal trap that shares the EXIT handler would clean up and let the script carry on
# (P12), leaving the mutated files behind on the next exit path. These disarm first.
on_signal()
{
    trap '' EXIT INT TERM
    restore
    exit "$1"
}

trap restore EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# Runs the validator and asserts section 5 printed a FAIL carrying MARKER, then restores
# both files.
expect_refusal()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    if grep -qF -- "  FAIL  $MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — section 5 did not refuse it"
    fi
    cp "$TMP/lock.bak" "$LOCK"
    cp "$TMP/evidence.bak" "$EVIDENCE"
}

# Rewrites a file through sed, via a temporary, so a failed edit cannot truncate it.
edit()
{
    sed "$2" "$1" > "$TMP/edited" && cp "$TMP/edited" "$1"
}

printf 'contract pin rules probe\n'

# --- a. the control ------------------------------------------------------------------
# Without this, every case below could pass because the validator fell over before
# reaching section 5 rather than because the rule under test bit.
sh tools/validate/validate.sh > "$TMP/control.log" 2>&1
for expected in \
    'the lock restates no contract value that lives in the upstream evidence' \
    "the digest the evidence records is the real SHA-256 of Contracts/spfn-mobile-contract.json"
do
    if grep -qF -- "  ok    $expected" "$TMP/control.log"
    then
        pass "the untouched tree passes: $expected"
    else
        fail "the untouched tree does not pass: $expected"
    fi
done

# --- b. a contract value grown back into the lock ------------------------------------
# The digest is the one that matters most, because a second copy of it is a second thing
# to keep in step with the bundle — and the one this repository actually carried until
# lockVersion 3.
edit "$LOCK" 's|"bundlePath": "Contracts/spfn-mobile-contract.json",|"bundlePath": "Contracts/spfn-mobile-contract.json",\n    "manifestSha256": "29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c",|'
expect_refusal 'a lock that restates the bundle digest fails' \
    'the lock restates values whose only source is Contracts/upstream-provenance.json: manifestSha256'

edit "$LOCK" 's|"bundlePath": "Contracts/spfn-mobile-contract.json",|"bundlePath": "Contracts/spfn-mobile-contract.json",\n    "supportedRange": ">=0.10.0 <0.11.0",|'
expect_refusal 'a lock that restates the supported range fails' \
    'the lock restates values whose only source is Contracts/upstream-provenance.json: supportedRange'

# --- c. the auth allowlist, whose home is the enum on each platform -------------------
edit "$LOCK" 's|^  "digestDiscipline": {|  "authProfiles": { "allowed": ["clientProofV1"] },\n  "digestDiscipline": {|'
expect_refusal 'a lock that restates the auth profile allowlist fails' \
    'the lock restates values whose only source is Contracts/upstream-provenance.json: authProfiles'

# --- d. evidence that does not describe the bundle on disk ----------------------------
# The direction that matters once there is only one copy: nothing else in the tree can
# contradict a tampered digest, so the digest is checked against the file's real bytes.
edit "$EVIDENCE" 's|"bundleSha256": "[0-9a-f]\{64\}"|"bundleSha256": "0000000000000000000000000000000000000000000000000000000000000000"|'
expect_refusal 'evidence naming a digest the vendored bundle does not have fails' \
    'the digest the evidence records is the real SHA-256 of Contracts/spfn-mobile-contract.json'

printf '\n%d checks, %d failures\n' "$CHECKS" "$FAILURES"
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi
printf 'RESULT: FAIL\n'
exit 1
