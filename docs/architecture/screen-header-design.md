# 설계 — 화면 헤더는 플랫폼 기본을 쓴다

- 상태: 승인 (2026-09-25). 개정 2: iOS 시뮬레이터 확인(§5) 결과로 "투명하게 비운 바" 방식을 버리고 iOS는 시스템 내비게이션 바를 그대로 쓴다.
- 대상: `SPFNUI`(iOS)와 `spfn-ui`(Android)의 `Screen`, `FlowHost`, `SwipeBackGesture`
- 관련: [IMPLEMENTATION-PITFALLS.md](../IMPLEMENTATION-PITFALLS.md) P21·P25·P27·P29·P32, [architecture README](README.md) "`Screen` owns the insets"

## 0. 요약

| 항목 | 지금 | 바뀐 뒤 |
|---|---|---|
| iOS 헤더 | 시스템 바를 숨기고 SDK가 직접 그린다 | **시스템 내비게이션 바**. iOS 26에서는 유리 효과(Liquid Glass) 막대. 제목·글꼴·색은 주입된 테마 |
| iOS 뒤로 | SDK가 그린 화살표 + 숨긴 바의 제스처를 강제로 켠다 | 시스템 뒤로 버튼과 두 제스처(가장자리, 콘텐츠) 그대로 |
| iOS 헤더 항목 (건너뛰기, 닫기, 로고) | SDK 헤더의 좌우 칸 | 시스템 바의 툴바 항목 (`ToolbarItem`) |
| Android 헤더 | SDK가 직접 그린다 | 같음 (Material 없이 쓸 기본 앱 바가 없다). 앱이 헤더를 그리려면 SDK 헤더를 끌 수 있다 |
| 화면 동작 (안전 영역, 키보드) | `Screen` 안에서 헤더와 묶여 있다 | 헤더와 분리. 헤더가 무엇이든 같은 동작 |
| 뒤로·닫기 정보 | 내부 전용 (`ScreenChrome`) | 읽기 전용으로 공개 |
| `SwipeBackGesture` | 있음 | 삭제 |

## 1. 왜 바꾸는가

| # | 문제 | 근거 |
|---|---|---|
| 1 | 헤더를 뺄 수 없다. 앱이 자기 디자인 시스템으로 화면 위쪽을 그리면 SDK 헤더와 겹친다. `Screen`을 안 쓰면 키보드 처리·뒤로·닫기가 한꺼번에 빠진다 | `Screen`의 `title`이 필수, `ScreenChrome`이 internal |
| 2 | iOS 제스처 강제 활성화가 비공개 동작에 기댄다. 두 번째 제스처를 비공개 클래스 이름(`Parallax`)으로 찾는다 | `UINavigationController.h`(iOS 26 SDK): `interactiveContentPopGestureRecognizer`가 공개됐지만 두 속성 모두 "실패 조건 설정에만 쓰라"고 적혀 있다 |
| 3 | 직접 그린 헤더가 iOS 26의 기본 모양(유리 효과 막대)과 다르다 | §5 스크린샷 |

## 2. 원칙

| # | 원칙 |
|---|---|
| H1 | `Flow`는 흐름 상태만 가진다. 헤더 모양을 모른다 (지금과 같음) |
| H2 | 헤더와 뒤로가기는 플랫폼 기본을 쓴다. SDK는 제스처를 켜거나 끄지 않는다 |
| H3 | 앱은 헤더에 항목(로고, 건너뛰기, 닫기)을 넣을 수 있다. 헤더 자체를 새로 그리는 것은 Android에서만 가능하다 |
| H4 | 화면 동작(안전 영역, 키보드)은 헤더와 무관하게 같다 |
| H5 | 두 플랫폼이 맞추는 것은 규칙(N3: 쌓인 화면은 왼쪽 뒤로, 띄운 흐름의 첫 화면은 오른쪽 닫기)이다. 픽셀이 아니다 |

## 3. 설계

### 3-1. 헤더

