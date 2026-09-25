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
| Components | `Screen`, `PrimaryButton` `SecondaryButton` `DestructiveButton` `TextButton`, `SpfnText`, `SpfnTextField`, `StatusText`, `LoadableView`, `PagedView`, header icons — 11 | same names, and `WayOutButton` (Android only: a screen drawn with `ScreenHeader.None` draws the flow's back or close with it) |
| Screen header | `Screen(title:leading:principal:trailing:scroll:)` — every header argument optional; the three items go into the system navigation bar as `ToolbarItem`s, the back is the system's | `Screen(title, leading, principal, trailing, header, scroll)` — the same three slots in the SDK's header; `header = ScreenHeader.None` draws none (Android only) |
| Way out | `ScreenWayOut` — `wayOut`, `back()`, `close()`; read from `@Environment(\.screenWayOut)`, `ScreenWayOut.none` outside a `FlowHost` | `ScreenWayOut`, same names; read from `ScreenWayOut.current`, `ScreenWayOut.None` outside a `FlowHost` |
| Tokens, strings | `SPFNTokens` (21 keys — the default theme's source, read by no component), `SPFNStrings` (10 keys) | `SpfnTokens`, `SpfnStrings` |
| Theme | `SPFNTheme` — `light` `dark` (`SPFNPalette`), `typography` (`SPFNTypography`), `spacing` (`SPFNSpacing`), `radius` (`SPFNRadius`), `buttons` (`SPFNButtons` → one `SPFNButtonAppearance` per button kind); `SPFNTheme.default`; injected with `.spfnTheme(_:)`, read from `\.spfnTheme` | `SpfnTheme`, same keys; `SpfnTheme.Default`; injected with `SpfnTheme(theme) { … }`, read from `LocalSpfnTheme` |

validate section 13 compares the two sets; section 15 compares the tokens, the strings, the
component set and the theme's keys, and refuses a UI source that reads the tokens directly. A screen that needs a component the SDK does
not have is a request to add one to the SDK on both platforms, not a one-off in the app.

A cursor is not in the table because it is not in `Paged`: `firstPage` and `appended` are
told what the next cursor is and keep only whether there WAS one. The value itself is the
model's, held privately next to the service it will be handed back to — this vocabulary is
what a screen SHOWS, and a screen never shows a cursor.

## 3. Identifiers and readouts (UI E10)

- Every control the contract lists carries `<screen>.<action>` as its accessibility identifier (iOS) / test tag (Android), spelled exactly as the contract does.
- Every readout the contract lists is a `SpfnText` in the mono role with the text `<name>=<value>` — `stack=2`, `state=ready`, `fixture=none`. Runners wait on these; they are part of the contract.
- Nothing else carries an identifier. An identifier a runner does not read is noise a reviewer has to explain.
- The flow's own way out carries `screen.close` on both platforms and `screen.back` on Android. The iOS back is the system navigation bar's button, which carries no SDK identifier: a runner finds it by its accessibility label, the title of the screen it goes back to or the platform's "Back", and the generated cells do exactly that.

A `Paged` and a `Form` model **publish their readouts as strings** — `model.readouts` — and
your view draws one `SpfnText` per entry, in that order. The model computes them because there
is no generated view for either screen to compute them in, and a readout each implementer
spelled for themselves would be a case table asserting on text two apps write differently.
`Loadable` and `Busy` models do not have the property: their views are generated, and adding
it would rewrite every generated model in the repository for no claim.

| Readout | On | Values |
| --- | --- | --- |
| `more=` | `Paged` | `idle`, `busy`, `error` — what a FURTHER page is doing. Never the first page's state, which is `state=` |
| `count=` | `Paged` | how many rows are on screen, appended pages included. `0` until the first page arrives |
| `hasMore=` | `Paged` | `true` / `false` — whether the server said there is anything after what has been read |
| `fields=` | `Form` | `<field>:<rule>` per refused field, **sorted by field name**, comma separated; `ok` when none. The rules are `required`, `minLength`, `maxLength`, `kind`, `custom` |
| `item=` | one ROW of a `Paged` screen | whatever names a row. Which values exist is the contract document's to say, and the case table never asserts on it — it asserts on `count=` |

`fields=` is sorted because the two platforms' `Form.fields` are not one order: Swift's is a
`Dictionary` and Kotlin's is the rules' own insertion order, so a readout taken in the order it
was found would be two different readouts for one state.

A list screen's controls are `<screen>.retry` (the first page), `<screen>.retryMore` (the
footer) and `<screen>.reload`. Two retry ids and not one: both can be on screen at once, and a
runner asked for one id would refuse to pick. There is no "load more" control at all —
`PagedView` asks when the end of the rows is laid out.

## 4. Shared rules — what must be the same on both platforms

Each rule states the behaviour, names the SDK piece that gives it for free, and the
pitfall that recorded how it was once broken. A contract references rules by id.

| Id | Rule | Given by | Broken once as |
| --- | --- | --- | --- |
| S1 | The vocabulary and identifiers above, verbatim | validate 13 | — |
| S2 | The header stays fixed; the body scrolls under it; the way out never scrolls away. On iOS the header is the system navigation bar; on Android it is the SDK's, and a screen that turned it off (`ScreenHeader.None`) owns this rule for the header it draws | `Screen(scroll: true)` — except under a `PagedView`, where the two toolkits differ: `LazyColumn` brings its own scroll, so an Android paged screen says `Screen(scroll = false)`, while the iOS half draws a bare `LazyVStack` and the `Screen` keeps the scroll | `longScroll-headerHolds` |
| S3 | A modal flow arrives from the bottom and leaves to the bottom; a sheet stands at its detent (fit / half / full = 92 %) and rises and falls; its scrim darkens with it | `FlowHost`, `Sheet`, `SheetGeometry` | P34 (fit sheet stood full), P38 (sheet snapped instead of moving) |
| S4 | A pushed screen shows a back control leading in the header; it pops to the screen beneath, which is in the state it was left in. iOS: the system navigation bar's back button, with both of its swipes — the SDK neither draws a back nor turns a gesture on or off. Android: the SDK header's chevron, or `WayOutButton` on a screen with no SDK header | iOS system bar; Android `Screen` header, `WayOutButton`; `Flow.back` | P29, P32 (the hidden bar took the swipes with it) |
| S5 | The root of a modal or sheet flow shows a close control trailing in the header; close empties the whole flow. iOS: a trailing item in the system bar; Android: the SDK header's X, or `WayOutButton` | `Screen`, `WayOutButton`, `Flow.close` | `modalTour-closeOnRight` |
| S6 | The system back (button, gesture, held predictive gesture) is the flow's own pop, above the last route; inside a sheet or a modal the same; a held gesture previews the screen beneath | `FlowHost`, `enableOnBackInvokedCallback` | P35 (no preview without the manifest flag), P30 |
| S7 | Inside a flow, forward slides in from the trailing edge and pop slides out to it, with the same slide inside a modal cover and a sheet as on a push | `FlowTransitions`, iOS system | P37 (modal popped with a scale-down) |
| S8 | Every control is at least the minimum touch target tall and answers a finger anywhere it is drawn — including a moving finger | button components, `contentShape`, no blanket pointer consumption | P21, P36 (a cover cancelled finger presses), P39 (only the label answered) |
| K1–K7 | The keyboard rules: the body avoids it, tap-outside dismisses, autofocus, return submits, editing clears the refusal, the refusal sits under the field | `Screen`, `SpfnTextField` | k-cells |
| R1–R8 | The model rules: empty required input refused before a call, second press ignored, write over an unloaded value ignored, then applies only on success, a source is read once per appearance, a refusal leaves the stack where it was, system back is the flow's pop | generated models, `CloseRulesTest` | u-cells |
| P1–P9 | The paged rules: the first page's four states, an append, a failed append that leaves the rows alone, an append asked for twice, an append where there is nothing to append, a retry, and a reload that starts from the first page | generated models, `Paged` | P-cells |
| F1–F8 | The form rules: every field checked at once, each refused by the first rule it breaks, a second submit ignored while the write is in flight, editing one field clearing that field's refusal alone | generated models, `Form` | F-cells |
| C1 | Key custody is read, never inferred: where the phone has no hardware key store the SDK falls back to a software key **silently** and records `softwareKeychain` / the Android equivalent in the stored key; the app reads `SPFNKeyCustody` / `SpfnKeyCustody` from that record to decide what to tell the person, and the SDK itself shows nothing (decision of 2026-09-18, review step 5) | `SPFNStoredKey.custody`, `SpfnStoredKey.custody` | — |

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

## 7. Theming an app

An app gives the components its own look by injecting a theme; it never edits the SDK.
With nothing injected every component draws `SPFNTheme.default` / `SpfnTheme.Default`,
which is the tokens key for key, so an app that does not theme draws exactly what it drew
before themes existed.

**Build one** from the default and change what the design changes. A theme is a value:
the palettes per scheme, the four type roles, the six spaces, the two radii, and for each
of the four button kinds its container, content, border (colour and width), corner radius,
pressed container and disabled container / content / border — every colour once per
scheme.

```swift
let base = SPFNTheme.default
let brand = SPFNTheme(
    light: SPFNPalette(background: .white, surface: .init(white: 0.96), text: .black,
                       textSecondary: .gray, accent: .indigo, error: .red, handle: .gray),
    dark: base.dark,
    typography: base.typography,
    spacing: base.spacing,
    radius: base.radius,
    buttons: base.buttons
)
```

```kotlin
val brand = SpfnTheme.Default.copy(
    light = SpfnTheme.Default.light.copy(accent = Color(0xFF4B3FFF)),
    buttons = SpfnTheme.Default.buttons.copy(
        primary = SpfnTheme.Default.buttons.primary.copy(cornerRadius = 24.dp)
    )
)
```

A button's colours are not derived from the palette once a theme states them: changing
`accent` recolours the text button's words only if the appearance is rebuilt from it.
The default derives them from the default palettes; a brand theme states its own.

**Inject it** once, around the host:

| | Swift | Kotlin |
| --- | --- | --- |
| Inject | `NavigationHost { … }.spfnTheme(brand)` — a view modifier over an environment value | `SpfnTheme(brand) { NavigationHost { … } }` — a wrapper over `LocalSpfnTheme` |
| Read in the app's own views | `@Environment(\.spfnTheme) var theme` | `LocalSpfnTheme.current` |
| Nest | an inner `.spfnTheme` themes its subtree; the outer one stands everywhere else | an inner `SpfnTheme { }` likewise |

Compose takes a wrapper rather than a parameter on `FlowHost` or `NavigationHost` because a
theme is not navigation: a `Screen` composed outside any host draws with it just the same,
and the wrapper is what nests. The scheme is the platform's (`colorScheme`,
`isSystemInDarkTheme`); the theme only says what each scheme looks like.

