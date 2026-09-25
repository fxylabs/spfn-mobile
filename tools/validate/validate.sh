#!/bin/sh
# SPFN Mobile — offline repository validator.
#
# Zero external dependencies beyond POSIX sh, grep, sed, awk, find and a SHA-256 utility
# (`shasum` or `sha256sum`). No network, no package manager, no toolchain.
#
#   sh tools/validate/validate.sh
#
# What it deliberately does NOT do: pretend to validate things it cannot reach, or repeat
# a check something stronger already makes. Swift compilation is `swift build` /
# `swift test`. Android compilation, the Kotlin suites and the UI lint checks are
# `tools/ci/android.sh`. Codegen determinism is `./gradlew :contract-codegen:spfnCodegenVerify`
# and `:ui-codegen:spfnUiVerify`. Podspec parsing is `pod ipc spec`. Those are separate
# commands with separate evidence; this script never fakes them.
#
# What is left here is what nothing else can check: pinned toolchain checksums, forbidden
# artifacts, the contract lock, generated-source provenance, the declared baselines, the
# secrets and commands build scripts and workflows may hold, the module graph against the
# manifests, and the few cross-platform and Swift-only rules no build, test or lint on
# this host can read. The section numbers are stable identifiers; the gaps are sections
# that moved to a stronger home or were dropped, and tools/validate/README.md says where
# each one went.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

FAILURES=0
CHECKS=0
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

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

note()
{
    printf '  --    %s\n' "$1"
}

section()
{
    printf '\n%s\n' "$1"
}

# SHA-256 of a file, or the empty string when the file is unreadable.
sha256_of()
{
    if [ ! -f "$1" ]
    then
        printf ''
        return 0
    fi
    if command -v shasum > /dev/null 2>&1
    then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

# First string value of a JSON key, at any nesting depth.
json_string()
{
    sed -n "s/.*\"$2\": *\"\([^\"]*\)\".*/\1/p" "$1" | head -1
}

# First boolean value of a JSON key. `sed -E` because BSD sed has no BRE alternation.
json_bool()
{
    sed -nE "s/.*\"$2\": *(true|false).*/\1/p" "$1" | head -1
}

# First integer value of a JSON key.
json_number()
{
    sed -n "s/.*\"$2\": *\([0-9][0-9]*\).*/\1/p" "$1" | head -1
}

# Asserts a file contains a fixed string.
contains()
{
    if [ -f "$1" ] && grep -qF -- "$2" "$1"
    then
        pass "$3"
    else
        fail "$3"
    fi
}

# Asserts a file does NOT contain an extended regex.
lacks()
{
    if [ ! -f "$1" ] || ! grep -qE -- "$2" "$1"
    then
        pass "$3"
    else
        fail "$3 (matched in $1)"
    fi
}

# Same, but ignores `//` and `#` comment lines. A prohibition has to be describable
# in the file that implements it, so comments are never evidence of a violation.
lacks_active()
{
    if [ -f "$1" ] && grep -vE '^[[:space:]]*(//|#)' "$1" | grep -qE -- "$2"
    then
        fail "$3 (matched in $1)"
    else
        pass "$3"
    fi
}

equals()
{
    if [ "$1" = "$2" ]
    then
        pass "$3"
    else
        fail "$3 (expected '$2', got '$1')"
    fi
}

# The Swift packages the module graph allows ONE module, read from that module's own
# line. Line-scoped because `externalDeps` holds one array per platform and a
# whole-file read would return the first module's list for every module (P5).
graph_swift_external()
{
    grep -F "\"swiftTarget\": \"$1\"" "$GRAPH" \
        | sed -n 's/.*"externalDeps": {"swift": \[\([^]]*\)\].*/\1/p' \
        | tr ',' '\n' | tr -d '" ' | grep -v '^$'
}

# A manifest target line with every external product the graph allows that module —
# each behind the Linux platform condition — erased, and with its indentation and
# trailing comma trimmed. What comes back is the declaration as it would read with no
# external product on it at all.
#
# Erasing rather than parsing is what makes this exact. A product literal carries
# commas of its own, so a comma-split read of the dependency list mis-splits it; a
# product that differs ANYWHERE — another package, another condition, no condition at
# all — is simply not erased and stays visible as leftover text.
strip_linux_products()
{
    STRIPPED=$(printf '%s' "$1" | sed -E 's/^[[:space:]]*//; s/,[[:space:]]*$//')
    for package in $(graph_swift_external "$2")
    do
        STRIPPED=$(printf '%s' "$STRIPPED" | sed -E \
            "s/(, )?\.product\(name: \"[A-Za-z0-9_]+\", package: \"$package\", condition: \.when\(platforms: \[\.linux\]\)\)//g")
    done
    printf '%s' "$STRIPPED"
}

printf 'SPFN Mobile — offline repository validation\n'
printf 'root: %s\n' "$ROOT"

VERSION=$(tr -d '[:space:]' < VERSION 2>/dev/null || printf 'MISSING')
PODSPEC=tools/cocoapods-compat/generated/SPFNMobileCompatFixture.podspec
GRAPH=tools/module-graph.json
LOCK=Contracts/upstream.lock.json
BUNDLE=Contracts/spfn-mobile-contract.json
PINS=gradle/wrapper/WRAPPER-PINS.json
SWIFT_GENERATED=Sources/SPFNGenerated/Generated
KOTLIN_GENERATED=android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated

# Paths that hold public API surface or contract data. Documentation is excluded on
# purpose: docs must be able to state what is prohibited without tripping the scan.
SURFACE_DIRS='Sources Tests android Contracts examples .github'

# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
section '1. required layout'
# ---------------------------------------------------------------------------
# Only what no build reads. A missing manifest, build script, source root or generated
# directory fails `swift build` (tools/ci/swift.sh) or any `./gradlew` run
# (tools/ci/android.sh) before it could fail here, and every file a later section reads —
# the wrapper and its pins, VERSION, the contract lock and bundle, the module graph, the
# version catalogue, the verification metadata — fails that section when it is missing.
# What is left is the documents a public repository owes its readers, which nothing
# executes, and the scripts the workflows run, which only the CI service reads.
for path in \
    README.md LICENSE SECURITY.md CONTRIBUTING.md COMPATIBILITY.md RELEASE.md CHANGELOG.md
do
    if [ -f "$path" ]
    then
        pass "file $path"
    else
        fail "missing file $path"
    fi
done

# A workflow names its scripts by path, and a renamed or deleted script fails on the runner
# and nowhere a person runs anything. Every `sh tools/…` a workflow names must exist.
WORKFLOW_SCRIPTS=$(grep -hoE 'sh tools/[A-Za-z0-9_./-]+\.sh' .github/workflows/*.yml 2>/dev/null \
    | sed 's/^sh //' | sort -u)
if [ -z "$WORKFLOW_SCRIPTS" ]
then
    fail 'no workflow under .github/workflows names a tools/ script; the reader did not run'
fi
for script in $WORKFLOW_SCRIPTS
do
    if [ -f "$script" ]
    then
        pass "workflow script $script"
    else
        fail "a workflow runs $script, which does not exist"
    fi
done

# ---------------------------------------------------------------------------
section '2. build toolchain is pinned to published checksums'
# ---------------------------------------------------------------------------
# D5 fixed the Gradle baseline, so the Step 1 rule "no wrapper may exist" is replaced by
# a stronger one: the committed wrapper jar must be byte-identical to the artifact
# gradle.org publishes for the pinned version, and the distribution must carry the
# checksum gradle.org publishes for it. A fabricated jar or checksum still fails.
PIN_GRADLE_VERSION=$(json_string "$PINS" gradleVersion)
PIN_DIST_URL=$(json_string "$PINS" distributionUrl)
PIN_DIST_SHA=$(json_string "$PINS" distributionSha256)
PIN_JAR_SHA=$(json_string "$PINS" wrapperJarSha256)

equals "$(sha256_of gradle/wrapper/gradle-wrapper.jar)" "$PIN_JAR_SHA" \
    'gradle-wrapper.jar matches the published wrapper checksum'

contains gradle/wrapper/gradle-wrapper.properties "distributionSha256Sum=$PIN_DIST_SHA" \
    'gradle-wrapper.properties pins the published distribution checksum'
contains gradle/wrapper/gradle-wrapper.properties "gradle-$PIN_GRADLE_VERSION-bin.zip" \
    "wrapper distribution is Gradle $PIN_GRADLE_VERSION"
contains "$PINS" 'https://services.gradle.org/distributions/' \
    'pinned distribution comes from services.gradle.org'
lacks gradle/wrapper/gradle-wrapper.properties 'distributionUrl=.*(SNAPSHOT|nightly|file:)' \
    'wrapper distribution is a released artifact, not a snapshot or a local file'

if printf '%s' "$PIN_DIST_URL" | grep -q "gradle-$PIN_GRADLE_VERSION-bin.zip"
then
    pass 'pinned distribution URL and version agree'
else
    fail "pinned distribution URL '$PIN_DIST_URL' does not name version $PIN_GRADLE_VERSION"
fi

# ---------------------------------------------------------------------------
section '3. forbidden artifacts (no fabricated binaries, no credentials)'
# ---------------------------------------------------------------------------
if [ -n "$(find . -maxdepth 1 -name '*.podspec' -print -quit)" ]
then
    fail 'a .podspec at the repository root would advertise CocoaPods distribution'
else
    pass 'no .podspec at repository root'
fi

# The wrapper jar is the ONLY binary allowed, and only because the check above proves
# it is the published artifact rather than something someone built.
BINARIES=$(find . -path ./.git -prune -o -path ./.build -prune -o -path ./.gradle -prune -o \
    -path './*/build' -prune -o \
    \( -name '*.jar' -o -name '*.a' -o -name '*.dylib' -o -name '*.so' -o -name '*.zip' \
       -o -name '*.xcframework' -o -name '*.framework' \) -print 2>/dev/null \
    | grep -v '^\./gradle/wrapper/gradle-wrapper\.jar$' || true)
if [ -z "$BINARIES" ]
then
    pass 'no binary artifacts beyond the checksum-verified wrapper jar'
else
    fail "unexpected binary artifacts present: $BINARIES"
fi

# Build outputs are pruned exactly as the binary scan above prunes them: a signed
# staging run legitimately writes .asc signature outputs under a module's build/
# directory, and those are generated, gitignored artifacts of the run — what this
# check forbids is credential-shaped files in the COMMITTED tree.
SECRETS=$(find . -path ./.git -prune -o -path ./.build -prune -o -path ./.gradle -prune -o \
    -path './*/build' -prune -o \
    \( -name '.netrc' -o -name '*.p12' -o -name '*.jks' -o -name '*.keystore' \
       -o -name '*.mobileprovision' -o -name '*.pem' -o -name '*.key' -o -name 'id_rsa*' \
       -o -name '*.gpg' -o -name '*.asc' -o -name 'secring*' -o -name 'pubring*' \
       -o -name '.env' -o -name '.env.*' \) -print 2>/dev/null || true)
if [ -z "$SECRETS" ]
then
    pass 'no credential, keystore or signing-identity files'
else
    fail "credential-shaped files present: $SECRETS"
fi

if grep -rIlE 'BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY' $SURFACE_DIRS docs tools 2>/dev/null | grep -qv '^tools/validate/'
then
    fail 'private key material found in tracked sources'
else
    pass 'no private key material in tracked sources'
fi

# ---------------------------------------------------------------------------
section '4. version consistency'
# ---------------------------------------------------------------------------
if printf '%s' "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'
then
    pass "VERSION is valid SemVer ($VERSION)"
else
    fail "VERSION is not valid SemVer ($VERSION)"
fi

contains Sources/SPFNCore/SPFNVersion.swift "\"$VERSION\"" 'SPFNVersion.current matches VERSION'
contains android/spfn-core/src/main/kotlin/xyz/superfunction/spfn/core/SpfnVersion.kt "\"$VERSION\"" 'SpfnVersion.CURRENT matches VERSION'
contains gradle.properties "spfn.version=$VERSION" 'gradle.properties spfn.version matches VERSION'
contains "$PODSPEC" "'$VERSION'" 'CocoaPods fixture version matches VERSION'
contains CHANGELOG.md "$VERSION" 'CHANGELOG references VERSION'

# ---------------------------------------------------------------------------
section '5. contract lock discipline'
# ---------------------------------------------------------------------------
# A resolved lock can lie in a way a placeholder cannot: it can claim provenance it does
# not have. The rules below let a locally authored dev bundle be pinned honestly, and
# refuse any claim of an upstream export that carries no upstream evidence.
#
# THE SOURCE OF A CONTRACT VALUE IS PROVENANCE, AND ONLY PROVENANCE. The contract's own
# facts — name, version, major, supportedRange, bundleSha256 — are read from
# Contracts/upstream-provenance.json, which the exporter wrote and this repository copies
# unmodified. Until lockVersion 3 the lock carried a second copy of them and this section
# compared the two; the comparison is gone, along with the two gaps found in it on
# 2026-08-04, because a value with one home has nothing to disagree with. What the lock
# still answers is what only the consumer knows: which commit was read, which npm versions
# came from it, and where the vendored copy sits in this tree.
STATUS=$(json_string "$LOCK" status)
BUNDLE_PATH=$(json_string "$LOCK" bundlePath)
LOCK_ORIGIN=$(json_string "$LOCK" origin)
LOCK_EXPORTED=$(json_bool "$LOCK" exportedByUpstreamCI)
LOCK_COMMIT=$(json_string "$LOCK" commit)
UPSTREAM_ORIGIN='spfn-primitives-ci-export'
UPSTREAM_EVIDENCE=Contracts/upstream-provenance.json
PINNED_DIGEST=$(json_string "$UPSTREAM_EVIDENCE" bundleSha256)

# A key this lock shed may not come back. Re-adding one restores the second source the
# shrink removed, and a second source is where the drift this section used to hunt comes
# from. Closed by construction: it names the keys, not the ways they could disagree.
REAPPEARED=''
for SHED in version major minor manifestSha256 supportedRange rangeRule authProfiles
do
    if grep -qE "\"$SHED\"[[:space:]]*:" "$LOCK"
    then
        REAPPEARED="$REAPPEARED $SHED"
    fi
done
if [ -z "$REAPPEARED" ]
then
    pass 'the lock restates no contract value that lives in the upstream evidence'
else
    fail "the lock restates values whose only source is $UPSTREAM_EVIDENCE:$REAPPEARED"
fi

case "$STATUS" in
    UNRESOLVED_PLACEHOLDER)
        pass 'lock status is UNRESOLVED_PLACEHOLDER'
        lacks "$LOCK" '[0-9a-f]{40,}' 'placeholder lock carries no fabricated digest or commit SHA'
        lacks "$BUNDLE" '[0-9a-f]{40,}' 'placeholder contract manifest carries no fabricated digest'
        FIXTURE_FILES=$(find Contracts/fixtures -type f ! -name 'MANIFEST.json' ! -name 'README.md' 2>/dev/null || true)
        if [ -z "$FIXTURE_FILES" ]
        then
            pass 'no fixture vectors exist while the contract is unresolved'
        else
            fail "fixture vectors exist without a resolved contract: $FIXTURE_FILES"
        fi
        ;;

    RESOLVED_DEV_BUNDLE)
        pass 'lock status is RESOLVED_DEV_BUNDLE'

        equals "$LOCK_ORIGIN" 'spfn-mobile-step2-dev-bundle' \
            'lock provenance names the locally authored dev bundle'
        equals "$LOCK_EXPORTED" 'false' \
            'lock does not claim the bundle was exported by upstream CI'
        contains "$BUNDLE" '"origin": "spfn-mobile-step2-dev-bundle"' \
            'the bundle itself states the same origin as the lock'
        contains "$BUNDLE" '"bundleKind": "DEV_BUNDLE"' \
            'the bundle is labelled a development bundle in its own text'

        if grep -q "$UPSTREAM_ORIGIN" "$LOCK"
        then
            fail "a dev-pinned lock names '$UPSTREAM_ORIGIN'; upstream provenance may not be claimed here"
        else
            pass 'a dev-pinned lock makes no upstream-export claim'
        fi

        if printf '%s' "$LOCK_COMMIT" | grep -qE '^[0-9a-f]{40}$'
        then
            fail 'a dev-pinned lock carries a 40-hex commit, which would read as an upstream pin'
        else
            pass 'a dev-pinned lock carries no upstream commit SHA'
        fi

        RESOLVED=yes
        ;;

    RESOLVED_UPSTREAM)
        pass 'lock status is RESOLVED_UPSTREAM'
        equals "$LOCK_ORIGIN" "$UPSTREAM_ORIGIN" 'an upstream lock names the upstream exporter'
        equals "$LOCK_EXPORTED" 'true' 'an upstream lock records that upstream CI exported it'
        contains "$BUNDLE" '"origin": "spfn-primitives-ci-export"' \
            'the bundle itself states the same origin as the lock'
        contains "$BUNDLE" '"bundleKind": "UPSTREAM_EXPORT"' \
            'the bundle is labelled an upstream export in its own text'

        if printf '%s' "$LOCK_COMMIT" | grep -qE '^[0-9a-f]{40}$'
        then
            pass 'an upstream lock carries an exact 40-hex source commit'
        else
            fail "an upstream lock must carry a 40-hex source commit, got '$LOCK_COMMIT'"
        fi

        # The evidence is what makes the claim checkable, and it is now the only place
        # the contract's own values live. What is left to check about it is that it is
        # there, that it agrees with itself, and that it names someone other than this
        # repository as the source — which is what a dev bundle dressed up as an export
        # would fail. The value-by-value comparison that used to sit here is gone with
        # the second copy it compared against.
        if [ -f "$UPSTREAM_EVIDENCE" ]
        then
            pass "upstream provenance evidence exists at $UPSTREAM_EVIDENCE"

            EV_ORIGIN=$(json_string "$UPSTREAM_EVIDENCE" origin)
            EV_EXPORTED=$(json_bool "$UPSTREAM_EVIDENCE" exportedByUpstreamCI)
            EV_REPOSITORY=$(json_string "$UPSTREAM_EVIDENCE" repository)

            equals "$EV_ORIGIN" "$UPSTREAM_ORIGIN" 'the evidence names the same exporter as the lock'
            equals "$EV_EXPORTED" 'true' 'the evidence itself records an upstream CI export'

            # The exporter cannot write the consumer's commit into a file it generates
            # before that commit exists, so the evidence says so in as many words and the
            # lock is where the SHA goes. A copy that filled the placeholder in was edited
            # on the way here, which is the one thing this file may not be.
            equals "$(json_string "$UPSTREAM_EVIDENCE" commit)" 'RECORDED_BY_CONSUMER' \
                'the evidence still carries the placeholder the exporter wrote, so it was copied unmodified'

            if printf '%s' "$EV_REPOSITORY" | grep -qi 'spfn-mobile'
            then
                fail "the evidence names '$EV_REPOSITORY' as the source; a bundle this repository wrote is not an upstream export"
            else
                pass 'the evidence names a source repository other than this one'
            fi
        else
            fail "an upstream-export claim requires $UPSTREAM_EVIDENCE; none exists, so the claim is unsupported"
        fi

        # Documents outlive the state they describe. Three review rounds each found a
        # surviving sentence saying the export does not exist, in wording the previous
        # round's grep did not cover, so the claims are listed here instead: each one is
        # true under RESOLVED_DEV_BUNDLE and false the moment the lock moves upstream, and
        # a reader has no way to tell which state a stale sentence was written for.
        # This is a list of exact claims, not a vocabulary ban — prose describing the
        # dev-bundle branch, or scoped to Step 2, stays legal because it stays true. What
        # it therefore does not catch: a paraphrase, a case variant, a claim in a code
        # comment, one in a file type outside the three globs below, or one reachable
        # only through a symlink, since `-type f` does not follow them. It closes the
        # wordings that were actually written here, and nothing wider.
        #
        # Enumerated and scanned in two steps, one file at a time. A single `find -exec
        # grep +` cannot tell "nothing matched" from "the scan could not run": both leave
        # an empty result and a non-zero status, and a check that passes when it could not
        # run is worse than no check. Here an enumeration that finds implausibly few
        # documents fails, an unreadable file fails, a path the reader cannot address
        # fails, and only a completed scan with no hit passes.
        STALE_DOCS=''
        STALE_UNREADABLE=0
        STALE_SCANNED=0

        # find writes one line per path, so a path holding a newline arrives as two paths
        # that each resolve somewhere else — the real file goes unscanned while the run
        # still reports clean. Counting the files independently of their names is what
        # notices: `-exec echo x \;` emits one line per file whatever the name contains,
        # so the two counts agree only when no path holds a newline. The format is `echo`
        # once per file rather than one `printf` over many, because a format string with
        # no conversion specifier consumes no argument and prints once for the whole set.
        STALE_FILES=$(find . -type f \( -name '*.md' -o -name '*.yml' -o -name '*.yaml' \) \
            -not -path './.git/*' -not -path '*/build/*' -not -path './.build/*' \
            -exec echo x \; 2>/dev/null | wc -l | tr -d ' ')

        if find . -type f \( -name '*.md' -o -name '*.yml' -o -name '*.yaml' \) \
            -not -path './.git/*' -not -path '*/build/*' -not -path './.build/*' \
            > "$TMP/provenance-docs" 2>/dev/null
        then
            while IFS= read -r DOC
            do
                STALE_SCANNED=$((STALE_SCANNED + 1))
                if grep -qF \
                    -e 'evidence that does not exist' \
                    -e 'no upstream evidence' \
                    -e 'export does not exist' \
                    -e 'no upstream contract exists' \
                    -e 'has not been exported' \
                    -e 'not exported by SPFN primitives' \
                    -- "$DOC"
                then
                    STALE_DOCS="$STALE_DOCS $DOC"
                elif [ $? -gt 1 ]
                then
                    STALE_UNREADABLE=$((STALE_UNREADABLE + 1))
                fi
            done < "$TMP/provenance-docs"
        fi

        # This repository has carried more than twenty such documents since Step 2. A
        # count near zero means the enumeration failed, not that the documents went away.
        if [ "$STALE_SCANNED" -lt 20 ]
        then
            fail "the stale-provenance scan reached only $STALE_SCANNED documents; it did not run"
        elif [ "$STALE_SCANNED" -ne "$STALE_FILES" ]
        then
            fail "the stale-provenance scan read $STALE_SCANNED lines for $STALE_FILES documents; a path contains a newline and cannot be addressed"
        elif [ "$STALE_UNREADABLE" -ne 0 ]
        then
            fail "the stale-provenance scan could not read $STALE_UNREADABLE of $STALE_SCANNED documents"
        elif [ -n "$STALE_DOCS" ]
        then
            fail "these documents still say the upstream export is missing:$STALE_DOCS"
        else
            pass "no document contradicts the resolved upstream provenance ($STALE_SCANNED scanned)"
        fi

        RESOLVED=yes
        ;;

    *)
        fail "lock status '$STATUS' is not one of UNRESOLVED_PLACEHOLDER, RESOLVED_DEV_BUNDLE, RESOLVED_UPSTREAM"
        ;;
