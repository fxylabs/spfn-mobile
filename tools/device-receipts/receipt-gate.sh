#!/bin/sh
# SPFN Mobile — device-receipt publication gate.
#
#   sh tools/device-receipts/receipt-gate.sh
#
# A social sign-in path cannot be proven by a simulator, an emulator or a mock: the
# provider sheet, the platform key store and the real server all have to be present at
# once, and only a person holding a phone can produce that. So the evidence is a file —
# one receipt per attempt, written by the harness app on the device — and this gate is
# what turns those files into a refusal. tools/rc-verify/rc-verify.sh calls it before it
# will verify a candidate, so a release candidate that has no device evidence for the
# pinned contract is refused rather than quietly published.
#
# WHAT IT JUDGES
#
# Fifteen cells: {ios × apple, ios × google, android × google} × {first-enroll,
# re-login, user-cancel, network-failure, server-reject}. Android × Apple is exempt by
# decision — no Android Apple adapter module exists, so there is nothing to enrol with.
# The exemption is printed on every run rather than left as an absence a reader has to
# notice.
#
# The expected values are transcribed by hand from the shared harness spec's case table
# (w-9jqtj), NOT derived from the receipts on disk (P10): a table read off its own
# evidence agrees with it by construction and proves nothing. They are stated once, in
# expected_for() below, with the spec's wording beside each row.
#
# A cell is proven by the LATEST receipt for that cell that matches every expected
# field, not by the latest receipt outright. That is deliberate and it is the honest
# reading of a human-driven run: a device session leaves failed attempts, mis-declared
# cases and retries behind, and every one of those files is kept because deleting the
# ugly ones would make the directory a story rather than a record. What a receipt can
# never do is un-prove a cell some other receipt proved — an operator cancelling out of
# a sheet after the case already passed says nothing about the SDK. A cell with no
# matching receipt at all fails, and the message says how many receipts it did read for
# that cell so "never attempted" and "attempted and wrong" do not look the same.
#
# WHAT IT REFUSES TO GUESS (P7)
#
# Every way this gate can fail to look at something is its own refusal with its own
# message: a receipt root that is absent, one that is not readable, an enumeration that
# could not complete, a path holding a newline, a file that is not a well-formed
# receipt, a field whose value is not of the shape the schema declares, a filename that
# disagrees with the receipt inside it. None of those is allowed to read as "checked
# clean". tools/device-receipts/probe-receipt-gate.sh drives each one and asserts the
# messages are distinct; tools/validate/validate.sh runs that probe on every run.
#
# The contract version is read from the repository's own pin (Contracts/upstream.lock.json
# contract.version, cross-checked against the generated sources' header) rather than
# hard-coded, so re-pinning the contract invalidates every receipt taken against the old
# one and the gate demands a fresh device run — which is the point: evidence about a
# 0.9.0 wire contract says nothing about a 0.10.0 one.
#
# WHERE THE EVIDENCE LIVES
#
#   tools/device-receipts/runs/<date>/receipt-<provider>-<case>-<epochMillis>.json
#
# One directory per device session, holding everything that session produced — retries,
# mis-declared cases and attempts against a server that was already down included. The
# ugly files stay because deleting them would turn a record into a story, and because
# the gate does not need them to be pretty: it needs one receipt per cell that says the
# right thing. Nothing but receipts lives under that root, and a file that is not named
# like one fails the gate rather than being skipped.
#
# Overrides, used by the probe and by nothing else:
#   SPFN_RECEIPT_ROOT   directory holding the dated receipt directories
#   SPFN_RECEIPT_LOCK   the contract lock to read the pin from
#
# Requirements: POSIX sh, awk, find, wc. No network, no toolchain, no JSON parser.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

RECEIPT_ROOT=${SPFN_RECEIPT_ROOT:-tools/device-receipts/runs}
LOCK=${SPFN_RECEIPT_LOCK:-Contracts/upstream.lock.json}
GENERATED_HEADER=Sources/SPFNGenerated/Generated/SPFNGeneratedContract.swift

TAB=$(printf '\t')
TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-receipt-gate.XXXXXX")

# A signal trap that shares the EXIT handler runs the cleanup and then lets the script
# carry on, so an interrupted gate would tidy up and keep judging (P12). The signal
# handlers disarm every trap first, then exit with the conventional 128+N themselves.
sweep()
{
    rm -rf "$TMP"
}

on_signal()
{
    trap '' EXIT INT TERM
    sweep
    exit "$1"
}

