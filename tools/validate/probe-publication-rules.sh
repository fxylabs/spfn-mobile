#!/bin/sh
# SPFN Mobile — proves the publication, dependency-source and build/workflow security
# rules refuse what they must.
#
# Section 7 of the validator holds two properties with the fewest checks that hold them:
# publication is disabled, and dependencies come only from reviewed sources. Section 7a
# holds build scripts and workflows free of secrets and unreviewed commands. Section 3
# holds the committed tree free of key material. Each refusal is exercised here, against
# the real validator, on real (temporarily mutated, cp-backed) files:
#
#   a. a committed publishing flag of true fails;
#   b. a publication block in a module build script fails;
#   c. a second, remote maven repository in the root fails;
#   d. a maven repository in a module build script fails;
#   e. a repository beyond google(), mavenCentral() and gradlePluginPortal() fails;
#   f. dependency verification switched off fails;
#   g. a committed key file (.asc) fails;
#   h. a credential-shaped key in gradle.properties fails;
#   i. credentials that are not a pure lookup — block form and call form — and a literal
#      username fail, while the one admitted lookup form passes;
#   j. a URL literal outside the POM allowlist in the root, a setUrl call, and a URL
#      literal in a module build script fail;
#   k. signing configuration outside the root fails, and removing any root signing or
#      staging-gate pin — or naming a key file in the root — fails;
#   l. a trigger beyond the declared ones fails, in every YAML spelling, as does an
#      unparseable trigger line and a workflow with no trigger at all;
#   m. a gate `run:` that is not a tools/ci script fails;
#   n. an unlisted secret, a non-allowlisted host, a scheme-less network command or a
#      push in publish-central.yml fails, and a secret in any other workflow fails;
#   o. a workflow input interpolated outside an env assignment fails, in every spelling;
#   p. the unmodified tree still passes.
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
GATE_WORKFLOW=.github/workflows/swift.yml
RC_WORKFLOW=.github/workflows/release-candidate.yml
PUBLISH_WORKFLOW=.github/workflows/publish-central.yml
PLANTED_KEY=android/spfn-core/probe-planted.asc

cp "$ROOT_BUILD" "$TMP/root-build.bak"
cp "$MODULE_BUILD" "$TMP/module-build.bak"
cp "$PROPERTIES" "$TMP/properties.bak"
cp "$METADATA" "$TMP/metadata.bak"
cp "$GATE_WORKFLOW" "$TMP/gate-workflow.bak"
cp "$RC_WORKFLOW" "$TMP/rc-workflow.bak"
cp "$PUBLISH_WORKFLOW" "$TMP/publish-workflow.bak"

