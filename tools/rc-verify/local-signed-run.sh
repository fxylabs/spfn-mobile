#!/bin/sh
# SPFN Mobile — signed RC verification from a fresh local clone.
#
# Automates the reproduction that isolated CI dispatch 30795139768: run the full RC
# harness against a fresh clone at an exact commit, signing with a real key from the
# local GPG keyring, injected in memory only. A PASS here with the same key material
# CI uses narrows a CI failure to the runner environment.
#
#   sh tools/rc-verify/local-signed-run.sh <GPG_KEYID> <commit-40-hex>
#
# What it does, and the two pitfalls it encodes:
#   - clones this repository with --no-tags into a directory named exactly
#     `spfn-mobile`: the candidate tag exists on the remote and would abort the
#     harness if fetched, and the clone directory's basename becomes the SwiftPM
#     package identity the harness's consumer resolves — any other name breaks
#     resolution.
#   - reads the key passphrase with stty -echo rather than a shell's read flags
#     (`read -p` means a coprocess in zsh, not a prompt), exports the armored secret
#     key via gpg into a variable, and passes key and passphrase to the harness as
#     per-run environment only. Neither value is ever echoed, logged or written to
#     disk, and both die with this process.
#   - the harness output directory ($TMPDIR) survives and is printed; the clone and
#     every intermediate are removed by a trap on every exit path, signals included.
#
# Requirements: everything rc-verify.sh needs, plus gpg with the named secret key.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

usage()
{
    printf 'usage: sh tools/rc-verify/local-signed-run.sh <GPG_KEYID> <commit-40-hex>\n' >&2
    exit 2
}

[ $# -eq 2 ] || usage
KEYID=$1
COMMIT=$2

die()
{
    printf 'local-signed-run FAIL: %s\n' "$1" >&2
    exit 1
}

case "$COMMIT" in
    *[!0-9a-f]* | '') die 'commit must be a full 40-hex lowercase SHA' ;;
esac
[ "${#COMMIT}" -eq 40 ] || die "commit must be exactly 40 hex characters, got ${#COMMIT}"
git -C "$ROOT" cat-file -e "$COMMIT^{commit}" 2>/dev/null \
    || die "commit $COMMIT does not exist in this repository"
command -v gpg > /dev/null 2>&1 || die 'gpg not found'

WORK=$(mktemp -d "${TMPDIR:-/tmp}/spfn-local-signed.XXXXXX")
CLONE="$WORK/spfn-mobile"
STTY_SAVED=''

sweep()
{
    if [ -n "$STTY_SAVED" ] && [ -t 0 ]
    then
        stty "$STTY_SAVED" 2>/dev/null || true
        STTY_SAVED=''
    fi
    rm -rf "$WORK"
}

cleanup()
{
    status=$?
    sweep
    exit "$status"
}

on_signal()
{
    trap '' EXIT INT TERM
    sweep
    exit "$1"
}

trap cleanup EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# --- passphrase, hidden; key, exported into memory only ----------------------------
if [ -t 0 ]
then
    printf 'GPG passphrase for %s (input hidden): ' "$KEYID"
    STTY_SAVED=$(stty -g)
    stty -echo
    IFS= read -r PASSPHRASE
    stty "$STTY_SAVED"
    STTY_SAVED=''
    printf '\n'
else
    # Non-interactive caller (a probe, a wrapper): the passphrase arrives on stdin.
    IFS= read -r PASSPHRASE
fi

KEY=$(printf '%s\n' "$PASSPHRASE" \
    | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
        --armor --export-secret-keys "$KEYID" 2>/dev/null) \
    || die "gpg could not export the secret key for '$KEYID' (key id or passphrase?)"
[ -n "$KEY" ] || die "gpg exported nothing for '$KEYID' (key id or passphrase?)"

# --- fresh clone at the exact commit -----------------------------------------------
git clone --quiet --no-tags "$ROOT" "$CLONE" \
    || die 'clone failed'
git -C "$CLONE" checkout --quiet --detach "$COMMIT" \
    || die "could not check out $COMMIT in the clone"

OUT=$(mktemp -d "${TMPDIR:-/tmp}/spfn-rc-local-signed.XXXXXX")
rmdir "$OUT"

printf 'signed RC verification: commit %s, clone %s\n' "$COMMIT" "$CLONE"

(
    cd "$CLONE" \
        && SPFN_RC_OUT="$OUT" \
            ORG_GRADLE_PROJECT_spfnSigningInMemoryKey="$KEY" \
            ORG_GRADLE_PROJECT_spfnSigningInMemoryKeyPassword="$PASSPHRASE" \
            sh tools/rc-verify/rc-verify.sh
) || die "the signed RC verification failed; evidence at $OUT"

printf '\nlocal-signed-run PASS\n'
printf '  evidence: %s\n' "$OUT"
printf '  the clone and the key material are gone with this process\n'