esac

# Digest and fixture discipline is the same obligation whichever way the contract was
# resolved. It used to live inside the dev-bundle branch only, so moving the lock to
# RESOLVED_UPSTREAM would have silently dropped every fixture check.
#
# RESOLVED says which branch ran, not whether it passed, and that is deliberate: fail()
# records a failure and keeps going, so one bad provenance field must not suppress the
# digest and fixture checks and hide a second problem behind the first. A run reports
# everything wrong with the lock at once. The only state that skips this block is
# UNRESOLVED_PLACEHOLDER, where nothing is pinned and there is nothing to digest.
if [ "${RESOLVED:-no}" = "yes" ]
then
    if printf '%s' "$PINNED_DIGEST" | grep -qE '^[0-9a-f]{64}$'
    then
        pass 'the evidence bundleSha256 is 64 lowercase hex characters'
    else
        fail "the evidence bundleSha256 '$PINNED_DIGEST' is not a SHA-256 digest"
    fi

    equals "$(sha256_of "$BUNDLE_PATH")" "$PINNED_DIGEST" \
        "the digest the evidence records is the real SHA-256 of $BUNDLE_PATH"

    FIXTURE_FILES=$(find Contracts/fixtures -type f -name '*.json' ! -name 'MANIFEST.json' 2>/dev/null || true)
    if [ -n "$FIXTURE_FILES" ]
    then
        pass 'a resolved contract carries conformance vectors'
    else
        fail 'a resolved contract must carry conformance vectors'
    fi

    FIXTURE_COUNT=$(json_number Contracts/fixtures/MANIFEST.json fixtureCount)
    ACTUAL_FIXTURES=$(printf '%s\n' "$FIXTURE_FILES" | grep -c . || true)
    equals "$ACTUAL_FIXTURES" "$FIXTURE_COUNT" \
        'fixture MANIFEST.json count matches the files on disk'
    contains Contracts/fixtures/MANIFEST.json "\"bundleSha256\": \"$PINNED_DIGEST\"" \
        'fixture MANIFEST.json pins the same bundle digest as the evidence'

    # Every fixture digest recorded in the manifest must be the real one.
    DRIFTED=''
    for fixture in $FIXTURE_FILES
    do
        recorded=$(grep -A2 "\"path\": \"$fixture\"" Contracts/fixtures/MANIFEST.json \
            | sed -n 's/.*"sha256": "\([0-9a-f]*\)".*/\1/p' | head -1)
        actual=$(sha256_of "$fixture")
        if [ "$recorded" != "$actual" ]
        then
            DRIFTED="$DRIFTED $fixture"
        fi
    done
    if [ -z "$DRIFTED" ]
    then
        pass 'every fixture digest in MANIFEST.json matches the file on disk'
    else
        fail "fixture digests drifted for:$DRIFTED"
    fi
fi

# The auth-profile allowlist is an SDK policy and not a contract pin, so the lock stopped
# restating it at lockVersion 3. Its home is the enum on each platform, which section 6
# reads directly; what stays here is the bundle's own statement, because that is the
# contract's side of the same boundary.
contains "$BUNDLE" '"allowed": ["clientProofV1"]' 'bundle allowlists exactly clientProofV1'

# The contract range rule decides whether the SDK talks to a server at all, and it is
# implemented twice. The decision table is shared so a rule that drifts on one platform
# fails there; a table only one suite reads would let the other drift unobserved.
VECTORS=tools/conformance/semver-range-vectors.json
SWIFT_VECTOR_SUITE=Tests/SPFNCoreTests/SPFNCoreTests.swift
KOTLIN_VECTOR_SUITE=android/spfn-core/src/test/kotlin/xyz/superfunction/spfn/core/SpfnCoreTest.kt

contains "$SWIFT_VECTOR_SUITE" "$VECTORS" \
    'the Swift suite reads the shared vector file'
contains "$KOTLIN_VECTOR_SUITE" "$VECTORS" \
    'the Kotlin suite reads the shared vector file'

# Both tables have to be consumed, not just the file opened. The range table can pass
# because the rule refused for the right reason or because the parse failed for the wrong
# one; only the parser table tells the two apart, so a suite that quietly dropped its
# parsing loop would keep a green build and lose the distinction.
for ARRAY in cases parsing
do
    contains "$SWIFT_VECTOR_SUITE" "\"$ARRAY\"" \
        "the Swift suite consumes the shared $ARRAY table"
    contains "$KOTLIN_VECTOR_SUITE" "\"$ARRAY\"" \
        "the Kotlin suite consumes the shared $ARRAY table"
done

# A table that transcribes the implementation proves nothing, so each suite carries a
# probe that runs the rule at the base commit and requires the tables to catch it. This validator cannot run either suite — that is `swift test` and `./gradlew
# build` — so what it holds is that the probe is still there to be run.
contains "$SWIFT_VECTOR_SUITE" 'testTheSharedTablesRejectTheRuleTheyReplaced' \
    'the Swift suite still probes the tables against the rules they replaced'
contains "$KOTLIN_VECTOR_SUITE" 'theSharedTablesRejectTheRuleTheyReplaced' \
    'the Kotlin suite still probes the tables against the rules they replaced'

