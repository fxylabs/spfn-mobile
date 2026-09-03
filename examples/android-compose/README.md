# The Compose example app

Nine flows, generated from one screen spec, running on Android: device approval, a three-deep
push, a modal over everything, three sheets at three heights, a stack inside a sheet, a form
with a keyboard, and a body that does not fit. It exists so the generated scaffolds have
somewhere to compile, so a Maestro cell has something to tap and so a person can look at the
three presentations side by side — it is not a design and not a sample of a product, and it
is never published.

    ./gradlew :ui-codegen:spfnGenerateUi        # (re)generate everything under generated/
    ./gradlew :example-compose:assembleDebug    # build it
    ./gradlew :example-compose:testDebugUnitTest # one test per cell of the case table

## Running one cell

Every case-table cell that a device runner can drive has a flow beside the table:

    maestro test -e APP_ID=xyz.superfunction.spfn.example \
        ../ui-spec/generated/flows/u1.yaml

The flow launches the app with `SPFN_UI_FIXTURE=<cell>`, which arrives here as an intent
extra. That argument says which cell this run is, and therefore which of the nine flows
opens, on which seeding, at which depth.

**A launch with no extra opens the MENU**, on the same `ready` fake every cell runs on. That
corrects what this page used to say: the app does not build a client against a server
`local.properties` names and does not report itself unconfigured. There is no real-server path
in this app at all — it has no enrolment path of its own, so a client built against a
configured server would refuse every call for want of a key, which is a refusal that says
nothing about the screens a menu button opens. The manifest now declares no INTERNET
permission, so the app cannot send whichever way it was launched. Reaching a real server is
`tools/harness`'s whole subject.

`examples/ui-spec/generated/device-approval.cases.md` is the table: one row per cell, what
it does, what it must then read out, and which fixture it runs under. A cell whose runner
is `unit` has no flow — it is about a moment a device runner cannot hold still, such as a
press during a call in flight — and is proven on the JVM instead. Its last section is the ten
cells whose runner is `manual`: a swipe back, a sheet dragged past its threshold, a detent
that has to snap. A device runner reports success for a gesture whether or not the platform
read it as one (`docs/IMPLEMENTATION-PITFALLS.md` P22), so those are a person's, and the
section below is how to reach them.

Receipts land in `<external files>/receipts/receipt-<cell>-<millis>.json`, which an
`adb pull` reaches without root. A receipt carries a cell id, a fixture name, a stack
depth and two version strings; this app never enrols and holds no key, so there is nothing
else it could carry.

## Running all of them

    ./gradlew :example-compose:assembleDebug
    adb -s <serial> install -r \
        examples/android-compose/build/outputs/apk/debug/example-compose-debug.apk
    sh examples/ui-spec/run-cells.sh android --device <serial>

`run-cells.sh` builds and installs nothing — those two commands are yours, and its own
header carries them — and it fails unless every cell whose runner is `both` left a receipt
behind. It launches the app once with no fixture first, so the slow first draw after an
install or a wipe is paid as a warm-up rather than reported as a failed cell. It then
drives the cells **one at a time**, pulling each cell's receipt off the device before the
next flow starts, because every flow opens with `clearState: true` — which on Android is a
`pm clear` that takes the external files directory, and the receipt in it, with it. Each
cell's line carries both facts, its flow's exit status and whether its receipt arrived, so
a cell that asserted everything and still left nothing is reported as that. Receipts and
the per-cell Maestro reports land in `examples/ui-spec/receipts/android/<date>/`.
`sh examples/ui-spec/run-cells.sh --probe` proves the receipt gate bites and needs no
device.

## Seeing the showcase on a real device

    export SPFN_ANDROID_SERIAL=<the serial adb devices prints>

    sh examples/ui-spec/install-device.sh android
    sh examples/ui-spec/install-device.sh android --fixture sheetHalf-detent

One command from a checkout to a phone showing the menu: it assembles the debug build,
installs it with `adb -s … install -r` and starts the activity. With `--fixture <cell>` the
start carries `--es SPFN_UI_FIXTURE <cell>` and the app opens straight onto that cell's flow,
which is how the `manual` rows of the case table are reached — record the answers in a copy of
`examples/ui-spec/receipts/manual/TEMPLATE.md`.

A wireless serial is `host:port` and goes in the same variable; nothing in the script parses
the value. There is no default: an unset variable stops the run naming the variable and
printing nothing else, which `sh examples/ui-spec/install-device.sh --probe` proves without a
phone.

## What is generated and what is not

    src/main/kotlin/xyz/superfunction/spfn/example/generated/   tools/ui-codegen's, never edited
    src/main/kotlin/xyz/superfunction/spfn/example/             the human's

Under `generated/` are the service, the flow and its routes, one screen model per screen,
the use case a screen asked for, and a view skeleton per screen. The skeletons are
skeletons: one control per action, one field per typed input, and the two readouts a
runner reads. **Layout is yours, and it belongs outside `generated/`** — the verify task
deletes what is stale there and fails on what has drifted, so an edit inside it does not
survive the next regeneration.

The four hand-written files are `MainActivity.kt` (the launch, the receipt, the root
readouts), `Fixtures.kt` (which seeding a cell runs under), `FakeDeviceApprovalService.kt`
(what that seeding answers) and `ExampleReceipt.kt`.

`MainActivity`'s root is a `Box` and the flow host is its last child, which is what lets a
modal flow cover the readouts rather than sit under them — `FlowHost` draws a `Modal` entry
as a cover filling its parent, and a `Column` would lay it out beside the root's own
content instead. That is the one thing the host asks of an app that presents a flow
modally; `android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowHost.kt` states it.

That root also owns the system-bar insets around the flow host, and not only around its own
readouts: a `Modal` cover fills its parent, and an app targeting API 35 or later is drawn
edge-to-edge whether it asks or not, so an un-inset parent puts the flow's first row under
the status bar where a runner's hierarchy stops carrying it
(`docs/IMPLEMENTATION-PITFALLS.md` P25).

## The one rule the validator enforces here

The generated service is the only place a call descriptor is named. Everything above it —
screen models, use cases, views, and anything you write — sees the service interface and
the generated request and response types. `tools/validate/validate.sh` section 14 fails on
a `SpfnGeneratedCalls.` reference anywhere under `examples/` or `tools/harness/` outside a
generated services directory, and `tools/validate/probe-example-scaffold-rules.sh` proves
that refusal bites. Two files are exempt and both are named in the validator rather than
covered by a directory: the harness's two `HarnessModel` files reach three operations the
SDK wraps in nothing.

The iOS half of this app is `examples/ios-swiftui`, generated from the same spec, with the
same screen names, the same selectors and the same case table.

And this app is not the only consumer of that spec. `tools/harness/android` compiles the
same screens from `src/main/kotlin/…/harness/generated`, written by the same generator
under a second target. The difference is what stands behind them: this app runs them
against fixtures and proves the screens' own rules, and the harness runs them against a
live reference server and proves that the requests those rules produce are ones a server
accepts — cells d1-d3 in `tools/harness/README.md`. A fixture cannot answer the second
question, which is why there are two apps and not one.
