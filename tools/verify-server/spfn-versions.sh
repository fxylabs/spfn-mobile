#!/bin/sh
# SPFN Mobile — what version of an @spfn package is published, and where.
#
#   sh tools/verify-server/spfn-versions.sh [package ...]
#
# There are two registries carrying @spfn, and they do not agree. A plain
# `npm view @spfn/auth version` answers from whichever one this machine's npmrc points
# the scope at, prints no indication of which, and so returns a confident wrong answer
# to anyone who had the other one in mind. That has now happened three times in one
# session, each time costing a chain of conclusions built on the wrong number.
#
# So this script asks both and prints both. There is no mode that asks one: a single
# number with no registry beside it is the shape of the mistake, not a shorter way to
# avoid it. When the two disagree it says so and exits non-zero, because a divergence is
# a fact somebody has to decide about rather than a detail to read past.
#
# The scope override is the part that is easy to get wrong. `--registry` does NOT steer a
# scoped package: an `@spfn:registry=` line in npmrc wins over it, so a query that looks
# like it named a registry can be answered by the other one. Only `--@spfn:registry=`
# overrides the scope, and it is passed on every call below for exactly that reason.
#
# Requires: npm.

set -eu

PUBLIC_REGISTRY=https://registry.npmjs.org/
PRIVATE_REGISTRY=https://git.superfunction.xyz/api/packages/superfunction/npm/

PACKAGES=${*:-'@spfn/auth @spfn/core'}

DIVERGED=0

# Asks one registry for one package's latest version. The scope is overridden as well as
# the default registry, because the scope is what actually decides for an @spfn name.
latest_from()
{
    npm view "$1" version --@spfn:registry="$2" --registry "$2" 2>/dev/null | tail -1
}

printf 'SPFN package versions, from both registries\n\n'
printf '%-16s %-22s %-22s\n' 'package' 'npmjs.org' 'git.superfunction.xyz'
printf '%-16s %-22s %-22s\n' '----------------' '----------------------' '----------------------'

for package in $PACKAGES
do
    PUBLIC=$(latest_from "$package" "$PUBLIC_REGISTRY")
    PRIVATE=$(latest_from "$package" "$PRIVATE_REGISTRY")

    [ -n "$PUBLIC" ] || PUBLIC='(absent)'
    [ -n "$PRIVATE" ] || PRIVATE='(absent)'

    printf '%-16s %-22s %-22s' "$package" "$PUBLIC" "$PRIVATE"

    if [ "$PUBLIC" != "$PRIVATE" ]
    then
        DIVERGED=1
        printf '  <- differ'
    fi

    printf '\n'
done

printf '\n'

if [ "$DIVERGED" -eq 0 ]
then
    printf 'Both registries agree.\n'
    exit 0
fi

printf 'The registries disagree, so "the published version" has no single answer.\n'
printf 'Decide which one the consumer installs from before acting on either number:\n'
printf '  - this machine sends @spfn to git.superfunction.xyz (see the npmrc scope line)\n'
printf '  - a fresh machine with no such line reaches npmjs.org\n'
exit 1
