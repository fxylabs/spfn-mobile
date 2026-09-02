# The screen spec, field by field

One JSON file describes one feature's screens, and `tools/ui-codegen` turns it into the
Swift and Kotlin scaffolds for both example apps, the case table and one Maestro flow per
cell. `device-approval.json` beside this file is the worked example; this page is the rule
book, written for whoever — person or model — authors the next one in a consumer app.

The generator is the only reader. It refuses a spec it does not fully understand rather
than emitting a plausible app from one: every rule below that says *fails* is a hard stop
with a message naming the field, not a warning.

    ./gradlew :ui-codegen:spfnGenerateUi     # rewrite the generated scaffolds
    ./gradlew :ui-codegen:spfnUiVerify       # fail if they are not up to date

## Top level

| Field | Type | Rule |
| --- | --- | --- |
| `specVersion` | integer | Exactly `1`. A spec written for a later generator is refused, never partially read. |
| `contract.manifestSha256` | string | The sha256 of the contract bundle this spec was written against. |
| `services` | object | One entry per service. The key is the service name in lowerCamelCase. |
| `flows` | object | One entry per flow. The key is the flow name in lowerCamelCase. |
| `screens` | object | One entry per screen. The key is the screen name in lowerCamelCase. |

Every one of the five keys is required. There is no default for any of them: a spec that
omitted `services` is not a spec with no services, it is a spec somebody did not finish.

## `services`

```json
"services": { "deviceApproval": { "lookup": { "operation": "authDeviceInfo" } } }
```

A service is a named set of **methods**, and a method names exactly one contract
operation. The operation name is the *descriptor* name — `authDeviceInfo`, not
`auth.device.info` — because that is the name the generated call descriptors carry
(`SpfnGeneratedCalls.authDeviceInfo` / `SPFNGeneratedCalls.authDeviceInfo`). The generator
derives the set of legal names from the pinned bundle with the same function the contract
generator names descriptors with, so the two can never drift apart.

The emitted service is the **only** layer that touches a descriptor. Everything above it —
screen models, use cases, views — sees the protocol/interface and the generated request
and response types, and the validator refuses a reference to `SpfnGeneratedCalls.` or
`SPFNGeneratedCalls.` anywhere under `examples/` outside the generated services directory.

An operation that declares no response type answers 204 with an empty body, so its method
returns `Void`/`Unit` rather than a value nothing can decode.

## `flows`

```json
"flows": { "approveDevice": { "entry": "modal", "start": "enterCode" } }
```

| Field | Allowed values | Meaning |
| --- | --- | --- |
| `entry` | `"modal"`, `"push"` | Maps to `FlowEntry.modal` / `FlowEntry.Modal` and its push twin. It decides what a system back on the flow's LAST route does: `modal` closes the flow, `push` lets the host app's back apply. |
| `start` | a screen name | The route the flow opens on. |

## `screens`

