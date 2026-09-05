# The by-hand cells — a run

Copy this file to `examples/ui-spec/receipts/manual/<date>.md` and fill it in. The blank
form is tracked and the filled ones are not: a gesture answer is a fact about a phone
somebody held on a day, exactly like a receipt pulled off a device, and this repository
keeps what a run must prove rather than what one run proved.

It is written by hand and NOT generated, which is a decision about where it lives rather
than about how much work it is. `tools/ui-codegen` deletes every file under a directory it
owns that it did not emit, so a template generated into this directory would take the
filled-in runs with it on the next generation.

The cells are the `manual` rows of `examples/ui-spec/generated/device-approval.cases.md`,
under **What a person checks**. That table is the source of what to do and what to expect;
this one is where the answer goes. If the two disagree about which cells exist, the
generated one is right and this copy is stale — start again from a fresh copy.

## The target

A reading with no phone beside it is not evidence, and a simulator answers most of these
the same way a device does. Both lines, every time.

| | |
| --- | --- |
| Date | |
| iPhone | model, iOS version, and whether it is a device or a simulator |
| Android | model, API level, and whether it is a device or an emulator |
| Branch and commit | |
| Build | the `install-device.sh` invocation, or how the app got onto each phone |

## What happened

One row per `manual` cell, in the generated table's order. Launch with
`SPFN_UI_FIXTURE=<cell>` to arrive on the right flow — `sh examples/ui-spec/install-device.sh
ios --fixture <cell>` does the build, the install and the launch in one.

Answer each side with `pass`, `fail` or `not run`, and a sentence when it is not `pass`.
`not run` is a real answer and it is not a failure: a predictive back is Android's alone,
and an iPhone row for one of those says so rather than being left blank. A blank cell is
a cell nobody looked at, and the difference matters.

| Cell | iPhone | Android | Notes |
| --- | --- | --- | --- |
| `keyboardForm-keyboard` | | | |
| `longScroll-headerHolds` | | | |
| `modalTour-predictiveBack` | | | |
| `modalTour-closeOnRight` | | | |
| `pushTour-swipeBack` | | | |
| `pushTour-predictiveBack` | | | |
| `sheetFit-detent` | | | |
| `sheetFull-detent` | | | |
| `sheetHalf-detent` | | | |
| `sheetNav-snapBack` | | | |
| `sheetNav-dragAway` | | | |

## What this run did not settle

Anything looked at and left undecided, and anything the two platforms answered differently
for a reason that is not a defect. A run with nothing here has said that every row above is
the whole story, which is a stronger claim than it usually deserves.
