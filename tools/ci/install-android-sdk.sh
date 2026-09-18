#!/bin/sh
# SPFN Mobile — puts a known Android SDK under ANDROID_HOME on an ubuntu-24.04 runner.
#
# D20, resolved 2026-09-18: the SDK components this build needs are named and installed by
# the workflow, never assumed to be in the runner image. A runner image that happens to
# carry android-36 today is not a pin, and the day it carries android-37 instead the build
# fails somewhere far from here.
#
# `sdkmanager` itself IS taken from the image when it is there — it is a downloader, not a
# component, and the components it fetches are named below either way. When it is absent
# the command-line tools arrive as a digest-pinned zip:
#
#   commandlinetools-linux-16111833_latest.zip, cmdline-tools;23.0, 181052239 bytes.
#   Google publishes SHA-1 e025545c62a8e64c7559119566a569fb1dec5f60 for it in
#   https://dl.google.com/android/repository/repository2-1.xml; that SHA-1 was checked
#   against a download taken 2026-09-18, and the SHA-256 below is that same file's.
#
# Not run on this repository's development VM — the SDK is already installed there — so
# this script is held to `sh -n` and to the digest above.
#
#   ANDROID_HOME=... sh tools/ci/install-android-sdk.sh

set -eu

CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8

# D5's compileSdk / build-tools, named here and nowhere else in CI.
PLATFORM='platforms;android-36'
BUILD_TOOLS='build-tools;36.0.0'

if [ -z "${ANDROID_HOME:-}" ]
then
    printf 'CI-ANDROID-SDK: ANDROID_HOME is empty; nothing names where the SDK goes.\n' >&2
    exit 1
fi

mkdir -p "$ANDROID_HOME"

if command -v sdkmanager > /dev/null 2>&1
then
    SDKMANAGER=sdkmanager
elif [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]
then
    SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
else
    ZIP="${RUNNER_TEMP:-/tmp}/android-cmdline-tools.zip"
    curl --fail --silent --show-error --location --output "$ZIP" "$CMDLINE_TOOLS_URL"
    printf '%s  %s\n' "$CMDLINE_TOOLS_SHA256" "$ZIP" > "$ZIP.sha256"
    sha256sum -c "$ZIP.sha256"

    # The zip unpacks to `cmdline-tools/`, and sdkmanager insists on being under
    # `cmdline-tools/latest/` — it derives the SDK root from its own path, so the wrong
    # depth makes it install components one directory too high.
    rm -rf "${RUNNER_TEMP:-/tmp}/cmdline-tools"
    unzip -q "$ZIP" -d "${RUNNER_TEMP:-/tmp}"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "${RUNNER_TEMP:-/tmp}/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f "$ZIP" "$ZIP.sha256"
    SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi

# Licence acceptance is a prompt per unaccepted licence, and an unaccepted licence makes
# the install below refuse silently rather than loudly.
yes | "$SDKMANAGER" --licenses > /dev/null

"$SDKMANAGER" --install "$PLATFORM" "$BUILD_TOOLS"

for component in "$ANDROID_HOME/platforms/android-36" "$ANDROID_HOME/build-tools/36.0.0"
do
    if [ ! -d "$component" ]
    then
        printf 'CI-ANDROID-SDK: sdkmanager reported success but %s does not exist.\n' "$component" >&2
        exit 1
    fi
done

printf 'CI-ANDROID-SDK: %s and %s are installed under %s\n' "$PLATFORM" "$BUILD_TOOLS" "$ANDROID_HOME"
