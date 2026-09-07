#!/bin/sh
# SPFN Mobile — proves the predictive-back declaration check refuses what it must.
#
# Section 16 of the validator is the shape of check that is worth the least when it is
# wrong: what it guards fails SILENTLY on a device. Undeclared, a back gesture still pops
# the flow, every cell that asserts a stack depth is green, and the only thing that changes
# is that `NavigationHost`'s `predictivePopTransitionSpec` never runs. Nothing in this
# repository asserts an animation. So if the check does not bite, nobody finds out until
# somebody holds a gesture on a phone and looks (docs/IMPLEMENTATION-PITFALLS.md P35).
#
# Which means the check is only worth what its fail-closed proof is worth (P7). This asks:
#
#   a. the example app's manifest with the declaration taken away fails, naming that file;
#   b. the harness manifest with the declaration taken away fails, naming that file — both
#      apps separately, because a check that hard-coded one path would pass this probe's
#      first half and cover half the repository;
#   c. a declaration spelled `="false"` fails. That is the spelling an app arrives at by
#      turning the flag off rather than by never having written it, and a check that grepped
#      for the attribute NAME would read it as compliance;
#   d. the declaration written only in a COMMENT fails. Both manifests explain this flag at
#      length in prose, so a reader that did not drop comments would find the words in a
#      manifest that declares nothing;
#   e. a manifest list that resolves to nothing fails instead of reporting that both apps
#      comply — the floor, which is the half of a reader that goes quiet rather than red;
#   f. the SDK's own consumer documentation is held too: `NavigationHost.kt` with the flag
#      unmentioned fails. A manifest rule with no SDK sentence behind it is a rule that
#      exists only in the two files that already obey it.
#
# e runs a ROOT-pinned copy of the validator whose own input has been taken away, because
# its subject is what the check does when it cannot read — the one condition that cannot be
# produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-predictive-back-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-back-probe.XXXXXX")

EXAMPLE_MANIFEST=examples/android-compose/src/main/AndroidManifest.xml
HARNESS_MANIFEST=tools/harness/android/src/main/AndroidManifest.xml
NAVIGATION_HOST=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/NavigationHost.kt

cp "$EXAMPLE_MANIFEST" "$TMP/example-manifest.bak"
cp "$HARNESS_MANIFEST" "$TMP/harness-manifest.bak"
cp "$NAVIGATION_HOST" "$TMP/navigation-host.bak"

restore_files()
{
    cp "$TMP/example-manifest.bak" "$EXAMPLE_MANIFEST"
    cp "$TMP/harness-manifest.bak" "$HARNESS_MANIFEST"
    cp "$TMP/navigation-host.bak" "$NAVIGATION_HOST"
}

restore()
{
    if [ -d "$TMP" ]
    then
        restore_files
        rm -rf "$TMP"
    fi
}

on_signal()
{
    trap '' EXIT INT TERM
    restore
    exit "$1"
}

trap restore EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# Section 16 begins at its own heading and runs to the end of the report. Scoped on purpose:
# this repository's validator has failing checks in other sections — the device receipt gate
# — and a probe that keyed on the validator's exit status would report those as its own
# evidence.
BACK_SECTION='/^16\. the Android apps declare/,$p'

# Runs the validator and expects section 16 to refuse, on the named rule.
expect_back_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$BACK_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the predictive-back section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs the validator and expects section 16 to be clean.
expect_back_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$BACK_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and expects
# the check to say so rather than to report a clean read.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$BACK_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the section passed while the check could not run"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    rm -f "$COPY"
}

printf 'predictive back declaration probe\n'

# --- a, b. each app separately -----------------------------------------------------
# One at a time, and the marker names the file: a check written against a single hard-coded
# manifest passes whichever of these two happens to be the one it reads, and covers one app.
grep -v 'android:enableOnBackInvokedCallback' "$TMP/example-manifest.bak" > "$EXAMPLE_MANIFEST"
expect_back_fail 'the example app with the declaration removed fails, naming that manifest' \
    "$EXAMPLE_MANIFEST:undeclared"

grep -v 'android:enableOnBackInvokedCallback' "$TMP/harness-manifest.bak" > "$HARNESS_MANIFEST"
expect_back_fail 'the harness with the declaration removed fails, naming that manifest' \
    "$HARNESS_MANIFEST:undeclared"

# --- c. turned off is not the same as declared -------------------------------------
sed 's/android:enableOnBackInvokedCallback="true"/android:enableOnBackInvokedCallback="false"/' \
    "$TMP/example-manifest.bak" > "$EXAMPLE_MANIFEST"
expect_back_fail 'a declaration spelled "false" fails rather than satisfying a scan for the name' \
    "$EXAMPLE_MANIFEST:undeclared"

# --- d. prose is not a declaration --------------------------------------------------
# The attribute moved out of `<application>` and into a comment of its own, in exactly the
# spelling the check looks for. Both of these manifests are mostly prose about the very flag
# being checked, so this is not a hypothetical shape: it is what removing the attribute while
# leaving the paragraph that explains it would produce.
# awk and not sed, because inserting a line means a newline in the replacement, which GNU
# sed accepts and BSD sed does not (docs/IMPLEMENTATION-PITFALLS.md P28).
grep -v 'android:enableOnBackInvokedCallback' "$TMP/harness-manifest.bak" \
    | awk '{ print } /^<manifest/ { print "    <!-- android:enableOnBackInvokedCallback=\"true\" -->" }' \
    > "$HARNESS_MANIFEST"
expect_back_fail 'the declaration written only inside a comment fails' \
    "$HARNESS_MANIFEST:undeclared"

# --- e. a reader that read nothing is not a reader that found compliance ------------
expect_unrunnable 'a manifest list that resolves to nothing fails instead of reporting that both apps comply' \
    'it did not run' \
    's#^examples/android-compose/src/main/AndroidManifest.xml$##'

# --- f. the SDK says it, or the rule lives only in the files that obey it ------------
grep -v 'enableOnBackInvokedCallback' "$TMP/navigation-host.bak" > "$NAVIGATION_HOST"
expect_back_fail 'the SDK with no word about the flag fails, so the rule is documented and not merely enforced' \
    'without telling a host app what its manifest has to declare'

# --- the unmodified tree still reads clean -------------------------------------------
expect_back_clean 'the predictive-back section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
