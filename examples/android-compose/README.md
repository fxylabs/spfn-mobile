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

## The one rule the validator enforces here

The generated service is the only place a call descriptor is named. Everything above it —
screen models, use cases, views, and anything you write — sees the service interface and
the generated request and response types. `tools/validate/validate.sh` section 14 fails on
a `SpfnGeneratedCalls.` reference anywhere under `examples/` outside a generated services
directory, and `tools/validate/probe-example-scaffold-rules.sh` proves that refusal bites.

The iOS half of this app is `examples/ios-swiftui`, generated from the same spec, with the
same screen names, the same selectors and the same case table.
