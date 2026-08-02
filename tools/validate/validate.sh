#!/bin/sh
# SPFN Mobile — offline repository validator.
#
# Zero external dependencies beyond POSIX sh, grep, sed, awk, find and a SHA-256
# utility (`shasum` or `sha256sum`). No network, no package manager, no toolchain.
#
#   sh tools/validate/validate.sh
#
# What it deliberately does NOT do: pretend to validate things it cannot reach.
# Swift compilation is `swift build` / `swift test`. Android compilation and the Kotlin
# conformance suite are `./gradlew build`. Codegen determinism is
# `./gradlew :contract-codegen:spfnCodegenVerify`. Podspec parsing is `pod ipc spec`.
# Those are separate commands with separate evidence; this script never fakes them.
#
# Step 2 changed what honesty requires here. Decision D5 fixed the toolchain baseline,
# so rules that used to read "no wrapper may exist" now read "the wrapper must match the
# checksum Gradle publishes". Nothing was relaxed: publication stays disabled, the auth
# boundary stays single-profile, and the contract lock gained a stricter rule than it
# had, because a resolved lock can lie in ways a placeholder cannot.

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

# Asserts a file contains a fixed string, ignoring case.
contains_i()
{
    if [ -f "$1" ] && grep -qiF -- "$2" "$1"
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
section '1. required layout'
# ---------------------------------------------------------------------------
for path in \
    Package.swift settings.gradle.kts build.gradle.kts gradle.properties \
    gradle/libs.versions.toml gradle/verification-metadata.xml \
    gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar \
    gradle/wrapper/gradle-wrapper.properties gradle/wrapper/WRAPPER-PINS.json \
    VERSION COMPATIBILITY.md CHANGELOG.md RELEASE.md SECURITY.md CONTRIBUTING.md \
    LICENSE CODEOWNERS README.md .gitignore \
    Contracts/upstream.lock.json Contracts/spfn-mobile-contract.json \
    Contracts/auth-profiles/clientProofV1.schema.json Contracts/fixtures/MANIFEST.json \
    tools/module-graph.json tools/conformance/semver-range-vectors.json \
    tools/contract-codegen/README.md \
    tools/contract-codegen/build.gradle.kts \
    tools/validate/validate.sh tools/validate/d11-forbidden.ere \
    tools/validate/d11-policy.lock.json tools/validate/probe-d11-guardrail.sh \
    tools/cocoapods-compat/generate-podspec.sh \
    docs/SCAFFOLD-STATUS.md docs/OPEN-DECISIONS.md docs/IMPLEMENTATION-PITFALLS.md \
    .github/workflows/contract.yml .github/workflows/swift.yml \
    .github/workflows/android.yml .github/workflows/security.yml \
    .github/workflows/release-candidate.yml
do
    if [ -f "$path" ]
    then
        pass "file $path"
    else
        fail "missing file $path"
    fi
done

for path in \
    Sources Tests android Contracts/fixtures tools examples/ios-swiftui \
    examples/android-compose examples/hybrid docs/architecture docs/migration docs/security \
    Tests/SPFNConformanceTests "$SWIFT_GENERATED" "$KOTLIN_GENERATED"
do
    if [ -d "$path" ]
    then
        pass "dir  $path"
    else
        fail "missing dir $path"
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

SECRETS=$(find . -path ./.git -prune -o \
    \( -name '.netrc' -o -name '*.p12' -o -name '*.jks' -o -name '*.keystore' \
       -o -name '*.mobileprovision' -o -name '*.pem' -o -name '*.key' -o -name 'id_rsa*' \
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
contains android/spfn-core/src/main/kotlin/xyz/superfunction/spfn/core/SpfnCore.kt "\"$VERSION\"" 'SpfnVersion.CURRENT matches VERSION'
contains gradle.properties "spfn.version=$VERSION" 'gradle.properties spfn.version matches VERSION'
contains "$PODSPEC" "'$VERSION'" 'CocoaPods fixture version matches VERSION'
contains CHANGELOG.md "$VERSION" 'CHANGELOG references VERSION'

# ---------------------------------------------------------------------------
section '5. contract lock discipline'
# ---------------------------------------------------------------------------
# A resolved lock can lie in a way a placeholder cannot: it can claim provenance it does
# not have. The rules below let a locally authored dev bundle be pinned honestly, and
# refuse any claim of an upstream export that carries no upstream evidence.
STATUS=$(json_string "$LOCK" status)
LOCK_DIGEST=$(json_string "$LOCK" manifestSha256)
BUNDLE_PATH=$(json_string "$LOCK" bundlePath)
LOCK_ORIGIN=$(json_string "$LOCK" origin)
LOCK_EXPORTED=$(json_bool "$LOCK" exportedByUpstreamCI)
LOCK_COMMIT=$(json_string "$LOCK" commit)
UPSTREAM_ORIGIN='spfn-primitives-ci-export'
UPSTREAM_EVIDENCE=Contracts/upstream-provenance.json

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

        if printf '%s' "$LOCK_DIGEST" | grep -qE '^[0-9a-f]{64}$'
        then
            pass 'manifestSha256 is 64 lowercase hex characters'
        else
            fail "manifestSha256 '$LOCK_DIGEST' is not a SHA-256 digest"
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

        # Until this change set the rule was "refuse an upstream claim that carries no
        # evidence". Now that a real export exists the rule turns around: the claim must
        # be checked against the evidence rather than merely accompanied by it. A lock
        # that agrees with itself proves nothing; a lock that agrees with a file the
        # exporter wrote is the whole point of pinning.
        if [ -f "$UPSTREAM_EVIDENCE" ]
        then
            pass "upstream provenance evidence exists at $UPSTREAM_EVIDENCE"

            EV_ORIGIN=$(json_string "$UPSTREAM_EVIDENCE" origin)
            EV_EXPORTED=$(json_bool "$UPSTREAM_EVIDENCE" exportedByUpstreamCI)
            EV_DIGEST=$(json_string "$UPSTREAM_EVIDENCE" bundleSha256)
            EV_EXPORTER=$(json_string "$UPSTREAM_EVIDENCE" exporterVersion)
            EV_REPOSITORY=$(json_string "$UPSTREAM_EVIDENCE" repository)
            EV_VERSION=$(json_string "$UPSTREAM_EVIDENCE" version)
            EV_RANGE=$(json_string "$UPSTREAM_EVIDENCE" supportedRange)
            LOCK_EXPORTER=$(json_string "$LOCK" exporterVersion)
            LOCK_REPOSITORY=$(json_string "$LOCK" repository)
            LOCK_VERSION=$(json_string "$LOCK" version)
            LOCK_RANGE=$(json_string "$LOCK" supportedRange)

            equals "$EV_ORIGIN" "$UPSTREAM_ORIGIN" 'the evidence names the same exporter as the lock'
            equals "$EV_EXPORTED" 'true' 'the evidence itself records an upstream CI export'
            equals "$EV_DIGEST" "$LOCK_DIGEST" \
                'the evidence records the same bundle digest the lock pins'
            equals "$LOCK_EXPORTER" "$EV_EXPORTER" 'lock and evidence name the same exporter version'
            equals "$LOCK_REPOSITORY" "$EV_REPOSITORY" 'lock and evidence name the same source repository'
            equals "$LOCK_VERSION" "$EV_VERSION" 'lock and evidence name the same contract version'
            equals "$LOCK_RANGE" "$EV_RANGE" 'lock and evidence declare the same supported range'

            if printf '%s' "$EV_REPOSITORY" | grep -qi 'spfn-mobile'
            then
                fail "the evidence names '$EV_REPOSITORY' as the source; a bundle this repository wrote is not an upstream export"
            else
                pass 'the evidence names a source repository other than this one'
            fi
        else
            fail "an upstream-export claim requires $UPSTREAM_EVIDENCE; none exists, so the claim is unsupported"
        fi

        # The lock's range must be the one its own version implies, so a pin cannot
        # quietly widen what the SDK accepts. Below 1.0.0 the breaking axis is the minor.
        LOCK_MAJOR=$(json_number "$LOCK" major)
        LOCK_MINOR=$(json_number "$LOCK" minor)
        if [ "$LOCK_MAJOR" = "0" ]
        then
            equals "$LOCK_RANGE" ">=$LOCK_VERSION <0.$((LOCK_MINOR + 1)).0" \
                'a 0.x lock declares a range bounded by the next minor, not the next major'
        else
            equals "$LOCK_RANGE" ">=$LOCK_VERSION <$((LOCK_MAJOR + 1)).0.0" \
                'a stable lock declares a range bounded by the next major'
        fi
        case "$LOCK_VERSION" in
            "$LOCK_MAJOR.$LOCK_MINOR."*)
                pass 'the lock version agrees with the major and minor recorded beside it'
                ;;
            *)
                fail "lock version '$LOCK_VERSION' does not start with its own major.minor ($LOCK_MAJOR.$LOCK_MINOR)"
                ;;
        esac

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
    if printf '%s' "$LOCK_DIGEST" | grep -qE '^[0-9a-f]{64}$'
    then
        pass 'manifestSha256 is 64 lowercase hex characters'
    else
        fail "manifestSha256 '$LOCK_DIGEST' is not a SHA-256 digest"
    fi

    equals "$(sha256_of "$BUNDLE_PATH")" "$LOCK_DIGEST" \
        "the pinned digest is the real SHA-256 of $BUNDLE_PATH"

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
    contains Contracts/fixtures/MANIFEST.json "\"bundleSha256\": \"$LOCK_DIGEST\"" \
        'fixture MANIFEST.json pins the same bundle digest as the lock'

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

contains "$LOCK" '"allowed": ["clientProofV1"]' 'lock allowlists exactly clientProofV1'
contains "$LOCK" '"unknownProfilePolicy": "reject"' 'lock rejects unknown auth profiles (no fallback)'
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
section '6. forbidden interactive-browser auth surface'
# ---------------------------------------------------------------------------
# v1 is clientProofV1 only. Any appearance of redirect/browser auth vocabulary in the
# API surface, contract data, examples or CI is a boundary violation. Documentation is
# excluded so it can describe the prohibition.
#
# The terms are matched as whole words. A bare substring match also fires on ordinary
# identifiers — `INVALID_TOKEN` ends in `id_token` — and a check that cries wolf is a
# check people start ignoring.
AUTH_TERMS='oidc|pkce|code_verifier|code_challenge|id_token|openid|authorization_code|oauth|implicit_grant'
HITS=$(grep -rIniE "(^|[^A-Za-z0-9_])($AUTH_TERMS)([^A-Za-z0-9_]|\$)" \
    $SURFACE_DIRS 2>/dev/null || true)
if [ -z "$HITS" ]
then
    pass 'no interactive-browser auth vocabulary in the public surface'
else
    fail 'interactive-browser auth vocabulary found:'
    printf '%s\n' "$HITS" | sed 's/^/          /'
fi

SWIFT_CASES=$(grep -c '^    case ' Sources/SPFNAuth/SPFNAuthProfile.swift 2>/dev/null || printf '0')
if [ "$SWIFT_CASES" = "1" ]
then
    pass 'SPFNAuthProfile declares exactly one profile'
else
    fail "SPFNAuthProfile declares $SWIFT_CASES profiles; v1 allows exactly one"
fi

contains Sources/SPFNAuth/SPFNAuthPolicy.swift 'allowedProfiles: [SPFNAuthProfile] = [.clientProofV1]' 'Swift allowlist is exactly clientProofV1'
contains android/spfn-auth/src/main/kotlin/xyz/superfunction/spfn/auth/SpfnAuthProfile.kt 'listOf(SpfnAuthProfile.CLIENT_PROOF_V1)' 'Kotlin allowlist is exactly clientProofV1'
contains Sources/SPFNHybrid/SPFNHybrid.swift 'allowedBridgeMessageNames: [String] = []' 'hybrid exposes no JavaScript bridge'
contains android/spfn-hybrid/src/main/kotlin/xyz/superfunction/spfn/hybrid/SpfnHybrid.kt 'ALLOWED_BRIDGE_MESSAGE_NAMES: List<String> = emptyList()' 'Android hybrid exposes no JavaScript bridge'

# Every generated operation must name the one allowed profile.
GENERATED_PROFILES=$(grep -h 'authProfile' "$SWIFT_GENERATED"/SPFNGeneratedOperations.swift 2>/dev/null \
    | sed -n 's/.*authProfile: "\([^"]*\)".*/\1/p' | sort -u)
if [ "$GENERATED_PROFILES" = "clientProofV1" ]
then
    pass 'every generated operation uses clientProofV1'
else
    fail "generated operations name profiles other than clientProofV1: $GENERATED_PROFILES"
fi

# ---------------------------------------------------------------------------
section '7. publication disabled, dependency sources constrained'
# ---------------------------------------------------------------------------
contains gradle.properties 'spfn.publishing.enabled=false' 'Gradle publishing disabled'
contains gradle.properties 'spfn.maven.group.verified=false' 'Maven namespace marked unverified'

GRADLE_FILES=$(find . -path ./.git -prune -o -path ./.gradle -prune -o -path './*/build' -prune -o \
    -name '*.gradle.kts' -print)
for file in $GRADLE_FILES
do
    lacks_active "$file" '(maven-publish|id\("signing"\)|^[[:space:]]*publishing[[:space:]]*\{|credentials[[:space:]]*\{)' \
        "no publication or credential block in $file"
    # Repositories are now legal, because D5 approved a toolchain that has to come from
    # somewhere. Only the three sources needed for that toolchain are allowed, and a
    # hand-written `maven { url ... }` still fails: an arbitrary repository is exactly
    # how an unreviewed artifact enters a build.
    lacks_active "$file" 'maven[[:space:]]*\{' "no arbitrary maven repository in $file"
done

REPOS=$(grep -hoE '^[[:space:]]*(google|mavenCentral|gradlePluginPortal|mavenLocal|jcenter)\(\)' $GRADLE_FILES 2>/dev/null \
    | tr -d ' ' | sort -u)
UNEXPECTED_REPOS=$(printf '%s\n' "$REPOS" | grep -vE '^(google|mavenCentral|gradlePluginPortal)\(\)$' || true)
if [ -z "$UNEXPECTED_REPOS" ]
then
    pass 'only google(), mavenCentral() and gradlePluginPortal() are declared'
else
    fail "unexpected dependency repositories: $UNEXPECTED_REPOS"
fi

contains gradle/verification-metadata.xml '<verify-metadata>true</verify-metadata>' \
    'Gradle dependency verification is enabled'
VERIFIED_COMPONENTS=$(grep -c '<component ' gradle/verification-metadata.xml || printf '0')
if [ "$VERIFIED_COMPONENTS" -gt 0 ]
then
    pass "dependency verification records $VERIFIED_COMPONENTS components with real checksums"
else
    fail 'dependency verification has no components while dependencies are declared'
fi

lacks_active Package.swift '\.package\(' 'Package.swift declares zero external dependencies'

TRUNK=$(grep -rIl 'pod trunk push' . --exclude-dir=.git --exclude-dir=.build --exclude-dir=.gradle --exclude=validate.sh 2>/dev/null || true)
if [ -z "$TRUNK" ]
then
    pass 'no CocoaPods trunk publication command anywhere'
else
    fail "CocoaPods trunk publication command present in: $TRUNK"
fi

for workflow in .github/workflows/*.yml
do
    lacks_active "$workflow" 'uses:' "$workflow uses no third-party action, so there is nothing to pin"
    lacks_active "$workflow" '(secrets\.|publish|deploy|upload-artifact|trunk|registry)' \
        "$workflow requests no secret and performs no publication"
    contains "$workflow" 'NOT A GATE' "$workflow states that it is not a gate"
    contains "$workflow" 'workflow_dispatch' "$workflow is manual-only"
done

# ---------------------------------------------------------------------------
section '8. module graph coherence'
# ---------------------------------------------------------------------------
grep '"swiftTarget"' "$GRAPH" > "$TMP/modules.txt"
MODULE_COUNT=$(wc -l < "$TMP/modules.txt" | tr -d ' ')

while IFS= read -r line
do
    swift_target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
    android_module=$(printf '%s' "$line" | sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p')
    swift_deps=$(printf '%s' "$line" | sed -n 's/.*"swiftDependsOn": \[\([^]]*\)\].*/\1/p')
    android_deps=$(printf '%s' "$line" | sed -n 's/.*"androidDependsOn": \[\([^]]*\)\].*/\1/p')

    if [ -d "Sources/$swift_target" ] && [ -n "$(find "Sources/$swift_target" -name '*.swift' -print -quit)" ]
    then
        pass "Sources/$swift_target exists with Swift sources"
    else
        fail "Sources/$swift_target missing or empty"
    fi

    contains Package.swift ".library(name: \"$swift_target\", targets: [\"$swift_target\"])" \
        "Package.swift exposes product $swift_target"

    if [ -z "$swift_deps" ]
    then
        contains Package.swift ".target(name: \"$swift_target\")" "Package.swift target $swift_target has no dependencies"
    else
        rendered=$(printf '%s' "$swift_deps" | sed 's/, /, /g')
        contains Package.swift ".target(name: \"$swift_target\", dependencies: [$rendered])" \
            "Package.swift target $swift_target dependency edge matches the graph"
    fi

    contains settings.gradle.kts "\":$android_module\"" "settings.gradle.kts includes :$android_module"
    contains settings.gradle.kts "project(\":$android_module\").projectDir = file(\"android/$android_module\")" \
        ":$android_module maps to android/$android_module"

    if [ -f "android/$android_module/build.gradle.kts" ] \
        && [ -n "$(find "android/$android_module/src/main/kotlin" -name '*.kt' -print -quit 2>/dev/null)" ]
    then
        pass "android/$android_module has a build script and Kotlin sources"
    else
        fail "android/$android_module missing build script or Kotlin sources"
    fi

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

    contains "$PODSPEC" "s.subspec '$swift_target'" "CocoaPods fixture has subspec $swift_target"
    printf '%s\n' "$swift_deps" | tr ',' '\n' | sed 's/[" ]//g' > "$TMP/deps.txt"
    while IFS= read -r dep
    do
        [ -n "$dep" ] || continue
        contains "$PODSPEC" "sp.dependency 'SPFNMobileCompatFixture/$dep'" \
            "CocoaPods fixture edge $swift_target -> $dep"
    done < "$TMP/deps.txt"
done < "$TMP/modules.txt"

SOURCE_DIRS=$(find Sources -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
ANDROID_DIRS=$(find android -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
SUBSPECS=$(grep -c 's.subspec ' "$PODSPEC" || printf '0')

for actual_pair in "Sources:$SOURCE_DIRS" "android:$ANDROID_DIRS" "podspec subspecs:$SUBSPECS"
do
    label=${actual_pair%:*}
    actual=${actual_pair##*:}
    if [ "$actual" = "$MODULE_COUNT" ]
    then
        pass "$label count is $MODULE_COUNT, matching module-graph.json"
    else
        fail "$label count is $actual but module-graph.json declares $MODULE_COUNT (undeclared module?)"
    fi
done

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
    if ! grep -q "bundleSha256:    $LOCK_DIGEST" "$generated"
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
    pass 'every generated header carries the digest pinned in the lock'
else
    fail "generated sources name a digest the lock does not pin:$WRONG_DIGEST"
fi

# ---------------------------------------------------------------------------
section '10. toolchain baseline (D5) is declared, not implied'
# ---------------------------------------------------------------------------
contains Package.swift 'swift-tools-version: 6.0' 'Package.swift pins swift-tools-version 6.0'
contains Package.swift '.iOS(.v16)' 'Package.swift pins the iOS 16 baseline'
contains Package.swift '.macOS(.v13)' 'Package.swift pins the macOS 13 baseline'

contains gradle/libs.versions.toml 'agp = ' 'version catalogue pins the AGP line'
contains gradle/libs.versions.toml 'kotlin = ' 'version catalogue pins the Kotlin line'
contains gradle/libs.versions.toml 'jdk-toolchain = ' 'version catalogue pins the JDK toolchain'
contains gradle/libs.versions.toml 'min-sdk = ' 'version catalogue pins minSdk'
contains gradle/libs.versions.toml 'compile-sdk = ' 'version catalogue pins compileSdk'

# Read from the module graph rather than restated here. A hand-written list silently
# stops covering a module the moment someone adds one, which is the failure mode this
# whole section exists to prevent.
for module in $(sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p' "$GRAPH")
do
    script="android/$module/build.gradle.kts"
    contains "$script" 'jvmToolchain(libs.versions.jdk.toolchain.get().toInt())' \
        "$module compiles on the pinned JDK toolchain"
    contains "$script" 'jvmTarget = JvmTarget.JVM_11' \
        "$module pins the AAR bytecode target explicitly"
    contains "$script" 'sourceCompatibility = JavaVersion.VERSION_11' \
        "$module pins javac source compatibility explicitly"
done

# ---------------------------------------------------------------------------
section '11. unresolved ownership and support are represented honestly'
# ---------------------------------------------------------------------------
if grep -vE '^[[:space:]]*(#|$)' CODEOWNERS | grep -q '@'
then
    fail 'CODEOWNERS contains an active owner entry; real handles and teams are undecided'
else
    pass 'CODEOWNERS has no invented owner identities'
fi

contains LICENSE 'MIT License' 'LICENSE is the MIT license decided in D8 (2026-08-01)'
contains LICENSE 'FXY Inc.' 'LICENSE names the decided copyright holder'
contains COMPATIBILITY.md 'UNRESOLVED' 'compatibility matrix still marks unresolved support rows'
contains docs/OPEN-DECISIONS.md 'OS/toolchain baseline' 'open decisions still record the OS/toolchain baseline entry'
contains docs/OPEN-DECISIONS.md 'Maven' 'open decisions record the Maven namespace question'
# D17 asked for an upstream export and now has one. The assertion follows the decision
# rather than being deleted with it: what has to stay recorded is that the contract comes
# from upstream, since that is the claim every other provenance check depends on.
contains docs/OPEN-DECISIONS.md 'Upstream contract export tooling | **RESOLVED' \
    'open decisions record D17 as resolved by a real upstream export'

# The pitfall register is only a device if its trigger table reaches every entry. An
# entry nothing routes to is never quoted into a brief, and a row pointing at an anchor
# that does not exist is a dead link — both rot the moment somebody adds an entry and
# forgets the table, which is the one failure mode this document has of its own.
#
# Counted, not merely searched: a run that reached no entries did not check the routing,
# and reporting that as clean is the shape this repository has already been bitten by.
REGISTER=docs/IMPLEMENTATION-PITFALLS.md
awk '
/^## 트리거 → 항목/ { table = 1; next }
/^---$/            { table = 0 }
table {
    rest = $0
    while (match(rest, /\(#p[0-9]+\)/))
    {
        routed[substr(rest, RSTART + 2, RLENGTH - 3)] = 1
        rest = substr(rest, RSTART + RLENGTH)
    }
}
/^## P[0-9]+\./ && match($0, /\{#p[0-9]+\}/) {
    headings++
    entries[substr($0, RSTART + 2, RLENGTH - 3)] = 1
}
END {
    unrouted = ""; dead = ""
    for (a in entries) { total++;  if (!(a in routed))  { unrouted = unrouted " " a } }
    for (a in routed)  { routes++; if (!(a in entries)) { dead = dead " " a } }
    printf "%d %d %d %s|%s\n", total + 0, routes + 0, headings + 0, unrouted, dead
}
' "$REGISTER" > "$TMP/register" 2>/dev/null || true

REGISTER_ENTRIES=$(awk '{print $1}' "$TMP/register")
REGISTER_ROUTES=$(awk '{print $2}' "$TMP/register")
REGISTER_HEADINGS=$(awk '{print $3}' "$TMP/register")
REGISTER_UNROUTED=$(sed 's/|.*//; s/^[0-9]* [0-9]* [0-9]* *//' "$TMP/register")
REGISTER_DEAD=$(sed 's/^[^|]*|//; s/^ *//' "$TMP/register")

if [ "${REGISTER_ENTRIES:-0}" -lt 10 ]
then
    fail "the pitfall register yielded only ${REGISTER_ENTRIES:-0} entries; it lost its entries or the scan could not read it"
elif [ "${REGISTER_ROUTES:-0}" -lt 10 ]
then
    fail "the pitfall register's trigger table yielded only ${REGISTER_ROUTES:-0} routed entries; the table is gone or the scan could not read it"
elif [ "${REGISTER_HEADINGS:-0}" -ne "$REGISTER_ENTRIES" ]
then
    # Anchors are collected as map keys, so two entries sharing one anchor collapse into
    # a single key and every reachability check below passes while one of them cannot be
    # addressed. Counting headings separately is what sees it. The routes side gets no
    # such rule on purpose: an entry routed from several trigger rows is correct, and P2
    # is deliberately reachable from three.
    fail "the pitfall register has ${REGISTER_HEADINGS:-0} entry headings but only $REGISTER_ENTRIES distinct anchors; an anchor is used twice"
elif [ -n "$REGISTER_DEAD" ]
then
    fail "the pitfall register's trigger table points at entries that do not exist:$REGISTER_DEAD"
elif [ -n "$REGISTER_UNROUTED" ]
then
    fail "these pitfall register entries are not reachable from the trigger table:$REGISTER_UNROUTED"
else
    pass "the pitfall register routes all $REGISTER_ENTRIES entries from its trigger table"
fi
# D11 decided that CocoaPods is not supported and deliberately recorded no activation
# condition. Wording that names a route to turn it on makes "not supported" read as
# "available on request", which is the one reading the decision exists to prevent.
#
# The gate is the digest, not a blocklist. A blocklist can only refuse the phrasings
# somebody thought of, and a policy sentence can be rewritten in unbounded ways; pinning
# the text means any edit fails until the lock is updated on purpose. The blocklist
# further down stays as a second, best-effort net over the REST of the fixture README,
# where free prose is legitimate and a digest would be too rigid.
D11_LOCK=tools/validate/d11-policy.lock.json
D11_SECTION=$(json_string "$D11_LOCK" section)
D11_PINNED=$(json_string "$D11_LOCK" sha256)
D11_ROW_PREFIX=$(json_string "$D11_LOCK" rowPrefix)
D11_ROW_PINNED=$(json_string "$D11_LOCK" rowSha256)

# The decision is written in two places and both are pinned whole. A substring check on
# the row's state cell would pass while the rest of the row said the opposite.
grep "^$D11_ROW_PREFIX" docs/OPEN-DECISIONS.md > "$TMP/d11-row.txt"
D11_ROW_COUNT=$(grep -c "^$D11_ROW_PREFIX" docs/OPEN-DECISIONS.md)
if [ "$D11_ROW_COUNT" != "1" ]
then
    fail "docs/OPEN-DECISIONS.md carries $D11_ROW_COUNT rows starting '$D11_ROW_PREFIX', expected exactly 1"
else
    equals "$(sha256_of "$TMP/d11-row.txt")" "$D11_ROW_PINNED" \
        'the whole D11 decision row is byte-identical to the row pinned in d11-policy.lock.json'
fi
awk -v heading="$D11_SECTION" \
    '$0 == heading {f = 1; print; next} f && /^## / {f = 0} f {print}' \
    tools/cocoapods-compat/README.md > "$TMP/d11-section.txt"
if [ ! -s "$TMP/d11-section.txt" ]
then
    fail "the D11 policy section '$D11_SECTION' is missing from the CocoaPods fixture README"
else
    equals "$(sha256_of "$TMP/d11-section.txt")" "$D11_PINNED" \
        'the D11 policy statement is byte-identical to the text pinned in d11-policy.lock.json'
fi

D11_FORBIDDEN=$(grep -v '^#' tools/validate/d11-forbidden.ere | grep -v '^$')
if [ -z "$D11_FORBIDDEN" ]
then
    fail 'tools/validate/d11-forbidden.ere carries no pattern'
elif grep -qiE "$D11_FORBIDDEN" tools/cocoapods-compat/README.md
then
    fail 'the CocoaPods fixture README reopens D11 with proposal or activation wording'
else
    pass 'the CocoaPods fixture README states D11 as decided, with no activation condition'
fi

# A negative check earns its line only if it bites. The probe holds the pinned section
# and the blocklist to both sides — what each must catch, and what each must spare.
if sh tools/validate/probe-d11-guardrail.sh > /dev/null 2>&1
then
    pass 'the D11 guardrail probe passes on both its positive and negative samples'
else
    fail 'tools/validate/probe-d11-guardrail.sh fails; the D11 guardrail no longer holds'
fi

# A build/parity baseline is not a support commitment. The compatibility matrix must not
# quietly acquire one just because the toolchain was pinned.
if grep -E '^\| (iOS|Android) \|' COMPATIBILITY.md | grep -q 'UNRESOLVED'
then
    pass 'iOS and Android support rows stay UNRESOLVED pending device evidence'
else
    fail 'an iOS or Android support row claims support without real-device evidence'
fi

# ---------------------------------------------------------------------------
section '12. repository status is stated, not implied'
# ---------------------------------------------------------------------------
for doc in README.md docs/SCAFFOLD-STATUS.md CONTRIBUTING.md RELEASE.md SECURITY.md
do
    contains_i "$doc" 'scaffold' "$doc states this is still a scaffold"
done

contains README.md 'no public support' 'README refuses to promise public support'
contains RELEASE.md 'No release has been made' 'RELEASE.md states no release exists'
contains Sources/SPFNCore/SPFNScaffold.swift 'isScaffold: Bool = true' 'the built library declares itself a scaffold'
contains android/spfn-core/src/main/kotlin/xyz/superfunction/spfn/core/SpfnCore.kt 'IS_SCAFFOLD: Boolean = true' \
    'the Android library declares itself a scaffold'

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
