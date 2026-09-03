<!--
GENERATED FILE — DO NOT EDIT.

generator:       spfn-ui-codegen 0.1.0-dev
spec:            examples/ui-spec/device-approval.json
specSha256:      ea4b08e490fa7f24720859c9b735a9d628949ad1595762d44cb1a833b0b7c164
bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
contractVersion: 0.10.0

Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
Verified by:     ./gradlew :ui-codegen:spfnUiVerify
-->

# Device approval — the case table

One row per cell of the screen table for the `approveDevice` flow.
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

## Running one

```
maestro test -e APP_ID=xyz.superfunction.spfn.example \
    examples/ui-spec/generated/flows/u1.yaml
```

The launch carries `SPFN_UI_FIXTURE=<cell>`, which is the only thing that installs a
fake service. Without it the app builds its client against the configured server and
no fixture exists at all.
