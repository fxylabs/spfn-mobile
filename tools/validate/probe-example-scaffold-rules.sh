#!/bin/sh
# SPFN Mobile — proves the example-scaffold checks refuse what they must.
#
# Section 14 of the validator is three READERS: it scans example sources for a call
# descriptor, scans generated Swift for `dismiss`, and reads a case table to see whether
# every cell is covered. A reader is the shape of check that goes quiet rather than red —
# a reader that read nothing finds no hits, and no hits is what a clean tree also produces
# (docs/IMPLEMENTATION-PITFALLS.md P7). So the probe asks both questions of each of them:
# does it bite, and does it fail red when it cannot run.
#
#   a. a call descriptor named outside a generated services directory fails, naming the file;
#   b. one named INSIDE a generated services directory still passes, so the exemption is a
#      path rule and not a blanket one;
#   l. the same reference in a HARNESS source that is not exempt fails — the second
#      consuming app is held to the same boundary as the first;
#   m. one more of them in `HarnessModel.swift`, which IS exempt by name, still passes;
#   n. an exemption list naming a file that is not in the tree fails, so the escape hatch
#      carries the floor every reader here carries.
#   c. a descriptor scan that reads no source fails instead of reporting none;
#   d. `@Environment(\.dismiss)` in a generated example view fails;
#   e. a dismiss scan that reads no source fails instead of reporting none;
#   f. a cell whose runner names Maestro and whose flow file is gone fails;
#   g. a cell whose runner names the JVM and which no test names fails — including when
#      the tree still MENTIONS it, because a cell id in a comment is not a case;
#   h. a case table nothing can read cells out of fails instead of reporting full coverage;
#   i. a bare `- back` in a device cell's flow fails, naming the cell;
#   j. a flow scan that read no flow fails instead of reporting every flow clean;
#   k. a test scan that matched no case fails instead of reporting every cell covered.
#
# c and e run a ROOT-pinned copy of the validator whose own input has been taken away,
# because their subject is what the check does when it cannot read — the one condition that
# cannot be produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-example-scaffold-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-example-probe.XXXXXX")

ACTIVITY=examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/MainActivity.kt
SERVICE=examples/ios-swiftui/Generated/Services/DeviceApprovalService.swift
VIEW=examples/ios-swiftui/Generated/Views/EnterCodeView.swift
CASES=examples/ui-spec/generated/device-approval.cases.json
FLOW=examples/ui-spec/generated/flows/u1.yaml
CELLTEST=examples/android-compose/src/test/kotlin/xyz/superfunction/spfn/example/CellTest.kt

# The second consuming app: one hand-written source that may NOT name a descriptor, and
# the one that may.
HARNESS_SCREEN=tools/harness/android/src/main/kotlin/xyz/superfunction/spfn/harness/HarnessScreen.kt
HARNESS_MODEL=tools/harness/ios/Sources/HarnessModel.swift

cp "$ACTIVITY" "$TMP/activity.bak"
cp "$SERVICE" "$TMP/service.bak"
cp "$VIEW" "$TMP/view.bak"
cp "$CASES" "$TMP/cases.bak"
cp "$FLOW" "$TMP/flow.bak"
cp "$CELLTEST" "$TMP/celltest.bak"
cp "$HARNESS_SCREEN" "$TMP/harness-screen.bak"
cp "$HARNESS_MODEL" "$TMP/harness-model.bak"