trap sweep EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

die()
{
    printf 'receipt gate FAIL: %s\n' "$1" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# The contract this repository is pinned to
# ---------------------------------------------------------------------------
# Read from inside the "contract" object rather than by first-hit-at-any-depth (P5):
# the lock also carries lockVersion and exporterVersion, and a whole-file read would
# happily return one of those.
[ -r "$LOCK" ] || die "the contract lock $LOCK is not readable, so the gate cannot know which contract to demand"

PINNED_CONTRACT=$(awk '
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

[ -n "$PINNED_CONTRACT" ] \
    || die "no contract.version in $LOCK; an unreadable pin is not a matching pin"
case "$PINNED_CONTRACT" in
    *[!0-9.]* | '' | .* | *.)
        die "contract.version '$PINNED_CONTRACT' in $LOCK is not a dotted version" ;;
esac

# The receipts carry the version the GENERATED contract announced to the app, so the
# generated sources and the lock must agree before their agreement means anything. When
# they disagree the repository is mid-repin and the gate has nothing valid to compare
# against — which is a refusal, not a pass.
[ -r "$GENERATED_HEADER" ] \
    || die "$GENERATED_HEADER is not readable, so the generated contract version cannot be cross-checked"
GENERATED_CONTRACT=$(awk '
/^\/\/ contractVersion:/ { print $3; exit }
' "$GENERATED_HEADER")
[ -n "$GENERATED_CONTRACT" ] \
    || die "$GENERATED_HEADER carries no '// contractVersion:' header line"
[ "$GENERATED_CONTRACT" = "$PINNED_CONTRACT" ] \
    || die "the lock pins contract $PINNED_CONTRACT but the generated sources were built from $GENERATED_CONTRACT; re-run codegen before judging device evidence"

# ---------------------------------------------------------------------------
# Enumeration — an unreadable tree must never look like an empty one
# ---------------------------------------------------------------------------
[ -e "$RECEIPT_ROOT" ] \
    || die "the receipt root $RECEIPT_ROOT does not exist; there is no device evidence to judge"
[ -d "$RECEIPT_ROOT" ] \
    || die "the receipt root $RECEIPT_ROOT is not a directory"
if [ ! -r "$RECEIPT_ROOT" ] || [ ! -x "$RECEIPT_ROOT" ]
then
    die "the receipt root $RECEIPT_ROOT is not a readable directory; the gate could not look, which is not the same as looking and finding nothing"
fi

if ! find "$RECEIPT_ROOT" -type f -name 'receipt-*.json' -print > "$TMP/files.txt"
then
    die "the receipt enumeration under $RECEIPT_ROOT did not complete; a partial listing cannot clear a single cell"
fi

# find writes one line per path, so a path holding a newline arrives as two paths that
# each resolve somewhere else and the real file goes unread. Counting the files
# independently of their names is what notices.
SCANNED=$(wc -l < "$TMP/files.txt" | tr -d ' ')
NAMED=$(find "$RECEIPT_ROOT" -type f -name 'receipt-*.json' -exec echo x \; | wc -l | tr -d ' ')
[ "$SCANNED" = "$NAMED" ] \
    || die "the enumeration read $SCANNED lines for $NAMED receipt files; a path contains a newline and cannot be addressed"
[ "$SCANNED" -gt 0 ] \
    || die "no receipt files under $RECEIPT_ROOT; the gate has read nothing and clears nothing"

# Nothing but receipts lives under this root, and the gate says so rather than passing
# over what it does not recognise. A receipt saved under a mistyped name would otherwise
# be skipped in silence — the one outcome a gate must never produce — and the run it
# belongs to would look like a run that never happened. Dot files are the exception:
# .DS_Store and its kind are the operating system's, never evidence.
if ! find "$RECEIPT_ROOT" -type f ! -name 'receipt-*.json' ! -name '.*' -print \
    > "$TMP/strays.txt"
then
    die "the stray-file scan under $RECEIPT_ROOT did not complete"
fi
if [ -s "$TMP/strays.txt" ]
then
    die "files under $RECEIPT_ROOT are not named like receipts and would have been skipped: $(tr '\n' ' ' < "$TMP/strays.txt")"
fi

# ---------------------------------------------------------------------------
# Reading one receipt
# ---------------------------------------------------------------------------
# Structure, the exact schema key set, and the schema's own prohibition on carrying a
# token or an account identifier, in one pass. A receipt holding a credential is a
# blocking defect by the spec, so it fails the gate rather than being reported later.
read_receipt()
{
    awk '
BEGIN {
    split("schema platform provider case outcome responseCode errorCode isNewUser keyIdMatch keyRemainsAfterFailure timestamp serverBaseURL serverCommit sdkVersion contractVersion", order, " ")
    for (i in order) { allowed[order[i]] = 1 }
    opens = 0
    closes = 0
    err = ""
}

function bail(m) { if (err == "") { err = m } }

{
    line = $0
    sub(/\r$/, "", line)
    gsub(/^[ \t]+/, "", line)
    gsub(/[ \t]+$/, "", line)
    if (line == "") { next }

    if (index(line, "@") > 0)
    {
        bail("line " NR " carries an @; the schema forbids an email or account identifier in a receipt")
        next
    }
    if (index(line, "eyJ") > 0)
    {
        bail("line " NR " carries token-shaped text; a receipt holding a token is itself a credential")
        next
    }
    if (length(line) > 200)
    {
        bail("line " NR " is " length(line) " characters; no schema field is that long")
        next
    }

    if (line == "{") { opens++; next }
    if (line == "}") { closes++; next }

    if (line !~ /^"[^"]+"[ \t]*:[ \t]*./)
    {
        bail("line " NR " is not a \"field\": value pair")
        next
    }

    q = index(substr(line, 2), "\"")
    key = substr(line, 2, q - 1)
    rest = substr(line, q + 2)
    sub(/^[ \t]*:[ \t]*/, "", rest)
    sub(/,$/, "", rest)
    gsub(/[ \t]+$/, "", rest)
    gsub(/\\\//, "/", rest)

    if (!(key in allowed))
    {
        bail("carries the field \"" key "\", which is not in the receipt schema")
        next
    }
    if (key in v)
    {
        bail("declares the field \"" key "\" more than once")
        next
    }
    v[key] = rest
}

END {
    if (err == "" && (opens != 1 || closes != 1))
    {
        err = "is not one JSON object (" opens " opening and " closes " closing braces)"
    }
    if (err == "")
    {
        for (i = 1; i <= 15; i++)
        {
            if (!(order[i] in v))
            {
                err = "is missing the schema field \"" order[i] "\""
                break
            }
        }
    }
    if (err != "")
    {
        printf "ERR\t%s\n", err
        exit 0
    }
    out = ""
    for (i = 1; i <= 15; i++) { out = out v[order[i]] "\t" }
    printf "OK\t%s\n", out
}
' "$1"
}

# A quoted JSON string with its quotes removed; anything else unchanged, so the literal
# null survives as the four characters `null` and can never be confused with the string
# "null".
unquote()
{
    case "$1" in
        '"'*'"')
            _u=${1#\"}
            printf '%s' "${_u%\"}"
            ;;
        *)
            printf '%s' "$1"
            ;;
    esac
}

is_iso_utc()
{
    case "$1" in
        [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) return 0 ;;
        *) return 1 ;;
    esac
}

is_lower_hex_commit()
{
    case "$1" in
        *[!0-9a-f]* | '') return 1 ;;
    esac
    _len=${#1}
    [ "$_len" -ge 7 ] && [ "$_len" -le 40 ]
}

# ---------------------------------------------------------------------------
# The case table, transcribed from the shared harness spec (w-9jqtj), by hand
# ---------------------------------------------------------------------------
#   | case            | expected outcome | expected fields                              |
#   | first-enroll    | enrolled         | responseCode 200, isNewUser true, keyIdMatch true |
#   | re-login        | enrolled         | responseCode 200, isNewUser false, keyIdMatch true |
#   | user-cancel     | cancelled        | errorCode = SDK cancel classification (P16: cancel is NOT failure), keyRemainsAfterFailure false |
#   | network-failure | failed           | transport-class error, keyRemainsAfterFailure false |
#   | server-reject   | failed           | server refusal, keyRemainsAfterFailure false  |
#
# Emitted as: outcome responseCode isNewUser keyIdMatch keyRemainsAfterFailure errorRule
#
# `not-true` admits false or null on purpose, and it is the one place the two platforms
# genuinely differ (P15): after a cancelled or failed attempt iOS writes false into
# isNewUser/keyIdMatch and Android writes null, because Kotlin models "the server never
# answered" as absence and Swift models it as the default. Both say the same thing —
# no enrolment happened — and demanding one spelling would fail a correct platform.
# What neither may say is `true`.
expected_for()
{
    case "$1" in
        first-enroll)    printf 'enrolled 200 true true false silent' ;;
        re-login)        printf 'enrolled 200 false true false silent' ;;
        user-cancel)     printf 'cancelled null not-true not-true false cancel' ;;
        network-failure) printf 'failed null not-true not-true false transport' ;;
        server-reject)   printf 'failed 401 not-true not-true false named' ;;
        *) return 1 ;;
    esac
}