| 항목 | iOS | Android |
|---|---|---|
| 막대 | 시스템 내비게이션 바. 숨기지 않는다 | SDK 헤더 (지금과 같음) |
| 제목 | `navigationTitle`. 없으면 비움. 글꼴·색은 테마 → `UINavigationBarAppearance.titleTextAttributes` | SDK 헤더 제목. 없으면 비움 |
| 뒤로 | 시스템 뒤로 버튼 | SDK 헤더 왼쪽 칸 (지금과 같음) |
| 닫기 (띄운 흐름 첫 화면) | 오른쪽 툴바 항목 | SDK 헤더 오른쪽 칸 (지금과 같음) |
| 앱 항목 | `leading`·`principal`·`trailing` 3칸 → `ToolbarItem(placement: .topBarLeading / .principal / .topBarTrailing)` | 같은 3칸 → SDK 헤더 왼쪽·가운데·오른쪽 칸 |
| 헤더 없이 (`header: .none`) | 지원하지 않는다. 막대는 항상 있다. 항목이 없으면 빈 막대는 공간을 차지하지 않는다 (§5 R5) | SDK 헤더를 그리지 않는다. 앱이 상태 바 아래부터 그린다 |

- 앱 항목의 왼쪽 칸은 쌓인 화면에서 시스템 뒤로 버튼 옆에 붙는다. 좁아서 로고가 잘린다 (§5 R7). 로고는 `principal`에 둔다.
- iOS 26에서 버튼 항목은 유리 캡슐 배경이 붙는다 (시스템 기본). 텍스트만 있는 `principal` 항목은 배경이 없다 (§5 R6).

### 3-2. 화면 동작 분리

| 동작 | 지금 | 바뀐 뒤 |
|---|---|---|
| 상단 inset | SDK 헤더가 상태 바 inset을 쓴다 | iOS: 시스템 바가 쓴다. Android: SDK 헤더가 쓴다. `header: .none`이면 내용이 받는다 |
| 하단 inset + 키보드 | 본문이 `ime ∪ navigationBars` | 같음 |
| 빈 곳 탭하면 키보드 닫기 | `Screen` 루트 | 같음 |
| 스크롤로 키보드 닫기 | `scroll = true`일 때 | 같음 |
| 시트 높이 측정 (P34) | 본문 | 같음 |

### 3-3. 공개 API

| 이름 | 종류 | 내용 |
|---|---|---|
| `Screen(title:leading:principal:trailing:scroll:content:)` | 변경 | `title` 선택 (기본 없음). `principal` 추가. iOS는 툴바 항목, Android는 SDK 헤더 칸 |
| `ScreenHeader.none` | Android 전용 옵션 | SDK 헤더를 그리지 않는다. iOS에는 없다 |
| `ScreenWayOut` | 읽기 전용 값 | 이 화면의 `wayOut`, `back()`, `close()`. iOS 환경값 / Android `CompositionLocal`. `FlowHost` 밖에서는 `none`. Android에서 `header: .none`인 화면이 자기 뒤로·닫기를 그릴 때 쓴다 |
| `WayOutButton` | Android 뷰 | `ScreenWayOut`을 읽어 뒤로 또는 닫기를 그린다. 최소 터치 영역(P21), 접근성 id `screen.back`·`screen.close` |

`ScreenChrome` 자체는 계속 internal이다. 공개하는 것은 읽는 쪽뿐이다.

### 3-4. 없어지는 것

| 대상 | 이유 |
|---|---|
| `SwipeBack.swift` 전체 | 바를 숨기지 않으므로 제스처가 기본 동작 |
| `HiddenNavigationBar` | 같음 |
| iOS SDK 헤더 (`header`, `control`, `BackChevron`·`CloseCross`를 그리는 코드) | 시스템 바와 툴바 항목 |

## 4. 케이스 표

| id | 플랫폼 | 흐름 | 깊이 | 헤더 | 행동 | 기대 |
|---|---|---|---|---|---|---|
| C1 | iOS | push | 2 | 제목 | 가장자리 스와이프 | 한 단계 뒤로 (u7b) |
| C2 | iOS | push | 2 | 제목 | 콘텐츠 스와이프 | 한 단계 뒤로 |
| C3 | iOS | push | 1 (호스트 위) | 제목 | 가장자리 스와이프 | 흐름 닫힘 (P32 셀) |
| C4 | iOS | push | 2 | 시스템 뒤로 버튼 | 탭 | 한 단계 뒤로 |
| C5 | iOS | modal | 1 | 닫기 항목 | 탭 | 흐름 닫힘 |
| C6 | iOS | push | 2 | `trailing` 버튼 | 탭 | 앱 동작 실행, 화면 그대로 |
| C7 | iOS | 흐름 첫 화면 | 1 | 항목 없음 | 첫 렌더 | 내용 맨 위가 상태 바 바로 아래 (빈 막대가 공간을 차지하지 않음) |
| C8 | iOS | — | — | 제목 | 테마 주입 | 제목 글꼴·색이 테마 값 |
| C9 | Android | push | 2 | SDK 헤더 | 시스템 back, 예측형 back | 한 단계 뒤로 (지금과 같음) |
| C10 | Android | push | 2 | `none` + `WayOutButton` | 탭, 시스템 back | 한 단계 뒤로 |
| C11 | Android | modal | 1 | `none` + `WayOutButton` | 탭 | 흐름 닫힘 |
| C12 | 둘 다 | — | — | — | 입력칸 밖 탭 | 키보드 닫힘 |

