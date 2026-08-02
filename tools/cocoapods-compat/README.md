# CocoaPods compatibility fixture (internal, unpublished)

**Swift Package Manager is the primary and only iOS distribution channel for SPFN
Mobile. CocoaPods is not supported.** Nothing in this directory is published, and the
existence of a podspec here is not a commitment to publish one.

## Why it exists at all

The approved topology keeps *one* Swift source tree and *one* module graph. This
directory proves that claim mechanically: `generate-podspec.sh` derives a podspec
from `tools/module-graph.json` and `VERSION`, and every subspec points at the same
`Sources/<Target>/` directory the SwiftPM manifest uses. There is no second
implementation, no forked source, and no CocoaPods-only behaviour — and the validator
fails the build if anyone tries to introduce one by hand-editing the generated file.

## Status of the underlying policy

**RESOLVED 2026-08-02** (decision `01kz0r31ya`, recorded as D11 in
`docs/OPEN-DECISIONS.md`): Swift Package Manager is the only iOS distribution channel
for SPFN Mobile v1 and CocoaPods is not supported. This directory exists to prove the
single-source claim above; it is not preparation for a publication path. Upstream
CocoaPods trunk is in maintenance mode with a stated read-only target date, so trunk
could not be a long-term channel in any case.

No activation condition is written down here, and that omission is deliberate. A
condition on the page would read as a route anyone could ask for, which is the opposite
of what "not supported" means. If a real requirement ever appears, it is judged as a
separate decision at that time.

## Files

| File | Role |
| --- | --- |
| `generate-podspec.sh` | generator; the only way the fixture may change |
| `generated/SPFNMobileCompatFixture.podspec` | generated output, never hand-edited |

## Validation

`pod ipc spec` parses the podspec **offline** and is what `tools/validate/validate.sh`
relies on. Full `pod lib lint` is deliberately *not* run: it needs network access to
the specs CDN and an `xcodebuild` run against a deployment target that has not been
approved. Claiming a lint pass we did not run would be exactly the kind of fake
evidence Step 1 forbids.

## Guardrails enforced by the validator

- No `.podspec` at the repository root, so the repo is not discoverable as a pod.
- The fixture is byte-identical to fresh generator output.
- The fixture version equals `VERSION`.
- No CocoaPods trunk publication command anywhere in the repository.
- Subspecs and their dependency edges match `tools/module-graph.json` exactly.