# Whether an errorCode satisfies the rule its case declares.
#   silent    — an enrolment that worked names no error
#   cancel    — the SDK's cancel classification: apple:cancelled, google:cancelled,
#               social:cancelled. Compared as lowercase ASCII against a fixed suffix,
#               never case-folded (P9)
#   transport — the SDK's transport class, which both platforms spell `connectivity`
#   named     — the server refused and the SDK carried a code out; which code is the
#               server's business, but a cancel code here would mean the case was
#               mis-declared
error_matches()
{
    case "$2" in
        silent)    [ "$1" = null ] ;;
        transport) [ "$1" = '"connectivity"' ] ;;
        cancel)
            case "$1" in
                '"'*cancelled'"') return 0 ;;
                *) return 1 ;;
            esac
            ;;
        named)
            case "$1" in
                null | '"'*cancelled'"') return 1 ;;
                '"'*'"') return 0 ;;
                *) return 1 ;;
            esac
            ;;
        *) return 1 ;;
    esac
}

not_true()
{
    [ "$1" = false ] || [ "$1" = null ]
}

printf 'SPFN Mobile — device receipt gate\n'
printf 'root:     %s\n' "$RECEIPT_ROOT"
printf 'contract: %s (pinned by %s, matching the generated sources)\n' "$PINNED_CONTRACT" "$LOCK"
printf 'receipts: %s files\n\n' "$SCANNED"

