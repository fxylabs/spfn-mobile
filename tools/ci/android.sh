#!/bin/sh
# SPFN Mobile — the Android half of the CI referee, and the same command a person runs.
#
# Everything here runs on Linux with an Android SDK and nothing else: unit tests, lint,
# and the three generators' verify tasks. What is NOT here is what cannot run on a
# hosted Linux runner — an emulator, a real device, a signed release — which is the
# boundary D2 draws.
#
# Unit tests are unqualified, so they reach every Android module including the Compose
# example and the Maestro harness: both are applications rather than libraries, but their
# screen models are tested and a scaffold that stopped compiling is a real failure.
#
# Lint is NOT unqualified. `docs/architecture/README.md` states that the publication,
# lint and API checks which apply to `android/*` do not reach an application under
# `examples/` or `tools/`, so lint is asked of the SDK modules by name — and the names
# are read from the directories under `android/`, which is the same rule the root build
# script uses to pick `sdkModules`. A module added there is linted without editing this.
#
# The three build tools (`:contract-codegen`, `:ui-codegen`, `:reference-server`) are JVM
# modules, so `testDebugUnitTest` never reaches them; their unit suites are named
# explicitly. `:reference-server:test` excludes its integration cases by its own
# configuration — those bind a socket and are `spfnIntegrationTest`, which CI does not run
# (tools/reference-server/run-integration.sh needs a Mac for the swift-e cell).
#
#   ANDROID_HOME=... sh tools/ci/android.sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

# The Android Gradle plugin reads ANDROID_HOME and the deprecated ANDROID_SDK_ROOT, and
# refuses to build when both are set and disagree. A hosted runner pre-sets the second to
# its own image's SDK, so a workflow that sets only the first builds against neither.
# Refusing here names the two paths; the plugin's own message arrives a minute later,
# after the Gradle distribution has been downloaded.
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ] && [ "$ANDROID_SDK_ROOT" != "$ANDROID_HOME" ]
then
    printf 'CI-ANDROID: ANDROID_HOME (%s) and ANDROID_SDK_ROOT (%s) name different SDKs; set both to one path or unset the second.\n' "$ANDROID_HOME" "$ANDROID_SDK_ROOT" >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]
then
    printf 'CI-ANDROID: ANDROID_HOME is empty; the Gradle build has no SDK to compile against.\n' >&2
    exit 1
fi

LINT_TASKS=''
for module in android/*/
do
    LINT_TASKS="$LINT_TASKS :$(basename "$module"):lint"
done

if [ -z "$LINT_TASKS" ]
then
    printf 'CI-ANDROID: no module directory exists under android/; there is nothing to lint and that is not a pass.\n' >&2
    exit 1
fi

printf 'CI-ANDROID: lint tasks:%s\n' "$LINT_TASKS"

# shellcheck disable=SC2086 — LINT_TASKS is a deliberate word list of task paths.
exec ./gradlew --console=plain \
    testDebugUnitTest \
    $LINT_TASKS \
    :contract-codegen:test :ui-codegen:test :reference-server:test \
    :contract-codegen:spfnCodegenVerify \
    :ui-codegen:spfnUiVerify \
    :ui-codegen:spfnHarnessUiVerify