# Counted per entry rather than by grepping for a quoted word, because a `why` string is
# prose and can contain any word the count would otherwise be inflated by. Every entry
# must also carry the full field set the suites read.
awk '
/"cases": \[/   { array = "cases";   next }
/"parsing": \[/ { array = "parsing"; next }
/^  \]/         { array = "";        next }
array != "" && $0 ~ /^[[:space:]]*\{/ {
    if (array == "cases")
    {
        cases++
        if ($0 !~ /"lower"/ || $0 !~ /"upper"/ || $0 !~ /"candidate"/ ||
            $0 !~ /"supported"/ || $0 !~ /"why"/) { malformed++ }
    }
    else
    {
        parsing++
        if ($0 !~ /"text"/ || $0 !~ /"valid"/ || $0 !~ /"why"/) { malformed++ }
    }
}
END { printf "%d %d %d\n", cases + 0, parsing + 0, malformed + 0 }
' "$VECTORS" > "$TMP/vectors"
read -r VECTOR_CASES PARSER_CASES MALFORMED_ENTRIES < "$TMP/vectors"

if [ "$VECTOR_CASES" -ge 40 ]
then
    pass "the shared contract-range table carries $VECTOR_CASES cases"
else
    fail "the shared contract-range table carries only $VECTOR_CASES cases"
fi

if [ "$PARSER_CASES" -ge 20 ]
then
    pass "the shared parser table carries $PARSER_CASES cases"
else
    fail "the shared parser table carries only $PARSER_CASES cases"
fi

if [ "$MALFORMED_ENTRIES" -eq 0 ]
then
    pass 'every shared vector entry carries the fields both suites read'
else
    fail "$MALFORMED_ENTRIES shared vector entries are missing a field both suites read"
fi

# ---------------------------------------------------------------------------
section '6. the clientProofV1 boundary'
# ---------------------------------------------------------------------------
# v1 is clientProofV1 only, and this section checks that the code says so.
#
# The vocabulary ban that used to live here is gone. It arrived in the bootstrap commit
# with no recorded rationale, from a time when a hybrid WebView adapter was still planned,
# and it never caught anything: every tree it still governed held zero hits while its
# exception list grew to three — the adapter modules, the contract bundle, and the
# generated sources. It was edited five times to keep itself passing.
#
# It could not have caught much either. It matched spelling, not meaning: `id_token` was
# refused while `idToken` sat in SPFNClient untouched, naming the same value. And a
# browser flow needs either a provider library or a WebView, both of which are refused by
# checks that cannot be evaded by renaming an identifier:
#
#   - the module graph's dependency allowlist, checked in both directions below;
#   - the WebView and JavaScript-bridge vocabulary ban, which stays;
#   - the single-profile allowlist, checked right here.
#
# What remains is the boundary itself, stated as what the code does rather than as words
# it may not contain.

SWIFT_CASES=$(grep -c '^    case ' Sources/SPFNAuth/SPFNAuthProfile.swift 2>/dev/null || printf '0')
if [ "$SWIFT_CASES" = "1" ]
then
    pass 'SPFNAuthProfile declares exactly one profile'
else
    fail "SPFNAuthProfile declares $SWIFT_CASES profiles; v1 allows exactly one"
fi

contains Sources/SPFNAuth/SPFNAuthPolicy.swift 'allowedProfiles: [SPFNAuthProfile] = [.clientProofV1]' 'Swift allowlist is exactly clientProofV1'
contains android/spfn-auth/src/main/kotlin/xyz/superfunction/spfn/auth/SpfnAuthProfile.kt 'listOf(SpfnAuthProfile.CLIENT_PROOF_V1)' 'Kotlin allowlist is exactly clientProofV1'
# The hybrid module used to prove "no bridge exists" by declaring an empty allowlist.
# The module is gone, so the claim is now proven the stronger way: no WebView or bridge
# vocabulary appears anywhere in the surface at all. An empty allowlist can be widened
# by editing one literal; an absent module cannot be widened without adding a module.
BRIDGE_TERMS='WKWebView|WKScriptMessage|WKUserContentController|WebView|WebViewClient|addJavascriptInterface|JavascriptInterface|evaluateJavascript|evaluateJavaScript|postMessage'
# Build outputs are excluded: AGP's own default ProGuard files name
# `@android.webkit.JavascriptInterface`, so a scan that reads them fires on every
# module after any build and a check that cries wolf is one people stop reading.
BRIDGE_HITS=$(grep -rIniE --exclude-dir=build "(^|[^A-Za-z0-9_])($BRIDGE_TERMS)([^A-Za-z0-9_]|\$)" \
    $SURFACE_DIRS 2>/dev/null || true)
if [ -z "$BRIDGE_HITS" ]
then
    pass 'no WebView or JavaScript-bridge surface exists on either platform'
else
    fail 'WebView or JavaScript-bridge vocabulary found in the public surface:'
    printf '%s\n' "$BRIDGE_HITS" | sed 's/^/          /'
fi

# Every generated operation names one of the two contract auth classes: the proven
# clientProofV1 class, or the declared unproven class `none` for enrollment operations
# that run before any key exists to sign with. Any other value is a boundary violation.
GENERATED_PROFILES=$(grep -h 'authProfile' "$SWIFT_GENERATED"/SPFNGeneratedOperations.swift 2>/dev/null \
    | sed -n 's/.*authProfile: "\([^"]*\)".*/\1/p' | sort -u)
EXPECTED_PROFILES=$(printf 'clientProofV1\nnone')
if [ "$GENERATED_PROFILES" = "$EXPECTED_PROFILES" ]
then
    pass 'every generated operation is clientProofV1-proven or contract-declared unproven'
else
    fail "generated operations name auth classes outside {clientProofV1, none}: $GENERATED_PROFILES"
fi

# ---------------------------------------------------------------------------
section '7. publication disabled, dependency sources constrained'
# ---------------------------------------------------------------------------
# Two properties, each held by the fewest checks that hold it.
#
# PUBLICATION IS DISABLED when the committed flag says false, the root build refuses to
# configure unless it does (tools/validate/probe-publishing-gate.sh proves that refusal
# bites), no module script can publish on its own, and nothing pushes a pod to trunk.
contains gradle.properties 'spfn.publishing.enabled=false' 'Gradle publishing disabled'
contains build.gradle.kts 'require(committedPublishingEnabled == "false")' \
    'the root build reads the COMMITTED publishing flag and requires false'

# DEPENDENCY SOURCES ARE CONSTRAINED when the only repositories are the three the toolchain
# needs, the one other repository block is the root's gated staging target, every artifact
# resolved carries a recorded checksum, and every Swift package is one the module graph
# declares. tools/validate/probe-publication-rules.sh proves each refusal here bites.
GRADLE_FILES=$(find . -path ./.git -prune -o -path ./.gradle -prune -o -path './*/build' -prune -o \
    -name '*.gradle.kts' -print)
PUBLISHING_SCRIPTS=''
MAVEN_SCRIPTS=''
for file in $GRADLE_FILES
do
    if [ "$file" = "./build.gradle.kts" ]
    then
        continue
    fi
    grep -vE '^[[:space:]]*(//|#)' "$file" > "$TMP/gradle-active.txt" || true
    if grep -qE '(maven-publish|^[[:space:]]*publishing[[:space:]]*\{)' "$TMP/gradle-active.txt"
    then
        PUBLISHING_SCRIPTS="$PUBLISHING_SCRIPTS $file"
    fi
    if grep -qE 'maven[[:space:]]*\{' "$TMP/gradle-active.txt"
    then
        MAVEN_SCRIPTS="$MAVEN_SCRIPTS $file"
    fi
done

if [ -z "$PUBLISHING_SCRIPTS" ]
then
    pass 'no build script outside the gated root configures publication'
else
    fail "publication configured outside the gated root:$PUBLISHING_SCRIPTS"
fi

TRUNK=$(grep -rIl 'pod trunk push' . --exclude-dir=.git --exclude-dir=.build --exclude-dir=.gradle --exclude=validate.sh 2>/dev/null || true)
if [ -z "$TRUNK" ]
then
    pass 'no CocoaPods trunk publication command anywhere'
else
    fail "CocoaPods trunk publication command present in: $TRUNK"
fi

REPOS=$(grep -hoE '^[[:space:]]*(google|mavenCentral|gradlePluginPortal|mavenLocal|jcenter)\(\)' $GRADLE_FILES 2>/dev/null \
    | tr -d ' ' | sort -u)
UNEXPECTED_REPOS=$(printf '%s\n' "$REPOS" | grep -vE '^(google|mavenCentral|gradlePluginPortal)\(\)$' || true)
if [ -z "$UNEXPECTED_REPOS" ]
then
    pass 'only google(), mavenCentral() and gradlePluginPortal() are declared'
else
    fail "unexpected dependency repositories: $UNEXPECTED_REPOS"
fi

if [ -z "$MAVEN_SCRIPTS" ]
then
    pass 'no build script outside the root declares a maven repository'
else
    fail "an arbitrary maven repository is declared in:$MAVEN_SCRIPTS"
fi

ROOT_MAVEN_BLOCKS=$(grep -vE '^[[:space:]]*(//|#)' build.gradle.kts \
    | grep -cE 'maven[[:space:]]*\{' || true)
equals "$ROOT_MAVEN_BLOCKS" "1" \
    'the root declares exactly one maven repository block, the staging target'
contains build.gradle.kts 'url = stagingUri' \
    'the root maven repository is the gated staging directory'

contains gradle/verification-metadata.xml '<verify-metadata>true</verify-metadata>' \
    'Gradle dependency verification is enabled'

# The graph is read line by line: `externalDeps` holds one array per platform, and a
# whole-file read would return the first module's list for every module (P5).
grep '"swiftTarget"' "$GRAPH" > "$TMP/graph-lines.txt" || true
sed -n 's/.*"externalDeps": {"swift": \[\([^]]*\)\].*/\1/p' "$TMP/graph-lines.txt" \
    | tr ',' '\n' | tr -d '" ' | grep -v '^$' | sort -u > "$TMP/declared-swift.txt" || true
grep -E '^[[:space:]]*\.package\(' Package.swift \
    | sed -E 's#.*\.package\(url:[[:space:]]*"[^"]*/([^/"]+)".*#\1#' \
    | sort -u > "$TMP/manifest-package-names.txt" || true

UNDECLARED_SWIFT=$(comm -23 "$TMP/manifest-package-names.txt" "$TMP/declared-swift.txt" || true)
if [ -z "$UNDECLARED_SWIFT" ]
then
    pass 'every external package in Package.swift is declared in the module graph'
else
    fail "Package.swift depends on packages the module graph does not declare: $(printf '%s' "$UNDECLARED_SWIFT" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
section '7a. build scripts and workflows hold no secrets and run only reviewed commands'
# ---------------------------------------------------------------------------
# Section 7 keeps publication off; this section keeps the paths that would turn it on,
# or leak what it needs, closed. No build or test can make these checks: a build script
# holding a literal password builds, and a workflow with an extra trigger or an
# interpolated input only misbehaves on the CI service. Every refusal here is proven to
# bite by tools/validate/probe-publication-rules.sh.
#
# BUILD SCRIPTS HOLD NO SECRETS. No committed property may hold a credential or key —
# the active keys in gradle.properties are version, gate flags, group and Gradle tuning.
# Credentials are banned in BOTH syntactic forms, the `credentials { }` block and the
# call `credentials(...)`, unless the same line is a pure lookup, and a literal username
# or password value fails wherever it appears.
lacks_active gradle.properties '[Ss]igning|[Tt]oken|[Pp]assword|[Ss]ecret|[Cc]redential|[Kk]ey' \
    'gradle.properties commits no credential-shaped key'

CREDENTIAL_LOOKUPS='environmentVariable\(|gradleProperty\(|System\.getenv\(|PasswordCredentials::class'
for file in $GRADLE_FILES
do
    CREDENTIAL_HITS=$(grep -vE '^[[:space:]]*(//|#)' "$file" \
        | grep -E 'credentials[[:space:]]*[({]' \
        | grep -vE "$CREDENTIAL_LOOKUPS" || true)
    if [ -z "$CREDENTIAL_HITS" ]
    then
        pass "no committed credential configuration in $file"
    else
        fail "credential configuration that is not a pure lookup in $file: $CREDENTIAL_HITS"
    fi

    LITERAL_SECRETS=$(grep -vE '^[[:space:]]*(//|#)' "$file" \
        | grep -E '(username|password)[[:space:]]*=[[:space:]]*"' \
        | grep -vE "$CREDENTIAL_LOOKUPS" || true)
    if [ -z "$LITERAL_SECRETS" ]
    then
        pass "no literal username or password value in $file"
    else
        fail "literal credential value committed in $file: $LITERAL_SECRETS"
    fi

    if [ "$file" != "./build.gradle.kts" ]
    then
        lacks_active "$file" '(id\("signing"\)|apply\("signing"\)|apply\(plugin[[:space:]]*=[[:space:]]*"signing"\)|SigningExtension|useInMemoryPgpKeys|^[[:space:]]*signing[[:space:]]*(\{|$))' \
            "no signing configuration outside the gated root script in $file"
        lacks_active "$file" 'https?://' "no URL literal outside the root build script in $file"
    fi
done

# NO REMOTE PUBLICATION URL. Central is never a Gradle repository — the upload is a
# bundle POST made by the manual workflow — so every URL LITERAL in the root is held to
# the POM's own metadata addresses, whatever carries it (`url = …`, `url.set(…)`, a
# plain string). `setUrl` is banned outright because a variable passed through it could
# point a repository anywhere without a literal appearing.
UNEXPECTED_ROOT_URLS=$(grep -vE '^[[:space:]]*(//|#)' build.gradle.kts \
    | grep -oE 'https?://[^"[:space:]]*' | sort -u \
    | grep -vE '^https://(opensource\.org/license/mit/|github\.com/fxylabs/spfn-mobile(\.git)?|superfunction\.xyz)$' || true)
if [ -z "$UNEXPECTED_ROOT_URLS" ]
then
    pass 'every URL literal in the root build script is a pinned POM metadata address'
else
    fail "URL literals outside the POM metadata allowlist in build.gradle.kts: $(printf '%s' "$UNEXPECTED_ROOT_URLS" | tr '\n' ' ')"
fi
lacks_active build.gradle.kts 'setUrl' \
    'the root never uses setUrl; the staging repository is assigned once, visibly'

# SIGNING AND STAGING ARE PINNED in the root by their load-bearing lines. Signing exists
# only as an in-memory key looked up from the per-run environment, and every path that
# would put key material or key identity in the tree is refused. Publication exists only
# behind the per-run gate, towards an absolute staging directory outside the repository;
# section 7 holds the committed-flag line and the staging URL. Fixed strings on purpose:
# an edit that removes a refusal removes its string.
contains build.gradle.kts 'useInMemoryPgpKeys(signingKey' \
    'root signing admits only the in-memory key mechanism'
contains build.gradle.kts 'providers.gradleProperty("spfnSigningInMemoryKey")' \
    'the signing key arrives as a per-run property lookup (ORG_GRADLE_PROJECT_*)'
lacks_active build.gradle.kts '(secretKeyRingFile|signing\.keyId|\.gpg|\.asc|secring|pubring)' \
    'root signing names no key file, keyring or key identity'
contains build.gradle.kts 'if (publishingEnabled)' \
    'root publication configuration exists only behind the per-run enablement gate'
contains build.gradle.kts 'require(candidate.isAbsolute)' \
    'root gate requires an absolute staging path'
contains build.gradle.kts '!canonical.path.startsWith(repoRoot.path + File.separator)' \
    'root gate refuses a staging path inside the repository'

# WORKFLOWS RUN ONLY WHEN AND WHAT WAS REVIEWED. The three gates trigger on pull_request
# and push; every other workflow is manual. The trigger check is an ALLOW-list over the
# parsed `on:` set, whichever YAML style declares it — a deny-list misses the flow forms
# (`on: [push]`, `on: {push: …}`) and every trigger nobody thought to name. A line at
# trigger depth the parser cannot read is refused rather than skipped, because a parser
# that skips what it does not understand admits exactly the trigger it could not see.
workflow_triggers()
{
    awk '
        /^on:/ {
            inline = $0
            sub(/^on:[[:space:]]*/, "", inline)
            sub(/[[:space:]]*#.*$/, "", inline)
            if (inline != "")
            {
                gsub(/[][{}]/, "", inline)
                n = split(inline, parts, ",")
                for (i = 1; i <= n; i++)
                {
                    t = parts[i]
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", t)
                    sub(/:.*$/, "", t)
                    gsub(/["'\'']/, "", t)
                    if (t != "") { print t }
                }
                next
            }
            inblock = 1
            blockindent = -1
            next
        }
        inblock {
            if ($0 ~ /^[^[:space:]#]/) { inblock = 0 }
            else if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*#/) { }
            else
            {
                indent = match($0, /[^[:space:]]/) - 1
                if (blockindent < 0) { blockindent = indent }
                if (indent == blockindent)
                {
                    if ($0 ~ /^[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:/)
                    {
                        t = $0
                        sub(/^[[:space:]]*/, "", t)
                        sub(/[[:space:]]*:.*$/, "", t)
                        print t
                    }
                    else
                    {
                        print "SPFN_UNPARSEABLE_TRIGGER"
                    }
                }
            }
        }
    ' "$1"
}

# A gate runs scripts, never commands of its own: the runner and a developer's terminal
# must execute the same text, and section 1 holds that each script it names exists.
# Only a manual workflow may request a secret or speak publication, and of those only
# publish-central.yml, whose boundary is pinned after the loop.
PUBLISH_WORKFLOW=.github/workflows/publish-central.yml
GATE_WORKFLOWS=' .github/workflows/swift.yml .github/workflows/android.yml .github/workflows/contract.yml '
for workflow in .github/workflows/*.yml
do
    case "$GATE_WORKFLOWS" in
        *" $workflow "*)
            ADMITTED_TRIGGERS='^(pull_request|push)$'
            TRIGGER_DESCRIPTION='pull_request and push, and nothing else'
            ;;
        *)
            ADMITTED_TRIGGERS='^workflow_dispatch$'
            TRIGGER_DESCRIPTION='workflow_dispatch and nothing else'
            ;;
    esac

    TRIGGERS=$(workflow_triggers "$workflow")
    UNEXPECTED_TRIGGERS=$(printf '%s\n' "$TRIGGERS" | grep -vE "$ADMITTED_TRIGGERS" | grep -v '^$' || true)
    if printf '%s\n' "$TRIGGERS" | grep -q '^SPFN_UNPARSEABLE_TRIGGER$'
    then
        fail "$workflow has a trigger line the parser cannot read; an unparseable trigger is refused"
    elif [ -z "$TRIGGERS" ]
    then
        fail "$workflow declares no trigger at all; a workflow must say when it runs"
    elif [ -z "$UNEXPECTED_TRIGGERS" ]
    then
        pass "$workflow triggers on $TRIGGER_DESCRIPTION"
    else
        fail "$workflow declares triggers beyond $TRIGGER_DESCRIPTION: $(printf '%s' "$UNEXPECTED_TRIGGERS" | tr '\n' ' ')"
    fi

    case "$GATE_WORKFLOWS" in
        *" $workflow "*)
            UNEXPECTED_RUNS=$(grep -nE '^[[:space:]]*(-[[:space:]]*)?run:' "$workflow" \
                | grep -vE '^[0-9]+:[[:space:]]*(-[[:space:]]*)?run:[[:space:]]*sh tools/ci/[a-z-]+\.sh[[:space:]]*$' || true)
            if [ -z "$UNEXPECTED_RUNS" ]
            then
                pass "$workflow runs tools/ci scripts and nothing else, so its gate is reproducible off a runner"
            else
                fail "$workflow runs a command that is not a tools/ci script: $UNEXPECTED_RUNS"
            fi
            ;;
    esac

    if [ "$workflow" != "$PUBLISH_WORKFLOW" ]
    then
        lacks_active "$workflow" '(secrets\.|publish|deploy|upload-artifact|trunk|registry)' \
            "$workflow requests no secret and performs no publication"
    fi
done

# The publish workflow's own boundary. Secrets by NAME only, from a fixed allowlist — a
# new secret name is a new decision, not an edit. The only hosts it may address are the
# Central Portal and github.com (its own clone). The host allowlist sees only URL
# literals and a network command needs no scheme, so every network-capable command must
# itself name an allowlisted host; backslash continuations are joined first so a command
# split across lines is judged as one.
UNEXPECTED_SECRETS=$(grep -oE 'secrets\.[A-Za-z0-9_]+' "$PUBLISH_WORKFLOW" 2>/dev/null | sort -u \
    | grep -vE '^secrets\.(CENTRAL_PORTAL_TOKEN|SIGNING_IN_MEMORY_KEY|SIGNING_IN_MEMORY_KEY_PASSWORD|GITHUB_TOKEN)$' || true)
if [ -z "$UNEXPECTED_SECRETS" ]
then
    pass 'publish-central.yml references only the four allowlisted secret names'
else
    fail "publish-central.yml references unexpected secrets: $UNEXPECTED_SECRETS"
fi

UNEXPECTED_HOSTS=$(grep -oE 'https?://[^/"[:space:]]+' "$PUBLISH_WORKFLOW" 2>/dev/null \
    | sed -E 's#https?://##; s#.*@##; s#:.*##' | sort -u \
    | grep -vE '^(central\.sonatype\.com|github\.com)$' || true)
if [ -z "$UNEXPECTED_HOSTS" ]
then
    pass 'publish-central.yml addresses only central.sonatype.com and github.com'
else
    fail "publish-central.yml addresses unexpected hosts: $UNEXPECTED_HOSTS"
fi

UNPINNED_NETWORK=$(awk '
    /\\[[:space:]]*$/ { sub(/\\[[:space:]]*$/, "", $0); buf = buf $0 " "; next }
    { print buf $0; buf = "" }
' "$PUBLISH_WORKFLOW" \
    | grep -vE '^[[:space:]]*#' \
    | grep -E '(curl|wget|git clone|git fetch|git pull|ssh |scp |nc )' \
    | grep -vE '(central\.sonatype\.com|github\.com)' || true)
if [ -z "$UNPINNED_NETWORK" ]
then
    pass 'every network command in publish-central.yml names an allowlisted host'
else
    fail "network commands without an allowlisted host in publish-central.yml: $UNPINNED_NETWORK"
fi
lacks_active "$PUBLISH_WORKFLOW" 'git (push|remote)' \
    'a publish run never pushes or rewires a remote'

# Expression injection: a workflow input interpolated into run text executes as script.
# The net is any mention of inputs inside an expression — `inputs.x`, the legacy
# `github.event.inputs.x`, the bracket form, an indirection through format() — in every
# workflow, and the one admitted shape is a plain `NAME: ${{ inputs.x }}` env assignment.
RAW_INPUT_USES=$(grep -HnE '\$\{\{.*inputs' .github/workflows/*.yml \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*[A-Z_][A-Z_0-9]*:[[:space:]]*\$\{\{[[:space:]]*inputs\.[A-Za-z_]+[[:space:]]*\}\}[[:space:]]*$' || true)
if [ -z "$RAW_INPUT_USES" ]
then
    pass 'workflow inputs reach the shell only through env assignments'
else
    fail "workflow inputs interpolated outside an env assignment: $RAW_INPUT_USES"
fi

# The commit input names what gets built and published, so it is machine-validated as
# exactly 40 lowercase hex characters before any use.
contains "$PUBLISH_WORKFLOW" '*[!0-9a-f]*' \
    'the commit input is refused unless it is lowercase hex'
contains "$PUBLISH_WORKFLOW" '-ne 40' \
    'the commit input is refused unless it is exactly 40 characters'

# ---------------------------------------------------------------------------
section '8. module graph coherence'
# ---------------------------------------------------------------------------
# What a build cannot see: whether the modules and edges it builds are the ones
# tools/module-graph.json declares. A build compiles whatever the manifests say, so an
# undeclared module or an extra edge compiles fine. What a build DOES catch is not
# repeated here: a graph target with no source directory fails `swift build`, an
# unguarded Apple-only import (CryptoKit included) fails the Linux `swift build` in
# tools/ci/swift.sh, a Gradle mapping to a project nobody included fails configuration,
# and the CocoaPods fixture's subspecs are section 9's to hold, because it regenerates the
# fixture from this graph and refuses any difference.
grep '"swiftTarget"' "$GRAPH" > "$TMP/modules.txt" || true
MODULE_COUNT=$(wc -l < "$TMP/modules.txt" | tr -d ' ')
MODULES_WITH_ANDROID=0
MODULES_IOS_ONLY=0
MODULES_UNREADABLE=0

# A graph nobody could read yields no module lines, and every per-module check below then
# passes by never running (P7).
if [ "$MODULE_COUNT" -ge 4 ]
then
    pass "the module graph read $MODULE_COUNT modules"
else
    fail "the module graph read $MODULE_COUNT modules from $GRAPH; it could not run"
fi

while IFS= read -r line
do
    swift_target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
    android_module=$(printf '%s' "$line" | sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p')
    swift_deps=$(printf '%s' "$line" | sed -n 's/.*"swiftDependsOn": \[\([^]]*\)\].*/\1/p')
    android_deps=$(printf '%s' "$line" | sed -n 's/.*"androidDependsOn": \[\([^]]*\)\].*/\1/p')

    contains Package.swift ".library(name: \"$swift_target\", targets: [\"$swift_target\"])" \
        "Package.swift exposes product $swift_target"

    if [ -z "$swift_deps" ]
    then
        # A module with no graph edges may still carry an external product — SPFNCore
        # hashes with swift-crypto where there is no CryptoKit — so two shapes are
        # admitted: the bare target, and a dependency list holding nothing but products the
        # graph allows THIS module, each behind `.when(platforms: [.linux])`. An
        # unconditional product, or one allowed to another module, is not erased and fails.
        NO_EDGE_LINE=$(grep -F ".target(name: \"$swift_target\"" Package.swift | head -1)
        NO_EDGE_REST=$(strip_linux_products "$NO_EDGE_LINE" "$swift_target")
        if [ "$NO_EDGE_REST" = ".target(name: \"$swift_target\")" ] \
            || [ "$NO_EDGE_REST" = ".target(name: \"$swift_target\", dependencies: [])" ]
        then
            pass "Package.swift target $swift_target declares no dependency beyond the external products the graph allows it"
        else
            fail "Package.swift target $swift_target has no graph edges but declares: $NO_EDGE_REST"
        fi
    else
        # The graph's edges are the LEADING dependencies of the target, in order; a
        # trait-gated external product may follow them.
        contains Package.swift ".target(name: \"$swift_target\", dependencies: [$swift_deps" \
            "Package.swift target $swift_target dependency edge matches the graph"
    fi

    # Three states, kept apart: a name is checked, a null is counted as iOS-only, and a
    # line that is neither is a line this loop did not understand.
    if [ -n "$android_module" ]
    then
        MODULES_WITH_ANDROID=$((MODULES_WITH_ANDROID + 1))

        contains settings.gradle.kts "project(\":$android_module\").projectDir = file(\"android/$android_module\")" \
            ":$android_module maps to android/$android_module"

        if [ -z "$android_deps" ]
        then
            expected='extra["spfnModuleDependsOn"] = listOf<String>()'
        else
            expected="extra[\"spfnModuleDependsOn\"] = listOf($android_deps)"
        fi
        contains "android/$android_module/build.gradle.kts" "$expected" \
            "android/$android_module dependency edge matches the graph"
        contains "android/$android_module/build.gradle.kts" "extra[\"spfnSwiftCounterpart\"] = \"$swift_target\"" \
            "android/$android_module declares its Swift counterpart $swift_target"
    elif printf '%s' "$line" | grep -q '"androidModule": null'
    then
        MODULES_IOS_ONLY=$((MODULES_IOS_ONLY + 1))

        # A declared-absent Android half must really be absent. Every Android module names
        # its Swift counterpart in its own build script, so the question has an exact answer.
        ORPHAN=$(grep -l "extra\[\"spfnSwiftCounterpart\"\] = \"$swift_target\"" \
            android/*/build.gradle.kts 2>/dev/null || true)
        if [ -z "$ORPHAN" ]
        then
            pass "$swift_target is iOS-only, and no Android module claims to be its counterpart"
        else
            fail "$swift_target declares no Android half, but $ORPHAN claims to be its counterpart"
        fi
    else
        MODULES_UNREADABLE=$((MODULES_UNREADABLE + 1))
        fail "the graph line for $swift_target declares neither an androidModule nor null"
    fi
done < "$TMP/modules.txt"

if [ "$((MODULES_WITH_ANDROID + MODULES_IOS_ONLY))" = "$MODULE_COUNT" ] && [ "$MODULES_UNREADABLE" = "0" ]
then
    pass "the coherence loop read all $MODULE_COUNT modules ($MODULES_WITH_ANDROID Android-backed, $MODULES_IOS_ONLY iOS-only)"
else
    fail "the coherence loop read $MODULES_WITH_ANDROID + $MODULES_IOS_ONLY of $MODULE_COUNT modules and could not read $MODULES_UNREADABLE"
fi

# `linux` is ABSENT on a module that builds on Linux and the literal false on one that has
# no Linux half. SwiftPM cannot condition a TARGET on a platform, so what makes `false`
# true is a guard around every file of the module, which then compiles to nothing on
# Linux. A build cannot tell an empty module from one that merely compiles there, so the
# guard is read here: the first line of code is `#if canImport(...)` and the last is its
# `#endif`. A line carrying `"linux":` with anything but false lands in neither bucket.
grep '"linux": false' "$TMP/modules.txt" > "$TMP/linux-absent.txt" || true
grep -v '"linux":' "$TMP/modules.txt" > "$TMP/linux-capable.txt" || true
LINUX_ABSENT=$(wc -l < "$TMP/linux-absent.txt" | tr -d ' ')
LINUX_CAPABLE=$(wc -l < "$TMP/linux-capable.txt" | tr -d ' ')

if [ "$((LINUX_ABSENT + LINUX_CAPABLE))" = "$MODULE_COUNT" ]
then
    pass "every one of the $MODULE_COUNT graph modules builds on Linux or declares it absent ($LINUX_CAPABLE Linux-capable, $LINUX_ABSENT declared absent)"
else
    fail "$((MODULE_COUNT - LINUX_ABSENT - LINUX_CAPABLE)) module lines in $GRAPH carry a \"linux\" key that is not false; they were not read"
fi

GUARD_SCANNED=0
GUARD_PROBLEMS=''
while IFS= read -r line
do
    absent_target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
    for directory in "Sources/$absent_target" "Tests/${absent_target}Tests"
    do
        if [ ! -d "$directory" ]
        then
            GUARD_PROBLEMS="$GUARD_PROBLEMS $directory:missing"
            continue
        fi
        find "$directory" -name '*.swift' | sort > "$TMP/guard-files.txt"
        while IFS= read -r source
        do
            GUARD_SCANNED=$((GUARD_SCANNED + 1))
            grep -vE '^[[:space:]]*(//|$)' "$source" > "$TMP/guard-body.txt" || true
            case $(head -1 "$TMP/guard-body.txt") in
                '#if canImport('*) ;;
                *) GUARD_PROBLEMS="$GUARD_PROBLEMS $source:opens-with-unguarded-code" ;;
            esac
            if [ "$(tail -1 "$TMP/guard-body.txt")" != '#endif' ]
            then
                GUARD_PROBLEMS="$GUARD_PROBLEMS $source:guard-closes-before-the-end"
            fi
        done < "$TMP/guard-files.txt"
    done
done < "$TMP/linux-absent.txt"

if [ "$GUARD_SCANNED" -ge 3 ]
then
    pass "the whole-file guard scan read $GUARD_SCANNED sources across the $LINUX_ABSENT modules that declare no Linux half"
else
    fail "the whole-file guard scan read only $GUARD_SCANNED sources; it did not run"
fi

if [ -z "$GUARD_PROBLEMS" ]
then
    pass 'every source of a module that declares no Linux half is guarded whole, so the target compiles to an empty module'
else
    fail "a module declaring no Linux half has sources that are not guarded whole:$GUARD_PROBLEMS"
fi

# An undeclared module compiles as happily as a declared one, so the module directories and
# the Gradle project mappings are counted against the graph. The two platforms carry
# different modules and are counted against different numbers.
SOURCE_DIRS=$(find Sources -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
ANDROID_DIRS=$(find android -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
SETTINGS_PROJECTS=$(grep -cE '^project\(":[a-z-]+"\)\.projectDir = file\("android/' settings.gradle.kts || printf '0')
equals "$SOURCE_DIRS" "$MODULE_COUNT" 'the Sources directory count matches module-graph.json'
equals "$ANDROID_DIRS" "$MODULES_WITH_ANDROID" 'the android directory count matches module-graph.json'
equals "$SETTINGS_PROJECTS" "$MODULES_WITH_ANDROID" 'settings.gradle.kts maps one Android project per Android-backed module'

# A module exists here only once it carries an implementation: the persistence/sync and
# hybrid modules were declared, never implemented, and published as empty coordinates
# through 0.1.0-alpha.3. Stub vocabulary in SDK sources is how that starts.
STUB_TERMS='notImplemented|not implemented|plannedStep|planned step|TODO|FIXME'
STUB_HITS=$(grep -rIniE --exclude-dir=build "($STUB_TERMS)" \
    Sources android/*/src/main 2>/dev/null || true)
if [ -z "$STUB_HITS" ]
then
    pass 'no module ships a stub: SDK sources carry no unimplemented-entry-point vocabulary'
else
    fail 'stub vocabulary in SDK sources — a module is added with behaviour or not at all:'
    printf '%s\n' "$STUB_HITS" | sed 's/^/          /'
fi

# No module is built on an API its own vendor has already retired. A suppression is exactly
# what makes this invisible in a build log, so the build log is not where it can be caught.
DEPRECATION_HITS=$(grep -rIn --exclude-dir=build '@Suppress' Sources android/*/src 2>/dev/null \
    | grep -i 'DEPRECAT' || true)
if [ -z "$DEPRECATION_HITS" ]
then
    pass 'no SDK source silences a deprecation warning: new code is not written on a retired API'
else
    fail 'deprecation suppression in SDK sources — migrate instead of silencing:'
    printf '%s\n' "$DEPRECATION_HITS" | sed 's/^/          /'
fi

# ---------------------------------------------------------------------------
section '9. generated sources are traceable to the pinned bundle'
# ---------------------------------------------------------------------------
if sh tools/cocoapods-compat/generate-podspec.sh > "$TMP/regenerated.podspec" 2>"$TMP/gen.err"
then
    if diff -u "$PODSPEC" "$TMP/regenerated.podspec" > "$TMP/gen.diff" 2>&1
    then
        pass 'CocoaPods fixture is byte-identical to fresh generator output'
    else
        fail 'CocoaPods fixture drifted from the generator (hand-edited?)'
        sed 's/^/          /' "$TMP/gen.diff" | head -20
    fi
else
    fail 'podspec generator failed'
    sed 's/^/          /' "$TMP/gen.err"
fi

GENERATED_FILES=$(find "$SWIFT_GENERATED" -name '*.swift' 2>/dev/null; find "$KOTLIN_GENERATED" -name '*.kt' 2>/dev/null)
GENERATED_COUNT=$(printf '%s\n' "$GENERATED_FILES" | grep -c . || true)
if [ "$GENERATED_COUNT" -gt 0 ]
then
    pass "$GENERATED_COUNT generated client sources exist"
else
    fail 'no generated client sources exist'
fi

UNMARKED=''
WRONG_DIGEST=''
for generated in $GENERATED_FILES
do
    if ! grep -q 'GENERATED FILE — DO NOT EDIT' "$generated"
    then
        UNMARKED="$UNMARKED $generated"
        continue
    fi
    if ! grep -q "bundleSha256:    $PINNED_DIGEST" "$generated"
    then
        WRONG_DIGEST="$WRONG_DIGEST $generated"
    fi
done

if [ -z "$UNMARKED" ]
then
    pass 'every file in a generated directory declares itself generated'
else
    fail "hand-written files in a generated directory:$UNMARKED"
fi

if [ -z "$WRONG_DIGEST" ]
then
    pass 'every generated header carries the digest the upstream evidence records'
else
    fail "generated sources name a digest the upstream evidence does not record:$WRONG_DIGEST"
fi

# ---------------------------------------------------------------------------
section '10. toolchain baseline (D5) is declared, not implied'
# ---------------------------------------------------------------------------
contains Package.swift 'swift-tools-version: 6.1' 'Package.swift pins swift-tools-version 6.1'
contains Package.swift '.iOS(.v17)' 'Package.swift pins the iOS 17 baseline'
contains Package.swift '.macOS(.v14)' 'Package.swift pins the macOS 14 baseline'

contains gradle/libs.versions.toml 'agp = ' 'version catalogue pins the AGP line'
contains gradle/libs.versions.toml 'kotlin = ' 'version catalogue pins the Kotlin line'
contains gradle/libs.versions.toml 'jdk-toolchain = ' 'version catalogue pins the JDK toolchain'
contains gradle/libs.versions.toml 'min-sdk = ' 'version catalogue pins minSdk'
contains gradle/libs.versions.toml 'compile-sdk = ' 'version catalogue pins compileSdk'

# Read from the module graph rather than restated here. A hand-written list silently
# stops covering a module the moment someone adds one, which is the failure mode this
# whole section exists to prevent.
# The extraction takes quoted names only, so a module declaring `androidModule: null`
# drops out of this loop by itself — which is right, and which is also how the loop
# would look if the extraction had broken entirely. The visit count is compared against
# the Android-backed bucket for exactly that reason.
TOOLCHAIN_VISITED=0
for module in $(sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p' "$GRAPH")
do
    TOOLCHAIN_VISITED=$((TOOLCHAIN_VISITED + 1))
    script="android/$module/build.gradle.kts"
    contains "$script" 'jvmToolchain(libs.versions.jdk.toolchain.get().toInt())' \
        "$module compiles on the pinned JDK toolchain"
    contains "$script" 'jvmTarget = JvmTarget.JVM_11' \
        "$module pins the AAR bytecode target explicitly"
    contains "$script" 'sourceCompatibility = JavaVersion.VERSION_11' \
        "$module pins javac source compatibility explicitly"
done

if [ "$TOOLCHAIN_VISITED" = "$MODULES_WITH_ANDROID" ]
then
    pass "the toolchain baseline was checked on all $TOOLCHAIN_VISITED Android-backed modules"
else
    fail "the toolchain baseline reached $TOOLCHAIN_VISITED of $MODULES_WITH_ANDROID Android-backed modules"
fi

# ---------------------------------------------------------------------------
section '13. the ui vocabulary is one vocabulary on both platforms'
# ---------------------------------------------------------------------------
# Why this is still a validator rule rather than a test: a comparison between the Swift
# and the Kotlin declarations needs both trees at once, and no build, test or lint on either
# platform reads the other's sources — the same reason section 15 stays. The `dismiss`
# refusal at the end is Swift-only, and this repository has no Swift lint and this host no
# Swift toolchain; it is a fixed-string ban that does not depend on how the code is shaped.
#
# `Loadable`, `Busy`, `Flow`, `Paged` and `Form` are written twice, once per platform, and
# the only thing that keeps the two copies the same vocabulary is that somebody compares
# them. A screen built against `Loadable.empty` on one platform and a `Loadable` that has no
# empty on the other is not a portable app; the two would compile, both suites would pass,
# and the divergence would surface as a missing branch in somebody's product.
#
# A type is compared over whichever of three grammars it is MADE of, and over more than one
# where it is made of more than one: an enum's cases, a type's public methods, and — since
# `Paged` and `Form` arrived — a type's public properties. `Paged` holds `page`, `more` and
# `hasMore` and performs five transitions on them, and neither half of that is visible to
# the grammar that reads the other.
#
# So the names are read out of both trees and compared per type. Extraction is
# TYPE-SCOPED, not file-scoped: `Flow.swift` also declares `SPFNUIError`, whose
# `emptyStack` is not one of Flow's names, and a file-scoped read would hand it over
# (docs/IMPLEMENTATION-PITFALLS.md P5, the wrong-hit class). The current type is the last
# declaration that began at column zero, which is the shape every top-level declaration in
# both halves of this module has.
#
# Names are compared lowercased, because the two languages spell the same case
# differently by convention and neither spelling is the vocabulary: Swift's `case loading`
# and Kotlin's `data object Loading` are one name.
#
# Every extraction has a floor. A reader that read nothing produces an empty set, and two
# empty sets agree — which would report parity having read no code at all (P7). Both sides
# of every comparison must be non-empty, and the pass message carries the count.
UI_SWIFT_DIR=Sources/SPFNUI
UI_KOTLIN_DIR=android/spfn-ui/src/main/kotlin

# The names one Swift type declares, one per line, lowercased.
#
# `kind` selects what counts as a name: `case` for an enum's cases, `func` for a type's
# public methods. A case list may be written on one line (`case loading, ready(Value)`) or
# one case per line, and both are read — a payload is erased before the comma split, so a
# payload that carries a comma of its own cannot be mistaken for a second case.
#
# A method is read whatever MODIFIERS stand between `public` and `func` — `public static
# func`, `public mutating func`, `public nonisolated func`. A grammar that recognised one
# spelling would not report the others as extra names, it would not see them at all: both
# floors stay satisfied, both sides stay equal, and the section reports parity over a
# method only one platform has (P7). Visibility is still the anchor, so `internal func`
# and `private func` are read by neither half, which is what makes this a comparison of
# the two PUBLIC vocabularies.
swift_ui_names()
{
    find "$UI_SWIFT_DIR" -name '*.swift' | sort > "$TMP/ui-swift-files.txt"
    # An empty list is returned as an empty result rather than passed to awk, which would
    # read standard input instead and hang. The caller's floor is what turns that into a
    # failure; nothing here may quietly succeed at reading nothing.
    if [ ! -s "$TMP/ui-swift-files.txt" ]
    then
        return 0
    fi
    awk -v want="$1" -v kind="$2" '
        # The current type is per FILE. Without this it survives into the next one, and a
        # file whose first declaration this grammar does not recognise — an `internal enum`,
        # say — would hand its members to whatever type the PREVIOUS file ended on (P5, the
        # wrong-hit class).
        FNR == 1 { current = "" }
        /^(public )?(final )?(class|struct|enum|protocol|extension) / {
            declaration = $0
            sub(/^public /, "", declaration)
            sub(/^final /, "", declaration)
            sub(/^[a-z]+ /, "", declaration)
            sub(/[^A-Za-z0-9_].*$/, "", declaration)
            current = declaration
            next
        }
        current == want && kind == "case" && /^[[:space:]]+case / {
            names = $0
            sub(/^[[:space:]]+case /, "", names)
            gsub(/\([^)]*\)/, "", names)
            count = split(names, parts, ",")
            for (part = 1; part <= count; part++)
            {
                name = parts[part]
                # Trimmed BEFORE the tail is cut, not after: a comma-separated list leaves
                # a leading space on every part but the first, and cutting from the first
                # non-word character would erase those parts entirely — which reads as a
                # one-case enum rather than as an extraction that went wrong.
                sub(/^[ \t]+/, "", name)
                sub(/[^A-Za-z0-9_].*$/, "", name)
                if (name != "") { print tolower(name) }
            }
        }
        current == want && kind == "func" && /^[[:space:]]+public ([a-z]+ )*func / {
            name = $0
            sub(/^[[:space:]]+public ([a-z]+ )*func /, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
        # A `property` is what `Paged` and `Form` are MADE of — `page`, `more`, `hasMore`,
        # `fields`, `submit` — and neither a case list nor a method set reaches them. Stored
        # and computed alike, and `public static let`/`public static var` too, because the
        # initial value a model starts at is one of the names both platforms have to spell
        # the same way. Visibility is the anchor here as everywhere else in this reader: a
        # `private var` is an implementation detail and is read by neither half.
        current == want && kind == "property" && /^[[:space:]]+public (static )?(let|var) / {
            name = $0
            sub(/^[[:space:]]+public (static )?(let|var) /, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
    ' $(cat "$TMP/ui-swift-files.txt") | sort -u
}

# The same, for the Kotlin half. A sealed interface's states are its nested `object` and
# `data class` declarations, which is that language's spelling of an enum case with a
# payload; a class's names are its public functions — `public suspend fun`,
# `public inline fun` and every other modifier sequence included, for the reason the Swift
# half states. `internal` is not a visibility this reads: it is not public, and a method
# the other platform cannot call is not part of the shared vocabulary.
kotlin_ui_names()
{
    find "$UI_KOTLIN_DIR" -name '*.kt' | sort > "$TMP/ui-kotlin-files.txt"
    if [ ! -s "$TMP/ui-kotlin-files.txt" ]
    then
        return 0
    fi
    awk -v want="$1" -v kind="$2" '
        FNR == 1 { current = "" }
        /^[A-Za-z]/ {
            declaration = $0
            sub(/^public /, "", declaration)
            sub(/^sealed /, "", declaration)
            sub(/^data /, "", declaration)
            sub(/^enum /, "", declaration)
            if (declaration ~ /^(interface|class|object) /)
            {
                sub(/^[a-z]+ /, "", declaration)
                sub(/[^A-Za-z0-9_].*$/, "", declaration)
                current = declaration
            }
            next
        }
        current == want && kind == "case" && /^[[:space:]]+public (data )?(object|class) / {
            name = $0
            sub(/^[[:space:]]+public /, "", name)
            sub(/^data /, "", name)
            sub(/^[a-z]+ /, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
        # A Kotlin enum entry carries no modifier and no keyword — it is a capitalised name
        # on a line of its own, ending the list or followed by a comma or a semicolon. The
        # anchor is that the WHOLE line is that name: a member declaration, a call and a
        # brace all carry something else on the line and none of them is read here.
        current == want && kind == "entry" && /^[[:space:]]+[A-Z][A-Za-z0-9_]*[,;]?[[:space:]]*$/ {
            name = $0
            sub(/^[[:space:]]+/, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
        current == want && kind == "fun" && /^[[:space:]]+public ([a-z]+ )*fun / {
            name = $0
            sub(/^[[:space:]]+public ([a-z]+ )*fun /, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
        # The Kotlin spelling of the Swift `property` above. One grammar reaches all three
        # places a name can be declared, because all three are the same line: a constructor
        # parameter of a data class, a computed `val` in the body, and a `val` on the
        # companion object, which is how this language spells `public static let`.
        #
        # No apostrophe appears in this comment or the one above it, and that is not a style
        # choice: the awk program is a single-quoted shell word, so one apostrophe ends it
        # and the rest of the reader is parsed as shell.
        current == want && kind == "val" && /^[[:space:]]+public val / {
            name = $0
            sub(/^[[:space:]]+public val /, "", name)
            sub(/[^A-Za-z0-9_].*$/, "", name)
            if (name != "") { print tolower(name) }
        }
    ' $(cat "$TMP/ui-kotlin-files.txt") | sort -u
}

# The host vocabulary, by NAME rather than by members.
#
# `HostEntry` is a pair of fields, and `NavigationHost` is a `View` on one platform and a
# `@Composable fun` on the other, so neither has a case list or a public method set for
# `compare_ui_type` to read. What both have is a name a host app and a generated flow write
# down, and a name only one platform declares is a flow that can only be hosted on one of
# them — the same divergence the comparisons above are for, one level coarser.
#
# `\b` is deliberately not used: it is a GNU extension and this script runs on a Mac too,
# where it is not read as a word boundary at all (docs/IMPLEMENTATION-PITFALLS.md P28 is the
# same class of defect one tool along). The boundary is spelled out instead.
UI_HOST_NAMES='NavigationHost HostStack HostEntry WayOut'

# The same, for the two names the paged and form vocabulary adds that have no member set to
# compare. `FieldValidator` is one method on each platform, which is below `compare_ui_type`'s
# floor of two and would report "the extraction did not run" rather than a divergence;
# `PagedView` is a `View` on one platform and a `@Composable fun` on the other, exactly as
# `NavigationHost` is. Both are named by generated screens, so a name only one platform has is
# a screen only one platform can be written for.
UI_FORM_NAMES='FieldValidator PagedView'

# One list of names, both halves.
#
# The Kotlin grammar admits `fun` twice — once as a modifier and once as the keyword — because
# `public fun interface FieldValidator` spells it both ways in one line, and it admits a
# generic parameter list between the keyword and the name, because `public fun <V> PagedView(`
# is how this platform writes a generic composable. A grammar blind to either would not report
# the name as missing, it would not see it at all, which is the reader-that-read-nothing
# failure this whole section is built around (docs/IMPLEMENTATION-PITFALLS.md P7).
compare_ui_declared_names()
{
    LABEL=$1
    NAMES=$2
    ONLY_SWIFT=''
    ONLY_KOTLIN=''
    FOUND=0
    for NAME in $NAMES
    do
        IN_SWIFT=$(grep -rlE "^(public )?(final )?(struct|class|enum|protocol) $NAME([^A-Za-z0-9_]|\$)" \
            "$UI_SWIFT_DIR" 2>/dev/null | head -n 1)
        IN_KOTLIN=$(grep -rlE "^(public )?(sealed |data |enum |fun )*(class|interface|object|fun) (<[A-Za-z0-9_,: ?]*> )?$NAME([^A-Za-z0-9_]|\$)" \
            "$UI_KOTLIN_DIR" 2>/dev/null | head -n 1)
        if [ -n "$IN_SWIFT" ] && [ -n "$IN_KOTLIN" ]
        then
            FOUND=$((FOUND + 1))
        elif [ -n "$IN_SWIFT" ]
        then
            ONLY_SWIFT="$ONLY_SWIFT $NAME"
        elif [ -n "$IN_KOTLIN" ]
        then
            ONLY_KOTLIN="$ONLY_KOTLIN $NAME"
        else
            ONLY_SWIFT="$ONLY_SWIFT $NAME(neither)"
        fi
    done

    if [ -z "$(printf '%s%s' "$ONLY_SWIFT" "$ONLY_KOTLIN" | tr -d ' ')" ]
    then
        pass "$LABEL is declared on both platforms ($FOUND names:$(printf ' %s' $NAMES))"
    else
        fail "$LABEL differs between platforms — only in Swift:${ONLY_SWIFT:- none}| only in Kotlin:${ONLY_KOTLIN:- none}"
    fi
}

# One type, both halves. Reads each side, refuses an empty read on either, and names the
# extra and the missing separately — "they differ" is not enough to act on.
# The scratch files are named for the type AND the kind, because `Paged` and `Form` are each
# compared twice — once over their properties and once over their methods — and one name per
# type would have the second read overwrite the first's evidence.
compare_ui_type()
{
    TYPE=$1
    SCRATCH="$TYPE-$2"
    swift_ui_names "$TYPE" "$2" > "$TMP/ui-swift-$SCRATCH.txt"
    kotlin_ui_names "$TYPE" "$3" > "$TMP/ui-kotlin-$SCRATCH.txt"
    SWIFT_COUNT=$(grep -c . "$TMP/ui-swift-$SCRATCH.txt" || true)
    KOTLIN_COUNT=$(grep -c . "$TMP/ui-kotlin-$SCRATCH.txt" || true)

    if [ "$SWIFT_COUNT" -ge 2 ] && [ "$KOTLIN_COUNT" -ge 2 ]
    then
        pass "$TYPE: read $SWIFT_COUNT names from $UI_SWIFT_DIR and $KOTLIN_COUNT from $UI_KOTLIN_DIR"
    else
        fail "$TYPE: read $SWIFT_COUNT Swift names and $KOTLIN_COUNT Kotlin names; the extraction did not run"
        return 0
    fi

    ONLY_SWIFT=$(comm -23 "$TMP/ui-swift-$SCRATCH.txt" "$TMP/ui-kotlin-$SCRATCH.txt" | tr '\n' ' ')
    ONLY_KOTLIN=$(comm -13 "$TMP/ui-swift-$SCRATCH.txt" "$TMP/ui-kotlin-$SCRATCH.txt" | tr '\n' ' ')
    if [ -z "$(printf '%s%s' "$ONLY_SWIFT" "$ONLY_KOTLIN" | tr -d ' ')" ]
    then
        pass "$TYPE names match on both platforms ($(tr '\n' ' ' < "$TMP/ui-swift-$SCRATCH.txt"))"
    else
        fail "$TYPE differs between platforms — only in Swift: ${ONLY_SWIFT:-none}| only in Kotlin: ${ONLY_KOTLIN:-none}"
    fi
}

if [ -d "$UI_SWIFT_DIR" ] && [ -d "$UI_KOTLIN_DIR" ]
then
    pass 'both halves of the ui module are present'
    compare_ui_type Loadable case case
    compare_ui_type Busy case case
    compare_ui_type Flow func fun
    # How a flow is entered, how tall a sheet stands, and what a screen's leading control
    # is. All three arrived with the sheet entry, and all three are named by generated code
    # and by host apps — a name only one platform has is a screen only one platform can be
    # written for. `case` against `entry` is not an asymmetry in the vocabulary but in the
    # two languages: Swift spells a closed set of names as enum cases either way, Kotlin
    # spells one with a payload as nested declarations and one without as enum entries.
    compare_ui_type FlowEntry case case
    compare_ui_type SheetDetent case entry
    compare_ui_type WayOut case entry
    # The sheet's arithmetic is written twice and has to answer the same, which the two
    # suites check against the same hand-written vectors; this is what checks that they are
    # still the same three questions.
    compare_ui_type SheetGeometry func fun
    # The host's stack is the third piece of arithmetic written twice, and the one a pushed
    # flow's whole behaviour now rests on: an operation only one platform has is a
    # reconciliation only one platform performs.
    compare_ui_type HostStack func fun
    # The paged and form vocabulary (UI D1, D2). `Paged` and `Form` are each read twice —
    # what they HOLD and what they DO — because a screen model names both and the two
    # grammars cannot see each other's names: a `hasMore` that became `hasNext` on one
    # platform is invisible to the method comparison, and an `appended` that only one
    # platform grew is invisible to the property one.
    compare_ui_type Paged property val
    compare_ui_type Paged func fun
    compare_ui_type Form property val
    compare_ui_type Form func fun
    compare_ui_type FieldError case case
    compare_ui_type FieldRules property val
    # `FieldKind` is older than these two and was never compared, which was safe while its
    # only reader was a keyboard type. `FieldRules.kind` and `FieldError.kind` now carry it
    # into a generated model and into a test's expectations, so a case only one platform has
    # is a rule only one platform can state.
    compare_ui_type FieldKind case entry
    compare_ui_declared_names 'the host vocabulary' "$UI_HOST_NAMES"
    compare_ui_declared_names 'the paged and form vocabulary' "$UI_FORM_NAMES"
else
    fail "the ui module is incomplete: $UI_SWIFT_DIR or $UI_KOTLIN_DIR is missing"
fi

# `dismiss` is refused outright. SwiftUI's `@Environment(\.dismiss)` closes whatever
# presented the current view without telling the Flow, which leaves the host dismissed
# over a flow that still believes it is open — the double-source-of-truth this module is
# built to avoid, arriving through the one door that looks like ordinary SwiftUI. Closing
# a flow is `Flow.close()`, which the host's own binding calls.
UI_DISMISS_SCANNED=0
UI_DISMISS_HITS=''
find "$UI_SWIFT_DIR" -name '*.swift' | sort > "$TMP/ui-dismiss-files.txt" 2>/dev/null || true
while IFS= read -r source
do
    UI_DISMISS_SCANNED=$((UI_DISMISS_SCANNED + 1))
    # Comment lines are dropped first: a prohibition has to be statable in the file that
    # implements it, and FlowHost.swift says in its header why `dismiss` is refused.
    UI_DISMISS_HITS="$UI_DISMISS_HITS$(grep -n 'dismiss)' "$source" | grep -vE '^[0-9]+:[[:space:]]*(//|\*)' | sed "s#^#$source:#" | tr '\n' ' ')"
done < "$TMP/ui-dismiss-files.txt"

if [ "$UI_DISMISS_SCANNED" -ge 4 ]
then
    pass "the dismiss scan read all $UI_DISMISS_SCANNED sources of $UI_SWIFT_DIR"
else
    fail "the dismiss scan read only $UI_DISMISS_SCANNED sources; it did not run"
fi

if [ -z "$(printf '%s' "$UI_DISMISS_HITS" | tr -d ' ')" ]
then
    pass 'no source of SPFNUI reaches the SwiftUI dismiss environment value'
else
    fail "SPFNUI reaches the dismiss environment value: $UI_DISMISS_HITS"
fi

# ---------------------------------------------------------------------------
section '14. the apps that consume the scaffold hold the generated boundary'
# ---------------------------------------------------------------------------
# Three rules, and each of them is a rule a consuming app could break silently.
#
#   a. the generated services are the ONLY place a call descriptor is named. That is the
#      layering the whole scaffold exists to demonstrate — services, then screen models,
#      then views — and it is exactly the rule an app breaks by reaching for one
#      convenient descriptor in a view.
#   b. `dismiss` is refused under a generated directory for the reason section 13 refuses
#      it inside SPFNUI: it closes a presentation without telling the flow, which leaves a
#      host dismissed over a flow that still believes it is open.
#   c. every cell of the case table is covered by something. A table is a claim about what
#      was checked, and a cell with neither a flow nor a test is a claim nobody honoured —
#      and a `both` cell's flow may not carry a bare `- back`, because that command is
#      Android's and does nothing at all on iOS (P22). A `manual` cell is covered by a
#      person and by nothing here: what it checks is a gesture, and P22 is the record of a
#      runner reporting success for one it never performed.
#   d. the iOS example app can SEED every cell of that table. Every cell is launched by name
#      into an app that answers "which fake do I install for this", and a cell the app does
#      not know opens the menu instead of the flow — a run that reports nothing wrong and
#      checks nothing. The Compose half has a unit test of its own for this (FixtureTableTest);
#      the Swift half is an Xcode target with no test target, so the comparison is made here.
#
# TWO apps per platform are held to a and b, not one. tools/harness is the second consumer
# of the same screen spec — it drives the generated approval screens against a live
# reference server — so the boundary it could break silently is the same boundary, and a
# rule scoped to examples/ would have stopped applying the moment a second consumer
# appeared.
#
# Every one of them has a floor. A scan that read no file produces no hits, and no hits is
# what a clean tree also produces — so each check states how much it read and fails when
# that number says it did not run (docs/IMPLEMENTATION-PITFALLS.md P7).
EXAMPLE_CASES=examples/ui-spec/generated/device-approval.cases.json
EXAMPLE_FLOWS=examples/ui-spec/generated/flows
EXAMPLE_TESTS=examples/android-compose/src/test
EXAMPLE_IOS_FIXTURES=examples/ios-swiftui/Sources/Fixtures.swift

# Where a scaffold is consumed, both platforms and both apps.
SCAFFOLD_APPS='examples tools/harness'

# Every directory the ui generator owns Swift under. Named rather than globbed: the
# harness's own `Generated/` is XcodeGen's and holds a plist, and a pattern loose enough
# to catch `GeneratedUI` would catch that too.
SCAFFOLD_SWIFT='examples/ios-swiftui/Generated tools/harness/ios/GeneratedUI'

# The hand-written files allowed to name a call descriptor, by NAME.
#
# The harness reaches three operations the SDK wraps in nothing — `auth.keys.revoke`,
# `auth.keys.list` and the device-code login — through the generated descriptors and
# `execute`, exactly as any app would have to. That is the one legitimate reason to name a
# descriptor outside a generated service, and it is granted to two files rather than to a
# directory: a directory-wide exemption would silently cover every file added beside them,
# which is a rule that fails open (docs/IMPLEMENTATION-PITFALLS.md P7).
# One path per line, and the blank first line is deliberate: every entry then sits on a
# line of its own, which is what makes the list readable and what lets a probe take one
# entry away without touching the quoting around it.
DESCRIPTOR_EXEMPT_FILES='
tools/harness/ios/Sources/HarnessModel.swift
tools/harness/android/src/main/kotlin/xyz/superfunction/spfn/harness/HarnessModel.kt
'

# --- a. only a generated service may name a call descriptor ------------------
# The two spellings are one rule: SPFNGeneratedCalls on one platform, SpfnGeneratedCalls
# on the other. Comment lines are dropped first, because a file has to be able to say what
# it is forbidden from doing — the generated services' own headers say exactly that.
DESCRIPTOR_SCANNED=0
DESCRIPTOR_EXEMPT=0
DESCRIPTOR_NAMED=0
DESCRIPTOR_HITS=''
# shellcheck disable=SC2086
find $SCAFFOLD_APPS -type f \( -name '*.swift' -o -name '*.kt' \) | sort > "$TMP/example-sources.txt"
while IFS= read -r source
do
    DESCRIPTOR_SCANNED=$((DESCRIPTOR_SCANNED + 1))
    # The three spellings of "a generated services directory": two Swift roots, because
    # the harness's is `GeneratedUI/` — `Generated/` there is XcodeGen's — and one Kotlin.
    case "$source" in
        */Generated/Services/*|*/GeneratedUI/Services/*|*/generated/services/*)
            DESCRIPTOR_EXEMPT=$((DESCRIPTOR_EXEMPT + 1))
            continue
            ;;
    esac
    # The named exemptions, matched whole-line and literally so that a path which merely
    # ends with one of these names is not one of them.
    if printf '%s\n' "$DESCRIPTOR_EXEMPT_FILES" | grep -qxF "$source"
    then
        DESCRIPTOR_NAMED=$((DESCRIPTOR_NAMED + 1))
        continue
    fi
    DESCRIPTOR_HITS="$DESCRIPTOR_HITS$(grep -nE '(SPFN|Spfn)GeneratedCalls\.' "$source" \
        | grep -vE '^[0-9]+:[[:space:]]*(//|\*)' | sed "s#^#$source:#" | tr '\n' ' ')"
done < "$TMP/example-sources.txt"

if [ "$DESCRIPTOR_SCANNED" -ge 60 ] && [ "$DESCRIPTOR_EXEMPT" -ge 4 ]
then
    pass "the descriptor scan read $DESCRIPTOR_SCANNED app sources, $DESCRIPTOR_EXEMPT of them generated services"
else
    fail "the descriptor scan read $DESCRIPTOR_SCANNED app sources and found $DESCRIPTOR_EXEMPT generated services; it did not run"
fi

# The named exemptions have to BE there. Two files are exempt, and an exemption list whose
# entries name nothing is a rule that has quietly stopped being an exception to anything —
# the same floor every reader here carries, applied to the escape hatch.
if [ "$DESCRIPTOR_NAMED" -eq 2 ]
then
    pass "the descriptor scan found both hand-written files it exempts by name"
else
    fail "the descriptor scan matched $DESCRIPTOR_NAMED of the 2 files it exempts by name; the exemption list is stale"
fi

if [ -z "$(printf '%s' "$DESCRIPTOR_HITS" | tr -d ' ')" ]
then
    pass 'no app source outside a generated services directory names a call descriptor'
else
    fail "a call descriptor is named outside the generated services: $DESCRIPTOR_HITS"
fi

# --- b. dismiss is refused under a generated directory too -------------------
# Section 13 makes the same refusal for Sources/SPFNUI. This is its other half: the views
# the generator writes are where an app would most plausibly reach for `dismiss`, and they
# are also where nobody would notice, because nobody edits them.
EXAMPLE_DISMISS_SCANNED=0
EXAMPLE_DISMISS_HITS=''
# shellcheck disable=SC2086
find $SCAFFOLD_SWIFT -name '*.swift' | sort > "$TMP/example-dismiss-files.txt"
while IFS= read -r source
do
    EXAMPLE_DISMISS_SCANNED=$((EXAMPLE_DISMISS_SCANNED + 1))
    EXAMPLE_DISMISS_HITS="$EXAMPLE_DISMISS_HITS$(grep -n 'dismiss)' "$source" \
        | grep -vE '^[0-9]+:[[:space:]]*(//|\*)' | sed "s#^#$source:#" | tr '\n' ' ')"
done < "$TMP/example-dismiss-files.txt"

# The floor is above what EITHER app's generated Swift comes to on its own — the example is
# 41 files across nine flows and the harness is 9 across the one it is narrowed to — so a
# scan pointed at one root instead of two fails here rather than reporting the half it read
# as clean. It was 10 while the spec had one flow and both apps generated it; the showcase
# made the example alone clear that number four times over, and the probe case that takes
# the harness root away stopped biting until this moved with it.
if [ "$EXAMPLE_DISMISS_SCANNED" -ge 50 ]
then
    pass "the generated dismiss scan read all $EXAMPLE_DISMISS_SCANNED generated Swift sources in both apps"
else
    fail "the generated dismiss scan read only $EXAMPLE_DISMISS_SCANNED sources; it did not run"
fi

if [ -z "$(printf '%s' "$EXAMPLE_DISMISS_HITS" | tr -d ' ')" ]
then
    pass 'no generated source reaches the SwiftUI dismiss environment value'
else
    fail "a generated source reaches the dismiss environment value: $EXAMPLE_DISMISS_HITS"
fi

# --- c. every cell is covered by the runner it declares ----------------------
# `both` means both, not either: a cell that claims a device runner and a JVM one has to
# have each. The pairs are read one cell at a time from the table's own canonical format,
# which puts one field per line inside one object per cell.
if [ -f "$EXAMPLE_CASES" ]
then
    awk '
        /"id": "/ { id = $0; sub(/.*"id": "/, "", id); sub(/".*/, "", id) }
        /"runner": "/ {
            runner = $0
            sub(/.*"runner": "/, "", runner)
            sub(/".*/, "", runner)
            # A runner with no id before it is a line this reader did not understand, and
            # emitting it would turn an unreadable table into a table of nameless cells.
            # Dropping it is what lets the count floor below be the thing that fires.
            if (id != "") { print id " " runner }
            id = ""
        }
    ' "$EXAMPLE_CASES" > "$TMP/example-cells.txt"
else
    : > "$TMP/example-cells.txt"
fi
CELL_COUNT=$(grep -c . "$TMP/example-cells.txt" || true)
CELL_PROBLEMS=''
CELL_FLOWS_READ=0
CELL_TESTS_READ=0

# A backtick as a value rather than as a character in the pattern below. Inside the double
# quotes that pattern needs, one would open a command substitution; the pattern needs it
# literally, because a JUnit case here is named `u5 closes the flow …` between two of them.
TICK='`'

while IFS=' ' read -r cell runner
do
    [ -n "$cell" ] || continue
    case "$runner" in
        maestro|both)
            if [ -f "$EXAMPLE_FLOWS/$cell.yaml" ]
            then
                CELL_FLOWS_READ=$((CELL_FLOWS_READ + 1))
                # A TOP-LEVEL `- back` only. Maestro's `back` is Android's own command and
                # on iOS it completes without doing anything, so a flow that used it there
                # failed at the next assertion rather than at the step — cells u7b and u10b
                # did exactly that on an iPhone 17 Pro simulator on 2026-09-02 (P22). The
                # anchor is what makes this a rule about the flow rather than a ban on the
                # word: inside `runFlow: when: platform: Android` the command is indented,
                # which is the one place it means what it says.
                if grep -q '^- back' "$EXAMPLE_FLOWS/$cell.yaml"
                then
                    CELL_PROBLEMS="$CELL_PROBLEMS $cell:bare-back"
                fi
            else
                CELL_PROBLEMS="$CELL_PROBLEMS $cell:no-flow"
            fi
            ;;
    esac
    case "$runner" in
        unit|both)
            # A cell id NAMED under the test tree is not a test for that cell. `grep -rlw`
            # counted one: CellTest.kt's header lists every cell the suite covers, so every
            # id in that list satisfied the check whether a case existed or not — a comment
            # proving the thing it describes (P7). What counts is a line that DECLARES a
            # case for the cell, in the two spellings this repository has: a backticked
            # JUnit name beginning with the id, and an assertion naming the cell.
            if grep -rqE "fun $TICK$cell [^$TICK]*$TICK|assertCell\(\"$cell\"" \
                "$EXAMPLE_TESTS" 2>/dev/null
            then
                CELL_TESTS_READ=$((CELL_TESTS_READ + 1))
            else
                CELL_PROBLEMS="$CELL_PROBLEMS $cell:no-test"
            fi
            ;;
    esac
    case "$runner" in
        # `manual` covers itself and is listed rather than being read as a typo. Those cells
        # check a gesture, which is the class of thing a device runner reports success for
        # whether or not the platform read it as the gesture it meant (P22), so what proves
        # one is a person's answer under examples/ui-spec/receipts/manual/ — and a table that
        # refused to carry them would push them out of the table and out of anybody's sight.
        unit|maestro|both|manual) ;;
        *) CELL_PROBLEMS="$CELL_PROBLEMS $cell:unreadable-runner" ;;
    esac
done < "$TMP/example-cells.txt"

if [ "$CELL_COUNT" -ge 50 ]
then
    pass "the cell coverage check read $CELL_COUNT cells from $EXAMPLE_CASES"
else
    fail "the cell coverage check read $CELL_COUNT cells from $EXAMPLE_CASES, fewer than 50; it did not run"
fi

if [ "$CELL_FLOWS_READ" -ge 33 ]
then
    pass "the flow scan read all $CELL_FLOWS_READ device-cell flows looking for a bare system back"
else
    fail "the flow scan read $CELL_FLOWS_READ device-cell flows, fewer than 33; it did not run"
fi

# The floor under the test scan, stated as a number for the reason the cell count is: a
# shrinking tree may not lower its own bar. A scan that matched nothing and a scan that
# could not run are one failure here, which is the point of having a floor at all.
if [ "$CELL_TESTS_READ" -ge 21 ]
then
    pass "the test scan matched a case declaration for $CELL_TESTS_READ JVM cells under $EXAMPLE_TESTS"
else
    fail "the test scan matched case declarations for $CELL_TESTS_READ JVM cells, fewer than 21; it did not run"
fi

if [ -z "$CELL_PROBLEMS" ]
then
    pass 'every cell of the case table has the flow and the test its runner declares'
else
    fail "cells of the case table are not covered by what they claim:$CELL_PROBLEMS"
fi

# --- d. the iOS app can seed every cell the table declares --------------------
# `Fixtures.forCell` decides what a launch does, and a cell it does not recognise is not an
# error there — it is the MENU, because launching the app from the home screen names no cell
# at all. That is the right answer for a person and the wrong one for a runner: the flow
# waits for a screen it will never see, and the cell fails somewhere that says nothing about
# why. Cells u1c, u8d and u8e were missing from this file for a whole round.
#
# The file answers in two ways and both are read. A table cell is named outright by a `case`
# string; a showcase cell is named `<flow>-<what>` and answered by its flow appearing in
# `showcaseFlows`. Reading only the first would report every showcase cell unseeded, which
# is a check nobody could leave green.
if [ -f "$EXAMPLE_IOS_FIXTURES" ]
then
    # Per QUOTED STRING and not per line: one `case` line carries several ids.
    grep -E '^[[:space:]]*case "' "$EXAMPLE_IOS_FIXTURES" \
        | tr ',' '\n' | sed -n 's/.*"\([^"]*\)".*/\1/p' | sort -u > "$TMP/ios-fixture-ids.txt"
    # The showcase flow names: the file's one list of bare quoted strings.
    sed -n 's/^[[:space:]]*"\([A-Za-z][A-Za-z0-9]*\)",*$/\1/p' "$EXAMPLE_IOS_FIXTURES" \
        | sort -u > "$TMP/ios-fixture-flows.txt"
else
    : > "$TMP/ios-fixture-ids.txt"
    : > "$TMP/ios-fixture-flows.txt"
fi

IOS_FIXTURE_IDS=$(grep -c . "$TMP/ios-fixture-ids.txt" || true)
IOS_FIXTURE_FLOWS=$(grep -c . "$TMP/ios-fixture-flows.txt" || true)
IOS_FIXTURE_PROBLEMS=''

while IFS=' ' read -r cell runner
do
    [ -n "$cell" ] || continue
    case "$cell" in
        *-*)
            if ! grep -qxF "${cell%%-*}" "$TMP/ios-fixture-flows.txt"
            then
                IOS_FIXTURE_PROBLEMS="$IOS_FIXTURE_PROBLEMS $cell:no-ios-flow"
            fi
            ;;
        *)
            if ! grep -qxF "$cell" "$TMP/ios-fixture-ids.txt"
            then
                IOS_FIXTURE_PROBLEMS="$IOS_FIXTURE_PROBLEMS $cell:no-ios-fixture"
            fi
            ;;
    esac
done < "$TMP/example-cells.txt"

# The floor both readers carry (P7). A file that moved, or a `case` spelling that changed,
# reads as zero ids — and zero ids against zero cells would report perfect agreement.
if [ "$IOS_FIXTURE_IDS" -ge 30 ] && [ "$IOS_FIXTURE_FLOWS" -ge 8 ]
then
    pass "the iOS fixture reader found $IOS_FIXTURE_IDS seeded cell ids and $IOS_FIXTURE_FLOWS showcase flows"
else
    fail "the iOS fixture reader found $IOS_FIXTURE_IDS cell ids and $IOS_FIXTURE_FLOWS showcase flows in $EXAMPLE_IOS_FIXTURES; it did not run"
fi

if [ -z "$IOS_FIXTURE_PROBLEMS" ]
then
    pass 'the iOS example app seeds every cell the case table declares'
else
    fail "cells of the case table have no seeding in the iOS example app:$IOS_FIXTURE_PROBLEMS"
fi

# The Compose example app's `id:` selectors, and the one line order they depend on
# (docs/IMPLEMENTATION-PITFALLS.md P33).
#
# `testTagsAsResourceId` is what turns a Compose test tag into the Android resource id a
# Maestro `id:` selector matches, and it is resolved by walking semantics PARENTS. A pushed
# flow's routes are drawn by `NavigationHost`'s own navigator, which makes them SIBLINGS of
# the root it was handed rather than children of it — so the switch has to be set OUTSIDE
# that host to reach them.
#
# Set inside, nothing fails to build and nothing warns: the text on a pushed screen still
# matches and every control on it stops being findable. On 2026-09-04 that was five cells of
# thirty-five, all three push flows at once, and the log said `Element not found`.
#
# Line numbers, because what is being checked is which encloses which and this file has one
# of each.
EXAMPLE_ANDROID_ROOT='examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/MainActivity.kt'
TAGS_LINE=$(grep -n 'testTagsAsResourceId = true' "$ROOT/$EXAMPLE_ANDROID_ROOT" | head -1 | cut -d: -f1)
HOST_LINE=$(grep -n 'NavigationHost {' "$ROOT/$EXAMPLE_ANDROID_ROOT" | head -1 | cut -d: -f1)

if [ -n "$TAGS_LINE" ] && [ -n "$HOST_LINE" ]
then
    pass "the Compose example app sets testTagsAsResourceId (line $TAGS_LINE) and opens a NavigationHost (line $HOST_LINE)"
    if [ "$TAGS_LINE" -lt "$HOST_LINE" ]
    then
        pass 'testTagsAsResourceId is set outside the NavigationHost, where a pushed flow inherits it'
    else
        fail "testTagsAsResourceId is set inside the NavigationHost (line $TAGS_LINE against line $HOST_LINE); every control of every pushed flow loses its resource id"
    fi
else
    fail "$EXAMPLE_ANDROID_ROOT has no testTagsAsResourceId or no NavigationHost; this check did not run"
fi

# ---------------------------------------------------------------------------
section '15. the visual vocabulary is written twice and says the same thing'
# ---------------------------------------------------------------------------
# Section 13 does this for the STATE vocabulary — Loadable, Busy, Flow — and the argument is
# the same one: two hand-written halves stay one vocabulary only because somebody compares
# them. What is new is what they carry. A component set is three parallel lists, and a name
# that exists on one platform only breaks each of them differently:
#
#   a. TOKENS. `SpfnTokens.accent` with no `SPFNTokens.accent` beside it is a component that
#      compiles on Android and cannot be written for iOS. The palette's six colours are
#      fields of a struct and the rest are statics on an enum, so both spellings are read.
#   b. STRINGS. Every sentence a generated screen can show is one of these, and the
#      generated `ScreenFailure` names them by key on both platforms. A key on one side only
#      is a screen that says nothing where the other says something — and it is the failure
#      path, which is the one nobody runs.
#   c. COMPONENTS. A `DestructiveButton` on one platform and not the other is a spec `role`
#      the generator can emit for one app and not the other.
#   d. the touch minimum. It used to be re-emitted into every generated view and was read off
#      the emitted text by the generator's own suite; the components own it now, so this is
#      where it is checked. 44 on Apple, 48 on Android, and P21 is what both are for.
#   e. the THEME. What an app injects to give the components its own look is one key set
#      per type on both platforms.
#   f. no UI source reads the tokens past the theme: the tokens are the default theme's
#      source, and a component that reads them directly is one no theme reaches.
#
# Every extraction has a floor, for the reason section 13's do: a reader that read nothing
# produces an empty set, two empty sets agree, and the section would report parity having
# read no code at all (docs/IMPLEMENTATION-PITFALLS.md P7).
SWIFT_TOKENS=Sources/SPFNUI/Tokens/SPFNTokens.swift
KOTLIN_TOKENS=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnTokens.kt
SWIFT_STRINGS=Sources/SPFNUI/SPFNStrings.swift
KOTLIN_STRINGS=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/SpfnStrings.kt
SWIFT_COMPONENTS=Sources/SPFNUI/Components
KOTLIN_COMPONENTS=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components

# The names one Swift value table declares: `public static let x` on a type, and `public let
# x` on the palette struct whose fields ARE six of the keys. Lowercased, because the two
# languages spell the same key with the same letters and neither casing is the vocabulary.
#
# `sed -E`, for the reason the JSON readers at the top of this file give: BSD sed has no
# `\?` in a basic expression and matches the two characters literally, so this read returned
# NOTHING on a Mac while returning every name on Linux. It failed the way the floor is built
# to catch — "the extraction did not run" — rather than silently, which is the only reason
# it was one line to find (docs/IMPLEMENTATION-PITFALLS.md P28).
swift_value_names()
{
    [ -f "$1" ] || return 0
    sed -E -n 's/^[[:space:]]*public (static )?let ([A-Za-z0-9_]*).*$/\2/p' "$1" \
        | tr 'A-Z' 'a-z' | sort -u
}

# The same for Kotlin: `public val x` on an object, and the `public val x` of a data class's
# constructor, which is that language's spelling of the palette's six fields.
kotlin_value_names()
{
    [ -f "$1" ] || return 0
    sed -n 's/^[[:space:]]*public val \([A-Za-z0-9_]*\).*$/\1/p' "$1" \
        | tr 'A-Z' 'a-z' | sort -u
}

# One table, both halves. Reads each side, refuses an empty read on either, and names the
# extra and the missing separately — "they differ" is not enough to act on.
compare_value_table()
{
    LABEL=$1
    swift_value_names "$2" > "$TMP/values-swift-$LABEL.txt"
    kotlin_value_names "$3" > "$TMP/values-kotlin-$LABEL.txt"
    VALUE_SWIFT=$(grep -c . "$TMP/values-swift-$LABEL.txt" || true)
    VALUE_KOTLIN=$(grep -c . "$TMP/values-kotlin-$LABEL.txt" || true)

    if [ "$VALUE_SWIFT" -ge "$4" ] && [ "$VALUE_KOTLIN" -ge "$4" ]
    then
        pass "$LABEL: read $VALUE_SWIFT names from $2 and $VALUE_KOTLIN from $3"
    else
        fail "$LABEL: read $VALUE_SWIFT Swift names and $VALUE_KOTLIN Kotlin names, fewer than $4 a side; the extraction did not run"
        return 0
    fi

    ONLY_SWIFT=$(comm -23 "$TMP/values-swift-$LABEL.txt" "$TMP/values-kotlin-$LABEL.txt" | tr '\n' ' ')
    ONLY_KOTLIN=$(comm -13 "$TMP/values-swift-$LABEL.txt" "$TMP/values-kotlin-$LABEL.txt" | tr '\n' ' ')
    if [ -z "$(printf '%s%s' "$ONLY_SWIFT" "$ONLY_KOTLIN" | tr -d ' ')" ]
    then
        pass "$LABEL keys match on both platforms ($VALUE_SWIFT of them)"
    else
        fail "$LABEL differs between platforms — only in Swift: ${ONLY_SWIFT:-none}| only in Kotlin: ${ONLY_KOTLIN:-none}"
    fi
}

if [ -f "$SWIFT_TOKENS" ] && [ -f "$KOTLIN_TOKENS" ]
then
    compare_value_table tokens "$SWIFT_TOKENS" "$KOTLIN_TOKENS" 18
else
    fail "the token twins are incomplete: $SWIFT_TOKENS or $KOTLIN_TOKENS is missing"
fi

if [ -f "$SWIFT_STRINGS" ] && [ -f "$KOTLIN_STRINGS" ]
then
    compare_value_table strings "$SWIFT_STRINGS" "$KOTLIN_STRINGS" 8
else
    fail "the string twins are incomplete: $SWIFT_STRINGS or $KOTLIN_STRINGS is missing"
fi

# --- c. the components are the same set on both platforms --------------------
# A Swift component is a `public struct X: View`; a Kotlin one is a `public fun X(` whose
# name is capitalised, which is that platform's spelling of the same thing. Anything not
# public is not part of the set: `RoleButton` is what all four buttons are and is neither
# platform's API.
if [ -d "$SWIFT_COMPONENTS" ] && [ -d "$KOTLIN_COMPONENTS" ]
then
    grep -rhoE '^public struct [A-Za-z0-9_]+' "$SWIFT_COMPONENTS" \
        | sed 's/^public struct //' | tr 'A-Z' 'a-z' | sort -u > "$TMP/components-swift.txt"
    grep -rhoE '^public fun <?[A-Za-z0-9_ :]*>? ?[A-Z][A-Za-z0-9_]*\(' "$KOTLIN_COMPONENTS" \
        | sed -e 's/^public fun //' -e 's/^<[^>]*> *//' -e 's/($//' -e 's/(//' \
        | tr 'A-Z' 'a-z' | sort -u > "$TMP/components-kotlin.txt"
    COMPONENTS_SWIFT=$(grep -c . "$TMP/components-swift.txt" || true)
    COMPONENTS_KOTLIN=$(grep -c . "$TMP/components-kotlin.txt" || true)

    if [ "$COMPONENTS_SWIFT" -ge 8 ] && [ "$COMPONENTS_KOTLIN" -ge 8 ]
    then
        pass "the component scan read $COMPONENTS_SWIFT Swift components and $COMPONENTS_KOTLIN Kotlin ones"
    else
        fail "the component scan read $COMPONENTS_SWIFT Swift and $COMPONENTS_KOTLIN Kotlin components, fewer than 8 a side; it did not run"
    fi

    ONLY_SWIFT=$(comm -23 "$TMP/components-swift.txt" "$TMP/components-kotlin.txt" | tr '\n' ' ')
    ONLY_KOTLIN=$(comm -13 "$TMP/components-swift.txt" "$TMP/components-kotlin.txt" | tr '\n' ' ')
    if [ -z "$(printf '%s%s' "$ONLY_SWIFT" "$ONLY_KOTLIN" | tr -d ' ')" ]
    then
        pass "the component set matches on both platforms ($(tr '\n' ' ' < "$TMP/components-swift.txt"))"
    else
        fail "the component set differs — only in Swift: ${ONLY_SWIFT:-none}| only in Kotlin: ${ONLY_KOTLIN:-none}"
    fi
else
    fail "the component directories are incomplete: $SWIFT_COMPONENTS or $KOTLIN_COMPONENTS is missing"
fi

# --- d. every component that can be touched is held to the platform minimum --
# The number itself is checked, not merely referenced: `Metrics` is one file per platform and
# a value edited down there would silently un-size every control at once. 44 is Apple's and
# 48 is Android's, and P21 is the run that paid for both — Compose reported one control's
# bounds inside a neighbour's and cell u5 tapped the wrong node.
if grep -q 'touchTarget: CGFloat = 44' "$SWIFT_COMPONENTS/Metrics.swift" 2>/dev/null
then
    pass "the Swift components hold the 44pt minimum touch target"
else
    fail "$SWIFT_COMPONENTS/Metrics.swift does not declare Apple's 44pt minimum touch target"
fi

if grep -q 'TOUCH_TARGET: Dp = 48.dp' "$KOTLIN_COMPONENTS/Metrics.kt" 2>/dev/null
then
    pass "the Kotlin components hold the 48dp minimum touch target"
else
    fail "$KOTLIN_COMPONENTS/Metrics.kt does not declare Android's 48dp minimum touch target"
fi

TOUCH_SWIFT=$(grep -rl 'Metrics.touchTarget' "$SWIFT_COMPONENTS" 2>/dev/null | grep -c . || true)
TOUCH_KOTLIN=$(grep -rl 'Metrics.TOUCH_TARGET' "$KOTLIN_COMPONENTS" 2>/dev/null | grep -c . || true)
if [ "$TOUCH_SWIFT" -ge 3 ] && [ "$TOUCH_KOTLIN" -ge 3 ]
then
    pass "the minimum is applied in $TOUCH_SWIFT Swift component files and $TOUCH_KOTLIN Kotlin ones"
else
    fail "the minimum is applied in $TOUCH_SWIFT Swift and $TOUCH_KOTLIN Kotlin component files, fewer than 3 a side; a control has stopped being sized"
fi

# --- e. the theme is one key set, type by type --------------------------------
# `SPFNTheme`/`SpfnTheme` is what an app injects to give the components its own look, and a
# key one side has and the other does not is an app that can be themed on one platform
# only. Read as `type.key` rather than as a flat list: `light` is a key of the theme AND of
# a button appearance, and a flat set would call the two platforms agreed with the key moved
# from one type to the other. The type names differ only by the prefix's casing and are
# lowercased with the keys.
#
# `awk`, and only its POSIX half, for the reason the readers above use `sed -E`: this runs
# under BSD tools on a Mac (docs/IMPLEMENTATION-PITFALLS.md P28). A Swift key is a `public
# let` or `public static let` inside a `public struct`; a Kotlin one is a `public val`
# inside a `public data class` or `object`, which is where a constructor property and a
# companion's `Default` both stand. Backticks are dropped because `default` is a keyword in
# Swift and not in Kotlin.
SWIFT_THEME=Sources/SPFNUI/Tokens/SPFNTheme.swift
KOTLIN_THEME=android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/tokens/SpfnTheme.kt
THEME_KEY_FLOOR=30

swift_theme_keys()
{
    [ -f "$1" ] || return 0
    awk '
        /^public struct [A-Za-z0-9_]+/ { owner = $3; sub(/[^A-Za-z0-9_].*$/, "", owner); next }
        /^[^ \t{]/ { owner = "" }
        owner != "" && /^[ \t]+public (static )?let / {
            key = $0
            sub(/^[ \t]+public (static )?let /, "", key)
            gsub(/`/, "", key)
            sub(/[^A-Za-z0-9_].*$/, "", key)
            print tolower(owner) "." tolower(key)
        }' "$1" | sort -u
}

kotlin_theme_keys()
{
    [ -f "$1" ] || return 0
    awk '
        /^public (data class|object) [A-Za-z0-9_]+/ { owner = $0; sub(/^public (data class|object) /, "", owner); sub(/[^A-Za-z0-9_].*$/, "", owner); next }
        /^[^ \t)]/ && !/^[{}]/ { owner = "" }
        owner != "" && /^[ \t]+public val / {
            key = $0
            sub(/^[ \t]+public val /, "", key)
            sub(/[^A-Za-z0-9_].*$/, "", key)
            print tolower(owner) "." tolower(key)
        }' "$1" | sort -u
}

swift_theme_keys "$SWIFT_THEME" > "$TMP/theme-swift.txt"
kotlin_theme_keys "$KOTLIN_THEME" > "$TMP/theme-kotlin.txt"
THEME_SWIFT=$(grep -c . "$TMP/theme-swift.txt" || true)
THEME_KOTLIN=$(grep -c . "$TMP/theme-kotlin.txt" || true)

if [ "$THEME_SWIFT" -ge "$THEME_KEY_FLOOR" ] && [ "$THEME_KOTLIN" -ge "$THEME_KEY_FLOOR" ]
then
    pass "theme: read $THEME_SWIFT keys from $SWIFT_THEME and $THEME_KOTLIN from $KOTLIN_THEME"
    ONLY_SWIFT=$(comm -23 "$TMP/theme-swift.txt" "$TMP/theme-kotlin.txt" | tr '\n' ' ')
    ONLY_KOTLIN=$(comm -13 "$TMP/theme-swift.txt" "$TMP/theme-kotlin.txt" | tr '\n' ' ')
    if [ -z "$(printf '%s%s' "$ONLY_SWIFT" "$ONLY_KOTLIN" | tr -d ' ')" ]
    then
        pass "theme keys match on both platforms ($THEME_SWIFT of them)"
    else
        fail "theme keys differ between platforms — only in Swift: ${ONLY_SWIFT:-none}| only in Kotlin: ${ONLY_KOTLIN:-none}"
    fi
else
    fail "theme: read $THEME_SWIFT Swift keys and $THEME_KOTLIN Kotlin keys, fewer than $THEME_KEY_FLOOR a side; the theme extraction did not run"
fi

# --- f. no component reads the tokens past the theme --------------------------
# The tokens are where the DEFAULT theme comes from. A component, a sheet or a header that
# reads `SPFNTokens.x` or `SpfnTokens.x` itself draws that value whatever theme the app
# injected, and nothing looks wrong until an app themes it — so every source of the UI module
# outside its tokens directory is read, which is the components, `Sheet*`, `ModalCover` and
# whatever is added beside them. Comment lines are skipped: a header may name the tokens it
# no longer reads. The floor is a file count, for P7's reason: a root that moved reads as
# zero offenders.
STATIC_TOKEN_ROOTS='Sources/SPFNUI android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui'
STATIC_TOKEN_FLOOR=20
STATIC_TOKEN_FILES=0
STATIC_TOKEN_OFFENDERS=''

for source in $(find $STATIC_TOKEN_ROOTS \( -name '*.swift' -o -name '*.kt' \) 2> /dev/null | grep -v '/[Tt]okens/' | sort)
do
    STATIC_TOKEN_FILES=$((STATIC_TOKEN_FILES + 1))
    if grep -nE 'SPFNTokens|SpfnTokens' "$source" | grep -vqE '^[0-9]*:[[:space:]]*(//|\*|/\*)'
    then
        STATIC_TOKEN_OFFENDERS="$STATIC_TOKEN_OFFENDERS $source"
    fi
done

if [ "$STATIC_TOKEN_FILES" -ge "$STATIC_TOKEN_FLOOR" ]
then
    pass "the static-token scan read $STATIC_TOKEN_FILES UI sources outside the tokens directories"
else
    fail "the static-token scan read $STATIC_TOKEN_FILES UI sources, fewer than $STATIC_TOKEN_FLOOR; it did not run"
fi

if [ -z "$STATIC_TOKEN_OFFENDERS" ]
then
    pass 'no UI source outside the tokens reads the static token object; every one reads the injected theme'
else
    fail "UI sources that read the static token object instead of the theme:$STATIC_TOKEN_OFFENDERS; an app's theme cannot reach what they draw"
fi

# ---------------------------------------------------------------------------
section '20. no Button in SPFNUI is styled plain'
# ---------------------------------------------------------------------------
# A style draws its label as the part of a button a tap lands on, and `.plain` leaves the
# label's hit shape at the pixels it drew: a role button answered only over its letters and
# a 20pt header glyph only over itself inside its 44pt frame (P39). SPFNUI's two styles —
# `RoleButtonStyle` and `HeaderControlStyle` — now set `.contentShape(Rectangle())` on the
# label they are handed, so the rule holds wherever they are used.
#
# What they cannot do is stop the next button from reaching for `.plain` again, and nothing
# else can either: a SwiftUI hit shape is not a value a Swift test can read, this repository
# has no Swift lint, and the validator host has no Swift toolchain. So the one spelling that
# brings the defect back is refused by name, which does not depend on how the code around it
# is shaped.
PLAIN_READ=$(find Sources/SPFNUI -name '*.swift' | wc -l | tr -d ' ')
PLAIN_HITS=$(grep -rnE '\.buttonStyle\(\.plain\)|PlainButtonStyle\(' Sources/SPFNUI 2>/dev/null \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*//' || true)
if [ "$PLAIN_READ" -ge 10 ] && [ -z "$PLAIN_HITS" ]
then
    pass "none of the $PLAIN_READ SPFNUI sources styles a Button plain; every button's label is hit-tested whole"
elif [ "$PLAIN_READ" -lt 10 ]
then
    fail "the plain-style scan read $PLAIN_READ SPFNUI sources; it did not run"
else
    fail "SPFNUI styles a Button plain, which hit-tests only the pixels its label drew: $(printf '%s' "$PLAIN_HITS" | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
section '23. no provider adapter logs a token or reads a profile field'
# ---------------------------------------------------------------------------
# Rows C8 and C9 of the adapter case table, which until now were a Swift XCTest and nothing
# else. That file sits beside an Apple-only module and is guarded on
# `canImport(AuthenticationServices)`, so on Linux it compiles to nothing and the rule
# reported green by not existing; and it never reached the Android adapter, which is the
# half with its own logging vocabulary. Here both rows cover all three source trees on
# every host. The Swift suite stays: it also proves the classification drops a token out of
# an error value, which is a call and not a scan.
#
# Both rows are about ABSENCE, and absence is not observable through a call — an adapter
# that logs a token logs it wherever it likes, and one that reads a display field reads it
# in a branch a fake driver never enters. So both are read out of the sources.
#
# The logging list is the Swift suite's own C8 list plus the Android entry points it had no
# reason to name. It is written out here rather than narrowed per language, because a
# per-call-site judgement is exactly what stops being made later:
#
#   Swift   print(  NSLog(  os_log(  debugPrint(  dump(  FileHandle.standardOutput
#   Kotlin  println  Log.  Timber.  System.out  System.err  printStackTrace(
#
# The profile list is C9's, unchanged: this SDK's scope ends at the identity token
# (decision 1), and an app that wants a display name asks the provider itself.
# `requestedScopes` is admitted only where the line also carries the empty literal, which is
# how the Apple adapter asks for none.
#
# Every term is matched as a plain substring, the way the Swift suite matches its own. A
# word boundary would be the obvious refinement and it is the wrong one: both lists are
# reached through a receiver — `credential.fullName`, `android.util.Log.d` — so a rule that
# demanded a non-identifier character in front would refuse the bare spelling and wave the
# qualified one through, which is the spelling somebody reaches for when the bare one is
# refused.
#
# Comments are excluded, the way the Swift suite excludes them: a prohibition has to be
# describable in the file that obeys it. The scan carries a floor for the P7 reason — a scan
# that read nothing agrees with a clean one — and the floor is per directory, because two
# of the three trees hold one file each and a missing one would otherwise vanish quietly.
SOCIAL_SURFACE_DIRS='Sources/SPFNSocialApple Sources/SPFNSocialGoogle android/spfn-social-google/src/main'
SOCIAL_LOGGERS='print\(|NSLog\(|os_log\(|debugPrint\(|dump\(|FileHandle\.standardOutput|println|Log\.|Timber\.|System\.out|System\.err|printStackTrace\('
SOCIAL_PROFILE_FIELDS='fullName|givenName|familyName|nickName|middleName|emailAddress|profileData'

: > "$TMP/social-active.txt"
SOCIAL_SCANNED=0
SOCIAL_EMPTY_DIRS=''
for SOCIAL_DIR in $SOCIAL_SURFACE_DIRS
do
    SOCIAL_FILES=$(find "$SOCIAL_DIR" \( -name '*.swift' -o -name '*.kt' \) 2> /dev/null | sort)
    if [ -z "$SOCIAL_FILES" ]
    then
        SOCIAL_EMPTY_DIRS="$SOCIAL_EMPTY_DIRS $SOCIAL_DIR"
        continue
    fi
    for SOCIAL_FILE in $SOCIAL_FILES
    do
        SOCIAL_SCANNED=$((SOCIAL_SCANNED + 1))
        grep -vn '^[[:space:]]*\(//\|/\*\|\*\)' "$SOCIAL_FILE" \
            | sed "s|^|$SOCIAL_FILE:|" >> "$TMP/social-active.txt"
    done
done

if [ -z "$SOCIAL_EMPTY_DIRS" ] && [ "$SOCIAL_SCANNED" -ge 3 ]
then
    pass "the adapter surface scan read $SOCIAL_SCANNED sources across every adapter tree"
else
    fail "the adapter surface scan found no source under:$SOCIAL_EMPTY_DIRS (read $SOCIAL_SCANNED in all); it did not run"
fi

SOCIAL_LOG_HITS=$(grep -E "$SOCIAL_LOGGERS" "$TMP/social-active.txt" || true)
if [ -z "$SOCIAL_LOG_HITS" ]
then
    pass 'C8: no adapter source carries a logging call on either platform'
else
    fail 'C8: an adapter that can log is an adapter that can log a token:'
    printf '%s\n' "$SOCIAL_LOG_HITS" | sed 's/^/          /'
fi

SOCIAL_FIELD_HITS=$(grep -E "$SOCIAL_PROFILE_FIELDS" "$TMP/social-active.txt" || true)
SOCIAL_SCOPE_HITS=$(grep -E 'requestedScopes' "$TMP/social-active.txt" | grep -vF '[]' || true)
if [ -z "$SOCIAL_FIELD_HITS" ] && [ -z "$SOCIAL_SCOPE_HITS" ]
then
    pass 'C9: no adapter source reads a provider profile field or asks for a scope'
else
    fail 'C9: the identity token is the whole of what an adapter reads (decision 1):'
    printf '%s\n' "$SOCIAL_FIELD_HITS$SOCIAL_SCOPE_HITS" | sed 's/^/          /'
fi

# ---------------------------------------------------------------------------
section '24. every action a workflow uses is on the SHA-pinned list (D14)'
# ---------------------------------------------------------------------------
# D14, resolved 2026-09-18: a workflow may use an action only if its name AND its commit
# SHA are written down in tools/ci/actions-allowlist.txt. This section is the register
# those SHAs are read from, so bumping an action is an edit to a reviewed list rather than
# a character change inside a YAML file nobody diffs.
#
# It fails closed in both directions. No list, or no `uses:` line anywhere under
# .github/workflows, means the check had nothing to read — which is indistinguishable from
# a check that was silently deleted — so it is reported as a failure rather than as a pass
# with nothing behind it. An allowlist entry that is not itself a 40-hex pin fails too:
# a list that admitted `@v7` would admit every future commit that tag ever points at.
#
# tools/validate/probe-ci-actions-rules.sh proves all three refusals bite.
ACTIONS_ALLOWLIST=tools/ci/actions-allowlist.txt

if [ ! -f "$ACTIONS_ALLOWLIST" ]
then
    fail "$ACTIONS_ALLOWLIST is missing, so the action pinning rule did not run"
else
    # The reference alone, with the list-item dash, the `uses:` key and any trailing
    # comment removed. Full-line comments are dropped first: an action named in prose is
    # documentation, not a step.
    USES_REFS=$(grep -nE 'uses:' .github/workflows/*.yml 2>/dev/null \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' \
        | sed -E 's/[[:space:]]*#.*$//; s/^([^:]+:[0-9]+):[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*/\1 /' \
        | sed -E 's/[[:space:]]+$//' || true)

    ALLOWED_REFS=$(sed -E 's/[[:space:]]*#.*$//; s/[[:space:]]+$//' "$ACTIONS_ALLOWLIST" | grep -v '^$' || true)
    UNPINNED_ENTRIES=$(printf '%s\n' "$ALLOWED_REFS" | grep -v '^$' \
        | grep -vE '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$' || true)

    if [ -z "$USES_REFS" ]
    then
        fail "no workflow under .github/workflows names any action, so the action pinning rule did not run"
    elif [ -n "$UNPINNED_ENTRIES" ]
    then
        fail "$ACTIONS_ALLOWLIST holds entries that are not 40-hex commit pins: $(printf '%s' "$UNPINNED_ENTRIES" | tr '\n' ' ')"
    else
        UNLISTED_ACTIONS=''
        LISTED_ACTIONS=0
        printf '%s\n' "$USES_REFS" > "$TMP/uses-refs.txt"
        while read -r location reference
        do
            if printf '%s\n' "$ALLOWED_REFS" | grep -qxF -- "$reference"
            then
                LISTED_ACTIONS=$((LISTED_ACTIONS + 1))
            else
                UNLISTED_ACTIONS="$UNLISTED_ACTIONS $location:$reference"
            fi
        done < "$TMP/uses-refs.txt"

        if [ -n "$UNLISTED_ACTIONS" ]
        then
            fail "actions used by a workflow but not in $ACTIONS_ALLOWLIST:$UNLISTED_ACTIONS"
            printf '%s\n' "$USES_REFS" | sed 's/^/          /'
        else
            pass "all $LISTED_ACTIONS action reference(s) under .github/workflows are pinned by a SHA listed in $ACTIONS_ALLOWLIST"
        fi
    fi
fi

# ---------------------------------------------------------------------------
printf '\n'
note "swift build / swift test, ./gradlew build,"
note "./gradlew :contract-codegen:spfnCodegenVerify and pod ipc spec are separate"
note "commands with separate evidence; this validator does not run or infer them."
printf '\n%s checks, %s failures\n' "$CHECKS" "$FAILURES"

if [ "$FAILURES" -eq 0 ]
then
    printf 'RESULT: PASS\n'
    exit 0
fi

printf 'RESULT: FAIL\n'
exit 1