# ---------------------------------------------------------------------------
# Validate every receipt, into one table the cell judgment reads
# ---------------------------------------------------------------------------
: > "$TMP/table.txt"

while IFS= read -r FILE
do
    [ -r "$FILE" ] \
        || die "$FILE is not readable; an unreadable receipt is not an absent one"

    BASE=${FILE##*/}
    NAME=${BASE%.json}
    case "$NAME" in
        receipt-*-*-*) ;;
        *) die "$FILE is not named receipt-<provider>-<case>-<epochMillis>.json" ;;
    esac
    REST=${NAME#receipt-}
    FN_PROVIDER=${REST%%-*}
    REST=${REST#*-}
    FN_EPOCH=${REST##*-}
    FN_CASE=${REST%-*}
    case "$FN_EPOCH" in
        *[!0-9]* | '') die "$FILE does not end in an epoch-millisecond stamp" ;;
    esac
    [ "${#FN_EPOCH}" -ge 12 ] \
        || die "$FILE names $FN_EPOCH, which is too short to be an epoch in milliseconds"

    READ=$(read_receipt "$FILE")
    case "$READ" in
        "ERR$TAB"*)
            die "$FILE is not a well-formed receipt: ${READ#ERR$TAB}"
            ;;
        "OK$TAB"*) ;;
        *)
            die "$FILE could not be read at all; the reader produced no verdict"
            ;;
    esac

    FIELDS=${READ#OK$TAB}
    IFS="$TAB" read -r R_SCHEMA R_PLATFORM R_PROVIDER R_CASE R_OUTCOME R_CODE R_ERROR \
        R_NEWUSER R_KEYID R_KEYREMAINS R_TIMESTAMP R_URL R_COMMIT R_SDK R_CONTRACT \
        <<EOF
$FIELDS
EOF

    [ "$(unquote "$R_SCHEMA")" = 'spfn-device-receipt/1' ] \
        || die "$FILE declares schema $R_SCHEMA, not spfn-device-receipt/1"

    P_PLATFORM=$(unquote "$R_PLATFORM")
    P_PROVIDER=$(unquote "$R_PROVIDER")
    P_CASE=$(unquote "$R_CASE")
    P_OUTCOME=$(unquote "$R_OUTCOME")
    P_CONTRACT=$(unquote "$R_CONTRACT")

    case "$P_PLATFORM" in
        ios | android) ;;
        *) die "$FILE names platform '$P_PLATFORM', which is neither ios nor android" ;;
    esac
    case "$P_PROVIDER" in
        apple | google) ;;
        *) die "$FILE names provider '$P_PROVIDER', which is neither apple nor google" ;;
    esac
    expected_for "$P_CASE" > /dev/null \
        || die "$FILE names case '$P_CASE', which is not one of the five declared cases"
    case "$P_OUTCOME" in
        enrolled | cancelled | failed) ;;
        *) die "$FILE names outcome '$P_OUTCOME', which is not enrolled, cancelled or failed" ;;
    esac

    [ "$P_PROVIDER" = "$FN_PROVIDER" ] \
        || die "$FILE is named for provider $FN_PROVIDER but the receipt inside says $P_PROVIDER"
    [ "$P_CASE" = "$FN_CASE" ] \
        || die "$FILE is named for case $FN_CASE but the receipt inside says $P_CASE"

    case "$R_CODE" in
        null | [1-5][0-9][0-9]) ;;
        *) die "$FILE records responseCode $R_CODE, which is neither null nor an HTTP status" ;;
    esac
    case "$R_ERROR" in
        null | '"'*'"') ;;
        *) die "$FILE records errorCode $R_ERROR, which is neither null nor a string" ;;
    esac
    for pair in "isNewUser=$R_NEWUSER" "keyIdMatch=$R_KEYID"
    do
        case "${pair#*=}" in
            true | false | null) ;;
            *) die "$FILE records ${pair%%=*} ${pair#*=}, which is not true, false or null" ;;
        esac
    done
    case "$R_KEYREMAINS" in
        true | false) ;;
        *) die "$FILE records keyRemainsAfterFailure $R_KEYREMAINS; the design promise is a boolean, and an absent answer is not a clean one" ;;
    esac

    is_iso_utc "$(unquote "$R_TIMESTAMP")" \
        || die "$FILE records timestamp $R_TIMESTAMP, which is not an ISO-8601 UTC instant"

    P_URL=$(unquote "$R_URL")
    case "$P_URL" in
        http://*/* | https://*/* | *'?'* | *'#'*)
            die "$FILE records serverBaseURL with a path or query; the schema admits a host only" ;;
        http://?* | https://?*) ;;
        *) die "$FILE records serverBaseURL '$P_URL', which is not an http(s) host" ;;
    esac

    if [ "$R_COMMIT" != null ]
    then
        is_lower_hex_commit "$(unquote "$R_COMMIT")" \
            || die "$FILE records serverCommit $R_COMMIT, which is not 7-40 lowercase hex; an unvalidated header value could carry anything into a receipt"
    fi

    [ -n "$(unquote "$R_SDK")" ] || die "$FILE records an empty sdkVersion"
    [ -n "$P_CONTRACT" ] || die "$FILE records an empty contractVersion"

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$P_PLATFORM" "$P_PROVIDER" "$P_CASE" "$FN_EPOCH" \
        "$P_OUTCOME" "$R_CODE" "$R_ERROR" "$R_NEWUSER" "$R_KEYID" "$R_KEYREMAINS" \
        "$P_CONTRACT" "$FILE" >> "$TMP/table.txt"
