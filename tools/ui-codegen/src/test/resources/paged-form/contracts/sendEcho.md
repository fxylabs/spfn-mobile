# sendEcho

The other half of the fixture: the smallest specVersion 2 spec that carries a screen
collecting more than one field. Its second field is an INTEGER on the wire and text on the
screen, which is the case the generated model converts and can still refuse.

## Screens

### compose

| Heading | Content |
| --- | --- |
| Reads | none |
| Shows | `Form`: idle, busy, error, and one refusal per field. Readouts `stack=`, `state=`, `fields=` |
| Inputs | `message` text, at least two characters; `sequence` number |
| Controls | `compose.send` primary, `compose.cancel` text |
| Layout | fields above controls |
| Behaviour | a second press while the write is in flight does nothing |
| Same on both | S5, K1–K7 |

## Machine block

```json spfn-ui
{
  "specVersion": 2,
  "contract": { "manifestSha256": "29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c" },
  "services": {
    "echo": {
      "send": { "operation": "echoSend" }
    }
  },
  "flows": {
    "sendEcho": { "entry": "modal", "start": "compose", "views": "authored" }
  },
  "screens": {
    "compose": {
      "flow": "sendEcho",
      "source": null,
      "title": "Say something",
      "inputs": {
        "message": {
          "kind": "text",
          "label": "Message",
          "rules": { "minLength": 2, "maxLength": 140 }
        },
        "sequence": {
          "kind": "number",
          "label": "Sequence",
          "submitOnReturn": true,
          "rules": { "required": true }
        }
      },
      "actions": {
        "send": { "call": "echo.send", "then": "close", "role": "primary" },
        "cancel": { "then": "close", "role": "text" }
      }
    }
  }
}
```
