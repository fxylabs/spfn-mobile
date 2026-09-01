#!/bin/sh
# SPFN Mobile — proof that the device-receipt gate refuses what it says it refuses.
#
#   sh tools/device-receipts/probe-receipt-gate.sh
#
# The gate's whole value is the publication it declines. Those refusals fire only when
# the evidence is already wrong, which is exactly when nobody is watching them, so they
# are exercised here on fixtures the probe builds itself rather than left to the day
# they are needed.
#
# It asserts the REASON, not only the exit code, and then asserts something the reason
# checks alone cannot: that no two failure modes produce the same sentence. That is the
# whole point of P7 — "the gate could not look" and "the gate looked and found nothing
# wrong" must never arrive as the same outcome, and neither may "the directory is empty"
# and "the directory is unreadable". A gate whose refusals are indistinguishable sends
# the reader to fix the wrong thing.
#
# The last case is the one that matters most: a complete, correct fixture set must PASS.
# Without it the probe would be satisfied by a gate that refuses everything, which
# blocks every honest release and proves nothing.
#
# Requires: POSIX sh, awk, find — the same tools the gate requires.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

GATE=tools/device-receipts/receipt-gate.sh
LOCK=Contracts/upstream.lock.json

WORK=$(mktemp -d "${TMPDIR:-/tmp}/spfn-receipt-probe.XXXXXX")
FAILURES=0
MESSAGES=$WORK/messages.txt
: > "$MESSAGES"

sweep()
{
    # Restore anything the unreadable-path cases locked, or the temporary tree cannot
    # be removed and the next run inherits it.
    chmod -R u+rwX "$WORK" 2> /dev/null || true
    rm -rf "$WORK"
}

# A signal handler that shares the EXIT trap cleans up and lets the script continue
# (P12). These disarm first and exit 128+N themselves.
on_signal()
{
    trap '' EXIT INT TERM
    sweep
    exit "$1"
}

trap sweep EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

fail()
{
    FAILURES=$((FAILURES + 1))
    printf 'FAIL  %s\n' "$1"
}

pass()
{
    printf 'ok    %s\n' "$1"
}

# The contract the repository pins, read independently of the gate so the two readings
# can be compared rather than assumed equal.
PINNED=$(awk '
$0 ~ /^[[:space:]]*"contract"[[:space:]]*:[[:space:]]*\{/ { inblock = 1; next }
inblock && $0 ~ /^[[:space:]]*\}/                         { inblock = 0 }
inblock && match($0, /"version"[[:space:]]*:[[:space:]]*"[^"]*"/) {
    v = substr($0, RSTART, RLENGTH)
    sub(/^"version"[[:space:]]*:[[:space:]]*"/, "", v)
    sub(/"$/, "", v)
    print v
    exit
}
' "$LOCK")

if [ -z "$PINNED" ]
then
    printf 'probe-receipt-gate.sh could not read contract.version from %s\n' "$LOCK" >&2
    exit 1
fi

# One receipt, written from the case table by hand — the same table the gate carries,
# transcribed here independently so a fixture and the rule it satisfies do not share a
# source (P10).
#   $1 directory  $2 platform  $3 provider  $4 case  $5 epochMillis  $6 contractVersion
write_receipt()
{
    case "$4" in
        first-enroll)
            outcome=enrolled; code=200; err=null; new=true; keyid=true ;;
        re-login)
            outcome=enrolled; code=200; err=null; new=false; keyid=true ;;
        user-cancel)
            outcome=cancelled; code=null; err='"social:cancelled"'; new=null; keyid=null ;;
        network-failure)
            outcome=failed; code=null; err='"connectivity"'; new=null; keyid=null ;;
        server-reject)
            outcome=failed; code=401; err='"InvalidSocialTokenError"'; new=null; keyid=null ;;
        *)
            printf 'write_receipt: unknown case %s\n' "$4" >&2
            exit 1
            ;;
    esac

    mkdir -p "$1"
    cat > "$1/receipt-$3-$4-$5.json" <<EOF
{
  "schema": "spfn-device-receipt/1",
  "provider": "$3",
  "platform": "$2",
  "case": "$4",
  "outcome": "$outcome",
  "responseCode": $code,
  "errorCode": $err,
  "isNewUser": $new,
  "keyIdMatch": $keyid,
  "keyRemainsAfterFailure": false,
  "timestamp": "2026-09-01T12:00:00Z",
  "serverBaseURL": "http://127.0.0.1:8790",
  "serverCommit": null,
  "sdkVersion": "0.0.0-probe.1",
  "contractVersion": "$6"
}
EOF
}

