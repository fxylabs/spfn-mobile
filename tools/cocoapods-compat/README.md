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

The CocoaPods compatibility tier is a **proposal awaiting human confirmation**, not a
confirmed decision. Upstream CocoaPods trunk is in maintenance mode with a stated
read-only target date, so trunk is never treated as a long-term channel. If a real
customer requirement appears, the supported paths are a Git-tag podspec or a private
specs repository — activated only after separate approval, and only if the result
passes the same version, API, conformance, security and release gates as SwiftPM.

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
