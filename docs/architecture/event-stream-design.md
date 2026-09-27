# 설계 — 서버 이벤트 스트림 `EventStream`

- 상태: 제안 (2026-09-27). 검토자가 답할 질문은 §10에 있다. 구현 전이다.
- 대상: `SPFNClient`(iOS)와 `spfn-client`(Android)에 새로 들어갈 `SPFNEventStream`/`SpfnEventStream`, 그 상태 기계, SSE 줄 해석기, 스트림 전송 어댑터. 기존 `SPFNClient.execute`, `SPFNSession`, 전송 계층은 바뀌지 않는다
- 관련: [architecture README](README.md) "Three layers in the client module"·"The `ui` module"·"Three ways in", [custom-route-contract-design.md](custom-route-contract-design.md) §9 ("실시간은 계약이 담지 않는다"), [app-contract-codegen.md](app-contract-codegen.md), [tab-host-design.md](tab-host-design.md) (설계 문서의 모양), [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P40·P42, 서버 쪽 정본은 `@spfn/core` 패키지의 `src/event/README.md` ("SSE authentication (Token Exchange)", "Multi-instance broadcast", "Pitfalls & anti-patterns")

## 0. 요약

| 항목 | 결정 |
|---|---|
| 전송 | **SSE**, 새 의존성 없이. iOS는 `URLSession` 데이터 태스크의 델리게이트가 받는 바이트, Android는 이미 쓰는 OkHttp의 응답 본문 `BufferedSource`. WebSocket도 폴링도 아니다 (§2-1) |
| 토큰 호출 | **SDK의 이벤트 모듈이 설정된 경로로 `SPFNOperation`을 만들고 `SPFNClient.execute`로 보낸다.** 코어 계약 연산이 아니고 앱 계약 연산도 아니다. 서명, 한 번의 재핸드셰이크, `PROOF_EXPIRED` 재앵커가 그대로 따라온다 (§2-2) |
| 경로 | 스트림 경로를 설정한다 (기본 `/events/stream`). 토큰 경로는 서버와 같은 규칙으로 **파생한다** (`/events/stream` → `/events/token`). 따로 줄 수도 있다 |
| 이벤트 | 앱이 이름과 디코더로 구독한다. 선로 위의 이름은 서버 이벤트 라우터의 **키**다 (`defineEvent`의 첫 인자가 아니다, §8 H-2) |
| 상태 | `idle` → `connecting` → `open(epoch)` ⇄ `retrying(attempt, delay, reason)`, `suspended`, `closed(reason)`. 모든 전이는 툴킷 없는 순수 상태 기계에 있고 JVM과 Linux에서 단위 테스트로 돈다 (`Flow`, `TabState`처럼) |
| 다시 읽기 규칙 | **`open(epoch)`에 들어갈 때마다 화면은 다시 읽는다.** 첫 열림, 재연결, 앱 복귀, 전달 버퍼 넘침이 모두 새 epoch다. 프레임은 신호일 뿐 상태가 아니다 (최대 1회 전달) |
| 재연결 | 초기 1 s, 배수 2, 상한 30 s, 지터는 계산값의 50–100 % 균등. 30 s 이상 열려 있던 연결이 끊기면 처음부터 다시 센다 |
| 침묵 감시 | 마지막 바이트 뒤 `2.5 × pingInterval` (기본 25 s) 동안 아무것도 오지 않으면 끊고 재연결한다. 한 곳(상태 기계)에서만 잰다. 전송의 읽기 타임아웃에 기대지 않는다 |
| 수명 | SDK는 앱 수명주기를 관찰하지 않는다. 앱이 `suspend()`/`resume()`/`stop()`을 부른다. 앞쪽에서 보일 때만 연결한다 |
| 범위 밖 | APNs/FCM 푸시, 오프라인 큐, WebSocket, 백그라운드 연결 유지, 프레임 재전송(`Last-Event-ID`) |

## 1. 맥락과 요구

어떤 앱의 홈은 세션 목록이고, 각 행은 그 세션의 에이전트가 일하는 중이면 스피너를, 사람이 아직 보지 않은 응답이 있으면 점을 보인다. 둘 다 서버의 사실이다. 지금 앱은 화면이 나타날 때와 사람이 당겨서 새로 고칠 때만 읽으므로, 그 사이에는 스피너가 멈춘 에이전트 위에서 돌고, 점은 새 응답이 와도 켜지지 않는다.

서버는 이미 같은 사실을 브라우저에 알린다. SPFN 서버의 `.events(router, { auth: { enabled: true, filter } })`가 SSE 스트림을 열고, 인증된 `POST /events/token`이 한 번 쓰는 30초짜리 토큰을 발급하고, `GET /events/stream?token=…&events=…`가 그 토큰으로 스트림을 연다. 이 설계는 네이티브 앱이 **같은 서버 표면을, 서버를 바꾸지 않고** 쓰게 한다.

| # | 요구 | 이 설계에서 |
|---|---|---|
| Q1 | 앞쪽에 있는 네이티브 앱이 이름 붙은 SPFN 이벤트 프레임을 타입으로 받는다 | `subscribe(name, decode)` (§3-2) |
| Q2 | 토큰 교환은 서명된 execute 경로를 지난다. 쿠키는 없다 | 토큰 호출은 `SPFNClient.execute` 하나로 (§2-2). SDK 전송은 쿠키를 끈다 (`hardenedConfiguration`, `CookieJar.NO_COOKIES`) |
| Q3 | 경로는 설정할 수 있다. 앱은 이벤트를 다른 곳에 마운트할 수 있다 | `streamPath`, 파생 또는 명시 `tokenPath` (§3-1) |
| Q4 | 신호는 상태가 아니다. 연결이 (다시) 열리면 화면은 다시 읽는다 | `open(epoch)` (§3-3, §4) |
| Q5 | 끊김, 서버의 백프레셔 닫힘, 프록시의 유휴 절단을 스스로 회복한다 | 재연결 표와 침묵 감시 (§4) |
| Q6 | 두 플랫폼이 같은 어휘, 같은 전이 표를 가진다 | §3의 이름 표, §9의 공유 테스트 이름 |
| Q7 | 토큰과 토큰을 실은 URL은 어디에도 기록되지 않는다 | §6 |

서버 쪽 사실 (`@spfn/core` 0.3.0-beta.13, `dist/event/sse/index.js`와 `dist/server/index.js`에서 읽음):

| 사실 | 값 |
|---|---|
| 토큰 경로 | 스트림 경로의 마지막 조각을 `token`으로 바꾼 것. 앱의 이름 붙은 미들웨어(전역 `authenticate` 포함)가 붙는다 |
| 토큰 응답 | `200 {"token":"<64 hex>"}`. 인증은 통과했는데 주체가 없으면 `401 {"error":"Unable to identify subject"}` — SPFN 오류 봉투가 **아니다** |
| 토큰 수명 | 기본 30 000 ms, 한 번 쓰면 사라진다 (`GETDEL` 또는 Map 삭제). 저장소에는 해시만 있다 |
| 스트림 거부 | `401` 토큰 없음·무효·만료, `400` `events` 없음 또는 모르는 이름 (`validEvents` 목록을 싣는다), `403` `authorize`가 빈 목록 |
| 스트림 프레임 | 첫 프레임 `event: connected`, `data: {"subscribedEvents":[…],"timestamp":n}`. 이벤트는 `id: n` (연결마다 1부터), `event: <라우터 키>`, `data: {"event":"<라우터 키>","data":<페이로드>}`. 핑은 `event: ping`, `data: {"timestamp":n}`, 기본 10 000 ms마다 |
| `retry:` 필드 | 보내지 않는다. `Last-Event-ID`도 읽지 않는다. 놓친 프레임은 다시 오지 않는다 |
| 백프레셔 | 연결마다 큐 1000개를 넘으면 **서버가 연결을 닫는다** (프레임을 버리지 않는다). 클라이언트에는 오류 없는 스트림 끝으로 보인다 |
| 다중 인스턴스 | 캐시가 있으면 pub/sub으로 파드 사이에 퍼진다. 토큰도 캐시에 있으므로 토큰을 발급한 파드와 스트림을 받는 파드가 달라도 된다 |
| 응답 헤더 | `X-Accel-Buffering: no` (nginx 계열 버퍼링 끔) |

## 2. 검토한 선택지

### 2-1. 전송: SSE, WebSocket, 폴링

| | (가) SSE, 새 의존성 없음 (**선택**) | (나) WebSocket | (다) 폴링 |
|---|---|---|---|
| 서버 | 이미 있다. 브라우저가 같은 표면을 쓴다 | `.websockets(router)`는 따로 등록해야 한다. 이 서버 표면을 쓰는 앱은 지금 등록하지 않았다 | 목록 읽기를 주기적으로 부른다. 서버 변경 없음 |
| iOS | `URLSession` 데이터 태스크 + `URLSessionDataDelegate.urlSession(_:dataTask:didReceive:)`. 새 패키지 없음 | `URLSessionWebSocketTask`. 새 패키지는 없지만 프레임 모양이 SSE와 다르다 (`@spfn/core` WS 프로토콜) | 기존 `execute` |
| Android | 이미 의존하는 OkHttp의 `response.body.source()`를 줄 단위로 읽는다. `okhttp-sse` 산출물은 **쓰지 않는다** — 새 좌표이고 `externalDeps` 허용 목록에 한 줄이 는다. 줄 해석은 30줄이면 된다 | OkHttp `WebSocket`. 의존성은 있다 | 기존 `execute` |
| 방향 | 서버 → 앱뿐. 이 요구에는 그것만 필요하다 | 양방향. 앱이 보낼 것이 없다 | — |
| 지연 | 커밋 직후 | 커밋 직후 | 주기의 절반 평균. 스피너가 5초 늦게 멈추는 것은 이 앱이 풀려는 문제 그대로다 |
| 비용 | 연결 하나, 10초마다 핑 몇십 바이트 | 연결 하나 | 주기마다 목록 전체. 사람이 보고 있는 동안 계속 |
| 인증 | 토큰 교환, 한 번 쓰는 토큰 | 같은 토큰 교환 (`/ws/token`) | 기존 서명 요청 |

(가)를 고른 이유: 서버에 이미 있고, 브라우저가 쓰는 같은 규칙(신호, 최대 1회, 주인에게만)을 그대로 물려받으며, 두 플랫폼 모두 새 의존성이 없다. (나)는 앱이 서버로 보낼 것이 생길 때 따로 설계한다 (§7). (다)는 스트림을 쓸 수 없는 서버를 위한 대체로 남기지 않는다: 스트림이 `closed`면 앱은 지금처럼 나타날 때와 당길 때 읽는다. 그것이 대체다.

**iOS에서 `URLSession.bytes(for:)`를 쓰지 않는 이유.** 이 모듈은 Linux에서도 빌드된다 (architecture README 모듈 표). swift-corelibs-foundation의 `FoundationNetworking`에 `bytes(for:)`(`AsyncBytes`)가 있는지는 이 설계에서 확인하지 못했다. 델리게이트의 `didReceive data`는 두 곳 모두에 있다. 그래서 어댑터는 델리게이트 모양으로 쓰고, Linux 빌드가 되는지는 구현 PR의 `swift build`가 확인한다. 안 되면 어댑터 파일 하나만 `#if canImport(Darwin)`으로 통째로 감싸고, 상태 기계와 해석기는 Linux에 남는다.

### 2-2. 한 번 쓰는 토큰 호출은 어디에 있는가

| | (가) 코어 계약 연산 | (나) 앱 계약 연산 | (다) SDK 이벤트 모듈이 설정된 경로로 연산을 만든다 (**선택**) |
|---|---|---|---|
| 출처 | 고정된 코어 번들은 SPFN primitives의 **auth 패키지**가 내보낸다 (`Contracts/README.md`). 토큰 경로는 `@spfn/core`의 **event** 모듈이 등록한다. 상류 exporter를 바꾸고 번들을 다시 고정해야 한다 | 앱 계약 문서는 `.contract()`가 붙은 라우트만 담는다. 토큰 경로는 프레임워크가 `app.on(['POST'], [tokenPath], …)`로 직접 등록하므로 표시가 없다. 앱이 손으로 적어야 한다 | 이벤트 모듈 안의 `SPFNOperation(id: "events.token", method: "POST", path: <설정>, authProfile: "clientProofV1", requiresSession: true, declaresResponse: true)`와 응답 코덱 `{token: String}` |
| 경로 | 계약의 경로는 고정이다. 서버는 스트림 경로에서 **파생**하므로 앱이 이벤트를 `/sse`에 마운트하면 `/token`이 된다. 계약이 틀린다 | 앱이 정할 수 있다 | 설정에서 온다. 서버와 같은 파생 규칙 |
| 실행 경로 | `execute` | `execute` | `execute`. `SPFNCall`과 `SPFNOperation`은 공개 이니셜라이저가 있고, `execute`는 인증 등급을 연산 id가 아니라 `authProfile` 문자열로 푼다 (`SPFNGeneratedOperations.authClass(of:)`). 새 실행 경로가 없다 |
| 계약 핀과의 관계 | 핀이 바뀐다 | 앱 핀만 | 핀과 무관하다. 토큰 경로는 계약이 아니라 **프레임워크 설정**이다 (custom-route-contract-design §9) |

(다)를 고른 이유: 토큰 경로는 계약의 사실이 아니라 서버 설정의 사실이다. 그 설정을 앱이 SDK에 그대로 알려 주는 것이 두 곳을 맞추는 가장 짧은 길이다. 그러면서도 "요청을 보내는 길은 `execute` 하나"라는 규칙(architecture README "Three layers")은 깨지지 않는다: 토큰 요청은 새 세션 헤더, 새 nonce, 새 증명을 달고 나가고, 인증 거부에는 재핸드셰이크 한 번이 따라온다.

`authenticate`가 서명 요청을 받아 `c.get('auth').userId`를 채우는 것은 서버 쪽 사실이고 이 저장소가 확인할 수 없다. 앱 서버가 서명된 `POST /events/token`에 200을 주는지를 §5 U-1에서 잰다. 쿠키가 없는 요청이므로 CSRF 규칙(`x-spfn-csrf`)은 브라우저 경로의 것이고 여기에 해당하지 않아야 한다. 해당한다면 서버 설정의 결함이다 (§10 Q-B).

**스트림 GET은 서명하지 않는다.** 서버는 스트림 경로에 미들웨어를 붙이지 않고 토큰만 본다. 서명을 붙여도 읽는 쪽이 없고, 증명 하나를 소모할 뿐이다. 스트림 요청은 `SPFNClientIdentity.headers`만 단다 (다른 모든 요청과 같다).

### 2-3. 쿠키와 브라우저 클라이언트의 차이

`@spfn/core`의 브라우저 클라이언트(`createAuthSSEClient`)는 쿠키 인증 RPC 프록시로 토큰을 받고 `spfn_csrf` 쿠키를 헤더로 옮긴다. SDK에는 쿠키가 없다: iOS 전송은 `httpCookieStorage = nil`, `httpShouldSetCookies = false`, Android는 `CookieJar.NO_COOKIES`. 스트림 어댑터도 같은 설정을 쓴다. 그래서 RPC 프록시(`/api/rpc/eventsToken`)는 쓰지 않고, **API 서버의 토큰 경로를 직접** 부른다. 앱의 `baseURL`은 API 서버여야 한다 (브라우저 앞의 Next 서버가 아니다).

브라우저 클라이언트에서 가져오는 규칙은 둘이다: 재연결마다 토큰을 새로 받는다 (한 번 쓰는 토큰이므로), `connected`와 `ping`은 앱에 넘기지 않는다.

## 3. 공개 API

어휘는 두 플랫폼에서 같다. Swift는 `SPFN` 접두사, Kotlin은 `Spfn` 접두사. validate가 두 플랫폼의 이름을 비교하는 절에 이 이름들이 는다.

### 3-1. 설정과 생성

| Swift | Kotlin |
|---|---|
| `SPFNEventStreamConfiguration(streamPath: String = "/events/stream", tokenPath: String? = nil, pingIntervalMillis: Int64 = 10_000, backoff: SPFNEventStreamBackoff = .standard, deliveryBuffer: Int = 256)` | `SpfnEventStreamConfiguration(streamPath: String = "/events/stream", tokenPath: String? = null, pingIntervalMillis: Long = 10_000, backoff: SpfnEventStreamBackoff = SpfnEventStreamBackoff.Standard, deliveryBuffer: Int = 256)` |
| `SPFNEventStream(client: SPFNClient, session: SPFNSession, configuration: .init(), transport: any SPFNStreamTransport = SPFNURLSessionStreamTransport())` | `SpfnEventStream(client: SpfnClient, session: SpfnSession, configuration = SpfnEventStreamConfiguration(), transport: SpfnStreamTransport = SpfnOkHttpStreamTransport(), scope: CoroutineScope)` |

- `tokenPath`가 `nil`이면 서버의 규칙으로 파생한다: `streamPath`의 마지막 `/` 뒤를 `token`으로 바꾼다. `/events/stream` → `/events/token`, `/sse` → `/token`. 경로는 `/`로 시작해야 하고, 쿼리를 담으면 거부한다 (Swift `throws SPFNEventStreamError.invalidConfiguration`, Kotlin `IllegalArgumentException`).
- `pingIntervalMillis`는 **서버의** 핑 간격이다. SDK가 핑을 보내지 않는다. 침묵 감시는 이 값의 2.5배다. 서버가 `pingInterval`을 바꾸면 앱도 바꾼다.
- 스트림 URL은 `session.baseURL + streamPath`다. `session`이 이미 https 또는 루프백 http만 받으므로 (D21) 스트림도 같은 규칙 아래에 있다. 새 검사가 없다.
- `transport`는 스트림 전송이다. `SPFNTransport`(요청 하나, 응답 하나)와 다른 경계다: 응답 헤더와 상태를 한 번, 그다음 바이트 조각을 여러 번 준다. 재시도하지 않고, 분류하지 않는다 (§3-6).

### 3-2. 구독

| Swift | Kotlin |
|---|---|
| `func subscribe<Event: Sendable>(_ name: String, decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event) -> SPFNEventSubscription<Event>` | `fun <E> subscribe(name: String, decode: (SpfnCanonicalValue) -> E): SpfnEventSubscription<E>` |
| `SPFNEventSubscription.frames: AsyncStream<Event>`, `cancel()` | `SpfnEventSubscription.frames: Flow<E>`, `cancel()` |

- `name`은 서버 이벤트 라우터의 **키**다 (`defineEventRouter({ sessionActivity, … })`의 `sessionActivity`). `defineEvent('session.activity', …)`의 첫 인자는 선로에 나오지 않는다 (§8 H-2).
- `decode`는 봉투 `{"event":…,"data":…}`의 `data`를 받는다. 봉투를 벗기는 것은 SDK다. 디코더가 던지면 그 프레임만 버리고 `droppedFrames` 진단 수를 1 늘린다. 연결은 그대로다. 오류 값에는 페이로드를 싣지 않는다 (§6).
- 같은 이름을 두 번 구독하면 두 구독 모두 같은 프레임을 받는다.
- 구독 이름의 합집합이 `events` 쿼리가 된다. **열린 뒤에 합집합이 바뀌면** 연결을 한 번 닫고 새 합집합으로 다시 연다 (같은 실행 루프 차례에 일어난 변경은 하나로 모은다). 새 연결은 새 `open(epoch)`이다. 대개는 `start()` 전에 모두 구독한다.
- `frames`는 순서대로, 빠짐없이 전달한다. 버퍼는 `deliveryBuffer`개이고, 넘치면 가장 오래된 것을 버리고 **새 epoch를 낸다** (§3-3): "놓쳤을 수 있다, 다시 읽어라"는 재연결과 같은 뜻이기 때문이다.
- 모으기(coalescing)는 앱의 일이다. SDK는 프레임을 합치지 않는다. 어떤 화면은 프레임마다 한 행을 고치고, 어떤 화면은 300 ms 동안 모아 목록을 한 번 읽는다. 그 차이는 화면이 안다.

### 3-3. 상태

| Swift | Kotlin |
|---|---|
| `enum SPFNEventStreamState: Equatable, Sendable` | `sealed interface SpfnEventStreamState` |
| `var state: SPFNEventStreamState { get }`, `var states: AsyncStream<SPFNEventStreamState>` (구독하면 현재 값을 먼저 준다) | `val state: StateFlow<SpfnEventStreamState>` |

| 상태 | 뜻 |
|---|---|
| `idle` | `start()` 전 |
| `connecting(attempt)` | 토큰을 받거나 스트림을 여는 중. `attempt`는 마지막 안정 연결 뒤 몇 번째 시도인가 (1부터) |
| `open(epoch)` | `connected` 프레임을 받았다. `epoch`는 이 객체가 열린 횟수 + 버퍼 넘침 횟수, 1부터. **값이 바뀔 때마다 화면은 다시 읽는다** |
| `retrying(attempt, delayMillis, reason)` | 다음 시도를 기다린다. `reason`: `network`, `silence`, `serverClosed`, `serverError(status)`, `tokenRejected`, `unreadable` |
| `suspended` | 앱이 뒤로 갔다. 연결이 없고 타이머도 없다. 구독은 남아 있다 |
| `closed(reason)` | 끝. 스스로 다시 시도하지 않는다. `reason`: `stopped`, `unauthorized`, `forbidden`, `unknownEvents([String])`, `noSubscriptions` |

`open`에 epoch를 싣는 이유: "다시 읽어라"는 신호를 **상태에서** 읽게 하기 위해서다. 콜백을 따로 두면 상태와 콜백의 순서를 정해야 하고, 늦게 구독한 화면은 콜백을 놓친다. `StateFlow`와 `states`는 현재 값을 먼저 주므로, 늦게 온 화면도 `open(3)`을 보고 한 번 읽는다. 같은 값은 두 번 오지 않으므로 (Equatable, `StateFlow`의 중복 제거) epoch가 없으면 `open` → `retrying` → `open`이 한 화면에 `open` 두 번으로만 보이고, 그 사이를 놓친 화면은 재연결을 모른다.

### 3-4. 조작

| 멤버 | 내용 |
|---|---|
| `start()` | `idle`, `closed`에서 `connecting(1)`로. 다른 상태에서는 아무 일도 없다. 구독이 없으면 `closed(noSubscriptions)` |
| `stop()` | 어느 상태에서든 `closed(stopped)`. 스트림을 닫고, 타이머를 끄고, 진행 중인 토큰 호출을 취소한다. 구독의 `frames`는 끝나지 않는다 (다시 `start()`할 수 있다) |
| `suspend()` | 앱이 뒤로 갔다. `closed`, `idle`이 아니면 `suspended`. 스트림을 닫고 타이머를 끈다 |
| `resume()` | 앱이 앞으로 왔다. `suspended`에서만 `connecting(1)`. 백오프 없이 곧바로 |
| `networkChanged(available: Bool)` | 선택 입력. 앱이 경로 감시기(iOS `NWPathMonitor`, Android `ConnectivityManager.NetworkCallback`)를 두었다면 넘긴다. `false`면 `retrying`의 타이머를 멈추고 기다린다. `true`면 `retrying`에서 곧바로 `connecting`. 넘기지 않아도 백오프로 회복한다 |

`start()`/`stop()`/`suspend()`/`resume()`은 모두 동기 호출이고 멱등이다. Swift에서는 `@MainActor`가 아니라 내부 actor가 입력을 순서대로 받는다 (`SPFNSession`처럼). 화면 모델은 메인 액터에서 부르고 기다리지 않는다.

### 3-5. 앱이 화면 수명에 묶는 법

SDK는 `scenePhase`도 `ProcessLifecycleOwner`도 보지 않는다. 클라이언트 모듈은 툴킷이 없고 (`SPFNUI`는 코어에만 의존한다), 어느 화면이 언제 보이는지는 앱만 안다.

```swift
// iOS — 앱 합성 지점에서 하나 만든다. 로그인한 동안 산다.
let events = SPFNEventStream(client: client, session: session)
let activity = events.subscribe("sessionActivity", decode: SessionActivity.init(canonical:))

// 홈이 보이는 동안
.task
{
    events.start()
    await withTaskGroup(of: Void.self)
    {
        group in
        group.addTask { for await _ in activity.frames { await model.poke() } }
        group.addTask { for await state in events.states { await model.streamState(state) } }
    }
}
.onDisappear { events.suspend() }          // 다른 탭·상세로 가면 연결을 쉰다
.onChange(of: scenePhase)
{
    _, phase in
    phase == .active ? events.resume() : events.suspend()
}
// 로그아웃: events.stop() — 반드시 (§4 E-40)
```

```kotlin
// Android — 같은 모양. repeatOnLifecycle(STARTED)가 앞쪽/뒤쪽을 준다.
LaunchedEffect(Unit)
{
    events.start();
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)
    {
        events.resume();
        try
        {
            launch { activity.frames.collect { model.poke() } };
            events.state.collect { model.streamState(it) };
        }
        finally
        {
            events.suspend();
        }
    }
}
```

화면이 보이는 동안만 연결한다는 것은 앱의 정책이다. SDK는 한 객체를 여러 화면이 공유해도 되게 만든다: 구독은 화면마다, 연결은 하나.

### 3-6. 스트림 전송 경계

| Swift | Kotlin |
|---|---|
| `protocol SPFNStreamTransport: Sendable { func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse }` | `interface SpfnStreamTransport { suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse }` |
| `SPFNStreamResponse { statusCode, headers, chunks: AsyncThrowingStream<[UInt8], Error>, cancel() }` | `SpfnStreamResponse { statusCode, headers, chunks: Flow<ByteArray>, cancel() }` |

- 오류는 `SPFNTransportError`의 네 경우를 그대로 쓴다. 새 오류 어휘가 없다.
- 2xx가 아니면 본문을 끝까지 읽어 `SPFNTransportResponse`처럼 넘긴다 (400의 `invalidEvents`를 읽기 위해서).
- `timeoutMillis`는 **연결과 헤더까지의** 기한이다 (기본 15 000). 헤더가 온 뒤에는 전송이 기한을 두지 않는다: iOS는 요청의 `timeoutInterval`을 침묵 감시보다 긴 60 s로 둔다 (유휴 타임아웃이므로 핑이 오는 한 울리지 않는 뒷받침), Android는 스트림 호출에만 `callTimeout(0)`을 준다. 침묵은 상태 기계가 잰다.
- 어댑터는 요청 전송과 같은 강화를 쓴다: 쿠키 끔, 캐시 끔, 리다이렉트 안 따름 (3xx는 `serverError(3xx)`로 재시도, §4), OkHttp `retryOnConnectionFailure(false)`. 요청 헤더에 `Accept: text/event-stream`, `Cache-Control: no-cache`.
- 설명 문자열(`description`)은 URL을 싣지 않는다 (`SPFNTransportRequest`가 이미 그렇다).

## 4. 케이스 표 (상태 기계)

상태: **I** `idle`, **T** `connecting` 중 토큰 호출, **S** `connecting` 중 스트림 여는 중 (헤더 전), **O** `open`, **R** `retrying` (타이머 대기), **P** `suspended`, **X** `closed`. 괄호 안은 `attempt` n과 epoch e.

재연결 수치:

| 이름 | 값 | 근거 |
|---|---|---|
| 초기 지연 | 1 000 ms | 서버 재배포나 백프레셔 닫힘 뒤 사람이 느끼기 전에 돌아온다 |
| 배수 | 2 | 1, 2, 4, 8, 16, 30, 30 … |
| 상한 | 30 000 ms | 토큰 수명과 같다. 30초보다 오래 기다리면 사람은 이미 당겨서 새로 고친다 |
| 지터 | 계산값 × U[0.5, 1.0] | 서버 재시작 뒤 모든 기기가 같은 순간에 돌아오지 않게. 0이 되지 않게 아래를 0.5로 |
| 안정 연결 | 30 000 ms 이상 `open` | 이 뒤에 끊기면 `attempt`를 1로 되돌린다. 곧바로 닫히는 연결(백프레셔 반복, 잘못된 프록시)이 1초 간격 무한 반복이 되지 않게 |
| 침묵 감시 | 2.5 × `pingIntervalMillis` = 25 000 ms | 핑 두 번을 놓치고 반 간격 더. 핑도 서버의 같은 쓰기 큐를 지나므로 큐가 밀리면 핑도 늦는다 |
| 스트림 401 즉시 재시도 | 한 번 | 토큰이 발급과 사용 사이에 만료(30 s)되었을 수 있다. 두 번째 401은 백오프 |

토큰 호출 결과는 `execute`가 이미 분류한 것을 읽는다. `execute`는 인증 거부에 재핸드셰이크를 **한 번** 이미 썼으므로, 여기로 올라온 인증 거부는 최종 답이다.

| id | 상태 | 입력 | 다음 상태 | 효과와 비고 |
|---|---|---|---|---|
| E-1 | I | `start()` | T(1) | 토큰 호출 |
| E-2 | I | `start()`, 구독 없음 | X(`noSubscriptions`) | 아무것도 보내지 않는다 |
| E-3 | T | 토큰 200 `{token}` | S(n) | 스트림 GET `?token=…&events=a,b` (이름은 정렬, 쉼표로). 토큰은 이 요청에만 쓰고 버린다 |
| E-4 | T | 토큰 `.auth(…)` (401/403 봉투) | X(`unauthorized`) | 재시도 없음. 로그인 상태를 판단하는 것은 앱의 일이다 |
| E-5 | T | 토큰 `.server(…)` 403 | X(`forbidden`) | |
| E-6 | T | 토큰 `.server(…)` 5xx, 429 | R(n+1, `serverError`) | 백오프. 429의 `Retry-After`는 읽지 않는다 (§10 Q-D) |
| E-7 | T | 토큰 `.transport(connectivity/timedOut)` | R(n+1, `network`) | |
| E-8 | T | 토큰 `.decoding(notAnErrorEnvelope, onSuccessStatus: false)` | R(n+1, `unreadable`) | 프레임워크 토큰 경로의 401 `{"error":…}`, 프록시의 HTML 502가 여기로 온다. 둘을 가를 수 없으므로 백오프로 둔다. 상한이 30 s라 폭주하지 않는다 (§10 Q-C) |
| E-9 | T | 토큰 2xx인데 본문을 못 읽음 | R(n+1, `unreadable`) | P42: 서버는 토큰을 발급했을 수 있다. 쓰지 않은 토큰은 30 s 뒤 사라지므로 버려도 해가 없다 |
| E-10 | T | `stop()` | X(`stopped`) | 토큰 호출 취소 |
| E-11 | T | `suspend()` | P | 토큰 호출 취소 |
| E-12 | S | 200, `content-type: text/event-stream` | S | 헤더 받음. 침묵 감시 시작. 아직 `open`이 아니다 |
| E-13 | S | `connected` 프레임 | O(e+1) | epoch를 올린다. **화면은 다시 읽는다.** 안정 타이머(30 s) 시작 |
| E-14 | S | 200인데 `text/event-stream`이 아님 | R(n+1, `unreadable`) | 앞에 선 프록시가 HTML을 준 경우 |
| E-15 | S | 401 | T(n) 한 번, 두 번째는 R(n+1, `tokenRejected`) | E-3의 토큰이 만료·소모됨. 즉시 새 토큰 |
| E-16 | S | 400 `invalidEvents` | X(`unknownEvents(이름들)`) | 서버가 모르는 이름을 구독했다 (앱이 서버보다 새 버전). 재시도해도 같다 |
| E-17 | S | 400 그 밖 | X(`unknownEvents([])`) | `events`가 비었다: 합집합이 비지 않았으면 일어날 수 없다. 디버그 빌드에서 멈춘다 |
| E-18 | S | 403 | X(`forbidden`) | `authorize`가 빈 목록 |
| E-19 | S | 5xx, 3xx | R(n+1, `serverError(status)`) | |
| E-20 | S | 전송 오류 | R(n+1, `network`) | |
| E-21 | S | 침묵 감시 만료 (헤더 뒤 `connected` 없음) | R(n+1, `silence`) | 버퍼링하는 프록시. §5 U-5 |
| E-22 | O | 구독한 이름의 프레임 | O(e) | 봉투를 벗기고 디코드해 그 이름의 구독들에 전달. 감시 재시작 |
| E-23 | O | 구독하지 않은 이름의 프레임 | O(e) | 버린다. 감시 재시작 |
| E-24 | O | 디코더가 던짐 | O(e) | 그 프레임만 버림, `droppedFrames` +1. 연결 유지 |
| E-25 | O | `ping` 프레임 | O(e) | 앱에 넘기지 않는다. 감시 재시작 |
| E-26 | O | 주석 줄(`:`로 시작), 빈 줄, 모르는 필드 | O(e) | SSE 규칙대로 무시. 바이트가 왔으므로 감시 재시작 |
| E-27 | O | 침묵 감시 만료 | R(1 또는 n+1, `silence`) | 스트림을 닫는다. 안정 연결이었으면 `attempt` 1 |
| E-28 | O | 스트림이 오류 없이 끝남 | R(1 또는 n+1, `serverClosed`) | 서버 재배포, **백프레셔 닫힘** (큐 1000 초과), 파드 종료가 모두 이 모양이다. 셋을 가르지 않는다 |
| E-29 | O | 스트림 전송 오류 | R(1 또는 n+1, `network`) | 기지국 전환, Wi-Fi ↔ 셀룰러 |
| E-30 | O | 전달 버퍼 넘침 | O(e+1) | 가장 오래된 프레임을 버리고 epoch를 올린다. 화면은 다시 읽는다 |
| E-31 | O | 구독 합집합이 바뀜 | T(1) | 한 번 닫고 새 합집합으로. 다음 `connected`에서 O(e+1) |
| E-32 | O | 마지막 구독 취소 | X(`noSubscriptions`) | 스트림을 닫는다 |
| E-33 | R | 타이머 만료 | T(n) | |
| E-34 | R | `networkChanged(false)` | R(n) 타이머 멈춤 | 네트워크가 없는데 시도해 봐야 `network`로 실패한다 |
| E-35 | R | `networkChanged(true)` | T(n) | 곧바로. 기다리던 지연을 버린다 |
| E-36 | O | `networkChanged(false)` | O(e) | 아무것도 하지 않는다. 경로 감시기의 `false`는 흔히 거짓이고, 정말 끊겼으면 E-29나 E-27이 온다 |
| E-37 | 아무 상태 (X, I 제외) | `suspend()` | P | 스트림 닫음, 타이머 끔, 토큰 호출 취소 |
| E-38 | P | `resume()` | T(1) | 백오프 없이. 다음 `connected`에서 새 epoch → 화면은 뒤에 가 있던 동안을 다시 읽는다 |
| E-39 | P | 프레임, 타이머, 전송 결과 (늦게 도착) | P | 버린다. 모든 비동기 결과는 발급 번호를 달고 오고, 현재 번호가 아니면 버린다 |
| E-40 | 아무 상태 | 로그아웃 → 앱이 `stop()` | X(`stopped`) | **앱의 의무.** 열린 스트림은 토큰을 받은 주체의 프레임을 계속 받는다. SDK는 로그아웃을 모른다 (§8 H-4) |
| E-41 | O | 키 회전 (`SPFNKeyLifecycle.rotate()`) | O(e) | 아무 일도 없다. 스트림은 열린 순간의 주체에 묶이고, 키에 묶이지 않는다. 다음 토큰 호출은 새 키로 서명된다 |
| E-42 | T | 세션 폐기 (`noteSessionRevoked`) 뒤 토큰 호출 | E-4 또는 T 성공 | `execute`의 재핸드셰이크가 처리한다. 스트림 모듈은 모른다 |
| E-43 | 아무 상태 | 다른 계정으로 로그인 | 앱이 `stop()` 후 새 `SPFNEventStream` | 한 객체는 한 세션에 묶인다 |
| E-44 | X | `start()` | T(1) | `closed(unauthorized)` 뒤라도 앱이 다시 로그인했으면 부를 수 있다 |
| E-45 | X | `resume()`, `suspend()`, `networkChanged` | X | 끝난 스트림은 수명 신호로 되살아나지 않는다 |
| E-46 | O | `start()` | O(e) | 멱등 |
| E-47 | O | 30 s 경과 (안정 타이머) | O(e) | `attempt`를 1로. 상태 값은 바뀌지 않는다 |

셀 수: 47. 모두 순수 상태 기계의 단위 테스트다 (§9-1). 실제 네트워크와 기기 수명이 필요한 것은 §5의 U-셀이다.

**다시 읽기 규칙이 상태에 있는 자리.** E-13, E-30, E-38 뒤의 `open(e)`. 화면 모델은 `state`를 보다가 epoch가 바뀌면 목록을 한 번 읽는다. 첫 열림(epoch 1)도 포함한다: 화면의 첫 읽기와 스트림의 열림 사이에 커밋된 변화는 어느 쪽에서도 오지 않기 때문이다. 대가는 화면이 나타날 때 읽기 한 번이 더 는 것이고, 모으기(§3-2)가 첫 읽기와 겹치면 하나로 줄인다.

## 5. 기기에서 모을 증거 (운영자가 돌린다)

기기: iPhone (iOS 26) 하나와 iOS 17 시뮬레이터, Android 실기기 (API 34 이상) 하나와 에뮬레이터. 서버는 로컬 API 서버(루프백 http, iOS 시뮬레이터와 `adb reverse`로 연결한 Android)와 TLS가 있는 배포 환경 하나.

| id | 플랫폼 | 시험 | 볼 것 | 틀리면 |
|---|---|---|---|---|
| U-1 | 둘 다 | 서명된 `POST /events/token` | 200과 64 hex 토큰. 401이면 `authenticate`가 서명 요청의 주체를 `auth.userId`에 넣지 않는 것이다 | 서버 설정 (`getSubject`). SDK는 바뀌지 않는다 |
| U-2 | 둘 다 | 연결 → 서버에서 이벤트 한 번 | 커밋에서 `frames` 전달까지 1 s 안쪽. 첫 `connected`가 곧바로 오는가 | iOS: `URLSession`이 MIME 추측을 위해 첫 512바이트를 모으는지 본다. `connected`(약 80바이트)가 핑 여러 개와 함께 늦게 오면 이것이다 |
| U-3 | iOS | 앱을 뒤로 보냄 → 30 s, 3 min, 10 min 뒤 복귀 | `suspended` → 복귀 즉시 `connecting` → `open(e+1)`. 뒤로 간 동안 소켓이 남아 있었는가 (서버 로그의 "SSE dead connection cleaned up" 시각) | iOS가 소켓을 곧바로 끊지 않으면 서버 쪽 연결이 핑 쓰기가 실패할 때까지 남는다. 해는 없지만 서버 연결 수를 잰다 |
| U-4 | Android | 화면 끔 → Doze 강제 (`adb shell dumpsys deviceidle force-idle`) → 해제 | 앱이 `STARTED`가 아니므로 `suspended`여야 한다. 앞쪽에 둔 채 화면만 끄면 `onStop`이 오는가 | 오지 않으면 Doze 중 소켓은 살아도 핑이 늦어 E-27이 난다. 그것도 회복 경로다 |
| U-5 | 둘 다 | 배포 환경의 로드밸런서와 프록시 뒤에서 10분 유휴 | 10 s 핑이 유휴 절단을 막는가. 끊긴다면 몇 초 주기인가 (`retrying(silence)` 또는 `serverClosed` 횟수) | 서버 `pingInterval`과 SDK `pingIntervalMillis`를 같이 낮춘다 |
| U-6 | 둘 다 | 비행기 모드 켬 → 1 min → 끔 | `retrying(network)`, 백오프 지연이 1·2·4·8 …로 늘고 30에서 멈추는가. 경로 감시기를 둔 앱은 끄는 순간 `connecting` | — |
| U-7 | 둘 다 | Wi-Fi → 셀룰러 전환 (Wi-Fi 끔) | 열린 스트림이 오류로 끝나는가 (E-29), 조용히 멈추는가 (E-27까지 25 s) | 조용히 멈추면 25 s가 사람에게 긴지 본다 |
| U-8 | 둘 다 | 서버 재시작 (로컬) | E-28 → 약 1 s 뒤 재연결 → `open(e+1)` → 화면이 한 번 읽는다 | — |
| U-9 | 둘 다 | 느린 소비자 흉내: 디버거로 수집 코루틴·태스크를 멈추고 서버가 이벤트 1 100개 방출 | 서버가 닫고 (E-28), 재연결 뒤 새 epoch. 앱 쪽 전달 버퍼가 넘치면 E-30 | — |
| U-10 | 둘 다 | 기기 로그와 프록시 로그 전체에서 토큰 검색 | 토큰 문자열, `token=`가 든 URL이 어디에도 없다 (§6) | 결함. 출시 막음 |

## 6. 보안

| 규칙 | 어떻게 |
|---|---|
| 토큰은 기록되지 않는다 | 토큰 응답 타입(`SPFNEventStreamToken`)의 `description`/`toString()`은 `SPFNEventStreamToken(redacted)`. 토큰은 스트림 요청 하나를 만드는 동안만 지역 변수로 있고 필드에 저장되지 않는다 |
| 토큰을 실은 URL은 기록되지 않는다 | 스트림 요청은 `SPFNTransportRequest`로 만든다. 그 `description`은 이미 URL을 싣지 않는다 ("a nonce can live in a query parameter"). 어댑터의 오류 문자열은 `URLError` 코드 숫자와 OkHttp 예외 타입 이름만 싣는다. `localizedDescription`은 URL을 담을 수 있으므로 쓰지 않는다. 상태의 `reason`에도 URL이 없다 |
| 한 번 쓰고, 짧게 산다 | 서버의 사실 (30 s, `GETDEL`). SDK는 재연결마다 새로 받고, 받은 토큰을 다시 쓰지 않는다 (E-15의 재시도도 새 토큰) |
| 전송은 TLS | 스트림 URL은 `session.baseURL`에서 온다. 세션이 만들어질 때 https 또는 루프백 http만 받는다 (D21, `SPFNSession.isTrusted`). 에뮬레이터의 `10.0.2.2`는 거부된다 — `adb reverse`로 루프백을 쓴다. 새 예외는 없다 |
| 쿠키 없음, 리다이렉트 없음 | §3-6. 리다이렉트를 따르면 토큰을 실은 URL이 다른 호스트로 갈 수 있다 |
| 페이로드는 기록되지 않는다 | 디코드 실패는 수만 센다 (E-24). 이벤트 이름은 기록해도 된다 |
| 주체 분리 | 프레임을 누구에게 줄지는 서버의 `filter`가 정한다. SDK는 받은 것을 믿지 않고 화면이 다시 읽는다 (신호일 뿐). 로그아웃 뒤 `stop()`은 앱의 의무다 (E-40) |

## 7. 범위

| 포함 | 제외 |
|---|---|
| `SPFNEventStream`/`SpfnEventStream`, 설정, 구독, 상태, 백오프 값 타입. 순수 상태 기계와 SSE 줄 해석기. 스트림 전송 경계와 두 어댑터. 단위 테스트와 가짜 전송. architecture README에 절 하나, 등록부 갱신 | **APNs/FCM 푸시.** 앱이 뒤에 있거나 꺼져 있을 때 알리는 것은 다른 문제다 (서버의 발송 경로, 기기 토큰 등록, 사용자 동의). 이번에는 앞쪽에서만 |
| | **오프라인 큐.** 스트림은 받기만 한다. 보낼 것이 없다 |
| | **WebSocket.** 앱이 서버로 보낼 실시간 메시지가 생기면 따로 설계한다 |
| | 백그라운드 연결 유지 (iOS `beginBackgroundTask`, Android 포그라운드 서비스) |
| | 놓친 프레임의 재전송. 서버가 `Last-Event-ID`를 읽지 않고, 설계상 필요도 없다 (다시 읽기 규칙) |
| | SDK가 제공하는 경로 감시기. `networkChanged`는 입력만 둔다 (§10 Q-E) |
| | 이벤트 이름과 페이로드의 코드 생성. 앱 계약 문서는 이벤트를 담지 않는다. 디코더는 앱이 손으로 쓴다 (페이로드는 신호라 작다) |
| | 특정 앱 전용 코드. 예제는 일반적인 "세션 목록" 홈으로만 쓴다 |

## 8. 함정

### 8-1. 등록부 후보 (구현에서 실제로 나오면 등록한다)

| id | 후보 | 탐지 |
|---|---|---|
| H-1 | **브라우저 식 자동 재시도는 죽은 토큰으로 다시 연결한다.** 한 번 쓰는 토큰이 URL에 있으므로, 전송이나 라이브러리가 같은 URL로 재시도하면 401만 돌아온다. 재연결은 언제나 토큰 호출부터 | OkHttp `retryOnConnectionFailure(false)`. 어댑터가 재시도하지 않는다는 테스트: 가짜 서버가 첫 요청을 끊으면 요청 수가 1 |
| H-2 | **선로 위의 이름은 라우터 키다.** `defineEvent('session.activity', …)`를 `defineEventRouter({ sessionActivity })`에 넣으면 `events=` 쿼리도, `event:` 필드도, 봉투의 `event`도 `sessionActivity`다. 점 찍힌 이름으로 구독하면 400 `invalidEvents` (E-16) | `closed(unknownEvents)`를 앱이 기록하게 한다. 400 본문의 `validEvents`가 정답 목록이다 |
| H-3 | **엄격한 파서는 소수를 거부한다.** `SPFNCanonicalJSON.parse`는 정수만 받는다. 페이로드에 소수가 있으면 그 프레임은 디코드 실패로 버려진다 (E-24). 지금 서버의 페이로드는 문자열·정수·불리언뿐이다 | 서버 이벤트 스키마에 `Type.Number()` 소수가 들어오면 앱 쪽 디코드 실패 수가 오른다. 서버 쪽 리뷰에서 막는다 |
| H-4 | **로그아웃은 스트림을 닫지 않는다.** 스트림의 주체는 열린 순간에 정해지고 서버는 다시 묻지 않는다. `stop()` 없이 로그아웃하면 다음 사람이 이전 사람의 신호를 받는다 (신호일 뿐이고 읽기는 거부되지만, 어느 세션이 움직였는지는 샌다) | 예제 앱의 로그아웃 경로가 `stop()`을 부르는 단위 테스트. 앱에도 같은 테스트를 권한다 |
| H-5 | **읽기 타임아웃으로 침묵을 재면 두 플랫폼이 다르게 운다.** URLSession `timeoutInterval`은 유휴 기한이고 OkHttp `callTimeout`은 호출 전체 기한이다 (`SPFNURLSessionTransport`의 주석). 전체 기한이 붙은 스트림은 건강해도 그 시각에 끊긴다 | 침묵 감시는 상태 기계 하나. 스트림 어댑터의 `callTimeout`은 0이다는 테스트 |

### 8-2. 기존 항목

| 항목 | 이 설계에서 |
|---|---|
| P40 (수면 중 멈추는 단조 시계) | 침묵 감시와 백오프 타이머는 경과 시간을 잰다. 기기가 잠들면 앱은 이미 `suspended`라 타이머가 없다. 앞쪽에 둔 채 잠드는 경우(U-4)에도 멈추는 시계는 감시를 늦출 뿐 잘못 울리지 않는다. 그래도 타이머의 원천은 P40의 "수면을 포함하는" 쪽을 쓴다 |
| P42 (2xx인데 읽을 수 없는 응답) | 토큰 호출의 2xx 읽기 실패는 E-9. 서버가 발급했을 수 있지만 쓰지 않은 토큰은 스스로 사라지므로 재시도가 안전하다 |
| 전송 계층 주석 "URL은 기록하지 않는다" | 스트림 어댑터도 같은 규칙 (§6) |

갱신할 다른 문서: architecture README에 "The event stream" 절 (세 계층 표 옆에: 토큰은 execute를 지나고, 스트림은 전송 경계 하나를 더 쓴다), 모듈 표의 `SPFNClient` 외부 의존성은 그대로(새 의존성 없음).

## 9. 시험 계획

### 9-1. 단위 (툴킷 없음, Linux와 JVM)

`SPFNEventStreamMachineTests.swift` / `SpfnEventStreamMachineTest.kt`. 상태 기계는 `(state, input) -> (state, [effect])` 순수 함수다. 효과는 `mintToken`, `openStream(names)`, `closeStream`, `startSilenceTimer(ms)`, `startRetryTimer(ms)`, `startStableTimer(ms)`, `cancelTimers`, `deliver(name, value)`, `publish(state)`. 시계도 난수도 주입한다 (지터는 `U[0.5, 1.0]`를 내는 함수를 받는다). 테스트 이름은 셀 이름을 따른다: `e1_start_mintsToken`, `e15_stream401_retriesTokenOnce_thenBacksOff`, `e28_serverClose_afterStableOpen_resetsAttempt` … 셀 47개 전부. 추가로:

| 테스트 | 내용 |
|---|---|
| `backoff_sequence` | 지터 1.0에서 1, 2, 4, 8, 16, 30, 30 s. 지터 0.5에서 0.5, 1, 2 … |
| `staleResult_ignored` | 발급 번호가 지난 토큰 결과, 스트림 결과, 타이머는 상태를 바꾸지 않는다 (E-39) |
| `epoch_monotonic` | 어떤 입력 순서에서도 epoch는 줄지 않는다 (무작위 입력 1 000개, 고정 시드) |
| `closed_isTerminal_untilStart` | X에서 `start()` 말고는 아무것도 상태를 바꾸지 않는다 |
| `tokenPath_derived` | `/events/stream` → `/events/token`, `/sse` → `/token`, `/a/b/c` → `/a/b/token`, 쿼리 거부 |

`SPFNSSELineParserTests` / `SpfnSseLineParserTest`: 조각 경계가 줄 가운데, `\r\n`, `\r`, 여러 `data:` 줄 이어 붙이기, 주석, 필드 이름만 있는 줄, 빈 `event`(기본 `message`), UTF-8 멀티바이트가 조각 경계에서 잘림, 끝에 빈 줄 없이 스트림이 끝남(마지막 이벤트는 버린다 — SSE 규칙). 입력은 서버의 실제 모양을 고정한 픽스처 (`connected`, 이벤트, `ping`).

두 플랫폼의 테스트 이름이 같아야 한다는 것을 validate가 확인한다 (전송 테스트가 이미 그렇게 짝지어져 있다).

### 9-2. 가짜 전송

`SPFNFakeStreamTransport` / `SpfnFakeStreamTransport`: 테스트가 응답 상태, 헤더, 조각을 차례로 밀어 넣고, 오류로 끝내거나 조용히 끝낸다. `SPFNEventStreamTests`는 가짜 `SPFNTransport`(토큰)와 가짜 스트림 전송으로 객체 전체를 돌린다: `start` → 토큰 요청이 `execute`를 지나 서명 헤더를 달았는가, 스트림 요청의 URL과 헤더, `connected` → `open(1)`, 프레임 → `frames`, 끊김 → `retrying` → 재연결 → `open(2)`. 시간은 주입한 시계로 넘긴다. 실제 소켓은 없다.

### 9-3. 어댑터

iOS: 기존 `URLProtocol` 스텁으로 청크 응답을 흉내 내 델리게이트가 조각을 순서대로 넘기는지. Android: OkHttp `MockWebServer`가 이미 테스트 의존성이면 그것으로 (아니면 로컬 `ServerSocket`), `callTimeout`이 0인지, 리다이렉트를 따르지 않는지, 쿠키를 보내지 않는지.

### 9-4. 사람이 확인하는 것

§5의 U-셀. 예제 앱에 "이벤트" 화면 하나: 상태 readout (`stream=open(3)`), 받은 프레임 수, 버린 프레임 수. 로컬 서버에서 이벤트를 방출하는 스크립트는 예제 서버가 생기면 더한다.

## 10. 검토자가 답할 질문

| id | 질문 | 제안 |
|---|---|---|
| Q-A | 토큰 호출을 SDK 이벤트 모듈이 설정된 경로로 만드는 것 (§2-2 (다))을 받아들이는가. 코어 계약에 넣으려면 상류 exporter가 event 모듈의 경로를 내보내야 한다 | 받아들인다. 상류가 이벤트 표면을 계약으로 내보내면 (경로 파생 규칙까지) 다시 본다 |
| Q-B | 서명 요청에 CSRF 규칙이 걸리는 서버 설정을 SDK가 우회해야 하는가 | 우회하지 않는다. 쿠키 없는 서명 요청에 CSRF를 요구하는 것은 서버 설정의 결함으로 본다 (U-1에서 확인) |
| Q-C | 토큰 경로의 봉투 아닌 401(`Unable to identify subject`)을 재시도(E-8)로 둘 것인가, 닫을 것인가 | 재시도. 프록시의 5xx HTML과 가를 수 없고, 상한 30 s가 폭주를 막는다. 상류가 이 응답을 SPFN 오류 봉투로 바꾸면 E-4로 옮긴다 |
| Q-D | 429의 `Retry-After`를 읽을 것인가 | 이번에는 읽지 않는다. 토큰 경로에 속도 제한이 걸리면 다시 본다 |
| Q-E | 경로 감시기를 SDK가 제공할 것인가 | 이번에는 입력만. iOS `NWPathMonitor`는 Darwin 전용이라 모듈의 Linux 규칙과 부딪히고, Android는 `Context`가 필요하다. 두 번째 앱이 같은 코드를 쓰면 올린다 |
| Q-F | 앱이 서버보다 새로워서 모르는 이벤트 이름을 구독하면 (E-16), 아는 이름만으로 다시 연결할 것인가 | 닫는다 (`unknownEvents`). 조용히 줄이면 앱이 기다리는 신호가 안 온다는 사실이 숨는다. 앱은 이 상태에서 지금처럼 나타날 때와 당길 때 읽는다 |
| Q-G | 침묵 감시 2.5 × 핑 (25 s)이 사람에게 긴가 (U-7) | 25 s로 시작하고 U-5·U-7 결과로 정한다 |
