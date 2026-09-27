# 설계 — 하단 탭 컨테이너 `TabHost`

- 상태: 승인, 구현됨 (2026-09-27). 검토자의 답은 §10에 적었다. §5의 U-1은 시뮬레이터에서 쟀고(아래), 나머지 U-셀은 구현 위에서 운영자가 기기로 잰다.
- 대상: `SPFNUI`(iOS)와 `spfn-ui`(Android)에 새로 들어갈 `TabHost`, `TabItem`, `TabState`. 기존 `NavigationHost`, `HostStack`, `FlowHost`, `Screen`의 동작 일부
- 관련: [architecture README](README.md) "The `ui` module"·"Three ways in"·"`Screen` owns the insets", [screen-header-design.md](screen-header-design.md), [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P21·P25·P29·P30·P31·P32·P33·P35·P36·P37·P39·P41, [UI-IMPLEMENTATION-GUIDE.md](../UI-IMPLEMENTATION-GUIDE.md) S1–S8·K1–K7

## 0. 요약

| 항목 | 결정 |
|---|---|
| 탭 선언 | 데이터: `TabItem(id, title, icon, selectedIcon, accessibilityLabel, root)`. 탭 수는 배열 길이일 뿐이라 2개에서 N개로 늘어도 API가 바뀌지 않는다 |
| 선택 상태 | 툴킷 없는 `TabState` (`Flow`처럼). 선택, 다시 누름, 뒤로를 판정하는 표가 여기 있다 |
| 탭별 스택 | **탭마다 `NavigationHost` 하나, 그러니 `HostStack`도 탭마다 하나.** N1(시간순 목록)은 한 탭 안에서 그대로 성립한다. 탭 사이에는 순서가 없다 |
| 탭 바 | **SDK가 그린다, 양 플랫폼 모두. 그리고 각 탭 루트 화면 안에 둔다.** push된 화면은 형제 항목이라 바를 저절로 덮는다. 바를 숨기는 코드가 없다 |
| iOS 시스템 `TabView` | 쓰지 않는다. 필요한 동작(push 때 바 숨김, pop 때 재등장, 경로를 바인딩한 스택을 탭 안에 두기)마다 iOS 17.4·18에서 보고된 결함이 있다 (§2) |
| 시트·모달 | 바를 덮는다, 양 플랫폼 모두. Android에서는 탭 안의 `FlowHost(.modal/.sheet)`가 `TabHost`에 등록되고, `TabHost`가 바 위 층에 그린다 |
| 현재 탭 다시 누름 | 깊이 > 0이면 루트까지 pop. 깊이 0이면 "맨 위로" 신호만 낸다. 스크롤은 앱이 한다 |
| Android 뒤로 | 시작 탭이 아닌 탭의 루트에서는 시작 탭으로 간다. 시작 탭 루트에서는 앱을 나간다 |

## 1. 맥락과 요구

어떤 앱의 홈 탭은 목록이고 두 번째 탭은 계정이다. 행을 누르면 상세 화면이 push되고, 상세는 탭 바를 덮는다. 나중에 탭이 늘어난다. 지금 `ui` 모듈에는 `NavigationHost`, `FlowHost`, `HostStack`, `Screen`, 시트, 모달 덮개가 있지만 탭이 없다. 이 설계는 탭을 **지금 있는 내비게이션 모델 안에** 넣는다. 그 옆에 따로 세우지 않는다.

| # | 요구 | 이 설계에서 |
|---|---|---|
| Q1 | 탭은 데이터로 선언한다: id, 제목, 아이콘, 접근성 라벨, 루트 콘텐츠 | `TabItem` (§3-1) |
| Q2 | 지금은 2개, 나중에 N개. API는 바뀌지 않는다 | `[TabItem]` / `List<TabItem>`. 배지는 나중에 기본값 있는 인자로 더한다 (§7) |
| Q3 | 탭마다 자기 내비게이션 스택을 가진다 | 탭마다 `NavigationHost` (§2-3) |
| Q4 | 현재 탭을 다시 누르면 루트까지 pop한다. 루트가 목록이면 맨 위로 스크롤한다 | pop은 SDK가 한다. 스크롤은 SDK가 신호(`TabScrollToTop`)를 내고 앱이 한다 (§3-4) |
| Q5 | push된 상세는 탭 바를 덮는다 (깊이 > 0이면 바가 없다) | 바가 루트 화면의 일부이므로 저절로 그렇다 (§2-2) |
| Q6 | 탭을 바꿔도 각 탭의 상태가 남는다 | iOS: 한 번 연 탭은 계속 살아 있다. Android: 저장소는 `TabHost`가 들고, 저장 가능한 상태는 탭 id로 보관한다 (§2-4) |
| Q7 | 모양은 테마(`SPFNTheme`)가 정한다. 헤더와 같다 | 팔레트와 타이포그래피의 기존 키만 쓴다. 새 키는 없다 (§3-6) |

## 2. 검토한 선택지

### 2-1. iOS: 시스템 `TabView` + 탭마다 `NavigationStack`, 아니면 SDK가 그린 바

| | (가) 시스템 `TabView` | (나) SDK 바를 각 탭 루트에 둔다 (**선택**) |
|---|---|---|
| 모양 | 시스템 바. iOS 26에서는 유리 효과, iPad에서는 사이드바로 바뀐다 | 테마로 그린 막대. iOS 26 유리 효과는 없다 |
| push 때 바 숨김 | 도착 화면마다 `.toolbar(.hidden, for: .tabBar)`를 붙인다 (iOS 16+, 최소 iOS 17이라 쓸 수 있다). UIKit의 `hidesBottomBarWhenPushed`에 해당하는 SwiftUI API는 이것뿐이다 | 숨길 것이 없다. 바는 루트 화면 안에 있고 상세는 루트 위에 쌓이는 형제 항목이다 |
| pop 때 재등장 | 보고된 결함: iOS 18에서 pop 뒤 바가 깜빡이고 사라진다 ([Apple 포럼 765702](https://developer.apple.com/forums/thread/765702)). iOS 17.4에서 `.toolbar(.hidden, for: .tabBar)`가 탭을 바꾸면 풀린다 ([748046](https://developer.apple.com/forums/thread/748046)) | 루트가 드러나면서 바도 같이 미끄러져 들어온다. 가장자리 스와이프 중에도 루트와 함께 보인다 |
| 경로를 바인딩한 스택 | 보고된 결함: iOS 18 `TabView` 안에서 `NavigationStack(path:)`가 한 번에 두 번 push한다. 18.1 베타에서도 고쳐지지 않았다 ([759542](https://developer.apple.com/forums/thread/759542)). `NavigationHost`가 바로 이 모양이다 | 바깥에 `TabView`가 없다. `NavigationHost`는 지금과 같은 조건에서 돈다 |
| 다시 누름 신호 | `TabView(selection:)`의 바인딩 setter가 같은 값으로 불리는지는 문서에 없다. 탭의 스택을 시스템이 스스로 pop하는지도 문서에 없다. 둘 다 재야 한다 | 바 항목의 탭 핸들러가 SDK 코드라 확실하다 |
| 접근성 | 시스템이 준다 (탭 바 특성, 선택 상태, 큰 글자에서 큰 콘텐츠 뷰어) | SDK가 붙인다 (§6). 붙일 수 있는 것은 전부 공개 API다 |
| H2 ("헤더와 뒤로가기는 플랫폼 기본을 쓴다") | 맞는다 | **어긋난다.** H2는 헤더와 뒤로가기에 관한 원칙이고 탭 바는 둘 다 아니다. 그래도 같은 정신과 부딪히므로 검토자가 판단할 질문으로 남긴다 (§10 Q-A) |

(나)를 고른 이유는 하나다. 요구 Q3·Q5가 기대는 동작은 셋이다: 탭 안에서 경로를 바인딩한 스택, push 때 바 숨김, pop 때 바 재등장. 그런데 (가)는 셋 모두에서 최소 지원 OS 범위(17–26) 안의 보고된 결함 위에 선다. screen-header-design은 시스템 바가 **깨끗하게 동작하는 것을 잰 뒤에**(§5 R1–R7) 시스템 바를 골랐다. 여기서는 잰 것이 반대 방향을 가리킨다. (가)는 기각이 아니라 §5 U-1–U-3의 대조군으로 남긴다. 세 확인이 모두 iOS 17·18·26에서 깨끗하게 나오면 검토자가 (가)로 되돌릴 수 있다.

### 2-2. 바를 어디서 숨기는가: 도착 화면마다, 스택마다, 아니면 숨기지 않는다

| 방식 | 문제 |
|---|---|
| 도착 화면마다 `.toolbar(.hidden, for: .tabBar)` | (가)의 방식. 위 결함들. 게다가 SDK가 모든 도착 화면을 감싸야 한다. `HostRegistration.screen`이 그 자리가 되었을 것이다 |
| 스택마다: 깊이 > 0이면 컨테이너가 바를 치운다 | 바가 스택 **바깥**에 있으니 바가 push 전환과 따로 움직인다. 전환 시작에 사라지고 pop이 끝난 뒤에 나타난다. 이것이 "pop 때 깜빡임"이다. 예측형 뒤로가기(Android)와 가장자리 스와이프(iOS)를 잡고 있는 동안에는 드러나는 루트에 바가 없다. 손을 떼기 전까지 깊이가 아직 1이기 때문이다 |
| **숨기지 않는다: 바는 루트 화면의 일부다** (선택) | 상세는 형제 항목이라 바가 있는 루트 **위로** 미끄러져 온다. pop은 바가 있는 루트를 드러낸다. 잡고 있는 제스처는 바가 있는 루트를 미리 보여 준다. 따로 움직이는 부분이 없으니 깜빡일 것도 없다 |

"깊이 > 0이면 바가 없다"는 규칙이 성립하는 것은 코드가 그렇게 해서가 아니라 구조 때문이다. 셀로는 여전히 확인한다 (§4 C-3, C-5).

`.toolbar(.hidden, for: .navigationBar)`와의 관계: 이 설계는 어떤 막대도 숨기지 않는다. P29의 탐지 명령 `grep -rn 'toolbar(.hidden' Sources/`는 계속 비어 있어야 한다. 탭 루트 화면의 시스템 내비게이션 바(screen-header-design)는 그대로이고, 가장자리 스와이프와 콘텐츠 스와이프는 UIKit의 것 그대로다. 탭 루트에서는 아래에 라우트가 없으므로 두 스와이프 모두 아무 일도 하지 않는다. 이것은 지금 `NavigationHost` 루트와 같다.

### 2-3. 기존 내비게이션 모델과의 합성

| 모양 | 판정 |
|---|---|
| 앱 전체에 `NavigationHost` 하나, 그 루트에 탭 전환 | 상세가 바를 저절로 덮는 것은 같다. 하지만 탭별 스택이 없다 (Q3). 다른 탭으로 가려면 스택을 통째로 바꿔야 하고, 그러면 `NavigationHost` 경로 setter의 "플랫폼은 줄이기만 한다" 규칙이 깨진다 |
| 앱 `NavigationHost` 안에 `TabHost`, 그 안에 탭별 `NavigationHost` | iOS에서는 `NavigationStack` 안에 `NavigationStack`을 넣게 되는데, SwiftUI가 지원하지 않는 중첩이다. Android에서는 `NavDisplay` 둘이 뒤로가기를 두고 다툰다. **금지한다.** `TabHost`가 앱의 최상위 내비게이션이다 |
| **`TabHost` 아래 탭마다 `NavigationHost`** (선택) | `NavigationHost`의 KDoc에 이미 "one per app — or one per independently navigating region"이라고 적혀 있다. 탭이 바로 그 region이다 |

**`FlowHost(.push)`가 어디에 등록되는가.** 가장 가까운 `NavigationHost`다. iOS에서는 `@Environment(\.hostStack)`, Android에서는 `LocalNavigationHost`다. 탭 루트 안에 있는 `FlowHost(.push)`는 그 탭의 호스트에 등록된다. 다른 탭의 호스트는 환경에 없으므로 잘못 등록될 길이 없다.

**N1(시간순)은 어떻게 되는가.** `HostStack`은 탭마다 하나이고 그 안에서는 지금과 똑같이 시간순이다. 탭 사이에는 순서가 없다. 탭 A에서 push한 뒤 탭 B로 가서 push해도 두 목록은 섞이지 않는다. 뒤로가기는 언제나 **선택된 탭의** 목록에만 간다. N1이 막으려던 결함, 곧 "호스트의 맨 위와 흐름의 맨 위가 다른 화면"은 한 목록 안에서만 생길 수 있는 일이라, 목록이 둘이 되어도 다시 생기지 않는다.

**한 흐름은 한 탭에 속한다.** 같은 `Flow` 객체의 `FlowHost(.push)`를 두 탭 루트에 두면 두 호스트가 같은 라우트를 동기화하고, 상세가 두 탭에 동시에 나타난다. `TabHost`가 탭별 owner 집합을 알고 있으므로 두 번째 등록은 디버그 빌드에서 `assertionFailure`/`check`로 멈춘다. 릴리스 빌드에서는 먼저 등록한 탭이 이긴다. 단위 테스트가 있다 (§9).

**`FlowHost(.modal)`·`FlowHost(.sheet)`가 탭 안에서 열리면.** iOS: `fullScreenCover`와 `sheet`는 창 수준 표시라 어디서 열든 탭 바를 덮는다. 따로 할 일이 없다. Android: `ModalCover`와 `Sheet`는 "호스트가 준 부모를 채운다"(architecture README "Three ways in"). 그래서 탭 루트 안에서 열면 탭 루트의 영역만 덮고 바는 그대로 눌린다. 두 가지 길이 있었다.

| 방식 | 판정 |
|---|---|
| `TabHost(presentations = { ... })` 슬롯. 앱이 모달·시트 `FlowHost`를 거기에 둔다 | 규칙을 앱에 맡긴다. 루트 안에 두면 조용히 틀린다. 게다가 탭을 바꾸면 루트가 컴포지션에서 빠지고, 열린 흐름이 그려지지 않은 채로 남는다 |
| **등록** (선택): 탭 안의 `FlowHost(.modal/.sheet)`는 `LocalTabHost`를 찾아 표시를 `TabHost`의 덮개 층에 등록한다. 그 층은 바 위에 그려진다 | `FlowHost(.push)`가 `NavigationHost`에 등록하는 N1과 같은 모양이다. 등록은 한 번 하고 흐름의 `StateFlow`를 `TabHost` 스코프의 코루틴이 따라간다. P31의 교훈 그대로다: 루트가 컴포지션에서 빠져도 등록은 풀리지 않는다. `TabHost` 밖에서는 지금과 같다 |

플랫폼 관례: iOS의 전체 화면 덮개와 시트는 탭 바를 덮는다. Material의 모달 바텀 시트도 앱의 내비게이션 위에 선다. 두 플랫폼 모두 "덮는다"로 통일한다.

### 2-4. Android: 바와 탭별 백 스택

| 항목 | 결정 |
|---|---|
| 바 | `foundation`으로 그린다. 이 저장소는 Material에 의존하지 않는다. 확인: `grep -n material android/spfn-ui/build.gradle.kts gradle/libs.versions.toml`는 아무것도 찾지 않는다. `spfn-ui`의 의존성은 compose runtime·ui·foundation, activity-compose, navigation3 runtime·ui가 전부다 |
| 탭별 스택 | 탭마다 `NavigationHost` 하나 = `NavDisplay` 하나. 각 `NavDisplay`는 지금처럼 `FlowTransitions`를 받는다 (P37) |
| 저장소의 수명 | `HostStackStore`는 지금 `NavigationHost` 안에서 `remember`된다. 탭 전환으로 탭이 컴포지션에서 빠지면 저장소도, 흐름의 등록도, 수집 코루틴도 같이 사라진다. 그래서 **저장소를 `TabHost`로 끌어올린다**: `TabHost`가 탭 id마다 저장소 하나를 자기 스코프에 `remember`하고, 내부용 `NavigationHost(store, root)` 오버로드에 넘긴다. 공개 API는 바뀌지 않는다 |
| 컴포즈하는 탭 | 선택된 탭만. 나머지는 `rememberSaveableStateHolder()`의 `SaveableStateProvider(tab.id)`가 `rememberSaveable` 상태(목록의 스크롤 위치, `NavDisplay`의 항목별 저장 상태)를 보관한다. 모든 탭을 계속 컴포즈해 두는 방식은 기각했다. 가려진 탭의 노드가 semantics 트리에 남아 러너가 보이지 않는 컨트롤을 찾게 된다 (P25의 반대 방향) |
| 예측형 뒤로가기 | 탭 안에서는 그 탭의 `NavDisplay`가 지금처럼 처리한다 (P35 매니페스트 선언 필요). 시작 탭이 아닌 탭의 루트에서는 `TabHost`의 `BackHandler`가 처리한다. 손을 뗐을 때 시작 탭으로 가고, 잡고 있는 동안의 미리보기는 없다. 미리보기가 필요하면 `PredictiveBackHandler`로 따로 설계한다 (§7) |
| `testTagsAsResourceId` | `TabHost` **바깥**에 건다. 탭 루트 안에 걸면 push된 화면과 덮개 층에서 빠진다 (P33) |

### 2-5. iOS: 탭 전환과 상태 보존

`TabHost`는 한 번 연 탭을 `ZStack` 안에 계속 둔다. 선택되지 않은 탭에는 `.opacity(0)`, `.allowsHitTesting(false)`, `.accessibilityHidden(true)`를 붙인다. 그래서 탭의 `NavigationHost`가 가진 `@State`(저장소)와 UIKit이 들고 있는 스크롤 위치가 남는다. 아직 열지 않은 탭은 만들지 않는다. 한 번도 연 적 없는 탭의 루트가 첫 화면 로드를 미리 부르면 안 되기 때문이다 (R6, D27).

## 3. 공개 API

어휘는 두 플랫폼에서 같다 (S1). validate section 13·15가 비교할 이름이 늘어난다.

### 3-1. 탭 선언

| Swift | Kotlin |
|---|---|
| `TabItem(id: String, title: String, icon: Image, selectedIcon: Image? = nil, accessibilityLabel: String? = nil, @ViewBuilder root: () -> Root)` | `TabItem(id: String, title: String, icon: Painter, selectedIcon: Painter? = null, accessibilityLabel: String? = null, root: @Composable () -> Unit)` |

- `id`는 `String`이다. Android에서 `rememberSaveable`과 `SaveableStateProvider`의 키가 되어야 하고, 식별자 `tab.<id>`와 readout `tab=<id>`에 그대로 들어간다. 규칙은 스펙 이름 규칙과 같다: lowerCamelCase.
- `accessibilityLabel`이 없으면 `title`을 쓴다.
- 탭의 순서는 배열 순서다. **첫 번째가 시작 탭이다.** 따로 지정하는 인자는 없다.

### 3-2. 선택 상태: `TabState`

`Flow`처럼 툴킷이 없다. 그래서 전이 표 전체가 JVM과 Linux에서 보통 단위 테스트로 돈다. Swift에서는 `@MainActor @Observable final class`, Kotlin에서는 `StateFlow`를 공개하는 `class`다.

| 멤버 | 내용 |
|---|---|
| `init(tabs: [String], selected: String? = nil)` | 비었거나 id가 겹치거나 `selected`가 목록에 없으면 거부한다 (Swift `throws SPFNUIError`, Kotlin `IllegalArgumentException`). `Flow.open(at:)`이 거부하는 방식과 같다 |
| `tabs`, `start` | id 목록, 그리고 그 첫 번째 |
| `selected` | 선택된 탭의 id. Swift는 관찰되는 프로퍼티, Kotlin은 `StateFlow<String>` |
| `select(_ id, depth: Int) -> TabSelection` | **바가 부른다.** 다른 탭이면 `.switched`. 같은 탭이고 `depth > 0`이면 `.popToRoot`: 호스트가 수행한다. 같은 탭이고 `depth == 0`이면 `.scrollToTop`: 그 탭의 `scrollToTop` 카운터가 1 늘어난다. 모르는 id는 `.ignored` |
| `show(_ id)` | **앱이 부른다** (딥 링크, 코드 전환). 선택만 바꾼다. pop하지 않고 스크롤 신호도 내지 않는다. 이미 선택된 탭이면 아무 일도 하지 않는다 |
| `back(depth: Int) -> Bool`, `handlesBack(depth: Int) -> Bool` | Android 시스템 뒤로. 선택된 탭이 시작 탭이 아니고 `depth == 0`이면 시작 탭을 선택하고 `true`. 그 밖에는 `false`: 탭의 `NavDisplay`나 액티비티에 맡긴다. `Flow.handlesBack`처럼 제스처를 받기 **전에** 묻는다 |
| `scrollToTop(for id) -> Int` | 그 탭 루트가 맨 위로 가야 했던 횟수. 앱의 목록은 값이 바뀌는 것을 보고 스크롤한다 |

`depth`는 인자로 받는다. `TabState`는 호스트의 스택을 모른다. 그것을 아는 것은 `TabHost`다. `Flow.back(entry:)`가 표시 방식을 인자로 받는 것과 같은 모양이다. 판정은 순수 함수이고, 수행은 호스트가 한다.

### 3-3. 호스트

| Swift | Kotlin |
|---|---|
| `TabHost(state: TabState, tabs: [TabItem])` | `@JvmSynthetic @Composable fun TabHost(state: TabState, tabs: List<TabItem>)` |

- 앱의 최상위다. 위에 `NavigationHost`를 두지 않는다 (§2-3). 테마는 지금처럼 바깥에서 주입한다: iOS `TabHost(...).spfnTheme(brand)`, Android `SpfnTheme(brand) { TabHost(...) }`.
- `tabs`의 id 목록과 `state.tabs`가 다르면 디버그 빌드에서 멈춘다. 릴리스 빌드에서는 `tabs`에 있는 것만 그린다.
- 각 탭은 `NavigationHost { root; 바 }` 모양으로 그린다. 바는 루트 콘텐츠 아래에 있다: iOS에서는 `.safeAreaInset(edge: .bottom)`, Android에서는 `Column`의 마지막 자식이다.

### 3-4. 탭별 스택과 다시 누름

- 탭별 스택은 따로 공개하는 타입이 없다. 그 탭의 `NavigationHost`가 스택이다. 앱은 지금처럼 탭 루트 안에 `FlowHost(.push)`를 두고, 흐름을 `push`/`open(at:)`/`close()`로 움직인다.
- `.popToRoot`는 그 탭 저장소의 `shorten(to: 0)`이다. 이것은 플랫폼이 스택을 루트까지 잘랐을 때와 **같은 경로**다: `HostStack.shortened(to: 0)`이 owner별로 잃은 개수를 내고, 각 흐름이 그 수만큼 `Flow.back(entry: .push)`를 받는다. push 흐름의 루트에서 뒤로는 닫기이므로(N2) 결과적으로 그 탭의 모든 흐름이 닫힌다. Android 저장소에는 지금 `back()`(맨 위 하나)만 있으니 같은 산술의 `shorten(to:)`를 더한다. 새 규칙은 없다.
- 다시 누름의 스크롤: `TabScrollToTop`을 읽는다. iOS는 `@Environment(\.tabScrollToTop)`, Android는 `TabScrollToTop.current`로, 둘 다 자기가 속한 탭의 카운터다. 앱은 이렇게 쓴다: iOS `.onChange(of: scrollToTop) { proxy.scrollTo(top) }`, Android `LaunchedEffect(scrollToTop) { listState.animateScrollToItem(0) }`. SDK가 직접 스크롤하지 않는 이유: 루트의 스크롤 컨테이너는 앱의 것일 수도 있고(`Screen(scroll:)`, `PagedView`, 앱 자신의 목록), SDK는 그 상태 객체를 쥐고 있지 않다. `PagedView`가 이 신호를 스스로 따를지는 열린 질문이다 (§10 Q-C).

### 3-5. 앱이 탭을 읽고 바꾸는 법, 딥 링크

```swift
// 읽기: state.selected (관찰됨). 바꾸기: state.show("account")
// 딥 링크 — 계정 탭의 주문 상세
state.show("account")
ordersFlow.open(at: [.orders, .order(id)])   // 계정 탭 루트 안에 FlowHost(.push)가 있는 흐름
```

```kotlin
state.show("account");
ordersFlow.open(listOf(OrderRoute.Orders, OrderRoute.Order(id)));
```

순서는 상관없다. 흐름의 상태가 원본이고, 호스트는 따라간다. 아직 컴포즈되지 않은 탭(Android)이나 아직 만들지 않은 탭(iOS)이면, `show`가 탭을 그리는 순간 `FlowHost(.push)`가 등록되고 첫 동기화(`initial: true` / 수집 시작)가 열린 스택을 호스트에 올린다. 이때 첫 프레임에 루트가 보였다가 상세가 push 전환으로 들어오는지, 곧바로 상세가 보이는지는 §5 U-9에서 잰다. 딥 링크가 오기 전에 떠 있던 모달·시트를 닫는 것은 앱의 일이다. `show`는 표시를 닫지 않는다 (§10 Q-E).

### 3-6. 모양

| 부분 | 테마 키 |
|---|---|
| 바 배경 | `palette.surface` |
| 선택된 항목 (아이콘, 라벨) | `palette.accent` |
| 선택 안 된 항목 | `palette.textSecondary` |
| 라벨 | `typography.caption` |
| 바 위 경계선 | `palette.handle`, 두께 `Metrics.borderWidth` |
| 항목 최소 높이 | `Metrics.touchTarget` (44pt / 48dp). 테마 밖이다 (S10) |

새 테마 키는 없다. 그래서 section 15의 키 비교는 그대로다. 탭 바 전용 외형(`SPFNTabBarAppearance`)을 둘지는 앱 디자인이 요구할 때 따로 정한다.

### 3-7. 식별자와 readout

| 대상 | iOS 접근성 id / Android test tag |
|---|---|
| 바 항목 | `tab.<id>` (`tab.home`, `tab.account`) |
| 바 자체 | 없다. 러너가 읽지 않는 식별자는 붙이지 않는다 (UI guide §3) |

readout은 예제 앱의 것이다: `tab=<selected id>`는 각 탭 루트에, `stack=<depth>`는 지금과 같다. SDK 컴포넌트는 readout을 그리지 않는다.

## 4. 케이스 표

상태 변수: **선택 탭** — 시작 S / 다른 탭 O. **깊이** — 선택된 탭 호스트의 `HostStack` 길이 d. **다른 탭의 깊이** — d'. **표시된 흐름** — 없음 / 모달 / 시트. **키보드** — 내림 / 올림.

| id | 플랫폼 | 선택 | d | 표시 | 키보드 | 행동 | 기대 |
|---|---|---|---|---|---|---|---|
| C-1 | 둘 다 | S | 0 | 없음 | 내림 | O 탭 누름 | O의 루트(처음이면 새로, 아니면 떠날 때 상태 그대로). 바에서 O가 선택됨. 전환 애니메이션 없음 |
| C-2 | 둘 다 | O | 0 (d'=2, C-15의 `show`로 온 상태) | 없음 | 내림 | S 탭 누름 | S의 깊이 2 화면이 그대로 보인다. 바는 없다 (S가 깊이 2이므로). §10 Q-D |
| C-3 | 둘 다 | S | 0 | 없음 | 내림 | 행 누름 (push) | 상세가 뒤쪽 가장자리에서 미끄러져 들어와 바를 **함께 덮는다**. `stack=1`. 바 항목 `tab.*`가 접근성 트리에 없다 |
| C-4 | 둘 다 | S | 1 | 없음 | 내림 | 바 누름 | 해당 없음: 바가 화면에 없다. 코드의 `show`는 C-15 |
| C-5 | iOS | S | 1 | 없음 | 내림 | 가장자리 스와이프 | 한 단계 뒤로. 스와이프 중에 루트가 **바와 함께** 보인다. 손을 떼면 `stack=0`, 바가 있다. 따로 나타나는 애니메이션이나 깜빡임이 없다 (P32 셀) |
| C-6 | iOS | S | 1 | 없음 | 내림 | 콘텐츠 스와이프, 시스템 뒤로 버튼 | C-5와 같다 |
| C-7 | Android | S | 1 | 없음 | 내림 | 시스템 뒤로 (버튼, 제스처) | 한 단계 뒤로. `stack=0`. 바가 루트와 함께 드러난다 |
| C-8 | Android | S | 1 | 없음 | 내림 | 예측형 뒤로 잡고 있기 | 미리보기에 루트가 **바와 함께** 보인다. 놓으면 C-7. 취소하면 d=1 그대로 (P35) |
| C-9 | Android | O | 0 | 없음 | 내림 | 시스템 뒤로 | S가 선택된다. S는 떠날 때 깊이 그대로다 (d가 0이 아니면 그 상세가 보인다). 앱은 그대로다 |
| C-10 | Android | S | 0 | 없음 | 내림 | 시스템 뒤로 | 액티비티가 끝난다 (앱을 나감). `TabHost`는 처리하지 않는다 |
| C-11 | Android | O | 0 | 없음 | 내림 | 예측형 뒤로 잡고 있기 | 미리보기가 없다 (시스템의 홈으로 가는 미리보기도 없다. 제스처는 `TabHost`가 받았다). 놓으면 C-9 |
| C-12 | iOS | 아무 탭 | 0 | 없음 | 내림 | 가장자리 스와이프 | 아무 일도 없다 (아래에 라우트가 없다) |
| C-13 | 둘 다 | S | 2 | 없음 | 내림 | 현재 탭 다시 누름 | 해당 없음: d>0이면 바가 없다. `select(_, depth: 2)`의 `.popToRoot`는 단위 테스트로 확인하고(§9), 앱이 코드에서 루트까지 pop하는 길은 그 탭 흐름의 `close()`다 |
| C-14 | 둘 다 | S | 0 | 없음 | 내림 | 현재 탭 다시 누름 | 스택은 그대로다. `TabScrollToTop`가 1 늘고, 그것을 따르는 목록은 맨 위로 간다 |
| C-15 | 둘 다 | S | 1 | 없음 | 내림 | 코드: `show(O)` | O가 선택된다. S는 d=1을 유지한다. 다시 S로 오면(`show(S)`) 그 상세가 그대로 있다 |
| C-16 | 둘 다 | S | 0 | 없음 | 내림 | 모달 흐름 열기 | 덮개가 바를 포함한 화면 전체를 덮는다. `tab.*`는 누를 수 없다. Android: 덮개는 `TabHost`의 덮개 층에 그려진다 |
| C-17 | 둘 다 | S | 0 | 없음 | 내림 | 시트 흐름 열기 | 시트와 스크림이 바를 덮는다. 스크림을 누르면 시트가 닫힌다 (지금 규칙). 바 항목은 눌리지 않는다 |
| C-18 | 둘 다 | S | 1 | 없음 | 내림 | 상세에서 모달 열기 | C-16과 같다. 닫으면 d=1 상세로 돌아온다 |
| C-19 | 둘 다 | S | 0 | 모달 | 내림 | 닫기 (X) | 덮개가 아래로 나간다. 루트와 바가 그대로 있다. 선택 탭도 그대로다 |
| C-20 | Android | S | 0 | 모달 (깊이 1) | 내림 | 시스템 뒤로 | 흐름이 닫힌다 (close 표 "modal, root"). `TabHost`의 뒤로는 받지 않는다 |
| C-21 | Android | O | 0 | 시트 | 내림 | 시스템 뒤로 | 시트가 닫힌다. 탭은 O 그대로다. 두 번째 뒤로를 보내면 C-9 |
| C-22 | iOS | S | 0 | 시트 | 내림 | 시트를 끌어내림 | 흐름이 닫힌다. 바가 그대로 있다 |
| C-23 | 둘 다 | S | 0 | 없음 | 올림 | (첫 렌더) | 본문은 키보드를 피한다 (K1). **바는 올라가지 않는다.** 키보드가 바를 덮는다 |
| C-24 | 둘 다 | S | 0 | 없음 | 올림 | 입력칸 밖 탭 | 키보드가 내려간다 (K2). 바가 다시 보인다. 선택 탭은 그대로다 |
| C-25 | 둘 다 | S | 0 | 없음 | 올림 | 입력칸에서 행 누름 (push) | 키보드가 내려가고 상세가 push된다 (C-3) |
| C-26 | Android | S | 0 | 없음 | 올림 | 시스템 뒤로 | 키보드만 내려간다 (IME가 먼저 소비한다). 탭과 스택은 그대로다 |
| C-27 | 둘 다 | O | 1 | 없음 | 내림 | 백그라운드로 보냈다 복귀 | 모든 것이 그대로다: O, d=1, S의 상태 |
| C-28 | Android | O | 1 (S: d'=1) | 없음 | 내림 | 프로세스 종료 후 복원 | 선택 탭 O가 복원된다 (`rememberSaveable`). 흐름의 스택은 **복원되지 않는다**. 흐름은 앱의 객체이고, 지금의 `NavigationHost`도 복원하지 않는다. 그래서 두 탭 모두 루트다. 목록의 스크롤 위치는 복원된다 (§10 Q-F) |
| C-29 | 둘 다 | O | 1 | 없음 | 내림 | 회전 / 창 크기 변경 | O, d=1, S의 상태가 그대로다. Android는 액티비티를 다시 만들지만, 저장소는 흐름이 다시 등록하면서 채워진다 (§2-4). 바는 가로에서도 아래에 있다 |
| C-30 | 둘 다 | S | 0 | 없음 | 내림 | 딥 링크: O의 상세 | O가 선택되고 상세가 보인다 (d=1 이상). 상세에서 뒤로를 보내면 O의 루트로 간다 (S가 아니다) |
| C-31 | 둘 다 | O | 0 (O 첫 방문 전) | 없음 | 내림 | 딥 링크: O의 상세 | C-30과 같다. 첫 프레임의 모양은 U-9에서 잰다 |
| C-32 | 둘 다 | S | 0 | 모달 | 내림 | 딥 링크: O의 상세 | 앱이 먼저 모달을 닫는다 (§3-5). 닫지 않으면 모달이 새 탭 위에 남는다 (정의된 동작, 기대가 아니다) |
| C-33 | Android | O | 2 | 없음 | 내림 | 시스템 뒤로 두 번, 세 번 | 2→1→0 (O의 루트), 세 번째에 S로 간다 (C-9) |
| C-34 | 둘 다 | S | 0 | 없음 | 내림 | O 누름 → O에서 push → S 누름 불가 → 뒤로 → S 누름 | 각 탭의 스택이 따로 움직인다. S의 d는 계속 0이다 (N1은 탭마다 따로) |

셀 수: 34. 단위 테스트로만 확인하는 것 1개(C-13: 바가 없으니 기기에서 누를 수 없다), 애니메이션 모양을 사람이 보는 것 3개(C-5·C-8·C-11. 스택 readout 부분은 러너도 돈다), 나머지 30개는 기기 러너가 돈다. 러너가 앱 밖이나 프로세스 종료를 단언할 수 없을 때 `manual`로 내리는 것은 §9-2에 적었다.

### 바가 다시 나타날 때 애니메이션이 있는가

있다. 하지만 바만의 애니메이션은 아니다. 바는 루트 화면의 일부이므로 pop 전환이 루트를 드러낼 때 함께 드러난다: iOS는 시스템 pop, Android는 `FlowTransitions.pop`과 `predictivePop`. 따로 나타나거나 사라지는 애니메이션은 없다.

## 5. 기기에서 모을 증거 (운영자가 돌린다)

기기: iPhone (iOS 26)과 iOS 17·18 시뮬레이터 하나씩, Android 실기기 하나 (API 34 이상, 제스처 내비게이션, P35 매니페스트 선언)와 3버튼 내비게이션 에뮬레이터 하나. 프로브 앱은 `examples/`의 예제 앱에 `TabHost`를 넣은 가지로 만든다.

| id | 플랫폼 | 시험 | 볼 것 | 틀리면 |
|---|---|---|---|---|
| U-1 | iOS 17·18·26 | 대조군 (가): 시스템 `TabView` + 탭마다 `NavigationHost` + 도착 화면에 `.toolbar(.hidden, for: .tabBar)`. push, pop, 가장자리 스와이프를 녹화한다 | 바가 push와 함께 움직이는가, pop 뒤 깜빡이는가, 스와이프 중에 루트에 바가 있는가 | 모두 깨끗하면 §2-1을 다시 연다. **잰 것 (2026-09-27, 설계 검토 중, iOS 18.2와 26.3 시뮬레이터): 두 버전 모두 pop이 끝난 뒤 약 0.23초가 지나서야 시스템 탭 바가 다시 나타났다.** 깨끗하지 않으므로 (나)를 유지한다 (Q-A) |
| U-2 | iOS 18 | 대조군 (가)에서 탭 안의 경로 바인딩 스택에 한 번 push | `stack=1`인가, 화면이 두 번 쌓였는가 (포럼 759542) | 두 번이면 (가)를 확정 기각 |
| U-3 | iOS 17·18·26 | 대조군 (가)에서 선택된 탭을 다시 누름 | `TabView(selection:)` setter가 불리는가, 시스템이 스택을 스스로 pop하는가, 그 pop이 경로 setter를 거쳐 흐름에 닿는가 | 기록만 한다 |
| U-4 | iOS 26 | (나): 루트 d=0 → push → 가장자리 스와이프를 반쯤 하다 취소, 그리고 끝까지 | 스와이프 중에 루트와 바가 함께 보이는가, 취소 뒤 상세와 `stack=1`, 완료 뒤 `stack=0` | 바를 루트 밖으로 옮겨야 한다면 설계 전체를 다시 본다 |
| U-5 | iOS 26 | (나): 탭 루트의 `Screen(scroll: true)` 긴 목록을 끝까지 스크롤 | 마지막 행이 바 위에서 끝나는가 (`.safeAreaInset`), 바 아래로 들어가 가려지지 않는가 | 루트 본문에 바 높이만큼 하단 여백 |
| U-6 | iOS 26 | 탭 루트에 입력칸, 키보드를 올림 | 바가 키보드 위로 올라가지 않는가 (`.safeAreaInset` 콘텐츠는 기본으로 키보드를 피한다. 바에 `.ignoresSafeArea(.keyboard)`가 필요하다), 본문은 키보드를 피하는가 (K1) | 바를 키보드 안전 영역 밖에 둔다 |
| U-7 | iOS 17 | `.accessibilityAddTraits(.isTabBar)`가 iOS 17에서 컴파일되고 VoiceOver가 "탭 막대"라고 읽는가 | VoiceOver 발화, Accessibility Inspector의 특성 | UIKit `UIAccessibilityTraits.tabBar`를 표현 뷰로 |
| U-8 | 둘 다 | 가장 큰 글자 크기 (iOS 접근성 크기 XXXL, Android 글꼴 크기 200%) | 라벨이 잘리는가, 바 높이가 늘어나는가, iOS에서 라벨을 길게 누르면 큰 콘텐츠 뷰어가 뜨는가 | §6의 글자 규칙 조정 |
| U-9 | 둘 다 | 한 번도 열지 않은 탭의 상세로 딥 링크 | 첫 프레임에 루트가 보였다가 push되는가, 곧바로 상세가 보이는가 | 곧바로 보이게 하려면 첫 동기화를 전환 없이 |
| U-10 | Android | O의 루트에서 예측형 뒤로를 잡고 있다 놓기, 취소하기 | C-11: 미리보기가 없고, 놓으면 S. 취소하면 O 그대로 | — |
| U-11 | Android | S d=1 상태에서 예측형 뒤로를 잡고 있기 | C-8: 미리보기에 바가 보이는가 | — |
| U-12 | Android | 탭 전환 (S 목록을 스크롤 → O → S) | 스크롤 위치가 남는가 (`SaveableStateProvider`), `NavDisplay` 항목의 저장 상태가 남는가 | 모든 탭을 컴포즈해 두는 방식으로 바꾸고 semantics를 가린다 |
| U-13 | Android | 개발자 옵션 "활동 유지 안 함" + 백그라운드 → 복귀 (프로세스 종료 흉내) | C-28: 선택 탭이 복원되는가, 두 탭 모두 루트인가 | — |
| U-14 | Android | `testTagsAsResourceId`를 `TabHost` 밖에 걸고 계층 덤프 (P21·P33 명령) | 루트, push된 상세, 덮개 층에서 `tab.*`와 화면 id가 모두 resource-id로 보이는가, 바 항목의 bounds 높이가 132px(2.75 밀도의 48dp) 이상이고 서로 겹치지 않는가 | — |
| U-15 | Android | 탭 루트 안의 `FlowHost(.modal)`와 `FlowHost(.sheet)`를 열기 | 덮개와 스크림이 바를 덮는가, 바 자리를 눌러도 탭이 바뀌지 않는가 (P36: 손가락으로도 확인) | — |
| U-16 | Android | 3버튼 내비게이션, 가로 회전 | 바가 내비게이션 바 인셋 위에 서는가, 인셋이 두 번 더해지지 않는가 (P25) | — |

## 6. 접근성

| 항목 | iOS | Android |
|---|---|---|
| 바의 의미 | 컨테이너에 `.accessibilityElement(children: .contain)`과 `.accessibilityAddTraits(.isTabBar)` (U-7) | 바의 `Row`에 `Modifier.selectableGroup()` |
| 항목 | `Button`, `.accessibilityAddTraits(.isSelected)`는 선택된 항목에만. `.buttonStyle(.plain)`을 쓰면 `contentShape`를 항목 전체에 둔다 (P39) | `Modifier.selectable(selected, role = Role.Tab, onClick)`. `clickable`에 따로 `semantics`를 붙이지 않는다 |
| 라벨 | `accessibilityLabel` 또는 `title`. 아이콘은 `.accessibilityHidden(true)` | 같다. 아이콘의 `contentDescription = null` |
| 선택 상태 | VoiceOver가 "선택됨"을 읽는다 | TalkBack이 "선택됨, 탭"을 읽는다 |
| 최소 터치 영역 | 항목마다 `.frame(minHeight: Metrics.touchTarget)` 자기 레이아웃에 (P21), 너비는 바를 N등분 | 항목마다 `heightIn(min = 48.dp)`, `weight(1f)`. 확장에 기대지 않는다 (P21) |
| 큰 글자 | 라벨은 한 줄이다. `dynamicTypeSize(...(.accessibility1))`로 상한을 두고, 그 위에서는 `accessibilityShowsLargeContentViewer`로 큰 콘텐츠 뷰어를 쓴다 (시스템 탭 바와 같은 방식) | 라벨은 한 줄이고 넘치면 말줄임. 바 높이는 글꼴 크기를 따라 늘어난다. `sp` 그대로, 상한은 없다 (U-8) |
| 스크롤과 접근성 트리 | 바는 스크롤 컨테이너 밖이라 항상 트리에 있다 (P25) | 같다 |
| 가려진 탭 | 선택되지 않은 탭은 `.accessibilityHidden(true)` | 선택되지 않은 탭은 컴포즈하지 않는다 |

### 인셋 소유 (P25, "`Screen` owns the insets"의 갱신)

| 자리 | 하단 인셋 (홈 인디케이터 / 내비게이션 바) | 키보드 |
|---|---|---|
| 탭 루트, 바가 보임 | **바가 쓴다.** 루트 본문은 그만큼과 바 높이를 소비된 것으로 받는다. Android: `TabHost`가 루트 콘텐츠에 `consumeWindowInsets(WindowInsets.navigationBars.add(WindowInsets(bottom = barHeight)))`를 준다. 그래서 `Screen` 본문의 `ime ∪ navigationBars`는 키보드가 없으면 0, 있으면 `ime − (내비게이션 바 + 바 높이)`가 된다. iOS: `.safeAreaInset(edge: .bottom)`이 안전 영역을 줄인다 | 본문이 피한다 (K1). 바는 피하지 않고 키보드 밑에 가려진다 |
| push된 상세 (바가 없음) | 지금처럼 상세의 `Screen` 본문이 쓴다. 상세는 루트의 형제라서 루트에 준 소비가 닿지 않는다 (P33의 문장 그대로) | 지금과 같다 |
| 덮개, 시트 | 지금과 같다 | 지금과 같다 |

## 7. 범위

| 포함 | 제외 |
|---|---|
| `TabItem`, `TabState`, `TabSelection`, `TabHost`, `TabScrollToTop` 양 플랫폼. Android `NavigationHost` 내부 오버로드(끌어올린 저장소), 저장소 `shorten(to:)`, 탭 안 모달·시트 등록. 케이스 표 셀, 단위 테스트, 스펙 확장. architecture README와 UI guide의 갱신 | **배지.** 항목 레이아웃에 아이콘 위 오른쪽 자리를 비워 두고, 나중에 `TabItem(badge: TabBadge? = nil)`를 기본값 있는 인자로 더한다. 그때 라벨은 "홈, 새 항목 3개"처럼 합친다. 지금은 코드가 없다 |
| | 위쪽 탭과 페이저 (따로 설계) |
| | 사이드바, 분할 뷰, iPad 사이드바 적응, Android 내비게이션 레일 (가로·큰 화면에서도 바는 아래에 있다) |
| | iOS 26 탭 바 최소화 (`tabBarMinimizeBehavior`), 바 위 보조 영역 (`tabViewBottomAccessory`) |
| | 탭 전환 애니메이션. 즉시 바꾼다 (iOS 시스템 탭 바와 같다) |
| | 시작 탭이 아닌 탭 루트에서 예측형 뒤로의 미리보기 |
| | 흐름 스택의 프로세스 종료 후 복원 |
| | 특정 앱 전용 코드. 예제는 일반적인 홈 목록 + 계정 탭으로만 쓴다 |

## 8. 다시 들여오면 안 되는 함정과 갱신할 항목

| 항목 | 이 설계에서 |
|---|---|
| P21 | 바 항목마다 자기 레이아웃에 최소 터치 영역. U-14에서 bounds를 잰다 |
| P25 | 바는 스크롤 밖에 있다. 인셋 소유는 §6 표. **갱신한다:** "`Screen`이 인셋을 가진다"에 탭 루트 예외 한 줄 |
| P29 | 어떤 막대도 숨기지 않는다. 탐지 grep이 계속 비어 있다. **갱신한다:** "지금 고른 것"에 시스템 `TabView`의 `.toolbar(.hidden, for: .tabBar)`를 기각한 이유와 §5 U-1 결과 |
| P30 | 탭 전환 뒤, push 뒤에 back/swipe를 보내는 셀은 `CaseTable.systemBack()`의 `waitForAnimationToEnd`를 거친다 |
| P31 | `TabHost`는 탭마다 `NavigationHost` 하나만 만든다. `FlowHost(.push)`는 자기 내비게이터를 만들지 않는다. `TabHost` 위에 `NavigationHost`를 두지 않는다 |
| P32 | SDK는 제스처를 켜거나 끄지 않는다. Android `TabHost`의 `BackHandler`는 `handlesBack(depth:)`로 **흐름의 상태가 바뀐 뒤에** 켜진다. 드러나는 화면의 수명주기로 켜지 않는다 |
| P33 | `testTagsAsResourceId`와 앱 전체에 걸 것은 `TabHost` 바깥에. **갱신한다:** 탐지 절에 "탭 루트 안에 건 것은 그 탭 루트에만 걸린다" |
| P35 | 탭 안 예측형 뒤로는 매니페스트 선언에 기댄다. 지금과 같다 |
| P36 | 덮개 층은 Main 패스에서 consume하지 않는다. 지금의 `ModalCover`를 그대로 쓴다 |
| P37 | 탭마다의 `NavDisplay`도 `FlowTransitions`를 받는다. 탭 전환은 `NavDisplay`를 거치지 않는다 |
| P39 | iOS 바 항목의 탭 영역은 항목 전체. `contentShape` |
| P41 | 탭마다 `NavDisplay`가 따로라서 탭 사이의 같은 route 값은 충돌하지 않는다. 한 탭 안에서는 지금과 같다 |
| 새 항목 후보 | "Android 모달·시트는 부모만 덮는다: 탭 루트 안에서 열면 바가 살아 있다." 구현 라운드에서 실제로 나오면 등록한다 (등록부는 실제로 나온 것만 담는다) |

갱신할 다른 문서: architecture README "Three ways in"(push는 선택된 탭의 호스트에 이어 붙는다. Android 모달·시트는 `TabHost` 안에서 덮개 층에 선다), "`Screen` owns the insets"(§6). UI guide §2 어휘 표에 한 줄, §3에 `tab.<id>`.

## 9. 시험 계획

### 9-1. 단위 (툴킷 없음, Linux와 JVM)

`TabStateTests.swift` / `TabStateTest.kt`. 이름은 셀 이름을 따른다.

| 테스트 | 셀 |
|---|---|
| `init_empty_refused`, `init_duplicate_refused`, `init_unknownSelected_refused` | — |
| `select_other_switches` | C-1 |
| `select_current_atDepth_popsToRoot` | C-13 |
| `select_current_atRoot_scrollsToTop_countsUp` | C-14 |
| `select_unknown_ignored` | — |
| `show_never_popsOrScrolls` | C-15 |
| `back_nonStartRoot_selectsStart`, `back_startRoot_notHandled`, `back_atDepth_notHandled` | C-9, C-10, C-7 |
| `handlesBack_matches_back` (모든 선택 × 깊이 0–2) | — |

`HostStackTests`/`HostStackTest`에 더한다: `shortened_toZero_dropsEveryOwner` (C-13의 pop이 기대는 산술). Android `HostStackStore`의 `shorten(to:)`는 가짜 등록 둘로 owner별 `back` 횟수를 센다. `TabHost`의 등록 충돌(같은 owner를 두 탭에)은 저장소 수준 테스트로 확인한다.

### 9-2. 기기 셀 (UI 스펙과 ui-codegen)

- 스펙에 최상위 키 `tabs`를 더한다. `specVersion: 3`이 필요하다. 버전 2와 같은 방식으로 **더하기만** 한다: 버전 1·2 파일은 같은 바이트를 낸다 (`spfnUiVerify`).

  ```json
  "tabs": [
    { "id": "home",    "title": "Home",    "root": "homeList",    "flows": ["itemDetail"] },
    { "id": "account", "title": "Account", "root": "accountHome", "flows": ["editProfile"] }
  ]
  ```

  거부 규칙(새로 번호를 매긴다): 탭 id가 이름 규칙을 어김, `root`가 없는 화면, 한 흐름이 두 탭에 나옴 (§2-3의 "한 흐름은 한 탭"), `tabs`가 있는데 비어 있음, id가 겹침. 배열 순서가 탭 순서이고 첫 번째가 시작 탭이다. 스펙의 다른 키(`flows`, `screens`)는 객체인데 여기만 배열인 이유는 순서가 의미를 갖기 때문이다 (§10 Q-G).

  **구현에서 정한 것 — `root`는 화면이 아니라 루트 뷰의 이름이다.** 스펙의 화면은 모두 흐름에 속하고 흐름의 스택 위에 선다. 탭 루트는 그 스택이 서는 **자리**, 곧 탭의 `NavigationHost`의 루트라서 어느 흐름의 화면도 될 수 없다. 그리고 스펙 문법에는 "화면이 다른 흐름을 연다"가 없다 (거부 3). 그래서 `root`는 생성기가 직접 쓰는 루트 뷰의 이름이다: readout(`tab=`, `stack=`, `scrollToTop=`), 탭이 여는 흐름마다 컨트롤 하나(`<root>.<flow>`), 키보드 셀을 위한 입력칸(`<root>.note`), 맨 위로 스크롤을 따르는 행들, 그리고 그 흐름들의 호스트. 위 거부 규칙의 "`root`가 없는 화면"은 그래서 "`root`가 이미 있는 화면이나 흐름의 이름"으로 바뀌었다 — 한 이름이 두 선언이 될 수 없기 때문이다. 거부 16 전체는 `examples/ui-spec/SCHEMA.md`에 있다.
- 조각 규칙: `tabs`는 한 조각만 선언한다 (탭 순서는 하나의 목록이다). 그 조각은 탭의 흐름도 함께 선언한다 — 조각은 따로 읽히므로 보이지 않는 흐름을 이름으로 부를 수 없다. 예제는 `examples/ui-spec/tabs.json`이다.
- 셀 id는 `tabs-c<n>`, §4의 C-n 그대로다. C-4(바가 화면에 없다)와 C-18(예제의 상세 화면은 두 번째 흐름을 열지 않는다)은 셀이 없다. C-13과 C-15는 예제 앱의 JVM 단위 테스트(`TabCellTest`)다. 플랫폼마다 답이 다른 행(C-9·C-12·C-20·C-21·C-26·C-33)은 한 플로우 파일 안에서 `runFlow: when: platform`으로 나뉜다.
- 생성기가 쓰는 것: 예제 앱의 `TabHost` 스캐폴드(두 앱 모두), `Rules.kt`의 탭 규칙 행 (T1–Tn, 이 문서의 C-셀에서 나온다. P10: 표를 구현에서 파생하지 않는다), 셀마다 Maestro 플로우 하나. 셀은 `tab=<id>`와 `stack=<n>` readout을 단언하고 `tab.<id>`를 id로 누른다.
- 러너 종류: C-1–C-4, C-7, C-9, C-10, C-12, C-14–C-27, C-29–C-31, C-33, C-34는 `both` 또는 플랫폼 한쪽. C-5·C-8·C-11의 애니메이션 모양은 `manual`. C-28은 Android 러너가 "활동 유지 안 함"을 켤 수 없으면 `manual`. C-10(앱을 나감)은 Maestro가 앱 밖을 단언할 수 없으면 `manual`.
- `CaseTable.headerBack`과 `systemBack`은 그대로 쓴다 (P30).

### 9-3. 사람이 확인하는 것

§5의 U-셀, 그리고 UI guide §6 5번: 셀마다 두 플랫폼의 스크린샷을 나란히.

## 10. 검토자가 답할 질문

검토자의 답 (2026-09-27): 여덟 질문 모두 **제안대로** 정했다. Q-A는 시뮬레이터 측정(§5 U-1: iOS 18.2와 26.3에서 pop 뒤 약 0.23초 늦게 시스템 탭 바가 다시 나타남)을 근거로 (나)를 확정했다. 표의 "답" 열이 결정이고, 구현이 따른 것이다.

| id | 답 | 구현에서 |
|---|---|---|
| Q-A | (나): SDK가 양 플랫폼 모두에서 바를 그린다. U-1 측정이 (가)를 받치지 않는다. U-1–U-3이 모든 지원 버전에서 깨끗해지면 다시 연다 | `TabHost.swift`, `TabHost.kt`. P29에 기록 |
| Q-B | 시작 탭이 아닌 탭의 루트에서 Android 뒤로는 시작 탭으로 간다. 시작 탭 루트에서는 앱을 나간다. 탭 기록은 되짚지 않는다 | `TabState.back(depth:)`, `TabHost.kt`의 `BackHandler`. 셀 `tabs-c9`·`tabs-c33`, 사람 셀 `tabs-c10` |
| Q-C | 맨 위로 스크롤은 앱의 일이다. `PagedView`는 신호를 스스로 따르지 않는다 | `TabScrollToTop`. 예제 루트가 따른다. 셀 `tabs-c14` |
| Q-D | 다른 탭에 갔다 오면 떠날 때의 상세가 그대로 보인다. 다시 누르면 루트로 | `TabState.select`, 셀 `tabs-c2` |
| Q-E | 딥 링크의 `show`는 떠 있는 모달·시트를 닫지 않는다. 앱의 딥 링크 처리기가 닫는다 | `TabState.show`. 사람 셀 `tabs-c32` |
| Q-F | 이번에는 Android 프로세스 종료 뒤 흐름 스택을 복원하지 않는다. 선택 탭만 복원한다 | `TabHost.kt`의 `rememberSaveable`. 사람 셀 `tabs-c28` |
| Q-G | 스펙의 `tabs`는 배열이다 | `specVersion` 3, 거부 16 |
| Q-H | 바 전용 테마 키는 아직 두지 않는다 | 팔레트·타이포그래피의 기존 키만 (§3-6). validate section 15의 키 비교는 그대로 |

아래는 검토 전에 물은 그대로의 질문과 제안이다.

| id | 질문 | 제안 |
|---|---|---|
| Q-A | iOS에서 시스템 탭 바 대신 SDK 바를 쓰는 것이 H2의 정신과 어긋나는 것을 받아들이는가 | 받아들인다. U-1–U-3이 깨끗하면 다시 연다 |
| Q-B | Android 뒤로 규칙: 시작 탭이 아닌 탭의 루트에서 뒤로는 시작 탭으로 가는가, 곧바로 나가는가. Android 개발자 문서는 "시작 화면은 뒤로를 눌러 런처로 돌아가기 전에 마지막으로 보는 화면"이라고 한다 ([Principles of navigation](https://developer.android.com/guide/navigation/principles), "Fixed start destination"). 반면 Material 1의 하단 내비게이션 지침은 "뒤로 버튼은 하단 내비게이션 뷰 사이를 오가지 않는다"고 한다 ([m1 bottom navigation](https://m1.material.io/components/bottom-navigation.html)). 두 문장은 "탭 사이를 기록대로 되짚지 않는다"와 "시작 화면을 거쳐 나간다"로 함께 읽을 수 있고, 이 설계는 그렇게 읽었다 | 시작 탭으로 간 뒤 나간다 (C-9, C-10). 탭 기록을 되짚지는 않는다 |
| Q-C | `PagedView`가 `TabScrollToTop`을 스스로 따를 것인가 | 따르지 않는다. 앱이 한 줄로 연결한다. 두 번째 앱이 같은 줄을 쓰면 다시 본다 |
| Q-D | 다른 탭에 가 있는 동안 떠난 탭의 스택이 깊이를 가지고 있었다면, 그 탭을 누를 때 상세로 돌아가는가 (C-2, iOS 시스템 관례), 루트로 돌아가는가 | 상세로 돌아간다 (요구 Q6). 다시 누르면 루트 |
| Q-E | 딥 링크의 `show`가 떠 있는 모달·시트를 닫아야 하는가 (C-32) | 닫지 않는다. SDK는 어떤 흐름이 떠 있는지 모른다. 앱의 딥 링크 처리기가 닫는다 |
| Q-F | Android 프로세스 종료 뒤 흐름 스택 복원 (C-28) | 이번에는 하지 않는다. `FlowRoute`를 저장 가능하게 만드는 것은 따로 설계한다 |
| Q-G | 스펙의 `tabs`를 배열로 할 것인가, 다른 키처럼 객체로 할 것인가 (객체면 순서에 의미가 실린다) | 배열 (§9-2의 예시). 순서가 의미를 가지는 곳에 객체를 쓰지 않는다 |
| Q-H | 바 전용 테마 키를 지금 둘 것인가 | 두지 않는다 (§3-6) |