# A complete, correct fifteen-cell set under $1, all at contract $2.
write_full_set()
{
    epoch=1788300000000
    for cell in ios:apple ios:google android:google
    do
        for case_name in first-enroll re-login user-cancel network-failure server-reject
        do
            epoch=$((epoch + 1000))
            write_receipt "$1/2026-09-01" "${cell%%:*}" "${cell#*:}" "$case_name" \
                "$epoch" "$2"
        done
    done
}

# Runs the gate against a fixture root and asserts it refused for the stated reason.
#   $1 case name  $2 receipt root  $3 fixed string the output must carry
#   $4 optional lock path override
expect_refusal()
{
    OUT=$WORK/out.txt

    set +e
    SPFN_RECEIPT_ROOT="$2" SPFN_RECEIPT_LOCK="${4:-$LOCK}" sh "$GATE" > "$OUT" 2>&1
    STATUS=$?
    set -e

    if [ "$STATUS" -eq 0 ]
    then
        fail "$1: the gate exited 0; this evidence must never clear a candidate"
        return
    fi

    if ! grep -qF "$3" "$OUT"
    then
        fail "$1: refused with exit $STATUS but not for the stated reason"
        printf '      wanted: %s\n' "$3"
        sed 's/^/      /' "$OUT"
        return
    fi

    # The refusal sentence itself, kept so the distinctness check below can compare all
    # of them. The first FAIL-shaped line is the reason; everything after it is detail.
    grep -E 'receipt gate FAIL|RECEIPT GATE RESULT: FAIL|^  - ' "$OUT" | head -1 >> "$MESSAGES"
    pass "$1"
}

printf 'SPFN Mobile — device receipt gate refusal probe\n'
printf 'root: %s\n' "$ROOT"
printf 'contract pinned by the repository: %s\n\n' "$PINNED"

# 1. No receipt root at all. Distinct from an empty one: nobody has run a device
#    session, rather than a session having produced nothing.
expect_refusal 'an absent receipt root is refused' \
    "$WORK/nowhere" 'does not exist'

# 2. A receipt root that exists and holds nothing. The canonical fail-open shape: an
#    empty scan result reading as a clean one.
mkdir -p "$WORK/empty"
expect_refusal 'an empty receipt root is refused, and not as a clean run' \
    "$WORK/empty" 'the gate has read nothing and clears nothing'

# 3. A receipt root that cannot be read. Running as root defeats the permission bits,
#    so the case is skipped rather than passed on a machine where it proves nothing.
if [ "$(id -u)" = "0" ]
then
    printf 'skip  an unreadable receipt root (running as root; the bits do not bite)\n'
else
    write_full_set "$WORK/locked-root" "$PINNED"
    chmod 000 "$WORK/locked-root"
    expect_refusal 'an unreadable receipt root is refused, distinctly from an empty one' \
        "$WORK/locked-root" 'is not a readable directory'
    chmod 755 "$WORK/locked-root"

    # 4. The root is readable but a directory under it is not, so the enumeration itself
    #    is incomplete. A partial listing that cleared the cells it happened to reach
    #    would be the worst outcome available.
    write_full_set "$WORK/locked-inner" "$PINNED"
    chmod 000 "$WORK/locked-inner/2026-09-01"
    expect_refusal 'an enumeration that could not complete is refused' \
        "$WORK/locked-inner" 'did not complete'
    chmod 755 "$WORK/locked-inner/2026-09-01"

    # 5. A receipt file the gate cannot open. Unreadable is not absent.
    write_full_set "$WORK/locked-file" "$PINNED"
    chmod 000 "$WORK/locked-file/2026-09-01/receipt-apple-first-enroll-1788300001000.json"
    expect_refusal 'an unreadable receipt file is refused as unreadable' \
        "$WORK/locked-file" 'is not readable; an unreadable receipt is not an absent one'
    chmod 644 "$WORK/locked-file/2026-09-01/receipt-apple-first-enroll-1788300001000.json"
