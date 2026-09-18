#!/bin/sh
# SPFN Mobile — installs the pinned Swift toolchain on an ubuntu-24.04 runner.
#
# Not an action. D14 admits `actions/checkout` and nothing else, so the toolchain arrives
# as a tarball this script fetches, checks against `tools/ci/swift-toolchain.lock` and
# unpacks. No runner cache either: `actions/cache` is an action, and a cache is a second
# place a toolchain can come from, which is the thing a pinned digest exists to prevent.
#
# The apt list is swiftlang/swift-docker's own Ubuntu 24.04 list — the dependencies the
# official image installs before unpacking the same tarball. `libcurl4-openssl-dev` and
# `libxml2-dev` are what Foundation links against; the `-13` runtime packages are what
# clang emits calls into.
#
# Nothing here runs on this repository's development VM: the toolchain is already there,
# and a 1 GB download to prove a script can download is not evidence. The script is held
# to `sh -n` and to the digest in the lock file instead.
#
#   sh tools/ci/install-swift.sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

LOCK=tools/ci/swift-toolchain.lock

if [ ! -f "$LOCK" ]
then
    printf 'CI-SWIFT-INSTALL: %s is missing; there is no pinned toolchain to install.\n' "$LOCK" >&2
    exit 1
fi

URL=$(sed -n 's/^URL=//p' "$LOCK")
SHA256=$(sed -n 's/^SHA256=//p' "$LOCK")

if [ -z "$URL" ] || [ -z "$SHA256" ]
then
    printf 'CI-SWIFT-INSTALL: %s names no URL or no SHA256; refusing to guess one.\n' "$LOCK" >&2
    exit 1
fi

sudo apt-get -q update
sudo apt-get -q install -y --no-install-recommends \
    binutils \
    git \
    gnupg2 \
    libc6-dev \
    libcurl4-openssl-dev \
    libedit2 \
    libgcc-13-dev \
    libncurses-dev \
    libpython3-dev \
    libsqlite3-0 \
    libstdc++-13-dev \
    libxml2-dev \
    libz3-dev \
    pkg-config \
    tzdata \
    unzip \
    zlib1g-dev

TARBALL="${RUNNER_TEMP:-/tmp}/swift-toolchain.tar.gz"
curl --fail --silent --show-error --location --output "$TARBALL" "$URL"
printf '%s  %s\n' "$SHA256" "$TARBALL" > "$TARBALL.sha256"
sha256sum -c "$TARBALL.sha256"

# --strip-components=1 so the install path is the lock's business and the tarball's
# top-level directory name is not: a release that renames it installs to the same place.
mkdir -p "$HOME/swift"
tar -xzf "$TARBALL" -C "$HOME/swift" --strip-components=1
rm -f "$TARBALL" "$TARBALL.sha256"

"$HOME/swift/usr/bin/swift" --version

if [ -n "${GITHUB_PATH:-}" ]
then
    printf '%s/swift/usr/bin\n' "$HOME" >> "$GITHUB_PATH"
else
    printf 'CI-SWIFT-INSTALL: GITHUB_PATH is unset; add %s/swift/usr/bin to PATH yourself.\n' "$HOME"
fi
