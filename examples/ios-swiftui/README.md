# The SwiftUI example app

Nine flows, generated from one screen spec, running on iOS: device approval, a three-deep
push, a modal over everything, three sheets at three heights, a stack inside a sheet, a form
with a keyboard, and a body that does not fit. It exists so the generated scaffolds have
somewhere to compile, so a Maestro cell has something to tap and so a person can look at the
three presentations side by side — it is not a design and not a sample of a product, and it
is never published.

    ./gradlew :ui-codegen:spfnGenerateUi          # (re)generate everything under Generated/
    xcodegen generate --spec examples/ios-swiftui/project.yml
    xcodebuild -project SPFNExample.xcodeproj -scheme SPFNExample \
        -destination 'platform=iOS Simulator,name=iPhone 15' CODE_SIGNING_ALLOWED=NO build

`SPFNExample.xcodeproj` is generated and gitignored; `project.yml` is the source. The
deployment target is iOS 17, which is what `@Observable` and this package's baseline need.

## Running one cell

    maestro test -e APP_ID=xyz.superfunction.spfn.example \
        examples/ui-spec/generated/flows/u1.yaml

The flow launches the app with `SPFN_UI_FIXTURE=<cell>`, which reaches iOS as the launch
argument pair `-SPFN_UI_FIXTURE <cell>`. That argument says which cell this run is, and
therefore which of the nine flows opens, on which seeding, at which depth.

**A launch that names no cell opens the MENU**, on the same `ready` fake every cell runs on.
That corrects what this page used to say: the app does not report itself unconfigured and it
is not the fixture argument that installs a fake. There is no real-server path in this app at
all — it has no enrolment path of its own, so a client built against a configured server would
refuse every call for want of a key, which is a refusal that says nothing about the screens a
menu button opens. Reaching a real server is `tools/harness`'s whole subject.

`examples/ui-spec/generated/device-approval.cases.md` is the table, and it is shared with
the Compose app: the same cells, the same selectors, the same flow files. Its last section is
the ten cells no runner drives — a swipe back, a sheet dragged past its threshold, a detent
that has to snap — because a device runner reports success for a gesture whether or not the
platform read it as one (`docs/IMPLEMENTATION-PITFALLS.md` P22). Those are a person's, and
the section below is how to reach them.

Receipts land in `Documents/receipts/receipt-<cell>-<millis>.json`. `UIFileSharingEnabled`
exposes that directory to Finder and to the Files app, which is what makes a receipt
collectable off a phone. A receipt carries a cell id, a fixture name, a stack depth and two
version strings; this app never enrols and holds no key, so there is nothing else it could
carry.

## Running all of them

Build with the commands at the top, install the product on a booted simulator, then:

    xcrun simctl install <udid> <path to SPFNExample.app>
    sh examples/ui-spec/run-cells.sh ios --device <udid>

`run-cells.sh` builds and installs nothing — that is what those commands are for, and its
own header carries them — and it fails unless every cell whose runner is `both` left a
receipt behind. It launches the app once with no fixture first, so the slow first draw
after an install is paid as a warm-up rather than reported as a failed cell. It then drives
the cells **one at a time**, pulling each cell's receipt out of the container before the
next flow starts, because every flow opens with `clearState: true` and that wipe empties
`Documents/receipts` along with everything else. The container itself is looked up again
after every cell rather than once before the run, because on iOS that wipe does not empty
the data container — it recreates it under a new id, so a path read before the first flow
names a directory that no longer exists. Each cell's line carries both facts, its
flow's exit status and whether its receipt arrived, so a cell that asserted everything and
still left nothing is reported as that. Receipts come out of the simulator's data container
through `xcrun simctl get_app_container` and land, with the per-cell Maestro reports, in
`examples/ui-spec/receipts/ios/<date>/`.
`sh examples/ui-spec/run-cells.sh --probe` proves the receipt gate bites and needs no
simulator.

## Seeing the showcase on a real device

    export SPFN_IOS_TEAM=<your Apple Developer team id>
    export SPFN_IOS_DEVICE=<the phone's HARDWARE identifier>
    export SPFN_IOS_DEVICECTL_UDID=<the same phone's CoreDevice udid>

    sh examples/ui-spec/install-device.sh ios
    sh examples/ui-spec/install-device.sh ios --fixture sheetHalf-detent

