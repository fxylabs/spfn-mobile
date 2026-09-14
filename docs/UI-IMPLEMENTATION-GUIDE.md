# Writing a flow's screens from its contract document

For whoever — a person or an AI — turns a contract document (`examples/ui-spec/CONTRACT.md`)
into SwiftUI and Compose screens. The generator has already written the flow's service
interface, screen models and tests from the document's machine block; what is left is the
views, and this page is the set of rules they are written and reviewed against. The rules
are numbered so a contract can name them.

## 1. Where the code goes — three layers (UI D6, D7, D9)

| Layer | What | Written by |
| --- | --- | --- |
| Services | one interface per service in the machine block, wrapping the contract's operations. **The only layer that calls the client** (D7; validate checks it) | generator (interface), app (implementation, fakes) |
| Screen models (+ optional use case) | one class per screen: its state (`Loadable`, `Busy`, `Paged`, `Form`), its actions, the flow it pushes and pops on. Constructor-injected (D9) | generator |
| Views | one composable / one `View` per screen, drawn out of the SDK's components, reading the model's state and calling its actions | **you** |

A view never calls a service and never holds state the model already holds. It reads
`model.state`, `model.stack`, `model.writing`, and calls `model.<action>()`. If a view
needs something the model does not expose, the model is what changes — through the
generator, not by hand.

## 2. Vocabulary — the same names on both platforms (UI D3, E7)

| | Swift (`SPFNUI`) | Kotlin (`spfn-ui`) |
| --- | --- | --- |
| Read state | `Loadable` — `.loading` `.ready(v)` `.empty` `.error(e)` | `Loadable.Loading` `Ready(v)` `Empty` `Error(e)` |
| Write state | `Busy` — `.idle` `.busy` `.error(e)` | `Busy.Idle` `Busy` `Error(e)` |
| Paged read state | `Paged` — `page` (a `Loadable` of the rows), `more` (a `Busy`), `hasMore`; `canLoadMore`, and the five transitions `firstPage` `firstPageFailed` `appending` `appended` `appendFailed` | same names |
| Form state | `Form` — `fields` (one `FieldError?` per field), `submit` (a `Busy`); `isValid`, `canSubmit`, and `check` `edited` `submitting` `submitted` `submitFailed` | same names |
| Field rules | `FieldError` — `.required` `.minLength(n)` `.maxLength(n)` `.kind(k)` `.custom(message:)`; `FieldRules`; `FieldValidator`; `FieldKind` — `.code` `.text` `.email` `.number` | same names |
| Flow | `Flow`, `FlowRoute`, `FlowHost`, `NavigationHost` | same names |
| Components | `Screen`, `PrimaryButton` `SecondaryButton` `DestructiveButton` `TextButton`, `SpfnText`, `SpfnTextField`, `StatusText`, `LoadableView`, `PagedView`, header icons — 11 | same names |
| Tokens, strings | `SPFNTokens` (20 keys), `SPFNStrings` (10 keys) | `SpfnTokens`, `SpfnStrings` |

validate section 13 compares the two sets. A screen that needs a component the SDK does
not have is a request to add one to the SDK on both platforms, not a one-off in the app.

A cursor is not in the table because it is not in `Paged`: `firstPage` and `appended` are
told what the next cursor is and keep only whether there WAS one. The value itself is the
model's, held privately next to the service it will be handed back to — this vocabulary is
what a screen SHOWS, and a screen never shows a cursor.

## 3. Identifiers and readouts (UI E10)

- Every control the contract lists carries `<screen>.<action>` as its accessibility identifier (iOS) / test tag (Android), spelled exactly as the contract does.
- Every readout the contract lists is a `SpfnText` in the mono role with the text `<name>=<value>` — `stack=2`, `state=ready`, `fixture=none`. Runners wait on these; they are part of the contract.
- Nothing else carries an identifier. An identifier a runner does not read is noise a reviewer has to explain.

## 4. Shared rules — what must be the same on both platforms

Each rule states the behaviour, names the SDK piece that gives it for free, and the
pitfall that recorded how it was once broken. A contract references rules by id.

