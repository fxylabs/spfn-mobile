#!/bin/sh
# SPFN Mobile — proves the publication and dependency-source rules refuse what they must.
#
# Section 7 of the validator holds two properties with the fewest checks that hold them:
# publication is disabled, and dependencies come only from reviewed sources. Section 3 holds
# the committed tree free of key material. Each refusal is exercised here, against the real
# validator, on real (temporarily mutated, cp-backed) files:
#
#   a. a committed publishing flag of true fails;
#   b. a publication block in a module build script fails;
#   c. a second, remote maven repository in the root fails;
#   d. a maven repository in a module build script fails;
#   e. a repository beyond google(), mavenCentral() and gradlePluginPortal() fails;
#   f. dependency verification switched off fails;
#   g. a committed key file (.asc) fails;
#   h. the unmodified tree still passes.
#
# The Swift half of "dependency sources constrained" — a package the module graph does not
# declare — is tools/validate/probe-social-adapter-rules.sh's. The actions a workflow may
# use are tools/validate/probe-ci-actions-rules.sh's.
#
# Offline, zero toolchain: it runs tools/validate/validate.sh repeatedly. Mutations are
# made on cp copies and restored by a trap on every exit path.
#
#   sh tools/validate/probe-publication-rules.sh

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

TMP=$(mktemp -d "${TMPDIR:-/tmp}/spfn-pubrules-probe.XXXXXX")

ROOT_BUILD=build.gradle.kts
MODULE_BUILD=android/spfn-core/build.gradle.kts
PROPERTIES=gradle.properties
METADATA=gradle/verification-metadata.xml
PLANTED_KEY=android/spfn-core/probe-planted.asc

cp "$ROOT_BUILD" "$TMP/root-build.bak"
cp "$MODULE_BUILD" "$TMP/module-build.bak"
cp "$PROPERTIES" "$TMP/properties.bak"
cp "$METADATA" "$TMP/metadata.bak"

restore_files()
{
    cp "$TMP/root-build.bak" "$ROOT_BUILD"
    cp "$TMP/module-build.bak" "$MODULE_BUILD"
    cp "$TMP/properties.bak" "$PROPERTIES"
    cp "$TMP/metadata.bak" "$METADATA"
    rm -f "$PLANTED_KEY"
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

# Runs the validator expecting failure on a specific rule, then restores every file.
expect_fail()
{
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        fail "$1 — the validator passed"
    elif grep -qF -- "$2" "$TMP/run.log"
    then
        pass "$1"
    else
        fail "$1 — the validator failed, but not on the expected rule"
    fi
    restore_files
}

printf 'publication rules probe\n'

sed 's/^spfn.publishing.enabled=false$/spfn.publishing.enabled=true/' "$TMP/properties.bak" > "$PROPERTIES"
expect_fail 'a committed publishing flag of true fails' \
    'Gradle publishing disabled'

printf 'plugins { id("maven-publish") }\n' >> "$MODULE_BUILD"
expect_fail 'a publication plugin in a module build script fails' \
    'publication configured outside the gated root'

printf 'maven { url = uri("https://repo.example.invalid/m2") }\n' >> "$ROOT_BUILD"
expect_fail 'a second, remote maven repository in the root fails' \
    'exactly one maven repository block'

printf 'repositories { maven { url = uri("https://repo.example.invalid/m2") } }\n' >> "$MODULE_BUILD"
expect_fail 'a maven repository in a module build script fails' \
    'an arbitrary maven repository is declared'

printf 'repositories {\n    mavenLocal()\n}\n' >> "$MODULE_BUILD"
expect_fail 'a repository beyond the three the toolchain needs fails' \
    'unexpected dependency repositories'

sed 's#<verify-metadata>true</verify-metadata>#<verify-metadata>false</verify-metadata>#' "$TMP/metadata.bak" > "$METADATA"
expect_fail 'dependency verification switched off fails' \
    'Gradle dependency verification is enabled'

printf 'probe: not a real key, planted by probe-publication-rules.sh\n' > "$PLANTED_KEY"
expect_fail 'a committed .asc key file fails' \
    'credential-shaped files present'

if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    pass 'the unmodified tree passes the validator after every restoration'
else
    fail 'the tree no longer passes; a probe leaked a mutation'
fi

printf '%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1