The iOS navigation bar is themed by `Screen`, per screen, out of the same injected theme: the
title is `SpfnText` in the title role in the bar's centre, and the bar's background is the
palette's background through `.toolbarBackground`. Not `UINavigationBarAppearance`: the
theme's fonts are SwiftUI `Font`s, which do not convert to the `UIFont` an appearance takes,
and an appearance is either global to every bar in the app or reached per bar through UIKit.
An app that puts its own item in the centre (`principal`) draws the title itself.

**What is not themable**, on purpose:

- **Touch targets** — 44pt / 48dp, the header height and the icon size are `Metrics`, the
  platform's (on iOS the header height is only the allowance a `fit` sheet makes for the system
  bar); a theme that could shrink a control would bring back P21 (rule S8). A button's
  minimum height is a rule, not a value.
- **Motion** — the push, modal and sheet transitions are one set every navigator is handed
  (S7, validate section 18).
- **Sheet detents** — fit / half / full = 92 % and the scrim's opacity are `SheetGeometry`
  (S3); the sheet's corner radius, background and handle colour ARE the theme's.
- **The Android window** — the status bar's foreground and a modal cover's fill are the host
  app's window theme (`android:windowLightStatusBar`, `android:colorBackground`), declared in
  its `themes.xml` for the reason the example app's file gives. An app that injects a dark
  background declares a window to match.
- **Keyboard behaviour** (K1–K7) and the string keys.
