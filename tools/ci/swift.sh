#!/bin/sh
# SPFN Mobile — the Swift half of the CI referee, and the same command a person runs.
#
# The Linux module set: `SPFNCore`, `SPFNGenerated`, `SPFNAuth` and `SPFNClient`, whose
# cryptography comes from swift-crypto behind `.when(platforms: [.linux])` because Linux
# has no CryptoKit. The two provider-adapter modules declare no Linux half at all, so
# their rows are absent rather than skipped, and a small number of rows a Linux platform
# cannot host skip themselves. A Linux run therefore reports skips, and that is the
# expected shape rather than a warning.
#
# What is NOT here is the macOS job: Keychain, Secure Enclave and the AuthenticationServices
# adapter need Apple frameworks, and D2 leaves exactly that outside CI.
#
# Build and test are two commands rather than one so a compile failure is told apart from
# a test failure in the log, and `--skip-build` keeps the second from redoing the first.
#
#   sh tools/ci/swift.sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

swift --version
swift build --build-tests
exec swift test --skip-build
