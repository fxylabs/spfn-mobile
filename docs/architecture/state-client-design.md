# 설계 — 네이티브 상태 클라이언트 `StateClient`

- 상태: **제안** (2026-10-16, 작업 단위 `w-hyg8j`). 받아들여진 항목은 없다. 항목마다 따로 승인한다 (CONTRIBUTING, "위임 설계 산출물은 통짜로 승인받지 않는다"). 코드는 승인 뒤에 쓴다.
- 개정 1 (2026-10-17, 운영자 결정 `01m52pdtgpxkbdp0z3jc0kg5a9`, PR #90 검토): 열린 질문 둘의 권고를 뒤집었다. **Q-G → 앱 계약 코드젠이 상태 서술을 생성한다** (§2-13-2, §2-13-4, §5-2 P-a). **Q-F → 관찰 어댑터는 SDK의 `events` 모듈에 있다** (§2-12, §3-6, §7). 나머지 항목은 아직 판단 전이다 — 상태는 그대로 제안이다.
- 대상: **새 모듈 `SPFNState`/`spfn-state`** (클라이언트 모듈에만 의존)에 들어갈 `SPFNStateClient`/`SpfnStateClient`, 상태 서술 `SPFNStateDescriptor`/`SpfnStateDescriptor`, 항목·연결 상태 타입, 순서 저장소. 앱 계약 코드젠(`tools/contract-codegen`)이 상태 서술을 내는 출력 하나 (§2-13-4). 이벤트 모듈 `SPFNEvents`/`spfn-events`의 관찰 어댑터 (§2-12). 그리고 이 설계가 **전제로 요구하는** 클라이언트 모듈 `SPFNEventStream`/`SpfnEventStream`의 확장 넷 (S-1–S-4, §5-1). 그 확장은 [event-stream-design.md](event-stream-design.md)의 개정 4로 따로 승인받는다. 이 문서는 그 모양만 정한다.
- 원본: `@spfn/core/state/client` — `packages/core/src/state/README.md`의 "Browser"부터 "Types"까지, "`at`", "Contract", 그리고 `client.ts`·`client-store.ts`·`client-types.ts`·`types.ts`. 이 문서가 README를 인용할 때는 절 이름과 문장을 그대로 적는다. 세 플랫폼이 갈라지지 않게 하려는 것이다.
- 서버 쪽 사실은 spfn 작업 사본(`packages/core/src/event/sse/handler.ts`, `connection-registry.ts`, `types.ts`의 `ConnectedPayload`·`SSESubscriptionChange`)에서 읽었다 (§1-2).
- 소비자: Superself `docs/workspace/app-root-package.md` §3.1 (키 없는 스냅샷 한 번, 그다음 앱이 도출한 키로 키 있는 상태), §6 (네이티브 어댑터), §7 (`server` 변수 ok · reconnecting · failed · error), §9·§12 (U4·U5는 상태 클라이언트를 프로토콜과 가짜 뒤에 둔다).
- 관련: [architecture README](README.md) "Three layers in the client module"·"The event stream"·"How a module is added", [app-contract-codegen.md](app-contract-codegen.md) (T15, §7), [tools/contract-codegen/README.md](../../tools/contract-codegen/README.md), `tools/module-graph.json`, [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) (§8).

## 0. 요약

| 항목 | 결정 | 상태 |
|---|---|---|
| 모듈 | **새 모듈 `state`** (`SPFNState`/`spfn-state`), `client`에만 의존. 툴킷 없음. Swift 외부 의존 없음, Android `kotlinx-coroutines-core` 하나 (§2-1) | 제안 |
| 스트림 | 앱의 **하나뿐인** `SPFNEventStream`을 같이 쓴다. 상태 클라이언트가 그 스트림의 **연결 쿼리의 유일한 주인**이다. 상태 이벤트 이름은 앱이 스트림 설정에 함께 적는다 (§2-2) | 제안 |
| 스트림 확장 (전제) | S-1 연결 쿼리 입력 `setQuery`, S-2 여러 이름을 한 큐로 받는 원선 청취 `listenWire` (`connected` 내용 포함), S-3 `subscription.granted`/`revoked` 전달, S-4 `reconnectNow()` (§5-1) | 제안 |
| 읽기 경로 | 스냅샷·읽기·쓰기는 **앱 계약 코드젠이 만든 호출**을 `SPFNClient.execute`로 보낸다. 앱이 호출을 값으로 넘기고, 클라이언트는 라우트 이름을 모른다 (§2-3) | 제안 |
| 프레임 디코드 | 상태마다 **그 상태의 읽기 호출의 `decode`**로 읽는다. 프레임은 읽기 응답에 `key`·`subject`를 더한 것이다. 앱이 손으로 디코더를 쓰지 않는다 (§2-4) | 제안 |
| 순서 | `(id, key)`마다 순서 도장(가장 새 `at`). 오래된 것은 버림, 같은 `at`은 값이 다를 때만, 쓰기 답은 프레임처럼. **`at`은 문자열로 비교한다** (UTF-8 바이트 순) (§2-5) | 제안 |
| 다시 읽기 신호 | 원선 청취의 **`opened(connected)`** (TS의 `onConnected`와 같은 자리). 스트림의 `state` 흐름이나 `attached`에 묶지 않는다. 청취자 넘침(`overflow`)도 다시 읽기다 (§2-6) | 제안 |
| 키 | 연결 하나에 키 필드마다 값 하나. 겹치면 **나중에 구독한 키**가 실린다. 실리지 않은 키(그림자 키)는 열릴 때마다 한 번 읽고 프레임은 없다 (§2-7) | 제안 |
| `revoked` | README 표의 여덟 행 그대로. 표시는 값과 따로 `(id, key)`마다, 해제는 **연결(epoch)로** 한다 (§2-8) | 제안 |
| 연결 상태 | `connected · reconnecting(attempt) · failed`. 스트림 상태에서 표 하나로 도출한다. `offline(n)`은 `reconnecting(n)` (§2-9) | 제안 |
| `initial` | 이미 읽은 스냅샷(`snapshot()`의 답, 또는 테스트 픽스처)으로 만든다. 매니페스트와 값이 생성 시각에 들어가고, `revoked`는 표시하지 않는다 (§2-10) | 제안 |
| API | 두 언어 같은 이름. `subscribe`·`current`·`get`·`write`·`snapshot`·`connection`·`subscribeConnection`·`retry`·`close`. 항목은 `loading · ready(frame) · revoked(frame?)` — Swift 연관값 enum, Kotlin sealed. 멤버가 받는 서술은 앱이 쓰지 않는다: 생성된 `AppStates.<id>`다 (§2-11, §2-13) | 제안 |
| 상태 서술 (Q-G) | **앱 계약 코드젠이 생성한다.** 선택에 `getStates`가 있으면, 그 파생 응답 스키마의 `states`가 상태 id를 대고, id마다 `get<Id>State`(필수)·`put<Id>State`(있으면)를 이름과 모양으로 확인한다. 출력: `AppStates.<id>` (`SPFNStateDescriptor`/`SpfnStateDescriptor`, 쓰기가 있으면 쓰기 서술), `AppStates.all`, `AppStates.events` (`state.<id>.changed`), `AppStates.snapshot`. `keyFields`는 읽기 연산의 쿼리에서 온다. 앱은 상태마다 아무것도 쓰지 않는다 (§2-13-2, §2-13-4) | **결정** (운영자, 2026-10-17) |
| 관찰 어댑터 (Q-F) | **SDK가 `events` 모듈에 둔다** — `@spfn/core/state/react`의 짝. Swift `@SPFNServerState`·`@SPFNConnectionStatus` (`@Observable` 모델 `SPFNServerStateModel`), Kotlin `rememberServerState`·`rememberConnectionStatus`. 루트 부착이 상태 클라이언트를 함께 받는다. 현재 값 먼저, 메인 액터, 뷰가 떠나면 해지. `state` 모듈은 툴킷 없는 그대로이고, 그래프에 간선 `events → state` 하나가 는다 (§2-12, §7) | **결정** (운영자, 2026-10-17) |

## 1. 맥락과 요구

Superself 앱의 루트는 서버가 가진 사실 몇 개로 갈 곳을 정한다: 약관에 동의했나, 작업공간이 있나, 지금 작업공간의 AI 계정이 연결됐나. 웹은 `@spfn/core/state/client`가 그 일을 한다 — 스냅샷을 한 번 읽고, SSE 프레임을 `(id, key)`마다 `at`으로 순서 짓고, 연결 상태를 알린다. 네이티브에는 그것이 없다. 앱 루트 패키지(U4·U5)는 지금 상태 클라이언트를 프로토콜과 가짜 뒤에 두고 이 단위를 기다린다 (app-root-package §12).

이 설계는 웹 클라이언트가 README에 적은 모든 경우에서 **같은 결정**을 네이티브에서 내리게 한다. TypeScript에만 있는 것(React 훅, `useSyncExternalStore`, 브라우저 `online` 이벤트, `EventSource` 콜백)은 네이티브의 짝을 이름으로 댄다.

| # | 요구 | 이 설계에서 |
|---|---|---|
| Q1 | 앱 루트가 **키 없는 스냅샷**을 매니페스트와 함께 한 번 읽는다 | `snapshot()`, 첫 `subscribe`가 부르는 첫 스냅샷 (§2-3, I-셀) |
| Q2 | 앱이 도출한 **키**로 키 있는 상태를 읽는다 (`aiAccounts` under `workspaceId`) | `subscribe(state, key:)`, 연결 쿼리 S-1 (§2-7, K-셀) |
| Q3 | 프레임 `state.<id>.changed`를 `(id, key)`마다 `at`으로 적용한다 | 원선 청취 S-2, 순서 저장소 (§2-5, O-셀) |
| Q4 | 연결 상태 `connected · reconnecting(attempt) · failed`와 `retry()` | §2-9, C-셀. 앱 루트의 `server` 변수가 이것을 읽는다 (app-root-package §7) |
| Q5 | `(id, key)`마다 `revoked` | §2-8, R-셀 |
| Q6 | 앱이 넘기는 `initial` 스냅샷 (이미 읽은 콜드 스타트, 또는 테스트) | §2-10, I-셀 |
| Q7 | Swift·Kotlin 같은 어휘, 공유 적합성 픽스처 하나 | §2-13 이름 표, §4 테스트 이름, `tests/fixtures/state-client/cases.json` |
| Q8 | **새 전송이 없다.** 프레임은 `SPFNEventStream`을, 읽기는 앱 계약 생성 호출로 `SPFNClient.execute`를 탄다 | §2-2, §2-3. 고정 코어 번들(`Contracts/`)은 쓰지 않는다 |

### 1-1. 지금 있는 것과 빠진 것

| 필요한 것 | 지금 SDK | 빠진 곳 |
|---|---|---|
| 상태 이벤트 프레임 | `SPFNEventStream.listen(name, decode, where:)` — 설정된 이름의 프레임을 청취자마다 큐로 | 키를 실을 **연결 쿼리**가 없다. 스트림 GET은 `token`과 `events`만 싣는다 (event-stream-design §3-1). 키 있는 상태는 쿼리에 키가 없으면 서버가 연결 시점에 거부한다 (`deniedEvents`) |
| 연결이 열린 순간 | `reread(.opened(epoch))` | `connected` 프레임의 **내용**(`deniedEvents`, `initial`)을 스트림이 먹고 넘기지 않는다 ("`connected`와 `ping`은 앱에 넘기지 않는다", event-stream-design §2-3) |
| 서버의 `grant`/`revoke` | 없음 | `subscription.granted`/`subscription.revoked`는 라우터 이벤트가 아니라 스트림 소유 프레임이다. 지금 기계는 설정에 없는 이름으로 버린다 (E-23) |
| `retry()` | 없음 | `retrying`은 타이머를, `closed`는 조건 변화(E-44)를 기다린다. "지금 다시" 입력이 없다 |
| 서명된 키 읽기 | 앱 계약 코드젠의 호출 | `clientProofV1` 연산의 **쿼리는 거부된다** (app-contract-codegen T15, §7). 상태 읽기는 키를 쿼리로 받는다 |

앞의 넷은 스트림 확장 S-1–S-4이고 (§5-1), 다섯째는 코드젠·실행 경로의 전제다 (§5-2). 이 설계는 그 다섯이 있다고 보고 쓴다.

### 1-2. 서버 쪽 사실

`@spfn/core`의 state·SSE 작업 사본에서 읽었다 (§0 머리의 파일).

| 사실 | 값 |
|---|---|
| 상태 이벤트 이름 | `state.<id>.changed`. 상태 라우터의 `.events`가 이 이름을 키로 갖는다 — 선로 위 이름이 곧 이 문자열이다 (event-stream-design H-2) |
| 프레임 | `data`의 페이로드가 `{ at, value, data?, key?, subject? }`. 키 있는 상태는 `key`를, 공유가 아닌 상태는 `subject`를 싣는다 (`types.ts`의 `StateFrame`) |
| 읽기 응답 | `{ at, value, data? }` (README "Contract": "response `Type.Object({ at: Type.String(), value, data })`") |
| 스냅샷 응답 | `{ at, states: { [id]?: { value, data? } }, manifest: { [id]: { key: string[], write: boolean } } }` (README "Contract") |
| 키 | 키 필드는 모두 필수 문자열이다. `events`·`token`이라는 필드 이름은 `.key()`가 거부한다 (README `.key()` 행). 그래서 연결 쿼리에 키를 실어도 스트림의 두 쿼리와 부딪히지 않는다 |
| `connected` | `{ subscribedEvents, deniedEvents, initial?, timestamp }`. `initial`은 연결 직후 현재 프레임을 보낸 상태들, 그 전 서버에는 없다 (`ConnectedPayload`) |
| 연결 직후 프레임 | `connected` 바로 뒤, 다른 어떤 라이브 프레임보다 먼저, 허용된 상태마다 현재 프레임 하나 (README "The stream", "Current frames on every open") |
| 권한 변경 | `event: subscription.granted` 또는 `subscription.revoked`, `data: {"event":"state.<id>.changed"}` (`connection-registry.ts`). 연결 쿼리로 범위가 정해진다 |
| 연결 거부 | `canSubscribe`(`canRead`) 거부는 `deniedEvents`에 들 뿐 연결은 열린다. 403은 `authorize`가 거부할 때만이다 (`handler.ts`) |
| `at` | `new Date().toISOString()`, `read()` 시작 **전에** 잰다 (README "`at`") |

## 2. 결정

항목마다 원본의 규칙을 인용하고, 네이티브의 짝을 적고, 이 저장소에 이미 있는 메커니즘과 견준다.

### 2-1. 모듈 위치 (제안)

원본: "`@spfn/core/state/client` is framework-neutral." 훅은 `@spfn/core/state/react`에 따로 있고, `react`는 선택적 피어다.

| | (가) 새 모듈 `state` (**제안**) | (나) `events` 모듈 안 | (다) `client` 모듈 안 |
|---|---|---|---|
| 의존 | `client` 하나. `SPFNEventStream`·`SPFNClient`·`SPFNKeyLifecycle`이 다 거기 있다 | `client` + SwiftUI/Network, Android는 Compose runtime·ui·lifecycle-process | 그대로 |
| 툴킷 | 없음. JVM·Linux 테스트 | 모듈은 툴킷을 가진다. 상태 클라이언트 자체는 툴킷이 없으므로 가드 밖 파일로 들어간다 | 없음 |
| 링크 비용 | 상태 라우터를 쓰는 앱만 | 상태를 쓰는 Android 앱이 Compose 네 별칭을 끌고 온다. 앱 루트 패키지의 JVM 코어(U5)도 | 상태를 쓰지 않는 모든 클라이언트 소비자 |
| 그래프 노트와의 관계 | "A module exists here only once it carries an implementation" — 순서 저장소, `revoked` 표시, 연결 상태 도출이 행동이다. "most consumers demonstrably will not link it" — 상태 라우터는 서버의 선택이고, 이벤트 스트림만 쓰는 앱이 있다 | `events` 노트: "this module only carries platform facts into it". 상태 클라이언트는 플랫폼 사실을 옮기지 않는다 — 노트가 틀린 말이 된다 | 그래프 노트 "The default shape of an extension is an injected protocol, not a module" 쪽. 그러나 `client`는 전송·세션·실행의 세 층이고 (architecture README), 상태 저장소는 어느 층도 아니다 |

(가)를 고른 이유: 의존이 `client` 하나로 가장 짧고, 툴킷이 없으며, 쓰지 않는 앱은 링크하지 않는다. `events` 모듈을 거치지 않으므로 Android에서 Compose를 끌고 오지 않는다. 대가는 산출물 하나다 (§7).

- `externalDeps.swift`: 없음. `externalDeps.android`: `kotlinx-coroutines-core` — 공개 시그니처가 `Flow`·`StateFlow`를 싣는다 (`spfn-client`는 이것을 `implementation`으로 두므로 새 모듈이 `api`로 선언해야 한다).
- `linux` 키 없음: 모듈 전체가 Linux에서 빌드되고 테스트된다. SwiftUI·Observation import가 없다. 어댑터는 이 모듈에 오지 않으므로 가드할 파일이 없다.
- **관찰 어댑터는 `events` 모듈에 있다** (§2-12, Q-F 결정 2026-10-17). 간선은 `events → state` 하나이고 방향이 거꾸로가 아니다: `state`는 `client` 하나에만 의존하는 줄 그대로, 툴킷도 Compose 별칭도 얻지 않는다. 어댑터를 이 모듈에 넣었다면 Compose runtime 별칭이 이 줄에 붙고 (가)의 이유가 사라졌을 것이다 — 운영자의 결정은 그 이유를 지킨 채 어댑터를 SDK로 가져온다.
- 모듈 의존을 한 줄로: `client` ← `state` ← `events` (어댑터), `client` ← `events` (스트림 부착). 앱 계약 생성물(`AppAPI`·`AppStates`)은 앱의 소스이고 `state`와 `client`에 의존한다 — SDK 모듈은 생성물에 의존하지 않는다.

### 2-2. 스트림: 앱의 스트림 하나를 같이 쓴다 (제안)

원본: "Give the state client an `SSEClient` of its own: an `SSEClient` holds one subscription, and the state client's supersedes any other made on it." 그리고 "One stream per client, opened when the first subscriber arrives and closed when the last leaves."

브라우저의 `SSEClient`는 구독 하나를 갖고, 그 구독이 이벤트 집합과 쿼리를 정한다. 그래서 상태 클라이언트가 자기 것을 가져야 했다. 네이티브 `SPFNEventStream`은 다르다: 이벤트 집합은 설정에서 고정이고, 청취자는 몇이든 붙는다 (event-stream-design §2-4). 걸리는 것은 연결 쿼리 하나뿐이다.

| | (가) 앱의 스트림을 같이 쓰고, 쿼리는 상태 클라이언트만 쓴다 (**제안**) | (나) 상태 클라이언트 전용 스트림 |
|---|---|---|
| 연결 | 앱에 하나. 토큰 호출·핑·재연결이 하나 | 둘. 토큰 호출과 핑이 두 배 |
| 루트 부착 | 그대로 하나 (`spfnEventStream`, `SpfnEventStreamHost`) | 둘을 붙이면 안쪽이 환경을 가린다 — "한 앱에 스트림은 하나다" (event-stream-design §2-13-3)가 깨진다 |
| 수명 | SDK가 소유한다 (앞쪽 ∧ 로그인). 상태 클라이언트는 수명을 건드리지 않는다 | 같다. 그러나 둘의 수명 관찰자가 둘이다 |
| 키 변경의 비용 | 재연결 한 번. 그 스트림의 모든 청취자가 `opened(e+1)`을 받고 한 번 다시 읽는다 | 상태 스트림만 재연결 |
| 쿼리의 주인 | 상태 클라이언트 하나. 둘째 상태 클라이언트를 같은 스트림에 만들면 프로그래머 오류다 | 전용이라 다툼이 없다 |

(가)를 고른 이유: 이벤트 스트림 설계가 받아들인 "연결은 하나" (개정 1)를 지키고, 루트 부착을 늘리지 않는다. 키 변경은 작업공간 전환이고, 그때 화면은 어차피 다시 읽는다 — 다른 청취자의 다시 읽기 한 번은 그 이동에 묻힌다.

- 앱은 스트림 설정의 `events`에 **상태마다 `state.<id>.changed`** 를 싣는다 — 손으로 적지 않고 생성된 `AppStates.events`를 더한다 (§2-13-2, §2-13-1). 이벤트 스트림의 규칙 "앱이 설정하는 이름 목록은 고정이다"를 그대로 따른다. 상태 클라이언트는 생성 시각에 서술마다 그 이름이 설정에 있는지 보고, 없으면 프로그래머 오류다 (L-3과 같은 거부: Swift `preconditionFailure`, Kotlin `IllegalArgumentException`).
- 원본 "Every event requested: `events=` lists `state.<id>.changed` for every state of the manifest … never only the subscribed ones"는 네이티브에서 "서술한 상태 모두"가 된다. 앱이 서술하지 않은 상태는 구독할 수도 없으므로 (타입이 없다) grant를 받을 일도 없다. 매니페스트에만 있는 상태는 무시한다.
- 원본의 "열고 닫는" 수명(첫 구독에 열고, 마지막 구독이 떠나면 닫는다)은 네이티브에 없다. 연결은 SDK 수명을 따른다 (event-stream-design L-2: "마지막 청취자여도 연결은 열려 있다"). 관찰되는 결과는 맞춘다: 구독이 없으면 연결 상태는 `reconnecting(0)`이고 (C-2), 쿼리는 비고 (K-5), 첫 스냅샷의 재시도 수는 처음부터다 (C-18).

### 2-3. 읽기 경로: 앱 계약 생성 호출을 값으로 받는다 (제안)

원본: "`get(id, key?)` | `GET` `get<Id>State`, key fields in the query", "`snapshot(query?)` | `GET getStates`", "`write(id, input, key?)` | `put<Id>State`". 웹 클라이언트는 `createApi`에 라우트 이름을 만들어 부른다 (`routeName`).

네이티브에는 이름으로 부르는 클라이언트가 없다. 앱의 연산은 앱 계약 문서에서 생성된 `SPFNCall`/`SpfnCall` 값이고, `SPFNClient.execute`가 유일한 실행 경로다 (architecture README "Three layers").

| | (가) 앱이 생성 호출을 서술에 넘긴다 (**제안**) | (나) 상태 클라이언트가 `SPFNOperation`을 이름·경로로 만든다 | (다) 고정 코어 번들에 상태 라우트를 넣는다 |
|---|---|---|---|
| 기존 메커니즘 | 앱 계약 코드젠 (app-contract-codegen) — 앱의 다른 호출과 같다 | 이벤트 스트림의 토큰 호출 (event-stream-design §2-2 (다)) | 코어 번들 (`Contracts/`) |
| 경로·인증 | 앱 계약 문서가 안다 (`path`, `auth`, `requiresSession`). 상태 클라이언트는 모른다 | 상태 라우터의 `path` 옵션(기본 `/state`)을 설정으로 받아야 한다. 인증 등급도 추측한다 | 번들은 SPFN primitives의 auth 패키지가 내보낸다. 상태 라우트는 앱 서버의 것이다 |
| 응답 검사 | 생성 디코더가 문서대로 검사한다 (`notTheDeclaredResponse`) | 손으로 쓴 디코더 | 생성 디코더 |
| 토큰 호출과 다른 까닭 | 토큰 경로는 `.contract()`가 없는 프레임워크 설정이다. 상태 라우트는 `.contract({ since })`로 문서에 실린다 (README "Contract") | — | — |

(가)를 고른 이유: 상태 라우트는 앱의 계약 표면이고, 앱 계약 코드젠이 바로 그것을 위해 있다. 클라이언트가 라우트 이름을 모르면 서버의 `path` 옵션이나 이름 규칙이 바뀌어도 SDK가 따라 바뀔 일이 없다. 앱 루트 계획(app-root-package §12 U6)은 (다)를 적었으나, 상태 라우트는 앱 서버의 것이라 코어 번들에 들어갈 수 없다 — 이 단위의 브리프가 (가)로 바로잡았다.

- 서술(§2-13-2)은 상태마다 **읽기 호출을 만드는 함수** `(key) -> SPFNCall<Void, Response>`와, 쓰기가 있으면 **쓰기 호출을 만드는 함수** `(key) -> SPFNCall<Body, Response>`를 받는다. 키를 생성 함수의 인자로 옮기는 한 줄도 앱 계약 코드젠이 쓴다 (`{ AppAPI.getAiAccountsState(workspaceId: $0["workspaceId"]!) }`, §2-13-2).
- 스냅샷도 같다: `(query) -> SPFNCall<Void, Response>` 하나. 상태 클라이언트는 그 호출의 `operation`과 `encode`를 쓰고, `decode`는 **감싼다**: 생성 디코더로 문서와 맞는지 검사한 뒤 정규값(`SPFNCanonicalValue`)을 그대로 받는다. 스냅샷의 `states`와 `manifest`는 문서에 적힌 모양이므로 상태 클라이언트가 직접 읽는다. `SPFNCall`의 이니셜라이저와 세 필드가 공개라서 새 실행 경로가 없다.
- 읽기·쓰기 호출도 같은 방식으로 감싸 정규값을 남긴다. 같은 값인지 가르는 데 쓴다 (§2-5).
- 서명할 클라이언트는 `SPFNKeyLifecycle.signedInClient()`에 호출마다 묻는다 — 이벤트 스트림의 토큰 호출과 같은 자리 (event-stream-design §3-1). `nil`(로그아웃)이면 보내지 않고, 앱이 부른 호출은 거부되고, 자동 읽기는 `onError`로 간다.
- 요청은 모두 `execute`를 지난다. 재시도 규칙은 `execute`의 것(인증 거부 한 번)뿐이다. 첫 스냅샷의 재시도(`retryDelay`, `maxRetries`)는 상태 클라이언트의 것이다 (§2-9).

### 2-4. 프레임 경로와 디코드 (제안)

원본: "`events=` lists `state.<id>.changed` for every state", "A frame for a key nobody here subscribed is dropped" (`client.ts` `onFrame`). 브리프의 기대는 "앱이 상태마다 페이로드 디코더를 준다 (spfn #111이 열려 있으므로)"였다.

| | (가) 읽기 호출의 `decode`로 프레임을 읽는다 (**제안**) | (나) 앱이 상태마다 프레임 디코더를 손으로 쓴다 |
|---|---|---|
| 근거 | 서버 타입: `StateFrame<T> = StateSnapshot<T> & { key? } & { subject? }`. 프레임은 읽기 응답의 상위 집합이고, 생성 디코더는 모르는 필드를 무시한다 (app-contract-codegen F2) | 이벤트 스트림의 규칙 그대로 (`listen(name, decode)`) |
| 문서 | 읽기 연산의 응답 스키마가 이미 문서에 있다 | 이벤트 페이로드는 문서에 없다 (#111) |
| 어긋날 위험 | 서버가 프레임을 읽기 응답과 다르게 만들면 디코드 실패 (`droppedFrames`, 아래) | 앱의 손 디코더와 서버가 어긋난다 — 같은 값을 두 번 적는다 |

(가)를 고른 이유: 같은 값을 두 번 적지 않는다. #111이 이벤트 페이로드를 문서에 실으면 생성된 이벤트 서술로 바꿀 수 있고, 그때 이 항목을 다시 본다 (§6 Q-B).

- 원선 청취(S-2)는 프레임을 정규값으로 넘긴다. 상태 클라이언트가 `key`를 꺼내 칸을 정하고, 그 칸이 구독 중이면 서술의 읽기 디코더로 `at`·`value`·`data`를 읽는다. `key`와 `subject`는 디코더가 무시한다.
- 디코드 실패는 그 프레임만 버리고 수를 센다 (`droppedFrames`, 이벤트 허브 L-11과 같은 이름). 페이로드는 기록하지 않는다. 연결과 다른 칸은 그대로다.
- 원본의 두 번째 지킴 "a frame for a key not subscribed here | dropped"는 그대로다 (O-11).

### 2-5. 순서 규칙 (제안)

원본 "Ordering": "Per `(id, key)`, every arrival — a snapshot entry, a frame, a read or a write answer — goes through one rule. … The client keeps, per `(id, key)`, an ordering stamp — the newest `at` seen — apart from the entry, and compares each arrival against it."

네이티브는 같은 표를 그대로 쓴다 (O-1–O-14). 다른 것은 두 가지 짝뿐이다.

**`at`의 비교 — 문자열이다.** 원본은 `arrival.at < stamp`, 곧 JavaScript 문자열 비교(UTF-16 코드 단위)다. 네이티브도 **문자열로** 비교한다: 두 `at`의 UTF-8 바이트를 사전 순으로. 그 이유:

| | 문자열 비교 (**제안**) | 시각으로 파싱 |
|---|---|---|
| 원본과 같은가 | 같다. 세 플랫폼이 같은 입력에 같은 답 | 서버가 `toISOString()` 아닌 것을 보내면 갈린다 |
| 안전한가 | `toISOString()`은 늘 `YYYY-MM-DDTHH:mm:ss.sssZ` — 고정 폭 24자, UTC, 0 채움, ASCII. 이 모양에서 사전 순은 시각 순이다 (0000–9999년) | 안전하다. 그러나 밀리초 아래가 없으니 얻는 정밀도가 없다 |
| "같은 `at`" | 같은 읽기의 같은 문자열 — 원본이 "one read stamps one `at`"으로 정한 뜻 그대로 | 다른 문자열이 같은 시각으로 파싱될 수 있다 (`+00:00`). 뜻이 넓어진다 |
| 새 실패 | 없다 | `ISO8601DateFormatter` 분수 초 옵션, `Instant.parse` — 두 플랫폼의 파서가 다른 입력을 거부한다 (P9의 갈래) |

- ASCII 안에서 Swift `String <`(유니코드 정준 순서), Kotlin `compareTo`(UTF-16), UTF-8 바이트 순은 모두 같다. 구현은 혼동이 없게 **UTF-8 바이트 비교**를 이름 붙은 함수 하나(`SPFNStateOrder.isOlder`)에 둔다. 앱 계약 코드젠도 `at`을 `String`으로 둔다 (T1).
- 픽스처가 경계를 고정한다: 밀리초·초·날·해가 넘어가는 두 `at` (O-12).

**"값이 다르다"의 짝 — 정규값 비교.** 원본 `sameJson`은 JSON 깊은 같음이다 (키 순서 무관, 배열은 순서대로). 네이티브는 응답과 프레임의 **정규값** `{value, data}`를 `SPFNCanonicalValue`의 `==`로 비교한다: 객체는 사전(키 순서 무관), 배열은 순서대로 — 같은 뜻이다 (O-13, O-14). 생성 타입의 `Equatable`/`equals`로 비교하지 않는다: 생성 디코더가 모르는 필드를 버리므로, 서버가 더한 필드의 변화가 TS에서는 알림이고 네이티브에서는 아닌 차이가 생긴다.

**"같은 객체"의 짝.** 원본은 `useSyncExternalStore` 때문에 변화가 없으면 같은 얼어 있는 객체를 돌려준다. 네이티브 항목은 값 타입이고, 구독 열은 **바뀐 항목만** 낸다 (Kotlin `StateFlow`의 같음 합치기, Swift는 직접 거른다). 알림 규칙 "status, value or data가 다를 때만"이 그 거름이다.

쓰기 답이 2xx인데 읽을 수 없으면 (P42) 그 호출은 거부되고 아무것도 적용하지 않는다. 쓰기는 서버에서 일어났을 수 있고, 쓰기가 보내는 프레임이 값을 가져온다 (O-15).

### 2-6. 다시 읽기 신호: 원선 청취의 `opened` (제안)

원본 "The stream": "Every stream open — the first, a reconnect, a reopen on a key change — brings each state's current value with a newer `at`: the stream's initial frame, or the snapshot re-read when the stream did not serve it." 그리고 "(It all runs on the stream's `connected`, not at `onReconnect`: a read before the new stream is live would leave the same gap.)" 웹 클라이언트는 `onOpen`에서 세대를 올리고 `onConnected`에서 다시 읽는다.

네이티브 스트림에는 두 신호가 있다: 청취자에게 가는 `reread(.opened(epoch))`와, 상태로 내는 `open(epoch, …)`. 둘 다 `connected` 프레임에서 일어난다 (E-13). 그 밖에 붙는 순간의 `attached`와 넘침의 `overflow`가 있다.

| 묶을 신호 | 남는 틈 |
|---|---|
| **원선 청취의 `opened(SPFNStreamConnected)`** (**제안**, S-2) | 없다 (아래 둘은 따로 막는다). 같은 큐에 프레임과 순서대로 들어오므로, 세대 e의 프레임이 e를 알기 전에 처리되는 일이 없다. `connected`의 `initial`·`deniedEvents`·쿼리를 싣고 오므로 원본의 판단을 그대로 한다 |
| 스트림의 `state` 흐름 (`open(epoch)`) | Kotlin `StateFlow`는 합친다 — 빠른 재연결 둘 사이의 epoch 하나를 놓칠 수 있다. 프레임과 다른 통로라서, 새 연결의 프레임이 세대를 바꾸기 전에 도착해 옛 세대로 적용된다 (`revoked` 해제가 틀린다, R-11) |
| `reread(.opened(e))` (지금 있는 신호) | 순서는 맞다. 그러나 `deniedEvents`·`initial`이 없으니 매 열림마다 스냅샷을 읽고 (원본의 "server predates initial" 갈래), 연결 시점 거부를 스냅샷 누락으로만 안다. S-2가 오기 전의 대체 길이다 (§6 Q-C) |
| `attached` | 붙는 순간일 뿐 연결과 무관하다. 스트림이 이미 열려 있으면 그 연결의 `connected`를 놓친다 |

- **이미 열린 스트림에 붙을 때.** 원본에는 없는 경우다 (웹 클라이언트는 자기 스트림을 연다). S-2는 열려 있는 동안 붙은 원선 청취자에게 `attached` 다음 **현재 연결의 `opened`를 한 번 다시 준다**. 상태 클라이언트는 그것을 보통의 열림처럼 다룬다 (C-27).
- **넘침.** 원선 청취자의 큐가 넘치면 허브가 큐를 비우고 `overflow`를 준다 (L-4). 프레임을 잃었으므로 상태 클라이언트는 `initial` 없는 열림처럼 스냅샷과 그림자 키를 다시 읽는다. 세대는 그대로다 (C-25).
- 다시 읽기의 판단은 원본과 같다: "The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents` … or when `initial` is absent (a server before it); a state in `deniedEvents` it watches on the carried key is `'revoked'`." (C-26–C-28)
- 스냅샷은 그 연결의 쿼리로 읽고, 그 연결의 세대로 꼬리표를 단다.

### 2-7. 키 (제안)

원본 "The stream": "Keys in the query: the connect query is the union of the subscribed keys' fields … A new key value, or the last subscriber of one leaving, closes the stream and opens it with the new query." 그리고 "One value per key field per connection. … when two subscribed keys disagree on a field, the most recently subscribed key is carried. A key the stream does not carry is read once (`get`) when the stream opens and receives no frames until it is carried again."

- 쿼리는 구독된 키들의 필드 합집합이고, 겹치면 나중에 구독한 키의 값이다. 바뀌면 `stream.setQuery(q)` (S-1) — 열린 연결은 닫고 새 토큰으로 다시 연다.
- **묶음.** 원본: "Subscriptions made in the same task … are batched into one change." 네이티브: 같은 차례에 일어난 구독·해지는 쿼리 계산 한 번으로 합친다. Swift는 클라이언트의 엔진 actor에 한 번 예약한 작업, Kotlin은 클라이언트 스코프에 한 번 `launch`한 작업이 쿼리를 계산한다. 앱 루트가 키 없는 상태와 키 있는 상태를 한꺼번에 구독해도 재연결은 한 번이다 (K-6). 테스트는 예약기를 주입해 차례를 손으로 넘긴다.
- **그림자 키.** 실리지 않은 키는 열릴 때마다 한 번 `get`으로 읽는다. 서버는 그 키의 프레임을 보내지 않는다 (연결 쿼리로 거른다). 그래도 그런 프레임이 오면 — 구독 중인 칸이므로 — 순서 규칙 아래 적용한다. 원본 `onFrame`이 그렇다 ("A frame for a key nobody here subscribed is dropped"의 반대: 구독 중이면 받는다) (K-4).
- 같은 칸을 둘이 구독하면 쿼리는 그대로다. 마지막 구독이 떠나면 그 키가 쿼리에서 빠지고 재연결한다 (K-5). 원본과 같다 — 작업공간 전환 말고는 일어나지 않는 일이다. 유예를 둘지는 §6 Q-D.
- 매니페스트가 알려진 뒤, 상태가 모르는 id이거나 키 필드가 상태의 것과 다르면 구독을 거부한다: 두 필드 집합을 이름으로 댄 오류가 `onError`로 가고, 구독 열은 아무것도 내지 않고 끝나며, 쿼리는 바뀌지 않는다 (K-8, K-9).

### 2-8. `revoked` (제안)

원본 "`'revoked'` — the server does not serve this to you": "One status says the server stopped serving a `(id, key)` to this subject, however the client learned it. The mark is kept per `(id, key)` apart from the value, so it holds even when no value has arrived yet."

네 도착이 표시를 붙인다 — `subscription.revoked` (S-3), `connected.deniedEvents` (S-2), 구독 중인 상태를 뺀 스냅샷, 그리고 그 전에 시작된 읽기는 표시를 그대로 둔다. 표시를 지우는 것은 넷이다 — `subscription.granted`, 표시 뒤에 열린 연결의 프레임(첫 프레임 포함), 그런 연결의 스냅샷, 그런 연결을 위한 그림자 키 읽기의 답. 원본 표 여덟 행이 R-1–R-16이다.

- **해제는 연결로 한다, `at`으로 하지 않는다.** 원본: "the lift goes by the connection, not by `at`." 표시는 그것을 붙인 연결의 세대를 기억한다. 도착은 그것이 시작된 연결의 세대를 단다 (프레임은 그것을 실어 온 연결, 자동 읽기는 시작할 때의 세대). 표시의 세대보다 새 세대의 도착이 표시를 지운다. 네이티브의 세대는 스트림의 epoch다 (§2-6).
- **표시된 칸에 더 오래된 프레임이 오면** (브리프의 결함 예상): 값은 순서 규칙이 버리고, 표시는 그대로다 (R-9). 같은 연결이거나 더 오래된 연결의 프레임이므로 해제할 근거도 없다.
- 앱이 부른 `get`·`write`의 답은 꼬리표가 없다: 값은 순서 규칙대로 적용하고 표시는 건드리지 않는다 (원본 `get`·`write`가 `store.apply`만 부른다). 표시를 지우는 것은 연결의 증거뿐이다.
- 표시 전에 아무 값도 없었으면 항목은 `revoked(nil)`이다 — 원본의 "`{ status: 'revoked' }` alone".
- 자동 읽기는 시작한 세대와 시계(틱)를 단다. 원본: "Every automatic read is tagged with the connection it was started for, which is how a snapshot in flight across a revoke is told from one read after it." 그래서 더 새 연결이 생긴 뒤 도착한 스냅샷, 또는 읽기가 시작된 뒤 grant가 온 스냅샷은 누락을 `revoked`로 읽지 않는다 (R-15, R-16).

### 2-9. 연결 상태 (제안)

원본 "Connection status": `connected` — "a stream is open (the SSE `onOpen`)"; `reconnecting(attempt)` — "no stream open — not yet, or nothing subscribed — or the SSE client retrying a dropped one … `0` before the first stream opens, while the first snapshot is read or retried, and after `retry()`"; `failed` — "the retries are exhausted: the SSE client's `maxReconnectAttempts` …, or `maxRetries` for the first snapshot."

네이티브 스트림의 상태에서 도출한다. 이 순서로 첫 번째 맞는 행:

| 조건 | 연결 상태 | 까닭 |
|---|---|---|
| 구독 없음 | `reconnecting(0)` | 원본: "nothing subscribed", "The last unsubscribe … 'reconnecting' 0, the start value". 스트림은 SDK 수명으로 열려 있을 수 있다 (§2-2) |
| 매니페스트를 모르고, 첫 스냅샷이 `maxRetries`를 다 썼다 | `failed` | 원본 그대로 |
| 매니페스트를 모른다 (첫 스냅샷을 읽거나 재시도 중) | `reconnecting(0)` | 원본 그대로 |
| 스트림 `open(e, …)` | `connected` | `onOpen`의 짝. 네이티브는 `connected` 프레임에서 열린다 |
| 스트림 `connecting(n)` | **앞의 값 그대로.** 앞이 `failed`면 `reconnecting(0)` | 열린 연결에서 `connecting`으로 바로 가는 것은 쿼리 재연결(S-1)과 계정 전환(E-43)뿐이다. 원본: "a reopen on a key change while connected stays 'connected'." `failed`에서 오는 것은 재시작이다: "The status is 'reconnecting' 0 until it opens" |
| 스트림 `retrying(n, …)` | `reconnecting(n)` | `onReconnect(attempt)`의 짝 |
| 스트림 `offline(n)` | `reconnecting(n)` (n ≥ 1) | 아래 |
| 스트림 `idle(_)` | `reconnecting(0)` | 연결할 조건이 아니다 (뒤쪽, 로그아웃). "not yet"이다 |
| 스트림 `closed(_)` | `failed` | 아래 |

- **`offline`은 `reconnecting(n)`이다, 0도 새 값도 아니다.** 새 값(`offline`)을 더하면 세 플랫폼의 어휘가 갈리고, 앱 루트는 네트워크를 이미 `network` 변수로 따로 안다 (app-root-package §7의 `offline` 케이스는 플랫폼 출처). 0으로 두면 "attempt > 0이면 배너"라는 원본의 예 (`connection.attempt > 0`)가 네트워크가 없는 동안 아무것도 보이지 않는다. 브라우저에서는 네트워크를 잃으면 `EventSource`가 오류를 내고 `onReconnect(1)`이 온다 — 네이티브 `offline(n)`은 늘 n ≥ 1이므로 같은 쪽에 선다.
- **`attempt`의 수는 스트림의 것이다.** 네이티브 스트림은 처음 연결이 실패하면 `retrying(2)`로 간다 (E-6–E-9: `R(n+1)`). 웹이라면 1이다. 세 플랫폼이 약속하는 것은 "`attempt == 0` ⇔ 마지막 열림 또는 재시작 뒤 실패가 없었다"이다. 정확한 수는 Swift·Kotlin 사이에서만 같다 (공유 픽스처가 고정한다).
- **`closed`는 `failed`다.** 네이티브 스트림은 재시도를 다 쓰는 일이 없다 (백오프 상한 30 s, 끝없이). 원본도 기본값으로는 그렇다: "Both default to `0`, retrying forever: the status never turns 'failed'." 네이티브에서 스트림 쪽 `failed`는 서버가 거절해 닫은 경우(`unauthorized`·`forbidden`·`unknownEvents`)뿐이다. 스트림에 시도 상한을 더할지는 §6 Q-E.
- **재시작.** 원본: "`retry()` and the browser's `online` event … restart when 'failed' or 'reconnecting'; a `subscribe` restarts when 'failed', reopening a failed stream even when the query is unchanged. An unsubscribe that leaves subscribers never does." 네이티브의 짝:
  - `retry()`: 매니페스트를 모르면 첫 스냅샷을 지금 읽는다 (수 초기화, 진행 중이면 겹치지 않음). 알면 `stream.reconnectNow()` (S-4).
  - `online`: 상태 클라이언트는 네트워크를 직접 보지 않는다 — 이벤트 모듈이 이미 `NWPathMonitor`/`ConnectivityManager`를 보고 스트림을 `offline`에서 꺼낸다 (E-35). 상태 클라이언트는 스트림이 **`offline`을 벗어나는 전이**를 `online`의 짝으로 읽는다: 매니페스트를 모르고 재시도 타이머가 걸려 있으면 지금 읽는다 (C-20).
- **첫 스냅샷의 재시도.** `retryDelayMillis` (기본 3 000), `maxRetries` (기본 0 = 끝없이). 원본 그대로. 타이머는 수면을 포함하는 시계를 쓴다 (P40).
- `subscribeConnection()`은 **현재 값을 먼저** 내고 바뀔 때마다 낸다 (Kotlin `StateFlow`, Swift는 이벤트 스트림의 `states`와 같은 약속). 원본의 청취자는 바뀔 때만 불리지만, 원본의 `useConnectionStatus`는 현재 값을 읽고 구독한다 — 네이티브의 열 하나가 그 둘이다. 같은 값은 다시 내지 않는다 (C-22, C-23).

### 2-10. `initial` (제안)

원본: "`initial: { snapshot, query? }` is the snapshot a server render read … The client installs its manifest and applies each state it holds at construction, keyed states under the query's key, so `current()` answers at once and the first `subscribe` opens the stream without reading the snapshot first. Nothing is marked 'revoked' from it: an omission in the render's snapshot is not a denial by the stream."

- 네이티브에는 서버 렌더가 없다. 짝은 둘이다: (1) 앱이 이미 읽은 스냅샷 — 같은 실행에서 앞선 `snapshot()`의 답 (예: 키 없는 스냅샷을 읽은 뒤 키를 도출하고 클라이언트를 새로 만드는 테스트), (2) 테스트·픽스처. 디스크에 저장해 둔 지난 실행의 스냅샷은 권하지 않는다 — 원본 "Pass what `api.getStates` answered, not a hand-built snapshot"과 같은 까닭이고, 지난 계정의 것일 수 있다.
- 타입: `SPFNStatesSnapshot`/`SpfnStatesSnapshot` — `snapshot()`이 돌려주는 값 그대로. `at`, 매니페스트, 상태별 정규값을 갖는다. 정규값에서 만드는 이니셜라이저 하나 (`init(canonical:) throws`)가 픽스처를 위해 있다.
- 세대 0으로 적용한다. 스트림은 아직 없다 — 연결 상태는 `reconnecting(0)`이다 (I-4). 첫 열림의 첫 프레임은 더 새 `at`으로 온다.
- 매니페스트가 처음부터 있으므로 잘못된 구독은 곧바로 `onError`다 (I-7).

### 2-11. API 모양 (제안)

원본의 멤버를 이름 그대로 옮긴다. 다른 점은 셋이다.

1. **id 대신 서술.** 원본은 `typeof states`로 id·키·값을 타입에 싣는다. 네이티브에는 서버 타입이 없으므로 앱 계약 코드젠이 상태마다 서술 값 하나를 만들고 (§2-13-2, §2-13-4), 모든 멤버가 서술을 받는다. 서술의 제네릭이 값·데이터 타입을 싣는다. 쓰기가 있는 상태만 쓰기 서술 타입이 되므로, 쓰기 없는 상태의 `write`는 컴파일되지 않는다 — 원본 "Typed only for a state with a write"의 짝. 원본의 런타임 거부("A `write` on a state the manifest marks `write: false` rejects naming the state, before any request")도 남긴다: 서술과 서버의 매니페스트가 어긋날 때 (I-11).
2. **청취자 대신 열.** 원본 `subscribe(id, key, listener)`는 해지 함수를 돌려준다. 네이티브는 이벤트 스트림의 `listen`처럼 **열**을 돌려준다: Swift `AsyncStream`, Kotlin `Flow`. 열이 소비되는 동안이 구독이고, 소비를 끝내면 (태스크 취소, 수집 취소) 해지다. 원본 "The same function subscribed twice is two subscriptions"는 열 둘로 그대로 성립한다. 열은 **현재 항목을 먼저** 낸다 — 원본 훅이 `getSnapshot`을 먼저 읽는 것의 짝이다.
3. **`{ value, data }`와 상태.** 원본 `StateEntry`는 `status`와 선택적 `at`·`value`·`data`의 유니언이고, 훅은 `'loading'`을 더한다. 네이티브는 셋을 하나의 열거로:

| | Swift | Kotlin |
|---|---|---|
| 프레임 | `struct SPFNStateFrame<Value, Data>: Equatable, Sendable { let at: String; let value: Value; let data: Data }` | `data class SpfnStateFrame<out V, out D>(val at: String, val value: V, val data: D)` |
| 항목 | `enum SPFNStateEntry<Value, Data> { case loading; case ready(SPFNStateFrame<Value, Data>); case revoked(SPFNStateFrame<Value, Data>?) }` | `sealed interface SpfnStateEntry<out V, out D> { data object Loading; data class Ready(val frame); data class Revoked(val frame: SpfnStateFrame<V, D>?) }` |
| 편의 | `status: SPFNStateStatus` (`.loading`·`.ready`·`.revoked`), `frame: SPFNStateFrame?`, `value: Value?` | 같은 이름의 확장 프로퍼티 |
| `.data()` 없는 상태 | `Data = SPFNNoStateData` (빈 `Equatable` 구조체) | `D = Unit` |

- `loading`은 값이 없다, `ready`는 늘 있다, `revoked`는 있을 수도 없을 수도 있다 — 원본이 허락하는 조합만 표현된다. 그래서 "값이 없는 `ready`"나 "`at`만 있는 항목"을 앱이 다룰 일이 없다 (app-root-package §12 U4·U5의 결함 예상 "옵셔널·enum 매핑 불일치"를 타입이 막는다).
- 값 타입 `Value`는 생성된 응답의 `value` 필드 타입이다. 문자열 상수 유니언이면 생성기의 열린 enum (`unknown(String)`/`Unknown`)이다 (app-contract-codegen T11). 상태 클라이언트는 값의 모양을 모른다.
- 원본 `current()`는 항목이 없으면 `undefined`다. 네이티브 `current`는 `.loading`을 돌려준다 — 원본 훅이 `undefined`를 `'loading'`으로 읽는 것과 같다. 원본의 `undefined`와 `'loading'`을 가를 소비자가 없다.

### 2-12. 관찰 어댑터는 `events` 모듈에 있다 (결정, 운영자 2026-10-17)

원본: "`@spfn/core/state/react` puts one state into a component." 훅 둘 — `useServerState(client, id, key?)`, `useConnectionStatus(client)` — 이 `useSyncExternalStore` 위에 서고, "`react` … is an **optional peer** … `@spfn/core/state/client` is framework-neutral." 네이티브의 짝은 Swift `@Observable` 모델과 그것을 뷰에 싣는 속성 래퍼, Kotlin `@Composable` 함수다.

| | `events` 모듈 (**결정**, Q-F) | 앱 루트 패키지가 만든다 (첫 권고, 뒤집힘) | `state` 모듈 안 |
|---|---|---|---|
| 원본과의 대응 | `@spfn/core/state/react`의 짝. 툴킷을 이미 가진 모듈이 툴킷 쪽 반을 갖고, 툴킷 없는 반(`state`)은 그대로다 — 원본의 `client`/`react` 나눔과 같은 선 | 앱마다 같은 열 줄을 다시 쓴다. Superself 밖의 앱은 짝이 없다 | 같은 모듈에 두 반 |
| 비용 | 간선 `events → state` 하나 (§7). `events`를 링크하는 앱은 `state`(툴킷 없음, `client`와 coroutines만)도 링크한다 | 없음 | Android `state`에 Compose runtime 별칭. §2-1 (가)의 이유가 사라진다 |
| 앱이 쓰는 코드 | 상태마다 선언 한 줄 (§2-13-4) | 상태마다 모델의 필드·구독 루프·취소 | 같다 |
| 기존 메커니즘 | `events`의 `onSPFNEvent`·`SpfnEventEffect`: 환경에서 객체를 찾고, 뷰가 떠나면 듣기가 끝나고, 루트 부착 없이 쓰면 프로그래머 오류 (event-stream-design §3-4). 어댑터가 같은 규칙을 따른다 | — | — |

운영자의 근거: 웹 앱이 쓰는 코드(`createStateClient` + `useServerState` + `useConnectionStatus`)와 견주면, Q-F와 Q-G를 함께 뒤집어야 네이티브 앱이 웹보다 더 쓰는 것이 **스트림 생성 한 줄과 루트 부착 한 줄**로 준다 (§2-13-4의 비교표).

**API** (이벤트 모듈. Swift는 `#if canImport(SwiftUI)`로 통째로 감싼 파일, Kotlin은 `spfn-events`):

| 역할 | Swift | Kotlin |
|---|---|---|
| 루트 부착 (기존 것에 인자 하나) | `func spfnEventStream(_ stream: SPFNEventStream, keyLifecycle: SPFNKeyLifecycle, stateClient: SPFNStateClient) -> some View` — 기존 두 인자 판의 겹침 | `SpfnEventStreamHost(stream: SpfnEventStream, keyLifecycle: SpfnKeyLifecycle, stateClient: SpfnStateClient? = null, content: @Composable () -> Unit)` |
| 환경 | `EnvironmentValues.spfnStateClient: SPFNStateClient?` | `LocalSpfnStateClient` (`staticCompositionLocalOf`, 기본값은 던진다) |
| 상태 하나 | `@propertyWrapper struct SPFNServerState<Value, Data>: DynamicProperty` — `init(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey = [:])`, `wrappedValue: SPFNStateEntry<Value, Data>`, `projectedValue: SPFNServerStateModel<Value, Data>` | `@Composable fun <V, D> rememberServerState(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): State<SpfnStateEntry<V, D>>` |
| 모델 | `@MainActor @Observable public final class SPFNServerStateModel<Value, Data>` — `entry`, 편의 `status`·`frame`·`value` (§2-11). 공개 이니셜라이저 없음: 래퍼가 만든다 | (Compose `State`가 그 자리다) |
| 연결 상태 | `@propertyWrapper struct SPFNConnectionStatus: DynamicProperty` — `wrappedValue: SPFNStateConnection`, `projectedValue: SPFNConnectionStatusModel` (`@Observable`, `connection`) | `@Composable fun rememberConnectionStatus(): State<SpfnStateConnection>` |
| 툴킷 없는 몸 | `@MainActor final class SPFNServerStateDriver<Value, Data>` — `update(client:state:key:)`, `end()`, `entry`, 바뀜 콜백. 모델과 래퍼는 이것 위의 얇은 껍질이다 | `class SpfnServerStateDriver<V, D>` — 같은 멤버, 수집은 부르는 코루틴에서 |

쓰기와 다시 시도는 어댑터에 없다. 앱은 환경의 클라이언트로 `write`·`retry()`를 부른다 (§2-13-3). 원본의 훅이 `write`를 결과에 싣는 것은 TypeScript가 상태 id로 쓰기 유무를 타입에 싣기 때문이다. Swift 속성 래퍼는 선언된 타입에서 제네릭을 추론하므로 입력 타입을 하나 더 실으면 키 있는 선언이 길어진다 — 한 줄의 `state.write(AppStates.consent, input: …)`가 더 짧다.

**행동.** 모두 이벤트 모듈의 기존 규칙에 묶는다.

- **현재 값 먼저.** 첫 그리기가 `client.current(state, key)`를 동기로 읽는다 — 원본 훅의 `getSnapshot`. 캐시된 항목이나 `initial`이 있으면 첫 그리기부터 `ready`이고 `loading`을 거치지 않는다 (A-1). 그다음 `subscribe` 열을 소비한다. 열의 첫 항목은 같은 값이므로 다시 그리지 않는다. 본 적 없는 키는 항목이 생길 때까지 `loading`이다 (A-2). 연결 상태도 같다: `connection()`을 먼저, 그다음 `subscribeConnection()` (A-7).
- **메인 액터에서.** Swift의 드라이버·모델은 `@MainActor`이고, 열의 소비는 메인 액터에 묶인 태스크가 한다. Kotlin은 `LaunchedEffect` 안에서 수집한다 — 컴포지션의 메인 디스패처다. 상태 클라이언트의 엔진(내부 actor, 받은 스코프)은 메인이 아니다. 그 경계를 넘는 것은 열의 원소 하나뿐이다 (A-6).
- **키는 값으로 비교한다.** 원본: "The key is compared by its fields (stable-stringified), so `{ workspaceId }` written inline does not resubscribe on every render; another `workspaceId` unsubscribes and subscribes." Swift는 `DynamicProperty.update()`(본문을 그리기 전마다)에서 `(클라이언트, id, 키)`를 앞의 값과 `==`로 견주고, Kotlin은 `LaunchedEffect(client, state.id, key)`의 키 같음으로 견준다. 같으면 아무것도 하지 않고 (A-3), 다르면 앞 구독을 끝내고 새 키의 현재 값 먼저 새 구독을 연다 (A-4). 키가 바뀌는 것은 `onSPFNEvent(id:)`·`SpfnEventEffect(key)`의 `id`/`key`가 바뀌는 것과 같은 자리다 (event-stream-design §3-4).
- **뷰가 떠나면 해지.** 이벤트 모듈 규칙 "듣기의 끝은 청취자의 끝이다"의 짝 (event-stream-design §3-4): 구독은 열의 소비이고, 소비가 취소되면 상태 클라이언트에서 해지된다 (§2-11, K-5, C-18). Kotlin은 `SpfnEventEffect`와 같다 — `LaunchedEffect`가 컴포지션을 떠날 때 취소된다. Swift 속성 래퍼에는 `.task`가 없다: 모델이 소비 태스크를 쥐고, SwiftUI가 그 뷰의 상태 저장소를 놓을 때(뷰가 계층을 떠날 때) 모델의 `deinit`이 태스크를 취소한다 (A-5). `onSPFNEvent`의 `.task`와 다른 한 곳은 **다른 화면에 가려진 뷰**다 — `.task`는 사라질 때 취소되고, 래퍼는 계층에 남아 있는 동안 구독을 쥔다. 상태 구독은 연결 쿼리에 키를 싣는 일이라, 가려질 때마다 끊으면 그 키의 마지막 구독이 떠나 재연결한다 (K-5) — 이벤트 모듈의 "이동은 재연결하지 않는다" (L-6)를 깨는 쪽이다. 돌아왔을 때 보이는 값은 어느 쪽이든 같다: 본 적 있는 키는 캐시된 `ready`를 먼저 보이고 재연결의 프레임이 덮는다 (K-12).
- **루트 부착 없이 쓰면 프로그래머 오류다.** 환경에 상태 클라이언트가 없으면 Swift `preconditionFailure`, Kotlin `IllegalStateException` — `onSPFNEvent`·`SpfnEventEffect`와 같다 (A-8). 부착이 받은 상태 클라이언트의 스트림이 부착된 스트림과 다르면 부착하는 순간 프로그래머 오류다: 쿼리의 주인은 그 스트림의 상태 클라이언트 하나다 (§2-2, A-9).
- Linux·JVM: 드라이버와 부착 검사는 툴킷이 없어 Linux·JVM 단위 테스트로 돈다 (`SPFNForegroundTally`와 같은 자리). SwiftUI 래퍼·모델과 Compose 함수는 그 위의 몇 줄이다.

### 2-13. 공개 API (제안)

어휘는 두 플랫폼에서 같다. Swift는 `SPFN`, Kotlin은 `Spfn` 접두사.

| 역할 | 모듈 | Swift | Kotlin |
|---|---|---|---|
| 클라이언트 | 상태 | `SPFNStateClient` | `SpfnStateClient` |
| 설정 | 상태 | `SPFNStateClientConfiguration` | `SpfnStateClientConfiguration` |
| 상태 서술 | 상태 | `SPFNStateDescriptor<Value, Data>`, `SPFNWritableStateDescriptor<Value, Data, Input>` | `SpfnStateDescriptor<V, D>`, `SpfnWritableStateDescriptor<V, D, I>` |
| 키 | 상태 | `typealias SPFNStateKey = [String: String]` | `typealias SpfnStateKey = Map<String, String>` |
| 프레임·항목 | 상태 | `SPFNStateFrame`, `SPFNStateEntry`, `SPFNStateStatus`, `SPFNNoStateData` | `SpfnStateFrame`, `SpfnStateEntry`, `SpfnStateStatus`, `Unit` |
| 스냅샷 | 상태 | `SPFNStatesSnapshot` (`at`, `manifest`, `init(canonical:)`) | `SpfnStatesSnapshot` |
| 연결 상태 | 상태 | `enum SPFNStateConnection { case connected; case reconnecting(attempt: Int); case failed }` | `sealed interface SpfnStateConnection { data object Connected; data class Reconnecting(val attempt: Int); data object Failed }` |
| 초기값 | 상태 | `SPFNStateInitial(snapshot:query:)` | `SpfnStateInitial(snapshot, query)` |
| 스트림 확장 | 클라이언트 | `setQuery(_:)`, `listenWire(_:)`, `SPFNWireSignal`, `SPFNStreamConnected`, `reconnectNow()` (§5-1) | `setQuery`, `listenWire`, `SpfnWireSignal`, `SpfnStreamConnected`, `reconnectNow()` |
| 관찰 어댑터 | 이벤트 | `SPFNServerState`, `SPFNServerStateModel`, `SPFNConnectionStatus`, `SPFNConnectionStatusModel`, `SPFNServerStateDriver`, `EnvironmentValues.spfnStateClient`, `spfnEventStream(_:keyLifecycle:stateClient:)` (§2-12) | `rememberServerState`, `rememberConnectionStatus`, `SpfnServerStateDriver`, `LocalSpfnStateClient`, `SpfnEventStreamHost(…, stateClient)` |
| 생성된 상태 | 앱 (코드젠 출력) | `AppStates.<id>`, `AppStates.all`, `AppStates.events`, `AppStates.snapshot` (§2-13-2) | 같은 이름, `object AppStates` |

#### 2-13-1. 설정과 생성

| Swift | Kotlin |
|---|---|
| `SPFNStateClientConfiguration(stream: SPFNEventStream, keyLifecycle: SPFNKeyLifecycle, states: [any SPFNAnyStateDescriptor], snapshot: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Void, some Sendable>, onError: @escaping @Sendable (Error) -> Void = SPFNStateClient.logError, retryDelayMillis: Int64 = 3_000, maxRetries: Int = 0, initial: SPFNStateInitial? = nil)` | `SpfnStateClientConfiguration(stream: SpfnEventStream, keyLifecycle: SpfnKeyLifecycle, states: List<SpfnStateDescriptor<*, *>>, snapshot: (SpfnStateKey) -> SpfnCall<Unit, *>, onError: (Throwable) -> Unit = SpfnStateClient::logError, retryDelayMillis: Long = 3_000, maxRetries: Int = 0, initial: SpfnStateInitial? = null)` |
| `SPFNStateClient(configuration:)` | `SpfnStateClient(configuration, scope: CoroutineScope)` |

설정은 생성물을 받는다. 앱이 손으로 쓰는 값은 스트림·라이프사이클·스코프뿐이다:

```swift
// 앱 합성 지점. AppStates는 앱 계약 코드젠의 출력이다 (§2-13-2).
let events = try SPFNEventStream(
    keyLifecycle: keyLifecycle,
    configuration: .init(events: AppStates.events + ["sessionActivity"])
)
let state = try SPFNStateClient(configuration: .init(
    stream: events,
    keyLifecycle: keyLifecycle,
    states: AppStates.all,
    snapshot: AppStates.snapshot
))
```

```kotlin
val events = SpfnEventStream(
    keyLifecycle = keyLifecycle,
    configuration = SpfnEventStreamConfiguration(events = AppStates.events + listOf("sessionActivity")),
    scope = applicationScope,
);
val state = SpfnStateClient(
    SpfnStateClientConfiguration(
        stream = events,
        keyLifecycle = keyLifecycle,
        states = AppStates.all,
        snapshot = AppStates.snapshot,
    ),
    scope = applicationScope,
);
```

- 스트림의 이름 목록에 `AppStates.events`를 더하는 것은 앱이다: 스트림은 상태 말고도 앱의 다른 이벤트를 싣고, "앱이 설정하는 이름 목록은 고정이다"는 이벤트 스트림의 규칙 그대로다. 빠뜨리면 생성 시각의 거부가 상태 이름을 댄다 (아래 첫 항목).

- 생성 시각의 거부 (프로그래머 오류, 이벤트 스트림 설계 §3-1과 같은 자리): 서술의 id가 겹침, 서술의 이벤트 이름 `state.<id>.changed`가 스트림 설정에 없음, 같은 스트림에 상태 클라이언트가 이미 있음 (쿼리의 주인은 하나, §2-2). Swift `throws SPFNStateClientError.invalidConfiguration(field)`, Kotlin `IllegalArgumentException`.
- `onError`는 원본과 같은 것을 받는다: "a failure of something the client ran on its own — the first snapshot, the snapshot after the stream opened, the read after a grant — and a refused `subscribe`". 앱이 부른 호출은 거부로 끝난다. 기본은 오류 이름만 기록한다 — 키와 값은 앱의 데이터라 기록하지 않는다 (원본 `notify`의 "The state id only: the key is the app's data").
- 취소는 실패가 아니다 (P16): `close()`가 끊은 읽기, 소비가 끝난 구독의 취소는 `onError`로 가지 않는다. 그물이 넓은 `catch`는 취소 절을 먼저 둔다 (P26).
- 동시성: `public final class SPFNStateClient: Sendable` — 동기 읽기(`current`, `connection`)는 잠금 하나 아래의 값을, 나머지는 내부 actor 엔진이 한다. `SPFNEventStream`과 같은 모양이다. Kotlin은 받은 스코프에서 돈다.

#### 2-13-2. 상태 서술 — 생성된 타입 (Q-G 결정, 운영자 2026-10-17)

서술의 모양은 그대로다. 바뀐 것은 누가 쓰느냐다: **앱 계약 코드젠이 상태마다 하나를 생성하고, 앱은 쓰지 않는다.** 이니셜라이저는 공개로 남는다 — 픽스처와 테스트가 가짜 호출로 서술을 만든다.

| Swift | Kotlin |
|---|---|
| `SPFNStateDescriptor<Value, Data>.init<Response>(id: String, keyFields: [String] = [], read: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Void, Response>, frame: @escaping @Sendable (Response) -> SPFNStateFrame<Value, Data>)` | `SpfnStateDescriptor<V, D>(id: String, keyFields: List<String> = emptyList(), read: (SpfnStateKey) -> SpfnCall<Unit, R>, frame: (R) -> SpfnStateFrame<V, D>)` |
| `func writable<Input, Response>(write: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Input, Response>, frame: @escaping @Sendable (Response) -> SPFNStateFrame<Value, Data>) -> SPFNWritableStateDescriptor<Value, Data, Input>` | `fun <I, R> writable(write: (SpfnStateKey) -> SpfnCall<I, R>, frame: (R) -> SpfnStateFrame<V, D>): SpfnWritableStateDescriptor<V, D, I>` |

**생성 규칙.** 항목마다 견준 길과 함께 (저장소의 설계 문서 관례):

| 항목 | 결정 | 견준 길 | 까닭 |
|---|---|---|---|
| 알아보는 규칙 | **이름 + 모양, `getStates`가 닻이다.** 선택된 `getStates`(GET, 본문 없음, 응답이 필수 `at: string`·`states: object`·`manifest: object`이고 `states`와 `manifest`의 속성 집합이 같다)의 `states` 속성 이름이 상태 id다. id마다 `get<Id>State`(`<Id>`는 첫 글자 대문자)가 문서에 있어야 하고, GET이고, 응답이 정확히 `{ at: string, value, data? }`이며 `value`·`data` 스키마가 `getStates`의 그 id 항목과 같다. `put<Id>State`가 있으면 PUT이고 응답이 읽기와 같다. 닻이 없으면 아무것도 알아보지 않는다: `get<Id>State`는 보통의 호출로만 생성된다 | (나) 이름 패턴만 (`get*State`·`put*State`·`getStates`): 앱이 손으로 만든 라우트 `getFooState`가 상태가 된다. (다) 문서의 표시: spfn의 `.contract()`는 **표시를 싣지 않는다** — 상태 연산은 보통 연산과 같은 키(`name`·`method`·`path`·`auth`·`requiresSession`·`since`·`request`·`response`)만 갖는다 (`@spfn/core/state` README "Contract", `defineState`의 `StateContract`). 표시를 더하려면 상류 변경이 먼저이고, 이 생성기는 모르는 연산 키를 거부하므로(R10) 표시를 싣는 서버가 옛 생성기를 모두 깬다 | 문서가 이미 가진 것만으로 닫힌다. `getStates`의 응답은 라우터가 **파생한** 스키마라서 상태 id 목록이 곧 서버의 선언이다 — 이름 패턴이 우연히 맞는 손 라우트는 그 목록에 없다. 상류가 나중에 표시를 더하면 대조로 쓰고 이 규칙을 바꾸지 않는다 |
| 선택 | 연산 선택(`contract-operations.json`)에 **`getStates` 하나**를 적으면, 그 스키마가 대는 id의 `get<Id>State`·`put<Id>State`가 함께 선택된다. 생성 파일 머리의 `operations:`가 그것을 모두 적는다. 앱이 그것을 따로 적어도 오류가 아니다 | 상태 연산을 앱이 하나씩 적는다 | 상태마다 앱이 한 줄을 쓰는 것이 결정이 없애려는 것이다. 서버에 상태를 더하면 재생성만으로 서술이 생긴다 (digest가 바뀌므로 verify가 재생성을 요구한다, app-contract-codegen §5) |
| 출력과 이름 | `AppStates.<id>`: 쓰기가 없으면 `SPFNStateDescriptor<Value, Data>`/`SpfnStateDescriptor<V, D>`, 있으면 `SPFNWritableStateDescriptor<Value, Data, Input>`/`SpfnWritableStateDescriptor<V, D, I>`. `Value`는 읽기 응답의 `value` 필드의 생성 타입(app-contract-codegen §3: 객체·enum이면 `Get<Id>StateResponseValue`, 스칼라면 T1·T2·T4), `Data`는 `Get<Id>StateResponseData` 또는 `SPFNNoStateData`/`Unit`, `Input`은 `Put<Id>StateBody`. `AppStates.all` (`[any SPFNAnyStateDescriptor]`/`List<SpfnStateDescriptor<*, *>>`), `AppStates.events` (`[String]`/`List<String>`, `state.<id>.changed`), `AppStates.snapshot`. 순서는 모두 id 순 (문서 키 순서에 기대지 않는다, app-contract-codegen §1-3) | 출력을 `AppAPI` 네임스페이스 안에 (`AppAPI.States.consent`) | 상태는 호출이 아니라 호출 둘을 묶은 값이다. 네임스페이스를 따로 두면 `AppAPI`의 타입 이름 공간과 부딪히지 않는다 |
| 스냅샷 호출 | **생성된 `AppStates.snapshot`** — `(SPFNStateKey) -> SPFNCall<Void, GetStatesResponse>`, 쿼리의 필드를 `AppAPI.getStates(…)`의 이름 붙은 인자로 옮긴다 | 지금처럼 `AppAPI.getStates`를 앱이 감싼다 (`{ AppAPI.getStates(workspaceId: $0["workspaceId"]) }`) | 감싸는 한 줄은 키 필드 이름을 앱이 다시 적는 일이다 — 읽기 서술의 키 옮기기와 같은 것이고, 같은 까닭으로 생성한다. `AppAPI.getStates`는 그대로 있다: 앱이 `snapshot()` 밖에서 부를 수 있다 |
| `keyFields`의 출처 | **읽기 연산의 쿼리.** `get<Id>State`의 `request.query` 속성 이름 (모두 필수 `string`이어야 한다 — spfn `.key()`가 그렇게만 허락한다), 이름 순. 키 없는 상태는 쿼리가 없다. `put<Id>State`의 쿼리는 같은 집합이어야 한다 | `getStates` 응답의 `manifest.<id>.key` | 매니페스트 스키마는 `key: string[]`라는 **타입**만 싣고 값을 싣지 않는다 — 값은 런타임에만 있다. 쿼리 속성 집합은 문서에 있는 값이다 |
| 매니페스트와 어긋날 때 | **K-8 그대로.** 서버가 진실이다: 매니페스트의 키 필드가 서술의 `keyFields`와 다르면 그 상태의 구독은 두 집합을 이름으로 댄 오류로 `onError`에 가고 거부된다. 그리고 상태 클라이언트는 키의 필드 집합이 서술의 `keyFields`와 다른 키로 `read`·`write`를 만들지 않는다 — 같은 거부가 매니페스트를 알기 전에도 선다. 그래서 생성된 읽기 함수는 늘 모든 키 필드를 받는다 (`key["workspaceId"]!`, `key.getValue("workspaceId")`) | 생성 코드가 빠진 필드를 빈 문자열로 채운다 | 빈 문자열은 서버에서 다른 키다 — 조용히 틀린 값을 읽는다. 어긋남은 서버가 키를 바꾼 뒤 재생성하지 않은 앱이고, 계약 게이트가 필수 쿼리의 추가를 거부하므로 드물다 |
| 거부 (이름을 대고 멈춘다) | (1) 값·데이터 스키마를 표가 담지 못한다 — `Type.Unknown`(R1), 소수(T3), 객체 유니언(R3): 상태 id와 경로를 댄다. `getStates`의 응답이 **모든** 상태의 값을 실으므로 한 상태가 생성 전체를 막는다. 그 상태를 `.contract()`에서 빼거나 스키마를 고친다. (2) `getStates`의 id에 `get<Id>State`가 없다, 메서드·응답 모양이 위와 다르다, 읽기와 스냅샷의 값 스키마가 다르다, 쓰기의 쿼리나 응답이 읽기와 다르다, 쓰기 본문이 객체가 아니다 (T16). (3) id가 `^[a-z][a-zA-Z0-9]*$`가 아니다 (spfn `defineState`와 같은 규칙). (4) id가 `all`·`events`·`snapshot`이다 — 생성 멤버와 겹친다 (R9). (5) 키 있는 상태의 필드가 `getStates`의 쿼리에 없다 (스냅샷을 그 키로 읽을 수 없다). 그리고 기존 T15: `clientProofV1` 연산의 쿼리 (P-c) | 표 밖의 값을 `SPFNCanonicalValue`로 두고 생성한다 | 기존 R1·T3·R3 행과 같은 판단이다 (P8): 추측해 방출하면 컴파일되는 거짓말이다. 정규값 탈출구는 앱 계약 코드젠이 다른 연산에도 열지 않은 문이다 |
| 네임스페이스와 파일 | 새 Gradle 속성 `spfn.appContract.statesNamespace` (예: `AppStates`). **선택에 `getStates`가 있으면 필수, 없으면 주면 거부.** 출력은 기존 출력 디렉터리에 파일 하나씩: `<StatesNamespace>.swift`, `<StatesNamespace>.kt` (`object`). 머리·verify 규칙은 다른 생성 파일과 같다 | `<swiftNamespace>States`로 파생 (`AppAPIStates`) | 기존 여섯 속성처럼 기본값으로 빠지지 않는다 — 오타가 초록으로 읽히면 P8(3)이다. 기존 소비자(상태 없음)의 태스크는 그대로 돈다 |

생성 결과 (Superself의 두 상태를 예로. `AppAPI`는 같은 실행의 호출 출력이다):

```swift
// GENERATED FILE — DO NOT EDIT. (머리는 app-contract-codegen §5와 같다)
public enum AppStates
{
    public static let aiAccounts = SPFNStateDescriptor(
        id: "aiAccounts",
        keyFields: ["workspaceId"],
        read: { key in AppAPI.getAiAccountsState(workspaceId: key["workspaceId"]!) },
        frame: { SPFNStateFrame(at: $0.at, value: $0.value, data: SPFNNoStateData()) }
    )

    public static let consent = SPFNStateDescriptor(
        id: "consent",
        read: { _ in AppAPI.getConsentState() },
        frame: { SPFNStateFrame(at: $0.at, value: $0.value, data: $0.data) }
    ).writable(
        write: { _ in AppAPI.putConsentState() },
        frame: { SPFNStateFrame(at: $0.at, value: $0.value, data: $0.data) }
    )

    public static let all: [any SPFNAnyStateDescriptor] = [aiAccounts, consent]

    public static let events: [String] = ["state.aiAccounts.changed", "state.consent.changed"]

    public static let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, AppAPI.GetStatesResponse> =
        { query in AppAPI.getStates(workspaceId: query["workspaceId"]) }
}
```

```kotlin
// GENERATED FILE — DO NOT EDIT.
object AppStates
{
    val aiAccounts = SpfnStateDescriptor(
        id = "aiAccounts",
        keyFields = listOf("workspaceId"),
        read = { key -> AppAPI.getAiAccountsState(workspaceId = key.getValue("workspaceId")) },
        frame = { SpfnStateFrame(it.at, it.value, Unit) },
    );

    val consent = SpfnStateDescriptor(
        id = "consent",
        read = { AppAPI.getConsentState() },
        frame = { SpfnStateFrame(it.at, it.value, it.data) },
    ).writable(
        write = { AppAPI.putConsentState() },
        frame = { SpfnStateFrame(it.at, it.value, it.data) },
    );

    val all: List<SpfnStateDescriptor<*, *>> = listOf(aiAccounts, consent);

    val events: List<String> = listOf("state.aiAccounts.changed", "state.consent.changed");

    val snapshot: (SpfnStateKey) -> SpfnCall<Unit, GetStatesResponse> =
        { query -> AppAPI.getStates(workspaceId = query["workspaceId"]) };
}
```

- `frame`은 생성된 응답에서 세 필드를 꺼내는 한 줄이다. 응답 타입이 상태마다 달라서 SDK가 대신 쓸 수 없고, 그래서 생성기가 쓴다.
- 생성기는 SDK 모듈에 의존하지 않는 채로 남는다 (tools/contract-codegen README "No SDK module is involved in generation"): 이름 `SPFNStateDescriptor` 등을 문자열로 방출할 뿐이다. 생성 파일을 컴파일하는 앱이 `SPFNState`/`spfn-state`를 링크한다.
- 생성기 픽스처(`src/test/resources/app-contract/`)에 상태 라우터 하나 — 키 없는 쓰기 상태, 키 있는 상태, `.data()` 있는 상태 — 와 거부 문서들을 더한다. 생성된 `AppStates` 픽스처는 상태 모듈의 테스트 타깃(`Tests/SPFNStateTests`, `spfn-state/src/test`)에 들어가 상태 클라이언트 테스트가 그것을 서술로 쓴다 — 생성물과 클라이언트가 함께 컴파일되는 증거다.

#### 2-13-3. 멤버

| 원본 | Swift | Kotlin | 행동 (원본과 같다, 다른 곳만 적는다) |
|---|---|---|---|
| `subscribe(id, key?, listener)` | `func subscribe<V, D>(_ state: SPFNStateDescriptor<V, D>, key: SPFNStateKey = [:]) -> AsyncStream<SPFNStateEntry<V, D>>` | `fun <V, D> subscribe(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): Flow<SpfnStateEntry<V, D>>` | 열. 현재 항목을 먼저, 그다음 status·value·data가 바뀔 때마다. 소비가 끝나면 해지. Swift는 부른 순간 붙는다 (이벤트 스트림 H-9와 같다: `for await` 바로 앞에서 부른다). Kotlin은 수집하는 동안 붙는다 |
| `current(id, key?)` | `func current<V, D>(_ state:, key:) -> SPFNStateEntry<V, D>` | `fun <V, D> current(state, key): SpfnStateEntry<V, D>` | 동기. 없으면 `.loading` (§2-11) |
| `get(id, key?)` | `func get<V, D>(_ state:, key:) async throws -> SPFNStateFrame<V, D>` | `suspend fun <V, D> get(state, key): SpfnStateFrame<V, D>` | 매니페스트를 먼저 (필요하면 첫 스냅샷), 답을 순서 규칙으로 적용하고 돌려준다 |
| `write(id, input, key?)` | `func write<V, D, I>(_ state: SPFNWritableStateDescriptor<V, D, I>, input: I, key:) async throws -> SPFNStateFrame<V, D>` | `suspend fun <V, D, I> write(state: SpfnWritableStateDescriptor<V, D, I>, input: I, key): SpfnStateFrame<V, D>` | 답을 곧바로 적용 — 쓰기가 보내는 프레임보다 먼저. 매니페스트가 `write: false`면 요청 전에 거부 |
| `snapshot(query?)` | `func snapshot(query: SPFNStateKey? = nil) async throws -> SPFNStatesSnapshot` | `suspend fun snapshot(query: SpfnStateKey? = null): SpfnStatesSnapshot` | 기본은 스트림의 쿼리. 있는 상태를 적용하고 구독 중인데 빠진 상태를 `revoked`로. 거부되면 아무것도 바꾸지 않는다 |
| `connection()` | `func connection() -> SPFNStateConnection` | `fun connection(): SpfnStateConnection` | 동기 |
| `subscribeConnection(listener)` | `func subscribeConnection() -> AsyncStream<SPFNStateConnection>` | `fun subscribeConnection(): StateFlow<SpfnStateConnection>` | 현재 값 먼저 (§2-9) |
| `retry()` | `func retry()` | `fun retry()` | §2-9. `connected`이거나, 첫 스냅샷이 진행 중이거나, 구독이 없으면 아무것도 하지 않는다 |
| `close()` | `func close()` | `fun close()` | 모든 구독 열을 끝내고, 연결 상태 열도 끝내고, 원선 청취자를 떼고, 쿼리를 비운다 (`setQuery([:])`). **스트림은 닫지 않는다** — 앱의 것이다. 상태는 `reconnecting(0)`. 멱등. 뒤의 `subscribe`가 다시 시작한다 |
| (진단) | `droppedFrames: Int` | `droppedFrames: Int` | 디코드에 실패해 버린 프레임 수 (§2-4) |

#### 2-13-4. 앱이 쓰는 코드 — 웹과 나란히

Q-F와 Q-G를 뒤집은 근거가 이 표다 (운영자, 2026-10-17). 웹 줄은 spfn README의 "Browser"·"`useConnectionStatus(client)`"·"Connection status" 예에서 그대로 옮겼다. 같은 두 상태 — 키 없는 쓰기 상태 `consent`, 키 있는 `runtime` — 로 쓴다.

| 무엇 | 웹 (`@spfn/core/state/client` + `/react`) | Swift | Kotlin | 네이티브가 더 쓰는 것 |
|---|---|---|---|---|
| 상태 선언 | `import type { states } from '@/server/states';` | 없음 — `AppStates`는 생성물 | 없음 — `AppStates`는 생성물 | 없음 |
| 스트림 | `sse: createAuthSSEClient()` (클라이언트 설정 안) | `let events = try SPFNEventStream(keyLifecycle: keyLifecycle, configuration: .init(events: AppStates.events))` | `val events = SpfnEventStream(keyLifecycle, SpfnEventStreamConfiguration(events = AppStates.events), applicationScope);` | **한 줄** (스트림이 앱 전체의 것이라 따로 만든다, §2-2) |
| 클라이언트 | `export const state = createStateClient<typeof states>({ sse: …, api: { baseUrl: '/api/rpc' } });` | `let state = try SPFNStateClient(configuration: .init(stream: events, keyLifecycle: keyLifecycle, states: AppStates.all, snapshot: AppStates.snapshot))` | `val state = SpfnStateClient(SpfnStateClientConfiguration(events, keyLifecycle, AppStates.all, AppStates.snapshot), applicationScope);` | 없음 (같은 한 줄) |
| 루트 부착 | 없음 (모듈 싱글턴을 가져온다) | `.spfnEventStream(events, keyLifecycle: keyLifecycle, stateClient: state)` | `SpfnEventStreamHost(events, keyLifecycle, stateClient = state) { … }` | **한 줄** (이벤트 스트림의 부착에 인자 하나 — 수명 관찰자는 어차피 붙는다) |
| 키 없는 상태 | `const consent = useServerState(state, 'consent');` | `@SPFNServerState(AppStates.consent) var consent` | `val consent by rememberServerState(AppStates.consent)` | 없음 |
| 키 있는 상태 | `const runtime = useServerState(state, 'runtime', { workspaceId });` | `@SPFNServerState var runtime: SPFNStateEntry<AppAPI.GetRuntimeStateResponseValue, SPFNNoStateData>` 와 뷰의 `init`에서 `_runtime = SPFNServerState(AppStates.runtime, key: ["workspaceId": workspaceID])` | `val runtime by rememberServerState(AppStates.runtime, key = mapOf("workspaceId" to workspaceId))` | Swift만 한 줄 — 속성 래퍼의 인자는 뷰의 다른 속성을 읽지 못한다 (SwiftData `@Query`와 같은 제약) |
| 읽기 | `consent.status === 'loading'`, `consent.value === 'pending'` | `consent.status == .loading`, `consent.value == .pending` | `consent.status == SpfnStateStatus.Loading`, `consent.value == ...Pending` | 없음 |
| 쓰기 | `consent.write({ termsVersion: '2026-10', marketing })` | `try await state.write(AppStates.consent, input: .init(marketing: marketing, termsVersion: "2026-10"))` | `state.write(AppStates.consent, PutConsentStateBody(marketing = marketing, termsVersion = "2026-10"))` | 뷰가 클라이언트를 환경에서 꺼내는 한 줄 (`@Environment(\.spfnStateClient)`, `LocalSpfnStateClient.current`) — 어댑터가 `write`를 싣지 않는 까닭은 §2-12 |
| 연결 상태 | `const connection = useConnectionStatus(state);` | `@SPFNConnectionStatus var connection` | `val connection by rememberConnectionStatus()` | 없음 |
| 다시 시도 | `state.retry()` | `state.retry()` | `state.retry()` | 없음 |

뒤집기 전에는 여기에 상태마다 서술 다섯 줄(Q-G), 이름 목록에 상태마다 한 줄, 스냅샷 감싸기 한 줄, 그리고 앱이 만드는 `@Observable` 모델·`@Composable` 훅(Q-F)이 더 있었다.

## 3. 경우의 수 표

표기: 칸 = `(id, key)`. **s** 순서 도장, **E** 항목 (`ready(at, v)`, `revoked(at, v)`, `revoked(nil)`, `loading`), **g** 세대(스트림 epoch), **r(g)** g가 붙인 `revoked` 표시, **Q** 연결 쿼리, **W** 구독 중인 칸. `t1 < t2 < t3`은 `at` 문자열의 순서. "알림"은 그 칸의 구독 열이 새 항목을 낸다는 뜻이다.

모든 행의 근거는 원본 README의 문장이다 (P10: 기대값은 구현에서 뽑지 않는다). 따옴표 안이 그 문장이고, 절 이름을 앞에 적는다 (Ord = "Ordering", Str = "The stream", Con = "Connection status", Rev = "`'revoked'`", Man = "The manifest", Cfg = "`createStateClient`", Hook = "`useServerState`", At = "`at`"). 네이티브의 짝이 원본 문장과 다른 행은 "짝" 칸에 적었다.

### 3-1. O — 순서

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| O-1 | 항목 없음 | 프레임 `at t1, v A` | s = t1, `ready(t1, A)`, 알림 | Ord: "no current entry, or `at` newer than the stamp \| the ordering stamp advances; applied and notified only when `status`, `value` or `data` differ" |
| O-2 | `ready(t1, A)`, s = t1 | 프레임 `t2, B` | s = t2, `ready(t2, B)`, 알림 | 같은 문장 |
| O-3 | `ready(t1, A)`, s = t1 | 프레임 `t2, A` | s = t2, 항목 그대로 (`at`은 t1), 알림 없음 | Ord: "A listener hears it only when `status`, `value` or `data` changed. The `at` of `current()` is the `at` of the read that last changed the value" |
| O-4 | `ready(t2, B)`, s = t2 | 프레임 `t1, C` | 버림. 아무것도 바뀌지 않음 | Ord: "`at` older than the stamp \| dropped" |
| O-5 | `ready(t1, A)`, s = t1 | 프레임 `t1, A` | 아무것도 바뀌지 않음 | Ord: "`at` equal to the stamp \| one read stamps one `at`, so this is the same value: applied and notified only when `value` or `data` differ" |
| O-6 | `ready(t1, A)`, s = t1 | 도착 `t1, B` | `ready(t1, B)`, 알림 | 같은 문장 |
| O-7 | `ready(t1, A, data X)`, s = t1 | 프레임 `t2, A, data Y` | `ready(t2, A, Y)`, 알림 | O-1의 문장 ("`data` differ") |
| O-8 | `ready(t1, A)`, s = t3 (O-3 뒤) | 프레임 `t2, B` | 버림 | Ord: "a later read that repeats it advances only the stamp, so an older arrival after it is still dropped" |
| O-9 | 칸 X: s = t3, 칸 Y: s = t1 | 스냅샷 `at t2` (X·Y 모두 담음) | X 그대로, Y는 `ready(t2, …)` | Ord: "a snapshot older than one state's stamp \| that state keeps its entry; the others take the snapshot" |
| O-10 | `ready(t1, A)` | `write` 답 `t2, B`, 그 뒤 쓰기의 프레임 `t2, B` | 답에서 `ready(t2, B)`, 알림 한 번. 프레임은 아무것도 바꾸지 않음 | Ord: "a write answer \| applied like a frame — its own frame, arriving later with the same `at` and value, changes nothing" |
| O-11 | W에 `(runtime, {ws: A})`만 | 프레임 `runtime`, `key {ws: B}` | 버림. 칸을 만들지 않음 | Ord: "a frame for a key not subscribed here \| dropped (the server already filters by the connect query; this is the second guard)" |
| O-12 | `ready("2026-12-31T23:59:59.999Z", A)` | 프레임 `"2027-01-01T00:00:00.000Z", B` | 적용, 알림. 밀리초·초·날 경계 쌍도 같은 픽스처에 | At: "`at` is `new Date().toISOString()`"; Ord: "a larger `at` is a later value". 짝: UTF-8 바이트 비교 (§2-5) |
| O-13 | `ready(t1, {a: 1, b: 2})` | 프레임 `t2, {b: 2, a: 1}` | s = t2, 항목 그대로, 알림 없음 | O-1의 "(deep-equal)". 짝: 정규값 같음 (§2-5) |
| O-14 | `ready(t1, [1, 2])` | 프레임 `t2, [2, 1]` | `ready(t2, [2, 1])`, 알림 | 같은 문장. 배열은 순서가 값이다 |
| O-15 | `ready(t1, A)` | `write`의 2xx 답을 읽을 수 없음 | 호출 거부, 아무것도 적용하지 않음. 그 뒤 프레임 `t2, B`는 O-2 | Cfg 표의 `write`: "The answer is applied at once" — 답이 없으면 적용할 것이 없다; Cfg: "Calls the app makes reject." P42 |
| O-16 | 아무 상태 | `snapshot()`의 요청 실패 | 거부. 아무것도 바뀌지 않음 | Cfg 표 `snapshot`: "A rejected fetch rejects and changes nothing" |

### 3-2. K — 키

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| K-1 | 매니페스트 있음, Q = {}, 스트림 열림 | `subscribe(runtime, {ws: A})` | Q = {ws: A}, `setQuery` 한 번 (재연결) | Str: "the connect query is the union of the subscribed keys' fields … A new key value … closes the stream and opens it with the new query" |
| K-2 | W에 `(runtime, {ws: A})`, Q = {ws: A} | 둘째 `subscribe(runtime, {ws: A})` | Q 그대로, 재연결 없음, 새 열은 현재 항목을 먼저 | 같은 문장 (합집합이 그대로) |
| K-3 | W에 `(runtime, {ws: A})`, Q = {ws: A} | `subscribe(aiAccounts, {ws: B})` | Q = {ws: B}. 열린 뒤 `(runtime, {ws: A})`를 `get`으로 한 번 | Str: "the most recently subscribed key is carried. A key the stream does not carry is read once (`get`) when the stream opens" |
| K-4 | K-3 뒤, `(runtime, {ws: A})`는 그림자 | 프레임 `runtime`, `key {ws: A}` | 구독 중인 칸이므로 순서 규칙으로 적용 | Ord: "a frame for a key not subscribed here \| dropped" (구독 중이면 받는다); Str: "receives no frames until it is carried again" (서버가 보내지 않는다는 뜻). 짝: §2-7 |
| K-5 | W에 `{ws: A}`와 `{ws: B}`, Q = {ws: B} | `{ws: B}`의 마지막 구독이 끝남 | Q = {ws: A}, 재연결 | Str: "or the last subscriber of one leaving, closes the stream and opens it with the new query" |
| K-6 | 구독 없음 | 같은 차례에 `consent`, `(aiAccounts, {ws: A})`, `(runtime, {ws: A})` | `setQuery` 한 번 | Str: "Subscriptions made in the same task … are batched into one change" |
| K-7 | W에 `(runtime, {ws: A})` | 같은 차례에 해지와 같은 키의 새 구독 | Q 그대로, 재연결 없음 | 같은 문장 ("a StrictMode unmount-remount") |
| K-8 | 매니페스트 `runtime.key = [workspaceId]` | `subscribe(runtime, {projectId: P})` | 거부: 오류가 두 필드 집합을 이름으로 대고 `onError`로. 열은 아무것도 내지 않고 끝남. Q 그대로 | Man: "a `subscribe` … whose key fields differ from the state's, is refused: the error, naming the state and both sets of fields, goes to `onError`, and the subscription is dropped without reopening the stream" |
| K-9 | 매니페스트에 `billing` 없음 | `subscribe(billing)` | 거부, `onError`, 열 끝 | Man: "a `subscribe` whose id it does not name … is refused" |
| K-10 | 매니페스트 모름 | `subscribe(runtime, {projectId: P})` | 기다림 (`loading`). 첫 스냅샷이 오면 K-8 | Man: "Until then a `subscribe` waits (it never throws)… Once the manifest is known, a `subscribe` … is refused" |
| K-11 | Q = {ws: A} | 키 없는 `consent`의 스냅샷 항목 | 키 없는 칸 하나에 적용 | Cfg: "keyed states under the query's key" (키 없는 상태는 키가 없다) |
| K-12 | `(runtime, {ws: A})`를 전에 본 적 있음 (`ready`), 지금 W에 없음 | `subscribe(runtime, {ws: A})` | 열이 캐시된 `ready`를 먼저. 재연결의 프레임이나 스냅샷이 오면 순서 규칙 | Hook: "a key seen before shows its cached entry as 'ready' until the reopen's frame or snapshot lands (entries are not evicted)" |
| K-13 | Q = {} | 스냅샷이 키 있는 `runtime` 항목을 담음 (손으로 만든 것) | 건너뜀 | Cfg 타입 `snapshot`: "Read every state the query names a key for" |

### 3-3. R — `revoked`

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| R-1 | W에 `(runtime, {ws: A})`, Q = {ws: A}, `ready(t1, A)`, g = 3 | `subscription.revoked`, `state.runtime.changed` | `revoked(t1, A)`, r(3), 알림 | Rev: "`subscription.revoked` … the entry of `id` for **the key the connection carries** … gets `status: 'revoked'`, value kept, listeners notified" |
| R-2 | R-1에 더해 그림자 `(runtime, {ws: B})` `ready` | 같은 `subscription.revoked` | `{ws: B}`는 그대로 | Rev: "a shadowed key of the same state is untouched" |
| R-3 | W에 `consent`, `ready(t1, A)` | 열림, `deniedEvents`에 `state.consent.changed` | `revoked(t1, A)`, r(g) | Rev: "`connected.deniedEvents` names a subscribed state — keyless, or keyed on the key the connection carries \| 'revoked', value kept" |
| R-4 | W에 그림자 `(runtime, {ws: B})`, Q = {ws: A} | 열림, `deniedEvents`에 `state.runtime.changed` | `{ws: B}` 그대로. `{ws: A}`가 W에 있으면 그것만 `revoked` | Rev: "A shadowed key of the same state is untouched" |
| R-5 | W에 `consent`, `ready(t1, A)` | 스냅샷이 `consent`를 뺌 | `revoked(t1, A)`, 알림 | Rev: "a snapshot that omits a subscribed state — keyless, or keyed with its key in the query \| 'revoked', value kept if one exists" |
| R-6 | W에 `(runtime, {ws: B})`, 스냅샷 쿼리 {ws: A} | 스냅샷이 `runtime`을 뺌 | 표시하지 않음 | Rev: "A keyed state whose key the query did not carry is not marked" |
| R-7 | W에 `consent`, 항목 없음 | `subscription.revoked` | `revoked(nil)`, 알림 | Rev: "the mark is kept … apart from the value, so it holds even when no value has arrived yet"; Cfg: "`{ status: 'revoked' }` for a state revoked before any value arrived" |
| R-8 | `revoked(t1, A)`, r(3), s = t1 | 세대 3의 프레임 `t2, B` | `revoked(t2, B)`, 알림 | Rev: "a frame, a read or a snapshot started before the revoke \| supplies its value under the ordering rule; the status stays 'revoked'" |
| R-9 | `revoked(t2, B)`, r(3), s = t2 | 세대 3의 프레임 `t1, C` (더 오래됨) | 버림. 표시 그대로 | 같은 문장 + Ord "older than the stamp \| dropped". 브리프의 결함 예상 |
| R-10 | `revoked(t1, A)`, r(3) | `subscription.granted`, 그 뒤 (또는 그 앞) 프레임 `t2, B`, 그리고 `get`의 답 `t2, B` | 표시 지움, `get` 한 번, 끝은 `ready(t2, B)` — 어느 순서든 | Rev: "`subscription.granted` for it \| clears the mark for the carried key and reads it once (`get`); the server also sends a frame, before or after the control frame. Both arrive 'ready', and either order ends at the latest value" |
| R-11 | `revoked(t1, A)`, r(3) | 세대 4의 프레임 (첫 프레임 포함) `t2, B` | `ready(t2, B)`, 알림 | Rev: "a frame — the initial one included — … of a connection opened after the revoke, holding the state \| clears the mark" |
| R-12 | `revoked(t1, A)`, r(3) | 세대 4에 시작한 스냅샷이 그 상태를 담음 | `ready`, 알림 | 같은 행 ("or the snapshot of a connection opened after the revoke") |
| R-13 | 그림자 칸 `revoked(t1, A)`, r(3) | 세대 4가 열려 시작한 그림자 읽기의 답 `t2, B` | `ready(t2, B)` | Rev: "the read of a shadowed key when a connection opened after the revoke, answered \| clears the mark the same way" |
| R-14 | 그림자 칸 `revoked(t1, A)`, r(3). 세대 4가 열린 뒤 앱의 `write` 답 `t3, B`가 먼저 와서 `revoked(t3, B)`, s = t3 (앱이 부른 호출의 답은 표시를 지우지 않는다) | 세대 4의 그림자 읽기 답 `t2, C` | 값은 버림, 표시는 지움 → `ready(t3, B)`, 알림 | Rev: "an answer of such a read older than the stamp … its value is dropped under the ordering rule, while the mark is still lifted: the lift goes by the connection, not by `at`" |
| R-15 | W에 `consent`, 세대 3에 스냅샷 시작 | 그 사이 `subscription.granted`, 그 뒤 스냅샷이 `consent`를 빼고 도착 | 표시하지 않음 | Rev: "Every automatic read is tagged with the connection it was started for, which is how a snapshot in flight across a revoke is told from one read after it" (grant 쪽도 같은 꼬리표, `client.ts` `applyOmitted`) |
| R-16 | 세대 3에 스냅샷 시작, 그 사이 세대 4가 열림 | 세대 3의 스냅샷이 `consent`를 빼고 도착 | 표시하지 않음. 있는 값은 순서 규칙으로 | 같은 문장 |

### 3-4. C — 연결 상태와 열림

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| C-1 | 생성 직후 (`initial` 있든 없든) | — | `reconnecting(0)` | Con: "**Start value** `{ status: 'reconnecting', attempt: 0 }`, a client built with `initial` too" |
| C-2 | 구독 없음, 스트림 `open` | — | `reconnecting(0)` | Con 표: "no stream open — not yet, or nothing subscribed". 짝: 스트림은 열려 있을 수 있다 (§2-2) |
| C-3 | 구독 있음, 매니페스트 있음 | 스트림 `open(e)` | `connected` | Con 표: "a stream is open (the SSE `onOpen`)" |
| C-4 | `connected` | 스트림 `retrying(1, …)` | `reconnecting(1)` | Con 표: "the SSE client retrying a dropped one: `attempt` is its count (`onReconnect`)" |
| C-5 | `reconnecting(2)` | 스트림 `connecting(2)` | `reconnecting(2)` 그대로 | 같은 행. 짝: §2-9 표 |
| C-6 | `connected` | 스트림 `offline(1)` | `reconnecting(1)` | 짝: §2-9 (`offline`) |
| C-7 | `connected` | 스트림 `idle(background)` | `reconnecting(0)` | Con 표: "no stream open — not yet" |
| C-8 | `reconnecting(n)` | 스트림 `closed(unauthorized)` | `failed` | Con 표: "the retries are exhausted". 짝: §2-9 (`closed`) |
| C-9 | 매니페스트 모름, `maxRetries` 0 | 첫 스냅샷 실패 ×3 | `reconnecting(0)` 그대로, 실패마다 `onError`, `retryDelay`마다 다시 | Man: "While subscriptions wait on it, it is read again every `retryDelay` …, each failure to `onError`, until it succeeds … (default `0`: no limit)" |
| C-10 | 매니페스트 모름, `maxRetries` 2 | 첫 읽기와 재시도 둘이 모두 실패 | `failed`, 타이머 없음 | Man: "or `maxRetries` retries failed too … — then the status is 'failed' and no retry is set"; Cfg 타입: "The first read is not a retry" |
| C-11 | C-10 뒤 | `retry()` | 첫 스냅샷을 지금, 수 초기화, `reconnecting(0)` | Cfg 표 `retry()`: "reads the first snapshot now (its retry count reset)" |
| C-12 | 첫 스냅샷 진행 중 | `retry()` | 아무것도 하지 않음 (겹치지 않음) | Cfg 표 `retry()`: "A no-op … while the first snapshot is in flight"; Con: "one in flight is not doubled" |
| C-13 | 매니페스트 있음, `failed` (스트림 `closed`) | `retry()` | `stream.reconnectNow()`, `reconnecting(0)`, 열리면 `connected` | Cfg 표 `retry()`: "once the manifest is known, reopens the stream now"; Con: "The status is 'reconnecting' 0 until it opens". 짝: S-4 |
| C-14 | `connected` / 구독 없음 | `retry()` | 아무것도 하지 않음 | Cfg 표 `retry()`: "A no-op when 'connected' … and with nothing subscribed" |
| C-15 | `connected`, Q = {ws: A} | 키 변경으로 `setQuery({ws: B})` → 스트림 `connecting` → `open(e+1)` | 내내 `connected` | Con: "a reopen on a key change while connected stays 'connected'" |
| C-16 | `failed`, 매니페스트 있음, 쿼리 그대로일 구독 | `subscribe` | `reconnectNow()`, `reconnecting(0)` | Con: "a `subscribe` restarts when 'failed', reopening a failed stream even when the query is unchanged" |
| C-17 | `failed`, 구독 둘 | 하나 해지 | `failed` 그대로 | Con: "An unsubscribe that leaves subscribers never does: the status stays 'failed'" |
| C-18 | 구독 하나, 첫 스냅샷 재시도 1회 쌓임 | 마지막 해지 | `reconnecting(0)`, Q = {}, 재시도 수 0 | Con: "**The last unsubscribe** closes the stream: 'reconnecting' 0, the start value, and the first snapshot's retry count starts over for the next wait". 짝: 스트림을 닫는 대신 쿼리를 비운다 |
| C-19 | 구독 있음, 첫 스냅샷 진행 중, `get` 하나가 그것을 기다림 | `close()` | 스냅샷 답은 버림, `get`은 거부 ("closed"), 모든 열 끝, `reconnecting(0)`. 그 뒤 `subscribe`는 다시 시작 | Man: "`close()` while it is in flight discards its answer, and a `get` or `write` waiting on it rejects: the client was closed"; Cfg 표 `close()`: "Idempotent. A `subscribe` after it starts the client again" |
| C-20 | 매니페스트 모름, 재시도 타이머 걸림 | 스트림이 `offline`을 벗어남 (네트워크 돌아옴) | 첫 스냅샷을 지금, 수 초기화 | Con: "`retry()` and the browser's `online` event … restart when 'failed' or 'reconnecting'". 짝: §2-9 (`online`) |
| C-21 | 매니페스트 있음, `reconnecting(1)` | 스트림이 `offline`을 벗어남 | 상태 클라이언트는 아무것도 하지 않음 (스트림이 스스로 다시 연다, E-35) | 같은 문장. 짝: 스트림이 이미 한다 |
| C-22 | `reconnecting(0)` | `subscribeConnection()` | 열이 `reconnecting(0)`을 먼저, 그다음 바뀔 때마다 | Cfg 표 `subscribeConnection`: "called with the new status on every change, after `connection()` returns it". 짝: §2-9 (현재 값 먼저) |
| C-23 | `reconnecting(1)` | 스트림 `retrying(1, …)`이 다시 (지연만 다름) | 열이 아무것도 내지 않음 | Cfg 표 `connection()`: "the same reference until it changes" |
| C-24 | 설정에 `state.billing.changed`, 서버가 모름 | 원선 `unavailable([state.billing.changed])` | `onError` 한 번 (상태 이름). 그 상태는 열릴 때마다 "내주지 않은 상태"로 쳐서 스냅샷을 읽게 한다 | Str: "The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents`". 짝: 이벤트 스트림 E-16·L-16 |
| C-25 | 열림, g = 3 | 원선 `overflow` | 스냅샷(현재 쿼리)과 그림자 키를 다시 읽음, g 그대로 | Str: "A frame sent while no stream was open is lost … The server covers that gap" — 잃은 프레임을 덮는 것이 다시 읽기다. 짝: §2-6 |
| C-26 | 구독 있음 | `opened`, `initial` = 서술한 모든 상태, `deniedEvents` = [] | 스냅샷을 읽지 않음, 그림자 키만 읽음 | Str: "The client reads the snapshot only when a state it requested is neither in `initial` nor in `deniedEvents`" |
| C-27 | 구독 있음 | `opened`, 서술한 상태 하나가 `initial`에도 `deniedEvents`에도 없음 (또는 열린 스트림에 붙어 다시 받은 `opened`) | 스냅샷(그 연결의 쿼리) 한 번과 그림자 키 읽기 | 같은 문장 ("a state whose read threw on the server"). 짝: §2-6 (붙을 때 다시 받는 `opened`) |
| C-28 | 구독 있음 | `opened`, `initial` 없음 (옛 서버) | 스냅샷 한 번과 그림자 키 읽기 | Str: "— or when `initial` is absent (a server before it)" |

### 3-5. I — 초기값과 매니페스트

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| I-1 | — | `initial { snapshot(at t1, consent: A, aiAccounts: X), query {ws: W1} }`로 생성 | `current(consent) = ready(t1, A)`, `current(aiAccounts, {ws: W1}) = ready(t1, X)`, 매니페스트 설치 | Cfg: "The client installs its manifest and applies each state it holds at construction, keyed states under the query's key, so `current()` answers at once" |
| I-2 | I-1, `initial`이 `runtime`을 뺌 | `subscribe(runtime, {ws: W1})` | `loading`. `revoked`가 아님 | Cfg: "Nothing is marked 'revoked' from it: an omission in the render's snapshot is not a denial by the stream" |
| I-3 | — | `initial`의 쿼리 {} 와 키 있는 상태 항목 | 그 항목을 건너뜀 | Cfg: "keyed states under the query's key"; `client.ts` `applyPresent`의 주석 "a hand-built `initial` might" |
| I-4 | I-1 | 첫 `subscribe` | 스냅샷을 읽지 않고 쿼리를 정함. 연결 상태 `reconnecting(0)` 열릴 때까지 | Cfg: "the first `subscribe` opens the stream without reading the snapshot first"; Con: "a client built with `initial` too: its entries are ready, its stream is not" |
| I-5 | I-1, `ready(t1, A)` | 첫 `opened`의 첫 프레임 `consent t2, A` / `t2, B` | `t2, A`면 도장만, 알림 없음. `t2, B`면 알림 | Hook: "the stream's initial frames bring anything newer"; Ord O-3 |
| I-6 | — | 매니페스트에 없는 상태를 담은 `initial` | 그 상태를 건너뜀 | `client.ts` `applyPresent`: "A state the manifest does not describe … is skipped" (README Cfg: "Pass what `api.getStates` answered, not a hand-built snapshot") |
| I-7 | I-1 | 키 필드가 틀린 `subscribe` | 곧바로 `onError` | Man: "Such a client knows it from construction: nothing above waits, a refused `subscribe` goes to `onError` at once" |
| I-8 | `initial` 없음, 구독 없음 | 첫 `subscribe(consent)` | 첫 스냅샷을 읽음 (쿼리는 지금의 합집합). 그동안 `loading`. 답이 매니페스트를 설치하고 값을 적용한 뒤 쿼리를 정함 | Man: "The first `subscribe` — or `get`, `write`, `snapshot` — therefore reads the snapshot **first**: the client installs its manifest, then applies its states, then opens the stream" |
| I-9 | I-8, 첫 스냅샷 진행 중 | 둘째 `subscribe(aiAccounts, {ws: A})` | 둘째 스냅샷 없음. 같은 답이 둘 다를 돌본다 | Man: "a `subscribe` while that snapshot is in flight is served by it, not by a second read" |
| I-10 | 매니페스트 모름, 구독이 기다림 | 앱의 `get(consent)` | 그 `get`이 부른 스냅샷이 기다리던 구독도 돌보고 쿼리를 정함 | Man: "a `get`, `write` or `snapshot` that reads it first serves the waiting subscriptions too — whichever call brings the manifest opens the stream" |
| I-11 | 매니페스트 `consent.write = false`, 서술은 쓰기 서술 | `write(consent, …)` | 요청 전에 거부, 상태 이름을 댐 | Man: "A `write` on a state the manifest marks `write: false` rejects naming the state, before any request" |
| I-12 | 매니페스트 모름 | 첫 스냅샷이 실패한 채 앱의 `get` | `get` 거부. 기다리던 구독은 C-9대로 | Man: "A first snapshot that fails goes to `onError` (or rejects the call that needed it)" |

### 3-6. A — 관찰 어댑터 (이벤트 모듈, 개정 1)

Hook = "`useServerState(client, id, key?)`", Conn = "`useConnectionStatus(client)`". 짝의 근거는 이벤트 모듈의 규칙(event-stream-design §3-4)이다.

| id | 전 | 도착 | 후 | 근거 |
|---|---|---|---|---|
| A-1 | `(consent)`가 `ready(t1, A)` (캐시 또는 `initial`) | 뷰가 처음 그려짐 (`@SPFNServerState(AppStates.consent)` / `rememberServerState(AppStates.consent)`) | 첫 그리기가 `ready(t1, A)` — `loading`을 거치지 않음. 구독 열의 첫 항목(같은 값)은 다시 그리지 않음 | Hook: "`getSnapshot` is `client.current(id, key)`"; "it shows the value when the client was built with `initial`" |
| A-2 | 그 키의 항목 없음 | 첫 그리기, 그 뒤 첫 스냅샷의 항목 | `loading`, 그다음 `ready` | Hook: "A key never seen is `'loading'` until it has an entry" |
| A-3 | `{ws: A}`로 구독 중 | 다시 그리기, 값이 같은 새 키 사전/맵 | 해지·재구독 없음, 쿼리 그대로 | Hook: "The key is compared by its fields (stable-stringified), so `{ workspaceId }` written inline does not resubscribe on every render" |
| A-4 | `{ws: A}`로 구독 중, `{ws: B}`를 전에 본 적 있음 | 키가 `{ws: B}`로 | 앞 구독 끝, 새 구독, 첫 그리기가 `{ws: B}`의 캐시된 `ready` | Hook: "another `workspaceId` unsubscribes and subscribes"; "a key seen before shows its cached entry as `'ready'`" (K-12) |
| A-5 | 구독 중 | 뷰가 떠남 (Swift: 계층을 떠나 상태 저장소가 놓임. Kotlin: 컴포지션을 떠남) | 열의 소비가 취소되어 상태 클라이언트에서 해지 (그 키의 마지막이면 K-5) | `useSyncExternalStore`의 해지. 짝: "듣기의 끝은 청취자의 끝이다" (event-stream-design §3-4), §2-12 |
| A-6 | 구독 중 | 엔진(메인이 아닌 곳)에서 적용된 프레임 | 모델의 `entry`·Compose `State`가 메인 액터·메인 디스패처에서 바뀜 | 짝: SwiftUI·Compose의 상태는 메인에서 바뀐다 (§2-12) |
| A-7 | `reconnecting(0)` | `@SPFNConnectionStatus` / `rememberConnectionStatus()` 첫 그리기, 그 뒤 스트림 `open` | 첫 그리기가 `reconnecting(0)`, 그다음 `connected` 한 번. 같은 값은 다시 그리지 않음 | Conn: "`subscribe` is `client.subscribeConnection(cb)`, `getSnapshot` is `client.connection()`" |
| A-8 | 루트 부착에 상태 클라이언트 없음 | `@SPFNServerState` / `rememberServerState` | 프로그래머 오류: Swift `preconditionFailure`, Kotlin `IllegalStateException` | 짝: `onSPFNEvent`·`SpfnEventEffect`의 "루트 부착 없이 쓰면 프로그래머 오류다" |
| A-9 | 상태 클라이언트가 스트림 X로 설정됨 | 스트림 Y의 부착에 그 클라이언트를 넘김 | 부착하는 순간 프로그래머 오류 | 짝: 쿼리의 주인은 그 스트림의 상태 클라이언트 하나 (§2-2) |

셀 수: O 16, K 13, R 16, C 28, I 12 = **85**. 모두 툴킷 없는 단위 테스트다. 가짜 스트림(원선 신호를 손으로 밀어 넣는다)과 가짜 `execute`로 돈다. 실제 서버가 필요한 것은 §5-3의 U-셀이다.

## 4. 테스트 이름 표

§3의 셀 하나에 이름 하나. 이벤트 스트림 설계 §9와 같은 방식이다.
 

`SPFNStateClientTests.swift` / `SpfnStateClientTest.kt`. Swift는 `test_` 접두사를 붙인다 (기존 짝과 같다). 두 스위트 모두 `tests/fixtures/state-client/cases.json`을 읽는다: 셀마다 `id`, `before`, `arrival`, `after`, `readme` (근거 문장). 기대값은 README에서 손으로 쓰고 그 사실을 파일 머리에 적는다 (P10). 픽스처가 담을 수 없는 셀(시간, 차례, 취소)은 이름만 같고 본문은 플랫폼마다 쓴다 — 표의 "픽스처" 칸이 가른다.

| 셀 | 테스트 이름 | 픽스처 |
|---|---|---|
| O-1 | `o1_noEntry_frameApplies` | ○ |
| O-2 | `o2_newerFrame_replacesAndNotifies` | ○ |
| O-3 | `o3_newerSameValue_advancesStampOnly` | ○ |
| O-4 | `o4_olderFrame_isDropped` | ○ |
| O-5 | `o5_equalAtSameValue_changesNothing` | ○ |
| O-6 | `o6_equalAtOtherValue_applies` | ○ |
| O-7 | `o7_dataChange_notifies` | ○ |
| O-8 | `o8_olderThanAdvancedStamp_isDropped` | ○ |
| O-9 | `o9_olderSnapshot_keepsNewerState_appliesOthers` | ○ |
| O-10 | `o10_writeAnswer_thenOwnFrame_notifiesOnce` | ○ |
| O-11 | `o11_frameForUnsubscribedKey_isDropped` | ○ |
| O-12 | `o12_atComparedAsUtf8_acrossBoundaries` | ○ |
| O-13 | `o13_objectKeyOrder_isNotAChange` | ○ |
| O-14 | `o14_arrayOrder_isAChange` | ○ |
| O-15 | `o15_unreadableWriteAnswer_appliesNothing` | — |
| O-16 | `o16_failedSnapshot_changesNothing` | — |
| K-1 | `k1_firstKeyedSubscribe_setsQuery` | ○ |
| K-2 | `k2_sameKeyTwice_keepsQuery` | ○ |
| K-3 | `k3_conflictingKey_latestCarried_shadowReadOnce` | ○ |
| K-4 | `k4_frameForShadowedKey_appliesUnderOrdering` | ○ |
| K-5 | `k5_lastSubscriberOfKey_leaves_requeries` | ○ |
| K-6 | `k6_sameTurnSubscriptions_setQueryOnce` | — |
| K-7 | `k7_sameTurnUnsubscribeResubscribe_noReopen` | — |
| K-8 | `k8_wrongKeyFields_refusedNamingBothSets` | ○ |
| K-9 | `k9_unknownId_refused` | ○ |
| K-10 | `k10_wrongKeyBeforeManifest_waitsThenRefused` | ○ |
| K-11 | `k11_keylessEntry_ignoresQueryKeys` | ○ |
| K-12 | `k12_seenKey_resubscribe_showsCachedReady` | ○ |
| K-13 | `k13_keyedEntryWithoutQueryKey_isSkipped` | ○ |
| R-1 | `r1_subscriptionRevoked_marksCarriedKey` | ○ |
| R-2 | `r2_subscriptionRevoked_leavesShadowedKey` | ○ |
| R-3 | `r3_deniedAtConnect_marksRevoked` | ○ |
| R-4 | `r4_deniedAtConnect_leavesShadowedKey` | ○ |
| R-5 | `r5_snapshotOmission_marksRevoked` | ○ |
| R-6 | `r6_snapshotOmission_keyNotInQuery_notMarked` | ○ |
| R-7 | `r7_revokeBeforeValue_isRevokedAlone` | ○ |
| R-8 | `r8_sameConnectionFrame_suppliesValue_staysRevoked` | ○ |
| R-9 | `r9_olderFrameOnRevoked_isDropped_markKept` | ○ |
| R-10 | `r10_granted_clearsAndReadsOnce_eitherOrder` | ○ |
| R-11 | `r11_newerConnectionFrame_clearsMark` | ○ |
| R-12 | `r12_newerConnectionSnapshot_clearsMark` | ○ |
| R-13 | `r13_newerConnectionShadowRead_clearsMark` | ○ |
| R-14 | `r14_olderShadowAnswer_dropsValue_liftsMark` | ○ |
| R-15 | `r15_snapshotAcrossGrant_omissionNotMarked` | ○ |
| R-16 | `r16_snapshotOfOlderConnection_omissionNotMarked` | ○ |
| C-1 | `c1_startValue_isReconnectingZero` | ○ |
| C-2 | `c2_nothingSubscribed_isReconnectingZero` | ○ |
| C-3 | `c3_streamOpen_isConnected` | ○ |
| C-4 | `c4_streamRetrying_isReconnectingN` | ○ |
| C-5 | `c5_streamConnecting_keepsStatus` | ○ |
| C-6 | `c6_streamOffline_isReconnectingN` | ○ |
| C-7 | `c7_streamIdle_isReconnectingZero` | ○ |
| C-8 | `c8_streamClosed_isFailed` | ○ |
| C-9 | `c9_firstSnapshotFails_retriesEveryDelay` | — |
| C-10 | `c10_maxRetriesSpent_isFailed_noTimer` | — |
| C-11 | `c11_retryAfterSnapshotFailed_readsNow_countReset` | — |
| C-12 | `c12_retryWhileSnapshotInFlight_isNoOp` | — |
| C-13 | `c13_retryWithManifest_reconnectsNow` | ○ |
| C-14 | `c14_retryWhenConnectedOrUnsubscribed_isNoOp` | ○ |
| C-15 | `c15_keyReopenWhileConnected_staysConnected` | ○ |
| C-16 | `c16_subscribeWhileFailed_reconnectsSameQuery` | ○ |
| C-17 | `c17_unsubscribeLeavingOthers_staysFailed` | ○ |
| C-18 | `c18_lastUnsubscribe_resetsStatusQueryAndCount` | ○ |
| C-19 | `c19_closeDuringFirstSnapshot_discardsAndRejects` | — |
| C-20 | `c20_leavingOffline_rereadsFirstSnapshotNow` | — |
| C-21 | `c21_leavingOfflineWithManifest_doesNothing` | ○ |
| C-22 | `c22_subscribeConnection_yieldsCurrentFirst` | ○ |
| C-23 | `c23_sameStatus_isNotRepeated` | ○ |
| C-24 | `c24_unavailableStateEvent_reportedOnce_rereadEachOpen` | ○ |
| C-25 | `c25_overflow_rereadsSnapshotAndShadows` | ○ |
| C-26 | `c26_openedAllServed_noSnapshot` | ○ |
| C-27 | `c27_openedUnserved_readsSnapshotOnce` | ○ |
| C-28 | `c28_openedWithoutInitial_readsSnapshot` | ○ |
| I-1 | `i1_initial_answersAtOnce` | ○ |
| I-2 | `i2_initialOmission_isLoadingNotRevoked` | ○ |
| I-3 | `i3_initialKeyedWithoutQueryKey_isSkipped` | ○ |
| I-4 | `i4_initial_firstSubscribe_readsNoSnapshot` | ○ |
| I-5 | `i5_initial_thenFirstFrame_ordersByAt` | ○ |
| I-6 | `i6_initialStateOutsideManifest_isSkipped` | ○ |
| I-7 | `i7_initial_refusedSubscribe_reportedAtOnce` | ○ |
| I-8 | `i8_noInitial_firstSubscribe_readsSnapshotFirst` | ○ |
| I-9 | `i9_subscribeDuringFirstSnapshot_notReadTwice` | — |
| I-10 | `i10_getBringsManifest_servesWaitingSubscriptions` | — |
| I-11 | `i11_writeOnReadOnlyState_refusedBeforeRequest` | ○ |
| I-12 | `i12_failedFirstSnapshot_rejectsCallingGet` | — |

성질 테스트 둘을 더한다 (고정 시드, 무작위 입력 1 000개). 보행이 목표 상태에 **닿았는지** 단언한다 (P46):

| 테스트 | 내용 |
|---|---|
| `ordering_neverRegresses` | 무작위 도착(프레임·읽기·스냅샷·쓰기 답, 무작위 `at`) 뒤 매 순간 도장은 줄지 않고, 항목의 `at`은 도장보다 크지 않다. 같은 `at` 둘 이상, 오래된 도착이 섞였는지 단언 |
| `revokedLift_goesByConnection` | 무작위 revoke·grant·열림·도착 뒤, 표시가 지워진 순간마다 그것을 지운 도착의 세대가 표시의 세대보다 크거나 grant였다. `revoked`에 닿았고 지워진 적이 있는지 단언 |

관찰 어댑터의 셀 (§3-6)은 이벤트 모듈의 스위트 `SPFNStateAdapterTests.swift` / `SpfnStateAdapterTest.kt`에 있다. 85개의 이름 짝과 따로 센다. "Linux·JVM" 칸이 ○인 셀은 툴킷 없는 드라이버(`SPFNServerStateDriver`/`SpfnServerStateDriver`)와 부착 검사로 돈다. —인 셀은 래퍼·컴포지션이 있어야 하므로 기기에서 본다 (이벤트 모듈 §9-3과 같은 나눔).

| 셀 | 테스트 이름 | Linux·JVM |
|---|---|---|
| A-1 | `a1_cachedEntry_firstRenderIsReady` | ○ |
| A-2 | `a2_unseenKey_isLoadingUntilEntry` | ○ |
| A-3 | `a3_equalKeyNewInstance_doesNotResubscribe` | ○ |
| A-4 | `a4_keyChange_resubscribesCachedFirst` | ○ |
| A-5 | `a5_leaving_endsSubscription` | ○ (드라이버의 `end()`). 래퍼의 `deinit`·`LaunchedEffect` 취소는 기기 |
| A-6 | `a6_entryChanges_onMain` | ○ (Swift `@MainActor` 격리는 컴파일러가, Kotlin은 테스트 디스패처로) |
| A-7 | `a7_connectionStatus_currentFirst` | ○ |
| A-8 | `a8_withoutAttachment_isProgrammerError` | Kotlin만 (`LocalSpfnStateClient` 기본값이 던지는지, 이벤트 모듈 L-8과 같다). Swift는 기기 |
| A-9 | `a9_stateClientOfOtherStream_isProgrammerError` | ○ |

## 5. 전제 조건

### 5-1. 스트림 확장 (이 저장소, event-stream-design 개정 4)

| id | 무엇 | 상태 기계·허브에 닿는 곳 | 없으면 |
|---|---|---|---|
| S-1 | **연결 쿼리 입력** `setQuery(_ fields: [String: String])` / `setQuery(fields: Map<String, String>)`. 스트림 GET이 `token`·`events` 옆에 싣는다. 열린 연결(T·S·O)에서 값이 바뀌면 닫고 새 토큰으로 곧바로 다시 연다 (백오프 없음, 새 epoch). R·N·I·X에서는 기억만 하고 다음 연결에 쓴다. 같은 값은 무시. `events`·`token`이라는 필드는 거부 | 새 입력 하나와 E-셀 몇 개. 조건 W는 그대로 | 키 있는 상태는 연결 시점에 거부된다 (`deniedEvents`). Q2·Q3이 막힌다 |
| S-2 | **원선 청취** `listenWire(_ names: [String]) -> AsyncStream<SPFNWireSignal>` / `Flow<SpfnWireSignal>`. 여러 이름을 **큐 하나**로: `attached`, `opened(SPFNStreamConnected)` (`epoch`, 그 연결의 쿼리, `subscribedEvents`, `deniedEvents`, `initial?`), `frame(name, SPFNCanonicalValue)`, `subscription(name, granted \| revoked)` (S-3), `overflow`, `unavailable([String])`. 열린 동안 붙으면 `attached` 다음 현재 연결의 `opened`를 한 번 다시 준다 | 허브에 청취자 종류 하나. 이름 검사(L-3)와 넘침(L-4)은 같다 | `reread(.opened(e))`로 대신한다: 매 열림 스냅샷, 연결 시점 거부는 스냅샷 누락으로만 안다 (§2-6, §6 Q-C) |
| S-3 | **`subscription.granted`/`revoked` 전달.** 스트림 소유 프레임이다. `data.event`가 설정된 이름이면 그 이름의 원선 청취자에게 `subscription`으로. 보통의 `listen` 청취자에게는 가지 않는다. `unexpectedFrames`로 세지 않는다 | E-23의 갈래 하나 | 서버의 `grant`/`revoke`를 다음 스냅샷까지 모른다. R-1·R-2·R-10이 막힌다 |
| S-4 | **지금 다시** `reconnectNow()`. R → T(1) (타이머 끔, 수 초기화), X → T(1), O·T·S·I → 아무것도 하지 않음, N → 그대로 (네트워크가 없다 — 경로 감시기가 권위다) | 새 입력 하나 | `retry()`가 매니페스트를 안 뒤에는 할 일이 없다. C-13·C-16이 막힌다 |

넷 모두 서버를 바꾸지 않는다. 서버는 이미 쿼리를 읽고 (`SubscribeRequest.query`), `connected`에 `deniedEvents`·`initial`을 싣고, 제어 프레임을 보낸다.

### 5-2. 다른 곳의 전제

| id | 전제 | 이 설계가 기대하는 것 |
|---|---|---|
| P-a | **앱의 계약 문서가 상태 라우트를 담는다** (superself-apps `w-5hzhy`, 앱 쪽 변경): Superself가 상태 선언에 `.contract({ since })`를 붙이고, `defineStateRouter`에 `contract` 옵션을 주고, `contracts/current.json`을 다시 만든다 | 코드젠이 상태를 알아보는 데 필요한 것 (§2-13-2의 규칙): (1) **라우터의 `contract` 옵션** — 이것이 없으면 `getStates`가 문서에 없고, 닻이 없으니 상태를 하나도 알아보지 않는다. 응답은 파생 스키마 `{ at, states: { [id]?: { value, data? } }, manifest: { [id]: { key, write } } }`이고 `states`의 속성 이름이 id다. (2) **상태마다 `.contract()`** — 읽기 연산 `get<Id>State` (GET, 응답 `{ at, value, data? }`, 키 필드는 필수 문자열 쿼리), 쓰기가 있으면 `put<Id>State` (PUT, 같은 쿼리, 객체 본문, 같은 응답). 이름 규칙은 spfn의 것 그대로: `get`/`put` + id의 첫 글자를 대문자로 + `State`, id는 `^[a-z][a-zA-Z0-9]*$`. `.contract()` 없는 상태가 `getStates`의 스키마에 실리면 (README "Contract": 스냅샷 응답은 "in declaration order — built once when the router is built") 읽기 연산이 없으므로 **생성이 그 id를 대고 멈춘다** — 상태 라우터의 상태는 모두 계약하거나 하나도 하지 않는다. (3) `getStates`의 쿼리가 키 있는 상태들의 키 필드를 (선택) 문자열로 싣는다 — 스냅샷을 키로 읽으려면. (4) 값 스키마가 앱 계약 코드젠의 표 안에 있다 — `Type.Unknown` 값(R1), 소수(T3), 객체 유니언(R3)이면 생성이 상태 id를 대고 멈춘다. 그리고 생성기가 지금 거부하는 두 모양이 상태 라우트에서 나올 수 있다: 문서 최상위의 `events` 절 (생성기는 `compatibilityPolicy`·`documentVersion`·`operations` 밖의 키를 거부한다 — spfn은 계약한 이벤트가 있으면 이 절을 쓰고, 상태의 `.contract()`는 `state.<id>.changed`도 계약한다), 연산의 `deprecatedIn`·`removedIn` (R10이 모르는 연산 키로 거부한다). 둘 다 생성기가 읽고 넘기도록 여는 것이 이 전제의 몫이다 — `events` 절의 내용은 Q-B·#111이 다시 볼 때까지 읽지 않는다 |
| P-b | **이벤트 페이로드가 계약 문서에 실린다** — spfn #111, 열려 있음 | 기다리지 않는다. 그때까지 프레임은 읽기 호출의 디코더로 읽는다 (§2-4). #111이 오면 이벤트 이름 목록과 디코드를 생성물에서 받을 수 있는지 다시 본다 (§6 Q-B) |
| P-c | **`clientProofV1` 연산의 쿼리** — app-contract-codegen T15가 거부하고, §7이 "`SPFNOperation`에 '서명하는 경로'와 '보내는 URL'을 나누는 SDK 변경"을 남겼다 | `w-51r9a` (앱 계약 코드젠 경로)가 이것을 연다고 본다: 서명된 연산이 쿼리를 싣고, 서버(경로만 서명 입력으로 쓰는)와 서명이 맞는다. 열리지 않으면 키 있는 읽기와 쿼리 있는 스냅샷을 생성할 수 없다 — 키 없는 상태만 된다. 이 설계에서 가장 무거운 전제다 |
| P-d | **primitives 라우트의 `.contract()`** — `w-mm380` | 이 설계는 그것에 기대지 않는다. 상태 라우트는 앱 서버의 것이고 앱 계약 문서로 온다. `w-mm380`이 같은 코드젠 경로(앱 계약 문서)를 넓히는 일이라면 P-c와 같은 쿼리 규칙을 공유한다 — 그 단위가 쿼리를 다르게 풀면 이 설계가 따른다 |
| P-e | **서버 버전** — `connected.initial`과 연결 직후 프레임 (`ConnectedPayload.initial`) | 없으면 C-28 갈래 (매 열림 스냅샷). 막히지 않는다 |

### 5-3. 기기·서버에서 모을 증거 (운영자가 돌린다, 구현 뒤)

| id | 시험 | 볼 것 |
|---|---|---|
| U-1 | 로컬 Superself 서버, 로그인, 앱 루트가 `consent`와 `(aiAccounts, {workspaceId})`를 구독 | 토큰 발급 1, 연결 1, 스트림 GET에 `workspaceId`가 실림. `opened`의 `initial`이 두 상태를 담고 스냅샷 GET이 0 (C-26) |
| U-2 | 서버에서 `aiAccounts.changed.emit` | 1 s 안에 열이 새 항목을 냄 |
| U-3 | 작업공간 전환 | 재연결 1, 연결 상태 내내 `connected` (C-15), 옛 키는 그림자 아님 (구독이 끝났으므로 K-5) |
| U-4 | 서버에서 `revoke({ subject })` 뒤 `grant` | `revoked` → `ready`, 값이 마지막 값 (R-1, R-10) |
| U-5 | 비행기 모드 1분 | `reconnecting(n ≥ 1)` → 네트워크 돌아오면 `connected`, 그 사이 바뀐 값이 첫 프레임으로 (C-6, C-21) |

## 6. 열린 질문

운영자가 정할 것. 한 줄에 하나, 권고를 함께 적는다.

| id | 질문 | 권고 |
|---|---|---|
| Q-A | 상태 클라이언트를 앱의 스트림 하나에 얹고 쿼리를 그것이 갖는가 (§2-2 (가)), 전용 스트림을 두는가 | **앱의 스트림 하나.** 연결 하나, 루트 부착 하나. 키 변경이 다른 청취자의 다시 읽기 한 번을 부르는 것은 작업공간 전환에 묻힌다 |
| Q-B | 프레임을 읽기 호출의 디코더로 읽는가 (§2-4 (가)), 앱이 이벤트 디코더를 따로 주는가 | **읽기 디코더.** 서버 타입이 프레임 ⊇ 읽기 응답을 보장한다. #111이 오면 다시 본다 |
| Q-C | S-1–S-4를 한 번에 내는가, S-1·S-4 먼저 내고 S-2·S-3 없이 시작하는가 (매 열림 스냅샷, 거부는 스냅샷 누락으로만) | **한 번에.** 둘로 나누면 R-1·R-3·R-10과 C-26이 두 번 구현되고, 그 사이 `revoked`가 원본과 다르게 늦다 |
| Q-D | 키의 마지막 구독이 떠날 때 곧바로 재연결하는가 (원본 그대로), 유예를 두는가 | **곧바로.** 원본과 같아야 픽스처가 셋에 하나다. 구독은 루트가 오래 쥐므로 떠나는 일이 드물다 |
| Q-E | 스트림에 재연결 시도 상한(원본의 `maxReconnectAttempts`)을 더하는가 | **더하지 않는다, 지금은.** 원본도 기본은 끝없이다. 네이티브 `failed`는 거절로 닫힘과 `maxRetries`에서 온다. 앱 루트의 `failed` 케이스가 다른 것을 요구하면 그때 |
| Q-H | 공유 픽스처를 `tests/fixtures/state-client/cases.json` (새 최상위 디렉터리)에 두는가, `Contracts/fixtures/`에 두는가 | **`tests/fixtures/`.** `Contracts/fixtures/`는 고정 번들의 출처를 지키는 자리이고 (`MANIFEST.json`의 번들 digest), 이 표는 번들에서 오지 않는다 |
| Q-I | 같은 픽스처를 spfn의 TypeScript 클라이언트 테스트도 읽게 상류에 제안하는가 | **제안한다.** 그래야 "세 플랫폼"이 문서가 아니라 테스트로 같다. 원본 README의 문장을 근거로 단 셀이 그 형식이다 |
| Q-J | `current`가 항목이 없을 때 `.loading`을 돌려주는가 (원본은 `undefined`) | **`.loading`.** 원본의 훅이 그렇게 읽고, 원본에서 둘을 가르는 소비자가 없다 |

답한 것 (개정 1 — 운영자, 2026-10-17, 결정 `01m52pdtgpxkbdp0z3jc0kg5a9`. 두 권고를 뒤집었다):

| id | 질문 | 답 |
|---|---|---|
| Q-F | `@Observable` 모델·`@Composable` 훅을 어디에 두는가 (첫 권고: 이 단위에 두지 않고 앱 루트 패키지가 만든다) | **SDK가 제공한다, `events` 모듈에** — `@spfn/core/state/react`의 짝. Swift `@SPFNServerState`·`@SPFNConnectionStatus` (`@Observable` 모델), Kotlin `rememberServerState`·`rememberConnectionStatus`, 루트 부착이 상태 클라이언트를 받는다. `state` 모듈은 툴킷 없는 그대로, 그래프에 `events → state` 간선 (§2-1, §2-12, §3-6, §7) |
| Q-G | 앱 계약 코드젠이 상태 서술을 직접 생성하는가 (첫 권고: 지금은 아니다, #111과 함께) | **생성한다.** 선택된 `getStates`의 파생 스키마가 상태 id를 대고, id마다 `get<Id>State`·`put<Id>State`를 이름과 모양으로 확인해 `AppStates.<id>`·`all`·`events`·`snapshot`을 낸다. `keyFields`는 읽기 연산의 쿼리, 어긋남은 K-8. 앱은 서술을 쓰지 않는다 (§2-13-2, §2-13-4, §5-2 P-a). #111은 기다리지 않는다: 상태 이벤트의 이름은 spfn이 id에서 고정하고 (`state.<id>.changed`), 페이로드는 읽기 디코더로 읽는다 (Q-B) |

## 7. 모듈 그래프와 검사

이 단위에서는 바꾸지 않는다. 구현 PR이 더할 것이다.

**그래프 줄** (`tools/module-graph.json`, `client` 줄과 `events` 줄 사이 — 간선이 위로만 가리키게):

```json
{"id": "state", "swiftTarget": "SPFNState", "androidModule": "spfn-state", "swiftDependsOn": ["SPFNClient"], "androidDependsOn": ["spfn-client"], "externalDeps": {"swift": [], "android": ["kotlinx-coroutines-core"]}}
```

`state` 줄은 Q-F 결정으로 바뀌지 않았다. 바뀌는 것은 `events` 줄이다 — **간선 `events → state`** 하나 (관찰 어댑터, §2-12). 외부 의존은 그대로다:

```json
{"id": "events", "swiftTarget": "SPFNEvents", "androidModule": "spfn-events", "swiftDependsOn": ["SPFNClient", "SPFNState"], "androidDependsOn": ["spfn-client", "spfn-state"], "externalDeps": {"swift": [], "android": ["kotlinx-coroutines-core", "androidx-compose-runtime", "androidx-compose-ui", "androidx-lifecycle-process"]}}
```

- `events`의 노트에 한 문장: 상태 클라이언트의 관찰 어댑터(`@spfn/core/state/react`의 짝)가 여기 있고, 그래서 `state`에 의존한다 — `state`가 `events`에 의존하는 반대 방향은 Compose를 `state`로 끌어온다. "this module only carries platform facts into it"는 스트림에 대해 그대로 참이다: 어댑터도 상태 클라이언트의 열을 뷰의 상태로 옮길 뿐이다.
- 이벤트 모듈을 링크하는 앱은 `spfn-state`도 링크한다. 그 모듈은 툴킷이 없고 외부 의존이 `events`가 이미 가진 coroutines뿐이다. 상태 라우터를 쓰지 않는 앱이 얻는 것은 쓰지 않는 작은 산출물 하나다.
- `Package.swift`: `.target(name: "SPFNEvents", dependencies: ["SPFNClient", "SPFNState"])`, `.testTarget(name: "SPFNEventsTests", dependencies: ["SPFNEvents", "SPFNClient", "SPFNState"])`. Gradle `android/spfn-events/build.gradle.kts`: `spfnModuleDependsOn = listOf("spfn-client", "spfn-state")`, `api(project(":spfn-state"))` (공개 시그니처가 `SpfnStateDescriptor`·`SpfnStateClient`를 싣는다). CocoaPods 픽스처의 `SPFNEvents` subspec에 `sp.dependency` 하나. 모두 프로젝트 간선이라 `verification-metadata.xml`은 바뀌지 않을 것이다 — 구현 PR이 확인해 적는다 (P44).

- `linux` 키 없음: Linux에서 빌드되고 테스트된다. Apple 전용 import가 없다.
- 노트 하나: 상태 클라이언트가 왜 따로 있는가 (상태 라우터는 서버의 선택이고 이벤트 스트림만 쓰는 앱이 있다; `events`의 Compose 의존을 지나지 않는다), 무엇에 의존하는가 (`client`: 스트림, `execute`, 키 라이프사이클), 그리고 연결의 주인은 여전히 `SPFNEventStream`이고 이 모듈은 쿼리만 쓴다는 것.
- `state`의 `Package.swift`: `.library(name: "SPFNState", targets: ["SPFNState"])`, `.target(name: "SPFNState", dependencies: ["SPFNClient"])`, `.testTarget(name: "SPFNStateTests", dependencies: ["SPFNState", "SPFNClient"])`.
- Gradle: `include(":spfn-state")`, `android/spfn-state/build.gradle.kts`에 `spfnModuleDependsOn = listOf("spfn-client")`, `spfnSwiftCounterpart = "SPFNState"`, `api(project(":spfn-client"))`, `api(libs.kotlinx.coroutines.core)`. 간선 하나를 더하는 일이므로 검증 메타데이터의 변화를 PR 본문에 적는다 (P44). 매니페스트 권한 없음.

**validate.sh가 새로 읽는 곳:**

| 절 | 확인하는 것 | 구현 PR이 바꾸는 것 |
|---|---|---|
| 7 (외부 의존성 허용 목록) | `android/spfn-state/build.gradle.kts`의 외부 의존이 그래프의 한 별칭과 양방향으로 같다. `SPFNState` 타깃이 외부 제품을 선언하지 않는다 | 그래프 줄 |
| 8 (모듈 그래프 정합) | `SPFNEvents`의 타깃 의존 `["SPFNClient", "SPFNState"]`과 `spfn-events`의 `spfnModuleDependsOn`. `Sources/SPFNState`의 Swift 소스, `.library`, 타깃 의존 `["SPFNClient"]`, Gradle include와 `projectDir`, 빌드 스크립트와 Kotlin 소스, CocoaPods 픽스처의 `s.subspec 'SPFNState'`. 모듈 수 8 → 9. Linux 버킷: 키 없음 → Linux-capable. 가드 밖 Apple 전용 import 없음 | `tools/cocoapods-compat/generate-podspec.sh --write`로 픽스처 재생성 |
| 13 (두 플랫폼의 한 어휘) | 테스트 이름 짝: `SPFNStateClientTests` ↔ `SpfnStateClientTest`의 이름 85개가 같다 (이벤트 스트림 테스트처럼). 추출은 한 단계 들여쓴 멤버만 (P47), 읽은 이름 수의 바닥 85 (P7). 이벤트 모듈: `SPFNStateAdapterTests` ↔ `SpfnStateAdapterTest`의 A-셀 이름 9개 (바닥 9), 이벤트 모듈의 공개 이름 쌍에 `SPFNServerState`↔`rememberServerState`, `SPFNConnectionStatus`↔`rememberConnectionStatus`, `spfnStateClient`↔`LocalSpfnStateClient` | 대조 뿌리 두 쌍 |
| 파일 목록 | `tests/fixtures/state-client/cases.json`이 있고, 두 스위트가 읽는다 | 필수 파일 목록에 한 줄 |

`tools/rc-verify/rc-verify.sh`와 게시 목록은 그래프를 읽으므로 한 줄씩 는다. `RELEASE.md`·`CHANGELOG.md`에 새 좌표 `xyz.superfunction.spfn:spfn-state`와 새 Swift 제품. architecture README의 모듈 표에 한 줄, "The state client" 절 하나.

## 8. 걸린 함정

`docs/IMPLEMENTATION-PITFALLS.md`의 "트리거 → 항목"에서 이 구현이 건드리는 행과 그 항목. 브리프의 결함 예상과 함께 적는다.

| 항목 | 트리거 행 | 이 설계에서 |
|---|---|---|
| [P9](../IMPLEMENTATION-PITFALLS.md#p9) | Swift·Kotlin 대칭 로직; 시계·타임스탬프 | `at` 비교를 문자열의 UTF-8 바이트로 못 박는다 (§2-5). 두 언어의 문자열 비교가 non-ASCII에서 갈리는 일을 이름 붙은 함수 하나로 막는다. 키 필드 정렬(원본 `stableKey`)도 같은 바이트 순으로 |
| [P10](../IMPLEMENTATION-PITFALLS.md#p10) | 공유 conformance 표·fixture | `cases.json`은 README 문장에서 손으로 쓴다. 셀마다 근거 문장을 싣는다. 판별력: 틀린 규칙(`at`을 `<=`로, 해제를 `at`으로)을 넣어 실패 건수를 잰다 |
| [P15](../IMPLEMENTATION-PITFALLS.md#p15) | Swift·Kotlin 대칭 로직; 화면 모델·비동기 완료 | "쓰기 없는 상태의 `write`는 컴파일되지 않는다"는 두 언어에서 따로 확인한다. Kotlin은 Java 호출자에게 제네릭이 지워진다 — 런타임 거부(I-11)를 남기는 까닭 |
| [P16](../IMPLEMENTATION-PITFALLS.md#p16) | 플랫폼 콜백을 async로; 비동기 완료 | `close()`가 끊은 읽기, 끝난 구독의 취소는 `onError`가 아니다 (§2-13-1) |
| [P24](../IMPLEMENTATION-PITFALLS.md#p24) | 화면 모델·비동기 호출의 완료 처리 | `close()` 뒤 도착한 스냅샷은 버린다 (C-19). 원본의 `lifetime` 꼬리표와 같은 장치 |
| [P26](../IMPLEMENTATION-PITFALLS.md#p26) | 생성물 소비처 | 상태 클라이언트는 앱 계약 생성 호출을 `execute`로 보내고, 그 실패를 `onError`로 넘긴다. `catch`는 `SPFNClientError`만이 아니라 SDK가 던지는 모든 것을 받고, 취소 절을 먼저 둔다 |
| [P40](../IMPLEMENTATION-PITFALLS.md#p40) | 재시도 백오프 | 첫 스냅샷의 `retryDelay` 타이머는 수면을 포함하는 시계를 쓴다 |
| [P42](../IMPLEMENTATION-PITFALLS.md#p42) | 서버 상태를 바꾸는 호출의 오류 처리 | `write`의 2xx를 읽지 못하면 거부하되 아무것도 적용하지 않는다. 쓰기는 일어났을 수 있고, 프레임이 값을 가져온다 (O-15) |
| [P44](../IMPLEMENTATION-PITFALLS.md#p44) | 모듈 간선 추가, 새 Android 모듈의 의존성 | `spfn-state` → `spfn-client` 간선과 `api(coroutines)`. `verification-metadata.xml`의 변화를 PR에 적는다 |
| [P45](../IMPLEMENTATION-PITFALLS.md#p45) | Compose 효과를 Allman으로 | `state` 모듈에는 Compose가 없다. 이벤트 모듈의 `rememberServerState`·`rememberConnectionStatus`가 만난다 (§2-12): `LaunchedEffect` 블록과 람다를 Allman으로 |
| [P46](../IMPLEMENTATION-PITFALLS.md#p46) | 상태 기계의 성질 테스트 | §4의 성질 테스트 둘이 닿음을 단언한다 |
| [P47](../IMPLEMENTATION-PITFALLS.md#p47) · [P7](../IMPLEMENTATION-PITFALLS.md#p7) · [P5](../IMPLEMENTATION-PITFALLS.md#p5) | validate에 두 플랫폼 이름 대조 추가 | 13절의 새 뿌리: 한 단계 들여쓴 멤버만, 바닥 85 |
| [P20](../IMPLEMENTATION-PITFALLS.md#p20) · [P7](../IMPLEMENTATION-PITFALLS.md#p7) | `#if canImport` 가드 추가 | `state` 모듈은 가드할 파일이 없다 — Linux에서 전부 빌드된다. 이벤트 모듈의 어댑터 파일(래퍼·모델)은 `#if canImport(SwiftUI)`로 통째로 (`canImport(UIKit)`이 아니다, P20). 드라이버와 부착 검사는 가드 밖 |
| [P8](../IMPLEMENTATION-PITFALLS.md#p8) | 계약 타입 문법 변화 | 상태 값 스키마가 코드젠 표 밖이면 생성이 상태 id를 대고 멈춘다 (§2-13-2 거부 행, P-a). 상태를 알아보는 규칙이 추측하지 않는다: 닻(`getStates`의 파생 스키마)과 모양이 모두 맞아야 하고, 어긋나면 이름을 대고 멈춘다. 상태 클라이언트는 문서를 읽지 않는다 |

브리프의 결함 예상과 처리:

| 결함 예상 | 처리 |
|---|---|
| `at`을 문자열로 비교 vs 시각으로 파싱 | 문자열(UTF-8 바이트)로 비교한다. `toISOString()`의 고정 폭 UTC 모양에서 사전 순이 시각 순이고, 원본과 같은 답을 낸다 (§2-5, O-12). U6 계획(app-root-package §12)의 "문자열로 해서 순서 오판"은 모양이 고정이 아닐 때의 걱정이다 — 서버의 유일한 생산자가 고정 모양이다 |
| 재연결 때 스냅샷 재읽기 | 원선 청취의 `opened(connected)`에 묶는다 (§2-6). 스냅샷은 내주지 않은 상태가 있거나 `initial`이 없을 때만 읽는다. `state` 흐름(합쳐짐, 다른 통로)과 `attached`(연결과 무관)의 틈, 넘침(C-25)과 열린 스트림에 붙기(C-27)를 따로 막는다 |
| 스트림이 싣지 않은 키의 프레임 (그림자 키) | 구독 중이면 순서 규칙으로 적용, 아니면 버림 (K-4, O-11). 열릴 때마다 한 번 읽는다 (K-3) |
| `revoked` 칸에 더 오래된 프레임 | 값은 순서 규칙이 버리고 표시는 남는다 (R-9). 해제는 더 새 연결의 도착만 한다 (R-11–R-14) |
| Linux 빌드 | 모듈에 SwiftUI·Observation import가 없다. `linux` 키 없음, validate 8절의 "가드 밖 Apple 전용 import 없음"이 지킨다 (§2-1, §7) |
