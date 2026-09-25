# 설계 — 화면 헤더를 선택 사항으로, 뒤로가기는 플랫폼 기본 동작으로

- 상태: 승인 (2026-09-25). §5 U-1~U-5는 구현 첫 단계에서 시뮬레이터로 확인한다.
- 대상: `SPFNUI`(iOS)와 `spfn-ui`(Android)의 `Screen`, `FlowHost`, `SwipeBackGesture`
- 관련: [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P21·P25·P27·P29·P32, [architecture README](README.md) "`Screen` owns the insets"

## 0. 요약

| 항목 | 지금 | 바뀐 뒤 |
|---|---|---|
| 헤더 | `Screen`이 항상 직접 그린 헤더를 그린다 (`title` 필수) | 기본값은 플랫폼 기본 헤더. 앱이 원하면 자기 헤더를 그린다 |
| iOS 시스템 내비게이션 바 | 숨긴다 (`.toolbar(.hidden, for: .navigationBar)`) | 숨기지 않는다. 기본값은 테마를 적용한 시스템 바, 커스텀이면 비우고 투명하게 둔다 |
| iOS 스와이프 뒤로가기 | 숨긴 바에 딸린 제스처를 `SwipeBackGesture`가 다시 켠다 | 바가 있으므로 UIKit 기본 동작 그대로. `SwipeBackGesture` 삭제 |
| Android | 직접 그린 헤더, 시스템 back·예측형 back은 `NavDisplay`·`BackHandler` | 기본값은 지금 헤더 그대로. 커스텀이면 앱의 헤더로 교체 |
| 화면 동작 (안전 영역, 키보드) | `Screen` 안에 헤더와 묶여 있다 | 헤더와 분리. 헤더가 무엇이든 같은 동작 |
| 뒤로·닫기 정보 (`ScreenChrome`) | 내부 전용 | 읽기 전용으로 공개. 앱의 헤더가 뒤로·닫기 버튼을 둘 수 있다 |

## 1. 왜 바꾸는가

### 1-1. 헤더를 뺄 수 없다

`Screen`은 제목 줄을 항상 그린다. 앱이 자기 디자인 시스템으로 화면 위쪽을 그리는 경우(로고만 있는 로그인 화면, 온보딩의 건너뛰기, 진행 점)에 SDK 헤더와 앱의 위쪽 줄이 겹친다. 그렇다고 `Screen`을 쓰지 않으면 아래가 한꺼번에 빠진다.

| `Screen`을 쓰지 않으면 빠지는 것 | 지금 위치 | 공개 여부 |
|---|---|---|
| 빈 곳을 탭하면 키보드 닫기 (K 조항) | `Screen`의 `onTapGesture` / `detectTapGestures` | `Screen` 안에서만 |
| 스크롤로 키보드 닫기 (iOS) | `Screen`의 `scrollDismissesKeyboard` | `Screen` 안에서만 |
| 키보드·하단 바 inset 처리 | `Screen` 본문 | `Screen` 안에서만 |
| 흐름에 맞는 뒤로·닫기 (`Flow.wayOut`) | `ScreenChrome` | **internal** |
| iOS 스와이프 뒤로가기 | `SwipeBackGesture` | internal |

### 1-2. iOS 제스처 재활성화가 비공개 동작에 기댄다

P29의 첫 번째 처방은 숨긴 바의 제스처를 다시 켜는 것이었다. 실제로는 제스처가 둘이다. `UINavigationController.h`(iOS 26 SDK) 기준:

| 제스처 | 도입 | 범위 |
|---|---|---|
| `interactivePopGestureRecognizer` | iOS 7 | 왼쪽 가장자리 |
| `interactiveContentPopGestureRecognizer` | iOS 26 | 콘텐츠 영역 전체 |

`SwipeBackGesture`는 두 번째 제스처를 공개 속성이 아니라 비공개 클래스 이름(`Parallax`)으로 찾는다. 두 속성 모두 헤더 주석이 "실패 조건 설정에만 쓰라"고 적는데, delegate를 바꾸는 것은 그 범위 밖이다. iOS가 바뀌면 조용히 깨질 수 있다.

### 1-3. P29의 두 번째 처방이 이제는 맞다

P29는 "바를 숨기지 않고 배경·제목·back 버튼만 비운다"를 두 번째 처방으로 적고, 바 높이만큼 레이아웃이 내려간다는 이유로 고르지 않았다. 그 레이아웃 문제는 앱의 헤더를 바가 차지한 높이 안에 그리면 사라진다(§3-3). 바가 있으면 두 제스처 모두 UIKit이 직접 관리한다.

## 2. 원칙

| # | 원칙 |
|---|---|
| H1 | `Flow`는 흐름 상태만 가진다. 헤더 모양을 모른다 (지금과 같음) |
| H2 | 뒤로가기와 그 제스처는 플랫폼의 것을 쓴다. SDK는 제스처를 켜거나 끄지 않는다 |
| H3 | 헤더는 선택이다. 고르지 않으면 플랫폼 기본 헤더를 쓴다 |
| H4 | 화면 동작(안전 영역, 키보드)은 헤더와 무관하게 같다 |
| H5 | 두 플랫폼이 맞추는 것은 규칙(N3: 쌓인 화면은 왼쪽 뒤로, 띄운 흐름의 첫 화면은 오른쪽 닫기)이다. 픽셀이 아니다 |

## 3. 설계

### 3-1. 헤더 모드

| 모드 | 호출 | iOS | Android |
|---|---|---|---|
| `standard` (기본값) | `Screen(title: "…") { … }` | 시스템 내비게이션 바. 제목·글꼴·색·배경은 주입된 테마로 (`UINavigationBarAppearance`, `.toolbarBackground`). 뒤로는 시스템 back 버튼, 닫기는 오른쪽 툴바 항목 | 지금 헤더 그대로 (Android에는 Material 없이 쓸 기본 앱 바가 없다) |
| `custom` | `Screen(header: .custom) { … }` | 시스템 바는 남기되 비우고 투명하게 (§3-3). 화면 내용이 바 높이 영역부터 그린다 | SDK 헤더를 그리지 않는다. 화면 내용이 상태 바 아래부터 그린다 |

- `custom`에서 앱은 자기 위쪽 줄에 `WayOutButton`(§3-4)을 둔다.
- iOS에서 `custom` 화면을 쌓으면 뒤로 버튼은 투명한 시스템 back 버튼이다. 앱의 `WayOutButton(.back)`은 그 위치에 그려지는 모양일 뿐이다 (U-2).

### 3-2. 화면 동작 분리

| 동작 | 지금 | 바뀐 뒤 |
|---|---|---|
| 상단 inset | 헤더가 상태 바 inset을 쓴다 | `standard`: 헤더가 쓴다. `custom`: 내용이 받는다 (Android는 `windowInsetsPadding`으로 소비되지 않은 값) |
| 하단 inset + 키보드 | 본문이 `ime ∪ navigationBars` | 같음. 헤더 모드와 무관 |
| 빈 곳 탭하면 키보드 닫기 | `Screen` 루트 | 같음. 헤더 모드와 무관 |
| 스크롤로 키보드 닫기 | `scroll = true`일 때 | 같음 |
| 시트 높이 측정 (P34) | 본문 | 같음 |

### 3-3. iOS `custom`: 비운 투명 바

| 항목 | 방법 | 주의 |
|---|---|---|
| 배경 | `UINavigationBarAppearance.configureWithTransparentBackground()` 또는 `.toolbarBackground(.hidden)` | 그림자 선까지 없앤다 |
| 제목 | 빈 문자열 | |
| 뒤로 버튼 | 숨기지 않는다. 화살표 이미지를 빈 이미지로, 글자 없이 (`backButtonDisplayMode = .minimal`) | `navigationBarBackButtonHidden(true)`는 스와이프를 끈다. 쓰지 않는다 |
| 내용 위치 | 내용이 바 영역(상태 바 아래 44pt)부터 그린다 | 바가 안전 영역에 들어가므로 내용이 44pt 내려간다. 바 높이만큼 위로 올리는 방법은 U-1 |
| 탭 | 바가 내용 위에 있다 | 바가 앱 헤더의 버튼 탭을 가로채는지 U-2 |
| iOS 26 유리 효과 | 막대 항목에 유리 배경이 자동으로 붙는다 | 투명 back 버튼에 유리 배경이 보이는지 U-3 |

### 3-4. 공개 API 추가

| 이름 | 종류 | 내용 |
|---|---|---|
| `ScreenHeader` | enum | `standard`, `custom` |
| `WayOut` | 이미 공개 | 변경 없음 |
| `ScreenWayOut` | 읽기 전용 값 | 이 화면의 `wayOut`, `back()`, `close()`. iOS 환경값 / Android `CompositionLocal`. `FlowHost` 밖에서는 `none` |
| `WayOutButton` | 뷰 | `ScreenWayOut`을 읽어 뒤로 또는 닫기를 그린다. 최소 터치 영역(P21) 포함, 접근성 id `screen.back`·`screen.close` 유지 |

`ScreenChrome` 자체는 계속 internal이다. 앱이 `ScreenChrome`을 만들 수 없다는 규칙(두 번째 흐름 정의 방지)은 유지한다. 공개하는 것은 읽는 쪽뿐이다.

### 3-5. 없어지는 것

| 대상 | 이유 |
|---|---|
| `SwipeBack.swift` 전체 (`SwipeBackGesture`, `SwipeBackDelegate`, `Parallax` 이름 검색) | 바를 숨기지 않으므로 제스처가 기본 동작 |
| `HiddenNavigationBar` | 같음 |
| iOS `BackChevron`을 헤더에 그리는 코드 | `standard`는 시스템 back 버튼. `custom`의 `WayOutButton`이 모양으로만 씀 |

## 4. 케이스 표

| id | 플랫폼 | 모드 | 흐름 | 깊이 | 행동 | 기대 |
|---|---|---|---|---|---|---|
| C1 | iOS | standard | push | 2 | 가장자리 스와이프 | 한 단계 뒤로 (u7b 그대로) |
| C2 | iOS | standard | push | 2 | 콘텐츠 가운데 스와이프 (iOS 26) | 한 단계 뒤로 |
| C3 | iOS | standard | push | 1 (호스트 위) | 가장자리 스와이프 | 흐름 닫힘 (P32 셀 그대로) |
| C4 | iOS | custom | push | 2 | 가장자리 스와이프 | 한 단계 뒤로 |
| C5 | iOS | custom | push | 2 | 앱 헤더의 `WayOutButton` 탭 | 한 단계 뒤로 |
| C6 | iOS | custom | modal | 1 | `WayOutButton`(닫기) 탭 | 흐름 닫힘 |
| C7 | iOS | custom | push | 1 (흐름 루트, 호스트 없음) | 가장자리 스와이프 | 아무 일 없음. 스택이 깨지지 않음 |
| C8 | Android | standard | push | 2 | 시스템 back | 한 단계 뒤로 (지금과 같음) |
| C9 | Android | custom | push | 2 | 시스템 back, 예측형 back | 한 단계 뒤로 |
| C10 | Android | custom | modal | 1 | `WayOutButton`(닫기) | 흐름 닫힘 |
| C11 | 둘 다 | 둘 다 | — | — | 입력칸 밖 탭 | 키보드 닫힘 |
| C12 | 둘 다 | custom | — | — | 첫 렌더 | 내용 맨 위가 상태 바 바로 아래 (iOS는 U-1 결과에 따름). 겹치지 않음 |
| C13 | iOS | standard | — | — | 테마 주입 | 바의 제목 글꼴·색·배경이 테마 값 |

- 기존 기기 셀 중 `screen.back`·`screen.close` id를 쓰는 흐름 3개와 스와이프를 쓰는 흐름 3개(`examples/ui-spec/generated/flows/`)는 생성기에서 C1·C3·C5 기준으로 다시 만든다. iOS `standard`의 뒤로는 시스템 back 버튼이라 id 대신 접근성 라벨로 찾는다.

## 5. 확실하지 않은 것

| id | 사실 | 가장 싼 확인 방법 | 틀리면 |
|---|---|---|---|
| U-1 | 투명 바 아래 44pt에 내용을 올려 그릴 수 있는가 (상태 바 inset은 지키고 바 높이만 무시) | 시뮬레이터 프로브: `.ignoresSafeArea(.container, edges: .top)` + 상태 바 높이 패딩, 또는 `safeAreaInset(edge: .top)`에 앱 헤더를 두는 방법 비교 | 앱 헤더를 `safeAreaInset(edge: .top)`로 바 아래에 둔다 (44pt 아래로 내려간 채) |
| U-2 | 투명 바가 앱 헤더의 버튼 탭을 가로채는가 | 프로브: 바 영역 안의 앱 버튼 탭, 바 밖 버튼 탭 | 앱 헤더 버튼을 바의 툴바 항목(`ToolbarItem`)으로 넘긴다 |
| U-3 | iOS 26에서 투명 back 버튼에 유리 배경이 보이는가 | iOS 26.x 시뮬레이터 스크린샷 | `sharedBackgroundVisibility(.hidden)` 적용, 안 되면 back 버튼 대신 시스템 제스처만 두고 C5를 앱 버튼 → `flow.back()`으로 |
| U-4 | `standard`에서 시스템 바 글꼴을 주입된 테마의 폰트로 바꿀 수 있는가 (커스텀 폰트 등록 포함) | `UINavigationBarAppearance.titleTextAttributes`에 테마 폰트 적용 프로브 | 제목만 `ToolbarItem(placement: .principal)`에 `SpfnText`로 |
| U-5 | 호스트 스택(`NavigationHost`)에 올린 push 흐름에서 바 모드가 화면마다 바뀔 때 전환 중 깜빡임이 있는가 | standard → custom push 녹화 | 모드 전환 화면에 전환 애니메이션 없이 appearance 적용 |

## 6. 범위

| 포함 | 제외 |
|---|---|
| `Screen` 헤더 모드, iOS 시스템 바 사용, `SwipeBackGesture` 삭제, `ScreenWayOut`·`WayOutButton` 공개, 케이스 표 셀, P29 절 갱신(고른 처방 변경), architecture README "`Screen` owns the insets" 갱신 | 앱 쪽 레이아웃, Android 헤더 모양 변경, 새 흐름 종류, 탭 바 |

## 7. 기존 결정과의 관계

| 결정 | 영향 |
|---|---|
| N3 (뒤로는 왼쪽, 닫기는 오른쪽) | 유지 |
| S10 (최소 터치 영역·헤더 높이는 테마 밖) | Android `standard`는 유지. iOS `standard`는 시스템 바 높이를 쓴다 |
| P29 "고른 것: 첫 번째" | 두 번째(바를 비운다)로 바꾼다. 이유는 §1-2, §1-3 |