| Id | Rule | Given by | Broken once as |
| --- | --- | --- | --- |
| S1 | The vocabulary and identifiers above, verbatim | validate 13 | — |
| S2 | The header stays fixed; the body scrolls under it; the way out never scrolls away | `Screen(scroll: true)` — except under a `PagedView`, where the two toolkits differ: `LazyColumn` brings its own scroll, so an Android paged screen says `Screen(scroll = false)`, while the iOS half draws a bare `LazyVStack` and the `Screen` keeps the scroll | `longScroll-headerHolds` |
| S3 | A modal flow arrives from the bottom and leaves to the bottom; a sheet stands at its detent (fit / half / full = 92 %) and rises and falls; its scrim darkens with it | `FlowHost`, `Sheet`, `SheetGeometry` | P34 (fit sheet stood full), P38 (sheet snapped instead of moving) |
| S4 | A pushed screen shows a back control leading in the header; it pops to the screen beneath, which is in the state it was left in | `Screen` header, `Flow.back` | — |
| S5 | A modal or sheet flow shows a close control trailing in the header on every screen; close empties the whole flow | `Screen` header, `Flow.close` | `modalTour-closeOnRight` |
| S6 | The system back (button, gesture, held predictive gesture) is the flow's own pop, above the last route; inside a sheet or a modal the same; a held gesture previews the screen beneath | `FlowHost`, `enableOnBackInvokedCallback` | P35 (no preview without the manifest flag), P30 |
| S7 | Inside a flow, forward slides in from the trailing edge and pop slides out to it, with the same slide inside a modal cover and a sheet as on a push | `FlowTransitions`, iOS system | P37 (modal popped with a scale-down) |
| S8 | Every control is at least the minimum touch target tall and answers a finger anywhere it is drawn — including a moving finger | button components, `contentShape`, no blanket pointer consumption | P21, P36 (a cover cancelled finger presses), P39 (only the label answered) |
| K1–K7 | The keyboard rules: the body avoids it, tap-outside dismisses, autofocus, return submits, editing clears the refusal, the refusal sits under the field | `Screen`, `SpfnTextField` | k-cells |
| R1–R8 | The model rules: empty required input refused before a call, second press ignored, write over an unloaded value ignored, then applies only on success, a source is read once per appearance, a refusal leaves the stack where it was, system back is the flow's pop | generated models, `CloseRulesTest` | u-cells |

The five defects the 3-stage device round found, and the rule that now covers each:

| Found by a person on a phone (2026-09-07/09) | Rule |
| --- | --- |
| A fit sheet stood at full height on Android | S3 (P34) |
| A held back gesture gave no preview on Android | S6 (P35) |
| The status bar was unreadable on a white screen on Android | S1 — theme is the SDK's (P35's neighbour) |
| Modal controls ignored a finger while a runner tap worked | S8 (P36) |
| A system back inside a modal shrank the screen away | S7 (P37) |
| A sheet appeared and vanished with no motion | S3 (P38) |
| An iOS button answered only on its label | S8 (P39) |

## 5. Pitfalls to read before writing a view

From `IMPLEMENTATION-PITFALLS.md`, the ones a view author meets: P21 (touch targets), P25
(controls below the fold leave the accessibility tree), P27 (SwiftUI ancestor gestures),
P29 (hiding the navigation bar kills the edge swipe), P34, P36, P39. Check the trigger table
there for anything else the change touches.

## 6. What "done" looks like

1. Both views compile and the flow's generated tests are green (`:spfn-ui`, `swift test`).
2. `sh tools/validate/validate.sh` — sections 13 and 16–20 pass.
3. `sh examples/ui-spec/run-cells.sh ios|android` — every automated cell of the contract leaves a receipt on a simulator/emulator and on a real Android phone.
4. The contract's by-hand rows walked on a phone of each platform and recorded in `receipts/manual/<date>.md`.
5. The two platforms' screenshots per cell placed side by side (N4, when it lands; until then the manual table's detent rows are the pattern) and every Layout constraint of the contract checked against both.

A view that satisfies 1–3 and fails 4 or 5 is not done: the runner presses the centre of
what it finds and reads only text, and the last three defects above were invisible to it.