fi

# 6. Malformed JSON. Reported as malformed rather than as a missing cell: a reader sent
#    to run another device session over a half-written file fixes nothing.
write_full_set "$WORK/malformed" "$PINNED"
printf '{\n  "schema": "spfn-device-receipt/1",\n' \
    > "$WORK/malformed/2026-09-01/receipt-apple-first-enroll-1788300001000.json"
expect_refusal 'a truncated receipt is refused as malformed, not as an absent cell' \
    "$WORK/malformed" 'is not one JSON object'

# 7. A field the schema does not declare. This is the shape a leak takes: nothing else
#    in the pipeline would notice a receipt that grew an extra key.
write_full_set "$WORK/extra-field" "$PINNED"
sed 's/"serverCommit": null,/"serverCommit": null,\n  "account": "someone",/' \
    "$WORK/extra-field/2026-09-01/receipt-google-re-login-1788300007000.json" \
    > "$WORK/extra-field.tmp"
mv "$WORK/extra-field.tmp" \
    "$WORK/extra-field/2026-09-01/receipt-google-re-login-1788300007000.json"
expect_refusal 'a receipt carrying a field outside the schema is refused' \
    "$WORK/extra-field" 'which is not in the receipt schema'

# 8. An account identifier smuggled into a declared field. The schema's prohibition is
#    on the VALUES too, not only on the key set.
write_full_set "$WORK/pii" "$PINNED"
sed 's|"serverCommit": null|"serverCommit": "someone@example.test"|' \
    "$WORK/pii/2026-09-01/receipt-google-re-login-1788300007000.json" > "$WORK/pii.tmp"
mv "$WORK/pii.tmp" "$WORK/pii/2026-09-01/receipt-google-re-login-1788300007000.json"
expect_refusal 'a receipt carrying an email-shaped value is refused' \
    "$WORK/pii" 'the schema forbids an email or account identifier'

# 9. A token in a receipt. A receipt holding a credential is itself a credential, which
#    the spec calls a blocking defect — so it blocks here.
write_full_set "$WORK/token" "$PINNED"
sed 's|"serverCommit": null|"serverCommit": "eyJhbGciOiJSUzI1NiJ9.payload.sig"|' \
    "$WORK/token/2026-09-01/receipt-google-re-login-1788300007000.json" > "$WORK/token.tmp"
mv "$WORK/token.tmp" "$WORK/token/2026-09-01/receipt-google-re-login-1788300007000.json"
expect_refusal 'a receipt carrying token-shaped text is refused' \
    "$WORK/token" 'a receipt holding a token is itself a credential'

# 10. The filename and the receipt inside it disagree. The gate judges by the contents,
#     so a hand-edited name must be caught rather than silently ignored.
write_full_set "$WORK/misnamed" "$PINNED"
mv "$WORK/misnamed/2026-09-01/receipt-apple-re-login-1788300002000.json" \
    "$WORK/misnamed/2026-09-01/receipt-apple-user-cancel-1788300002000.json"
expect_refusal 'a filename that disagrees with its receipt is refused' \
    "$WORK/misnamed" 'but the receipt inside says'

# 10a. A field of the right name and the wrong type. The receipt writers live on two
#      platforms and are edited independently, so a status quoted as a string is the
#      shape a drift takes — and a gate comparing "200" against 200 would call a passing
#      cell unproven for a reason nobody could see.
write_full_set "$WORK/typed" "$PINNED"
sed 's/"responseCode": 401/"responseCode": "401"/' \
    "$WORK/typed/2026-09-01/receipt-apple-server-reject-1788300005000.json" > "$WORK/typed.tmp"
