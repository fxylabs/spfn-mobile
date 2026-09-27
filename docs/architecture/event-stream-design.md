# 설계 — 서버 이벤트 스트림 `EventStream`

- 상태: 제안, 개정 1 (2026-09-27). 검토자가 §0–§3을 읽고 SSE(§2-1)와 설정된 경로로 만드는 토큰 호출(§2-2)을 받아들였다. 수명 분담(앱이 `start`/`suspend`/`resume`을 부르고 화면의 `subscribe`가 서버 쪽 이벤트 집합을 바꾸던 옛 §3-4·§3-5)은 거절되었고, 이 개정이 그 자리를 **SDK가 연결을 소유한다**로 바꾼다. 남은 질문은 §10에 있다. 구현 전이다.
- 대상: 클라이언트 모듈(`SPFNClient`, `spfn-client`)에 새로 들어갈 `SPFNEventStream`/`SpfnEventStream`, 그 상태 기계, 청취자 허브, SSE 줄 해석기, 스트림 전송 어댑터. `ui` 모듈(`SPFNUI`, `spfn-ui`)에 새로 들어갈 루트 부착과 화면 청취 API. `SPFNKeyLifecycle`/`SpfnKeyLifecycle`에 로그인 상태를 내보내는 읽기 전용 멤버 하나. 기존 `SPFNClient.execute`, `SPFNSession`, 전송 계층은 바뀌지 않는다
- 관련: [architecture README](README.md) "Three layers in the client module"·"The `ui` module"·"Three ways in", [custom-route-contract-design.md](custom-route-contract-design.md) §9 ("실시간은 계약이 담지 않는다"), [app-contract-codegen.md](app-contract-codegen.md), [tab-host-design.md](tab-host-design.md) (설계 문서의 모양), [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P40·P42, `tools/module-graph.json` (`ui`의 의존 규칙), 서버 쪽 정본은 `@spfn/core` 패키지의 `src/event/README.md` ("SSE authentication (Token Exchange)", "Multi-instance broadcast", "Pitfalls & anti-patterns")

## 0. 요약

| 항목 | 결정 |
|---|---|
| 전송 | **SSE**, 새 의존성 없이. iOS는 `URLSession` 데이터 태스크의 델리게이트가 받는 바이트, Android는 이미 쓰는 OkHttp의 응답 본문 `BufferedSource`. WebSocket도 폴링도 아니다 (§2-1, **받아들여짐**) |
| 토큰 호출 | **SDK의 이벤트 모듈이 설정된 경로로 `SPFNOperation`을 만들고 `SPFNClient.execute`로 보낸다.** 코어 계약 연산도 앱 계약 연산도 아니다. 서명, 한 번의 재핸드셰이크, `PROOF_EXPIRED` 재앵커가 그대로 따라온다 (§2-2, **받아들여짐**) |
| 연결의 주인 | **SDK.** 앱은 한 번 설정하고 (스트림 경로와 **고정된 이벤트 이름 목록**), 앱 루트에 한 번 붙인다. 그 뒤로 앱 코드는 연결을 열지도, 쉬게 하지도, 닫지도 않는다 |
| 설정 | 스트림 경로 (기본 `/events/stream`), 토큰 경로는 서버와 같은 규칙으로 **파생한다** (`/events/stream` → `/events/token`, 따로 줄 수도 있다), 이벤트 이름 목록 (비면 거부). 목록이 곧 `events=` 쿼리이고, 앱이 사는 동안 바뀌지 않는다 |
| 수명 입력 | **`ui` 모듈이 관찰한다**: 앞쪽/뒤쪽 (iOS `scenePhase`, Android `ProcessLifecycleOwner`), 로그인 상태 (`SPFNKeyLifecycle`의 로그인한 클라이언트 id — 로그아웃하면 닫힌다, 다른 계정이면 새로 연다), 네트워크 (`NWPathMonitor`, `ConnectivityManager`). 세 입력을 클라이언트 모듈의 상태 기계에 넘긴다. 연결은 "앞쪽이고 로그인했다"일 때만 있다 |
| 모듈 분담 | 클라이언트 모듈: 툴킷 없는 토큰 교환, SSE 해석기, 상태 기계, 재연결, 청취자 허브. JVM과 Linux에서 단위 테스트로 돈다. `ui` 모듈: 루트 부착, 세 관찰자, 화면 청취 API. **`ui` → 클라이언트 모듈 의존 간선이 새로 생긴다** (§3-7) |
| 화면 | **듣기만 한다.** 이름으로 프레임을 받고, 붙은 순간과 새 `open(epoch)`마다 "다시 읽어라" 신호를 받는다. 화면이 떠나면 듣기가 끝난다. 설정에 없는 이름으로 들으려 하면 프로그래머 오류로 곧바로 거부한다. **화면의 붙고 떨어짐은 연결을 건드리지 않는다: 이동은 재연결하지 않는다** |
| 상태 | `idle(signedOut 또는 background)`, `connecting(attempt)`, `open(epoch)`, `retrying(attempt, delay, reason)`, `offline(attempt)`, `closed(reason)`. 모든 전이는 툴킷 없는 순수 상태 기계에 있다 (`Flow`, `TabState`처럼) |
| 다시 읽기 규칙 | **신호가 오면 화면은 다시 읽는다.** 신호는 청취자가 붙을 때 한 번, 그 뒤 새 epoch(첫 열림, 재연결, 앱 복귀)마다 한 번, 그 청취자의 전달 버퍼가 넘칠 때 한 번. 프레임은 신호일 뿐 상태가 아니다 (최대 1회 전달) |
| 재연결 | 초기 1 s, 배수 2, 상한 30 s, 지터는 계산값의 50–100 % 균등. 30 s 이상 열려 있던 연결이 끊기면 처음부터 다시 센다. 네트워크가 없으면 타이머 없이 `offline`에서 기다린다 |
| 침묵 감시 | 마지막 바이트 뒤 `2.5 × pingInterval` (기본 25 s) 동안 아무것도 오지 않으면 끊고 재연결한다. 한 곳(상태 기계)에서만 잰다. 전송의 읽기 타임아웃에 기대지 않는다 |
| `ui` 없는 앱 | 클라이언트 모듈만으로도 쓴다: 같은 객체의 `setForeground`, `setSignedIn`, `setNetworkAvailable`을 앱이 부르고 `listen`으로 듣는다 (§3-6). 상태 기계는 같다 |
| 범위 밖 | APNs/FCM 푸시, 오프라인 큐, WebSocket, 백그라운드 연결 유지, 프레임 재전송(`Last-Event-ID`), 화면별 이벤트 집합 |

## 1. 맥락과 요구

어떤 앱의 홈은 세션 목록이고, 각 행은 그 세션의 에이전트가 일하는 중이면 스피너를, 사람이 아직 보지 않은 응답이 있으면 점을 보인다. 둘 다 서버의 사실이다. 지금 앱은 화면이 나타날 때와 사람이 당겨서 새로 고칠 때만 읽으므로, 그 사이에는 스피너가 멈춘 에이전트 위에서 돌고, 점은 새 응답이 와도 켜지지 않는다. 같은 신호를 상세 화면도, 다른 탭도 쓴다.

서버는 이미 같은 사실을 브라우저에 알린다. SPFN 서버의 `.events(router, { auth: { enabled: true, filter } })`가 SSE 스트림을 열고, 인증된 `POST /events/token`이 한 번 쓰는 30초짜리 토큰을 발급하고, `GET /events/stream?token=…&events=…`가 그 토큰으로 스트림을 연다. 이 설계는 네이티브 앱이 **같은 서버 표면을, 서버를 바꾸지 않고** 쓰게 한다.

개정 1이 요구를 바꾼 곳은 Q8–Q10이다: 연결의 수명은 앱 코드가 아니라 SDK가 정하고, 화면은 듣기만 한다. 앱이 수명을 부르는 옛 모양은 부르는 자리(홈 화면의 `onDisappear`)가 곧 다른 화면으로 가는 순간이라 이동마다 연결을 끊었고, 로그아웃 뒤 `stop()`을 잊는 것이 주체 사이의 누출(옛 E-40)이었다. 둘 다 앱마다 다시 짜야 하는 코드였다.

| # | 요구 | 이 설계에서 |
|---|---|---|
| Q1 | 앞쪽에 있는 네이티브 앱이 이름 붙은 SPFN 이벤트 프레임을 타입으로 받는다 | 화면 청취 `listen(name, decode)`와 그 SwiftUI·Compose 모양 (§3-4, §3-5) |
| Q2 | 토큰 교환은 서명된 execute 경로를 지난다. 쿠키는 없다 | 토큰 호출은 `SPFNClient.execute` 하나로 (§2-2). SDK 전송은 쿠키를 끈다 (`hardenedConfiguration`, `CookieJar.NO_COOKIES`) |
| Q3 | 경로는 설정할 수 있다. 앱은 이벤트를 다른 곳에 마운트할 수 있다 | `streamPath`, 파생 또는 명시 `tokenPath` (§3-1) |
| Q4 | 신호는 상태가 아니다. 연결이 (다시) 열리면 화면은 다시 읽는다 | `reread` 신호 (§3-4, §4) |
| Q5 | 끊김, 서버의 백프레셔 닫힘, 프록시의 유휴 절단을 스스로 회복한다 | 재연결 표와 침묵 감시 (§4) |
| Q6 | 두 플랫폼이 같은 어휘, 같은 전이 표를 가진다 | §3의 이름 표, §9의 공유 테스트 이름 |
| Q7 | 토큰과 토큰을 실은 URL은 어디에도 기록되지 않는다 | §6 |
| Q8 | **연결은 SDK가 소유한다.** 앱은 설정 한 번, 루트 부착 한 번으로 끝난다. 앞쪽/뒤쪽, 로그인/로그아웃, 네트워크를 SDK가 본다 | 설정 (§3-1), 루트 부착 (§3-3), 소유 표 (§3-8) |
| Q9 | **화면은 듣기만 한다.** 화면이 붙고 떨어져도 연결은 그대로다. 이동은 재연결하지 않는다 | 청취자 허브 (§3-4), L-셀 (§4) |
| Q10 | 로그아웃하면 앱 코드 없이 스트림이 닫힌다. 다른 계정으로 들어오면 그 계정의 토큰으로 새로 연다 | 로그인 입력 (§3-3), E-40·E-43 |

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

§2-1과 §2-2는 검토자가 받아들였다 (개정 1). 그대로 둔다. §2-4는 개정 1이 더한 수명 분담의 선택지다.

### 2-1. 전송: SSE, WebSocket, 폴링 (받아들여짐)

| | (가) SSE, 새 의존성 없음 (**선택**) | (나) WebSocket | (다) 폴링 |
|---|---|---|---|
| 서버 | 이미 있다. 브라우저가 같은 표면을 쓴다 | `.websockets(router)`는 따로 등록해야 한다. 이 서버 표면을 쓰는 앱은 지금 등록하지 않았다 | 목록 읽기를 주기적으로 부른다. 서버 변경 없음 |
| iOS | `URLSession` 데이터 태스크 + `URLSessionDataDelegate.urlSession(_:dataTask:didReceive:)`. 새 패키지 없음 | `URLSessionWebSocketTask`. 새 패키지는 없지만 프레임 모양이 SSE와 다르다 (`@spfn/core` WS 프로토콜) | 기존 `execute` |
| Android | 이미 의존하는 OkHttp의 `response.body.source()`를 줄 단위로 읽는다. `okhttp-sse` 산출물은 **쓰지 않는다** — 새 좌표이고 `externalDeps` 허용 목록에 한 줄이 는다. 줄 해석은 30줄이면 된다 | OkHttp `WebSocket`. 의존성은 있다 | 기존 `execute` |
| 방향 | 서버 → 앱뿐. 이 요구에는 그것만 필요하다 | 양방향. 앱이 보낼 것이 없다 | — |
| 지연 | 커밋 직후 | 커밋 직후 | 주기의 절반 평균. 스피너가 5초 늦게 멈추는 것은 이 앱이 풀려는 문제 그대로다 |
| 비용 | 연결 하나, 10초마다 핑 몇십 바이트 | 연결 하나 | 주기마다 목록 전체. 사람이 보고 있는 동안 계속 |
| 인증 | 토큰 교환, 한 번 쓰는 토큰 | 같은 토큰 교환 (`/ws/token`) | 기존 서명 요청 |

(가)를 고른 이유: 서버에 이미 있고, 브라우저가 쓰는 같은 규칙(신호, 최대 1회, 주인에게만)을 그대로 물려받으며, 두 플랫폼 모두 새 의존성이 없다. (나)는 앱이 서버로 보낼 것이 생길 때 따로 설계한다 (§7). (다)는 스트림을 쓸 수 없는 서버를 위한 대체로 남기지 않는다: 스트림이 `closed`면 화면은 지금처럼 나타날 때(붙을 때의 `reread`)와 당길 때 읽는다. 그것이 대체다.

**iOS에서 `URLSession.bytes(for:)`를 쓰지 않는 이유.** 이 모듈은 Linux에서도 빌드된다 (architecture README 모듈 표). swift-corelibs-foundation의 `FoundationNetworking`에 `bytes(for:)`(`AsyncBytes`)가 있는지는 이 설계에서 확인하지 못했다. 델리게이트의 `didReceive data`는 두 곳 모두에 있다. 그래서 어댑터는 델리게이트 모양으로 쓰고, Linux 빌드가 되는지는 구현 PR의 `swift build`가 확인한다. 안 되면 어댑터 파일 하나만 `#if canImport(Darwin)`으로 통째로 감싸고, 상태 기계와 해석기는 Linux에 남는다.

### 2-2. 한 번 쓰는 토큰 호출은 어디에 있는가 (받아들여짐)

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

### 2-4. 수명은 누가 정하는가 (개정 1)

| | (가) 앱이 부른다 (제안 0, **거절됨**) | (나) SDK가 소유하고 `ui` 모듈이 관찰한다 (**선택**) |
|---|---|---|
| 연결을 여는 자리 | 화면 코드의 `start()`/`resume()` | 루트 부착 한 번. 조건(앞쪽 ∧ 로그인)이 맞으면 SDK가 연다 |
| 이벤트 집합 | 화면의 `subscribe`가 합집합에 더한다. 합집합이 바뀌면 재연결 | 설정의 고정 목록. 화면은 그 안에서 고른다. 집합이 바뀌는 일이 없다 |
| 이동 | 홈의 `onDisappear`가 `suspend()`, 돌아오면 `resume()` → 이동마다 토큰 호출과 재연결 | 청취자만 붙고 떨어진다. 연결은 그대로 |
| 로그아웃 | 앱의 의무(`stop()`). 잊으면 다음 사람이 이전 사람의 신호를 받는다 | SDK가 로그인 상태를 본다. 앱 코드가 없다 |
| 툴킷 경계 | 클라이언트 모듈은 툴킷이 없다. 앱이 번역한다 | 같다. 번역을 `ui` 모듈이 한다 (이미 SwiftUI·Compose에 의존한다). 상태 기계는 클라이언트 모듈에 남아 JVM·Linux에서 돈다 |
| 대가 | 앱마다 같은 수명 코드 | `ui` → 클라이언트 모듈 간선 하나 (§3-7). 뒤에 있는 탭만 쓰는 이벤트도 연결을 연다 (연결은 하나라 비용이 같다) |

## 3. 공개 API

어휘는 두 플랫폼에서 같다. Swift는 `SPFN` 접두사, Kotlin은 `Spfn` 접두사. validate가 두 플랫폼의 이름을 비교하는 절에 이 이름들이 는다.

한눈에:

| 역할 | 모듈 | Swift | Kotlin |
|---|---|---|---|
| 설정 | 클라이언트 | `SPFNEventStreamConfiguration` | `SpfnEventStreamConfiguration` |
| 연결 객체 | 클라이언트 | `SPFNEventStream` | `SpfnEventStream` |
| 루트 부착 | `ui` | `View.spfnEventStream(_:keyLifecycle:)` (뷰 수정자) | `SpfnEventStreamHost(stream, keyLifecycle) { … }` (컴포저블 호스트) |
| 화면 청취 (뷰) | `ui` | `View.onSPFNEvent(_:decode:perform:)` | `SpfnEventEffect(name, decode) { signal -> … }` |
| 화면 청취 (모델) | 클라이언트 | `SPFNEventStream.listen(_:decode:) -> AsyncStream<SPFNEventSignal<E>>` | `SpfnEventStream.listen(name, decode): Flow<SpfnEventSignal<E>>` |
| 신호 | 클라이언트 | `SPFNEventSignal<E>` (`.reread(SPFNRereadCause)`, `.frame(E)`) | `SpfnEventSignal<E>` (`Reread(cause)`, `Frame(value)`) |
| 수동 입력 (`ui` 없는 앱) | 클라이언트 | `setForeground(_:)`, `setSignedIn(_:)`, `setNetworkAvailable(_:)` | 같은 이름 |
| 상태 (진단) | 클라이언트 | `state`, `states` | `state: StateFlow` |
| 로그인 상태의 원천 | 클라이언트 | `SPFNKeyLifecycle.signedInClientIDs: AsyncStream<String?>` | `SpfnKeyLifecycle.signedInClientId: StateFlow<String?>` |
| 환경 | `ui` | `EnvironmentValues.spfnEventStream` | `LocalSpfnEventStream` |

### 3-1. 설정과 생성

| Swift | Kotlin |
|---|---|
| `SPFNEventStreamConfiguration(events: [String], streamPath: String = "/events/stream", tokenPath: String? = nil, pingIntervalMillis: Int64 = 10_000, backoff: SPFNEventStreamBackoff = .standard, deliveryBuffer: Int = 256) throws` | `SpfnEventStreamConfiguration(events: List<String>, streamPath: String = "/events/stream", tokenPath: String? = null, pingIntervalMillis: Long = 10_000, backoff: SpfnEventStreamBackoff = SpfnEventStreamBackoff.Standard, deliveryBuffer: Int = 256)` |
| `SPFNEventStream(client: SPFNClient, session: SPFNSession, configuration: SPFNEventStreamConfiguration, transport: any SPFNStreamTransport = SPFNURLSessionStreamTransport())` | `SpfnEventStream(client: SpfnClient, session: SpfnSession, configuration: SpfnEventStreamConfiguration, transport: SpfnStreamTransport = SpfnOkHttpStreamTransport(), scope: CoroutineScope)` |

- **`events`는 앱이 이 스트림에서 받을 이름 전부다.** 서버 이벤트 라우터의 **키**다 (`defineEventRouter({ sessionActivity, … })`의 `sessionActivity`. `defineEvent('session.activity', …)`의 첫 인자는 선로에 나오지 않는다, §8 H-2). 정렬해 쉼표로 이은 것이 `events=` 쿼리다. 비었거나, 빈 문자열이나 쉼표를 담은 이름이 있으면 거부한다. 중복은 하나로 친다. 생성 뒤에 바뀌지 않는다: 바꾸는 API가 없다.
- `tokenPath`가 `nil`이면 서버의 규칙으로 파생한다: `streamPath`의 마지막 `/` 뒤를 `token`으로 바꾼다. `/events/stream` → `/events/token`, `/sse` → `/token`. 경로는 `/`로 시작해야 하고, 쿼리를 담으면 거부한다.
- 거부는 모두 생성 시각에: Swift `throws SPFNEventStreamError.invalidConfiguration(field)`, Kotlin `IllegalArgumentException`. 앱 합성 지점에서 한 번 일어나는 일이라 첫 실행에서 드러난다.
- `pingIntervalMillis`는 **서버의** 핑 간격이다. SDK가 핑을 보내지 않는다. 침묵 감시는 이 값의 2.5배다. 서버가 `pingInterval`을 바꾸면 앱도 바꾼다.
- 스트림 URL은 `session.baseURL + streamPath`다. `session`이 이미 https 또는 루프백 http만 받으므로 (D21) 스트림도 같은 규칙 아래에 있다. 새 검사가 없다.
- `deliveryBuffer`는 **청취자 하나의** 큐 길이다 (§3-4).
- `transport`는 스트림 전송이다. `SPFNTransport`(요청 하나, 응답 하나)와 다른 경계다 (§3-9).
- 앱은 이 객체를 합성 지점에서 **하나** 만든다. 로그인·로그아웃을 지나도 같은 객체다 (계정은 입력이다, §3-3). 만들어진 객체는 `idle(signedOut)`에 있고 아무것도 보내지 않는다.

### 3-2. 상태

| Swift | Kotlin |
|---|---|
| `enum SPFNEventStreamState: Equatable, Sendable` | `sealed interface SpfnEventStreamState` |
| `var state: SPFNEventStreamState { get }`, `var states: AsyncStream<SPFNEventStreamState>` (구독하면 현재 값을 먼저 준다) | `val state: StateFlow<SpfnEventStreamState>` |

| 상태 | 뜻 |
|---|---|
| `idle(reason)` | 연결할 조건이 아니다. `reason`: `signedOut` (로그인한 계정 없음, 생성 직후), `background` (로그인했지만 뒤쪽). 연결도 타이머도 없다. 두 조건이 다 거짓이면 `signedOut` |
| `connecting(attempt)` | 토큰을 받거나 스트림을 여는 중. `attempt`는 마지막 안정 연결 뒤 몇 번째 시도인가 (1부터) |
| `open(epoch)` | `connected` 프레임을 받았다. `epoch`는 이 객체가 열린 횟수, 1부터. 값이 바뀔 때마다 붙어 있는 청취자에게 `reread`가 간다 |
| `retrying(attempt, delayMillis, reason)` | 다음 시도를 기다린다. `reason`: `network`, `silence`, `serverClosed`, `serverError(status)`, `tokenRejected`, `unreadable` |
| `offline(attempt)` | 연결할 조건인데 네트워크가 없다. 타이머 없이 네트워크가 돌아오기를 기다린다 |
| `closed(reason)` | 스스로 다시 시도하지 않는다. `reason`: `unauthorized`, `forbidden`, `unknownEvents([String])`. 조건 하나가 거짓이 되었다가 돌아올 때(뒤쪽 갔다 앞쪽, 다른 계정)만 벗어난다 (E-44) |

옛 상태와의 관계: `suspended`는 `idle(background)`가 되었다. `closed(stopped)`는 `idle(signedOut)`이 되었다 (앱이 부르는 `stop()`이 없다). `closed(noSubscriptions)`는 없어졌다: 목록은 설정에서 비지 않고, 청취자 수는 연결과 무관하다. `offline`은 새 상태다: 옛 표의 "타이머를 멈춘 `retrying`"(옛 E-34)을 따로 보이게 했다.

상태는 **화면이 다시 읽을 때를 정하는 데 쓰지 않는다.** 그것은 `reread` 신호의 일이다 (§3-4). 상태는 진단과 "실시간" 표시 같은 장식에 쓴다. 예제 앱의 이벤트 화면이 `stream=open(3)`을 보인다 (§9-4).

### 3-3. 루트 부착 (`ui` 모듈)

앱은 루트에 **한 번** 붙인다. 붙이는 자리가 세 관찰자를 띄우고, 스트림을 환경에 넣어 아래 화면들이 찾게 한다.

```swift
// iOS — 앱 합성 지점
@main
struct ExampleApp: App
{
    let events = try! SPFNEventStream(
        client: client,
        session: session,
        configuration: .init(events: ["sessionActivity", "sessionUnread"])
    )

    var body: some Scene
    {
        WindowGroup
        {
            RootView()
                .spfnEventStream(events, keyLifecycle: keyLifecycle)
        }
    }
}
```

```kotlin
// Android — 액티비티의 setContent 안
val events = SpfnEventStream(
    client = client,
    session = session,
    configuration = SpfnEventStreamConfiguration(events = listOf("sessionActivity", "sessionUnread")),
    scope = applicationScope,
);

setContent
{
    SpfnEventStreamHost(events, keyLifecycle)
    {
        RootScreen();
    }
}
```

붙은 자리가 관찰하는 것:

| 입력 | iOS (`spfnEventStream` 수정자) | Android (`SpfnEventStreamHost`) | 상태 기계에 넘기는 값 |
|---|---|---|---|
| 앞쪽/뒤쪽 | `@Environment(\.scenePhase)`. `.active`와 `.inactive`는 앞쪽, `.background`만 뒤쪽 (§8 H-6). 수정자가 여러 장면(iPad 다중 창)에 붙으면 장면마다 세고, 하나라도 앞쪽이면 앞쪽 | `ProcessLifecycleOwner.get().lifecycle`: `ON_START` 앞쪽, `ON_STOP` 뒤쪽. 액티비티의 수명주기가 아니다 — 회전마다 재연결하지 않게 (§8 H-7) | `setForeground(Bool)` |
| 로그인 | `keyLifecycle.signedInClientIDs`를 `task`에서 읽는다 | `keyLifecycle.signedInClientId`를 수집한다 | `setSignedIn(String?)`: `nil`은 로그아웃, 다른 id는 다른 계정 |
| 네트워크 | `NWPathMonitor`, `status == .satisfied`면 있음 | `ConnectivityManager.registerDefaultNetworkCallback`: `onAvailable` 있음, `onLost` 없음 | `setNetworkAvailable(Bool)` |

- 부착은 멱등이다: 같은 스트림을 두 번 붙이면 (장면 둘) 관찰자는 하나씩 더 세지만 입력은 같은 값이다. 서로 다른 두 스트림을 한 트리에 붙이면 안쪽이 환경을 가린다 — 한 앱에 스트림은 하나다.
- 부착이 떠나면 (뷰가 사라지면, 컴포지션이 끝나면) 관찰자를 풀고 `setForeground(false)`를 넘긴다. 스트림 객체는 앱의 것이라 버리지 않는다.
- 로그인 상태의 원천은 **`SPFNKeyLifecycle`**이다. 이 SDK에서 로그인은 활성 슬롯에 클라이언트 id가 저장된 키가 있는 것이고 (`enroll`·`enrollByDeviceCode`·`enrollByLinkCode`가 저장한다), 로그아웃은 `wipe()`다 (`noteSessionRevoked()`도 `wipe()`다). 구현 PR은 라이프사이클에 읽기 전용 멤버 하나를 더한다: Swift `signedInClientIDs: AsyncStream<String?>` (구독하면 현재 값을 먼저 준다), Kotlin `signedInClientId: StateFlow<String?>`. 저장·삭제가 끝난 뒤 값을 낸다. 회전(`rotate`)은 클라이언트 id를 바꾸지 않으므로 값이 바뀌지 않는다 (E-41).
- 뷰 수정자의 이름을 `spfnEventStream`으로 한 것은 SwiftUI의 `environment(_:)`·`task` 같은 수정자 이름 규칙(소문자 동사·명사)을 따른 것이다. Compose의 `SpfnEventStreamHost`는 이 모듈의 `NavigationHost`·`TabHost`·`FlowHost`와 같은 "내용을 감싸는 호스트" 모양이다.

### 3-4. 화면 청취

화면은 이름 하나로 듣는다. 듣는 동안 받는 것은 두 가지다:

| Swift | Kotlin |
|---|---|
| `enum SPFNEventSignal<Event: Sendable>: Sendable { case reread(SPFNRereadCause); case frame(Event) }` | `sealed interface SpfnEventSignal<out E> { data class Reread(val cause: SpfnRereadCause); data class Frame<E>(val value: E) }` |
| `enum SPFNRereadCause: Equatable, Sendable { case attached; case opened(epoch: Int); case overflow }` | `sealed interface SpfnRereadCause { data object Attached; data class Opened(val epoch: Int); data object Overflow }` |

**모델 수준 (클라이언트 모듈, 툴킷 없음):**

| Swift | Kotlin |
|---|---|
| `func listen<Event: Sendable>(_ name: String, decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event) -> AsyncStream<SPFNEventSignal<Event>>` | `fun <E> listen(name: String, decode: (SpfnCanonicalValue) -> E): Flow<SpfnEventSignal<E>>` |

```swift
// 화면 모델. 뷰의 .task { await model.observe(events) }에서 부른다 — 화면이 떠나면 태스크가 취소되고 듣기가 끝난다.
func observe(_ events: SPFNEventStream) async
{
    for await signal in events.listen("sessionActivity", decode: SessionActivity.init(canonical:))
    {
        switch signal
        {
        case .reread:
            await load()
        case .frame(let activity):
            apply(activity)
        }
    }
}
```

**뷰 수준 (`ui` 모듈, 환경의 스트림을 쓴다):**

| Swift | Kotlin |
|---|---|
| `func onSPFNEvent<Event: Sendable>(_ name: String, decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event, perform: @escaping @MainActor (SPFNEventSignal<Event>) async -> Void) -> some View` | `@Composable fun <E> SpfnEventEffect(name: String, decode: (SpfnCanonicalValue) -> E, onSignal: suspend (SpfnEventSignal<E>) -> Unit)` |

```swift
SessionListView(model: model)
    .onSPFNEvent("sessionActivity", decode: SessionActivity.init(canonical:))
    {
        signal in
        switch signal
        {
        case .reread:
            await model.load()
        case .frame(let activity):
            model.apply(activity)
        }
    }
```

```kotlin
SpfnEventEffect("sessionActivity", SessionActivity::fromCanonical)
{
    signal ->
    when (signal)
    {
        is SpfnEventSignal.Reread -> model.load();
        is SpfnEventSignal.Frame -> model.apply(signal.value);
    }
}
```

`onSPFNEvent`는 `task` 위에 선 `listen`이고, `SpfnEventEffect`는 `LaunchedEffect(name)` 안에서 `listen`을 수집한다. 둘 다 환경(`EnvironmentValues.spfnEventStream`, `LocalSpfnEventStream`)에서 스트림을 찾는다. 루트 부착 없이 쓰면 프로그래머 오류다 (Swift `preconditionFailure`, Kotlin `IllegalStateException`).

규칙:

- **붙을 때 첫 신호는 언제나 `reread(.attached)`다.** 스트림의 상태와 무관하다 (`closed`여도 온다). 그래서 이 신호가 화면의 첫 읽기가 된다: 붙은 **뒤에** 읽으므로 읽기와 붙기 사이에 커밋된 변화가 빠지지 않는다. 나타날 때 따로 읽는 코드는 지운다. 당겨서 새로 고침은 그대로 앱의 것이다.
- **새 `open(epoch)`마다 붙어 있는 모든 청취자가 `reread(.opened(epoch))`를 받는다.** 첫 열림, 재연결, 앱 복귀, 다른 계정이 모두 새 epoch다. 그 청취자의 큐에 남은 옛 연결의 프레임은 버린다: 다시 읽기가 그것들을 덮는다. 붙은 직후에 연결이 열리면 `attached`와 `opened(1)`이 잇달아 온다 — 화면의 모으기(아래)가 둘을 한 번의 읽기로 줄인다.
- `frame`은 설정된 이름 중 이 청취자가 고른 이름의 프레임이다. `decode`는 봉투 `{"event":…,"data":…}`의 `data`를 받는다. 봉투를 벗기는 것은 SDK다. 디코더는 청취자마다 따로다: 한 청취자의 디코더가 던지면 그 청취자에게만 그 프레임이 가지 않고 `droppedFrames` 진단 수가 1 는다. 연결과 다른 청취자는 그대로다. 오류 값에는 페이로드를 싣지 않는다 (§6).
- **설정에 없는 이름은 프로그래머 오류다.** `listen`을 부른 순간 거부한다: Swift `preconditionFailure("… is not in the configured events")` (릴리스 빌드에서도 멈춘다), Kotlin `require` → `IllegalArgumentException`. Kotlin의 `listen`은 차가운 `Flow`를 돌려주지만 이름 검사는 수집 전, 부른 순간에 한다. 조용히 아무것도 안 주는 청취자는 "왜 신호가 안 오지"를 기기에서야 드러내기 때문이다. 이름이 문자열 상수라 첫 실행에서 드러난다.
- 같은 이름에 청취자가 둘이면 둘 다 같은 프레임을 받는다. 한 화면이 두 이름을 들으려면 청취자를 둘 둔다 (첫 `attached`도 둘이다).
- **듣기의 끝은 청취자의 끝이다.** Swift: `listen`이 돌려준 `AsyncStream`을 소비하는 태스크가 취소되면 (`.task`는 뷰가 사라질 때 취소된다) `onTermination`이 떼어 낸다. Kotlin: 수집이 취소되면 (`LaunchedEffect`는 컴포지션을 떠날 때 취소된다) 떼어 낸다. 붙기는 Swift에서 `listen`을 부른 순간, Kotlin에서 수집을 시작한 순간이다.
- **붙고 떨어짐은 연결을 건드리지 않는다.** 청취자가 0이 되어도 연결은 열려 있고 프레임은 버려진다 (L-2). 청취자가 붙어도 토큰 호출은 없다 (L-1). 이동은 재연결하지 않는다 (L-6).
- 전달은 순서대로다. 큐는 청취자마다 `deliveryBuffer`개이고, 넘치면 큐를 비우고 `reread(.overflow)` 하나를 넣는다 (L-4). 다른 청취자와 epoch는 그대로다: 느린 화면 하나가 모든 화면을 다시 읽게 하지 않는다.
- 모으기(coalescing)는 앱의 일이다. SDK는 프레임도 `reread`도 합치지 않는다. 어떤 화면은 프레임마다 한 행을 고치고, 어떤 화면은 300 ms 동안 모아 목록을 한 번 읽는다. 그 차이는 화면이 안다.
- `closed`나 `idle`인 동안 붙은 청취자는 `attached`만 받고 기다린다. 연결이 열리면 `opened`를 받는다.

### 3-5. 옛 구독 API와의 대응

| 제안 0 | 개정 1 | 까닭 |
|---|---|---|
| `subscribe(name, decode)` → `SPFNEventSubscription` (`frames`, `cancel()`) | `listen(name, decode)` → 신호의 비동기 열 | 이름이 서버 이벤트 집합에 더하지 않는다. 끝은 `cancel()`이 아니라 소비의 취소다. 다시 읽기 신호가 같은 열에 들어 순서가 정해진다 |
| `start()`, `stop()`, `suspend()`, `resume()` | 없음. 조건 입력 셋 (§3-6) | 앱이 수명을 부르지 않는다 |
| `networkChanged(available:)` | `setNetworkAvailable(_:)` | `ui` 모듈이 넘긴다. 이름을 다른 둘과 맞췄다 |
| 상태의 epoch를 보고 화면이 다시 읽음 | `reread` 신호 | 늦게 붙은 화면이 `StateFlow`의 현재 값으로 한 번 더 읽던 규칙이 `attached`로 바뀌었다. 상태는 진단용이 되었다 |

### 3-6. 수동 입력 (`ui` 모듈 없는 앱)

클라이언트 모듈만 쓰는 앱(자체 UI 계층, 테스트 하네스)은 `ui` 모듈이 하는 일을 직접 한다. 같은 객체, 같은 상태 기계다.

| 멤버 | 내용 |
|---|---|
| `setForeground(_ isForeground: Bool)` | 앞쪽이면 `true`. 생성 직후 값은 `false` |
| `setSignedIn(_ clientID: String?)` | 로그인한 클라이언트 id, 없으면 `nil`. 값이 다른 id로 바뀌면 다른 계정이다: 열린 연결을 닫고 새 토큰으로 다시 연다 (E-43). 생성 직후 값은 `nil` |
| `setNetworkAvailable(_ isAvailable: Bool)` | 경로 감시기가 없으면 부르지 않아도 된다: 생성 직후 값은 `true`이고, 백오프로 회복한다 |
| `listen(_:decode:)` | §3-4의 모델 수준 API 그대로 |

세 입력은 모두 동기 호출이고 멱등이다 (같은 값을 두 번 넘기면 두 번째는 아무 일도 없다). Swift에서는 `@MainActor`가 아니라 내부 actor가 입력을 순서대로 받는다 (`SPFNSession`처럼). 호출자는 메인 액터에서 부르고 기다리지 않는다. Kotlin에서는 생성자의 `scope`에서 차례로 처리한다. 연결은 `foreground && signedIn != nil`일 때만 있다.

### 3-7. 모듈 간선과 새 의존성

`ui` 모듈은 지금 코어에만 의존한다 (`tools/module-graph.json`의 노트: "nothing here needs a transport, a session or a generated operation"). 루트 부착과 `onSPFNEvent`가 `SPFNEventStream`과 `SPFNKeyLifecycle`을 받으므로 이 규칙이 바뀐다:

| 바뀌는 것 | iOS | Android |
|---|---|---|
| 모듈 간선 | `SPFNUI` → `SPFNClient` (그리고 그 너머 `SPFNAuth`, `SPFNGenerated`) | `spfn-ui` → `spfn-client` (`api`: 공개 시그니처가 `SpfnEventStream`을 싣는다). OkHttp가 `spfn-ui`의 전이 의존이 된다 |
| 외부 의존성 | 없음. `Network`(`NWPathMonitor`)는 OS가 싣는 프레임워크다. 그 파일은 `#if canImport(Network)`로 통째로 감싸고, validate 8절의 Apple 전용 프레임워크 목록에 `Network`를 더한다 (SwiftUI를 더한 것과 같은 까닭) | `androidx.lifecycle:lifecycle-process` 2.10.0. **새로 받는 산출물은 아니다**: 오늘 `spfn-ui`의 `releaseRuntimeClasspath`에 이미 있다 (`compose-foundation` → `emoji2` → `lifecycle-process`, `./gradlew :spfn-ui:dependencies`로 확인). 직접 쓰므로 이 모듈의 관례대로(`activity-compose`처럼) 카탈로그 별칭과 `externalDeps.android`에 한 줄 더한다. 검증 메타데이터에는 이미 있다 |
| 권한 | 없음 | `android.permission.ACCESS_NETWORK_STATE` (일반 권한, 설치 시 부여). `spfn-ui`에 지금 `AndroidManifest.xml`이 없으므로 하나 생기고, 앱 매니페스트로 병합된다. `RELEASE.md`/`CHANGELOG.md`에 적는다 |
| Linux | 루트 부착과 `onSPFNEvent`는 SwiftUI 파일(`#if canImport(SwiftUI)`) 안이다. 장면을 세는 `SPFNForegroundTally`(장면 id별 앞쪽 여부 → 하나라도 앞쪽인가)는 툴킷 없는 파일이라 Linux에서 테스트가 돈다 | — |

"렌더링만 하는 앱은 클라이언트 모듈을 링크하지 않는다"는 노트의 약속이 깨진다. 이 대가를 피하는 다른 길은 새 모듈(`ui` 옆의 작은 이벤트 모듈) 하나이고, 그것은 새 산출물이라 검토자에게 묻는다 (§10 Q-H).

### 3-8. 누가 무엇을 하는가

| 일 | SDK 클라이언트 모듈 | SDK `ui` 모듈 | 앱 |
|---|---|---|---|
| 스트림 경로와 이벤트 이름 목록을 정한다 | 검사하고 파생한다 | — | **한 번, 합성 지점에서** |
| 스트림 객체를 만든다 | — | — | **한 번** |
| 루트에 붙인다 | — | 수정자·호스트를 준다 | **한 번, 루트에서** |
| 앞쪽/뒤쪽을 안다 | 입력을 받는다 | `scenePhase`·`ProcessLifecycleOwner`를 본다 | — (`ui` 없는 앱은 `setForeground`) |
| 로그인·로그아웃·계정 바뀜을 안다 | 라이프사이클이 값을 낸다, 상태 기계가 입력을 받는다 | 그 값을 넘긴다 | 로그인·로그아웃 자체 (`enroll…`/`wipe()`) |
| 네트워크를 안다 | 입력을 받는다 | `NWPathMonitor`·`ConnectivityManager`를 본다 | — |
| 토큰 호출, 스트림 열기, SSE 해석, `connected`·`ping` 소비 | **한다** | — | — |
| 재연결, 백오프, 침묵 감시, 늦은 결과 버리기 | **한다** | — | — |
| 로그아웃 시 스트림 닫기 | **한다** (E-40) | 입력을 넘긴다 | — (옛 설계의 의무가 사라졌다) |
| 어느 화면이 어떤 이름을 듣나, 디코더 | 이름을 검사한다 | 환경에서 스트림을 찾아 준다 | **화면이 고른다** |
| 언제 다시 읽나 | `reread` 신호를 낸다 | 화면에 전달한다 | 신호를 받으면 **읽는다** |
| 모으기, 당겨서 새로 고침 | — | — | **한다** |
| 스트림이 `closed`일 때의 대체 | `attached` 신호는 그래도 준다 | — | 당겨서 새로 고침 |

### 3-9. 스트림 전송 경계

| Swift | Kotlin |
|---|---|
| `protocol SPFNStreamTransport: Sendable { func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse }` | `interface SpfnStreamTransport { suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse }` |
| `SPFNStreamResponse { statusCode, headers, chunks: AsyncThrowingStream<[UInt8], Error>, cancel() }` | `SpfnStreamResponse { statusCode, headers, chunks: Flow<ByteArray>, cancel() }` |

- 오류는 `SPFNTransportError`의 네 경우를 그대로 쓴다. 새 오류 어휘가 없다.
- 2xx가 아니면 본문을 끝까지 읽어 `SPFNTransportResponse`처럼 넘긴다 (400의 `invalidEvents`를 읽기 위해서).
- `timeoutMillis`는 **연결과 헤더까지의** 기한이다 (기본 15 000). 헤더가 온 뒤에는 전송이 기한을 두지 않는다: iOS는 요청의 `timeoutInterval`을 침묵 감시보다 긴 60 s로 둔다 (유휴 타임아웃이므로 핑이 오는 한 울리지 않는 뒷받침), Android는 스트림 호출에만 `callTimeout(0)`을 준다. 침묵은 상태 기계가 잰다.
- 어댑터는 요청 전송과 같은 강화를 쓴다: 쿠키 끔, 캐시 끔, 리다이렉트 안 따름 (3xx는 `serverError(3xx)`로 재시도, §4), OkHttp `retryOnConnectionFailure(false)`. 요청 헤더에 `Accept: text/event-stream`, `Cache-Control: no-cache`.
- 설명 문자열(`description`)은 URL을 싣지 않는다 (`SPFNTransportRequest`가 이미 그렇다).

## 4. 케이스 표

두 표다. **E-표**는 연결의 순수 상태 기계 `(state, conditions, input) -> (state, [effect])`이고, **L-표**는 청취자 허브(청취자 목록과 청취자별 큐)다. 둘 다 툴킷이 없고 JVM·Linux에서 단위 테스트로 돈다.

상태: **I** `idle`, **T** `connecting` 중 토큰 호출, **S** `connecting` 중 스트림 여는 중 (헤더 전), **O** `open`, **R** `retrying` (타이머 대기), **N** `offline`, **X** `closed`. 괄호 안은 `attempt` n과 epoch e.

조건: **F** 앞쪽, **A** 로그인한 클라이언트 id (`nil`이면 로그아웃), **Net** 네트워크. **W** = F ∧ A ≠ nil ("연결할 조건"). 입력은 `ui` 모듈(또는 `ui` 없는 앱)이 넘기는 `setForeground`, `setSignedIn`, `setNetworkAvailable`이다. 생성 직후 F = false, A = nil, Net = true, 상태 I(`signedOut`).

**R/N 규칙:** 아래 표에서 다음 상태가 R(m, …)인 셀은, 그 순간 Net = false이면 N(m)으로 간다 (타이머 없음). 셀마다 되풀이하지 않는다.

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

### 4-1. E-표 (연결)

id는 제안 0의 것을 지킨다. 뜻이 바뀐 셀은 "바뀜" 칸에 옛 입력을 적었다. 새 셀은 E-48부터다.

| id | 상태 | 입력 | 다음 상태 | 효과와 비고 | 바뀜 |
|---|---|---|---|---|---|
| E-1 | I | 입력이 W를 참으로 만든다 (`setForeground(true)`에 A 있음, 또는 `setSignedIn(id)`에 F 참), Net 참 | T(1) | 토큰 호출 | 옛 `start()` |
| E-3 | T | 토큰 200 `{token}` | S(n) | 스트림 GET `?token=…&events=a,b` (설정의 이름을 정렬, 쉼표로). 토큰은 이 요청에만 쓰고 버린다 | |
| E-4 | T | 토큰 `.auth(…)` (401/403 봉투) | X(`unauthorized`) | 재시도 없음. 다음 기회는 앞쪽 복귀나 다시 로그인 (E-44, E-43) | |
| E-5 | T | 토큰 `.server(…)` 403 | X(`forbidden`) | | |
| E-6 | T | 토큰 `.server(…)` 5xx, 429 | R(n+1, `serverError`) | 백오프. 429의 `Retry-After`는 읽지 않는다 (§10 Q-D) | |
| E-7 | T | 토큰 `.transport(connectivity/timedOut)` | R(n+1, `network`) | Net이 거짓이면 R/N 규칙으로 N | |
| E-8 | T | 토큰 `.decoding(notAnErrorEnvelope, onSuccessStatus: false)` | R(n+1, `unreadable`) | 프레임워크 토큰 경로의 401 `{"error":…}`, 프록시의 HTML 502가 여기로 온다. 둘을 가를 수 없으므로 백오프로 둔다. 상한이 30 s라 폭주하지 않는다 (§10 Q-C) | |
| E-9 | T | 토큰 2xx인데 본문을 못 읽음 | R(n+1, `unreadable`) | P42: 서버는 토큰을 발급했을 수 있다. 쓰지 않은 토큰은 30 s 뒤 사라지므로 버려도 해가 없다 | |
| E-10 | T | `setSignedIn(nil)` (로그아웃) | I(`signedOut`) | 토큰 호출 취소 | 옛 `stop()` |
| E-11 | T | `setForeground(false)` | I(`background`) | 토큰 호출 취소 | 옛 `suspend()` |
| E-12 | S | 200, `content-type: text/event-stream` | S | 헤더 받음. 침묵 감시 시작. 아직 `open`이 아니다 | |
| E-13 | S | `connected` 프레임 | O(e+1) | epoch를 올린다. **붙어 있는 청취자마다 큐를 비우고 `reread(.opened(e+1))`.** 안정 타이머(30 s) 시작 | 옛: 화면이 상태의 epoch를 봄 |
| E-14 | S | 200인데 `text/event-stream`이 아님 | R(n+1, `unreadable`) | 앞에 선 프록시가 HTML을 준 경우 | |
| E-15 | S | 401 | T(n) 한 번, 두 번째는 R(n+1, `tokenRejected`) | E-3의 토큰이 만료·소모됨. 즉시 새 토큰 | |
| E-16 | S | 400 `invalidEvents` | X(`unknownEvents(이름들)`) | **설정 목록**에 서버가 모르는 이름이 있다 (앱이 서버보다 새 버전). 재시도해도 같다. 목록의 다른 이름을 듣는 화면도 프레임을 받지 못한다 (§10 Q-F) | 옛: 구독한 이름 |
| E-17 | S | 400 그 밖 | X(`unknownEvents([])`) | `events`가 비었다: 설정이 빈 목록을 거부하므로 일어날 수 없다. 디버그 빌드에서 멈춘다 | |
| E-18 | S | 403 | X(`forbidden`) | `authorize`가 빈 목록 | |
| E-19 | S | 5xx, 3xx | R(n+1, `serverError(status)`) | | |
| E-20 | S | 전송 오류 | R(n+1, `network`) | | |
| E-21 | S | 침묵 감시 만료 (헤더 뒤 `connected` 없음) | R(n+1, `silence`) | 버퍼링하는 프록시. §5 U-5 | |
| E-22 | O | 설정된 이름의 프레임, 그 이름의 청취자 1개 이상 | O(e) | 봉투를 벗기고 청취자마다 그 청취자의 디코더로 디코드해 큐에 넣는다. 감시 재시작 | 옛: 구독한 이름 |
| E-23 | O | 설정된 이름인데 청취자 없음, 또는 설정에 없는 이름 | O(e) | 버린다. 감시 재시작. 설정에 없는 이름은 서버가 보내지 않아야 하므로 진단 수 `unexpectedFrames` +1 | 옛: 구독하지 않은 이름 |
| E-24 | O | 청취자 하나의 디코더가 던짐 | O(e) | 그 청취자에게만 그 프레임을 버림, `droppedFrames` +1. 다른 청취자와 연결은 그대로 | 옛: 구독의 디코더 |
| E-25 | O | `ping` 프레임 | O(e) | 청취자에게 넘기지 않는다. 감시 재시작 | |
| E-26 | O | 주석 줄(`:`로 시작), 빈 줄, 모르는 필드 | O(e) | SSE 규칙대로 무시. 바이트가 왔으므로 감시 재시작 | |
| E-27 | O | 침묵 감시 만료 | R(1 또는 n+1, `silence`) | 스트림을 닫는다. 안정 연결이었으면 `attempt` 1 | |
| E-28 | O | 스트림이 오류 없이 끝남 | R(1 또는 n+1, `serverClosed`) | 서버 재배포, **백프레셔 닫힘** (큐 1000 초과), 파드 종료가 모두 이 모양이다. 셋을 가르지 않는다 | |
| E-29 | O | 스트림 전송 오류 | R(1 또는 n+1, `network`) | 기지국 전환, Wi-Fi ↔ 셀룰러 | |
| E-33 | R | 타이머 만료 | T(n) | | |
| E-34 | R | `setNetworkAvailable(false)` | N(n) | 타이머를 끈다. 네트워크가 없는데 시도해 봐야 `network`로 실패한다 | 옛: R에서 타이머만 멈춤 |
| E-35 | N | `setNetworkAvailable(true)` | T(n) | 곧바로. 기다리던 지연은 이미 버렸다 | 옛: R에서 |
| E-36 | O | `setNetworkAvailable(false)` | O(e) | 아무것도 하지 않는다. 경로 감시기의 `false`는 흔히 거짓이고, 정말 끊겼으면 E-29나 E-27이 온다 | |
| E-37 | S, O, R, N | `setForeground(false)` | I(`background`) | 스트림 닫음, 타이머 끔 | 옛 `suspend()` |
| E-38 | I(`background`) | `setForeground(true)` (A 있음) | T(1), Net 거짓이면 N(1) | 백오프 없이. 다음 `connected`에서 새 epoch → 청취자는 뒤에 가 있던 동안을 다시 읽는다 | 옛 `resume()` |
| E-39 | I, X | 프레임, 타이머, 토큰·전송 결과 (늦게 도착) | 그대로 | 버린다. 모든 비동기 결과는 발급 번호를 달고 오고, 현재 번호가 아니면 버린다 | 옛: P에서 |
| E-40 | S, O, R, N, X | `setSignedIn(nil)` (로그아웃) | I(`signedOut`) | 스트림 닫음, 타이머 끔. **SDK가 한다.** 열린 스트림은 토큰을 받은 주체의 프레임을 계속 받으므로 로그아웃과 함께 닫혀야 한다 (§8 H-4) | 옛: 앱의 `stop()` 의무 |
| E-41 | O | 키 회전 (`SPFNKeyLifecycle.rotate()`) | O(e) | 아무 일도 없다. 클라이언트 id가 그대로라 `setSignedIn`이 오지 않는다. 스트림은 열린 순간의 주체에 묶이고, 키에 묶이지 않는다. 다음 토큰 호출은 새 키로 서명된다 | |
| E-42 | T | 세션 폐기 (`noteSessionRevoked`) 뒤 토큰 호출 | E-4 또는 T 성공 | `execute`의 재핸드셰이크가 처리한다. `noteSessionRevoked`는 `wipe()`이므로 곧이어 `setSignedIn(nil)`이 와서 E-10이 된다 | |
| E-43 | T, S, O, R, N, X | `setSignedIn(다른 id)` | T(1), Net 거짓이면 N(1) | 스트림 닫음, 타이머 끔, 토큰 호출 취소. 새 계정의 토큰으로 연다. 다음 `connected`에서 새 epoch | 옛: 앱이 `stop()` 후 새 객체 |
| E-44 | X | `setForeground(false)` | I(`background`) | `closed`에서 벗어나는 길. 앞쪽으로 돌아오면 E-38로 다시 시도한다. 사람이 앱을 오가는 빈도로만 재시도하므로 폭주하지 않는다. 서버를 고쳤거나 다시 배포했으면 여기서 회복한다 | 옛: X에서 `start()` |
| E-45 | X | `setNetworkAvailable`, 같은 값의 `setForeground(true)`·`setSignedIn(같은 id)` | X | 끝난 스트림은 네트워크나 되풀이된 입력으로 되살아나지 않는다 | 옛: `resume`/`suspend`/`networkChanged` |
| E-46 | O | 같은 값의 입력 (`setForeground(true)`, `setSignedIn(같은 id)`) | O(e) | 멱등 | 옛: O에서 `start()` |
| E-47 | O | 30 s 경과 (안정 타이머) | O(e) | `attempt`를 1로. 상태 값은 바뀌지 않는다 | |
| E-48 | I | 입력이 W를 참으로 만든다, Net 거짓 | N(1) | 아무것도 보내지 않는다 | 새 |
| E-49 | I(`signedOut`) | `setForeground(_)`, `setNetworkAvailable(_)` | I(`signedOut`) | 조건만 기록한다. 로그인하면 E-1 또는 E-48 | 새 |
| E-50 | I(`background`) | `setSignedIn(nil)` | I(`signedOut`) | 이유만 바뀐다. `setSignedIn(다른 id)`는 I(`background`) 그대로 (앞쪽으로 오면 새 계정으로 E-38) | 새 |
| E-51 | T, S | `setNetworkAvailable(false)` | 그대로 | 진행 중인 호출은 스스로 끝난다. 실패하면 R/N 규칙으로 N | 새 |

없앤 셀:

| id | 제안 0의 셀 | 없앤 까닭 |
|---|---|---|
| E-2 | I, 구독 없이 `start()` → X(`noSubscriptions`) | 이벤트 목록은 설정에서 오고 빈 목록은 생성 시각에 거부된다 (§3-1). 연결은 청취자 수와 무관하다. 대신 `configuration_rejectsEmptyEvents` 테스트 (§9-1) |
| E-30 | O, 전달 버퍼 넘침 → O(e+1) | 버퍼가 청취자마다로 옮겼다. 넘침은 그 청취자에게만 `reread(.overflow)`를 주고 epoch를 올리지 않는다 → L-4 |
| E-31 | O, 구독 합집합이 바뀜 → T(1) | 이벤트 집합은 고정이다. 화면이 붙어도 집합이 바뀌지 않는다 → L-1 (재연결 없음) |
| E-32 | O, 마지막 구독 취소 → X(`noSubscriptions`) | 청취자가 0이어도 연결은 연다. 이동 중 잠깐 0이 되는 순간마다 끊으면 이동이 재연결이 된다 → L-2 |

### 4-2. L-표 (청취자 허브)

허브는 상태 기계의 효과 `deliver(name, value)`와 `reread(epoch)`를 받고, 청취자의 붙기·떨어지기를 받는다. **허브의 어떤 입력도 상태 기계에 입력을 만들지 않는다** — 그것이 "이동은 재연결하지 않는다"의 증명이다 (§9-1 `listeners_neverReachMachine`).

| id | 연결 상태 | 입력 | 결과 | 비고 |
|---|---|---|---|---|
| L-1 | 아무 상태 | 설정된 이름으로 청취자가 붙는다 | 청취자 추가, 그 청취자에게 `reread(.attached)`. 연결 상태 그대로 | 토큰 호출도 재연결도 없다 |
| L-2 | 아무 상태 | 청취자가 떨어진다 (태스크 취소, 수집 취소) | 청취자와 그 큐 제거. 연결 상태 그대로 | 마지막 청취자여도 연결은 열려 있다. 그 뒤 프레임은 E-23 |
| L-3 | 아무 상태 | 설정에 없는 이름으로 `listen` | 부른 자리에서 거부: Swift `preconditionFailure`, Kotlin `IllegalArgumentException` | 청취자가 생기지 않는다. 연결 상태 그대로 |
| L-4 | O | 한 청취자의 큐가 `deliveryBuffer`를 넘는다 | 그 큐를 비우고 `reread(.overflow)` 하나 | 다른 청취자, epoch, 연결 그대로 |
| L-5 | T, S, R, N, I, X | 청취자가 붙는다 | `reread(.attached)`만. 프레임 없음 | 연결이 열리면 E-13으로 `reread(.opened(e))` |
| L-6 | O | 화면 A가 떠나고 화면 B가 붙는다 (push, pop, 탭 전환) | L-2 다음 L-1. 연결 상태 그대로 | **이동은 재연결하지 않는다.** B는 `attached`로 한 번 읽는다 |
| L-7 | O | 같은 이름에 청취자 둘 | 두 큐 모두에 같은 프레임 | 디코더는 청취자마다 (E-24) |
| L-8 | 아무 상태 | 루트 부착 없이 `onSPFNEvent`·`SpfnEventEffect` | 프로그래머 오류 (Swift `preconditionFailure`, Kotlin `IllegalStateException`) | 모델 수준 `listen`에는 해당하지 않는다 (객체를 직접 받는다) |

셀 수: 제안 0은 47 (E-1–E-47). 개정 1은 **E 47개** (옛 43 유지·수정 + 새 4, 없앤 4) + **L 8개** = **55**. 모두 순수 단위 테스트다 (§9-1). 실제 네트워크와 기기 수명이 필요한 것은 §5의 U-셀이다.

**다시 읽기 규칙이 있는 자리.** L-1·L-5의 `attached`, E-13의 `opened(e)`, L-4의 `overflow`. 화면 모델은 어느 것이든 받으면 한 번 읽는다. 첫 열림(epoch 1)도 포함한다: 화면의 첫 읽기와 스트림의 열림 사이에 커밋된 변화는 어느 쪽에서도 오지 않기 때문이다. 대가는 붙자마자 열리는 경우 읽기가 한 번 더 는 것이고, 모으기(§3-4)가 둘을 겹치면 하나로 줄인다.

## 5. 기기에서 모을 증거 (운영자가 돌린다)

기기: iPhone (iOS 26) 하나와 iOS 17 시뮬레이터, iPad (다중 창) 하나, Android 실기기 (API 34 이상) 하나와 에뮬레이터. 서버는 로컬 API 서버(루프백 http, iOS 시뮬레이터와 `adb reverse`로 연결한 Android)와 TLS가 있는 배포 환경 하나.

| id | 플랫폼 | 시험 | 볼 것 | 틀리면 |
|---|---|---|---|---|
| U-1 | 둘 다 | 서명된 `POST /events/token` | 200과 64 hex 토큰. 401이면 `authenticate`가 서명 요청의 주체를 `auth.userId`에 넣지 않는 것이다 | 서버 설정 (`getSubject`). SDK는 바뀌지 않는다 |
| U-2 | 둘 다 | 연결 → 서버에서 이벤트 한 번 | 커밋에서 `frame` 전달까지 1 s 안쪽. 첫 `connected`가 곧바로 오는가 | iOS: `URLSession`이 MIME 추측을 위해 첫 512바이트를 모으는지 본다. `connected`(약 80바이트)가 핑 여러 개와 함께 늦게 오면 이것이다 |
| U-3 | iOS | 앱을 뒤로 보냄 → 30 s, 3 min, 10 min 뒤 복귀 | `scenePhase == .background`에서 `idle(background)` → 복귀 즉시 `connecting` → `open(e+1)` → 붙어 있던 화면이 한 번 읽는다. 뒤로 간 동안 소켓이 남아 있었는가 (서버 로그의 "SSE dead connection cleaned up" 시각) | iOS가 소켓을 곧바로 끊지 않으면 서버 쪽 연결이 핑 쓰기가 실패할 때까지 남는다. 해는 없지만 서버 연결 수를 잰다 |
| U-4 | Android | 화면 끔 → Doze 강제 (`adb shell dumpsys deviceidle force-idle`) → 해제 | 화면을 끄면 `ProcessLifecycleOwner`가 `ON_STOP`을 내고 `idle(background)`가 되는가 (700 ms 지연 뒤) | 오지 않으면 Doze 중 소켓은 살아도 핑이 늦어 E-27이 난다. 그것도 회복 경로다 |
| U-5 | 둘 다 | 배포 환경의 로드밸런서와 프록시 뒤에서 10분 유휴 | 10 s 핑이 유휴 절단을 막는가. 끊긴다면 몇 초 주기인가 (`retrying(silence)` 또는 `serverClosed` 횟수) | 서버 `pingInterval`과 SDK `pingIntervalMillis`를 같이 낮춘다 |
| U-6 | 둘 다 | 비행기 모드 켬 → 1 min → 끔 | 켜는 순간 경로 감시기가 `false`를 넘기고, 끊긴 스트림이 `offline`으로 가는가 (타이머 없음). 끄는 순간 `connecting` → `open(e+1)` | `offline`으로 가지 않고 `retrying`이 1·2·4…로 늘면 감시기가 입력을 넘기지 않는 것이다 |
| U-7 | 둘 다 | Wi-Fi → 셀룰러 전환 (Wi-Fi 끔) | 열린 스트림이 오류로 끝나는가 (E-29), 조용히 멈추는가 (E-27까지 25 s). 경로 감시기가 잠깐 `false`를 내도 열린 연결은 그대로인가 (E-36) | 조용히 멈추면 25 s가 사람에게 긴지 본다 |
| U-8 | 둘 다 | 서버 재시작 (로컬) | E-28 → 약 1 s 뒤 재연결 → `open(e+1)` → 화면이 한 번 읽는다 | — |
| U-9 | 둘 다 | 느린 소비자 흉내: 디버거로 한 화면의 수집 코루틴·태스크를 멈추고 서버가 이벤트 1 100개 방출 | 서버가 닫고 (E-28), 재연결 뒤 새 epoch. 앱 쪽에서 멈춘 청취자만 `overflow`를 받는가 (L-4) | — |
| U-10 | 둘 다 | 기기 로그와 프록시 로그 전체에서 토큰 검색 | 토큰 문자열, `token=`가 든 URL이 어디에도 없다 (§6) | 결함. 출시 막음 |
| U-11 | 둘 다 | 홈 → 상세 push → pop → 다른 탭 → 돌아옴, 각 10회 | 서버의 토큰 발급 수와 연결 수가 처음 1에서 늘지 않는다. 화면마다 `attached`로 한 번 읽는다 | 결함 (L-6). 청취자가 상태 기계에 닿은 것이다 |
| U-12 | 둘 다 | 연결이 열린 채 로그아웃 (`wipe()`) | 1 s 안에 서버 로그에 연결 정리가 찍히고 상태가 `idle(signedOut)`. 다른 계정으로 로그인하면 새 토큰 → 새 epoch | 결함 (E-40, E-43). 출시 막음 |
| U-13 | iOS | 제어 센터·알림 센터를 내림, 앱 전환기를 열었다 닫음 | `scenePhase`가 `.inactive`만 오가고 연결이 그대로다 (§8 H-6). iPad에서 창 둘 중 하나를 뒤로 보내도 그대로다 | `.inactive`를 뒤쪽으로 읽은 것이다 |
| U-14 | Android | 연결이 열린 채 화면 회전, 다크 모드 전환 (액티비티 재생성) | 연결이 그대로다 (`ProcessLifecycleOwner`의 지연 `ON_STOP`). 컴포지션이 다시 만들어지므로 청취자는 L-2·L-1을 지나 한 번 읽는다 | 액티비티 수명주기를 본 것이다 (§8 H-7) |

## 6. 보안

| 규칙 | 어떻게 |
|---|---|
| 토큰은 기록되지 않는다 | 토큰 응답 타입(`SPFNEventStreamToken`)의 `description`/`toString()`은 `SPFNEventStreamToken(redacted)`. 토큰은 스트림 요청 하나를 만드는 동안만 지역 변수로 있고 필드에 저장되지 않는다 |
| 토큰을 실은 URL은 기록되지 않는다 | 스트림 요청은 `SPFNTransportRequest`로 만든다. 그 `description`은 이미 URL을 싣지 않는다 ("a nonce can live in a query parameter"). 어댑터의 오류 문자열은 `URLError` 코드 숫자와 OkHttp 예외 타입 이름만 싣는다. `localizedDescription`은 URL을 담을 수 있으므로 쓰지 않는다. 상태의 `reason`에도 URL이 없다 |
| 한 번 쓰고, 짧게 산다 | 서버의 사실 (30 s, `GETDEL`). SDK는 재연결마다 새로 받고, 받은 토큰을 다시 쓰지 않는다 (E-15의 재시도도 새 토큰) |
| 전송은 TLS | 스트림 URL은 `session.baseURL`에서 온다. 세션이 만들어질 때 https 또는 루프백 http만 받는다 (D21, `SPFNSession.isTrusted`). 에뮬레이터의 `10.0.2.2`는 거부된다 — `adb reverse`로 루프백을 쓴다. 새 예외는 없다 |
| 쿠키 없음, 리다이렉트 없음 | §3-9. 리다이렉트를 따르면 토큰을 실은 URL이 다른 호스트로 갈 수 있다 |
| 페이로드는 기록되지 않는다 | 디코드 실패는 수만 센다 (E-24). 이벤트 이름은 기록해도 된다 |
| 주체 분리 | 프레임을 누구에게 줄지는 서버의 `filter`가 정한다. SDK는 받은 것을 믿지 않고 화면이 다시 읽는다 (신호일 뿐). **로그아웃과 계정 바뀜은 SDK가 본다** (E-40, E-43): 라이프사이클의 로그인 값이 바뀌면 열린 스트림을 닫는다. 클라이언트 id는 증명 입력이지 비밀이 아니므로 입력으로 넘겨도 된다 (`SPFNStoredKey`의 주석). 로그아웃을 `wipe()` 없이 하는 앱에는 닿지 않는다 (§8 H-4) |

## 7. 범위

| 포함 | 제외 |
|---|---|
| 클라이언트 모듈: `SPFNEventStream`/`SpfnEventStream`, 설정, 조건 입력 셋, `listen`과 신호 타입, 상태, 백오프 값 타입. 순수 상태 기계, 청취자 허브, SSE 줄 해석기. 스트림 전송 경계와 두 어댑터. `SPFNKeyLifecycle`/`SpfnKeyLifecycle`의 로그인 값. `ui` 모듈: 루트 부착(`spfnEventStream`, `SpfnEventStreamHost`), 화면 청취(`onSPFNEvent`, `SpfnEventEffect`), 세 관찰자, 환경 키. 모듈 그래프 간선과 노트, 카탈로그 한 줄, Android 매니페스트 권한. 단위 테스트와 가짜 전송. architecture README에 절 하나, 등록부 갱신 | **APNs/FCM 푸시.** 앱이 뒤에 있거나 꺼져 있을 때 알리는 것은 다른 문제다 (서버의 발송 경로, 기기 토큰 등록, 사용자 동의). 이번에는 앞쪽에서만 |
| | **오프라인 큐.** 스트림은 받기만 한다. 보낼 것이 없다 |
| | **WebSocket.** 앱이 서버로 보낼 실시간 메시지가 생기면 따로 설계한다 |
| | 백그라운드 연결 유지 (iOS `beginBackgroundTask`, Android 포그라운드 서비스) |
| | 놓친 프레임의 재전송. 서버가 `Last-Event-ID`를 읽지 않고, 설계상 필요도 없다 (다시 읽기 규칙) |
| | **화면별 이벤트 집합, 실행 중 목록 바꾸기.** 목록은 설정에서 고정이다 (개정 1의 결정). 뒤쪽 탭만 쓰는 이름도 연결에 실린다 |
| | 한 앱에 스트림 둘 (서로 다른 서버, 서로 다른 경로). 한 트리에 붙일 수는 있지만 환경은 안쪽 것만 보인다 (§3-3) |
| | 이벤트 이름과 페이로드의 코드 생성. 앱 계약 문서는 이벤트를 담지 않는다. 디코더는 앱이 손으로 쓴다 (페이로드는 신호라 작다) |
| | 특정 앱 전용 코드. 예제는 일반적인 "세션 목록" 홈으로만 쓴다 |

## 8. 함정

### 8-1. 등록부 후보 (구현에서 실제로 나오면 등록한다)

| id | 후보 | 탐지 |
|---|---|---|
| H-1 | **브라우저 식 자동 재시도는 죽은 토큰으로 다시 연결한다.** 한 번 쓰는 토큰이 URL에 있으므로, 전송이나 라이브러리가 같은 URL로 재시도하면 401만 돌아온다. 재연결은 언제나 토큰 호출부터 | OkHttp `retryOnConnectionFailure(false)`. 어댑터가 재시도하지 않는다는 테스트: 가짜 서버가 첫 요청을 끊으면 요청 수가 1 |
| H-2 | **선로 위의 이름은 라우터 키다.** `defineEvent('session.activity', …)`를 `defineEventRouter({ sessionActivity })`에 넣으면 `events=` 쿼리도, `event:` 필드도, 봉투의 `event`도 `sessionActivity`다. 설정 목록에 점 찍힌 이름을 쓰면 400 `invalidEvents`로 스트림 전체가 닫힌다 (E-16) | `closed(unknownEvents)`를 앱이 기록하게 한다. 400 본문의 `validEvents`가 정답 목록이다 |
| H-3 | **엄격한 파서는 소수를 거부한다.** `SPFNCanonicalJSON.parse`는 정수만 받는다. 페이로드에 소수가 있으면 그 프레임은 디코드 실패로 버려진다 (E-24). 지금 서버의 페이로드는 문자열·정수·불리언뿐이다 | 서버 이벤트 스키마에 `Type.Number()` 소수가 들어오면 앱 쪽 디코드 실패 수가 오른다. 서버 쪽 리뷰에서 막는다 |
| H-4 | **`wipe()`를 지나지 않는 로그아웃은 스트림을 닫지 않는다.** 스트림의 주체는 열린 순간에 정해지고 서버는 다시 묻지 않는다. SDK는 라이프사이클의 로그인 값으로 로그아웃을 안다 (E-40). 앱이 자기 상태만 지우고 키를 남기는 "로그아웃"을 만들면 SDK에게는 로그인 상태이고, 다음 사람이 이전 사람의 신호를 받는다 (신호일 뿐이고 읽기는 거부되지만, 어느 세션이 움직였는지는 샌다) | 예제 앱의 로그아웃 경로가 `wipe()`를 부르고 스트림이 `idle(signedOut)`이 된다는 테스트. 앱에도 같은 테스트를 권한다 |
| H-5 | **읽기 타임아웃으로 침묵을 재면 두 플랫폼이 다르게 운다.** URLSession `timeoutInterval`은 유휴 기한이고 OkHttp `callTimeout`은 호출 전체 기한이다 (`SPFNURLSessionTransport`의 주석). 전체 기한이 붙은 스트림은 건강해도 그 시각에 끊긴다 | 침묵 감시는 상태 기계 하나. 스트림 어댑터의 `callTimeout`은 0이다는 테스트 |
| H-6 | **`scenePhase == .inactive`는 뒤쪽이 아니다.** 제어 센터를 내리거나 앱 전환기를 여는 순간, 전화가 오는 순간 `.inactive`가 된다. 이것을 뒤쪽으로 읽으면 스와이프 한 번마다 토큰 호출과 재연결이 일어나고, 화면은 그때마다 다시 읽는다 | `.background`만 뒤쪽. U-13 |
| H-7 | **액티비티의 수명주기는 앱의 앞쪽/뒤쪽이 아니다.** 회전, 다크 모드, 언어 바꿈이 액티비티를 다시 만들며 `ON_STOP`→`ON_START`를 낸다. 루트 컴포저블의 `LocalLifecycleOwner`를 보면 회전마다 재연결한다 | `ProcessLifecycleOwner`(설정 변경에 700 ms 지연을 둔다). U-14 |
| H-8 | **`ViewModel`에서 수집한 청취자는 화면보다 오래 산다.** `viewModelScope`는 화면이 뒤 스택에 있어도 살아 있으므로 "화면이 떠나면 듣기가 끝난다"가 깨지고, 보이지 않는 화면이 매 신호마다 읽는다. 연결은 그대로라 틀린 답은 아니지만 읽기가 는다 | 예제는 `SpfnEventEffect`나 `LaunchedEffect` 안에서 `listen`을 수집한다. Swift는 `.task` 안에서 |
| H-9 | **Swift `listen`은 부른 순간 붙는다.** `AsyncStream`을 만들어 두고 소비하지 않으면 청취자가 붙은 채 남고 큐가 차서 `overflow`를 되풀이한다. 또 `AsyncStream`은 소비자 하나만 받는다 | `listen`은 `for await` 바로 앞에서 부른다. 문서 주석과 예제가 그 모양이다 |
| H-10 | **설정에 없는 이름은 앱을 멈춘다 (L-3).** 서버에 새 이벤트를 더하고 앱의 화면에만 이름을 쓰고 설정 목록에 더하지 않으면 첫 실행에서 멈춘다. 의도한 것이다: 조용히 아무것도 오지 않는 것보다 낫다 | 이름을 문자열 상수 하나로 두고 설정과 화면이 같은 상수를 쓰게 권한다 |

### 8-2. 기존 항목

| 항목 | 이 설계에서 |
|---|---|
| P40 (수면 중 멈추는 단조 시계) | 침묵 감시와 백오프 타이머는 경과 시간을 잰다. 기기가 잠들면 앱은 이미 `idle(background)`라 타이머가 없다. 앞쪽에 둔 채 잠드는 경우(U-4)에도 멈추는 시계는 감시를 늦출 뿐 잘못 울리지 않는다. 그래도 타이머의 원천은 P40의 "수면을 포함하는" 쪽을 쓴다 |
| P42 (2xx인데 읽을 수 없는 응답) | 토큰 호출의 2xx 읽기 실패는 E-9. 서버가 발급했을 수 있지만 쓰지 않은 토큰은 스스로 사라지므로 재시도가 안전하다 |
| 전송 계층 주석 "URL은 기록하지 않는다" | 스트림 어댑터도 같은 규칙 (§6) |

갱신할 다른 문서: architecture README에 "The event stream" 절 (세 계층 표 옆에: 토큰은 execute를 지나고, 스트림은 전송 경계 하나를 더 쓰고, 수명은 `ui` 모듈이 넘긴다), "The `ui` module" 절과 `tools/module-graph.json`의 노트 (클라이언트 간선, §3-7), 모듈 표의 `SPFNClient` 외부 의존성은 그대로(새 의존성 없음), `spfn-ui` 외부 의존성에 `lifecycle-process` 한 줄.

## 9. 시험 계획

### 9-1. 단위 (툴킷 없음, Linux와 JVM)

`SPFNEventStreamMachineTests.swift` / `SpfnEventStreamMachineTest.kt`. 상태 기계는 `(state, conditions, input) -> (state, [effect])` 순수 함수다. 효과는 `mintToken`, `cancelToken`, `openStream(names)`, `closeStream`, `startSilenceTimer(ms)`, `startRetryTimer(ms)`, `startStableTimer(ms)`, `cancelTimers`, `deliver(name, value)`, `reread(epoch)`, `publish(state)`. 시계도 난수도 주입한다 (지터는 `U[0.5, 1.0]`를 내는 함수를 받는다). 테스트 이름은 셀 이름을 따른다: `e1_signedInForeground_mintsToken`, `e15_stream401_retriesTokenOnce_thenBacksOff`, `e40_signOut_closesOpenStream`, `e43_accountSwitch_reconnectsWithNewToken`, `e44_closed_leavesOnlyThroughBackground` … E-셀 47개 전부.

`SPFNEventListenerHubTests.swift` / `SpfnEventListenerHubTest.kt`: L-셀 8개 (L-8은 `ui` 모듈 쪽, 아래). `l1_attach_sendsAttachedReread_noMachineInput`, `l3_unknownName_isRefused` (Kotlin은 `assertThrows`; Swift의 `preconditionFailure`는 프로세스를 멈추므로 검사를 `SPFNEventStreamConfiguration.contains(_:)` 순수 함수로 떼어 그 함수를 시험하고, 멈춤 자체는 시험하지 않는다), `l4_overflow_replacesQueueWithOneReread`, `l6_navigation_doesNotReconnect`.

추가로:

| 테스트 | 내용 |
|---|---|
| `backoff_sequence` | 지터 1.0에서 1, 2, 4, 8, 16, 30, 30 s. 지터 0.5에서 0.5, 1, 2 … |
| `staleResult_ignored` | 발급 번호가 지난 토큰 결과, 스트림 결과, 타이머는 상태를 바꾸지 않는다 (E-39) |
| `epoch_monotonic` | 어떤 입력 순서에서도 epoch는 줄지 않는다 (무작위 입력 1 000개, 고정 시드) |
| `closed_leavesOnlyThroughCondition` | X에서 F가 거짓이 되거나 A가 바뀌는 것 말고는 아무것도 상태를 바꾸지 않는다 |
| `connection_iffForegroundAndSignedIn` | 무작위 입력 1 000개 뒤 매 순간: 상태가 T·S·O·R·N이면 F ∧ A ≠ nil, I면 그 반대 (고정 시드) |
| `listeners_neverReachMachine` | 무작위 붙기·떨어지기 1 000개가 상태 기계에 입력을 하나도 만들지 않는다 |
| `tokenPath_derived` | `/events/stream` → `/events/token`, `/sse` → `/token`, `/a/b/c` → `/a/b/token`, 쿼리 거부 |
| `configuration_rejectsEmptyEvents` | 빈 목록, 빈 이름, 쉼표 든 이름 거부. 중복은 하나로. 쿼리 순서는 정렬 (없앤 E-2의 자리) |
| `foregroundTally` | (Swift, `SPFNUI`의 툴킷 없는 파일) 장면 둘: 하나 `.background`, 하나 `.inactive` → 앞쪽. 둘 다 `.background` → 뒤쪽. 장면이 떠나면 센 것에서 빠진다 |
| `keyLifecycle_signedInClientID` | `enroll` 뒤 id, `rotate` 뒤 같은 id(값을 내지 않는다), `wipe` 뒤 `nil`, `noteSessionRevoked` 뒤 `nil` |

`SPFNSSELineParserTests` / `SpfnSseLineParserTest`: 조각 경계가 줄 가운데, `\r\n`, `\r`, 여러 `data:` 줄 이어 붙이기, 주석, 필드 이름만 있는 줄, 빈 `event`(기본 `message`), UTF-8 멀티바이트가 조각 경계에서 잘림, 끝에 빈 줄 없이 스트림이 끝남(마지막 이벤트는 버린다 — SSE 규칙). 입력은 서버의 실제 모양을 고정한 픽스처 (`connected`, 이벤트, `ping`).

두 플랫폼의 테스트 이름이 같아야 한다는 것을 validate가 확인한다 (전송 테스트가 이미 그렇게 짝지어져 있다).

### 9-2. 가짜 전송

`SPFNFakeStreamTransport` / `SpfnFakeStreamTransport`: 테스트가 응답 상태, 헤더, 조각을 차례로 밀어 넣고, 오류로 끝내거나 조용히 끝낸다. `SPFNEventStreamTests`는 가짜 `SPFNTransport`(토큰)와 가짜 스트림 전송으로 객체 전체를 돌린다: `setSignedIn(id)`, `setForeground(true)` → 토큰 요청이 `execute`를 지나 서명 헤더를 달았는가, 스트림 요청의 URL(설정 목록의 정렬된 `events=`)과 헤더, `connected` → `open(1)` → 붙은 `listen`이 `attached` 다음 `opened(1)`, 프레임 → `frame`, 끊김 → `retrying` → 재연결 → `opened(2)`, `setSignedIn(nil)` → 스트림 취소와 `idle(signedOut)`. 시간은 주입한 시계로 넘긴다. 실제 소켓은 없다.

### 9-3. 어댑터와 `ui` 모듈

어댑터 — iOS: 기존 `URLProtocol` 스텁으로 청크 응답을 흉내 내 델리게이트가 조각을 순서대로 넘기는지. Android: OkHttp `MockWebServer`가 이미 테스트 의존성이면 그것으로 (아니면 로컬 `ServerSocket`), `callTimeout`이 0인지, 리다이렉트를 따르지 않는지, 쿠키를 보내지 않는지.

`ui` 모듈 — 루트 부착은 얇다: 플랫폼 신호를 세 입력으로 옮길 뿐이다. 옮기는 규칙(`scenePhase` → 앞쪽, `NWPath.Status` → 있음, `Lifecycle.Event` → 앞쪽)은 이름 붙은 작은 함수로 두고 JVM 단위 테스트(`spfn-ui/src/test`, 지금의 `TabStateTest`처럼)와 macOS 테스트에서 시험한다. 관찰자의 등록·해제(`NWPathMonitor.cancel()`, `unregisterNetworkCallback`, 수명주기 관찰자 제거)가 부착이 떠날 때 일어나는지는 기기에서 본다 (U-11–U-14). L-8은 Kotlin만 JVM 테스트가 된다 (컴포지션 없이 `LocalSpfnEventStream`의 기본값이 던지는지).

### 9-4. 사람이 확인하는 것

§5의 U-셀. 예제 앱에 "이벤트" 화면 하나: 상태 readout (`stream=open(3)`), 받은 프레임 수, 버린 프레임 수, 받은 `reread` 수와 원인. 로컬 서버에서 이벤트를 방출하는 스크립트는 예제 서버가 생기면 더한다.

## 10. 검토자가 답할 질문

답한 것 (개정 1):

| id | 질문 | 답 |
|---|---|---|
| Q-A | 토큰 호출을 SDK 이벤트 모듈이 설정된 경로로 만드는 것 (§2-2 (다))을 받아들이는가 | **받아들여짐.** 상류가 이벤트 표면을 계약으로 내보내면 (경로 파생 규칙까지) 다시 본다 |
| — | 전송을 SSE로 (§2-1 (가)) | **받아들여짐** |
| — | 수명을 앱이 부르는 분담 (제안 0의 §3-4·§3-5) | **거절됨.** SDK가 연결을 소유하고 `ui` 모듈이 관찰한다, 화면은 듣기만 한다 (§2-4) |
| Q-E | 경로 감시기를 SDK가 제공할 것인가 | **제공한다, `ui` 모듈에서** (개정 1의 결정). 제안 0이 걸려 했던 두 문제는 이렇게 풀린다: `NWPathMonitor`는 `#if canImport(Network)` 파일 안이라 Linux 규칙과 부딪히지 않고, `Context`는 Compose 호스트가 `LocalContext`에서 얻는다. 대가는 Android 권한 한 줄 (§3-7) |

남은 것:

| id | 질문 | 제안 |
|---|---|---|
| Q-B | 서명 요청에 CSRF 규칙이 걸리는 서버 설정을 SDK가 우회해야 하는가 | 우회하지 않는다. 쿠키 없는 서명 요청에 CSRF를 요구하는 것은 서버 설정의 결함으로 본다 (U-1에서 확인) |
| Q-C | 토큰 경로의 봉투 아닌 401(`Unable to identify subject`)을 재시도(E-8)로 둘 것인가, 닫을 것인가 | 재시도. 프록시의 5xx HTML과 가를 수 없고, 상한 30 s가 폭주를 막는다. 상류가 이 응답을 SPFN 오류 봉투로 바꾸면 E-4로 옮긴다 |
| Q-D | 429의 `Retry-After`를 읽을 것인가 | 이번에는 읽지 않는다. 토큰 경로에 속도 제한이 걸리면 다시 본다 |
| Q-F | 설정 목록에 서버가 모르는 이름이 있으면 (E-16), 아는 이름만으로 다시 연결할 것인가 | 닫는다 (`unknownEvents`). 목록이 고정이 되면서 이 질문은 더 무거워졌다: 이름 하나가 목록의 모든 이름을 막는다. 그래도 조용히 줄이면 앱이 기다리는 신호가 안 온다는 사실이 숨는다. 화면은 `attached`와 당겨서 새로 고침으로 읽는다. 앱과 서버의 배포 순서(서버 먼저)로 막는다 |
| Q-G | 침묵 감시 2.5 × 핑 (25 s)이 사람에게 긴가 (U-7) | 25 s로 시작하고 U-5·U-7 결과로 정한다 |
| Q-H | `ui` → 클라이언트 모듈 간선 (§3-7)을 받아들이는가, 아니면 루트 부착과 화면 청취를 새 모듈(예: `SPFNEventsUI`/`spfn-ui-events`, `ui`와 클라이언트에 의존)에 둘 것인가 | 간선. 결정이 "`ui` 모듈"이라 했고, 스트림을 쓰는 앱은 이미 클라이언트 모듈을 링크한다. 렌더링만 하는 앱이 클라이언트를 링크하게 되는 대가는 노트를 고쳐 적는다. 새 모듈은 산출물이 하나 늘고 두 플랫폼의 게시·검증 목록이 한 줄씩 는다 |
| Q-I | `closed(unauthorized)`·`closed(unknownEvents)`에서 앞쪽 복귀마다 다시 시도하는 것 (E-44)이 맞는가 | 맞다. 사람이 앱을 오가는 빈도로만 시도하고, 서버 수정·재배포 뒤 앱을 다시 켜지 않아도 회복한다. 대가는 복귀마다 토큰 호출 하나 |
| Q-J | 로그인 상태를 `SPFNKeyLifecycle`에서 읽는 것 (§3-3)이 맞는가. 이 SDK에서 로그인은 "활성 키에 클라이언트 id가 있다"이다 | 맞다. 다른 원천(앱이 넘기는 `Bool`)은 계정 바뀜(E-43)을 모르고, 옛 설계의 "앱이 잊으면 샌다"를 되살린다 |
