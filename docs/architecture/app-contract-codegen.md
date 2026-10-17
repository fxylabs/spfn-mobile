# 설계 — 앱 계약 문서에서 모바일 호출을 생성한다

- 상태: 구현됨 (2026-09-27). [custom-route-contract-design.md](custom-route-contract-design.md)의 작업 6이다 — "앱 계약을 읽는 모바일 코드젠 경로".
- 대상: `tools/contract-codegen`의 새 입력(앱 계약 문서)과 Gradle 태스크 두 쌍. SDK 모듈은 바뀌지 않는다.
- 관련: [custom-route-contract-design.md](custom-route-contract-design.md) D-C·D-D·§7, [tools/contract-codegen/README.md](../../tools/contract-codegen/README.md), [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P7·P8·P9·P10

## 0. 요약

| 항목 | 결정 |
|---|---|
| 입력 | 앱이 커밋한 `@spfn/core:contract` 문서(`documentVersion: 1`)와, 앱이 부르는 연산 이름 목록. 둘 다 앱 저장소의 파일이다 (D-C) |
| 출력 | Swift 파일 3개와 Kotlin 파일 3개. 디렉터리, Swift 네임스페이스, Kotlin 패키지는 소비자가 넘긴다. 선택에 `getStates`가 있으면 상태 서술 파일이 하나씩 더 생긴다 (§8) |
| 실행 경로 | 생성된 호출은 `SPFNCall` / `SpfnCall` 값이다. 앱은 코어 호출과 똑같이 `SPFNClient.execute`에 넘긴다. 새 오류 타입도 새 실행 경로도 없다 |
| 문법 | JSON Schema 중 TypeBox가 내는 부분집합. §2 표 밖의 구성은 **이름을 대고 거부한다** (P8) |
| 호환 | 모르는 enum 값은 `unknown(String)`, 모르는 필드는 무시, 빠진 optional은 nil. 시각은 `String` 그대로 |
| 출처 | 파일 머리에 문서 SHA-256과 선택된 연산. verify 태스크가 문서 digest와 새로 생성한 결과를 모두 대조한다 |

## 1. 입력

### 1-1. 문서

```json
{
  "compatibilityPolicy": "perOperation",
  "documentVersion": 1,
  "operations": [
    { "name": "getItem", "method": "GET", "path": "/v1/items/:itemId",
      "auth": "clientProofV1", "requiresSession": false, "since": "1.0.0", "interceptor": {},
      "request": { "params": { "type": "object", "properties": { "itemId": { "type": "string" } }, "required": ["itemId"] } },
      "response": { "type": "object", "properties": { … }, "required": [ … ] } }
  ]
}
```

- `documentVersion`이 `1`이 아니면 그 값을 대고 거부한다. 최상위에 모르는 키가 있어도 거부한다. 예외는 `events` 하나다: spfn은 서버가 이벤트를 계약하면 (상태의 `.contract()`는 `state.<id>.changed`를 계약한다) 이 절을 쓰고, 생성기는 받아들이되 **읽지 않는다** — 내용을 무엇에 쓸지는 Q-B·spfn #111이 정한다 ([state-client-design §5-2](state-client-design.md#5-2-다른-곳의-전제) P-a).
- 연산 이름이 문서 안에서 겹치면 거부한다.
- **선택된 연산만 깊이 읽는다.** 앱이 부르지 않는 연산의 스키마에 이 생성기가 못 여는 구성이 있어도 생성은 통과한다. 선택되지 않은 연산이 바뀌었다고 앱 빌드가 깨지지는 않는다 — 다만 문서 digest가 바뀌므로 재생성은 필요하다(§5).
- JSON 리더는 코어 번들 리더와 같은 손으로 쓴 리더다. 앱 문서를 읽을 때만 소수(`"minimum": 0.5`)를 받아들인다. 받아들인 소수는 어떤 타입으로도 방출하지 않는다.

### 1-2. 연산 선택

```json
{ "operations": ["getItem", "listItems"] }
```

- 문서에 없는 이름, 목록 안의 중복, 빈 목록은 이름을 대고 거부한다. 모르는 키도 거부한다.
- 선택 목록의 순서는 출력에 영향이 없다. 출력은 연산 이름 순이다.
- `getStates`를 선택하면 그것이 대는 상태의 `get<Id>State`·`put<Id>State`가 함께 선택된다 (§8). 앱이 그것을 따로 적어도 오류가 아니다.

### 1-3. 키 순서에 기대지 않는다

문서의 객체 키 순서는 생성 결과를 바꾸지 못한다. `properties`는 이름 순으로 방출하고, `required`는 집합으로 읽는다. 배열 순서는 의미가 있으므로 그대로 둔다 — `anyOf`의 상수 순서가 enum case 순서다.

## 2. 타입 매핑 표

"응답"은 디코드만, "본문"은 인코드만 생성한다. 한 타입이 두 쪽에 쓰이는 일은 없다 — 이름이 `<Op>Response…`와 `<Op>Body…`로 갈린다(§3).

필수(required)와 널 허용(nullable)은 서로 다른 축이다.

| 축 | JSON Schema | 뜻 |
|---|---|---|
| 필수 | 속성 이름이 `required`에 있다 | 키가 **반드시 온다** |
| 널 허용 | `anyOf: [T, {type: "null"}]` (순서 무관) | 값이 `null`일 수 있다 |

### 2-1. 스칼라와 구조

| # | JSON Schema | Swift | Kotlin | 비고 |
|---|---|---|---|---|
| T1 | `{type: string}` | `String` | `String` | `format`(`date-time` 포함)은 무시한다. 시각은 `String`으로 남는다 — 생성 코드에 날짜 파싱은 없다 |
| T2 | `{type: integer}` | `Int64` | `Long` | 53비트를 넘는 정수는 걱정거리가 아니다. 서버(JS)가 2^53 너머를 정확히 보낼 수 없고, SDK의 와이어 리더는 부호 있는 64비트를 싣는다 |
| T3 | `{type: number}` | 거부 | 거부 | SDK의 와이어 리더(`SPFNCanonicalJSON` / `SpfnCanonicalJson`)는 정수만 읽는다. 소수 하나가 **응답 전체**를 `NON_INTEGER_NUMBER`로 만든다. 방출하면 컴파일되는 거짓말이다 |
| T4 | `{type: boolean}` | `Bool` | `Boolean` | |
| T5 | `{type: object, properties, required}` | `struct` (`Equatable, Sendable`) | `data class` | 속성은 이름 순. `additionalProperties`가 없거나 `true`/`false`면 받는다 |
| T6 | `{type: array, items: <스칼라>}` | `[String]` 등 | `List<String>` 등 | |
| T7 | `{type: array, items: <객체>}` | `[<Name>Item]` | `List<<Name>Item>` | 원소 타입 이름은 §3 |
| T8 | 필수 + 널 허용 | `T?` | `T?` | 응답: 키가 없으면 `MISSING_FIELD`, `null`이면 nil — 서버가 키를 약속했으니 빠짐은 불일치다. 본문: nil은 **명시적 `null`로 인코드**하고, 생성자 인자에 기본값이 없다 |
| T9 | 선택(`required`에 없음) | `T?` | `T?` | 응답: 없음과 `null` 모두 nil. 본문: nil이면 키를 **생략**하고, 생성자 인자 기본값은 nil |
| T10 | 선택 + 널 허용 | 응답 `T?` / 본문 거부 | 응답 `T?` / 본문 거부 | 응답은 T9와 같다(둘 다 "값 없음"). 본문과 쿼리는 "보내지 않음"·"null로 지움"·"값"의 세 상태를 담을 타입이 필요한데 두 언어의 `T?`는 두 상태뿐이다. 조용히 한쪽으로 접으면 PATCH가 뜻을 잃으므로 거부한다 |
| T11 | `anyOf`가 문자열 `const`만 (+ 선택적으로 `null`) | `enum` + `case unknown(String)` | `sealed interface` + `data class Unknown(wireValue)` | 열린 enum. §4 |
| T12 | 홀로 선 문자열 `const` | case 하나짜리 열린 enum | 같음 | 문자열이 아닌 `const`는 거부 |
| T13 | 객체 안의 객체 | 이름 붙은 중첩 타입 | 같음 | 이름은 §3 |

### 2-2. 요청과 응답의 자리

| # | 자리 | 생성 결과 | 비고 |
|---|---|---|---|
| T14 | `request.params` | 호출 함수의 인자(경로에 나오는 순서). 값은 경로에 퍼센트 인코딩해 넣는다 | 속성은 `string` / `integer`만, 모두 필수. 속성 집합이 경로의 `:name` 집합과 정확히 같아야 한다. 인코딩: RFC 3986 unreserved(`A-Z a-z 0-9 - . _ ~`)만 그대로, 나머지는 UTF-8 바이트마다 `%XX`(대문자). 값이 통째로 `.`이나 `..`이면 점도 인코딩한다 — 경로 정규화가 세그먼트를 먹지 못하게 |
| T15 | `request.query` | 호출 함수의 인자(이름 순, 선택 인자는 기본값 nil). 경로가 아니라 호출(`SPFNCall.query` / `SpfnCall.query`)에 `(이름, 값)` 쌍으로 실리고, SDK가 `?k=v&…`로 보낸다. nil은 생략 | 스칼라(`string`·`integer`·`boolean`·문자열 enum)만. `auth: none`과 `clientProofV1` 연산에서 똑같이 생성한다 — SDK는 `SPFNOperation.path`만 서명하고 경로 + 쿼리(`pathAndQuery`)를 보내므로, 쿼리를 뺀 경로로 검증하는 서버와 서명이 맞는다 (`Contracts/fixtures/request/wire.json`의 `queryVectors`) |
| T16 | `request.body` | `<Op>Body` 구조체가 호출의 Request 타입 | 객체만. `GET`의 본문은 거부 — OkHttp는 GET에 본문을 싣지 못한다(`SpfnOkHttpTransportTest`가 고정) |
| T17 | 요청 없음 | Request는 `Void` / `Unit`, 인코드는 `nil` / `null` — 본문 없음 | 코어의 `requestType` 없는 GET과 같은 모양. 실행 경로는 본문 바이트도 `content-type`도 보내지 않고, 증명은 absent-body digest(0 64개)를 서명한다 — 번들 `clientProofV1.proofInput.bodySha256`, 픽스처 `proof-input.json`의 `handshake-no-body`. 메서드가 아니라 요청 타입이 없다는 사실이 기준이므로 DELETE도 같다. 선언된 본문이 빈 객체이면 `{}`를 그대로 보낸다 |
| T18 | `response: {type: object}` | `<Op>Response`, 디코드 | |
| T19 | `response: {type: null}` | `SPFNNoResponse` / `SpfnNoResponse`, `noResponse` 팩토리, `declaresResponse: false` | SDK는 이 경우 204 + 빈 본문을 요구한다 — 코어 계약 0.10.0의 규칙 그대로 |

### 2-3. 거부 (이름을 대고 멈춘다)

| # | 구성 | 이유 |
|---|---|---|
| R1 | `type`이 없는 스키마 (`Type.Unknown`, `Type.Any`, `{}`) | 무엇을 방출할지 모른다. 추측하면 P8 |
| R2 | `additionalProperties: <스키마>`, `patternProperties` | 맵. D-D가 열기로 한 문법이지만 인코딩이 합의되지 않았다 — 코어 생성기와 같은 판단 |
| R3 | 문자열 상수가 아닌 것들의 `anyOf` (객체 유니온 포함), `oneOf`, `allOf`, `not`, `enum` | 두 언어의 표현이 갈린다 (D-D "열지 않는 것") |
| R4 | `$ref`, `$id` (재귀·참조) | D-D. 깊이 64를 넘는 중첩도 같은 이유로 거부 |
| R5 | `type`이 배열 (`["string", "null"]`), 튜플 `items` | TypeBox가 내지 않는 모양. 받으면 표가 닫히지 않는다 |
| R6 | 널 허용 배열 원소, 응답 루트가 객체도 null도 아님 | 표 밖 |
| R7 | 그 밖의 모르는 키워드 | 주석·검증 키워드(`description`, `title`, `default`, `examples`, `format`, `pattern`, `minLength`, `maxLength`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minItems`, `maxItems`, `uniqueItems`)만 무시한다. 클라이언트는 검증하지 않는다 — 서버가 한다 |
| R8 | 식별자가 아닌 속성 이름·enum 값 (ASCII `[A-Za-z_][A-Za-z0-9_]*`, enum 값은 `-`·`.`·공백도 구분자로 허용) | P9 — 두 언어의 문자 분류는 non-ASCII에서 갈린다. ASCII로 못 박는다 |
| R9 | 이름 충돌 (§3) | 컴파일 오류가 아니라 생성기 오류로 |
| R10 | 연산 모양: 모르는 연산 키 (`deprecatedIn`·`removedIn`은 안다: `deprecatedIn`이 있으면 호출에 `@available(*, deprecated, message:)` / `@Deprecated`를 단다. `removedIn`은 아무것도 내지 않는다 — 호출을 지우지 않는다. 버전은 `[0-9A-Za-z.+-]+`가 아니면 거부), 모르는 `request` 키(`headers`, `cookies` 등), `auth`가 SDK 고정 계약이 선언하지 않은 클래스, `auth: none`인데 `requiresSession: true`, 경로가 `/`로 시작하지 않거나 `//`·`?`·`#`·끝 `/`를 가짐 | 실행 경로가 받지 못하는 설명자 |

## 3. 이름

| 대상 | 규칙 | 예 |
|---|---|---|
| 호출 | 연산 이름 그대로 | `getItem` → `getItem(itemId:)` |
| 응답 | `<Op>Response` (`Op`는 첫 글자 대문자) | `GetItemResponse` |
| 본문 | `<Op>Body` | `PutItemNoteBody` |
| 중첩 객체 | 부모 이름 + 속성 이름(Pascal) | `GetItemResponse` 의 `owner` → `GetItemResponseOwner` |
| 배열 원소 | 배열의 이름 + `Item` | `ListItemsResponse`의 `items` → `ListItemsResponseItemsItem` |
| enum | 객체와 같은 경로 규칙 | `ListItemsResponseItemsItemKind` |
| enum case | 값을 `_` `-` `.` 공백으로 나눠 Swift는 lowerCamel, Kotlin은 Pascal | `in_review` → `.inReview` / `InReview` |
| Pascal 변환 | 속성 이름을 `_`·`-`로 나누고 각 조각의 첫 글자만 대문자 | `head_seq` → `HeadSeq` |

**단수화하지 않는다.** `items` → `Item`은 쉬워 보이지만 `statuses`, `data`, `children`에서 영어 규칙이 필요하고, `item`과 `items`가 한 객체에 같이 있으면 충돌한다. `Item` 접미사는 기계적이고 결과를 예측할 수 있다. 이름이 길어지는 대가는 받는다.

**키워드.** Swift 키워드(`default`, `in`, `is`, `self` …)와 Kotlin 하드 키워드(`in`, `is`, `object`, `fun` …)는 백틱으로 감싼다. 와이어 이름은 바뀌지 않는다.

**충돌은 거부한다.** 생성되는 모든 타입 이름, 한 enum 안의 case 이름(그리고 예약된 `unknown` / `Unknown`), 한 호출의 인자 이름(경로 인자와 쿼리 인자), 한 객체의 Pascal 조각이 겹치면 겹친 이름과 두 출처를 대고 멈춘다. 네임스페이스 이름과 겹치는 타입 이름도 같다.

## 4. 앞으로의 서버를 견디는 디코드

이미 스토어에 나간 앱은 다시 컴파일되지 않는다(D-F). 서버가 늘어나는 것은 앱을 깨지 않아야 한다.

| # | 서버가 한 일 | 생성된 디코더 |
|---|---|---|
| F1 | 닫힌 문자열 집합에 값을 더함 | `unknown("새값")` / `Unknown("새값")`. 응답 전체가 실패하지 않는다. 코어 enum의 엄격한 디코드와 다르다 — 코어는 SDK와 서버가 함께 버전이 오르고, 앱 계약은 그렇지 않다 |
| F2 | 응답에 필드를 더함 | 무시 |
| F3 | 선택 필드를 보내지 않음 | nil |
| F4 | 시각 필드 | `String` 그대로 |

한계: SDK 와이어 리더는 응답 **어디에든** 소수가 있으면 응답 전체를 거부한다. 이 앱이 모르는 새 필드라도 그렇다. 서버가 선택된 연산의 응답에 `number` 필드를 더하는 것은 파괴적 변경이다.

## 5. 출처와 verify

생성된 파일마다 머리에 다음이 들어간다. 절대 경로, 시각, 호스트 이름은 없다.

```
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  <문서 바이트의 SHA-256>
// documentVersion: 1
// operations:      getItem, listItems
```

verify는 두 가지를 순서대로 본다.

1. 출력 디렉터리의 각 파일 머리의 `documentSha256`이 지금 문서의 digest와 같은가. 다르면 "문서가 바뀌었는데 재생성하지 않았다"를 이름으로 말한다.
2. 새로 생성한 결과와 바이트 단위로 같은가. 다르면 손으로 고친 파일이다. 출력 디렉터리에 생성되지 않은 파일이 있으면 그것도 실패다(코어 생성기와 같은 규칙).

## 6. 태스크

| 태스크 | 입력 | 용도 |
|---|---|---|
| `spfnAppContractGenerate` / `spfnAppContractVerify` | Gradle 속성 6개(§README) — 전부 필수, 절대 경로. 그리고 `statesNamespace` — 선택에 `getStates`가 있으면 필수, 없으면 주면 거부 (§8) | 소비자 빌드 |
| `spfnAppContractFixtureGenerate` / `spfnAppContractFixtureVerify` | `tools/contract-codegen/src/test/resources/app-contract/`의 지어낸 문서 | 이 저장소의 양 플랫폼 행동 테스트. verify는 `check`에 물린다 |
| `spfnAppContractStateFixtureGenerate` / `spfnAppContractStateFixtureVerify` | 같은 디렉터리의 지어낸 상태 라우터 `state-contract.json` (선택은 `getStates` 하나) | 상태 모듈의 테스트(`Tests/SPFNStateTests/AppStatesFixture`, `spfn-state`의 `appstates`)가 생성된 서술로 상태 클라이언트를 돌린다 — 생성물과 클라이언트가 함께 컴파일되는 증거. verify는 `check`에 물린다 |

소비자 태스크의 속성이 하나라도 없으면 빠진 속성 이름을 대고 실패한다. 기본값으로 픽스처를 쓰지 않는다 — 속성 이름의 오타가 픽스처 검증의 초록으로 읽히면 P8(3)이다.

## 7. 이 설계가 답하지 않는 것

- **서명된 쿼리.** T15 — 열렸다(w-mmpsb): 호출이 쿼리를 경로 밖에 싣고, SDK는 `operation.path`를 서명하고 경로 + 쿼리를 보낸다.
- **퍼센트 인코딩과 서명.** 서버(Hono)의 `c.req.path`는 `%XX`를 풀어서(`decodeURI`) 준다. 경로 인자에 인코딩이 필요한 문자(공백, non-ASCII)가 있으면 클라이언트는 인코딩된 경로를, 서버는 풀린 경로를 서명 입력으로 쓴다 — `clientProofV1` 연산에서 `PROOF_INVALID`. unreserved 문자만 쓰는 id에는 영향이 없다. 쿼리와 같은 SDK 변경으로 푼다.
- **오류 본문.** 계약 문서는 성공 본문만 담는다. 오류는 SDK의 기존 봉투(`SPFNErrorEnvelope`)와 `SPFNClientError`로 읽는다.
- **성공 상태 코드.** 문서에 없다. `{type: null}`이 204라는 것만 SDK 규칙으로 안다.

## 8. 상태 서술

[state-client-design §2-13-2](state-client-design.md)의 결정(Q-G)을 생성기가 하는 그대로 적는다. 상태 라우터를 알아보면 플랫폼마다 파일 하나를 더 낸다. 생성기는 SDK 모듈에 의존하지 않는다 — `SPFNStateDescriptor` 등의 이름을 문자열로 방출하고, 생성 파일을 컴파일하는 앱이 `SPFNState` / `spfn-state`를 링크한다.

### 8-1. 알아보는 규칙

| # | 항목 | 규칙 |
|---|---|---|
| S1 | 닻 | **선택된** `getStates`. GET, 본문 없음, 응답이 필수 `at: string`·`states: object`·`manifest: object`이고 `states`와 `manifest`의 속성 집합이 같다. 아니면 거부한다. `states`의 속성 이름이 상태 id다. 닻이 선택에 없으면 아무것도 알아보지 않는다 — `get<Id>State`는 보통의 호출이고, 선택에 `get…State`·`put…State` 모양의 이름이 있으면 호출 파일에 "`getStates`가 선택되지 않아 상태 서술을 만들지 않았다"는 주석 두 줄을 남긴다 |
| S2 | 읽기 | id마다 `get<Id>State` (`<Id>`는 첫 글자 대문자)가 문서에 있어야 한다. GET, `params`·`body` 없음, 응답이 정확히 `{ at: string, value, data? }`이고 모든 멤버가 필수. `value`·`data`의 스키마가 `getStates`의 `states.<id>` 항목과 같다 (`required`는 집합으로 견준다, §1-3) |
| S3 | 키 | 읽기의 `request.query` 속성 — 모두 필수 `string`이어야 한다 (spfn `.key()`). 이름 순으로 `keyFields`가 된다. 키 필드는 모두 `getStates`의 쿼리에 있어야 한다 |
| S4 | 쓰기 | `put<Id>State`가 문서에 있으면: PUT, `params` 없음, 쿼리가 읽기와 같고, 본문이 객체(T16), 응답이 읽기와 같다 |
| S5 | 스냅샷 쿼리 | `getStates`의 쿼리 속성은 모두 선택 `string`이어야 한다 — 생성된 `snapshot`이 키의 필드를 선택 인자로 그대로 옮긴다 |
| S6 | 선택 | `getStates` 하나를 적으면 S2·S4의 연산이 함께 선택된다. 생성 파일 머리의 `operations:`가 그것을 모두 적는다 (§5) |
| S7 | 문서의 `events` 절 | 읽지 않는다 (§1-1). `events` 출력은 id에서 만든다 |

### 8-2. 출력

| 대상 | Swift | Kotlin |
|---|---|---|
| 파일 | `<statesNamespace>.swift` — `import SPFNCore`, `import SPFNState`, `public enum <statesNamespace>` | `<statesNamespace>.kt` — `object <statesNamespace>`, `xyz.superfunction.spfn.core`·`xyz.superfunction.spfn.state`에서 쓰는 이름만 import |
| `<id>` | `SPFNStateDescriptor(id:keyFields:read:frame:)`, 쓰기가 있으면 `.writable(write: { _ in <Namespace>.put<Id>State() })`. 키 없는 상태는 `keyFields`를 생략한다 | `SpfnStateDescriptor(id =, keyFields =, read =, frame =)`, 쓰기가 있으면 `.writable(write = { <Namespace>.put<Id>State() })` |
| 읽기·쓰기 함수 | `{ key in <Namespace>.get<Id>State(workspaceId: key["workspaceId"]!) }`, 키 없으면 `{ _ in … }` | `{ key -> <Namespace>.get<Id>State(workspaceId = key.getValue("workspaceId")) }`, 키 없으면 `{ <Namespace>.get<Id>State() }` |
| `frame` | `{ SPFNStateFrame(at: $0.at, value: $0.value, data: $0.data) }`, `data`가 없으면 `SPFNNoStateData()` | `{ SpfnStateFrame(it.at, it.value, it.data) }`, `data`가 없으면 `Unit` |
| `all` | `[any SPFNAnyStateDescriptor]` | `List<SpfnStateDescriptor<*, *>>` |
| `events` | `[String]`, `state.<id>.changed` | `List<String>`, 같음 |
| `snapshot` | `@Sendable (SPFNStateKey) -> SPFNCall<Void, <Namespace>.GetStatesResponse>`, `{ query in <Namespace>.getStates(workspaceId: query["workspaceId"]) }` | `(SpfnStateKey) -> SpfnCall<Unit, GetStatesResponse>`, `{ query -> <Namespace>.getStates(workspaceId = query["workspaceId"]) }` |

쓰기에는 `frame`이 없다 — §2-13-2의 `.writable(write:frame:)`에서 벗어난다: `put<Id>State`의 응답은 따로 생성된 타입(`PutConsentStateResponse`, 그 `value`는 `PutConsentStateResponseValue`)이라 읽기의 `Value`로 프레임을 만들 수 없고 컴파일되지 않는다. 그래서 쓰기의 답은 생성된 쓰기 디코더가 확인한 뒤 상태의 **읽기 디코더**가 읽는다 — 생성기가 두 응답 스키마가 같음을 이미 확인했으므로(S4) 건전하다.

순서는 모두 id 순이다 (문서 키 순서에 기대지 않는다, §1-3). 값·데이터·입력 타입은 같은 실행의 호출 출력이 정한다 — `Get<Id>StateResponseValue`(객체·enum) 또는 스칼라, `Get<Id>StateResponseData`, `Put<Id>StateBody` (§3).

### 8-3. 거부 (상태 id를 대고 멈춘다)

| 구성 | 메시지가 대는 것 |
|---|---|
| 값·데이터 스키마가 §2 표 밖 — `Type.Unknown`(R1), 소수(T3), 객체 유니언(R3) 등 | `state '<id>': get<Id>State.response.value` 와 행 번호. `getStates`의 응답이 모든 상태를 실으므로 한 상태가 생성 전체를 막는다 |
| `getStates`의 id에 `get<Id>State`가 없다 | 상태 id와 빠진 연산 이름 |
| 읽기가 GET이 아니거나 `params`·`body`가 있다, 응답 모양이 S2와 다르다 | 상태 id와 읽기 연산 |
| 읽기와 스냅샷의 `value`·`data` 스키마가 다르다 | 상태 id, 필드, 두 자리 |
| 키 필드가 필수 `string`이 아니다, `getStates`의 쿼리에 없다 | 상태 id와 필드 |
| 쓰기가 PUT이 아니다, 쿼리나 응답이 읽기와 다르다, 본문이 객체가 아니다 (T16) | 상태 id와 쓰기 연산 |
| id가 `^[a-z][a-zA-Z0-9]*$`가 아니다 (spfn `defineState`) | id |
| id가 `all`·`events`·`snapshot` — 생성 멤버와 겹친다 (R9) | id |
| `getStates`가 S1·S5의 모양이 아니다 | `operation 'getStates'` |
| `statesNamespace`가 선택에 `getStates`가 있는데 없다 / 없는데 있다, 다른 생성 파일·타입 이름과 겹친다 (R9) | 속성 이름 `spfn.appContract.statesNamespace` (`--states-namespace`) |
