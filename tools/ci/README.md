# The CI referee

GitHub Actions cannot be run on a developer machine, so nothing that matters is written in
a workflow file. **Every gate is a script here, and a workflow only calls one.** That is
what lets the same command be run on a laptop, on the development VM and on a runner and
mean the same thing — and it is why a change to a gate is a change to a script in this
directory, never an edit to `.github/workflows/*.yml`.

The three required checks are `swift`, `android` and `contract`. Everything they run is
below.

## The scripts

**`validate.sh`** runs `tools/validate/validate.sh`, prints its whole output and its exit
code, and then decides its own exit code from the output. The validator exits non-zero
whenever it counts any failure, and this repository has failures it keeps on purpose, so
obeying that exit code would make the check red forever and ignoring it with `|| true`
would make it green forever. Neither happens: the exit code is reported, and the judgement
is the allowlist rule below.

**`android.sh`** runs unit tests across every Android module, lint across the SDK modules
under `android/`, the three build tools' JVM suites, and the codegen determinism tasks
`:contract-codegen:spfnCodegenVerify`, `:ui-codegen:spfnUiVerify` and
`:ui-codegen:spfnHarnessUiVerify`. The lint task list is read from the directories under
`android/` — the same rule the root build script uses to pick its SDK modules — so a module
added there is linted without editing this script. It refuses to run with an empty
`ANDROID_HOME`.

**`swift.sh`** runs `swift build --build-tests` then `swift test --skip-build` over the
Linux module set. A Linux run reports skips: the two provider-adapter modules declare no
Linux half at all, and a few rows a Linux platform cannot host skip themselves.

**`install-swift.sh`** installs the apt packages swiftlang's own Ubuntu 24.04 image
installs, then downloads the tarball named in `swift-toolchain.lock`, checks it against the
digest there, unpacks it into `$HOME/swift` and appends the toolchain to `GITHUB_PATH`. No
runner cache: `actions/cache` is an action, and D14 admits two actions, neither of them
that one.

**`install-android-sdk.sh`** installs `platforms;android-36` and `build-tools;36.0.0` under
`ANDROID_HOME` with `sdkmanager`, accepting licences first (D20). It uses the runner's
`sdkmanager` when there is one and otherwise fetches the command-line tools zip pinned by
SHA-256 in the script. It refuses to run with an empty `ANDROID_HOME`, and it verifies the
two component directories exist afterwards rather than trusting the exit code.

The two installers are the only scripts here that are never run on the development VM: the
toolchains are already installed there, and a gigabyte of download proving a download works
is not evidence. They are held to `sh -n` and to their pinned digests instead.

## The allowlist rule

`validate-known-red.txt` holds the validator failures CI admits, one per line, each the
exact text a failed row prints after its `  FAIL  ` prefix.

- A judged row is a line beginning with exactly `  FAIL  `. That is what the validator's
  `fail()` prints and nothing else in the output has that shape.
- A **deeper-indented** FAIL line is the captured output of a sub-tool one of those rows
  ran — the receipt gate prints its own fifteen-cell table, which the validator re-indents
  underneath the row. It belongs to the row above it and is admitted with it.
- A judged row that is not in the file fails the check.
- A line in the file that is not a judged row **also** fails the check. A known failure
  that stopped failing means the file has outlived its reason, and a stale allowlist is how
  a real failure gets admitted six months later. Emptying the file is the fix.
- The validator's own `N checks, M failures` count must match the number of rows that were
  read. A mismatch means a failure was reported in a shape this script cannot judge, and
  judging the rest would be a false green.

Today the file holds two lines, both the device-receipt gate, red on purpose since the
2026-09-02 re-pin to contract `0.10.0`. Clearing them needs fifteen cells driven by a
person on two real phones — the kind of evidence D2 leaves outside CI.

## Pinning

`swift-toolchain.lock` names the Swift tarball's URL and SHA-256, with the provenance of
that digest in its own comments: swift.org publishes a detached PGP signature rather than a
checksum, so the digest was taken from a download whose signature was verified first.

`actions-allowlist.txt` names every action any workflow may use, each pinned to a full
commit SHA (D14). Section 24 of `tools/validate/validate.sh` reads every `uses:` line under
`.github/workflows` and fails on anything that is not one of those lines, so a tag ref fails
even for an action already on the list.