mv "$WORK/typed.tmp" "$WORK/typed/2026-09-01/receipt-apple-server-reject-1788300005000.json"
expect_refusal 'a quoted HTTP status is refused as a wrong-typed field' \
    "$WORK/typed" 'which is neither null nor an HTTP status'

# 10b. A timestamp that is not the ISO-8601 UTC instant the schema declares. Locale-
#      formatted time is the classic way this field rots (P9), and a receipt whose time
#      cannot be read is a receipt whose ordering cannot be trusted.
write_full_set "$WORK/timestamp" "$PINNED"
sed 's|"timestamp": "2026-09-01T12:00:00Z"|"timestamp": "2026. 9. 1. 12:00:00"|' \
    "$WORK/timestamp/2026-09-01/receipt-apple-network-failure-1788300004000.json" \
    > "$WORK/timestamp.tmp"
mv "$WORK/timestamp.tmp" \
    "$WORK/timestamp/2026-09-01/receipt-apple-network-failure-1788300004000.json"
expect_refusal 'a timestamp that is not an ISO-8601 UTC instant is refused' \
    "$WORK/timestamp" 'which is not an ISO-8601 UTC instant'

# 10c. A server URL carrying a path. The schema admits a host only, because a path is
#      where a query string, a token or an account id would ride into the file.
write_full_set "$WORK/url" "$PINNED"
sed 's|"serverBaseURL": "http://127.0.0.1:8790"|"serverBaseURL": "http://127.0.0.1:8790/_auth/oauth/google/native"|' \
    "$WORK/url/2026-09-01/receipt-google-first-enroll-1788300011000.json" > "$WORK/url.tmp"
mv "$WORK/url.tmp" "$WORK/url/2026-09-01/receipt-google-first-enroll-1788300011000.json"
expect_refusal 'a serverBaseURL carrying a path is refused' \
    "$WORK/url" 'the schema admits a host only'

# 11. A cell with no receipt at all, named in the refusal. A gate that reported only a
#     count would leave the operator to work out which phone to pick up.
write_full_set "$WORK/missing-cell" "$PINNED"
rm "$WORK/missing-cell/2026-09-01/receipt-google-server-reject-1788300015000.json"
expect_refusal 'a missing cell is refused, and the message names it' \
    "$WORK/missing-cell" 'android x google x server-reject: no receipt exists'

# 12. A cell whose receipt exists and says the wrong thing. first-enroll with
#     isNewUser=false is the exact mistake the first device run made nine times over,
#     and it must not read as proof.
write_full_set "$WORK/wrong-field" "$PINNED"
sed 's/"isNewUser": true/"isNewUser": false/' \
    "$WORK/wrong-field/2026-09-01/receipt-apple-first-enroll-1788300001000.json" \
    > "$WORK/wrong-field.tmp"
mv "$WORK/wrong-field.tmp" \
    "$WORK/wrong-field/2026-09-01/receipt-apple-first-enroll-1788300001000.json"
expect_refusal 'a cell whose receipt disagrees with the case table is refused by name' \
    "$WORK/wrong-field" 'ios x apple x first-enroll: no receipt matches the case table (1 read)'

# 13. A file under the receipt root that is not named like a receipt. The gate's
#     enumeration only sees receipt-*.json, so anything else would be skipped without a
#     word — and a receipt saved under a mistyped name is exactly the file that must not
#     be skipped.
write_full_set "$WORK/stray" "$PINNED"
cp "$WORK/stray/2026-09-01/receipt-apple-user-cancel-1788300003000.json" \
    "$WORK/stray/2026-09-01/reciept-apple-user-cancel-1788300003000.json"
expect_refusal 'a file the enumeration would skip is refused, not ignored' \
    "$WORK/stray" 'would have been skipped'

# 14. Evidence from a different contract. Device proof is proof about a wire contract;
#     re-pinning the contract must invalidate it rather than carry it forward.
write_full_set "$WORK/old-contract" '0.8.0'
expect_refusal 'receipts taken against another contract version are refused' \
    "$WORK/old-contract" "repository pins $PINNED"

