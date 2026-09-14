# browseItems

A fixture, not a shipped flow. It is the smallest specVersion 2 spec that carries a screen
reading a page at a time, written as a contract document because `views: authored` is only
writable in one — which is itself the rule a list screen exists to exercise.

The prose here is deliberately thin. What the generator reads is the block, and what this
fixture is FOR is the block: `SpecRefusalTest` generates it and reads the emitted models.

## Screens

### items

| Heading | Content |
| --- | --- |
| Reads | `catalogue.list`, a page at a time. `ListItemsResponse` |
| Shows | `Paged`: loading, ready with rows, empty, error; the footer's own idle, busy and error. Readouts `stack=`, `state=`, `more=`, `count=`, `hasMore=` |
| Inputs | none |
| Controls | `items.retry` first page, `items.retryMore` footer, `items.reload`, `items.done` |
| Layout | the header is fixed and the rows scroll under it |
| Behaviour | the first page is read once when the screen appears; the end of the rows asks for the next |
| Same on both | S2 |

## Machine block

```json spfn-ui
{
  "specVersion": 2,
  "contract": { "manifestSha256": "29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c" },
  "services": {
    "catalogue": {
      "list": { "operation": "itemsList" }
    }
  },
  "flows": {
    "browseItems": { "entry": "push", "start": "items", "views": "authored" }
  },
  "screens": {
    "items": {
      "flow": "browseItems",
      "source": "catalogue.list",
      "title": "Everything there is",
      "scroll": false,
      "list": {
        "items": "items",
        "next": "nextCursor",
        "cursor": "cursor",
        "limit": { "field": "limit", "value": 20 }
      },
      "actions": {
        "done": { "then": "close", "role": "text" }
      }
    }
  }
}
```
