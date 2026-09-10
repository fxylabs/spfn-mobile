# approveDevice — a person approves a device that is signing in with a code

Written to `../CONTRACT.md`. The machine block at the end is the `approveDevice` part
of `../device-approval.json` and must stay equal to it until the generator reads this file.

## Purpose

Another device has started a sign-in and is showing a short code. On this phone, a
signed-in person types that code, reads what the device is, and approves or denies it.
The flow ends when a write succeeds or the person leaves; nothing in it is remembered.

## Entry

| | |
| --- | --- |
| Presented as | `modal` — a cover over the host, arriving from the bottom (S3) |
| Starts on | `enterCode` |
| Closed as a whole by | the header's close control on any screen (S5), a `then: close` action, or the system back on the first screen |
| Deep entry | `reviewDevice` may be opened directly with a `userCode` (cell u14); it reads its source on arrival like any other appearance |

## Screens

### enterCode

**Reads** — none. Its state type is `Busy`.

**Shows**

| State | On screen | Runner readouts |
| --- | --- | --- |
| idle | title "Approve a device", the code field, the two controls | `stack=1`, `state=idle` |
| busy | the same, the primary control shows its busy indicator and ignores presses | `state=busy` |
| error | the same, the refusal drawn UNDER the field (`errorValidation` for an empty code, the envelope's message otherwise) | `state=error` |

**Inputs**

| Field | Label | Kind | Rules | Return key | Autofocus |
| --- | --- | --- | --- | --- | --- |
| `userCode` | "Code from the device" | `code` (monospace, no autocorrect, no capitalisation) | required | `go` — performs `submit` | yes |

**Controls**

| Identifier | Role | Calls | Then | Disabled when |
| --- | --- | --- | --- | --- |
| `enterCode.submit` | primary | `deviceApproval.lookup(userCode)` | push `reviewDevice` | busy |
| `enterCode.cancel` | text | — | close | never |

**Layout**
- Header: title centred, close control on the trailing side (S5), no back control (first screen).
- Body: field first, then `submit`, then `cancel`; spacing `space4`; body scrolls under the fixed header (S2).
- Keyboard: the field and the control under it stay visible with the keyboard up (K1); a tap on the body outside the field puts the keyboard away and changes nothing else (K2).
- Every control is at least `touchTarget` tall and answers a tap anywhere it is drawn (S8).

**Behaviour**
- Editing the field clears the refusal under it (K6).
- A second press of `submit` while one is in flight is ignored (R2).
- An empty code is refused before anything is sent (R1); the screen stays usable (u6).
- System back on this screen closes the flow (it is the first screen).

**Same on both** — S1 S2 S3 S5 S8 K1 K2 K6.

### reviewDevice

**Reads** — `deviceApproval.lookup(userCode)` → `DeviceAuthInfoResponse`. State type `Loadable`. The screen loads once per appearance, however it appeared (R6).

**Shows**

| State | On screen | Runner readouts |
| --- | --- | --- |
| loading | title "Review the device", `stateLoading` text, controls present but writes ignored (R3) | `stack=2`, `state=loading` |
| ready | what the device is (the response's fields, as text), then the controls | `state=ready` |
| empty | `stateEmpty` text | `state=empty` |
| error | the envelope's message and a retry control `reviewDevice.retry` | `state=error` |

**Inputs** — none.

**Controls**

| Identifier | Role | Calls | Then | Disabled when |
| --- | --- | --- | --- | --- |
| `reviewDevice.approve` | primary | `deviceApproval.approve(userCode)` | close | not ready, or a write in flight |
| `reviewDevice.deny` | destructive | `deviceApproval.deny(userCode)` | close | not ready, or a write in flight |
| `reviewDevice.retry` | (LoadableView's own) | `deviceApproval.lookup(userCode)` | stays | never |
| `reviewDevice.back` | text | — | pop | never |

**Layout**
- Header: title centred, back control leading (S4), close control trailing (S5).
- Body: the loaded content first, then `approve`, `back`, `deny` in that order with `space4` between; scrolls under the header.
- The destructive control is drawn in the `error` colour token and is never the first control.

**Behaviour**
- Header back, `back`, the system back button and the edge swipe all pop to `enterCode`, which is idle again (u7, u7b, u10, u10b; S4, S6).
- A write that succeeds closes the whole flow (u8, u9); one that fails leaves the screen in `error` with the stack where it was (R7).
- A write whose flow closed before it returned writes nothing into the screen (P24).
- A held predictive back on Android previews `enterCode` underneath (S6); iOS's edge swipe is the system's.

**Same on both** — S1 S2 S4 S5 S6 S7 S8.

## Shared rules

S1 vocabulary and identifiers · S2 fixed header, scrolling body · S3 modal cover motion · S4 back control and pop · S5 close control and close · S6 back gesture parity · S7 inner transitions slide · S8 touch targets and hit areas. Defined in `docs/UI-IMPLEMENTATION-GUIDE.md`.

## Acceptance

**Automated** (rows of `../generated/device-approval.cases.md`; `both` runs on the unit suite and on a device, `unit` on the unit suite only, `maestro` on a device only)

| Cell | Asserts |
| --- | --- |
| u1 | submit with a code → `stack=2`, `state=ready` |
| u2 | submit with an empty code → refused, `stack=1`, `state=error` |
| u3 | second submit while busy is ignored (unit) |
| u4 | the lookup fails → `state=error`, stack unchanged |
| u5 | cancel → `stack=0` |
| u6 | refused, then submit again → proceeds |
| u7, u7b | back / system back from `reviewDevice` → `stack=1`, `enterCode` idle |
| u8, u9 | approve / deny succeed → `stack=0` |
| u10, u10b | back / system back from an `error` `reviewDevice` → same pop |
| u11 | approve while loading is ignored (unit) |
| u12 | retry after a refused source → `state=ready` |
| u13 | the source refuses → `state=error`, `stack=2` |
| u14 | deep entry onto `reviewDevice` → it loads, `state=ready` |
| k1–k7 | the keyboard: body avoids it, tap-outside dismisses, autofocus, return submits, editing clears the refusal, the refusal is under the field |

**By hand** — none beyond the showcase table's rows; this flow's gestures are covered by the `modalTour` rows (`modalTour-closeOnRight`, `modalTour-fingerTap`, `modalTour-predictiveBack`), which stand in for every modal flow.

## Machine block

```json spfn-ui
{
  "specVersion": 1,
  "contract": { "manifestSha256": "29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c" },
  "services": {
    "deviceApproval": {
      "lookup": { "operation": "authDeviceInfo" },
      "approve": { "operation": "authDeviceApprove" },
      "deny": { "operation": "authDeviceDeny" }
    }
  },
  "flows": {
    "approveDevice": { "entry": "modal", "start": "enterCode" }
  },
  "screens": {
    "enterCode": {
      "flow": "approveDevice",
      "source": null,
      "title": "Approve a device",
      "inputs": {
        "userCode": { "kind": "code", "label": "Code from the device", "submitOnReturn": true, "autofocus": true }
      },
      "actions": {
        "submit": { "call": "deviceApproval.lookup", "then": { "push": "reviewDevice" }, "role": "primary" },
        "cancel": { "then": "close", "role": "text" }
      }
    },
    "reviewDevice": {
      "flow": "approveDevice",
      "source": "deviceApproval.lookup",
      "usecase": true,
      "title": "Review the device",
      "actions": {
        "approve": { "call": "deviceApproval.approve", "then": "close", "role": "primary" },
        "deny": { "call": "deviceApproval.deny", "then": "close", "role": "destructive" },
        "retry": { "call": "deviceApproval.lookup" },
        "back": { "then": "pop", "role": "text" }
      }
    }
  }
}
```