# 15. The pin itself unreadable. The gate cannot demand a version it could not read,
#     and must say so rather than compare against an empty string.
write_full_set "$WORK/good" "$PINNED"
expect_refusal 'an unreadable contract pin is refused' \
    "$WORK/good" 'cannot know which contract to demand' "$WORK/no-such-lock.json"

# ---------------------------------------------------------------------------
# Every refusal above must be its own sentence.
# ---------------------------------------------------------------------------
TOTAL=$(wc -l < "$MESSAGES" | tr -d ' ')
UNIQUE=$(sort -u < "$MESSAGES" | wc -l | tr -d ' ')
if [ -n "${SPFN_PROBE_VERBOSE:-}" ]
then
    printf '\ncollected refusals:\n'
    sed 's/^/      /' "$MESSAGES"
    printf '\n'
fi
if [ "$TOTAL" -lt 10 ]
then
    fail "only $TOTAL refusal messages were collected; the probe did not run its cases"
elif [ "$TOTAL" = "$UNIQUE" ]
then
    pass "all $TOTAL refusals carry distinct messages"
else
    fail "$TOTAL refusals produced only $UNIQUE distinct messages; two failure modes are indistinguishable"
    sort < "$MESSAGES" | uniq -d | sed 's/^/      /'
fi

# ---------------------------------------------------------------------------
# A correct fixture set must pass, or the gate refuses everything and means nothing.
# ---------------------------------------------------------------------------
set +e
SPFN_RECEIPT_ROOT="$WORK/good" sh "$GATE" > "$WORK/green.txt" 2>&1
GREEN_STATUS=$?
set -e

if [ "$GREEN_STATUS" -ne 0 ]
then
    fail "a complete, correct fifteen-cell set was refused with exit $GREEN_STATUS"
    sed 's/^/      /' "$WORK/green.txt"
elif ! grep -qF 'RECEIPT GATE RESULT: PASS (15 of 15 cells proven' "$WORK/green.txt"
then
    fail 'a correct fixture set passed without reporting fifteen proven cells'
    sed 's/^/      /' "$WORK/green.txt"
elif ! grep -qF "contract: $PINNED" "$WORK/green.txt"
then
    fail "the gate did not report the contract this repository pins ($PINNED)"
else
    pass 'a complete, correct fifteen-cell set passes, at the pinned contract'
fi

# A later attempt that failed must not un-prove a cell an earlier one proved: the run
# this gate was built for left exactly that behind, and deleting it would have made the
# record a story.
write_full_set "$WORK/with-noise" "$PINNED"
write_receipt "$WORK/with-noise/2026-09-01" android google re-login 1788399999000 "$PINNED"
sed 's/"outcome": "enrolled"/"outcome": "cancelled"/' \
    "$WORK/with-noise/2026-09-01/receipt-google-re-login-1788399999000.json" \
    > "$WORK/noise.tmp"
mv "$WORK/noise.tmp" "$WORK/with-noise/2026-09-01/receipt-google-re-login-1788399999000.json"

set +e
SPFN_RECEIPT_ROOT="$WORK/with-noise" sh "$GATE" > "$WORK/noise.txt" 2>&1
NOISE_STATUS=$?
set -e

if [ "$NOISE_STATUS" -eq 0 ]
then
    pass 'a later failed attempt does not un-prove a cell an earlier receipt proved'
else
    fail 'a kept failed attempt un-proved a cell that a correct receipt already proved'
    sed 's/^/      /' "$WORK/noise.txt"
fi

# ---------------------------------------------------------------------------
# And the committed evidence must clear the committed gate.
# ---------------------------------------------------------------------------
set +e
sh "$GATE" > "$WORK/committed.txt" 2>&1
COMMITTED_STATUS=$?
set -e

if [ "$COMMITTED_STATUS" -eq 0 ]
then
    pass 'the committed device receipts prove all fifteen cells'
else
    fail "the committed device receipts no longer clear the gate (exit $COMMITTED_STATUS)"
    sed 's/^/      /' "$WORK/committed.txt"
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL (%s)\n' "$FAILURES"
exit 1
