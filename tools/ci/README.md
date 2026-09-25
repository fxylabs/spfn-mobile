# The CI referee

GitHub Actions cannot be run on a developer machine, so nothing that matters is written in
a workflow file. **Every gate is a script here, and a workflow only calls one.** That is
what lets the same command be run on a laptop, on the development VM and on a runner and
mean the same thing — and it is why a change to a gate is a change to a script in this
directory, never an edit to `.github/workflows/*.yml`.

The three required checks are `swift`, `android` and `contract`. Everything they run is
below.

## The scripts

**`validate.sh`** runs `tools/validate/validate.sh`, and the validator's exit code is the
gate's. No failure is admitted. There used to be an allowlist of rows red on purpose — the
device-receipt gate — and a judge over the output; the receipt gate is now a manual
pre-release command (`COMPATIBILITY.md`, "Device sign-in evidence"), so the allowlist and the
judge are gone with it.

**`android.sh`** runs unit tests across every Android module, lint across the SDK modules
under `android/`, lint on the two applications restricted to the checks `tools/ui-lint`
registers, the four build tools' JVM suites (`:ui-lint` among them), and the codegen
determinism tasks
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

## Pinning

`swift-toolchain.lock` names the Swift tarball's URL and SHA-256, with the provenance of
that digest in its own comments: swift.org publishes a detached PGP signature rather than a
checksum, so the digest was taken from a download whose signature was verified first.

`actions-allowlist.txt` names every action any workflow may use, each pinned to a full
commit SHA (D14). Section 24 of `tools/validate/validate.sh` reads every `uses:` line under
`.github/workflows` and fails on anything that is not one of those lines, so a tag ref fails
even for an action already on the list.
