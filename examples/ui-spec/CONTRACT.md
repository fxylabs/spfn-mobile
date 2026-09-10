# The screen contract document, section by section

One flow is one Markdown file under `examples/ui-spec/contracts/` (a consumer app keeps
its own copies wherever its screens live). It is the document a person and an AI read
before writing the flow's screens in SwiftUI and Compose, and the document a reviewer
holds the two implementations against afterwards. Nothing draws a screen from it. What
IS derived from it, mechanically, is everything that can be: the service interfaces, the
screen models, the case table and one Maestro flow per cell — all from the one JSON block
at the end, whose fields are the ones `SCHEMA.md` already states.

This split is the decision of 2026-09-09 (UI D1, D2 and E3 as revised, E11). A screen
written from a layout grammar is a UI framework by another name; a screen written by an
implementer against a contract is native on each platform and still checkable. So the
document says what a screen must do, must show, and must not differ in between the two
platforms — and leaves how it is drawn to the implementer and the SDK's components.

`contracts/approveDevice.md` is the worked example. This page is the rule it follows.

## What a section is for, and who reads it

| Section | Reader | What it must settle |
| --- | --- | --- |
| Purpose | person, AI | why the flow exists, in two sentences, and what ends it |
| Entry | person, AI | how the flow is presented (push, modal, sheet + detent), and what closes the whole of it |
| Screens | person, AI | one subsection per screen, in stack order — see below |
| Shared rules | person, AI | the platform rules every screen in this flow inherits, named by the guide's rule id |
| Acceptance | person, AI, reviewer | the cells: what a person or a runner does, and what they must see. The automated ones are the generated table's rows; the by-hand ones are listed here |
| Machine block | generator | the JSON `SCHEMA.md` reads. Exactly one fenced block tagged `json spfn-ui` |

A section a flow does not need still appears, with the one line `none`. A blank
section is a section nobody wrote, and the difference matters to a reviewer.

## A screen's subsection

Every screen carries these headings, in this order:

| Heading | Content |
| --- | --- |
| Reads | the service method its `source` names, or `none`. The response type it shows |
| Shows | per state of its state type — `Loadable`: loading, ready, empty, error; `Busy`: idle, busy, error; `Paged`, `Form` when they exist — what is on screen and what a runner reads (`<name>=<value>` readouts, E10) |
| Inputs | each field: label, kind, rules, return key, autofocus. `none` for a screen without fields |
| Controls | each action: its identifier `<screen>.<action>` (E10), role, what it calls, where it goes (`then`), when it is disabled |
| Layout | the constraints that must hold on both platforms, stated as constraints and never as coordinates: header title centred, close on the right, primary control last in the body, fields above controls, body scrolls under a fixed header, keyboard never covers the focused field or the control under it |
| Behaviour | back (system, header, swipe), close, what a second press does, what happens when the flow closes with a call in flight (P24) |
| Same on both | the rules from the implementation guide this screen relies on, by id (`S1`, `S2` …), so the reviewer knows what to compare |

Layout is written as a list of constraints because that is what a reviewer can check on
two screenshots side by side and an implementer can satisfy with either toolkit. A pixel
value is a constraint only when the token that carries it is named (`space4`, not 16).

## Acceptance

Two lists.

- **Automated** — the ids of the generated table's cells for this flow, with the one
  line each that says what is asserted. They are copied out of
  `generated/device-approval.cases.md`, never invented here; the generator is the
  source and this list is the reader's index into it. A cell here that the table does
  not hold is a defect of the document.
- **By hand** — the rows a person walks on a phone, in the shape of
  `receipts/manual/TEMPLATE.md`: do, expect. These are the gestures and the looks a
  runner cannot judge (P22, P36, P39).

## Machine block

The last section. One fenced code block, tagged `json spfn-ui`, holding the flow's
part of the spec in the shape `SCHEMA.md` states: `specVersion`, `contract`, the
`services` it uses, its one entry under `flows`, and its `screens`. Until the generator
reads Markdown (N5), `device-approval.json` stays the generator's input and this block
must be equal to that file's part for the flow — `tools/ui-codegen` compares them when
N5 lands, and until then the sample's block was checked by hand against the file.

Two blocks, or none, is a refusal: a document that carries two truths carries none.

## Writing rules

- Say what must be the same on both platforms; do not say how to draw it on either.
- Name identifiers exactly as the machine block does; a reviewer greps for them.
- Every readout a runner waits on (`stack=`, `state=`, `fixture=`) is written in the
  Shows table, because it is part of the contract and not a debugging aid.
- A rule that turned out to differ between the platforms once (the 3-stage device
  round found five) is written into the guide as a numbered rule and referenced here,
  never re-explained.
