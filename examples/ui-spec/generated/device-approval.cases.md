<!--
GENERATED FILE — DO NOT EDIT.

generator:       spfn-ui-codegen 0.1.0-alpha.3
spec:            examples/ui-spec
specSha256:      571f09bd88446d067fb3b9173e2705d80d4078de36c608f8f2be11004e8fcba2
bundleSha256:    bb0373c2c3e95bcc3923c84a160945e17ca57d5c13fd8122f341f1df181bc658
contractVersion: 0.13.0

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
| `s1` | `enterCode` | `idle` | `screen.close` | maestro | `ready` | `stack=0` | S1 and C5 — the root of a flow presented over something offers the flow's close at the header's trailing end, and pressing it closes the flow |
| `s2` | `reviewDevice` | `ready` | `screen.back` | maestro | `ready` | `stack=1`, `state=idle` | S2 and C4 — a route above the root has the header's back, and pressing it pops one route; on iOS that back is the system navigation bar's own button |
| `keyboardForm-close` | `form` | `idle` | `submit` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `longScroll-close` | `long` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `modalTour-close` | `modalTwo` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `modalTour-wayOutClose` | `modalOne` | `idle` | `screen.close` | maestro | `ready` | `stack=0` | C11 — the root of a presented flow whose Android half draws no SDK header offers the flow's close through its own WayOutButton, and it closes the flow |
| `pushTour-reach` | `tourThree` | `idle` | `next` | maestro | `ready` | `stack=3`, `state=idle` | R5 — every push adds one route, so the stack is as deep as the tour is long |
| `pushTour-close` | `tourThree` | `idle` | `done` | maestro | `ready` | `stack=0` | R5 — close empties the stack whatever the depth and whatever presented it, so the flow is no longer on show |
| `pushTour-rootBack` | `tourOne` | `idle` | `headerBack` | maestro | `ready` | `stack=0` | N2 — the back on a pushed flow's root closes the flow, which is what hands the person back to the host's own screen |
| `pushTour-rootSystemBack` | `tourOne` | `idle` | `systemBack` | maestro | `ready` | `stack=0` | N2 and R8 — the system back on a pushed flow's root is the same act as the header's, so the flow closes and the host is underneath |
| `pushTour-contentSwipe` | `tourTwo` | `idle` | `contentSwipe` | maestro | `ready` | `stack=1`, `state=idle` | C2 — on iOS 26 and later a back swipe that starts in the content rather than at the edge is the system's own second back gesture, and it pops one route (an iOS 17 or 18 runtime has no such gesture); on Android the system back does the same |
| `pushTour-trailing` | `tourTwo` | `idle` | `next` | maestro | `ready` | `stack=3`, `state=idle` | C6 — the header's trailing item is the app's own action and not a way out: pressing it moves the flow the way the action says |
| `pushTour-wayOutBack` | `tourThree` | `idle` | `headerBack` | maestro | `ready` | `stack=2`, `state=idle` | C10 — a screen whose Android half draws no SDK header offers the flow's back through its own WayOutButton, and it pops one route; iOS keeps the bar's |
| `pushTour-noHeaderSystemBack` | `tourThree` | `idle` | `systemBack` | maestro | `ready` | `stack=2`, `state=idle` | C10 — with no SDK header the system back is still the flow's own pop |
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

15 cells with no runner, for one of two reasons. Most are a GESTURE or a
resting height, which is the class of thing a device runner reports success for
whether or not the platform read it as the gesture it meant — cells u7b and u10b
spent a Mac round on exactly that (`docs/IMPLEMENTATION-PITFALLS.md` P22). The rest
are here because a runner's TAP is not a finger: it is a down and an up with no
movement between them, and a press that only a moving finger can cancel is one no
runner can be pointed at (P36). So these are checked by a person on a real phone,
and the answers are written down.

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
| `modalTour-closeOnRight` | `modalTour` | `modalOne` | look at the top of the flow's first screen, on both phones | N3 — the way out is an X drawn as an icon in the TOP RIGHT corner — an item in the navigation bar on iPhone, the header's mark or the screen's own way-out row on Android — and it is not a word on the left (`stack=1`) |  |  |
| `modalTour-fingerTap` | `modalTour` | `modalOne` | tap `modalOne.next` on the flow's first screen WITH A FINGER — a real thumb on the glass, not a runner tap and not `adb shell input tap` | P36 — the control responds and the stack moves, because nothing drawn over or around the screen consumed the small movements a finger makes inside a tap (`stack=2`) |  |  |
| `pushTour-swipeBack` | `pushTour` | `tourTwo` | swipe in from the left edge on iPhone, or use the system back gesture on Android | S2 and R8 — the gesture is the flow's own pop, so one route drops and the screen under it is the one it was (`stack=1`) |  |  |
| `pushTour-predictiveBack` | `pushTour` | `tourTwo` | on Android, press and HOLD the back gesture at the edge without releasing it | the screen underneath is drawn under the gesture while it is held, and releasing lands on it; letting go back at the edge cancels and changes nothing (`stack=1`) |  |  |
| `pushTour-barTheme` | `pushTour` | `tourOne` | on iPhone, look at the navigation bar over the flow's first screen, and then switch the phone between light and dark with the screen up | C8 — the bar's title is drawn in the theme's title font and text colour, and the bar's background is the theme's background colour, in both appearances (`stack=1`) |  |  |
| `pushTour-barSpace` | `pushTour` | `tourOne` | on iPhone, look at where the flow's first line stands under the bar — and on the example app's own menu, which has neither a title nor an item in its bar | C7 — the content starts right under the bar, with no second header's worth of space between them; under a bar with nothing in it, right under the status bar (`stack=1`) |  |  |
| `pushTour-buttonEdge` | `pushTour` | `tourOne` | tap `tourOne.next` on the flow's first screen at the far EDGE of the button — the coloured part well away from the words — with a finger | P39 — the stack moves, because the whole button is the tap target and not only the pixels its label happened to draw (`stack=2`) |  |  |
| `sheetFit-detent` | `sheetFit` | `fitOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet is as tall as its content and no taller, on both platforms, and it does not grow to a fraction of the window it did not need (`stack=1`) |  |  |
| `sheetFull-detent` | `sheetFull` | `fullOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet stands nearly full height and stops short of the top, leaving the screen under it visible above (`stack=1`) |  |  |
| `sheetHalf-detent` | `sheetHalf` | `halfOne` | look at how tall the sheet stands, and compare the two platforms side by side | the sheet stands at about half the window on both platforms (`stack=1`) |  |  |
| `sheetNav-snapBack` | `sheetNav` | `navOne` | drag the sheet's handle down a SHORT way — less than half its height — and let go | the sheet returns to the height it was standing at and the flow is untouched (`stack=1`) |  |  |
| `sheetNav-dragAway` | `sheetNav` | `navTwo` | drag the sheet's handle down PAST half its height and let go | the whole flow closes rather than one route — a sheet is a presentation and a drag dismisses the presentation, from whatever depth it started at (`stack=0`) |  |  |

Where a cell has to be walked to before the gesture, the walk is a tap on the
controls named in the table's JSON `steps` — the same ids a flow file would use.