restore_files()
{
    cp "$TMP/root-build.bak" "$ROOT_BUILD"
    cp "$TMP/module-build.bak" "$MODULE_BUILD"
    cp "$TMP/properties.bak" "$PROPERTIES"
    cp "$TMP/metadata.bak" "$METADATA"
    cp "$TMP/gate-workflow.bak" "$GATE_WORKFLOW"
    cp "$TMP/rc-workflow.bak" "$RC_WORKFLOW"
    cp "$TMP/publish-workflow.bak" "$PUBLISH_WORKFLOW"
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
# The marker is looked for on FAIL lines only: most rules print the same words on their
# `ok` line, and a marker found there proves nothing.
expect_fail()
{
    if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
    then
        fail "$1 — the validator passed"
    elif grep -F '  FAIL  ' "$TMP/run.log" | grep -qF -- "$2"
    then
        pass "$1"
    else
        fail "$1 — the validator failed, but not on the expected rule"
    fi
    restore_files
}

# Writes the root build script without the line holding a fixed string, so the pin the
# validator reads there is gone.
drop_root_line()
{
    grep -vF -- "$1" "$TMP/root-build.bak" > "$ROOT_BUILD"
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

# --- section 7a: build scripts hold no secrets ---------------------------------------
printf 'centralPortalToken=probe-not-a-real-value\n' >> "$PROPERTIES"
expect_fail 'a credential-shaped key committed in gradle.properties fails' \
    'commits no credential-shaped key'

printf 'credentials { username = "leaked-user"; password = "leaked-pass" }\n' >> "$ROOT_BUILD"
expect_fail 'a committed credentials block with literal values fails' \
    'credential configuration that is not a pure lookup'

printf 'credentials(someUnreviewedProvider)\n' >> "$ROOT_BUILD"
expect_fail 'a call-form credentials configuration that is not a lookup fails' \
    'credential configuration that is not a pure lookup'

printf 'val probeUser = mapOf("username" to 1); val username = "leaked-literal"\n' >> "$MODULE_BUILD"
expect_fail 'a literal username value in a module build script fails' \
    'literal credential value committed'

printf 'val probeUrl = uri("https://repo.example.invalid/m2")\n' >> "$ROOT_BUILD"
expect_fail 'a URL literal outside the POM metadata allowlist fails, whatever carries it' \
    'URL literals outside the POM metadata allowlist'

printf 'setUrl(stagingUri)\n' >> "$ROOT_BUILD"
expect_fail 'a setUrl call in the root fails' \
    'never uses setUrl'

printf 'val probeUrl = "https://cdn.example.invalid/artifact"\n' >> "$MODULE_BUILD"
expect_fail 'a URL literal in a module build script fails' \
    'no URL literal outside the root build script'

printf 'pluginManager.apply("signing")\n' >> "$MODULE_BUILD"
expect_fail 'signing configuration in a module build script fails' \
    'no signing configuration outside the gated root'

printf 'apply(plugin = "signing")\n' >> "$MODULE_BUILD"
expect_fail 'the apply(plugin = "signing") spelling fails too' \
    'no signing configuration outside the gated root'

printf 'signing { secretKeyRingFile = "probe" }\n' >> "$ROOT_BUILD"
expect_fail 'a key file or keyring named in the root fails' \
    'root signing names no key file'

drop_root_line 'useInMemoryPgpKeys(signingKey'
expect_fail 'removing the in-memory signing key pin fails' \
    'root signing admits only the in-memory key mechanism'

drop_root_line 'providers.gradleProperty("spfnSigningInMemoryKey")'
expect_fail 'removing the per-run signing key lookup fails' \
    'the signing key arrives as a per-run property lookup'

drop_root_line 'if (publishingEnabled)'
expect_fail 'removing the per-run enablement gate fails' \
    'exists only behind the per-run enablement gate'

drop_root_line 'require(candidate.isAbsolute)'
expect_fail 'removing the absolute staging path requirement fails' \
    'root gate requires an absolute staging path'

drop_root_line '!canonical.path.startsWith(repoRoot.path + File.separator)'
expect_fail 'removing the in-repository staging refusal fails' \
    'root gate refuses a staging path inside the repository'

# --- section 7a: workflows run only when and what was reviewed -----------------------
# Block style, flow style, an event nobody named, an event that does not exist yet: the
# allow-list must refuse them all.
printf 'on:\n  push:\n    branches: [main]\n' >> "$RC_WORKFLOW"
expect_fail 'a block-style push trigger on a manual workflow fails' \
    'declares triggers beyond workflow_dispatch'

printf 'on: [push, workflow_dispatch]\n' >> "$RC_WORKFLOW"
expect_fail 'a flow-style trigger list carrying push fails' \
    'declares triggers beyond workflow_dispatch'

printf 'on:\n  workflow_run:\n    workflows: [swift]\n' >> "$RC_WORKFLOW"
expect_fail 'a workflow_run trigger fails' \
    'declares triggers beyond workflow_dispatch'

printf 'on: [some_future_trigger_kind]\n' >> "$RC_WORKFLOW"
expect_fail 'a trigger kind nobody has named yet fails' \
    'declares triggers beyond workflow_dispatch'

printf 'on:\n  "push":\n    branches: [main]\n' >> "$RC_WORKFLOW"
expect_fail 'a quoted trigger key the parser cannot read fails instead of being skipped' \
    'cannot read'

grep -vE '^(on:|  workflow_dispatch:)' "$TMP/rc-workflow.bak" > "$RC_WORKFLOW"
expect_fail 'a workflow with no trigger fails' \
    'declares no trigger at all'

printf 'on:\n  schedule:\n    - cron: "0 0 * * *"\n' >> "$GATE_WORKFLOW"
expect_fail 'a gate trigger beyond pull_request and push fails' \
    'declares triggers beyond pull_request and push'

printf '      - name: probe\n        run: ./gradlew build\n' >> "$GATE_WORKFLOW"
expect_fail 'a gate run: that is not a tools/ci script fails' \
    'runs a command that is not a tools/ci script'

printf '      - run: swift build\n' >> "$GATE_WORKFLOW"
expect_fail 'the step-first "- run:" spelling fails too' \
    'runs a command that is not a tools/ci script'

printf '      - name: probe\n        run: echo "${{ secrets.GITHUB_TOKEN }}"\n' >> "$RC_WORKFLOW"
expect_fail 'a secret in a workflow other than publish-central.yml fails' \
    'requests no secret and performs no publication'

printf '      - name: probe\n        run: echo "${{ secrets.UNLISTED_PROBE_TOKEN }}"\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'an unlisted secret name in publish-central.yml fails' \
    'unexpected secrets'

printf '      - name: probe\n        run: curl https://uploads.example.invalid/put\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a non-Central host in publish-central.yml fails' \
    'unexpected hosts'

printf '      - name: probe\n        run: curl uploads.example.invalid/put\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a scheme-less network command in publish-central.yml fails' \
    'network commands without an allowlisted host'

printf '      - name: probe\n        run: |\n          curl \\\n            uploads.example.invalid/put\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a network command split across a backslash continuation fails' \
    'network commands without an allowlisted host'

printf '      - name: probe\n        run: git push origin HEAD\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a push from publish-central.yml fails' \
    'never pushes or rewires a remote'

printf '      - name: probe\n        run: git checkout --detach "${{ inputs.commit }}"\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'a workflow input interpolated into run text fails' \
    'interpolated outside an env assignment'

printf '      - name: probe\n        run: echo "${{ github.event.inputs.commit }}"\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'the legacy github.event.inputs spelling in run text fails' \
    'interpolated outside an env assignment'

printf '      - name: probe\n        run: echo "${{ format('"'"'{0}'"'"', inputs.commit) }}"\n' >> "$PUBLISH_WORKFLOW"
expect_fail 'an input reaching run text through format() indirection fails' \
    'interpolated outside an env assignment'

printf '      - name: probe\n        run: echo "${{ inputs.reason }}"\n' >> "$RC_WORKFLOW"
expect_fail 'an interpolated input in any other workflow fails too' \
    'interpolated outside an env assignment'

printf 'val probeLookupOnly = "credentials(PasswordCredentials::class)"\n' >> "$ROOT_BUILD"
if sh tools/validate/validate.sh > "$TMP/run.log" 2>&1
then
    pass 'the one admitted lookup form, credentials(PasswordCredentials::class), passes'
else
    fail 'the admitted lookup form credentials(PasswordCredentials::class) was refused'
fi
restore_files

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