- 기존 기기 셀 중 `screen.back`·`screen.close` id를 쓰는 흐름 3개와 스와이프를 쓰는 흐름 3개(`examples/ui-spec/generated/flows/`)는 생성기에서 다시 만든다. iOS의 뒤로는 시스템 뒤로 버튼이라 id 대신 접근성 라벨로 찾는다.

## 5. 시뮬레이터 확인 (2026-09-25, iPhone 17 Pro, iOS 26 시뮬레이터, 프로브 앱 + Maestro)

| id | 시험 | 결과 |
|---|---|---|
| R1 | 바 유지, 뒤로 화살표를 빈 이미지로·글자 투명으로 | **뒤로 버튼이 유리 원형으로 그대로 보인다.** iOS 26은 화살표 이미지 설정을 무시한다. 스와이프 2가지는 동작 |
| R2 | 바 유지, `navigationBarBackButtonHidden(true)` | 가장자리·콘텐츠 스와이프 **모두 동작하지 않음** |
| R3 | 바 숨김 (`.toolbar(.hidden, for: .navigationBar)`), 강제 활성화 없이 | 스와이프 **모두 동작하지 않음** (P29 재확인) |
| R4 | 시스템 바 + 제목, `titleTextAttributes`에 다른 글꼴·색 | 적용됨 |
| R5 | 흐름 첫 화면, 바 유지·항목 없음 | 내용이 상태 바 바로 아래 (y 62). 빈 바가 공간을 차지하지 않음 |
| R6 | `principal`에 텍스트, `topBarTrailing`에 버튼 | 텍스트는 배경 없음, 버튼은 유리 캡슐. 탭 동작. 콘텐츠 스와이프 동작 |
| R7 | 쌓인 화면 `topBarLeading`에 로고 텍스트 | 시스템 뒤로 버튼 옆 유리 캡슐 안에서 잘림 ("L…") |

- 이 결과로 개정 1의 "투명하게 비운 바 + 앱 헤더" 방식(P29 두 번째 처방)은 iOS 26에서 성립하지 않는다 (R1·R2).

## 6. 확실하지 않은 것

| id | 사실 | 확인 방법 | 틀리면 |
|---|---|---|---|
| U-1 | 앱 번들에 등록한 커스텀 폰트도 `titleTextAttributes`로 적용되는가 | 테마 폰트로 R4 반복 | 제목을 `principal` 항목의 `SpfnText`로 |
| U-2 | 테마가 바뀌면(라이트·다크 전환) 막대 모양이 즉시 따라오는가 | 전환 중 스크린샷 | 화면 단위 `.toolbarBackground`·`.toolbarColorScheme` |
| U-3 | 호스트 스택에서 제목 있는 화면 ↔ 없는 화면 전환에 깜빡임이 있는가 | 전환 녹화 | 제목을 빈 문자열 대신 투명 색으로 |
| U-4 | iOS 17·18(유리 효과 이전)에서 같은 코드가 평범한 막대로 보이는가 | iOS 18 시뮬레이터 | 버전별 appearance 분기 |

## 7. 범위

| 포함 | 제외 |
|---|---|
| 헤더 항목 3칸, iOS 시스템 바 사용, `SwipeBackGesture`·iOS SDK 헤더 삭제, Android `header: .none`, `ScreenWayOut`·`WayOutButton` 공개, 케이스 표 셀, P29 절 갱신, architecture README "`Screen` owns the insets" 갱신 | 앱 쪽 레이아웃, Android 헤더 모양 변경, 새 흐름 종류, 탭 바 |

## 8. 기존 결정과의 관계

| 결정 | 영향 |
|---|---|
| N3 (뒤로는 왼쪽, 닫기는 오른쪽) | 유지 |
| S10 (최소 터치 영역·헤더 높이는 테마 밖) | Android 유지. iOS는 시스템 바 높이 |
| P29 "고른 것: 첫 번째" | 바를 숨기지 않는 것으로 바뀌어 처방 자체가 필요 없어진다. R1~R3을 P29에 기록 |
