<!--
GENERATED FILE — DO NOT EDIT.

generator:       spfn-ui-codegen 0.1.0-dev
spec:            examples/ui-spec/device-approval.json
specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
contractVersion: 0.10.0

Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
Verified by:     ./gradlew :ui-codegen:spfnUiVerify
-->

# The showcase — the case table

One row per cell of the screen table, across the 9 flows the spec declares.
Every expectation is a READOUT, because a readout is the only thing both runners can
read and neither can guess: `state=<…>` is the screen model's own state and
`stack=<depth>` is the flow's.

**Where the expectations come from.** They are derived from the rule table in
`tools/ui-codegen/src/main/kotlin/xyz/superfunction/spfn/uicodegen/Rules.kt`, not from
the generated models — the models are derived from the spec, and a table derived from
the code it checks proves only that the code equals itself
(`docs/IMPLEMENTATION-PITFALLS.md` P10).

A cell whose runner is `unit` is about a moment a device runner cannot hold still —
a press during a call in flight, an answer arriving after its flow closed — so it is
proven on the JVM against the models and has no flow file.

| Cell | Screen | State | Action | Runner | Fixture | Expect | Rule |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `u1` | `enterCode` | `idle` | `submit` | both | `ready` | `stack=2`, `state=ready` | R5 — the call succeeds, so the then applies and the pushed screen loads (R6) |
| `u1c` | `enterCode` | `busy` | `submit` | unit | `ready` | `stack=1`, `state=busy` | R4 — the flow is closed while the call is in flight and reopened at its start screen before the answer arrives, so that answer belongs to an appearance that is gone |
| `u2` | `enterCode` | `idle` | `submit` | both | `ready` | `stack=1`, `state=error` | R1 — an empty required input is refused before anything is sent |
| `u3` | `enterCode` | `busy` | `submit` | unit | `slow` | `stack=1`, `state=busy` | R2 — the second press while the first is in flight is ignored |
| `u4` | `enterCode` | `idle` | `submit` | both | `refused` | `stack=1`, `state=error` | R7 — the call fails, so no then applies and the screen carries the refusal |
| `u5` | `enterCode` | `idle` | `cancel` | both | `ready` | `stack=0` | R5 — close empties the stack, so the flow is no longer presented |
| `u6` | `enterCode` | `error` | `submit` | both | `ready` | `stack=2`, `state=ready` | R1 then R5 — a refused input leaves the screen usable, and the next press proceeds |
| `u7` | `reviewDevice` | `ready` | `back` | both | `ready` | `stack=1`, `state=idle` | R5 — pop drops the top route and the entry screen is idle again |
| `u7b` | `reviewDevice` | `ready` | `systemBack` | both | `ready` | `stack=1`, `state=idle` | R8 — the system back gesture above the last route is the flow's own pop |
| `u8` | `reviewDevice` | `ready` | `approve` | both | `ready` | `stack=0` | R5 — the write succeeds and close empties the stack |
| `u8c` | `reviewDevice` | `ready` | `approve` | unit | `writeRefused` | `stack=0`, `state=ready` | R4 — the flow closes while the write is in flight, so its refusal changes nothing |
| `u8d` | `reviewDevice` | `ready` | `approve` | unit | `ready` | `stack=1`, `state=ready` | R9 — the system back pops this route while the write is in flight, so its answer changes nothing and navigates nowhere |
| `u8e` | `reviewDevice` | `ready` | `approve` | unit | `ready` | `stack=3`, `state=ready` | R9 — a second copy of the entry route is pushed over this screen while the write is in flight, so the answer is for a screen that is no longer the one on show |
| `u9` | `reviewDevice` | `ready` | `deny` | both | `ready` | `stack=0` | R5 — the second write closes the same way the first does |
| `u9c` | `reviewDevice` | `ready` | `deny` | unit | `writeRefused` | `stack=0`, `state=ready` | R4 — the same late refusal, on the write that declares no response body |
| `u10` | `reviewDevice` | `error` | `back` | both | `sourceRefused` | `stack=1`, `state=idle` | R5 — pop drops a route in any state, and the screen under it is where it was left |
| `u10b` | `reviewDevice` | `error` | `systemBack` | both | `sourceRefused` | `stack=1`, `state=idle` | R8 — the system back gesture is the same pop from the same state |
| `u11` | `reviewDevice` | `loading` | `approve` | unit | `slow` | `stack=2`, `state=loading` | R3 — a write over a value the screen has not read yet is ignored |
| `u12` | `reviewDevice` | `error` | `retry` | both | `sourceRefusedOnce` | `stack=2`, `state=ready` | R5 — an action with no then leaves the stack alone and re-reads the source |
| `u13` | `reviewDevice` | `loading` | `load` | both | `sourceRefused` | `stack=2`, `state=error` | R7 — the source refuses, so the screen carries the refusal and the stack stands |
| `u14` | `reviewDevice` | `loading` | `deepEntry` | both | `deepReady` | `stack=2`, `state=ready` | R6 — a screen loads its source once however it appeared, including on a deep entry |
| `k1` | `enterCode` | `idle` | `submit` | maestro | `ready` | `stack=2`, `state=ready` | K1 — the body gets out of the keyboard's way, so the control under the field is still on screen with the keyboard up and pressing it still submits |
| `k2` | `enterCode` | `idle` | `hideKeyboard` | maestro | `ready` | `stack=1`, `state=idle` | K2 — a tap outside the field puts the keyboard away and changes nothing else: the screen is where it was and the field is still there |
| `k3` | `enterCode` | `idle` | `submit` | maestro | `ready` | `stack=2`, `state=ready` | K3 — autofocus means the field already holds the focus, so text typed without tapping it first reaches the field and the write goes out with it |
| `k4` | `enterCode` | `idle` | `return` | maestro | `ready` | `stack=2`, `state=ready` | K4 — submitOnReturn means the return key performs the screen's action, with no control pressed at all |
| `k5` | `enterCode` | `idle` | `return` | maestro | `ready` | `stack=2`, `state=ready` | K4 and K2 together — the return key still submits after the keyboard was put away and the field taken up again, which is the state a person is in after reading the screen |
| `k6` | `enterCode` | `error` | `submit` | maestro | `ready` | `stack=1`, `state=idle` | K6 — editing the field clears the refusal under it, so the screen is usable again without the person pressing anything |
| `k7` | `enterCode` | `error` | `submit` | maestro | `ready` | `stack=1`, `state=error` | K7 and C7 — a refused input draws its refusal UNDER the field rather than somewhere on the screen, and the line is drawn at all |
| `s1` | `enterCode` | `idle` | `screen.close` | maestro | `ready` | `stack=0` | S1 — the root of a flow presented over something draws the header's close, and pressing it closes the flow |
| `s2` | `reviewDevice` | `ready` | `screen.back` | maestro | `ready` | `stack=1`, `state=idle` | S2 — a route above the root draws the header's back, and pressing it pops one route |
| `keyboardForm-close` | `form` | `idle` | `submit` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `longScroll-close` | `long` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `modalTour-close` | `modalTwo` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `pushTour-reach` | `tourThree` | `idle` | `next` | maestro | `ready` | `stack=3`, `state=idle` | R5 — every push adds one route, so the stack is as deep as the tour is long |
| `pushTour-close` | `tourThree` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `sheetFit-close` | `fitOne` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `sheetFull-close` | `fullOne` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `sheetHalf-close` | `halfOne` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `sheetNav-reach` | `navTwo` | `idle` | `next` | maestro | `ready` | `stack=2`, `state=idle` | R5 — every push adds one route, so the stack is as deep as the tour is long |
| `sheetNav-close` | `navTwo` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |

## Running one

```
maestro test -e APP_ID=xyz.superfunction.spfn.example \
    examples/ui-spec/generated/flows/u1.yaml
```

The launch carries `SPFN_UI_FIXTURE=<cell>`, which is what says WHICH cell this run is
and therefore which flow opens and what its fake service answers. A launch that names
no cell opens the menu instead, on the same fake.

## What a person checks

10 cells with no runner. Every one of them is a GESTURE or a resting
height, which is the class of thing a device runner reports success for whether or
not the platform read it as the gesture it meant — cells u7b and u10b spent a Mac
round on exactly that (`docs/IMPLEMENTATION-PITFALLS.md` P22). So these are checked
by a person on a real phone, and the answers are written down.

Launch the app with `SPFN_UI_FIXTURE=<cell>` to arrive on the right flow, do what the
**Do** column says, and record what happened. Copy
`examples/ui-spec/receipts/manual/TEMPLATE.md` to
`examples/ui-spec/receipts/manual/<date>.md` and fill it in there; this table is
generated and anything written into it is lost on the next generation.

| Cell | Flow | Screen | Do | Expect | iPhone | Android |
| --- | --- | --- | --- | --- | --- | --- |
| `keyboardForm-keyboard` | `keyboardForm` | `form` | tap the field, and read the screen with the keyboard up | K1 — the field stays visible and the control under it is still reachable; nothing jumps as the keyboard arrives and nothing is left scrolled out of place (`stack=1`) |  |  |
| `longScroll-headerHolds` | `longScroll` | `long` | scroll the body from the top to the bottom and back | S2's other half — the header and its title stay exactly where they are while the body moves under them, so the way out of the flow never scrolls away (`stack=1`) |  |  |
| `modalTour-predictiveBack` | `modalTour` | `modalOne` | on Android, use the system back gesture on the flow's FIRST screen | R8 — a flow presented over something is closed by a back on its last route, so the whole flow goes rather than one route (`stack=0`) |  |  |
| `pushTour-swipeBack` | `pushTour` | `tourTwo` | swipe in from the left edge on iPhone, or use the system back gesture on Android | S2 and R8 — the gesture is the flow's own pop, so one route drops and the screen under it is the one it was (`stack=1`) |  |  |
| `pushTour-predictiveBack` | `pushTour` | `tourTwo` | on Android, press and HOLD the back gesture at the edge without releasing it | the screen underneath is drawn under the gesture while it is held, and releasing lands on it; letting go back at the edge cancels and changes nothing (`stack=1`) |  |  |
| `sheetFit-detent` | `sheetFit` | `fitOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet is as tall as its content and no taller, on both platforms, and it does not grow to a fraction of the window it did not need (`stack=1`) |  |  |
| `sheetFull-detent` | `sheetFull` | `fullOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet stands nearly full height and stops short of the top, leaving the screen under it visible above (`stack=1`) |  |  |
| `sheetHalf-detent` | `sheetHalf` | `halfOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet stands at about half the window on both platforms (`stack=1`) |  |  |
| `sheetNav-snapBack` | `sheetNav` | `navOne` | drag the sheet's handle down a SHORT way — less than half its height — and let go | the sheet returns to the height it was standing at and the flow is untouched (`stack=1`) |  |  |
| `sheetNav-dragAway` | `sheetNav` | `navTwo` | drag the sheet's handle down PAST half its height and let go | the whole flow closes rather than one route — a sheet is a presentation and a drag dismisses the presentation, from whatever depth it started at (`stack=0`) |  |  |

Where a cell has to be walked to before the gesture, the walk is a tap on the
controls named in the table's JSON `steps` — the same ids a flow file would use.