restore_files()
{
    cp "$TMP/activity.bak" "$ACTIVITY"
    cp "$TMP/service.bak" "$SERVICE"
    cp "$TMP/view.bak" "$VIEW"
    cp "$TMP/cases.bak" "$CASES"
    cp "$TMP/flow.bak" "$FLOW"
    cp "$TMP/celltest.bak" "$CELLTEST"
    cp "$TMP/harness-screen.bak" "$HARNESS_SCREEN"
    cp "$TMP/harness-model.bak" "$HARNESS_MODEL"
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

# Section 14 begins at its own heading and runs to the end of the report. Every assertion
# below is scoped to it: this repository's validator has failing checks in other sections
# for reasons that have nothing to do with the example apps, and a probe that keyed on the
# validator's exit status would report those as its own evidence.
EXAMPLE_SECTION='/^14\. the apps that consume/,$p'

expect_example_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$EXAMPLE_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the example scaffold section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

expect_example_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$EXAMPLE_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$EXAMPLE_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# --- a, b. the descriptor boundary -------------------------------------------
sed 's#^class MainActivity : ComponentActivity()$#private val leak = SpfnGeneratedCalls.authDeviceInfo;\nclass MainActivity : ComponentActivity()#' \
    "$TMP/activity.bak" > "$ACTIVITY"
expect_example_fail 'a call descriptor named in an app source fails, naming the file' \
    'a call descriptor is named outside the generated services'

# The same reference, in the one directory that is allowed it. If this failed, the check
# would be a ban on the string rather than a rule about where the string may appear — and
# the generated services themselves would not pass their own validator.
sed 's#^import SPFNGenerated$#import SPFNGenerated\nprivate let extra = SPFNGeneratedCalls.authDeviceInfo#' \
    "$TMP/service.bak" > "$SERVICE"
expect_example_clean 'a call descriptor inside a generated services directory is still allowed'

expect_unrunnable 'a descriptor scan that reads no source fails instead of reporting none' \
    'it did not run' \
    '/example-sources.txt/ s#-name .\*\.swift. -o -name .\*\.kt.#-name "*.no-such-suffix"#'

# --- l, m, n. the second consuming app, and its two named exemptions ----------
# tools/harness is the harness, and since it drives the generated approval screens it is
# a consumer of the scaffold exactly as examples/ is. The rule therefore reaches it, and
# the two `HarnessModel` files are the whole of the exception: they call three operations
# the SDK wraps in nothing.
sed 's#^private val Ink: Color = Color.Black;$#private val leak = SpfnGeneratedCalls.authDeviceInfo;\nprivate val Ink: Color = Color.Black;#' \
    "$TMP/harness-screen.bak" > "$HARNESS_SCREEN"
expect_example_fail 'a call descriptor named in a harness source that is not exempt fails' \
    'HarnessScreen.kt'

# The same reference, in the one hand-written file that is allowed it. If this failed, the
# exemption would not be doing its job — and the harness could not reach revocation,
# `keys.list` or the device-code login at all.
sed 's#^import SPFNUI$#import SPFNUI\nprivate let extra = SPFNGeneratedCalls.authDeviceInfo#' \
    "$TMP/harness-model.bak" > "$HARNESS_MODEL"
expect_example_clean 'a call descriptor in the hand-written file exempted by name is still allowed'

# The exemption is a list of names, and a name that is in the list and not in the tree is
# an exception to nothing. Probed on a copy of the validator rather than by deleting the
# file, because deleting `HarnessModel.swift` is not a state this tree can be left in.
expect_unrunnable 'an exemption naming a file that is not in the tree fails' \
    'the exemption list is stale' \
    's#^tools/harness/ios/Sources/HarnessModel.swift$#tools/harness/ios/Sources/NoSuchModel.swift#'

# --- d, e. the dismiss refusal, under a generated directory ------------------
sed 's#^    @State private var userCode: String = ""$#    @State private var userCode: String = ""\n    @Environment(\\.dismiss) private var dismiss#' \
    "$TMP/view.bak" > "$VIEW"
expect_example_fail 'the SwiftUI dismiss environment value in a generated example view fails' \
    'a generated source reaches the dismiss environment value'

expect_unrunnable 'a generated dismiss scan that reads no source fails instead of reporting none' \
    'it did not run' \
    "/example-dismiss-files.txt/ s#-name '\\*\\.swift'#-name '*.no-such-suffix'#"

# The dismiss scan reads BOTH apps' generated Swift, so the harness's root has to be one
# of the two it is pointed at. Taking that root away is what says so: the count floor
# fires rather than the scan quietly reading half of what it claims to.
expect_unrunnable 'a dismiss scan pointed at only one of the two generated roots fails' \
    'it did not run' \
    "s#^SCAFFOLD_SWIFT=.*#SCAFFOLD_SWIFT='examples/ios-swiftui/Generated'#"

# --- f, g. cell coverage ------------------------------------------------------
rm -f "$FLOW"
expect_example_fail 'a Maestro cell whose flow file is gone fails, naming the cell' \
    'u1:no-flow'

sed 's/u5/z5/g' "$TMP/celltest.bak" > "$CELLTEST"
expect_example_fail 'a JVM cell that no test names fails, naming the cell' \
    'u5:no-test'

# The same cell, still MENTIONED by the suite and no longer covered by it: only the case
# declaration and the assertion are renamed, and the header comment listing every cell the
# file covers is left alone. A scan that took any occurrence of the id as coverage passed
# this — the comment proved itself (docs/IMPLEMENTATION-PITFALLS.md P7).
sed -e 's/`u5 closes the flow and empties its stack`/`z5 closes the flow and empties its stack`/' \
    -e 's/assertCell("u5"/assertCell("z5"/' "$TMP/celltest.bak" > "$CELLTEST"
expect_example_fail 'a cell mentioned in a comment but declared by no case fails, naming the cell' \
    'u5:no-test'

# --- i, j. the bare system back -----------------------------------------------
# Maestro's `back` is Android's command and completes on iOS without doing anything, so a
# flow carrying one fails at its next assertion rather than at the step
# (docs/IMPLEMENTATION-PITFALLS.md P22). Appended at the TOP level, which is the only shape
# the rule forbids: the generated flows still hold an indented one inside their
# Android-only `runFlow` block and must stay clean.
{ cat "$TMP/flow.bak"; printf -- '- back\n'; } > "$FLOW"
expect_example_fail 'a bare system back in a device cell'"'"'s flow fails, naming the cell' \
    'u1:bare-back'

expect_unrunnable 'a flow scan that read no flow fails instead of reporting them clean' \
    'the flow scan read 0 device-cell flows' \
    's#^EXAMPLE_FLOWS=.*#EXAMPLE_FLOWS=/nonexistent-flows#'

expect_unrunnable 'a test scan that matched no case fails instead of reporting every cell covered' \
    'the test scan matched case declarations for 0 JVM cells' \
    's#^EXAMPLE_TESTS=.*#EXAMPLE_TESTS=/nonexistent-tests#'

# --- h. a table nothing can read ---------------------------------------------
# The floor, exercised by taking the table's own key away rather than by editing the
# validator: a reader that found no cells would otherwise report every cell covered.
sed 's/"id":/"identifier":/g' "$TMP/cases.bak" > "$CASES"
expect_example_fail 'a case table nothing can read cells out of fails instead of reporting coverage' \
    'it did not run'

# --- the unmodified tree still reads clean -----------------------------------
expect_example_clean 'the example scaffold section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
