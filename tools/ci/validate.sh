#!/bin/sh
# SPFN Mobile — the contract gate's command, and the same command a person runs.
#
# The offline validator admits no known failure, so its exit code is the gate's. It used to
# be judged against an allowlist of rows that were red on purpose — the device-receipt gate,
# which needs two real phones and a person — and that gate is now a manual pre-release
# command (COMPATIBILITY.md, "Device sign-in evidence") rather than a validator row, so there
# is nothing left to admit and nothing to judge.
#
# It stays a script under tools/ci/ because every gate workflow runs one, and a workflow
# file cannot be run on a developer's machine.
#
#   sh tools/ci/validate.sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

exec sh tools/validate/validate.sh
