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
#      guard on FlowHost.swift is the whole of what makes that true;
#   m. a host vocabulary name declared on one platform only fails, naming it. `HostEntry`
#      has no cases and no public methods, so `compare_ui_type` cannot see it at all — it is
#      exactly the shape of type that goes quiet, and a `HostEntry` only iOS has is a stack
#      only iOS can put two flows on.
#
# and the same two questions of section 15, which is section 13's shape applied to the
# VISUAL vocabulary — the tokens, the strings and the component set:
#
#   j. a token deleted from one platform's table fails, naming the side it is missing from;
#   k. a component that exists on one platform only fails;
#   l. a token table this reader can extract nothing out of fails instead of reporting
#      parity — the floor, which is the half of a reader that goes quiet rather than red.
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
KOTLIN_HOST_STACK=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/HostStack.kt
KOTLIN_TOKENS=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnTokens.kt
SWIFT_TOKENS=Sources/SPFNUI/Tokens/SPFNTokens.swift
SWIFT_BUTTONS=Sources/SPFNUI/Components/Buttons.swift

cp "$SWIFT_LOADABLE" "$TMP/swift-loadable.bak"
cp "$SWIFT_FLOW" "$TMP/swift-flow.bak"
cp "$SWIFT_HOST" "$TMP/swift-host.bak"
cp "$KOTLIN_LOADABLE" "$TMP/kotlin-loadable.bak"
cp "$KOTLIN_FLOW" "$TMP/kotlin-flow.bak"
cp "$KOTLIN_HOST_STACK" "$TMP/kotlin-host-stack.bak"
cp "$KOTLIN_TOKENS" "$TMP/kotlin-tokens.bak"
cp "$SWIFT_TOKENS" "$TMP/swift-tokens.bak"
cp "$SWIFT_BUTTONS" "$TMP/swift-buttons.bak"

restore_files()
{
    cp "$TMP/swift-loadable.bak" "$SWIFT_LOADABLE"
    cp "$TMP/swift-flow.bak" "$SWIFT_FLOW"
    cp "$TMP/swift-host.bak" "$SWIFT_HOST"
    cp "$TMP/kotlin-loadable.bak" "$KOTLIN_LOADABLE"
    cp "$TMP/kotlin-flow.bak" "$KOTLIN_FLOW"
    cp "$TMP/kotlin-host-stack.bak" "$KOTLIN_HOST_STACK"
    cp "$TMP/kotlin-tokens.bak" "$KOTLIN_TOKENS"
    cp "$TMP/swift-tokens.bak" "$SWIFT_TOKENS"
    cp "$TMP/swift-buttons.bak" "$SWIFT_BUTTONS"
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

# Section 15 begins at its own heading. Scoped separately from section 13 even though 13's
# range already runs to the end of the report: a mutation aimed at the visual vocabulary has
# to be shown to fail THAT section, not merely to fail somewhere below line 13.
TOKEN_SECTION='/^15\. the visual vocabulary/,$p'

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

# --- m. a name only one platform declares -------------------------------------------
# `HostEntry` renamed on the Kotlin half and left alone on the Swift one. It is the cheapest
# real divergence for a type with no members: a merge that renamed one side, or a second
# platform that never got the type at all. Neither half's `compare_ui_type` reads it — there
# is nothing in it to read — so without the name check the section would report parity over
# a vocabulary one platform does not have.
sed 's/^public data class HostEntry(/public data class HostCell(/' \
    "$TMP/kotlin-host-stack.bak" > "$KOTLIN_HOST_STACK"
expect_ui_fail 'a host vocabulary name declared on one platform only fails, naming it' \
    'only in Swift: HostEntry'

# --- i. SwiftUI is Apple-only, and SPFNUI builds on Linux --------------------------
sed 's/^#if canImport(SwiftUI)$//; s/^#endif$//' "$TMP/swift-host.bak" > "$SWIFT_HOST"
expect_fail_anywhere 'import SwiftUI outside a canImport guard fails in a module that builds on Linux' \
    'an Apple-only framework is imported unconditionally'

# --- j, k, l. the visual vocabulary is two halves too ------------------------------
# Runs the validator and expects SECTION 15 to refuse, on the named rule.
expect_token_fail()
{
    LABEL=$1
    MARKER=$2
    sh tools/validate/validate.sh > "$TMP/run.log" 2>&1 || true
    sed -n "$TOKEN_SECTION" "$TMP/run.log" > "$TMP/token-section.log"
    if ! grep -q '^  FAIL' "$TMP/token-section.log"
    then
        fail "$LABEL — the visual vocabulary section passed"
    elif grep -qF -- "$MARKER" "$TMP/token-section.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the section failed, but not on the expected rule"
    fi
    restore_files
}

# One token taken off the Kotlin half. It is the cheapest real divergence there is — a
# component written against `SPFNTokens.accent` on one platform and nothing on the other —
# and it is exactly what a merge that dropped a line would leave behind.
sed '/public val accent: Color,/d' "$TMP/kotlin-tokens.bak" > "$KOTLIN_TOKENS"
expect_token_fail 'a token deleted from the Kotlin table fails, naming the side it is missing from' \
    'tokens differs between platforms'

# One component that exists on one platform only. A spec `role` the generator can emit for
# one app and not the other, which would surface as a compile error in a generated file.
sed 's/^public struct DestructiveButton: View$/public struct RuinousButton: View/' \
    "$TMP/swift-buttons.bak" > "$SWIFT_BUTTONS"
expect_token_fail 'a component only one platform has fails, naming it' \
    'the component set differs'

# A table whose declarations this reader cannot see. The file is still there and still
# names tokens — every `let` is simply spelled in a way the grammar does not admit — which
# is the condition the `-f` guard above cannot produce: two sides that both exist, one of
# which yielded nothing. Without the floor the empty set would agree with the full one and
# the section would report parity over a table it never read (P7).
# `sed -E` here for the same reason the reader it probes uses it: BSD sed reads `\?` in a
# basic expression as two literal characters, so on a Mac this mutation changed nothing and
# the probe reported the floor unbitten (P28).
sed -E 's/^([[:space:]]*)public (static )?let /\1public \2var /' \
    "$TMP/swift-tokens.bak" > "$SWIFT_TOKENS"
expect_token_fail 'a token table this reader can extract nothing from fails instead of reporting parity' \
    'the extraction did not run'

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
