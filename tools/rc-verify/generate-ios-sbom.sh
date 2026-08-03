#!/bin/sh
# SPFN Mobile — static CycloneDX SBOM for the Swift package.
#
# Decision D7 (resolved 2026-08-03): the Android SBOM comes from the CycloneDX Gradle
# plugin because the Android SDK has resolved third-party dependencies to enumerate.
# The Swift package has none — `Package.swift` declares zero external packages, held by
# tools/validate/validate.sh — so its SBOM is generated statically: the components are
# this repository's own six library products and nothing else, with the edges read from
# tools/module-graph.json so this file cannot drift from the graph the validator holds.
#
#   sh tools/rc-verify/generate-ios-sbom.sh <output-file.json>
#
# Writes to the named path only. Nothing is committed; the RC harness writes into its
# $TMPDIR output directory.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GRAPH="$ROOT/tools/module-graph.json"

if [ $# -ne 1 ]
then
    printf 'usage: sh tools/rc-verify/generate-ios-sbom.sh <output-file.json>\n' >&2
    exit 2
fi
OUTPUT=$1

VERSION=$(tr -d '[:space:]' < "$ROOT/VERSION")
COMMIT=$(git -C "$ROOT" rev-parse HEAD)
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SERIAL=$(uuidgen | tr 'A-F' 'a-f')

# One module object per line is the graph file's canonical format (its own comment says
# so), which is what makes this parse honest rather than hopeful.
TARGET_LINES=$(grep '"swiftTarget"' "$GRAPH")

{
    printf '{\n'
    printf '  "bomFormat": "CycloneDX",\n'
    printf '  "specVersion": "1.6",\n'
    printf '  "serialNumber": "urn:uuid:%s",\n' "$SERIAL"
    printf '  "version": 1,\n'
    printf '  "metadata": {\n'
    printf '    "timestamp": "%s",\n' "$TIMESTAMP"
    printf '    "component": {\n'
    printf '      "type": "library",\n'
    printf '      "bom-ref": "pkg:swift/spfn-mobile@%s",\n' "$VERSION"
    printf '      "name": "spfn-mobile",\n'
    printf '      "version": "%s",\n' "$VERSION"
    printf '      "description": "SPFN Mobile Swift package. Zero external package dependencies by design: cryptography comes from CryptoKit, which the declared platforms ship. This SBOM is generated statically because there is no third-party dependency graph to resolve."\n'
    printf '    },\n'
    printf '    "properties": [\n'
    printf '      { "name": "spfn:sourceCommit", "value": "%s" },\n' "$COMMIT"
    printf '      { "name": "spfn:externalPackageDependencies", "value": "0" }\n'
    printf '    ]\n'
    printf '  },\n'

    printf '  "components": [\n'
    FIRST=1
    printf '%s\n' "$TARGET_LINES" | while IFS= read -r line
    do
        target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
        if [ "$FIRST" = "1" ]
        then
            FIRST=0
        else
            printf ',\n'
        fi
        printf '    {\n'
        printf '      "type": "library",\n'
        printf '      "bom-ref": "pkg:swift/spfn-mobile/%s@%s",\n' "$target" "$VERSION"
        printf '      "name": "%s",\n' "$target"
        printf '      "version": "%s"\n' "$VERSION"
        printf '    }'
    done
    printf '\n  ],\n'

    printf '  "dependencies": [\n'
    printf '    {\n'
    printf '      "ref": "pkg:swift/spfn-mobile@%s",\n' "$VERSION"
    printf '      "dependsOn": [\n'
    printf '%s\n' "$TARGET_LINES" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p' \
        | awk -v v="$VERSION" '
            NR > 1 { printf ",\n" }
            { printf "        \"pkg:swift/spfn-mobile/%s@%s\"", $0, v }
            END { printf "\n" }'
    printf '      ]\n'
    printf '    },\n'
    printf '%s\n' "$TARGET_LINES" | awk -v v="$VERSION" '
        {
            target = $0; sub(/.*"swiftTarget": "/, "", target); sub(/".*/, "", target)
            deps = $0
            if (deps ~ /"swiftDependsOn": \[\]/)
            {
                deplist = ""
            }
            else
            {
                sub(/.*"swiftDependsOn": \[/, "", deps); sub(/\].*/, "", deps)
                gsub(/[" ]/, "", deps)
                deplist = deps
            }
            if (NR > 1) { printf ",\n" }
            printf "    {\n"
            printf "      \"ref\": \"pkg:swift/spfn-mobile/%s@%s\",\n", target, v
            printf "      \"dependsOn\": ["
            n = split(deplist, arr, ",")
            first = 1
            for (i = 1; i <= n; i++)
            {
                if (arr[i] == "") { continue }
                if (!first) { printf ", " }
                printf "\"pkg:swift/spfn-mobile/%s@%s\"", arr[i], v
                first = 0
            }
            printf "]\n"
            printf "    }"
        }
        END { printf "\n" }'
    printf '  ]\n'
    printf '}\n'
} > "$OUTPUT"

printf 'wrote %s (%s Swift targets, 0 external dependencies)\n' \
    "$OUTPUT" "$(printf '%s\n' "$TARGET_LINES" | wc -l | tr -d ' ')"
