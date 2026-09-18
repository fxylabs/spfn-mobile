# The screen spec, field by field

One feature's screens are described in JSON, and `tools/ui-codegen` turns that into the
Swift and Kotlin scaffolds for both example apps, the case table and one Maestro flow per
cell. The JSON is not all in one file: this directory is the spec, `device-approval.json`
beside this page is the worked example of the file half, and `contracts/approveDevice.md`
is the worked example of the other — a flow whose part of the spec lives in the machine
block of the document its screens were written from. This page is the rule book for the
fields, written for whoever — person or model — authors the next one in a consumer app;
`CONTRACT.md` is the rule book for the documents.

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

## Where the spec lives

The generator is given a PLACE, and it is either one JSON file or a directory:

    ./gradlew ...   # tools/ui-codegen/build.gradle.kts passes `examples/ui-spec`

A directory spec is every `.json` file directly inside it and every `.md` document under
its `contracts/`, and the spec is their **union**. Each piece is read whole, by the one
reader, with every rule on this page applied to it — a document with a broken block fails
exactly as a broken spec file fails, and names its own path. Only then are the pieces put
together, under four rules, all of which are refusals:

| Rule | Why it is a refusal and not a merge |
| --- | --- |
| `specVersion` and `contract.manifestSha256` are the same in every piece | Both are properties of the whole spec. Pieces that disagree were written for different generators, or against different contract bundles, and there is no version of the answer that is right for both. |
| A service method two pieces both declare names the same operation | A piece is read whole, so a document whose screen calls `deviceApproval.approve` has to declare that method — overlap is expected and disagreement is the one case a merge would have to choose in. |
| A flow name is declared in exactly one piece | One flow lives in one place. Two documents describing one flow is two truths about what is on the phone. |
| A screen name is declared in exactly one piece | A screen belongs to the flow it names, and its flow is in one document, so it is too. |

Every one of those refusals names both paths, because a collision is a fact about two files
and a message naming one of them sends the reader to the wrong document.

The `spec:` line a generated header prints is then the DIRECTORY, and `specSha256:` is the
sha256 of the pieces: for each piece in name order, its name inside the spec directory, a
newline, its bytes, a newline. Sorted, because a directory walked in the order the
filesystem happens to hold would hash differently on a Mac than on the CI runner and make
every header a fact about the host. Framed with the name inside the directory rather than
the repository-relative one, because the directory's own path is already an input — it is
the `spec:` line — and an input that reached the output through two lines at once would be
one no reader could watch move alone.

"Its bytes" means something different for the two kinds of piece, and the difference is the
point. **A document's prose is not an input to the generator; its block is.** A `.json`
piece is digested as its whole file, because that file is block and nothing else. A `.md`
document is digested as its `json spfn-ui` block ALONE — the fence lines dropped, a `\r`
removed from the end of each line, and nothing trimmed or collapsed inside it — because the
prose is the half you are meant to keep rewriting. It is where you argue what the screens
must do, and nothing downstream reads it. Digesting the whole document moved the header of
every one of the 137 generated files each time a sentence was reworded, which is a bill for
editing the document as intended.

What still moves the digest is any edit to the spec itself: one character changed inside a
block, a space added inside its JSON, or a piece added, removed or renamed. And a document
holding no valid block never reaches the digest at all — it is refused by name first.

### What stays in the JSON file, and why

`device-approval.json` keeps the eight showcase flows. They exist so that the three
presentations, a stack inside a sheet, a keyboard and a body that does not fit can be
looked at and driven by a runner — and a contract document is written for an implementer
who is about to draw a screen against it. Nobody is going to draw `sheetHalf`: it is a
sheet with two paragraphs in it, its screens ARE the generated skeleton, and a document
saying so for each of the eight would be eight pages no reader is the reader of. `approveDevice`
is the flow with a person on the other side of it, so it is the flow with a document.

The split is therefore not a migration that stopped half way. A flow gets a document when
somebody is going to write its screens; until then its part of the spec is a JSON entry,
and moving it later is moving one object between two files.

## Top level

