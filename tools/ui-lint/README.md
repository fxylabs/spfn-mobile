# tools/ui-lint

Android Lint checks for the UI rules that no compiler and no JVM unit test can hold. Each one
used to be a regular expression over Kotlin text in `tools/validate/validate.sh`; here it reads
lint's syntax tree, with calls resolved to what they call.

| Issue id | Rule | Why nothing else sees it |
|---|---|---|
| `SpfnBlanketPointerConsumption` | no loop over a pointer event's changes consumes each one with no decision about it | only a finger produces the MOVE that turns it into a cancelled press (P36) |
| `SpfnNavDisplayTransitions` | every `NavDisplay` is handed `transitionSpec`, `popTransitionSpec` and `predictivePopTransitionSpec` from `FlowTransitions` | a navigator's arguments are not readable outside a composition (P37) |
| `SpfnPagedViewInScrollingScreen` | a `PagedView` drawn in a `Screen`'s content is inside one that passes `scroll = false` | the crash is at layout time on a device; no JVM test composes a screen |

Applied with `lintChecks(project(":ui-lint"))` by `:spfn-ui`, `:example-compose` and
`:harness-android`; `tools/ci/android.sh` runs `lint` on all three. The two apps restrict lint
to these issues (`lint { checkOnly }`), so the built-in checks still reach the SDK modules only.

```sh
./gradlew :ui-lint:test          # each check refuses what it must and spares what it must
./gradlew :spfn-ui:lint          # the checks, applied
```
