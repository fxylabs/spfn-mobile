#!/bin/sh
# SPFN Mobile — proves the ui vocabulary parity check refuses what it must.
#
# Section 13 of the validator is a READER: it extracts names out of two source trees and
# compares them. A reader is the shape of check that goes quiet rather than red — a reader
# that read nothing produces an empty set, two empty sets agree, and the section reports
# parity having read no code at all (docs/IMPLEMENTATION-PITFALLS.md P7). So the probe
# asks both questions of it: does it bite, and does it fail red when it cannot run.
#
#   a. a renamed Swift case fails, naming the type;
#   b. a renamed Kotlin state fails;
#   c. a Flow method that exists on one platform only fails, in either direction — and
#      still fails when it is spelled with a MODIFIER (`public suspend fun`,
#      `public mutating func`), because a grammar that recognised only the bare spelling
#      would not report that method as extra, it would not see it at all;
#   d. a Swift case list written on ONE line is still read — first that it passes, then
#      that a rename inside that one line still fails, because a form the reader silently
#      skips would pass the first half and the second;
#   e. a Swift extraction that reads no file fails instead of reporting parity;
#   f. so does a Kotlin one;
#   g. `@Environment(\.dismiss)` in an SPFNUI source fails;
#   h. a dismiss scan that reads no source fails instead of reporting none;
#   i. `import SwiftUI` outside a canImport guard fails — SPFNUI builds on Linux, so the
#      guard on FlowHost.swift is the whole of what makes that true.
#
# e, f and h run a ROOT-pinned copy of the validator whose own input has been taken away,
# because their subject is what the check does when it cannot read — the one condition
# that cannot be produced by editing the tree without destroying it.
#
# Offline, zero toolchain. Mutations are made on cp copies and restored by a trap on every
# exit path; `git checkout --` is never used, because it restores from HEAD and eats
# uncommitted work.
#
#   sh tools/validate/probe-ui-vocabulary-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-ui-probe.XXXXXX")

SWIFT_LOADABLE=Sources/SPFNUI/Loadable.swift
SWIFT_FLOW=Sources/SPFNUI/Flow.swift
SWIFT_HOST=Sources/SPFNUI/FlowHost.swift
KOTLIN_LOADABLE=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Loadable.kt
KOTLIN_FLOW=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Flow.kt

cp "$SWIFT_LOADABLE" "$TMP/swift-loadable.bak"
cp "$SWIFT_FLOW" "$TMP/swift-flow.bak"
cp "$SWIFT_HOST" "$TMP/swift-host.bak"
cp "$KOTLIN_LOADABLE" "$TMP/kotlin-loadable.bak"
cp "$KOTLIN_FLOW" "$TMP/kotlin-flow.bak"

restore_files()
{
    cp "$TMP/swift-loadable.bak" "$SWIFT_LOADABLE"
    cp "$TMP/swift-flow.bak" "$SWIFT_FLOW"
    cp "$TMP/swift-host.bak" "$SWIFT_HOST"
    cp "$TMP/kotlin-loadable.bak" "$KOTLIN_LOADABLE"
    cp "$TMP/kotlin-flow.bak" "$KOTLIN_FLOW"
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

# Section 13 begins at its own heading and runs to the end of the report. Every assertion
# below is scoped to it on purpose: this repository's validator has failing checks in other
# sections for reasons that have nothing to do with the ui module, and a probe that keyed
# on the validator's exit status would report those as its own evidence.
UI_SECTION='/^13\. the ui vocabulary/,$p'

# Runs the validator and expects section 13 to refuse, on the named rule.
expect_ui_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$UI_SECTION" "$TMP/run.log" > "$TMP/section.log"
    if ! grep -q '^  FAIL' "$TMP/section.log"
    then
        fail "$LABEL — the ui vocabulary section passed"
    elif grep -qF -- "$MARKER" "$TMP/section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# Runs the validator and expects section 13 to be clean. Used where the point is that a
# legal spelling is READ rather than skipped: a reader that skipped it would also pass.
expect_ui_clean()
{
    LABEL=$1
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if sed -n "$UI_SECTION" "$TMP/run.log" | grep -q '^  FAIL'
    then
        fail "$LABEL — the section reported a failure"
    else
        pass "$LABEL"
    fi
    restore_files
}

# Runs the validator expecting a failure ANYWHERE in the report, for a rule that does not
# live in section 13.
expect_fail_anywhere()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    if grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator did not refuse it"
    fi
    restore_files
}