| Field | Type | Rule |
| --- | --- | --- |
| `specVersion` | integer | `1` or `2`. A spec written for a later generator is refused, never partially read. |
| `contract.manifestSha256` | string | The sha256 of the contract bundle this spec was written against. |
| `services` | object | One entry per service. The key is the service name in lowerCamelCase. |
| `flows` | object | One entry per flow. The key is the flow name in lowerCamelCase. |
| `screens` | object | One entry per screen. The key is the screen name in lowerCamelCase. |

Every one of the five keys is required. There is no default for any of them: a spec that
omitted `services` is not a spec with no services, it is a spec somebody did not finish.

### The two versions

One reader reads both, because they are the same spec. **Version 2 adds two keys and changes
nothing else**: `screens.<s>.list`, which makes a read arrive a page at a time, and
`screens.<s>.inputs.<i>.rules`, which says what a field is checked against. A version 1 file
generates exactly the files it generated before — `:ui-codegen:spfnUiVerify` is the gate that
says so, byte for byte.

What the version buys is the refusal in the other direction. A `list` or a `rules` written
into a file that says `"specVersion": 1` is refused by name (refusal 12), because it is a spec
whose author expected a screen this generator would not have emitted — the quiet failure the
whole of this page's strictness is against.

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
| `views` | `"reference"`, `"authored"` | Who writes this flow's views. Default `"reference"`. |

`views` says who draws the screens. `reference` — the default — is the generator, which
emits one view per screen out of SPFNUI's components; `authored` is a person, writing from
the flow's contract document, and for such a flow the generator neither writes those files
nor deletes them as stale. That second half is the load-bearing one: a generated directory
holds nothing but generated files, and without the exemption the next run would delete the
work. It is only writable in a contract document — a flow in a `.json` piece that claims
`authored` is refused, because `authored` says a person wrote these screens against a
document that states what they must do, show and not differ in, and a JSON file states none
of that. A word outside the pair is refused under refusal 7.

Section 21 of `tools/validate/validate.sh` is the other end of it: an authored flow's view
files must exist and must NOT carry the generated header, and a reference flow's must. The
generator cannot see either failure — it does not emit an authored path, so there is nothing
for `spfnUiVerify` to miss.

`sheet` is required-and-refused in both directions on purpose. A sheet with no detent has no
height to resolve, and a modal with one carries a number nothing reads — which is the state
`FlowEntry` stopped being an enum to avoid, said one layer up. `fit` measures its content and
never exceeds `full`; `half` and `full` are fractions of the space the host gave the flow, and
`SheetGeometry` resolves all three identically on both platforms.

A spec carries as many flows as it has, and this directory carries nine: `approveDevice`,
which reads and writes and has a contract document of its own, and the eight in
`device-approval.json` that exist so the three presentations, a stack inside a sheet, a
keyboard and a body that does not fit can be looked at. Nothing in this page
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
| `list` | object, optional, **version 2** | How this screen reads its `source` a page at a time. Refused on a screen without one. See below. |
| `actions` | object | One entry per control on the screen. |

`title` is **not** required, deliberately. A screen with no title is a screen somebody has not
named yet, and a header reading `enterCode` says exactly that — where a refusal would stop a
spec being writable in the order people actually write one.

`header.close` only ever suppresses. `Flow.wayOut` gives a back to every route above the
root and to the root of a pushed flow, and a close — the X in the header's TRAILING slot —
to the root of a flow presented over something, so `false` means anything only on a root
that would have had a close: a consent step, or a screen mid-way through something a person
should finish or cancel deliberately. Written on any other screen it changes nothing — and
in particular it never removes a back.

### `body`

```json
"long": { "flow": "longScroll", "source": null, "body": "lorem.long", "actions": { … } }
```

A KEY, never the words. A spec says what a screen IS, and a paragraph of body copy is not
that: a spec carrying its own prose is one nobody can read the structure out of, and the
longest body here is thirty lines on its own. The words live in
`tools/ui-codegen/.../BodyText.kt`, which is the same split `SPFNStrings` makes for the
sentence a failed screen shows, and the emitters write one text component per paragraph.