```json
"reviewDevice": {
  "flow": "approveDevice",
  "source": "deviceApproval.lookup",
  "usecase": true,
  "actions": { "approve": { "call": "deviceApproval.approve", "then": "close" } }
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `flow` | flow name | Which flow this screen belongs to. Required. |
| `source` | `null` or `service.method` | The read that fills the screen. `null` means the screen reads nothing. |
| `usecase` | boolean, optional | `true` puts a use-case protocol between the model and the service. Default `false`. |
| `actions` | object | One entry per control on the screen. |

### `actions`

| Field | Type | Rule |
| --- | --- | --- |
| `call` | `service.method`, optional | The write this action performs. Absent means the action only navigates. |
| `then` | see below, optional | What happens to the flow after the action succeeds. Absent means the flow does not move. |

`then` takes one of four forms:

| Form | Effect |
| --- | --- |
| `"close"` | `flow.close()` — the stack empties and the flow is no longer presented. |
| `"pop"` | `flow.pop()` — the top route goes, and on the last route this is a no-op. |
| `{ "push": "<screen>" }` | `flow.push(<route for that screen>)`. |
| absent | nothing. |

An action with neither `call` nor `then` is refused: it is a control that does nothing.

## The five refusals

The generator fails, and generates nothing at all, when:

1. **Digest mismatch.** `contract.manifestSha256` differs from the recomputed sha256 of
   the bundle, or from `Contracts/upstream.lock.json`'s `contract.manifestSha256`. Both
   comparisons are made — a spec pinned to yesterday's bundle and a lock pointed at a
   different file are different mistakes and both are refused.
2. **Unknown operation.** A name in `services.<service>.<method>.operation` is not one of
   the descriptor names the contract generator emits for the pinned bundle.
3. **`then` target outside the flow.** A `{ "push": "x" }` naming a screen that does not
   exist, or one that belongs to another flow. Two flows' routes on one stack is exactly
   what `FlowRoute` exists to prevent, and a spec is where it can be prevented for free.
4. **`start` is not a screen of that flow.** A flow that opened on a foreign route would
   push a route its own host cannot render.
5. **Unknown service method in `call` or `source`.** `deviceApproval.lookp` is a typo that
   would otherwise reach a Kotlin compiler as a missing method, one stage too late and in
   the wrong file.

## How a screen's state type is derived

The screen model's state is not declared in the spec. It follows from `source`, so a
screen cannot claim a state its read cannot produce:

| `source` | State | States it can be in |
| --- | --- | --- |
| `null` | `Busy` | `idle`, `busy`, `error` |
| an operation whose response is an **object** | `Loadable<Response>` | `loading`, `ready`, `error` |
| an operation whose response is a **list** | `Loadable<[Response]>` | `loading`, `ready`, `empty`, `error` |

`empty` exists only for a list, because "the server answered with no rows" is a state only
a list can be in. An object response that arrived is a value; there is no such thing as an
object that arrived and is empty.

**1단계 rule: every response is an object.** Whether a response is a list is a fact about
the contract, and the bundle does not carry it — `tools/contract-codegen/.../Bundle.kt`
models a response as `responseType: String?`, a single named type or nothing at all, and
its `FieldType` grammar puts `array<...>` on a *field* and never on a response. There is
no bundle key that says "this operation answers with a list". So until the contract
declares one, every response with a type is read as an object and no generated screen
carries `empty`. A screen model that needs `empty` needs a contract change first, which
is the honest order.

An operation with no response type at all cannot be a `source`: there is nothing for the
screen to show. It is refused under rule 5's family — a source must name a method whose
operation declares a response.

## What the generator writes

Everything below is generated. Nothing under a `Generated/` or `generated/` directory is
edited by hand; the verify task deletes what is stale and fails on what has drifted.

    examples/ios-swiftui/Generated/Services/…            the service protocol and its default impl
    examples/ios-swiftui/Generated/Flows/…               the route enum, the flow, the flow host
    examples/ios-swiftui/Generated/Screens/…             one model per screen, plus any use case
    examples/ios-swiftui/Generated/Views/…               one view skeleton per screen
    examples/android-compose/src/main/kotlin/…/generated/…  the same seven files in Kotlin
    examples/ui-spec/generated/device-approval.cases.json   the case table
    examples/ui-spec/generated/device-approval.cases.md     the same table for a reader
    examples/ui-spec/generated/flows/<cell>.yaml            one Maestro flow per runnable cell

Selector rules, which both platforms and both runners share:

- a **button** is found by id `<screen>.<action>` — `enterCode.submit`, `reviewDevice.deny`;
- a **readout** is found by its text, `<name>=<value>` — `state=ready`, `stack=2`.

The split is forced by the platforms rather than chosen: an Android resource id is fixed at
build time, so a control whose identity never changes is found by id, and a readout whose
whole point is that its value changes is found by text. `tools/harness/ios/Sources/HarnessView.swift`
records the two runs that paid for that sentence.
