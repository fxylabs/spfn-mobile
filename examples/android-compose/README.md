# The Compose example app

The device-approval flow, generated from one screen spec, running on Android. It exists so
the generated scaffolds have somewhere to compile and so a Maestro cell has something to
tap — it is not a design and not a sample of a product, and it is never published.

    ./gradlew :ui-codegen:spfnGenerateUi        # (re)generate everything under generated/
    ./gradlew :example-compose:assembleDebug    # build it
    ./gradlew :example-compose:testDebugUnitTest # one test per cell of the case table

## Running one cell

Every case-table cell that a device runner can drive has a flow beside the table:

    maestro test -e APP_ID=xyz.superfunction.spfn.example \
        ../ui-spec/generated/flows/u1.yaml

The flow launches the app with `SPFN_UI_FIXTURE=<cell>`, which arrives here as an intent
extra. That argument is the only thing that installs a fake service: with no extra,
`Fixtures.forCell` is never reached and the app builds its client against the server
`local.properties` names — or, with no server and no enrolled key, reports itself
unconfigured and sends nothing. There is no flag inside the app that turns a fake on.

`examples/ui-spec/generated/device-approval.cases.md` is the table: one row per cell, what
it does, what it must then read out, and which fixture it runs under. A cell whose runner
is `unit` has no flow — it is about a moment a device runner cannot hold still, such as a
press during a call in flight — and is proven on the JVM instead.

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