One command from a checkout to a phone showing the menu: it generates the project, builds and
signs for the device, installs with `devicectl` and launches. With `--fixture <cell>` it
launches straight onto that cell's flow, which is how the `manual` rows of the case table are
reached — record the answers in a copy of
`examples/ui-spec/receipts/manual/TEMPLATE.md`.

**The two ids are two different ids.** `xcodebuild -destination` takes the hardware
identifier, the long dashed `00008120-…` form; `devicectl` takes CoreDevice's own udid for
the same phone, which is an unrelated UUID. Passing either where the other belongs fails, and
neither failure says the id was the wrong KIND of id — xcodebuild says the destination is
unavailable and devicectl says it found no matching device, and both read as "the phone is
not plugged in". `xcrun devicectl list devices` prints the CoreDevice udid; add `--verbose`
for the hardware identifier beside it.

Nothing here has a default. An unset variable stops the run naming the variable and printing
nothing else, which `sh examples/ui-spec/install-device.sh --probe` proves without a phone.
The signing settings and the trust prompt on first launch are the ones
`tools/harness/README.md` records in full: `project.yml` pins manual signing so that no
credential lives in this tree, so a device build overrides it, and the certificate has to be
trusted on the phone under Settings, General, VPN & Device Management before the app will
start.

**Maestro cannot drive a physical iPhone**, so `run-cells.sh` is a simulator command and this
one is not. `tools/harness/README.md` records why in detail: every method of Maestro's
`DeviceControlIOSDevice` but three throws `NotImplementedError`.

## What is generated and what is not

    Generated/    tools/ui-codegen's, never edited
    Sources/      the human's
    Info.plist    XcodeGen's, written from project.yml and gitignored

One directory, one owner. `Info.plist` sits beside `Generated/` rather than inside it
because `spfnGenerateUi` DELETES every file under `Generated/` it did not emit and
`spfnUiVerify` fails on one it finds there — two tools writing into one directory means
whichever runs last is right. `tools/harness/ios` keeps its own plist under a `Generated/`
of its own for the opposite reason: nothing else writes there.

Under `Generated/` are the service, the flow and its routes, one screen model per screen,
the use case a screen asked for, and a view skeleton per screen. The skeletons are
skeletons: one control per action, one field per typed input, and the two readouts a
runner reads. **Layout is yours, and it belongs outside `Generated/`** — the verify task
deletes what is stale there and fails on what has drifted, so an edit inside it does not
survive the next regeneration.

`Generated/` sits outside the package's own `Sources/`, so `swift build` never compiles it;
this app target is its only consumer. That also means it is written on a host where SwiftUI
does not build and first compiled on a Mac — `SwiftEmitter` mirrors `KotlinEmitter`
declaration for declaration so that a fix a Mac forces maps back one to one.

The four hand-written files are `ExampleApp.swift` (the launch, the receipt, the root
readouts), `Fixtures.swift` (which seeding a cell runs under),
`FakeDeviceApprovalService.swift` (what that seeding answers) and `ExampleReceipt.swift`.

## The one rule the validator enforces here

The generated service is the only place a call descriptor is named, and `dismiss` is
refused outright under `Generated/` — it closes a presentation without telling the flow,
which is how a host ends up dismissed over a flow that still believes it is open.
`tools/validate/validate.sh` section 14 enforces both, over `examples/` and
`tools/harness/` alike, and `tools/validate/probe-example-scaffold-rules.sh` proves each
refusal bites. Two files are exempt from the first rule and both are named in the
validator rather than covered by a directory: the harness's two `HarnessModel` files reach
three operations the SDK wraps in nothing.

The Android half of this app is `examples/android-compose`, generated from the same spec,
with the same screen names, the same selectors and the same case table.

And this app is not the only consumer of that spec. `tools/harness/ios` compiles the same
screens from `tools/harness/ios/GeneratedUI`, written by the same generator under a second
target. The difference is what stands behind them: this app runs them against fixtures and
proves the screens' own rules, and the harness runs them against a live reference server
and proves that the requests those rules produce are ones a server accepts — cells d1-d3
in `tools/harness/README.md`. A fixture cannot answer the second question, which is why
there are two apps and not one.