Three keys today, and the set is closed — a key the table does not carry is refused by name
(refusal 7's family), because the failure it prevents is quiet: a screen that drew nothing
looks like a screen somebody had not filled in yet.

| Key | What it is for |
| --- | --- |
| `lorem.sheet` | one sentence pair. A screen presented in a sheet at `fit` or `half`, where the fold is the sheet's and not the phone's |
| `lorem.short` | two paragraphs. A screen that needs something above its control |
| `lorem.long` | eight paragraphs. Long enough to put the control at its foot below the fold on a phone, which is what makes a scrolling cell exercise anything |

Which key a screen names is a fact about the presentation it is shown in. A sheet at `fit`
or `half` is a viewport whose fold is far above the phone's, and a control below that fold
is out of the accessibility tree the same way (P25) — with no way back, because a scroll
started at the middle of a half sheet drags the sheet's grabber rather than its content.
So the sheet screens take `lorem.sheet` and the long bodies stay where they can be read:
`sheetFull`, and the pushed and modal flows.

`body` is **refused** on a screen with a `source` (refusal 9). That screen's body is what
it read; a static one written under it would be a second answer to the same question, and
the read's would be the one nobody could see.

### `list` (version 2)

```json
"items": {
  "flow": "browseItems",
  "source": "catalogue.list",
  "scroll": false,
  "list": {
    "items": "items",
    "next": "nextCursor",
    "cursor": "cursor",
    "limit": { "field": "limit", "value": 20 }
  },
  "actions": { "done": { "then": "close", "role": "text" } }
}
```

A screen with a `list` reads its source **a page at a time**, and its state is `Paged<T>`
rather than `Loadable<T>`. Every one of the four names below is the CONTRACT's own field name,
checked against the pinned bundle when the spec is read: the emitted model reaches for these
fields by name, so a field renamed upstream has to be a refusal here rather than a compile
error in a file nobody wrote.

| Field | Type it must have | Meaning |
| --- | --- | --- |
| `items` | a response field declared `array<T>` | The rows. `T` is what the screen's `Paged<T>` is of, and it must be a type the contract declares. |
| `next` | a response field, **optional** `string` | The cursor of the page after this one. Absent means there is no page after it. |
| `cursor` | a request field, **optional** `string` | Where to hand that cursor back. |
| `limit.field` | a request field, `integer` | Where to say how many rows a page is. |
| `limit.value` | integer | How many rows this screen asks for. |

`limit` is an object and not a bare number because **the field's name is the spec's to say**.
A contract is free to call a page size `pageSize`, and a generator that hard-coded the word
`limit` would refuse a contract for spelling one of its own fields differently.

The page size is **not** a route parameter, however required the contract makes the field.
It is a number the spec wrote down, so a route that carried it would ask every caller of
`push` to say how long the next screen's pages are, and two pushes of one screen could then
disagree about what that screen is. The cursor is not a route parameter either, and it is not
in `Paged`: the model holds it privately, next to the service it hands it back to, because a
screen shows rows and never a cursor.

A paged screen's own calls are `load`, `loadMore`, `retryMore` and `reload`. An `action` with
a `call` on one is refused (refusal 12): a write beside the pages would be a second thing
changing the rows under a person's scroll, with no state to say so.

### `inputs`

```json
"inputs": {
  "userCode": { "kind": "code", "label": "Code from the device", "submitOnReturn": true, "autofocus": true },
  "message":  { "kind": "text", "label": "Message", "rules": { "minLength": 2, "maxLength": 140 } }
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
| `rules` | object, **version 2** | What the field is checked against before anything is sent. See below. |

`code` is the strict one and the reason `kind` exists at all. A machine-issued code left as
ordinary text is capitalised at its first letter, offered a correction for what looks like a
word, and can have its hyphen substituted — and the request then carries a code the server
never issued, with no failure anywhere except a refusal the person cannot explain. `code` asks
for an ASCII keyboard, capitalises every character, and turns autocorrection off, on both
platforms.

An entry naming something the screen does not collect is **refused** (refusal 8): the inputs
come from the contract, so a request field renamed upstream would otherwise leave a stale
decoration behind and the field would go on being collected as plain text.

#### `rules` (version 2)

| Field | Type | Rule |
| --- | --- | --- |
| `required` | boolean | Whether an empty field is a refusal. Default `true`. |
| `minLength` | integer | The fewest UTF-16 code units the field accepts. |
| `maxLength` | integer | The most UTF-16 code units the field accepts. |
| `custom` | string | The spec's name for an extra rule, asked of the screen's `FieldValidator`. |

Every one is optional and `required` is the one default that is not "nothing": a field somebody
bothered to write rules for is a field they expect to be filled in, and the other default would
make `"rules": { "minLength": 2 }` a rule that accepts an empty value. `kind` is **not**
repeated here — it is already `inputs.<i>.kind`, where it decides the keyboard, and the
generator writes that one value into `FieldRules.kind` as well. A second spelling of it would
be two answers to one question.

`rules` on a field is what makes the screen check with `Form.check`, on either model. A screen
with two or more fields is a `Form` and checks all of them at once; a screen with one keeps its
`Busy` model and checks that one, which is rule R1 said more precisely rather than R1 changed —
the input is still refused before anything is sent and the screen still carries the refusal, and
what moves is that the envelope now names the rule beside the field, as `<field>:<rule>`.

`custom` is a name and nothing else; the sentence behind it is the app's. One field naming a
custom rule makes the model's `validator` a **required** constructor argument, and makes
`AppContainer` take one per such screen — injected where a service is injected, because a
validator defaulted to null is a rule that is never asked and a field that is always accepted.

#### A field the contract types as an integer

The screen collects TEXT. `FieldKind.number` checks the SHAPE — an optional sign and at least
one digit — and says nothing about width, deliberately: this repository's two `Int`s are 32 and
64 bits, and a check that parsed would accept `3000000000` on one platform and refuse it on the
other (`docs/IMPLEMENTATION-PITFALLS.md` P9).

So the generated model converts **after** the check and **before** the call, to 32 bits on both
platforms — Kotlin `text.toIntOrNull()`, Swift `Int32(text)` — and a value the conversion cannot
hold is refused as `FieldError.kind(.number)` with nothing sent. Any other contract type on an
input is refused: a boolean, a float or an array is not something a text field collects.

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

## The thirteen refusals

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
7. **A value outside a closed set.** `entry`, `sheet.detent`, `views`, `role` and
   `inputs.<i>.kind` each admit a fixed list, and every one of those values becomes a
   component name, an enum case or a decision about which files this run owns. A word outside the list would not fail here — it would reach an emitter
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
10. **A `list` on a screen with no `source`.** A page is a page OF a read, and a screen that
    performs none has nothing to page.
11. **A `list` field the contract does not declare, or declares as something else.** Every one
    of the four names reaches the emitted model as a field access or a request argument:
    `next` naming a field that is not an optional string, or `items` naming something that is
    not an `array<T>`, would be a compile error in a file nobody wrote — or, worse, rows of a
    type nobody asked for. The message names the contract type and lists its fields.
12. **A version 2 key in a version 1 file, and a shape neither screen has.** `list` or
    `inputs.<i>.rules` written where `specVersion` says `1` is refused rather than ignored: it
    is a spec whose author expected a screen this generator would not have emitted. Under the
    same rule, an `action` with a `call` on a paged screen, and a second calling action beside
    the write that sends a form's fields, are refused — a paged screen's calls are its own
    four, and a form is one screenful of input and one write that sends it.
13. **`views: reference` on a flow with a list or a form screen.** Neither can be drawn from a
    grammar — a list is rows of something, and what a row shows is the whole design of the
    screen — so the generator has no skeleton to offer and says so rather than emitting one.
    `authored` is only writable in a contract document, so the two rules together mean a flow
    of either kind lives in a document that states what its screens must do.

## How a screen's state type is derived

The screen model's state is not declared in the spec. It follows from `source`, so a
screen cannot claim a state its read cannot produce:

| `source` | `list` | fields collected | State | States it can be in |
| --- | --- | --- | --- | --- |
| `null` | — | 0 or 1 | `Busy` | `idle`, `busy`, `error` |
| `null` | — | 2 or more | `Form` | `idle`, `busy`, `error`, and one refusal per field |
| an operation whose response is an **object** | absent | any | `Loadable<Response>` | `loading`, `ready`, `error` |
| an operation whose response is an **object** | present | any | `Paged<T>` | the first page's `loading`, `ready`, `empty`, `error`, and the footer's `idle`, `busy`, `error` |

`empty` exists only where there are rows, because "the server answered with no rows" is a
state only a list can be in. An object response that arrived is a value; there is no such
thing as an object that arrived and is empty.

**Two fields is the line for a form**, and it is a line rather than a key because a screen's
fields are the contract's to say: what a screen collects is the required fields of the request
its action sends, so how many there are is not something a spec could disagree with. One field
is where the `Busy` model already says everything there is to say — there is no second refusal
to report beside the first — and `Form` exists because a person pressing submit should be told
every wrong thing at once. A screen that READS is never a form whatever it collects: its state
is what it read.

A `Paged<T>` carries a `Loadable` inside it and is not one, which is why the table above
splits them: `Paged.page` is the FIRST page's read — in flight, rows, none, or failed — and
`Paged.more` is a `Busy` for every page after it, because a failed append leaves the rows
already on screen exactly where they are. `Paged.hasMore` is the one fact neither carries.

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
- a **field** is found by id `<screen>.<field>` — `compose.message`;
- a **readout** is found by its text, `<name>=<value>` — `state=ready`, `stack=2`.

A list screen's own two controls are `<screen>.retry`, which reads the first page again, and
`<screen>.retryMore`, which asks again for the page the footer failed on. Two ids and not one:
both can be on screen at once, and a runner asked for one id would refuse to pick. `reload` is
a third — it is the action a pull or a refresh control calls — and every one of them follows
`<screen>.<action>` like any other control. **There is no "load more" control**: `PagedView`
asks for the next page when the end of the rows is laid out, so a runner asks for one by
scrolling, which is what a person does.

## The fixtures the case table names

A fixture is a seeding the example app installs by hand, named for what the source read does
under it. This is the vocabulary; `examples/android-compose` and `examples/ios-swiftui` hold
the seeding itself, and a cell names one.

| Fixture | What every call answers under it |
| --- | --- |
| `ready` | Every read and every write answers. |
| `slow` | Every call waits before answering, so an in-flight state can be observed. |
| `writeRefused` | Every read answers and every write refuses. |
| `refused` | Every read refuses. |
| `sourceRefused` | The first read answers and every later one refuses. |
| `sourceRefusedOnce` | The first read answers, the second refuses, and the third answers again. |
| `deepReady` | Every read answers, and the flow is opened at a whole stack rather than pushed onto. |
| `pagesTwo` | Two pages: three rows and a cursor, then two rows and no cursor after them. |
| `pagesOne` | One page: three rows and no cursor, so the server said there is nothing after it. |
| `pagesNone` | A first page with no rows and no cursor. |
| `firstRefused` | The first page refuses, so nothing ever reaches the screen. |
| `secondRefused` | The first page answers three rows and a cursor; every page after it refuses. |
| `secondRefusedOnce` | The same, except that the SECOND ask for the second page answers its two rows. |
| `slowSecond` | The first page answers at once and the second waits, so an append is visible in flight. |

A paged fixture is named for the SHAPE of its answers and not for its rows: three rows then two
is `pagesTwo` on every run, so a cell asserting `count=5` is asserting the fixture's arithmetic
rather than a list somebody may lengthen.

The split is forced by the platforms rather than chosen: an Android resource id is fixed at
build time, so a control whose identity never changes is found by id, and a readout whose
whole point is that its value changes is found by text. `tools/harness/ios/Sources/HarnessView.swift`
records the two runs that paid for that sentence.
