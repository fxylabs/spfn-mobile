#!/bin/sh
# SPFN Mobile — generates the internal CocoaPods compatibility fixture.
#
# The fixture exists to prove one thing: the exact same Swift sources and the exact
# same module graph can be described to CocoaPods without a second implementation.
# It is NOT a distribution channel. See tools/cocoapods-compat/README.md.
#
# Inputs (single sources of truth): tools/module-graph.json, VERSION
# Usage:
#   sh tools/cocoapods-compat/generate-podspec.sh            # print to stdout
#   sh tools/cocoapods-compat/generate-podspec.sh --write    # write the fixture file
#
# tools/validate/validate.sh regenerates and diffs, so a hand-edited fixture fails.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
GRAPH="$ROOT/tools/module-graph.json"
OUT="$SCRIPT_DIR/generated/SPFNMobileCompatFixture.podspec"
POD_NAME="SPFNMobileCompatFixture"

VERSION=$(tr -d '[:space:]' < "$ROOT/VERSION")

emit()
{
    cat <<PODSPEC_HEADER
# GENERATED FILE — DO NOT EDIT.
# Regenerate with: sh tools/cocoapods-compat/generate-podspec.sh --write
#
# INTERNAL COMPATIBILITY FIXTURE. This podspec is never published. CocoaPods is not
# a supported SPFN Mobile distribution channel; Swift Package Manager is the primary
# iOS channel. No pod name is claimed, no trunk publication is planned or promised,
# and tools/validate/validate.sh fails if a trunk publication command ever appears.
#
# It is generated from tools/module-graph.json and VERSION so it can never drift
# into a second implementation: every subspec points at the same Sources/ tree the
# SwiftPM manifest uses.
#
# Deliberately absent:
#   - s.platform / deployment targets and s.swift_versions — the D5 baseline lives in
#     Package.swift; this unpublished fixture does not restate a support surface
#   - a resolvable source / tag        (nothing is published and no tag exists)

Pod::Spec.new do |s|
  s.name             = '$POD_NAME'
  s.version          = '$VERSION'
  s.summary          = 'Internal, unpublished CocoaPods compatibility fixture for the SPFN Mobile Swift sources.'
  s.description      = <<-DESC
                       Step 1 scaffold fixture. Describes the SwiftPM module graph to CocoaPods
                       from the same sources, purely to keep a single module graph verifiable.
                       Not a supported distribution channel and not published anywhere.
                       DESC
  s.homepage         = 'https://github.com/fxylabs/spfn-mobile'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.authors          = { 'FXY Inc.' => 'https://github.com/fxylabs' }
  s.source           = { :git => 'file://LOCAL-ONLY-NO-PUBLISHED-SOURCE', :tag => 'NO-TAG-EXISTS' }
PODSPEC_HEADER

    grep '"swiftTarget"' "$GRAPH" | while IFS= read -r line
    do
        target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
        deps=$(printf '%s' "$line" | sed -n 's/.*"swiftDependsOn": \[\([^]]*\)\].*/\1/p')

        printf '\n'
        printf "  s.subspec '%s' do |sp|\n" "$target"
        printf "    sp.source_files = 'Sources/%s/**/*.swift'\n" "$target"

        # A trailing newline is required: POSIX `read` discards a final unterminated
        # line, which would silently drop the last dependency edge.
        printf '%s\n' "$deps" | tr ',' '\n' | sed 's/[" ]//g' | while IFS= read -r dep
        do
            [ -n "$dep" ] || continue
            printf "    sp.dependency '%s/%s'\n" "$POD_NAME" "$dep"
        done

        printf '  end\n'
    done

    printf '\nend\n'
}

if [ "${1:-}" = "--write" ]
then
    mkdir -p "$SCRIPT_DIR/generated"
    emit > "$OUT"
    printf 'wrote %s\n' "$OUT"
else
    emit
fi
