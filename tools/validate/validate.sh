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

# Every import in one file that reaches a named framework with nothing making it
# conditional, as `path:line ` pairs.
#
# The `#if` nesting is tracked rather than pattern-matched: an import can sit at any
# depth inside a guard, and a line-anchored read would call every one of them
# unguarded. Only `#if canImport(...)` counts as a guard — `#if os(iOS)` and a trait
# condition say WHEN to compile, not WHETHER the framework is there — and the `#else`
# arm of a canImport guard stays admitted, because that arm is the platform that
# does not have it.
unguarded_imports()
{
    awk -v frameworks="$2" '
        BEGIN { depth = 0; guarded = 0 }
        /^[[:space:]]*#if/ {
            depth++
            canimport[depth] = ($0 ~ /#if[[:space:]]*canImport\(/) ? 1 : 0
            guarded += canimport[depth]
            next
        }
        /^[[:space:]]*#endif/ {
            if (depth > 0) { guarded -= canimport[depth]; depth-- }
            next
        }
        guarded == 0 && $0 ~ ("^[[:space:]]*import[[:space:]]+(" frameworks ")[[:space:]]*$") {
            printf "%s:%d ", FILENAME, FNR
        }
    ' "$1"
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
    tools/validate/probe-publishing-gate.sh \
    tools/validate/probe-publication-rules.sh \
    tools/validate/probe-social-adapter-rules.sh \
    tools/rc-verify/rc-verify.sh tools/rc-verify/generate-ios-sbom.sh \
    tools/rc-verify/probe-trap-exit.sh tools/rc-verify/local-signed-run.sh \
    tools/device-receipts/receipt-gate.sh tools/device-receipts/probe-receipt-gate.sh \
    tools/cocoapods-compat/generate-podspec.sh \
    tools/verify-server/run.sh tools/verify-server/probe-refusals.sh \
    tools/verify-server/README.md tools/verify-server/spfn-versions.sh \
    docs/SCAFFOLD-STATUS.md docs/OPEN-DECISIONS.md docs/IMPLEMENTATION-PITFALLS.md \
    .github/workflows/contract.yml .github/workflows/swift.yml \
    .github/workflows/android.yml .github/workflows/security.yml \
    .github/workflows/release-candidate.yml .github/workflows/publish-central.yml
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
    examples/android-compose docs/architecture docs/migration docs/security \
    tools/device-receipts tools/device-receipts/runs \
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
            # The two ranges answer different questions and stop being identical the
            # moment a pinned version carries a patch. The evidence declares the line
            # upstream supports (">=0.4.0 <0.5.0"); the lock declares the window THIS
            # SDK admits (">=0.4.1 <0.5.0"), whose floor is the pinned version because
            # 0.4.1 added operations a 0.4.0 server does not serve. Requiring the two to
            # be equal would force the lock to promise a server it would then call
            # missing operations on.
            #
            # So the rule is containment, checked in the only two places it can go wrong
            # on a 0.x line: the ceilings must agree, and the evidence floor must name
            # the same minor the lock pins. The lock's own floor is pinned to its exact
            # version by the range-shape check below, so a lock cannot widen itself.
            EV_CEILING=${EV_RANGE##*<}
            LOCK_CEILING=${LOCK_RANGE##*<}
            EV_FLOOR=${EV_RANGE#>=}
            EV_FLOOR=${EV_FLOOR%% *}
            equals "$LOCK_CEILING" "$EV_CEILING" \
                'lock and evidence declare the same upper bound'
            if [ -z "$EV_FLOOR" ] || [ -z "$EV_CEILING" ] || [ "$EV_FLOOR" = "$EV_RANGE" ]
            then
                fail "the evidence range '$EV_RANGE' is not a '>=<floor> <<ceiling>' window"
            else
                pass 'the evidence range parses as a bounded window'
            fi
            equals "${EV_FLOOR%.*}" "${LOCK_VERSION%.*}" \
                'the evidence floor names the same major.minor the lock pins'

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
contains gradle.properties 'spfn.publishing.enabled=false' 'Gradle publishing disabled'
contains gradle.properties 'spfn.maven.group.verified=true' \
    'Maven namespace recorded as Central-verified (D4, resolved 2026-08-03)'
contains gradle.properties 'spfn.maven.group=xyz.superfunction.spfn' \
    'the D4-verified Maven group is the committed coordinate'

# No committed property may ever hold a credential or key. The active keys in
# gradle.properties are version, gate flags, group and Gradle tuning — anything
# credential-shaped is a value that belongs in a per-run environment, never in a file.
lacks_active gradle.properties '[Ss]igning|[Tt]oken|[Pp]assword|[Ss]ecret|[Cc]redential|[Kk]ey' \
    'gradle.properties commits no credential-shaped key'

# The publication transition (D3/D4/D7) narrowed these rules again without weakening
# them. The root build script — and only the root build script — holds publication and
# signing configuration. What it may hold is pinned below: publication exists only
# behind the per-run gate towards the local staging directory, and signing exists only
# as an in-memory key looked up from the per-run environment. Credentials are banned
# in BOTH syntactic forms — the `credentials { }` block and the call form
# `credentials(...)` — unless the same line is a pure lookup, and a literal username
# or password value fails wherever it appears. Central itself is never a Gradle
# repository: the upload is a bundle POST done by the manual workflow, so any remote
# publication URL in a build script is still a failure.
# tools/validate/probe-publication-rules.sh proves each of these refusals bites.
GRADLE_FILES=$(find . -path ./.git -prune -o -path ./.gradle -prune -o -path './*/build' -prune -o \
    -name '*.gradle.kts' -print)
CREDENTIAL_LOOKUPS='environmentVariable\(|gradleProperty\(|System\.getenv\(|PasswordCredentials::class'
for file in $GRADLE_FILES
do
    # Both credential forms, block and call. A hit is legal only when the same line is
    # one of the approved lookup shapes; any other hit — above all a literal value —
    # fails.
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

    if [ "$file" = "./build.gradle.kts" ]
    then
        continue
    fi

    lacks_active "$file" '(id\("signing"\)|apply\("signing"\)|apply\(plugin[[:space:]]*=[[:space:]]*"signing"\)|SigningExtension|useInMemoryPgpKeys|^[[:space:]]*signing[[:space:]]*(\{|$))' \
        "no signing configuration outside the gated root script in $file"
    lacks_active "$file" '(maven-publish|^[[:space:]]*publishing[[:space:]]*\{)' \
        "no publication block in $file"
    lacks_active "$file" 'https?://' \
        "no URL literal outside the root build script in $file"
    # Repositories are now legal, because D5 approved a toolchain that has to come from
    # somewhere. Only the three sources needed for that toolchain are allowed, and a
    # hand-written `maven { url ... }` still fails: an arbitrary repository is exactly
    # how an unreviewed artifact enters a build.
    lacks_active "$file" 'maven[[:space:]]*\{' "no arbitrary maven repository in $file"
done

# Signing in the root: lookup-only, in memory, per run. The admission is pinned as the
# exact mechanism; every path that would put key material or key identity in the tree
# is refused.
contains build.gradle.kts 'useInMemoryPgpKeys(signingKey' \
    'root signing admits only the in-memory key mechanism'
contains build.gradle.kts 'providers.gradleProperty("spfnSigningInMemoryKey")' \
    'the signing key arrives as a per-run property lookup (ORG_GRADLE_PROJECT_*)'
lacks_active build.gradle.kts '(secretKeyRingFile|signing\.keyId|\.gpg|\.asc|secring|pubring)' \
    'root signing names no key file, keyring or key identity'

# The root build script's publication gate, held by its load-bearing lines. These are
# fixed strings on purpose: each one is a refusal the probe script exercises, and an
# edit that removes the refusal removes the string.
contains build.gradle.kts 'require(committedPublishingEnabled == "false")' \
    'root gate reads the COMMITTED publishing flag from the file and requires false'
contains build.gradle.kts 'if (publishingEnabled)' \
    'root publication configuration exists only behind the per-run enablement gate'
contains build.gradle.kts 'require(candidate.isAbsolute)' \
    'root gate requires an absolute staging path'
contains build.gradle.kts '!canonical.path.startsWith(repoRoot.path + File.separator)' \
    'root gate refuses a staging path inside the repository'
contains build.gradle.kts 'url = stagingUri' \
    'the only publication repository in the root is the gated staging directory'

ROOT_MAVEN_BLOCKS=$(grep -vE '^[[:space:]]*(//|#)' build.gradle.kts \
    | grep -cE 'maven[[:space:]]*\{' || true)
equals "$ROOT_MAVEN_BLOCKS" "1" \
    'the root declares exactly one maven repository block, the staging target'

# Remote addresses are judged notation-neutrally: every URL LITERAL in the root is
# extracted — whether it rides in `url = …`, `url.set(…)`, `setUrl(…)` or a plain
# string — and held to the exact allowlist of the POM's own metadata addresses. A
# repository URL can therefore never be remote in any spelling, and `setUrl` is
# additionally banned outright because a variable passed through it could point a
# repository anywhere without a literal appearing.
ROOT_URLS=$(grep -vE '^[[:space:]]*(//|#)' build.gradle.kts \
    | grep -oE 'https?://[^"[:space:]]*' | sort -u || true)
UNEXPECTED_ROOT_URLS=$(printf '%s\n' "$ROOT_URLS" | grep -v '^$' \
    | grep -vE '^https://(opensource\.org/license/mit/|github\.com/fxylabs/spfn-mobile(\.git)?|superfunction\.xyz)$' || true)
if [ -z "$UNEXPECTED_ROOT_URLS" ]
then
    pass 'every URL literal in the root build script is a pinned POM metadata address'
else
    fail "URL literals outside the POM metadata allowlist in build.gradle.kts: $(printf '%s' "$UNEXPECTED_ROOT_URLS" | tr '\n' ' ')"
fi

lacks_active build.gradle.kts 'setUrl' \
    'the root never uses setUrl; the staging repository is assigned once, visibly'

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

# ---------------------------------------------------------------------------
# External dependencies: only what the module graph declares.
# ---------------------------------------------------------------------------
# The rule here used to be "zero", and it was true until a provider adapter needed the
# provider's own SDK. Zero is not the property worth keeping — REVIEWED is. So the rule
# became an allowlist: tools/module-graph.json names, per module and per platform, what
# may be pulled in, and both build systems are held to it in both directions. An
# undeclared dependency fails, and a declared one no manifest uses fails too, because an
# allowance nobody exercises is an allowance nobody is watching.
#
# tools/validate/probe-social-adapter-rules.sh proves each refusal here bites, in both
# directions and in the notations that would otherwise slip past.
#
# The graph is read line by line, never with json_string: `externalDeps` holds one
# array per platform and a whole-file "first hit at any depth" read would return the
# first module's list for every module (P5). Each module object is one line by the
# graph's own canonical format, which is what makes a line-scoped read exact.
grep '"swiftTarget"' "$GRAPH" > "$TMP/graph-lines.txt" || true
GRAPH_MODULES=$(wc -l < "$TMP/graph-lines.txt" | tr -d ' ')

# Since schemaVersion 3 a module can have no Android half at all: `androidModule` is
# either a name or the literal null, and null is a DECLARATION, not an omission. Every
# Android-side check below and in sections 8 and 10 skips a null-declared module, so the
# skip is what has to be kept narrow — a reader that also skips a line it simply failed
# to parse reports a clean graph having read nothing (P7).
#
# The bucketing is the guard. Each module line lands in exactly one of two buckets, and
# the two must add up to the number of module lines; a line that lands in neither was
# not understood, and being not understood is a failure rather than a skip.
# The name must be non-empty: `"androidModule": ""` is a value nobody wrote on purpose,
# and bucketing it as Android-backed would turn a malformed line into a module whose
# directory is merely missing.
grep '"androidModule": "[^"]' "$TMP/graph-lines.txt" > "$TMP/graph-android.txt" || true
grep '"androidModule": null' "$TMP/graph-lines.txt" > "$TMP/graph-ios-only.txt" || true
GRAPH_ANDROID=$(wc -l < "$TMP/graph-android.txt" | tr -d ' ')
GRAPH_IOS_ONLY=$(wc -l < "$TMP/graph-ios-only.txt" | tr -d ' ')

if [ "$((GRAPH_ANDROID + GRAPH_IOS_ONLY))" = "$GRAPH_MODULES" ]
then
    pass "every one of the $GRAPH_MODULES graph modules declares its Android half or declares it absent ($GRAPH_ANDROID backed, $GRAPH_IOS_ONLY iOS-only)"
else
    fail "$((GRAPH_MODULES - GRAPH_ANDROID - GRAPH_IOS_ONLY)) module lines in $GRAPH declare neither an androidModule nor null; they were not read"
fi

# A graph nobody could read yields an empty allowlist, and an empty allowlist agrees
# with an empty manifest scan: two zeroes match, and the whole section reports green
# without having looked at anything. The floors make that impossible, and there are two
# of them because the two platforms no longer carry the same modules — one number
# covering both would pass with an entire platform at zero (P7).
if [ "$GRAPH_MODULES" -ge 4 ]
then
    pass "the external-dependency allowlist read $GRAPH_MODULES Swift modules from the graph"
else
    fail "the external-dependency allowlist read $GRAPH_MODULES Swift modules from $GRAPH; it could not run"
fi

if [ "$GRAPH_ANDROID" -ge 4 ]
then
    pass "the external-dependency allowlist read $GRAPH_ANDROID Android modules from the graph"
else
    fail "the external-dependency allowlist read $GRAPH_ANDROID Android modules from $GRAPH; it could not run"
fi

sed -n 's/.*"externalDeps": {"swift": \[\([^]]*\)\].*/\1/p' "$TMP/graph-lines.txt" \
    | tr ',' '\n' | tr -d '" ' | grep -v '^$' | sort -u > "$TMP/declared-swift.txt" || true
DECLARED_SWIFT=$(wc -l < "$TMP/declared-swift.txt" | tr -d ' ')

grep -E '^[[:space:]]*\.package\(' Package.swift > "$TMP/manifest-packages.txt" || true
MANIFEST_PACKAGES=$(wc -l < "$TMP/manifest-packages.txt" | tr -d ' ')
sed -E 's#.*\.package\(url:[[:space:]]*"[^"]*/([^/"]+)".*#\1#' "$TMP/manifest-packages.txt" \
    | sort -u > "$TMP/manifest-package-names.txt"

if [ "$MANIFEST_PACKAGES" = "$DECLARED_SWIFT" ]
then
    pass "Package.swift declares $MANIFEST_PACKAGES external packages, the number the module graph allows"
else
    fail "Package.swift declares $MANIFEST_PACKAGES external packages; the module graph allows $DECLARED_SWIFT"
fi

UNDECLARED_SWIFT=$(comm -23 "$TMP/manifest-package-names.txt" "$TMP/declared-swift.txt" || true)
if [ -z "$UNDECLARED_SWIFT" ]
then
    pass 'every external package in Package.swift is declared in the module graph'
else
    fail "Package.swift depends on packages the module graph does not declare: $(printf '%s' "$UNDECLARED_SWIFT" | tr '\n' ' ')"
fi

UNUSED_SWIFT=$(comm -13 "$TMP/manifest-package-names.txt" "$TMP/declared-swift.txt" || true)
if [ -z "$UNUSED_SWIFT" ]
then
    pass 'every Swift package the module graph allows is actually declared'
else
    fail "the module graph allows Swift packages nothing depends on: $(printf '%s' "$UNUSED_SWIFT" | tr '\n' ' ')"
fi

# An external package may only be reached through the trait its module declares, so a
# trait-off consumer resolves nothing. The condition is what makes that true; the
# declaration alone would still put the package in every consumer's resolution.
for trait in $(sed -n 's/.*"swiftTrait": "\([^"]*\)".*/\1/p' "$TMP/graph-lines.txt")
do
    contains Package.swift ".trait(name: \"$trait\"" "Package.swift declares the trait $trait"
done
contains Package.swift '.default(enabledTraits: [])' \
    'no trait is enabled by default, so a consumer resolves an adapter SDK only on request'

# Android: the same allowlist, per module. Every dependency line in an SDK module must
# be either a project edge or a catalogue alias the graph names for that module, and a
# line in any other shape fails outright — a coordinate string is exactly how an
# unreviewed artifact enters a build, and no name extraction would recognise it.
#
# The loop runs over the ANDROID-BACKED bucket, so a module that declares no Android
# half is out of scope by declaration rather than by accident. Inside the bucket
# nothing is skipped: a build script that is missing is a problem, not a reason to move
# on, because "the file was not there" and "the file was clean" must never share an
# outcome.
ANDROID_SCANNED=0
ANDROID_PROBLEMS=''
while IFS= read -r graph_line
do
    android_module=$(printf '%s' "$graph_line" | sed -n 's/.*"androidModule": "\([^"]*\)".*/\1/p')
    script="android/$android_module/build.gradle.kts"
    ANDROID_SCANNED=$((ANDROID_SCANNED + 1))
    if [ ! -f "$script" ]
    then
        ANDROID_PROBLEMS="$ANDROID_PROBLEMS $android_module:no-build-script"
        continue
    fi

    printf '%s' "$graph_line" \
        | sed -n 's/.*"externalDeps": {[^}]*"android": \[\([^]]*\)\].*/\1/p' \
        | tr ',' '\n' | tr -d '" ' | grep -v '^$' | sort -u > "$TMP/declared-android.txt" || true

    # Every OCCURRENCE is judged, not every line: a dependency can share its line with
    # the block that opens it, and a line-anchored read walks straight past that one
    # (P13, the notation-bypass class). Comment lines are dropped first, and the
    # capitalised `testImplementation` / `androidTestImplementation` spellings fall
    # outside the alternation on purpose — test configurations describe how this
    # repository is checked, not what a consumer links.
    grep -vE '^[[:space:]]*(//|#)' "$script" \
        | grep -oE '(^|[^A-Za-z0-9_.])(api|implementation|compileOnly|runtimeOnly)\([^,)]*' \
        | sed -E 's/.*(api|implementation|compileOnly|runtimeOnly)\(//' \
        > "$TMP/module-deps.txt" || true

    UNKNOWN_SHAPE=$(grep -vE '^(libs\.[A-Za-z0-9.]+|project\(":[a-z-]+"|$)' "$TMP/module-deps.txt" || true)
    if [ -n "$UNKNOWN_SHAPE" ]
    then
        ANDROID_PROBLEMS="$ANDROID_PROBLEMS $android_module:unrecognised-dependency-form"
    fi

    grep -E '^libs\.' "$TMP/module-deps.txt" \
        | sed 's/^libs\.//' | tr '.' '-' | sort -u > "$TMP/module-external.txt" || true

    UNDECLARED=$(comm -23 "$TMP/module-external.txt" "$TMP/declared-android.txt" || true)
    UNUSED=$(comm -13 "$TMP/module-external.txt" "$TMP/declared-android.txt" || true)
    if [ -n "$UNDECLARED" ]
    then
        ANDROID_PROBLEMS="$ANDROID_PROBLEMS $android_module:undeclared($(printf '%s' "$UNDECLARED" | tr '\n' ','))"
    fi
    if [ -n "$UNUSED" ]
    then
        ANDROID_PROBLEMS="$ANDROID_PROBLEMS $android_module:declared-but-unused($(printf '%s' "$UNUSED" | tr '\n' ','))"
    fi
done < "$TMP/graph-android.txt"

if [ "$ANDROID_SCANNED" = "$GRAPH_ANDROID" ]
then
    pass "the external-dependency allowlist was applied to all $ANDROID_SCANNED Android-backed modules"
else
    fail "the external-dependency allowlist reached $ANDROID_SCANNED of $GRAPH_ANDROID Android-backed modules"
fi

if [ -z "$ANDROID_PROBLEMS" ]
then
    pass 'every Android module depends on exactly the external artifacts the module graph declares'
else
    fail "Android external dependencies disagree with the module graph:$ANDROID_PROBLEMS"
fi

TRUNK=$(grep -rIl 'pod trunk push' . --exclude-dir=.git --exclude-dir=.build --exclude-dir=.gradle --exclude=validate.sh 2>/dev/null || true)
if [ -z "$TRUNK" ]
then
    pass 'no CocoaPods trunk publication command anywhere'
else
    fail "CocoaPods trunk publication command present in: $TRUNK"
fi

# One workflow — and only one — may speak publication: publish-central.yml, the manual
# Central path the publication transition opened. Every other workflow keeps the full
# Step 2 rule. What publish-central.yml itself may do is pinned right after the loop:
# named secrets only, one remote endpoint only, held-for-confirmation upload only.
#
# Manual-only is an ALLOW-list over the parsed `on:` trigger set, not a deny-list of
# trigger names: a deny-list misses the flow-style forms (`on: [push, ...]`,
# `on: push`, `on: {push: …}`) and every trigger nobody thought to name —
# workflow_run, repository_dispatch, merge_group, a future one. Here the trigger set
# of every workflow is extracted, whichever YAML style declares it, and anything that
# is not exactly `workflow_dispatch` fails, as does a workflow declaring no trigger.
PUBLISH_WORKFLOW=.github/workflows/publish-central.yml
for workflow in .github/workflows/*.yml
do
    [ -f "$workflow" ] || continue

    # Actions allow-list. publish-central.yml may use exactly one action — the
    # SHA-pinned log-artifact uploader that carries failure evidence out of the
    # runner — and a tag- or branch-pinned form of even that one fails, because a
    # movable ref is how an unreviewed action enters a workflow. Every other
    # workflow still uses none.
    if [ "$workflow" = "$PUBLISH_WORKFLOW" ]
    then
        # Non-anchored on purpose: `uses:` can open a step (`- uses:`) or ride in a
        # flow-style map, and an anchored extraction reads right past both. Only
        # full-line comments are exempt; the admitted shape allows the list-item
        # dash and nothing else.
        UNEXPECTED_USES=$(grep -E 'uses:' "$workflow" \
            | grep -vE '^[[:space:]]*#' \
            | grep -vE '^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*actions/upload-artifact@[0-9a-f]{40}([[:space:]]+#.*)?$' || true)
        if [ -z "$UNEXPECTED_USES" ]
        then
            pass "$workflow uses only the commit-SHA-pinned upload-artifact action"
        else
            fail "$workflow uses an action outside the SHA-pinned allowlist: $UNEXPECTED_USES"
        fi
    else
        lacks_active "$workflow" 'uses:' "$workflow uses no third-party action, so there is nothing to pin"
    fi

    TRIGGERS=$(awk '
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
        # Fail closed: a line at trigger depth the parser cannot read as a plain key
        # (a quoted key, an anchor, anything unforeseen) is reported as a sentinel and
        # refused below. A parser that skips what it does not understand would admit
        # exactly the trigger it could not see.
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
    ' "$workflow")
    UNEXPECTED_TRIGGERS=$(printf '%s\n' "$TRIGGERS" | grep -v '^workflow_dispatch$' | grep -v '^$' || true)
    if printf '%s\n' "$TRIGGERS" | grep -q '^SPFN_UNPARSEABLE_TRIGGER$'
    then
        fail "$workflow has a trigger line the parser cannot read; an unparseable trigger is refused"
    elif [ -z "$TRIGGERS" ]
    then
        fail "$workflow declares no trigger at all; a workflow must be explicitly manual"
    elif [ -z "$UNEXPECTED_TRIGGERS" ]
    then
        pass "$workflow triggers on workflow_dispatch and nothing else"
    else
        fail "$workflow declares triggers beyond workflow_dispatch: $(printf '%s' "$UNEXPECTED_TRIGGERS" | tr '\n' ' ')"
    fi

    contains "$workflow" 'NOT A GATE' "$workflow states that it is not a gate"
    contains "$workflow" 'workflow_dispatch' "$workflow is manual-only"

    if [ "$workflow" = "$PUBLISH_WORKFLOW" ]
    then
        continue
    fi

    lacks_active "$workflow" '(secrets\.|publish|deploy|upload-artifact|trunk|registry)' \
        "$workflow requests no secret and performs no publication"
done

# The publish workflow's own boundary. Secrets by NAME only, from a fixed allowlist —
# a new secret name is a new decision, not an edit. The only remote hosts it may
# address are the Central Portal and github.com (its own clone), and the upload must
# be held for a person (USER_MANAGED), never auto-released.
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

contains "$PUBLISH_WORKFLOW" 'central.sonatype.com/api/v1/publisher/upload' \
    'the Central upload goes through the Portal publisher API'
contains "$PUBLISH_WORKFLOW" 'publishingType=USER_MANAGED' \
    'the uploaded deployment is held for explicit human confirmation'
contains "$PUBLISH_WORKFLOW" 'useInMemoryPgpKeys' \
    'publish-central.yml documents the in-memory key custody (no key file on disk)'

# The host allowlist above only sees URL literals, and a network command needs no
# scheme — `curl evil.example` walks straight past it. So every line that invokes a
# network-capable command must itself name an allowlisted host, and pushing anything
# back from a publish run is banned outright.
# Backslash continuations are joined first, so a command split across lines is judged
# as the one command it is.
NETWORK_LINES=$(awk '
    /\\[[:space:]]*$/ { sub(/\\[[:space:]]*$/, "", $0); buf = buf $0 " "; next }
    { print buf $0; buf = "" }
' "$PUBLISH_WORKFLOW" \
    | grep -vE '^[[:space:]]*#' \
    | grep -E '(curl|wget|git clone|git fetch|git pull|ssh |scp |nc )' || true)
UNPINNED_NETWORK=$(printf '%s\n' "$NETWORK_LINES" | grep -v '^$' \
    | grep -vE '(central\.sonatype\.com|github\.com)' || true)
if [ -z "$UNPINNED_NETWORK" ]
then
    pass 'every network command in publish-central.yml names an allowlisted host'
else
    fail "network commands without an allowlisted host in publish-central.yml: $UNPINNED_NETWORK"
fi

lacks_active "$PUBLISH_WORKFLOW" 'git (push|remote)' \
    'a publish run never pushes or rewires a remote'

# Expression injection: a workflow input interpolated into run text executes as
# script. Inputs may reach the shell ONLY as an env assignment, and the commit input
# must be machine-validated as exactly 40 hex characters before any use. The net is
# any mention of inputs inside an expression — `inputs.x`, the legacy
# `github.event.inputs.x`, the bracket form, or an indirection through format() —
# and the one admitted shape is a plain `NAME: ${{ inputs.x }}` env assignment.
RAW_INPUT_USES=$(grep -nE '\$\{\{.*inputs' "$PUBLISH_WORKFLOW" \
    | grep -vE '^[0-9]+:[[:space:]]*[A-Z_][A-Z_0-9]*:[[:space:]]*\$\{\{[[:space:]]*inputs\.[A-Za-z_]+[[:space:]]*\}\}[[:space:]]*$' || true)
if [ -z "$RAW_INPUT_USES" ]
then
    pass 'workflow inputs reach the shell only through env assignments'
else
    fail "workflow inputs interpolated outside an env assignment: $RAW_INPUT_USES"
fi

contains "$PUBLISH_WORKFLOW" '*[!0-9a-f]*' \
    'the commit input is refused unless it is lowercase hex'
contains "$PUBLISH_WORKFLOW" '-ne 40' \
    'the commit input is refused unless it is exactly 40 characters'
contains "$PUBLISH_WORKFLOW" 'if: failure()' \
    'log surfacing and the log artifact exist only on the failure path'
contains "$PUBLISH_WORKFLOW" 'rc-out/logs' \
    'observability is confined to the rc-out/logs directory'

# ---------------------------------------------------------------------------
section '8. module graph coherence'
# ---------------------------------------------------------------------------
grep '"swiftTarget"' "$GRAPH" > "$TMP/modules.txt"
MODULE_COUNT=$(wc -l < "$TMP/modules.txt" | tr -d ' ')
MODULES_WITH_ANDROID=0
MODULES_IOS_ONLY=0
MODULES_UNREADABLE=0

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
        # A module with no graph edges used to be held to the literal
        # `.target(name: "X")`, and that was the whole check. It may now carry an
        # external product — SPFNCore hashes with swift-crypto where there is no
        # CryptoKit — so two shapes are admitted: the bare target, and a dependency
        # list holding nothing but products the graph allows THIS module, each behind
        # `.when(platforms: [.linux])`.
        #
        # Section 7 holds the package NAME to `externalDeps`; what is held here is that
        # nothing else rode in on the relaxation. An unconditional product is not
        # erased and fails, and so does a product allowed to some other module — which
        # is the refusal tools/validate/probe-social-adapter-rules.sh exercises.
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
        # The graph's edges are the LEADING dependencies of the target, in order, and
        # the closing bracket is deliberately not part of the match: a target may carry
        # a trait-gated external product after them, which section 7 holds to
        # `externalDeps` instead.
        rendered=$(printf '%s' "$swift_deps" | sed 's/, /, /g')
        contains Package.swift ".target(name: \"$swift_target\", dependencies: [$rendered" \
            "Package.swift target $swift_target dependency edge matches the graph"
    fi

    # The Android half is checked when the graph names one. A module that declares
    # `androidModule: null` has none by decision, and the three states are kept apart
    # here: a name is checked, a null is counted as iOS-only, and a line that is neither
    # is a line this loop did not understand — which fails rather than passing quietly.
    if [ -n "$android_module" ]
    then
        MODULES_WITH_ANDROID=$((MODULES_WITH_ANDROID + 1))

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
    elif printf '%s' "$line" | grep -q '"androidModule": null'
    then
        MODULES_IOS_ONLY=$((MODULES_IOS_ONLY + 1))

        # A declared-absent Android half must really be absent, or the train would ship
        # an Android artifact nothing in the graph describes — the same drift the graph
        # exists to prevent, pointing the other way. The link is looked up rather than
        # derived from the target name: every Android module names its Swift counterpart
        # in its own build script, so the question "does an Android module for this
        # target exist" has an exact answer that no naming convention has to supply.
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

    contains "$PODSPEC" "s.subspec '$swift_target'" "CocoaPods fixture has subspec $swift_target"
    printf '%s\n' "$swift_deps" | tr ',' '\n' | sed 's/[" ]//g' > "$TMP/deps.txt"
    while IFS= read -r dep
    do
        [ -n "$dep" ] || continue
        contains "$PODSPEC" "sp.dependency 'SPFNMobileCompatFixture/$dep'" \
            "CocoaPods fixture edge $swift_target -> $dep"
    done < "$TMP/deps.txt"
done < "$TMP/modules.txt"

# What the loop above did with each line, counted independently of what it checked. The
# two buckets have to account for every module: a line the loop skipped without
# understanding would otherwise leave no trace at all.
if [ "$((MODULES_WITH_ANDROID + MODULES_IOS_ONLY))" = "$MODULE_COUNT" ] && [ "$MODULES_UNREADABLE" = "0" ]
then
    pass "the coherence loop read all $MODULE_COUNT modules ($MODULES_WITH_ANDROID Android-backed, $MODULES_IOS_ONLY iOS-only)"
else
    fail "the coherence loop read $MODULES_WITH_ANDROID + $MODULES_IOS_ONLY of $MODULE_COUNT modules and could not read $MODULES_UNREADABLE"
fi

# ---------------------------------------------------------------------------
# The Linux half of the graph (schemaVersion 4).
# ---------------------------------------------------------------------------
# `linux` is ABSENT on a module that builds on Linux and the literal false on a module
# that has no Linux half at all. The three-state rule `androidModule` follows applies
# here for the same reason: an absent key, a declared false and a value nobody could
# read are three different events, and a reader that folds the third into either of the
# first two reports a clean graph having read nothing (P7). So every module line is
# bucketed and the two buckets must add up; a line carrying `"linux":` followed by
# anything but false lands in neither and fails as unread.
#
# SwiftPM cannot condition a TARGET on a platform, so `linux: false` is not something
# Package.swift can state. The mechanism is in the sources: every file of such a module
# is guarded whole — first line of code to last — so the target compiles to an empty
# module. That is what is checked below, file by file, and so is the other direction:
# a module WITHOUT the key may not reach an Apple-only framework outside a guard.
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

# A module that declares no Linux half must really have none, and the only thing that
# makes that true is the guard on each of its files. Both ends are checked: the first
# line of code is `#if canImport(...)`, and the last is its `#endif`. Checking only the
# first would admit a guard closed early, which leaves whatever trails it — a final
# extension, a helper type — compiling on Linux inside a module that claims to be empty.
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
            FIRST_CODE=$(head -1 "$TMP/guard-body.txt")
            LAST_CODE=$(tail -1 "$TMP/guard-body.txt")
            case $FIRST_CODE in
                '#if canImport('*) ;;
                *) GUARD_PROBLEMS="$GUARD_PROBLEMS $source:opens-with-unguarded-code" ;;
            esac
            if [ "$LAST_CODE" != '#endif' ]
            then
                GUARD_PROBLEMS="$GUARD_PROBLEMS $source:guard-closes-before-the-end"
            fi
        done < "$TMP/guard-files.txt"
    done
done < "$TMP/linux-absent.txt"

# The floor. A loop that visited nothing reports the same empty problem list as a loop
# that found everything guarded, and the two must not share an outcome.
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

# The other direction. Every Swift file that is NOT part of a declared-absent module has
# to compile on Linux, so an Apple-only framework may only be reached from inside a
# `#if canImport(` guard. The file list is the whole tree minus those modules, rather
# than the module directories the graph names, so the test targets that belong to no
# module — conformance, repository, integration, verify — are covered too.
find Sources Tests -name '*.swift' | sort > "$TMP/all-swift.txt"
cp "$TMP/all-swift.txt" "$TMP/linux-swift-files.txt"
while IFS= read -r line
do
    absent_target=$(printf '%s' "$line" | sed -n 's/.*"swiftTarget": "\([^"]*\)".*/\1/p')
    grep -v "^Sources/$absent_target/" "$TMP/linux-swift-files.txt" \
        | grep -v "^Tests/${absent_target}Tests/" > "$TMP/linux-swift-kept.txt" || true
    mv "$TMP/linux-swift-kept.txt" "$TMP/linux-swift-files.txt"
done < "$TMP/linux-absent.txt"

APPLE_ONLY_FRAMEWORKS='AuthenticationServices|UIKit|AppKit|LocalAuthentication|Security'
APPLE_IMPORT_SCANNED=0
APPLE_IMPORT_HITS=''
while IFS= read -r source
do
    APPLE_IMPORT_SCANNED=$((APPLE_IMPORT_SCANNED + 1))
    APPLE_IMPORT_HITS="$APPLE_IMPORT_HITS$(unguarded_imports "$source" "$APPLE_ONLY_FRAMEWORKS")"
done < "$TMP/linux-swift-files.txt"

if [ "$APPLE_IMPORT_SCANNED" -ge 20 ]
then
    pass "the Apple-framework import scan read $APPLE_IMPORT_SCANNED sources outside the modules that declare no Linux half"
else
    fail "the Apple-framework import scan read only $APPLE_IMPORT_SCANNED sources; it did not run"
fi

if [ -z "$APPLE_IMPORT_HITS" ]
then
    pass 'no module that builds on Linux imports an Apple-only framework outside a canImport guard'
else
    fail "an Apple-only framework is imported unconditionally in a module that builds on Linux: $APPLE_IMPORT_HITS"
fi

# CryptoKit is the one framework the rule covers everywhere, including inside the
# modules that declare no Linux half. It is the SDK's cryptography, swift-crypto is the
# same API where CryptoKit is absent, and the swap is an import: a file that imports
# CryptoKit unconditionally is a file that has no Linux half and did not say so.
CRYPTO_IMPORT_SCANNED=0
CRYPTO_IMPORT_HITS=''
while IFS= read -r source
do
    CRYPTO_IMPORT_SCANNED=$((CRYPTO_IMPORT_SCANNED + 1))
    CRYPTO_IMPORT_HITS="$CRYPTO_IMPORT_HITS$(unguarded_imports "$source" 'CryptoKit')"
done < "$TMP/all-swift.txt"

if [ "$CRYPTO_IMPORT_SCANNED" -ge 20 ]
then
    pass "the CryptoKit import scan read all $CRYPTO_IMPORT_SCANNED Swift sources"
else
    fail "the CryptoKit import scan read only $CRYPTO_IMPORT_SCANNED sources; it did not run"
fi

if [ -z "$CRYPTO_IMPORT_HITS" ]
then
    pass 'every CryptoKit import is behind a canImport guard, so swift-crypto can stand in where CryptoKit is absent'
else
    fail "CryptoKit is imported outside a canImport guard: $CRYPTO_IMPORT_HITS"
fi

# The two platforms no longer carry the same modules, so they are counted against
# different numbers. One number covering both would have let an entire Android tree
# disappear while the Swift side kept the count right.
SOURCE_DIRS=$(find Sources -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
ANDROID_DIRS=$(find android -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')
SUBSPECS=$(grep -c 's.subspec ' "$PODSPEC" || printf '0')

for actual_pair in "Sources:$SOURCE_DIRS:$MODULE_COUNT" "android:$ANDROID_DIRS:$MODULES_WITH_ANDROID" "podspec subspecs:$SUBSPECS:$MODULE_COUNT"
do
    label=${actual_pair%%:*}
    rest=${actual_pair#*:}
    actual=${rest%:*}
    expected=${rest#*:}
    if [ "$actual" = "$expected" ]
    then
        pass "$label count is $expected, matching module-graph.json"
    else
        fail "$label count is $actual but module-graph.json declares $expected (undeclared module?)"
    fi
done

# A Gradle include outlives the directory it points at without anything above noticing:
# the per-module checks only look at the modules the graph names, and the directory
# count only sees directories. Counting the project mappings is what closes that gap.
SETTINGS_PROJECTS=$(grep -cE '^project\(":[a-z-]+"\)\.projectDir = file\("android/' settings.gradle.kts || printf '0')
if [ "$SETTINGS_PROJECTS" = "$MODULES_WITH_ANDROID" ]
then
    pass "settings.gradle.kts maps $SETTINGS_PROJECTS Android projects, one per Android-backed module"
else
    fail "settings.gradle.kts maps $SETTINGS_PROJECTS Android projects but the graph declares $MODULES_WITH_ANDROID"
fi

# A module exists here only once it carries an implementation. The persistence/sync and
# hybrid modules were declared in the Step 1 scaffold from the approved layout, never
# implemented, and published as empty coordinates through 0.1.0-alpha.3 — a reservation
# that a consumer reads as a promise. Stub vocabulary in SDK sources is how that starts,
# so it is refused outright: a module is added with behaviour or not at all. Examples,
# tools and documentation are excluded, since a placeholder example is honest about
# being one.
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

# And no module is built on an API its own vendor has already retired. Suppressing a
# deprecation warning in new SDK code buys nothing: the migration still has to happen,
# only later, on someone else's schedule, with consumers already on the old surface. The
# Google adapter was written this way once — on the deprecated one-tap sign-in API,
# behind two `@Suppress("DEPRECATION")` — and moving it to Credential Manager cost a day
# it would not have cost if the suppression had never been available.
#
# The refusal is the whole point: a suppression is exactly what makes this invisible in
# a build log, so the build log is not where it can be caught. Deprecating something of
# our own is a different act and stays legal — this refuses SILENCING a vendor's notice.
# Test sources are included: a suppression there is a suppression.
DEPRECATION_SCANNED=$(find Sources android/*/src -type f \( -name '*.swift' -o -name '*.kt' \) 2>/dev/null | wc -l | tr -d ' ')
DEPRECATION_HITS=$(grep -rIn --exclude-dir=build '@Suppress' Sources android/*/src 2>/dev/null \
    | grep -i 'DEPRECAT' || true)

if [ "$DEPRECATION_SCANNED" -ge 20 ]
then
    pass "the deprecation-suppression scan read $DEPRECATION_SCANNED SDK sources"
else
    fail "the deprecation-suppression scan read only $DEPRECATION_SCANNED SDK sources; it did not run"
fi

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
contains Package.swift 'swift-tools-version: 6.1' 'Package.swift pins swift-tools-version 6.1'
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

# The real-server runner's whole value is what it declines to run. Those refusals fire
# only when a setup is already wrong, which is exactly when nobody is watching them, so
# they are exercised here rather than left to the day they are needed. The probe builds
# its own fixture apps and needs no verify app of its own.
if sh tools/verify-server/probe-refusals.sh > /dev/null 2>&1
then
    pass 'the verify-server runner refuses every setup it claims to, and passes a correct one'
else
    fail 'tools/verify-server/probe-refusals.sh fails; the real-server runner no longer fails closed'
fi

# The device receipts are the only evidence in this repository that a person ever held a
# phone, and the gate over them is the only thing standing between "no device run
# happened" and a published candidate. Both are checked here rather than only inside
# rc-verify, because rc-verify needs a Swift toolchain, an Android SDK and a clean tree,
# and a check that only runs on a release day is a check nobody is watching.
if sh tools/device-receipts/probe-receipt-gate.sh > "$TMP/receipt-probe.txt" 2>&1
then
    pass 'the device receipt gate refuses every way its evidence can be missing, unreadable or wrong'
else
    fail 'tools/device-receipts/probe-receipt-gate.sh fails; the device receipt gate no longer fails closed'
    sed 's/^/          /' "$TMP/receipt-probe.txt"
fi

# And the committed evidence must actually clear it. The probe proves the gate bites;
# this proves the repository is on the passing side of it, which is what a reader of
# COMPATIBILITY.md is being asked to believe.
if sh tools/device-receipts/receipt-gate.sh > "$TMP/receipt-gate.txt" 2>&1
then
    RECEIPT_LINE=$(grep '^RECEIPT-GATE-SUMMARY ' "$TMP/receipt-gate.txt" || true)
    if [ -z "$RECEIPT_LINE" ]
    then
        fail 'the device receipt gate passed without reporting what it counted'
    else
        pass "the committed device receipts clear the gate ($RECEIPT_LINE)"
    fi
else
    fail 'the committed device receipts do not clear tools/device-receipts/receipt-gate.sh'
    sed 's/^/          /' "$TMP/receipt-gate.txt"
fi

# The gate only guards publication if the publication path actually calls it, and only
# guards it honestly if a shell variable cannot redirect it elsewhere. Both are pinned
# as fixed strings: an edit that removes the refusal removes the string.
contains tools/rc-verify/rc-verify.sh 'sh tools/device-receipts/receipt-gate.sh' \
    'rc-verify runs the device receipt gate before it will verify a candidate'
contains tools/rc-verify/rc-verify.sh 'unset SPFN_RECEIPT_ROOT SPFN_RECEIPT_LOCK' \
    'rc-verify clears the gate overrides, so no environment variable can point it at hand-written evidence'

# A build/parity baseline is not a support commitment, and neither is a proven sign-in
# path. The iOS and Android rows name whole-platform gates — lifecycle cells and release
# evidence — that the 2026-09-01 device run did not meet, so they must not quietly
# acquire a support claim from evidence that is narrower than they are.
if grep -E '^\| (iOS|Android) \|' COMPATIBILITY.md | grep -q 'UNRESOLVED'
then
    pass 'iOS and Android support rows stay UNRESOLVED pending device evidence'
else
    fail 'an iOS or Android support row claims support without real-device evidence'
fi

# The one row that DOES claim something must point at the evidence and at the gate that
# judges it, so the claim and its proof cannot drift apart silently.
contains COMPATIBILITY.md 'tools/device-receipts/receipt-gate.sh' \
    'the device sign-in row names the gate that enforces it'
contains COMPATIBILITY.md 'tools/device-receipts/runs/2026-09-01/' \
    'the device sign-in row names the receipts it rests on'

# ---------------------------------------------------------------------------
section '12. repository status is stated, not implied'
# ---------------------------------------------------------------------------
for doc in README.md docs/SCAFFOLD-STATUS.md CONTRIBUTING.md RELEASE.md SECURITY.md
do
    contains_i "$doc" 'scaffold' "$doc states this is still a scaffold"
done

contains README.md 'no public support' 'README refuses to promise public support'
# Releases exist now, so the doc must name the current train rather than deny every
# release: the literal this replaced ("No release has been made") stayed pinned after
# 0.1.0-alpha.2 shipped, which made the check enforce a false sentence. Reading the
# version from VERSION is what keeps it honest — the next version bump fails here until
# RELEASE.md records what happened to it.
contains RELEASE.md "$(tr -d '[:space:]' < VERSION)" 'RELEASE.md names the current version'
contains RELEASE.md 'UNRESOLVED' 'RELEASE.md still claims no device support'
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
