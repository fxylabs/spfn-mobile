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

What comes out is a pure function of the **spec bytes**, the **bundle bytes**, the spec's
**repository-relative path** and the lock's **contract block** — nothing else, and in
particular no timestamp, host name or absolute path. The path is on that list because every
generated header prints it, so it is an input to the output rather than a detail of how the
run was invoked; the lock's contract block is on it because it names the bundle file and
the digest its bytes must have, which decides which bytes are read and whether the run
happens at all.

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
"flows": { "pickPlan":      { "entry": "sheet", "sheet": { "detent": "half" }, "start": "plans" } }
```

| Field | Allowed values | Meaning |
| --- | --- | --- |
| `entry` | `"modal"`, `"push"`, `"sheet"` | Maps to `FlowEntry.modal` / `FlowEntry.Modal` and its two siblings. It decides what a system back on the flow's LAST route does: `modal` and `sheet` close the flow, `push` lets the host app's back apply. |
| `sheet.detent` | `"fit"`, `"half"`, `"full"` | How tall the sheet stands. **Required** when `entry` is `sheet` and **refused** otherwise. |
| `start` | a screen name | The route the flow opens on. |

`sheet` is required-and-refused in both directions on purpose. A sheet with no detent has no
height to resolve, and a modal with one carries a number nothing reads — which is the state
`FlowEntry` stopped being an enum to avoid, said one layer up. `fit` measures its content and
never exceeds `full`; `half` and `full` are fractions of the space the host gave the flow, and
`SheetGeometry` resolves all three identically on both platforms.

A spec carries as many flows as it has, and `device-approval.json` beside this file carries
nine. One of them reads and writes; the other eight exist so the three presentations, a stack
inside a sheet, a keyboard and a body that does not fit can be looked at. Nothing in this page
is per-flow — the generator emits a route enum, a factory and a host for each — but two things
downstream are, and both are named where they live: `Rules.kt` covers exactly one flow that
reads and derives a much shorter list for the rest, and a target may narrow to a subset with
`--flows` (below).

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
| `title` | string, optional | The header's title. Default: the screen's own name. |
| `scroll` | boolean, optional | Whether the body scrolls, and therefore gets out of the keyboard's way. Default `true`. |
| `header.close` | boolean, optional | Whether the header draws a close. Default: `true` on the root of a `modal` or a `sheet`, `false` everywhere else. |
| `inputs` | object, optional | What the screen says about the inputs it collects. See below. |
| `body` | body key, optional | The static prose the screen draws. Refused on a screen with a `source`. See below. |
| `actions` | object | One entry per control on the screen. |

`title` is **not** required, deliberately. A screen with no title is a screen somebody has not
named yet, and a header reading `enterCode` says exactly that — where a refusal would stop a
spec being writable in the order people actually write one.

`header.close` only ever suppresses. `Flow.leading` gives a back to every route above the
root and a close to the root of a flow presented over something, so `false` means anything
only on a root that would have had a close: a consent step, or a screen mid-way through
something a person should finish or cancel deliberately. Written on any other screen it
changes nothing — and in particular it never removes a back.

### `body`

```json
"long": { "flow": "longScroll", "source": null, "body": "lorem.long", "actions": { … } }
```

A KEY, never the words. A spec says what a screen IS, and a paragraph of body copy is not
that: a spec carrying its own prose is one nobody can read the structure out of, and the
longest body here is thirty lines on its own. The words live in
`tools/ui-codegen/.../BodyText.kt`, which is the same split `SPFNStrings` makes for the
sentence a failed screen shows, and the emitters write one text component per paragraph.

Two keys today, and the set is closed — a key the table does not carry is refused by name
(refusal 7's family), because the failure it prevents is quiet: a screen that drew nothing
looks like a screen somebody had not filled in yet.

| Key | What it is for |
| --- | --- |
| `lorem.short` | two paragraphs. A screen that needs something above its control |
| `lorem.long` | eight paragraphs. Long enough to put the control at its foot below the fold on a phone, which is what makes a scrolling cell exercise anything |

`body` is **refused** on a screen with a `source` (refusal 9). That screen's body is what
it read; a static one written under it would be a second answer to the same question, and
the read's would be the one nobody could see.

### `inputs`

```json
"inputs": {
  "userCode": { "kind": "code", "label": "Code from the device", "submitOnReturn": true, "autofocus": true }
}
```

An input is **derived**, not declared: `RouteParameters.inputs` reads it off the contract,
because what a screen has to collect is a fact about the request its action sends. This object
is the decoration on top of it, keyed by the derived input's own name.

| Field | Allowed values | Rule |
| --- | --- | --- |
| `kind` | `"code"`, `"text"`, `"email"`, `"number"` | Decides the keyboard, never the request. Default `"text"`. |
| `label` | string | What the field is called on screen. Default: the input's own name. |
| `submitOnReturn` | boolean | Whether the return key performs the screen's action, and therefore says `go` rather than `done`. Default `false`. |
| `autofocus` | boolean | Whether the field takes focus when the screen appears. Default `false`. |

`code` is the strict one and the reason `kind` exists at all. A machine-issued code left as
ordinary text is capitalised at its first letter, offered a correction for what looks like a
word, and can have its hyphen substituted — and the request then carries a code the server
never issued, with no failure anywhere except a refusal the person cannot explain. `code` asks
for an ASCII keyboard, capitalises every character, and turns autocorrection off, on both
platforms.

An entry naming something the screen does not collect is **refused** (refusal 8): the inputs
come from the contract, so a request field renamed upstream would otherwise leave a stale
decoration behind and the field would go on being collected as plain text.

### `actions`

| Field | Type | Rule |
| --- | --- | --- |
| `call` | `service.method`, optional | The write this action performs. Absent means the action only navigates. |
| `then` | see below, optional | What happens to the flow after the action succeeds. Absent means the flow does not move. |
| `role` | `"primary"`, `"secondary"`, `"destructive"`, `"text"`, optional | Which component draws the control. Default `"secondary"`. |

`role` decides a fill and a font and nothing else — never what the action does. The default is
`secondary` rather than `primary` because a default that shouted would make every unconsidered
control the loudest thing on its screen.

`then` takes one of four forms:

| Form | Effect |
| --- | --- |
| `"close"` | `flow.close()` — the stack empties and the flow is no longer presented. |
| `"pop"` | `flow.pop()` — the top route goes, and on the last route this is a no-op. |
| `{ "push": "<screen>" }` | `flow.push(<route for that screen>)`. |
| absent | nothing. |

An action with neither `call` nor `then` is refused: it is a control that does nothing.

## The nine refusals

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
6. **A key the generator does not read.** Every object above — the top level, `contract`, a
   service method, a flow, a flow's `sheet`, a screen, its `header`, an input, an action and an
   object `then` — is checked against the keys listed for it, and an extra one is refused by
   its path: `screens.reviewDevice.useCase is not a key this generator reads`. This is the
   refusal that makes the promise at the top of this page true for OPTIONAL keys. A required
   key misspelled is already a missing-key refusal; a misspelled `usecase` is not, and without
   this rule it would emit a screen whose use-case layer was asked for and quietly left out.
7. **A value outside a closed set.** `entry`, `sheet.detent`, `role` and `inputs.<i>.kind` each
   admit a fixed list, and every one of those values becomes a component name or an enum case
   on both platforms. A word outside the list would not fail here — it would reach an emitter
   that writes `FieldKind.Otp`, and the first evidence would be a compile error in a file
   nobody wrote. A `sheet` on a flow that is not one, and a sheet flow with no `sheet`, are
   refused under the same rule.
8. **An `inputs` entry that decorates nothing.** The inputs a screen collects come from the
   contract, so `inputs.userCod` beside a request field called `userCode` is a decoration with
   no field under it: the field keeps being collected, as plain text with no label and no
   return key. Nothing fails and the screen is not the one somebody wrote.
9. **A `body` on a screen that reads, or one naming words that do not exist.** Both
   directions, for the reason `sheet.detent` is refused both ways: a screen with a `source`
   shows what it read, and static prose under it is either words nothing draws or words drawn
   over the read the screen exists for. A key outside `BodyText`'s closed set is refused by
   name rather than emitted as an empty screen.

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

## What the generator writes, and for whom

One spec, more than one consumer. Which app a run writes into is a **target**: the caller
supplies the two output roots, the Kotlin package and the application id, and the
generator names no app of its own. Two targets ship today, one Gradle task each:

| Target | Task | Swift root | Kotlin root | Flows | Table and flows | Readouts |
| --- | --- | --- | --- | --- | --- | --- |
| `example` | `:ui-codegen:spfnGenerateUi` | `examples/ios-swiftui/Generated` | `examples/android-compose/src/main/kotlin/…/example/generated` | all nine | yes | yes |
| `harness` | `:ui-codegen:spfnGenerateHarnessUi` | `tools/harness/ios/GeneratedUI` | `tools/harness/android/src/main/kotlin/…/harness/generated` | `approveDevice` | no | yes |

`--flows` is the fourth column and, like the last two, a **target** field rather than a spec
key. Which of a showcase's flows a consumer has a use for is a fact about the consumer: the
harness drives device approval against a live reference server, so the other eight would
arrive there as routes, models and views nothing in that app opens. Narrowing takes the
screens of the flows that stay, and the services those screens reach — a service nothing kept
calls would be a protocol and a default implementation with no caller. The whole spec is read
and checked first and narrowed after, so a target cannot hide a broken flow by not asking for
it. A `--flows` value naming something that is not a flow is refused.

`--runner-readouts` is the last column and it is a **target** field rather than a spec key,
for the reason the output roots are: one spec, more than one consumer, and what a consumer is
FOR is not something the screens say about themselves. A readout is test equipment — the one
thing both runners can read and neither can guess, and two lines of monospaced diagnostics on
a screen a person is meant to use. Both consumers that ship today are driven by a runner and
set it; the third, whenever it arrives, is a real app and leaves it off.

The case table and the Maestro flows are the **spec's** artefacts and not an app's: they
name cells, fixtures and expectations, and one app installs the fixtures those cells run
against. So exactly one target declares a table root. The harness drives the same screens
against a real reference server through flows of its own
(`tools/harness/flows/d1-approve.yaml` and its two siblings), and a second copy of the
table there would claim coverage nothing provides.

Everything below is generated. Nothing under a `Generated/`, `GeneratedUI/` or
`generated/` directory is edited by hand; the verify task deletes what is stale and fails
on what has drifted.

    <swift root>/Services/…            the service protocol and its default impl
    <swift root>/Flows/…               the route enum, the flow, the flow host
    <swift root>/Screens/…             one model per screen, plus any use case
    <swift root>/Views/…               one view per screen, built out of SPFNUI's components
    <kotlin root>/…                    the same nine files in Kotlin

and, for the target that declares a table root:

    <table root>/device-approval.cases.json   the case table
    <table root>/device-approval.cases.md     the same table for a reader
    <table root>/flows/<cell>.yaml            one Maestro flow per runnable cell

### What a generated view is made of

Nothing in a generated view draws a control of its own. A field is an `SpfnTextField`, a
control is the button its `role` names, a refusal is a `StatusText`, a read's four states are
a `LoadableView`, and the whole thing stands inside a `Screen`. So the minimum touch target,
the keyboard contract, the palette and the words a failure is shown in live in the SDK —
written once per platform and compared by section 15 of `tools/validate/validate.sh` — rather
than being re-emitted into every view, where a fix would have to be made in the generator and
shipped before an app could take it.

What a **value** looks like is still the human's, outside the generated directory: the ready
slot a `LoadableView` gets is deliberately empty.

A failure is shown by its **key** and never by the server's words. The generated
`ScreenFailure` classifies an envelope's code — the 401 and 404 families are read out of the
pinned bundle at generation time — into one of `deviceNotFound`, `network`, `unauthorized`,
`validation` or `unexpected`, and `SPFNStrings`/`SpfnStrings` is where each of those becomes a
sentence. `message` is text a server chose, and a screen that drew it would publish whatever
the server felt like saying to whoever is holding the phone (decision C7).

Selector rules, which both platforms and both runners share:

- a **button** is found by id `<screen>.<action>` — `enterCode.submit`, `reviewDevice.deny`;
- a **readout** is found by its text, `<name>=<value>` — `state=ready`, `stack=2`.

The split is forced by the platforms rather than chosen: an Android resource id is fixed at
build time, so a control whose identity never changes is found by id, and a readout whose
whole point is that its value changes is found by text. `tools/harness/ios/Sources/HarnessView.swift`
records the two runs that paid for that sentence.
