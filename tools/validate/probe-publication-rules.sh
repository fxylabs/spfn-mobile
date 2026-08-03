#!/bin/sh
# SPFN Mobile — proves the publication-transition validator rules refuse what they must.
#
# The transition change set (D3/D4/D7) is a relaxation: credentials went from "banned
# outright" to "lookup configuration only", signing from "banned" to "in-memory key
# only", and one workflow may now speak publication. A relaxation is where holes are
# born, so every new admission has its refusals probed here, against the real
# validator, on real (temporarily mutated, cp-backed) files:
#
#   a. a committed literal credential — block form and call form — fails;
#   b. a remote maven repository beyond the local staging target fails;
#   c. a committed key file (.asc/.gpg/secring) fails;
#   d. an automatic workflow trigger (push:) fails;
#   e. a credential-shaped key in gradle.properties fails;
#   f. signing configuration outside the gated root fails;
#   g. an unlisted secret name or a non-Central host in publish-central.yml fails;
#   h. the one admitted lookup form (credentials(PasswordCredentials::class)) passes.
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
RC_WORKFLOW=.github/workflows/release-candidate.yml
PUBLISH_WORKFLOW=.github/workflows/publish-central.yml
PLANTED_KEY=android/spfn-core/probe-planted.asc

cp "$ROOT_BUILD" "$TMP/root-build.bak"
cp "$MODULE_BUILD" "$TMP/module-build.bak"
cp "$PROPERTIES" "$TMP/properties.bak"
cp "$RC_WORKFLOW" "$TMP/rc-workflow.bak"
cp "$PUBLISH_WORKFLOW" "$TMP/publish-workflow.bak"

restore()
{
    if [ -d "$TMP" ]
    then
        cp "$TMP/root-build.bak" "$ROOT_BUILD"
        cp "$TMP/module-build.bak" "$MODULE_BUILD"
        cp "$TMP/properties.bak" "$PROPERTIES"
        cp "$TMP/rc-workflow.bak" "$RC_WORKFLOW"
        cp "$TMP/publish-workflow.bak" "$PUBLISH_WORKFLOW"
        rm -f "$PLANTED_KEY"
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
    LABEL=$1
    MARKER=$2
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        fail "$LABEL — the validator passed"
    elif grep -qF -- "$MARKER" "$TMP/run.log"
    then
        pass "$LABEL"
    else
        fail "$LABEL — the validator failed, but not on the expected rule"
    fi
    cp "$TMP/root-build.bak" "$ROOT_BUILD"
    cp "$TMP/module-build.bak" "$MODULE_BUILD"
    cp "$TMP/properties.bak" "$PROPERTIES"
    cp "$TMP/rc-workflow.bak" "$RC_WORKFLOW"
    cp "$TMP/publish-workflow.bak" "$PUBLISH_WORKFLOW"
    rm -f "$PLANTED_KEY"
}

printf 'publication rules probe\n'

# --- a. literal credentials, both forms --------------------------------------------
printf 'credentials { username = "leaked-user"; password = "leaked-pass" }\n' >> "$ROOT_BUILD"
expect_fail 'a committed credentials block with literal values fails' \
    'credential configuration that is not a pure lookup'

printf 'credentials(someUnreviewedProvider)\n' >> "$ROOT_BUILD"
expect_fail 'a call-form credentials configuration that is not a lookup fails' \
    'credential configuration that is not a pure lookup'

printf 'val probeUser = mapOf("username" to 1); val username = "leaked-literal"\n' >> "$MODULE_BUILD"
expect_fail 'a literal username value in a module build script fails' \
    'literal credential value committed'

# --- b. a remote repository beyond the staging target ------------------------------
printf 'maven { url = uri("https://repo.example.invalid/m2") }\n' >> "$ROOT_BUILD"
expect_fail 'a second, remote maven repository in the root fails' \
    'exactly one maven repository block'

# --- c. a committed key file -------------------------------------------------------
printf 'probe: not a real key, planted by probe-publication-rules.sh\n' > "$PLANTED_KEY"
expect_fail 'a committed .asc key file fails' \
    'credential-shaped files present'

# --- d. an automatic workflow trigger ----------------------------------------------
printf 'push:\n' >> "$RC_WORKFLOW"
expect_fail 'a push trigger on a workflow fails' \
    'has no automatic trigger'

# --- e. a credential-shaped committed property -------------------------------------
printf 'centralPortalToken=probe-not-a-real-value\n' >> "$PROPERTIES"
expect_fail 'a credential-shaped key committed in gradle.properties fails' \
    'commits no credential-shaped key'

# --- f. signing configuration outside the gated root -------------------------------
printf 'pluginManager.apply("signing")\n' >> "$MODULE_BUILD"
expect_fail 'signing configuration in a module build script fails' \
    'no signing configuration outside the gated root'

# --- g. the publish workflow boundary ----------------------------------------------
printf '      - name: probe\n        run: echo "${{ secrets.UNLISTED_PROBE_TOKEN }}"\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'an unlisted secret name in publish-central.yml fails' \
    'unexpected secrets'

printf '      - name: probe\n        run: curl https://uploads.example.invalid/put\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a non-Central host in publish-central.yml fails' \
    'unexpected hosts'

# --- h. the admitted lookup form stays admitted ------------------------------------
printf 'val probeLookupOnly = "credentials(PasswordCredentials::class)"\n' >> "$ROOT_BUILD"
if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    pass 'the pure lookup form credentials(PasswordCredentials::class) is admitted'
else
    fail 'the lookup form was refused; the rule is broader than the policy'
fi
cp "$TMP/root-build.bak" "$ROOT_BUILD"

# --- the unmodified tree still passes ----------------------------------------------
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