done < "$TMP/files.txt"

VALIDATED=$(wc -l < "$TMP/table.txt" | tr -d ' ')
[ "$VALIDATED" = "$SCANNED" ] \
    || die "$VALIDATED of $SCANNED receipts reached the case table; the rest were dropped without a verdict"

# ---------------------------------------------------------------------------
# The fifteen cells
# ---------------------------------------------------------------------------
CASES='first-enroll re-login user-cancel network-failure server-reject'
PROVEN=0
REQUIRED=0
FAILED=''

for cell in ios:apple ios:google android:google
do
    PLATFORM=${cell%%:*}
    PROVIDER=${cell#*:}

    for case_name in $CASES
    do
        REQUIRED=$((REQUIRED + 1))
        set -- $(expected_for "$case_name")
        X_OUTCOME=$1
        X_CODE=$2
        X_NEWUSER=$3
        X_KEYID=$4
        X_KEYREMAINS=$5
        X_ERROR=$6

        SEEN=0
        FIELD_OK=0
        BEST_EPOCH=''
        BEST_FILE=''
        WRONG_CONTRACT=''
        LATEST_EPOCH=''
        LATEST_WHY=''

        while IFS="$TAB" read -r t_platform t_provider t_case t_epoch \
            t_outcome t_code t_error t_newuser t_keyid t_keyremains t_contract t_path
        do
            if [ "$t_platform" != "$PLATFORM" ] || [ "$t_provider" != "$PROVIDER" ] \
                || [ "$t_case" != "$case_name" ]
            then
                continue
            fi

            SEEN=$((SEEN + 1))

            WHY=''
            [ "$t_outcome" = "$X_OUTCOME" ] || WHY="outcome=$t_outcome"
            [ "$t_code" = "$X_CODE" ] || WHY="$WHY responseCode=$t_code"
            if [ "$X_NEWUSER" = not-true ]
            then
                not_true "$t_newuser" || WHY="$WHY isNewUser=$t_newuser"
            else
                [ "$t_newuser" = "$X_NEWUSER" ] || WHY="$WHY isNewUser=$t_newuser"
            fi
            if [ "$X_KEYID" = not-true ]
            then
                not_true "$t_keyid" || WHY="$WHY keyIdMatch=$t_keyid"
            else
                [ "$t_keyid" = "$X_KEYID" ] || WHY="$WHY keyIdMatch=$t_keyid"
            fi
            [ "$t_keyremains" = "$X_KEYREMAINS" ] || WHY="$WHY keyRemainsAfterFailure=$t_keyremains"
            error_matches "$t_error" "$X_ERROR" || WHY="$WHY errorCode=$t_error"

            if [ -z "$LATEST_EPOCH" ] || [ "$t_epoch" -gt "$LATEST_EPOCH" ]
            then
                LATEST_EPOCH=$t_epoch
                LATEST_WHY=$WHY
            fi

            [ -z "$WHY" ] || continue
            FIELD_OK=$((FIELD_OK + 1))

            if [ "$t_contract" != "$PINNED_CONTRACT" ]
            then
                WRONG_CONTRACT=$t_contract
                continue
            fi

            if [ -z "$BEST_EPOCH" ] || [ "$t_epoch" -gt "$BEST_EPOCH" ]
            then
                BEST_EPOCH=$t_epoch
                BEST_FILE=$t_path
            fi
        done < "$TMP/table.txt"

        CELL="$PLATFORM x $PROVIDER x $case_name"

        if [ -n "$BEST_FILE" ]
        then
            PROVEN=$((PROVEN + 1))
            printf '  ok    %-34s %s\n' "$CELL" "$BEST_FILE"
        elif [ "$SEEN" -eq 0 ]
        then
            printf '  FAIL  %-34s no receipt exists for this cell\n' "$CELL"
            FAILED="$FAILED|$CELL: no receipt exists"
        elif [ "$FIELD_OK" -eq 0 ]
        then
            printf '  FAIL  %-34s no receipt matches the case table (%s read); the latest disagrees on%s\n' \
                "$CELL" "$SEEN" " $LATEST_WHY"
            FAILED="$FAILED|$CELL: no receipt matches the case table ($SEEN read)"
        else
            printf '  FAIL  %-34s %s matching receipts, all taken against contract %s, not %s\n' \
                "$CELL" "$FIELD_OK" "$WRONG_CONTRACT" "$PINNED_CONTRACT"
            FAILED="$FAILED|$CELL: proven only against contract $WRONG_CONTRACT, repository pins $PINNED_CONTRACT"
        fi
    done
done

printf '  --    %-34s exempt by decision: no Android Apple adapter module exists\n' 'android x apple x (all cases)'

printf '\n'
if [ -n "$FAILED" ]
then
    printf 'unproven cells:\n' >&2
    printf '%s' "${FAILED#|}" | tr '|' '\n' | sed 's/^/  - /' >&2
    printf '\nRECEIPT-GATE-SUMMARY cells=%s proven=%s scanned=%s contract=%s root=%s\n' \
        "$REQUIRED" "$PROVEN" "$SCANNED" "$PINNED_CONTRACT" "$RECEIPT_ROOT"
    printf 'RECEIPT GATE RESULT: FAIL (%s of %s cells unproven)\n' \
        "$((REQUIRED - PROVEN))" "$REQUIRED" >&2
    exit 1
fi

printf 'RECEIPT-GATE-SUMMARY cells=%s proven=%s scanned=%s contract=%s root=%s\n' \
    "$REQUIRED" "$PROVEN" "$SCANNED" "$PINNED_CONTRACT" "$RECEIPT_ROOT"
printf 'RECEIPT GATE RESULT: PASS (%s of %s cells proven from %s receipts)\n' \
    "$PROVEN" "$REQUIRED" "$SCANNED"
