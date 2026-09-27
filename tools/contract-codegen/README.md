# `:contract-codegen`

Generates the Swift and Kotlin clients from the pinned contract bundle, in one run.

```sh
./gradlew :contract-codegen:spfnGenerateClients   # rewrite the generated sources
./gradlew :contract-codegen:spfnCodegenVerify     # fail if they are not up to date
```

`spfnCodegenVerify` is wired into `check`, so `./gradlew build` fails when a generated
file has been hand-edited or the bundle changed without regeneration.

## Boundary

A non-published Kotlin/JVM Gradle module. It lives inside the JDK/Gradle toolchain
Android already requires, so the repository does not acquire a second toolchain — Node,
pnpm and Turbo are deliberately not the root build system here. Its own JSON reader is
hand-written for the same reason: zero external dependencies.

## Contract

- **Input:** the bundle named by `Contracts/upstream.lock.json`'s `contract.bundlePath`,
  read through `ContractPin` — which `:ui-codegen` shares rather than copying — and
  described by `Contracts/upstream-provenance.json`, and nothing else.
- **Output:** `Sources/SPFNGenerated/Generated/` (Swift) and
  `android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated/` (Kotlin),
  produced in the same run from the same input.
- **Digest gate:** the generator recomputes the bundle's SHA-256 and refuses to run when
  it does not match the `contract.bundleSha256` the upstream evidence records. A generated
  header that names a digest was therefore demonstrably produced from a file with that
  digest.
- **Zero network:** generation reads three files from disk — the pin's two halves and the
  bundle they name between them. Nothing is fetched.
- **Deterministic:** output is a pure function of the bundle bytes. No timestamp, no
  host name, no absolute path, no unordered map iteration. Two consecutive runs produce
  byte-identical files.

## What each output file holds

| File | Contents |
| --- | --- |
| `SPFNGeneratedContract.swift` / `SpfnGeneratedContract.kt` | the contract binding: version, bundle digest, supported range, origin, operation ids, replay window, proof-input field order |
| `SPFNGeneratedTypes.swift` / `SpfnGeneratedTypes.kt` | one struct per contract type, with canonical encoding and strict decoding |
| `SPFNGeneratedOperations.swift` / `SpfnGeneratedOperations.kt` | one operation per contract operation — id, method, path, auth class — plus lookup by contract id |
| `SPFNGeneratedCalls.swift` / `SpfnGeneratedCalls.kt` | one call descriptor per operation: the operation paired with the codecs for its request and response types, ready to hand to `execute` |
| `SPFNGeneratedErrors.swift` / `SpfnGeneratedErrors.kt` | every contract error code with its HTTP status and retryability |

Generated directories hold generated files only. A leftover from an earlier contract is
deleted on the next write and reported as stale by `spfnCodegenVerify`, because a
compiling ghost from a previous contract is worse than a missing file.

## Why the generated code is thin

Field readers, canonical serialization and digests live in `SPFNCore` / `spfn-core`,
hand-written and reviewed once. The generated files are a listing of the contract, not
a place where logic hides. Reviewing a contract change should mean reading a diff of
names and types.

## An app's own contract document

The same module generates calls for an app's own routes from the contract document the
app's server build writes (`@spfn/core:contract`, `documentVersion: 1`). The design,
the type mapping table and the naming rules are
[docs/architecture/app-contract-codegen.md](../../docs/architecture/app-contract-codegen.md).

```sh
./gradlew :contract-codegen:spfnAppContractGenerate \
    -Pspfn.appContract.document=/abs/app/contracts/current.json \
    -Pspfn.appContract.operations=/abs/app/mobile/contract-operations.json \
    -Pspfn.appContract.swiftOut=/abs/app/ios/Generated/API \
    -Pspfn.appContract.swiftNamespace=AppAPI \
    -Pspfn.appContract.kotlinOut=/abs/app/android/src/main/kotlin/com/example/generated/api \
    -Pspfn.appContract.kotlinPackage=com.example.generated.api

./gradlew :contract-codegen:spfnAppContractVerify   # same six properties
```

| Property | Meaning |
| --- | --- |
| `document` | the app's contract document |
| `operations` | `{ "operations": ["getItem", …] }` — the operations the app calls; a name the document does not declare is refused |
| `swiftOut`, `swiftNamespace` | the Swift directory, and the `public enum` every call and type is nested in |
| `kotlinOut`, `kotlinPackage` | the Kotlin directory and package; the calls are `object <swiftNamespace>` |

Every property is required and every path absolute. A missing one fails by name — the
tasks never fall back to a default, because a misspelt property that silently checked
something else would read as green.

**Output.** Three files per platform: `<Namespace>.swift` / `<Namespace>Support.kt` (the
namespace and the percent-encoding and decoding helpers), `<Namespace>Types` (one type per
object and string set) and `<Namespace>Calls` (one function per operation returning an
`SPFNCall` / `SpfnCall`). An app sends a call the way it sends a core one:

```swift
let answer = try await client.execute(AppAPI.getItem(itemId: id), request: ())
```

```kotlin
val answer = client.execute(AppAPI.getItem(itemId = id), Unit)
```

Errors are the SDK's own `SPFNClientError` / `SpfnClientError`; a response that does not
match the document is `notTheDeclaredResponse` / `NOT_THE_DECLARED_RESPONSE`.

**Provenance.** Every generated file's header records the document's SHA-256 and the
selected operations. `spfnAppContractVerify` fails when the document's digest differs from
the one a header records, when a file differs from a fresh generation, and when an output
directory holds a file the generator did not write (hidden files such as `.DS_Store`
aside). Output directories hold generated files only; `spfnAppContractGenerate` deletes
anything else in them.

**Same boundary as above.** Zero network — the document, the selection and this
repository's pinned contract (for the auth classes the execute path accepts) are read
from disk — and deterministic: output depends on neither the document's key order nor the
selection's order. No SDK module is involved in generation, and none gains a dependency.

**Fixture.** `spfnAppContractFixtureGenerate` / `spfnAppContractFixtureVerify` run the same
generator over the invented document in `src/test/resources/app-contract/`, into
`android/spfn-core/src/test/kotlin/xyz/superfunction/spfn/core/appcontract/` and
`Tests/SPFNCoreTests/AppContractFixture/`. The fixture verify is wired into `check`.