# Runs a ROOT-pinned copy of the validator whose own input has been taken away, and
# expects the check to say so rather than to report a clean read.
expect_unrunnable()
{
    LABEL=$1
    MARKER=$2
    EXPRESSION=$3
    COPY="$TMP/validator-copy.sh"
    sed -e "s#^ROOT=.*#ROOT=$ROOT#" -e "$EXPRESSION" tools/validate/validate.sh > "$COPY"
    sh "$COPY" > "$TMP/run.log" 2>&1 || true
    sed -n "$UI_SECTION" "$TMP/run.log" > "$TMP/section.log"
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

# Rewrites Loadable's four Swift cases onto one line, the other legal spelling of the same
# enum. `sed` from the backup, so the mutation never compounds.
one_line_swift_cases()
{
    awk '
        /^    case loading$/ { print "    case loading, ready(Value), empty, error(SPFNErrorEnvelope)"; next }
        /^    case ready\(Value\)$/ { next }
        /^    case empty$/ { next }
        /^    case error\(SPFNErrorEnvelope\)$/ { next }
        { print }
    ' "$TMP/swift-loadable.bak"
}

printf 'ui vocabulary rules probe\n'

# --- a, b. a renamed state on either platform is a divergence ----------------------
sed 's/^    case empty$/    case blank/' "$TMP/swift-loadable.bak" > "$SWIFT_LOADABLE"
expect_ui_fail 'a renamed Swift case fails, naming the type and the side it is missing from' \
    'Loadable differs between platforms'

sed 's/public data object Empty : Loadable<Nothing>/public data object Blank : Loadable<Nothing>/' \
    "$TMP/kotlin-loadable.bak" > "$KOTLIN_LOADABLE"
expect_ui_fail 'a renamed Kotlin state fails' \
    'Loadable differs between platforms'

# --- c. a Flow method that exists on one platform only, in both directions ---------
sed '/^    public func replace(_ route: Route)$/,/^    }$/d' "$TMP/swift-flow.bak" > "$SWIFT_FLOW"
expect_ui_fail 'a Flow method missing from the Swift half fails' \
    'Flow differs between platforms'

sed 's#^    /\*\* Closes the flow and forgets its routes\. A no-op on a flow that is already closed\. \*/$#    public fun dismiss() { close(); }#' \
    "$TMP/kotlin-flow.bak" > "$KOTLIN_FLOW"
expect_ui_fail 'a Flow method only the Kotlin half has fails' \
    'Flow differs between platforms'

# A method only one platform has, spelled with a modifier between `public` and the keyword.
# It is the same divergence as the two cases above and the reader has to reach it through a
# grammar rather than through a fixed string: `suspend` is the modifier a Kotlin coroutine
# API grows first, and `static`/`mutating` are Swift's. A reader blind to them reports
# parity over a vocabulary that differs.
sed 's#^    public fun push(route: R)$#    public suspend fun reopen(at: List<R>) { open(at); }\n\n    public fun push(route: R)#' \
    "$TMP/kotlin-flow.bak" > "$KOTLIN_FLOW"
expect_ui_fail 'a Kotlin Flow method spelled `public suspend fun` is read, and its absence in Swift fails' \
    'only in Kotlin: reopen'

sed 's#^    public func push(_ route: Route)$#    public mutating func reopen(at stack: [Route]) { }\n\n    public func push(_ route: Route)#' \
    "$TMP/swift-flow.bak" > "$SWIFT_FLOW"
expect_ui_fail 'a Swift Flow method spelled `public mutating func` is read, and its absence in Kotlin fails' \
    'only in Swift: reopen'

# --- d. the one-line case list is read, not skipped --------------------------------
one_line_swift_cases > "$SWIFT_LOADABLE"
expect_ui_clean 'a Swift case list written on one line still matches the Kotlin states'

one_line_swift_cases | sed 's/, empty,/, blank,/' > "$SWIFT_LOADABLE"
expect_ui_fail 'a rename inside a one-line case list still fails, so that form is read rather than skipped' \
    'Loadable differs between platforms'

# --- e, f. a reader that reads nothing is not a reader that found parity -----------
expect_unrunnable 'a Swift extraction that reads no file fails instead of reporting parity' \
    'the extraction did not run' \
    '/ui-swift-files.txt/ s#-name .\*\.swift.#-name "*.no-such-suffix"#'

expect_unrunnable 'a Kotlin extraction that reads no file fails instead of reporting parity' \
    'the extraction did not run' \
    '/ui-kotlin-files.txt/ s#-name .\*\.kt.#-name "*.no-such-suffix"#'

# --- g, h. the dismiss refusal -----------------------------------------------------
sed 's#^    private let entry: FlowEntry$#    private let entry: FlowEntry\n    @Environment(\\.dismiss) private var dismiss#' \
    "$TMP/swift-host.bak" > "$SWIFT_HOST"
expect_ui_fail 'the SwiftUI dismiss environment value in an SPFNUI source fails' \
    'SPFNUI reaches the dismiss environment value'

expect_unrunnable 'a dismiss scan that reads no source fails instead of reporting none' \
    'it did not run' \
    's#^find "$UI_SWIFT_DIR" -name .\*\.swift. | sort > "$TMP/ui-dismiss-files.txt"#find "$UI_SWIFT_DIR" -name "*.no-such-suffix" | sort > "$TMP/ui-dismiss-files.txt"#'

# --- i. SwiftUI is Apple-only, and SPFNUI builds on Linux --------------------------
sed 's/^#if canImport(SwiftUI)$//; s/^#endif$//' "$TMP/swift-host.bak" > "$SWIFT_HOST"
expect_fail_anywhere 'import SwiftUI outside a canImport guard fails in a module that builds on Linux' \
    'an Apple-only framework is imported unconditionally'

# --- the unmodified tree still reads clean -----------------------------------------
expect_ui_clean 'the ui vocabulary section is clean again after every restoration'

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
