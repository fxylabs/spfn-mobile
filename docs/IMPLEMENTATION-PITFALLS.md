# 구현 주의 패턴 등록부

이 저장소에서 **실제로 나온** 함정만 담는다. 프로젝트와 무관한 범용 패턴은
[coding-context](https://git.superfunction.xyz/spfn-core-projects/coding-context)로 가고,
여기 항목은 그쪽 페이지를 링크한다.

각 항목은 **증상 / 탐지 / 처방 / 나온 곳**이다. "이번 그 버그"가 아니라
"이런 모양이면 의심하라"로 쓴다.

## 쓰는 법

**등록할 때.** 리뷰가 재발 가능한 결함을 찾으면 항목으로 넣는다. predictable이었는지는
따지지 않는다 — novel이 곧 다음 번의 predictable이다. 넣기 전에 **이미 있는 항목인지
본다.** 있으면 새 항목을 만들지 말고 그 항목의 *탐지* 절을 강화한다. 중복된 항목 둘은
하나보다 나쁘다.

**참조할 때.** SDK 작업을 디스패치하기 전에 아래 표에서 건드리는 표면의 행을 찾아
해당 항목을 브리프에 같이 넣는다. 그게 전부다.

**측정할 때.** 세는 값은 하나다 — **이미 항목으로 있던 것을 구현자가 놓쳐 리뷰어가
지적한 횟수.** 그것이 0이 아니면 등록부가 읽히지 않았거나 항목이 찾기 어렵게 쓰였다는
뜻이고, 그것만이 이 문서의 실패다. 리뷰가 등록부에 없던 것을 찾는 것은 실패가 아니라
정상 동작이다.

**항목이 지켜야 할 것.** 저장소에 대한 사실을 주장하면 **그 사실을 확인하는 명령을
함께 적는다.** "digest는 세 곳에 있다" 같은 문장은 쓸 때는 맞아 보이고 시간이 지나면
조용히 틀린다. 브리프가 인용하므로 **틀린 항목은 없는 항목보다 나쁘다.**

## 트리거 → 항목

착수 전에 브리프가 건드리는 표면의 행을 찾아 해당 항목을 브리프에 인용한다.
결함 예상 섹션에서 각 항목을 어떻게 피했는지 답한다.

| 건드리는 것 | 봐야 할 항목 |
| --- | --- |
| 계약 번들 교체·재핀, `upstream.lock.json` 수정 | [P1](#p1) [P2](#p2) [P3](#p3) [P6](#p6) |
| `tools/validate/` 수정, 새 검사 추가 | [P4](#p4) [P5](#p5) [P6](#p6) [P7](#p7) |
| `tools/contract-codegen/` 수정, 계약 타입 문법 변화 | [P8](#p8) [P2](#p2) |
| `tools/ui-codegen/` 수정, 화면 스펙(`examples/ui-spec/*.json`) 작성·수정 | [P2](#p2) [P8](#p8) [P10](#p10) [P21](#p21) |
| Swift·Kotlin 대칭 로직 추가·수정 | [P9](#p9) [P10](#p10) [P15](#p15) |
| 플랫폼 콜백 API를 async/suspend로 감싸기, 제공자 어댑터 | [P16](#p16) [P15](#p15) |
| 공유 conformance 표·fixture 수정 | [P10](#p10) [P2](#p2) |
| 게이트·통합 매트릭스 실행 | [P11](#p11) [P12](#p12) |
| 버전 범위·호환성 규칙 수정 | [P3](#p3) [P13](#p13) |
| Android main 소스에 `java.*`/`javax.*` import 추가 | [P14](#p14) |
| Xcode 타깃에서 SwiftPM 패키지 트레이트 켜기 | [P17](#p17) |
| 테스트 더블에 키 id·별칭을 주입하고 두 번째 키를 만드는 흐름 | [P18](#p18) |
| 본문 없는(204) 응답을 서버에 추가 | [P19](#p19) |
| 플랫폼 `#if canImport(...)` 가드 추가, 한 플랫폼에서 모듈 비우기 | [P20](#p20) [P7](#p7) |
| 러너가 id로 탭하는 컨트롤 추가·수정 (Compose·SwiftUI 뷰) | [P21](#p21) |
| Maestro 플로우 생성·수정 | [P22](#p22) [P21](#p21) [P23](#p23) |
| 기기 러너의 증거 수집 시점 수정 (`run-cells.sh`, `run-harness.sh`) | [P23](#p23) [P7](#p7) [P12](#p12) |

---

## P1. 번들 필드는 upstream 소유다 {#p1}

**증상.** 소비 측 결함을 고치려고 `Contracts/spfn-mobile-contract.json`의 필드를
손보고 싶어진다. 고치는 순간 digest가 달라지고, 그 번들은 더 이상 upstream export가
아니다. validator가 잡지만, 잡히기 전까지 "왜 이 필드를 못 고치지"로 시간을 쓴다.

**탐지.** 고치려는 값이 번들·`upstream-provenance.json` 안에 있나. 있으면 그것은
계약의 주장이지 이 SDK의 동작이 아니다.

**처방.** 소비 측 결함은 **파생 필드**로 푼다. 번들 값은 그대로 두고 SDK가 실제로
하는 일을 계산해 별도 필드로 노출하고, 진단·문서가 그것을 인용하게 한다. 두 값이
같아야 하는 구간은 테스트로 고정한다.

**나온 곳.** cs-6jcny r3 — `supportedRange`가 pre-release 핀에서 SDK가 거부하는 창을
광고했다. 번들 필드라 못 고쳐 `admittedRange`를 도입했다.
범용: [declared-constraint-vs-enforced-rule](https://git.superfunction.xyz/spfn-core-projects/coding-context/src/branch/main/reliability/declared-constraint-vs-enforced-rule.md)

## P2. digest는 48개 파일에 있고 역할이 넷으로 갈린다 {#p2}

**증상.** 번들을 교체했는데 어딘가가 옛 digest를 들고 있어 빌드나 검증이 깨진다.
또는 더 나쁘게, 한 곳만 갱신되어 검사끼리 서로 다른 파일을 가리킨다.

**탐지.** `git grep -l <digest>`로 세는 것이 유일하게 정확하다. 오늘 기준 명령은 48개를
내고, 48개 모두가 역할을 지닌다. `CHANGELOG.md`도 산문 안에서 digest를 언급하지만
`29c26160…`처럼 축약하므로 이 명령에는 걸리지 않는다 — 그것은 역할이 아니라 기록이다.
개수를 외우지 말고 **역할**을 기억한다 — 넷은 서로 다르게 갱신되고, **손으로 고치는
자리는 둘뿐이며 나머지 46개는 손대는 순간 자기 생성기와 어긋난다.**

| 역할 | 파일 | 어떻게 갱신되나 | 확인 |
| --- | --- | --- | --- |
| 손으로 고정 | `Contracts/upstream.lock.json` (`contract.manifestSha256`) | 직접 편집하는 두 자리 중 하나이자, 다른 하나가 대조하는 기준 | 번들 파일의 실제 sha256과 같은지 재계산해 대조. validator 5절이 강제한다 |
| 손으로 고정 | `examples/ui-spec/device-approval.json` (`contract.manifestSha256`) | 화면 스펙을 쓴 사람이 적는다. 번들을 재핀하면 lock과 **함께** 고쳐야 하는 자리 | `:ui-codegen:spfnGenerateUi`가 번들 sha256을 재계산해 lock과 스펙 **둘 다**에 대조하고, 어느 쪽이 어긋나도 생성을 거부한다 |
| fixture 파생물 | `Contracts/fixtures/MANIFEST.json` (`bundleSha256`) | `derive-expected-values.py` 재실행. **손으로 고치지 않는다** | 파일 안 `derivedBy` 필드가 스스로 밝힌다 |
| codegen 산출물 | 생성 파일 10개 헤더 (Swift 5 + Kotlin 5) | codegen 재생성. **손으로 고치지 않는다** | `:contract-codegen:spfnCodegenVerify` |
| codegen 산출물 | `examples/` 아래 34개 — 두 앱의 `Generated`·`generated` 소스, case 표 둘, Maestro flow 14개 | `:ui-codegen:spfnGenerateUi` 재실행. **손으로 고치지 않는다** | `:ui-codegen:spfnUiVerify` (`check`에 물려 있다) |
| upstream 제공 | `Contracts/upstream-provenance.json` (`bundleSha256`) | 새 번들과 함께 도착한다. 갱신 대상이 아니라 **대조 대상** | validator 5절이 lock과 필드 단위로 맞춰 본다 |

**처방.** 번들 교체는 lock 직접 편집 → **화면 스펙의 `contract.manifestSha256`도 같은
값으로 편집** → `derive-expected-values.py` 재실행 → codegen 재생성 → `ui-codegen` 재생성
→ 결정성 확인이 한 묶음이다. 스펙을 빼먹으면 `spfnGenerateUi`가 거부하므로 조용히
틀리지는 않지만, 거부 메시지를 "생성기가 깨졌다"로 읽으면 시간을 쓴다. 나머지 셋 중
둘(MANIFEST, 생성 파일 44개)은 이 저장소의 **파생물**이라 손으로 편집하면 자기 생성기와
어긋난다. 네 번째는 파생물이
아니라 **upstream이 발행한 증거**다 — 우리 도구 중 무엇도 그것을 쓰지 않는다. 그것을
"우리가 갱신할 것"으로 착각하면 evidence를 lock에 맞춰 편집하게 되는데, 그것은
provenance 게이트가 정확히 잡으려는 행위다([P1](#p1)).

**검증.** `git grep -l <digest> | wc -l`이 48이고, 그중 `upstream-provenance.json`이
포함돼 있으며 그 값이 lock과 같은지 본다. 개수가 늘었다면 새 소비처가 생긴 것이니
**그 파일이 스스로 파생물임을 밝히는지**(`derivedBy` 류 필드, 생성기 헤더) 먼저 보고
역할을 판정해 이 표에 추가한다.

**갱신.** w-0j1z8이 연산별 호출 서술자 파일(`SPFNGeneratedCalls.swift` /
`SpfnGeneratedCalls.kt`)을 추가하면서 생성 파일이 8개에서 10개가 됐다. 확인은
`git grep -l <digest> | wc -l`로 했고 12에서 14로 늘었다 — 새 헤더 둘, 그리고
`CHANGELOG.md`의 산문 언급 하나는 그대로다. 이 항목이 세 번째로 틀렸던 자리도 여기다:
"11"은 `CHANGELOG.md`를 빼고 센 수였는데 검증 문장은 명령의 출력과 비교하라고 적혀
있었다. 이제 둘을 나눠 적는다.

**갱신.** w-w823n이 `tools/ui-codegen`을 추가하면서 소비처가 13개에서 48개로 늘었다.
늘어난 35개 중 34개는 파생물이라 헤더가 스스로 그렇게 밝히지만, 나머지 하나
(`examples/ui-spec/device-approval.json`)는 **사람이 적는 두 번째 자리**다. 이 항목이
"손으로 고치는 것은 하나뿐"이라고 단정해 온 문장이 그래서 더는 참이 아니다 — 소비처가
늘 때 파생물인지만 묻고 **사람이 적는 자리인지**를 묻지 않으면 이런 항목은 조용히
틀린 채 남는다.

**나온 곳.** cs-6jcny — dev bundle → upstream export 전환. 항목 자체는 두 번 틀렸다.
cs-mzv14 r1이 "세 곳"을 잡았고(`upstream-provenance.json` 누락), r2가 그 수정을 다시
잡았다 — `MANIFEST.json`을 손으로 갱신하는 파일로 분류했으나 실제로는
`derive-expected-values.py`의 산출물이다. 손으로 고치는 자리는 lock 하나뿐이다.

## P3. 0.x 계약 라인에서 파괴 축은 minor다 {#p3}

**증상.** major만 비교하는 호환성 검사가 1.x에서는 옳고 0.x에서는 틀리다. 계약이
`1.0.0-dev.1`이던 동안 전 스위트가 통과했고, `0.1.0`으로 내려오자 `0.2.0` 서버가
자기 range가 배제하는데도 통과했다.

**탐지.** 버전 비교 코드의 비교 축 개수가 선언된 range의 경계 개수와 같은가.
`>=x <y`는 경계 2개다. 비교문이 1개면 적중.

**처방.** 상·하한을 모두 강제하고, upper bound는 선언 문자열을 파싱하지 말고
핀에서 파생한다. 벡터 표에 0.x 경계 케이스를 반드시 넣는다.

**나온 곳.** cs-6jcny r1.
범용: [declared-constraint-vs-enforced-rule](https://git.superfunction.xyz/spfn-core-projects/coding-context/src/branch/main/reliability/declared-constraint-vs-enforced-rule.md)

## P4. validate.sh의 의존성 한계 {#p4}

**증상.** 새 검사를 python3나 jq로 짜고 싶어진다. 스크립트 헤더가 선언한
"POSIX sh, grep, sed, awk, find, sha256 유틸 외 의존성 없음"을 깬다.

**탐지.** 추가하는 명령이 위 목록에 있나. `jq`, `python3`, `xargs -0`, `readarray`,
`read -d`는 전부 밖이다.

**처방.** awk로 푼다. JSON 구조 계수는 배열 문맥을 추적하는 awk 패스로 가능하다.
정말 안 되면 검사를 validate.sh가 아니라 플랫폼 테스트 스위트에 둔다.

**나온 곳.** cs-6jcny r3 — 공유 벡터 표의 엔트리 계수.

## P5. `json_string`류는 임의 깊이 첫 히트를 취한다 {#p5}

**증상.** lock에 같은 이름 키가 둘 생기면 validator가 의도하지 않은 쪽을 읽는다.
에러가 아니라 **다른 파일을 digest**하는 형태로 나타난다.

**탐지.** validator가 읽는 키 이름을 전부 나열하고, 그 이름이 파일 안에 2번 이상
나오는지, 또는 다른 키의 부분 문자열인지 본다. 새 키를 lock에 추가할 때도 같은 질문.

**처방.** 키 이름을 유일하게 짓는다(`bundlePath` → `upstreamBundlePath`). 이유를
파일 안에 적어 다음 사람이 반복하지 않게 한다.

**나온 곳.** cs-6jcny — lock에 `bundlePath`가 둘이 되어 upstream 경로를 digest했다.

## P6. 검사를 상태 분기 안에 두면 전환 시 사라진다 {#p6}

**증상.** digest·fixture 검사가 `RESOLVED_DEV_BUNDLE` 분기 안에만 있었다. lock을
`RESOLVED_UPSTREAM`으로 옮기면 그 검사들이 **한마디 없이** 전부 빠진다.

**탐지.** 검사가 `case`/`if` 분기 안에 있으면 "다른 분기로 갔을 때 이 검사는 어떻게
되나"를 묻는다. 답이 "사라진다"인데 그 검사가 분기와 무관한 의무라면 적중.

**처방.** 분기와 무관한 의무는 공통 블록으로 뺀다. 분기 변수는 "어느 쪽이 돌았나"만
기록하고 "통과했나"는 담지 않는다 — 한 항목의 실패가 다음 검사를 억누르면 안 된다.

**나온 곳.** cs-6jcny.

## P7. 검사가 "못 돌았음"과 "이상 없음"을 구분하는가 {#p7}

**증상.** 스캔형 검사가 빈 결과를 통과로 읽는다. 읽을 수 없는 파일, 열거 실패,
개행 든 경로에서 아무것도 검사하지 않고 초록이 된다.

**탐지.** 검사의 성공 조건이 빈 결과인가. `|| true`·`2>/dev/null`이 검사 경로에 있나.
몇 건을 훑었는지 보고하나.

**처방.** 열거와 검사를 분리하고, 훑은 개수를 세고 하한을 두고, 통과 메시지에 개수를
싣는다. **검사를 실행 불가능하게 만들었을 때 붉게 실패하는지 probe한다** — "무는가"만
확인하고 끝내면 이 패턴을 그대로 통과시킨다.

**나온 곳.** cs-6jcny r5·r6 — 리뷰 finding을 자동 검사로 승격했더니 그 검사가
fail-open이었고, 다음 라운드에 개행 파일명을 건너뛰었다.
범용: [empty-scan-result-reads-as-clean](https://git.superfunction.xyz/spfn-core-projects/coding-context/src/branch/main/reliability/empty-scan-result-reads-as-clean.md)

## P8. codegen은 실패하지 않고 잘못된 이름을 찍는다 {#p8}

**증상.** 계약의 타입 문법이 `array<Item>`에서 `Item[]`로 바뀌자, `Bundle.kt`의 파서가
`array<` 접두사만 인식해 `Item[]`이 else 분기로 떨어졌다. 생성기는 **에러 없이**
`Item[]`이라는 이름의 타입을 양 플랫폼에 찍고, 컴파일에서야 깨진다.

**탐지.** 파서의 else 분기가 "알 수 없는 것을 이름으로 취급"하는가. 계약 표면의 문법
변화(선언되지 않은 것 포함)를 만나면 생성기가 거부하는지 통과시키는지 확인한다.

**탐지(2) — 타입별 분기가 `optional`을 잊는 경우.** emitter의 디코드 분기는 타입마다
하나씩인데, 그중 하나가 `field.optional`을 읽지 않아도 **컴파일은 통과한다.** 계약에
그 타입의 optional 필드가 아직 없으면 잠복하고, 처음 생기는 순간 "서버가 보내지 않은
필드"가 `MISSING_FIELD`로 터진다. 확인 명령:

```
# 디코드 분기 중 optional을 보지 않는 것을 센다. 0이어야 한다.
grep -n 'is FieldType\.' tools/contract-codegen/src/main/kotlin/xyz/superfunction/spfn/codegen/*Emitter.kt
```

분기마다 `field.optional`이 있는지 눈으로 대조하고, 없다면 계약에 그 조합이 없을
뿐인지 확인한다. 증거: w-253gk — `BooleanType` 분기만 `optional`을 무시했고, 계약
0.10.0이 처음으로 optional boolean(`PollDeviceAuthResponse.passwordChangeRequired`)을
들여오자 `pending` 응답이 **어느 플랫폼에서도 디코드되지 않았다.** 잠복 필드가 둘
더 있었다(`ListKeysRequest.includeRevoked`, `RevokeAllKeysRequest.includeCurrent`).

**처방.** 계약 문법 변화는 선언된 변경으로 다뤄 파서 변경 + 양 플랫폼 재생성 +
결정성 재확인을 함께 한다. upstream 쪽 변화는 소비 전에 대조한다.

**나온 곳.** primitives export 검증(b8e3d2f) — 재핀 전에 blocking으로 보고해 해소.

## P9. Swift·Kotlin 문자 분류가 non-ASCII에서 갈린다 {#p9}

**증상.** 같은 규칙을 두 언어로 쓸 때 `Character.isNumber`/`isLetter`와
`it in '0'..'9'`/`isDigit()`가 같아 보이지만 아니다. Swift의 `isNumber`는 위첨자·
전각·아라비아-인도 숫자를 받고, Kotlin의 명시적 범위는 받지 않는다.

**탐지.** 문자 분류 API를 쓰는 대칭 코드를 양쪽 나란히 놓고 비교한다. 한쪽이 유니코드
인식이고 다른 쪽이 ASCII 범위면 적중. 두 플랫폼의 불일치는 **blocking**이다 — 서로의
검사가 되라고 두 구현을 두는 것이다.

**처방.** ASCII 가드를 명시적으로 건다(`$0.isASCII && ...`). 공유 벡터 표에 non-ASCII
케이스를 넣어 갈림이 실서버가 아니라 그 표에서 드러나게 한다.

**나온 곳.** cs-6jcny r2·r3 — SemVer 파서 양 플랫폼 대조.

## P10. 공유 표는 구현에서 파생하면 무효다 {#p10}

**증상.** 크로스 플랫폼 벡터 표를 구현에서 뽑거나, "예전 규칙"을 커밋이 아니라
기억으로 재구현해 표의 판별력을 증명한다. 둘 다 초록이고 둘 다 증거가 아니다.

**탐지.** 표가 어떻게 만들어졌는지 파일에 적혀 있나. 표의 판별력은 **틀린 규칙을
넣었을 때 실패 건수**로 잰다 — 0이나 1~2건이면 표가 구현을 따라 쓴 것이다.
"예전에는 이랬다"를 주장하는 코드는 `git show <base>:<path>`로 대조한다.

**처방.** 기대값은 계약·표준에서 손으로 쓰고 그 사실을 파일에 적는다. 교체된 규칙을
테스트가 함께 실행해 표가 잡는지 assert하고, 불일치 0이면 실패시킨다.

**나온 곳.** cs-6jcny r7 — probe의 "옛 규칙"이 base가 아니라 리뷰 라운드 사이의
중간 형태였다. base 규칙으로 교체하니 판별력이 2/41 → 23/41로 올랐다.
범용: [test-oracle-derived-from-subject](https://git.superfunction.xyz/spfn-core-projects/coding-context/src/branch/main/maintainability/test-oracle-derived-from-subject.md)

## P11. 통합 매트릭스 external 모드 {#p11}

**증상.** primitives dev 서버를 띄우고 끝낸 뒤 다음 기동이 실패한다. tsx가 띄운
자식 node가 부모 종료 후에도 살아남아 8791 포트를 물고 있다.

**탐지·처방.**

```
# 서버: spfn/examples/04-mobile-contract-dev
SPFN_CLIENT_PROOF_LAUNCH_FILE=<path> pnpm dev
# 러너
SPFN_INTEGRATION_LAUNCH_FILE=<launch.json> sh tools/reference-server/run-integration.sh
```

끝나면 **반드시** 8791 리스너를 직접 확인하고 종료한다(`lsof -i :8791`). 러너는
조용히 로컬로 넘어가지 않고 정직하게 실패한다(설계대로다).

wire 경로를 건드리지 않은 change set에서는 재실행이 선택이다. 다만 리뷰어에게 그
판단을 명시적으로 묻는다.

**나온 곳.** cs-6jcny 전 구간.

## P12. 게이트 실행의 함정 {#p12}

- **Gradle은 `ANDROID_HOME`이 필요하다** (`~/Library/Android/sdk`). attempt 브리프의
  boundary env에 넣는다.
- **`validate.sh`를 파이프하면 exit code를 삼킨다.** 파일로 리다이렉트하고 `$?`를 읽는다.
- **probe 편의로 뚫어둔 env 오버라이드가 발행 경로에서는 우회로다.** 게이트에
  `SPFN_*_ROOT` 류 오버라이드를 두면 probe는 픽스처를 가리킬 수 있지만, 같은 변수를
  셸에 export한 채 발행을 돌리면 손으로 쓴 디렉터리가 증거 행세를 한다. 탐지: 게이트
  스크립트가 `${SPFN_...:-<기본값>}` 꼴로 경로·핀을 받는가, 그 변수를 호출자가
  지우는가. 처방: 발행 경로(rc-verify)에서 호출 직전 `unset`하고, 그 `unset` 줄 자체를
  validate의 고정 문자열 검사로 박는다. 증거:
  `tools/device-receipts/receipt-gate.sh`(오버라이드 보유) +
  `tools/rc-verify/rc-verify.sh`의 `unset SPFN_RECEIPT_ROOT SPFN_RECEIPT_LOCK`.
- **probe 전에 `cp`로 사본을 뜬다.** `git checkout --`는 HEAD에서 복원해 미커밋 작업을
  먹는다. 전역 훅이 막고 있지만 습관이 먼저다.
- **Kotlin 테스트 결과는 `testDebugUnitTest` 변형에 있다.** `:spfn-core:test`가 아니라
  `:spfn-core:testDebugUnitTest --rerun-tasks`로 돌리고
  `build/test-results/testDebugUnitTest/`를 읽는다.
- **신호 trap 안의 `$?`는 인터럽트가 아니라 마지막 완료 명령의 상태다.** `trap cleanup
  EXIT INT TERM` 한 줄로 합치면 SIGINT/SIGTERM으로 죽은 실행이 정리는 다 하고 exit 0으로
  끝난다. 탐지: trap 목록에 신호가 EXIT와 같은 핸들러를 공유하면서 핸들러가 `exit "$status"`
  꼴이면 의심. 처방: 신호는 전용 핸들러에서 `trap '' EXIT INT TERM`으로 먼저 무장해제하고
  128+N(INT 130, TERM 143)으로 직접 exit. 증거: `tools/rc-verify/probe-trap-exit.sh`가
  태그 생성 후 kill을 실측한다.
- **서명 실행의 .asc 잔존을 Gradle 데몬 탓으로 오진하지 말 것.** Gradle 9.5.1의
  `providers.gradleProperty`는 호출별 클라이언트 env를 읽고, 데몬은 키를 붙잡지
  않는다 — 통제 재현: 키 주입 publish(staging .asc 24) 직후 같은 데몬(PID 집합 동일,
  로그에 "Starting a Gradle Daemon" 0회)에서 키 없는 publish → staging .asc 0.
  실제로 남는 함정은 두 가지다. (1) 서명 실행이 `android/*/build/` 아래 .asc 24개를
  남기고 이후 무서명 실행에서도 그대로 잔존한다(staging으로는 안 간다). (2)
  `ORG_GRADLE_PROJECT_*`가 셸에 남아 있으면 무서명이라 믿은 실행이 실제로는 서명된다.
  확인 명령: `env | grep -c ORG_GRADLE_PROJECT`(무서명 실행 전 0 확인),
  `find android -path '*/build/*' -name '*.asc' | wc -l`(서명 실행 뒤 잔존 확인),
  `./gradlew --status`와 실행 로그의 "Starting a Gradle Daemon" 카운트(데몬 동일성).
  처방: 키 주입 실행 뒤 build/ 아래 .asc를 지운다. rc-verify는 키 없는 실행의
  staging에 .asc가 있으면 FAIL로 잡는다.

## P13. 규칙을 조이면 그 규칙을 광고하는 문자열도 본다 {#p13}

**증상.** 라운드 1이 "검사가 출력 문자열보다 느슨하다"였고, 그 수정이 라운드 3에서
"검사가 문자열보다 엄격하다"가 됐다. 같은 필드를 두 번 고치며 반대 방향으로 두 번
틀렸다.

**탐지.** 비교 규칙을 바꾸는 diff에서 그 규칙을 인용하는 곳을 전부 나열한다 —
range 문자열, 에러 메시지, doc comment, COMPATIBILITY.md 행, 생성 코드.
직렬화 포맷·DSL(YAML·Gradle DSL)을 향한 검사를 신설·확장할 때는 표기 변형을 probe에
반드시 심는다 — flow/block 스타일, quoted 키, 리스트 항목 접두(`- uses:`),
호출형/대입형, **블록 여는 줄에 같이 쓴 항목**(`dependencies { implementation(...) }`).
이 클래스는 한 PR 사이클에서 3회 재발했다: F-A(flow-style `on:` 트리거
통과) → DF-2(quoted `"push":` skip) → `- uses:`(step 첫 키 관용형이 앵커 추출을 우회).
w-6m8dz에서 4번째로 나왔다: Gradle 의존성 검사가 `^[[:space:]]*(api|implementation)\(`로
줄을 앵커해 읽어서, 블록 여는 줄에 함께 쓴 의존성이 통과했다. **줄이 아니라 출현을
읽는다** — 앵커를 버리고 `grep -oE`로 모든 출현을 뽑아 판정한다.
일반형 패턴은 coding-context `reliability/denylist-notation-bypass`.

**처방.** 규칙 변경과 그것을 서술하는 문자열 변경을 같은 커밋에 넣는다. 파생 불가면
[P1](#p1)의 파생 필드 처방을 쓴다.

**나온 곳.** cs-6jcny r1 → r3.

## P14. JDK API의 API-level 하한은 JVM 단위 테스트가 못 잡는다 {#p14}

**증상.** Android main 소스에 `java.util.Base64` 같은 JDK 클래스를 쓰면 JVM 단위
테스트는 호스트 JDK에서 돌아 전부 통과하지만, 그 클래스의 Android 도입 API level이
minSdk(24)보다 높으면 실기기에서 크래시한다. lint(NewApi)가 잡는데, lint는
`./gradlew build` 전체 실행에서만 돈다 — `test`·`testDebugUnitTest`만 돌린 게이트는
통과한 것처럼 보인다.

**탐지.** Android main 소스에 `java.*`·`javax.*` import를 추가하는 diff면 그 클래스의
"Added in API level"을 확인한다 (developer.android.com 레퍼런스 하단). 확인 명령:
`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew build` — lint 포함 전체가 게이트다
([P12](#p12)의 ANDROID_HOME 함정과 겹친다).

**처방.** API level 하한이 없는 대체를 먼저 찾는다 — base64는
`kotlin.io.encoding.Base64`(stdlib, Kotlin 2.0+ stable)가 `java.util.Base64.getEncoder()`와
같은 RFC 4648 padded 출력을 낸다. 대체가 없으면 desugaring이 아니라 minSdk 상향을
별도 결정으로 올린다.

**나온 곳.** PR #18이 넣은 `SpfnKeyLifecycle.kt`의 `java.util.Base64`(API 26+, minSdk 24)
— enrollment·rotation 본문 인코딩이 API 24/25에서 크래시하는 결함. alpha.3 릴리스
change set에서 발견·수정.

## P15. 같은 규칙이라도 두 언어의 강제력이 다르다 {#p15}

**증상.** Swift에서 막은 것을 Kotlin에 "같은 모양"으로 옮기고 대칭이 됐다고 본다.
모양은 같고 강제력은 다르다. `package` 가시성은 패키지 밖에서 이름 자체를 지우지만,
`@RequiresOptIn`은 Kotlin 컴파일러 규칙이라 Java 호출자에게는 아무 게이트가 아니다.
수명도 갈린다 — 실패한 등록에서 Swift는 메모리 값 하나를 버리면 되고, Android는 이미
Keystore에 별칭이 있어 지우지 않으면 고아로 남는다.

**탐지.** 대칭 코드에서 "앱이 이것을 할 수 없다"를 주장하면, 그 주장을 **각 플랫폼의
소비 언어마다** 따로 확인한다. Android 모듈은 Java에서도 소비된다. 확인 명령:

```
# 접근자가 Java 시야에서 지워졌는지는 클래스 플래그로 판정한다.
# -v가 필요하다 — javap -p는 synthetic 메서드도 평범한 public으로 찍는다.
javap -v -p -cp android/spfn-client/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes \
  xyz.superfunction.spfn.client.SpfnSocialNonce | grep -A3 'getRawValue()'
# 기대: flags 줄에 ACC_SYNTHETIC. 없으면 Java에서 그냥 읽힌다.
```

정리(cleanup) 블록이면 **성공 경로의 마지막 부수효과까지** 감싸는지 본다. 서버가 이미
받아들인 뒤에 던지는 구간이 블록 밖에 있으면 적중.

**처방.** Kotlin 쪽은 opt-in에 `@get:JvmSynthetic`을 겹쳐 두 언어가 같은 접근을
거부하게 한다. 플랫폼이 요구하는 타입은 넓은 상위 타입(`Context`)이 아니라 실제로
동작하는 타입(`Activity`)으로 받는다 — 컴파일되고 사용자가 보는 순간 실패하는 인자는
값 타입으로 막는 것과 같은 종류의 결함이다. 수명이 갈리는 자원은 대칭을 코드 모양이
아니라 **무엇이 남는가**로 맞춘다.

**나온 곳.** PR #24 fresh 리뷰 — `SpfnSocialNonce.rawValue`가 Java에서 그냥 읽혔고
(승인된 결정 "앱은 raw를 읽거나 보낼 수 없다"가 Android에서 성립하지 않았다),
`SpfnKeyLifecycle.enroll`의 `store.save`가 정리 블록 밖이라 저장 실패가 Keystore
별칭을 고아로 남겼으며, Credential Manager 드라이버가 `Context`를 받아 application
context로도 컴파일됐다.

## P16. 취소는 실패가 아니다 {#p16}

**증상.** 어댑터가 `catch (Throwable)`/`catch`로 전부 받아 제 에러 타입으로 분류한다.
그 그물에 취소가 같이 걸린다. Kotlin은 `CancellationException`을 던져 코루틴을
취소하므로 호출자 스코프는 취소된 적 없다고 믿고, Swift에서는 `CancellationError`가
`NSError`로 읽혀 `code: 0`짜리 "제공자 거부"가 된다. 사용자가 겪은 적 없는 실패다.

반대편도 있다. 플랫폼 콜백 API를 `withCheckedContinuation`으로 감쌀 때, 시트를 닫는
것은 델리게이트 콜백으로 오지만 **태스크 취소는 아무것도 부르지 않는다.** 핸들러가
없으면 continuation은 프로세스가 끝날 때까지 suspend로 남는다.

**탐지.** 어댑터의 catch 절이 취소 타입보다 먼저 넓게 잡는지 본다. `withCheckedContinuation`
계열이면 `withTaskCancellationHandler`가 같이 있는지, 컨트롤러를 취소할 수 있게
보관하는지 본다. 제공자의 "사용자가 닫음" 예외와 언어의 취소 예외는 **다른 타입**이고
둘 다 "cancelled"로 읽혀 한쪽만 처리하기 쉽다.

**처방.** 취소는 분류하지 말고 그대로 다시 던진다(Kotlin은 `classify`의 첫 분기,
Swift는 `catch let … as CancellationError`). continuation은 취소 핸들러에서 먼저
resume하고 그 다음 플랫폼 컨트롤러를 취소한다 — 순서가 반대면 콜백이 먼저 도착해
취소가 제공자 거부로 보고된다.

**나온 곳.** PR #24 fresh 리뷰 — Google 어댑터가 `CancellationException`을
`Failed(SIGN_IN_FAILED)`로 바꿨고, Apple 세션에는 취소 핸들러가 없어 태스크 취소가
continuation과 세션을 영구히 붙잡았다. 범용 축은 coding-context 후보.

## P17. Xcode는 패키지 트레이트를 켜지 못한다 {#p17}

**증상.** Xcode 타깃에서 트레이트 게이트된 API를 쓰려고 XcodeGen `packages:`에 `traits:`를
적는다. XcodeGen은 `XCLocalSwiftPackageReference`에 `traits = (...)`를 정직하게 써 넣고,
Xcode 26.2는 그 키를 **무시한다.** 실패는 조용하다 — 빌드가 "트레이트를 못 켠다"고 말하는
대신 `#if <트레이트>` 블록 안의 타입을 "cannot find type ... in scope"로 보고하고,
트레이트 뒤의 원격 의존성은 아예 해석되지 않는다. 원인이 트레이트라는 단서가 에러에 없다.

**탐지.** 트레이트 게이트된 심볼이 "scope에 없다"고 나오면 먼저 해석 목록을 본다.
확인 명령:

```
xcodebuild -project <proj> -resolvePackageDependencies -scheme <scheme> 2>&1 \
  | grep 'resolved source packages'
```

트레이트 뒤에 있는 원격 패키지가 그 줄에 없으면 트레이트가 꺼진 것이다. `.xcodeproj`에
`traits = (...)`가 **있는데도** 없으면 적중.

**처방.** 트레이트는 매니페스트만 켤 수 있다. 앱 타깃과 SDK 사이에 매니페스트 하나짜리
패키지를 두고 거기서 `.package(name:path:traits:)`로 의존한다. 그 패키지를 그래프에 넣으면
같은 그래프 안의 모든 사본에 트레이트가 켜진다 — 앱은 SDK 프로덕트를 계속 직접 참조해도
된다. `name:`을 빼지 말 것: path 의존성의 identity는 **디렉터리 이름**이라 worktree처럼
브랜치 이름이 붙은 체크아웃에서 `.product(package:)`가 깨진다.

**나온 곳.** w-9jqtj iOS — 하네스가 실기기에서 Google 시트를 띄우려면 `SocialGoogle`이
필요했다. 프로브 프로젝트로 실측: `traits:`만 쓴 쪽은 원격 패키지 0개에
`SPFNGooglePresentingContext` 미해결, 매니페스트 쪽은 GoogleSignIn 9.2.0 해석 + 빌드 성공.
실물은 `tools/harness/ios/HarnessSupport/Package.swift`.

## P18. 테스트가 키 id를 고정하면 두 번째 키가 첫 번째를 덮는다 {#p18}

**증상.** 스위트가 `newKeyId`/`makeKey`를 주입해 키 id를 고정한다. 그 흐름이 키를
**두 번** 만들면(등록 후 rotate) 두 번째 생성이 같은 id → 같은 별칭으로 들어가
첫 번째 키를 덮어쓴다. 그 다음 첫 번째 키로 서명한 proof는 새 개인키로 서명되어
서버가 `PROOF_INVALID`로 거절한다. 증상이 SDK 결함처럼 보이는 것이 함정의 전부다.

**탐지.** 주입한 id 공급자가 **상수를 반환**하는가. 그 케이스가 키를 두 번 이상
만드는가(enroll+rotate, device-code+rotate). 저장소·엔진이 id에서 별칭을 파생하는가
(Android: `spfn-client-key-$keyId`). 셋 다 예이면 적중.

**처방.** 공급자는 큐로 만든다 — 첫 번째만 고정 id, 이후는 매번 새 값. 케이스가
말하고 싶은 것은 "파킹한 키"이지 "모든 키"가 아니다.

**나온 곳.** w-253gk 통합 케이스 g — device-code 승인 후 `rotate()`가
`PROOF_INVALID`로 실패했다. 서버도 SDK도 옳았고, 틀린 것은 상수 `newKeyId`였다.

## P19. `sendResponseHeaders(code, 0)`은 본문 없음이 아니다 {#p19}

**증상.** 계약이 "204 + 빈 본문"을 요구하는 오퍼레이션을 서버에 추가하고
`exchange.sendResponseHeaders(204, body.size.toLong())`로 답한다. `body.size`가 0이면
`com.sun.net.httpserver`는 **chunked, 길이 미상**으로 읽는다 — 즉 본문이 있다는 뜻이고,
클라이언트의 no-response 판독기는 이를 정확히 거부한다(`bodyOnNoResponseOperation`).

**탐지.** 응답 작성 지점에 `sendResponseHeaders(status, length)`가 있고 length가
`body.size`인가. 빈 본문이 그 경로로 갈 수 있는가. 그렇다면 적중. 계약 쪽 짝은
**id가 아니라 디스크립터**로 판정하는지 함께 본다(`declaresResponse`).

**처방.** 빈 본문은 `-1`(본문 없음)로 보내고, `content-type`도 붙이지 않는다. 단
계약 버전 announcement 헤더는 204에도 붙인다 — 클라이언트는 상태 코드보다 먼저 그것을
읽으므로, 빠지면 유일하게 잘못된 이유로 거절되는 응답이 된다.

**나온 곳.** w-253gk — `auth.device.deny`가 이 저장소 서버가 처음으로 답하는 본문 없는
오퍼레이션이다. 통합 케이스 h가 end-to-end로 고정한다.

## P20. `canImport(UIKit)`은 macOS에서도 거짓이다 {#p20}

**증상.** Linux에서 빼려는 모듈을 파일 통째로 `#if canImport(UIKit)`으로 감싼다. Linux
에서는 의도대로 타깃이 빈 모듈로 컴파일된다 — **그리고 macOS에서도 그렇게 된다.** macOS
에는 UIKit이 없고 AppKit이 있기 때문이다. 실패는 조용하다: 에러도 경고도 없이 macOS
`swift test`의 실행 개수만 줄어든다. Linux 게이트는 초록이고 "Linux에서 비웠다"는 보고와
완전히 일치하므로, 무엇도 macOS를 가리키지 않는다. UIKit뿐이 아니다 — `Darwin`,
`AuthenticationServices`, `Security`는 애플 전 플랫폼에 있고 `UIKit`·`AppKit`·
`WatchKit`은 그중 일부에만 있다. "애플 전용 프레임워크"와 "Linux에 없는 프레임워크"는
같은 집합이 아니다.

**탐지.** 가드를 붙이기 전과 후의 **macOS 테스트 개수를 비교한다.** 줄었으면 적중.

```
swift test 2>&1 | grep -E 'Executed [0-9]+ tests' | tail -1
```

그리고 가드로 고른 프레임워크가 그 파일의 **모든 `#if` 분기**를 덮는지 본다. `#else`에
`import AppKit`이 있는 파일은 UIKit 파일이 아니다.

```
grep -rn 'import ' Sources/<Target> Tests/<Target>Tests | grep -v '^.*://' | sort -u
```

**처방.** 파일이 실제로 쓰는 프레임워크들의 **합집합**으로 가드한다 —
`#if canImport(UIKit) || canImport(AppKit)`. 또는 애플 전 플랫폼에 정말로 있는 것
(`AuthenticationServices`, `Security`, `Darwin`)을 고른다. 어느 쪽이든 두 플랫폼에서
개수를 다시 센다: 한쪽 플랫폼의 행을 떨어뜨리는 가드는 검사 대상을 바꾼 가드다.
`Sources/`뿐 아니라 `Tests/`도 같은 가드를 받으므로 사라지는 것은 코드가 아니라 **행**이다.

**나온 곳.** w-dpv9h — 브리프가 Google 어댑터에 `#if canImport(UIKit)`을 지정했는데, 그
파일의 macOS 분기는 `import AppKit`이고 `SPFNGooglePresentingContext = NSWindow`다.
UIKit만으로 감쌌다면 macOS `swift test`에서 `SPFNSocialGoogleTests` 6행이 조용히
사라진 채 Linux 게이트만 초록이었을 것이다. `|| canImport(AppKit)`으로 바꿔 macOS
290행을 지켰다.

## P21. 최소 터치 타깃보다 작은 컨트롤은 이웃의 좌표를 보고한다 {#p21}

**증상.** Compose에서 한 줄짜리 `BasicText`에 `clickable`만 붙이면 컨트롤이 차지하는
자리는 한 줄(16dp)인데 터치가 먹히는 영역만 최소 터치 타깃(48dp)으로 넓혀진다. 세로로
붙어 있는 컨트롤들의 넓혀진 영역은 서로 겹치고, 그때 접근성에 **보고되는** bounds는
이웃에게 잘려 컨트롤 자신의 자리를 벗어난다. 러너(Maestro, `adb shell input tap`)는
보고된 bounds의 중심을 누르므로 **다른 노드를 누른다.** 클릭 람다는 아예 호출되지 않고
예외도 로그도 없다. 모델과 `Flow`는 옳으므로 JVM 단위 테스트는 통과하고, SwiftUI의
`Button`은 기본으로 44pt를 넘기므로 iOS 셀도 통과한다 — 한 플랫폼의 한 셀만 붉다.

**탐지.** 계층 덤프에서 **컨트롤의 bounds 높이가 최소 터치 타깃과 같은지, 이웃의
bounds와 겹치지 않는지** 본다. 밀도 2.75인 에뮬레이터에서 48dp는 132px이다. 45px로
나오면 확장 대상이고, 132px보다 짧게 나오면 이미 이웃에게 잘린 것이다.

```
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml \
  && adb -s emulator-5554 shell cat /sdcard/ui.xml \
  | tr '<' '\n<' | grep -o 'resource-id="[^"]*"[^>]*bounds="[^"]*"'
```

그다음 보고된 중심을 직접 눌러 확인한다. 컨트롤이 반응하지 않고 **이웃이** 반응하면
(텍스트 필드라면 키보드가 뜬다) 확정이다.

```
adb -s emulator-5554 shell input tap <보고된 중심 x> <보고된 중심 y>
adb -s emulator-5554 shell dumpsys input_method | grep -o 'mInputShown=[a-z]*'
```

**처방.** 상호작용 요소마다 **자기 레이아웃에** 최소 터치 타깃을 준다 — Compose는
`Modifier.heightIn(min = 48.dp)`, SwiftUI는 `.frame(minHeight: 44)`. 확장할 것이
없어지면 보고되는 bounds가 곧 실제 bounds이고 어느 둘도 겹치지 않는다. 부모에
`spacing`만 주는 것으로는 부족하다: 8dp 간격은 16dp 컨트롤의 48dp 확장을 여전히
겹치게 둔다. 방출기가 찍는 코드라면 규칙은 방출기에 두고 두 플랫폼 모두에 적는다.

**나온 곳.** ui/scaffold-1c — 셀 u5(`enterCode.cancel`)만 실패했다. 계층 덤프에서
`enterCode.cancel`이 보고한 사각형은 `[0,492][122,537]`(높이 45px)이고 바로 위
`enterCode.userCode`는 `[0,447][270,579]`(높이 132px)여서 앞의 것이 뒤의 것 안에 완전히
들어가 있었다. 같은 빌드에 대고 y만 바꿔 눌러 보면 y=500·514는 키보드를 열고
(`mInputShown=true`) `stack=1`로 남았고, y=540·557·570은 `stack=0`을 냈다 — 취소 컨트롤이
실제로 받는 자리는 보고된 사각형보다 아래였다. `enterCode.submit`은 열의 마지막이라
아래에서 잘릴 이웃이 없어 보고된 중심이 자기 안에 있었고, 그래서 같은 화면의 다른
컨트롤은 멀쩡했다. 모든 컨트롤에 48dp를 준 뒤 덤프는 `userCode [0,577][270,709]`,
`cancel [0,709][122,841]`, `submit [0,841][124,973]`로 서로 겹치지 않았고 u5·u7·u8·u1이
모두 통과했다.

## P22. 한 플랫폼 전용 Maestro 명령은 다른 플랫폼에서 조용히 성공한다 {#p22}

**증상.** `- back`은 **Android 전용** 명령이다. iOS에서는 실패하지 않는다 — 아무 일도
하지 않고 성공으로 끝난다. 그래서 플로우는 그 명령이 아니라 **다음 단언**에서 깨지고,
로그는 "back 통과, assertVisible 실패"로 남는다. 읽는 사람은 화면 상태나 셀렉터를 의심하며
시간을 쓰고, 정작 아무것도 하지 않은 단계는 초록으로 지나간다. 명령이 없는 것이 아니라
**있고 무해하게 성공하는 것**이 이 함정의 전부다.

**탐지.** 한 줄이면 된다. `when: platform:` 블록 밖의 top-level `- back`은 하나도 없어야
한다 — 조건부 블록 안에서는 들여쓰기가 되므로 `^`가 그 둘을 가른다.

```
grep -n "^- back" examples/ui-spec/generated/flows/*.yaml tools/harness/flows/*.yaml
```

생성된 플로우만 보고 끝내지 않는다. **하네스 자신의 플로우가 두 번째로 볼 곳**이다:
`tools/harness/flows`는 손으로 쓴 플로우이고 같은 명령을 같은 이유로 부를 수 있다.
(2026-09-02 기준 그쪽은 깨끗하다 — `- back`을 하나도 쓰지 않는다.)
`tools/validate/validate.sh` 14절이 `both` 셀의 플로우에 대해 이 규칙을 강제하고,
`tools/validate/probe-example-scaffold-rules.sh`가 그 거부가 실제로 무는지 증명한다.

**처방.** 플랫폼마다 그 플랫폼의 제스처를 준다. 시스템 back은 Android에서는 `back`이고
iOS에서는 화면 왼쪽 가장자리에서 시작하는 interactive-pop 스와이프다.

```yaml
- runFlow:
    when:
      platform: Android
    commands:
      - back
- runFlow:
    when:
      platform: iOS
    commands:
      - swipe:
          start: "1%, 50%"
          end: "90%, 50%"
          duration: 600
```

플로우를 **생성기가 찍는다면 규칙은 생성기에 둔다**. 두 플랫폼이 공유하는 플로우 파일
하나에 조건부 쌍을 넣는 것이지, 플랫폼별 플로우 파일을 두 벌 만드는 것이 아니다 —
케이스 표는 두 플랫폼이 같은 셀을 같은 파일로 돈다는 주장이고, 파일이 갈라지면 그
주장이 사라진다.

**나온 곳.** w-w823n의 Mac 실기 라운드, 2026-09-02. iPhone 17 Pro 시뮬레이터(iOS 26.3)
14셀 중 12셀 통과, 실패한 둘은 u7b·u10b — 시스템 back을 쓰는 두 셀 전부였다. `- back`
직후의 계층 덤프는 여전히 `stack=2` / `state=ready`였다: 스택은 움직이지 않았고 명령은
성공했다. `tools/ui-codegen`의 `CaseTable.kt`에는 "iOS에서 Maestro가 이것을 가장자리
스와이프로 실현한다"는 주석이 있었으나 **측정된 적 없는 가정이었고 거짓이다.** 위의 쌍은
같은 시뮬레이터에서 end-to-end로 확인했다 — `stack=1`, `state=idle`, 리시트 기록까지.
같은 라운드의 Android(Pixel 3a API 34) 쪽 12/14는 이 항목이 아니라 [P21](#p21)이었다.

## P23. `clearState`는 앞 셀이 남긴 증거를 지운다 {#p23}

**증상.** 플로우 스위트는 통과했는데 **리시트가 하나도 없다.** 14셀 중 9셀이 초록이고
게이트는 `0 of 14 cells left a receipt`로 붉다. 리시트를 앱이 자기 저장소에 쓰고
러너가 **끝에 한 번** 가져오는 구조에서, 모든 플로우가 `launchApp: clearState: true`로
시작할 때 나온다. clearState는 화면 상태가 아니라 **앱 저장소 전체를 지운다** — iOS는
데이터 컨테이너, Android는 `pm clear`이고 이쪽은 `/sdcard/Android/data/<pkg>/files`까지
같이 간다. 그래서 셀 N+1의 첫 줄이 셀 N의 리시트를 지우고, 마지막 한 번의 pull은
**마지막 wipe 이후에 남은 것만** 집는다. 마지막 플로우가 자기 마지막 단계 전에 깨졌다면
그것조차 0이다. 앱이 쓰는 리시트와 끝에 한 번 하는 pull은 **같이 성립할 수 없다.**

**iOS는 한 걸음 더 간다.** 시뮬레이터에서 clearState가 하는 일은 데이터 컨테이너를 비우는
것이 아니라 **다시 만드는 것**이다 — 컨테이너 uuid가 launch마다 바뀐다. 그래서 러너가
컨테이너 경로를 루프 앞에서 한 번 읽어 두면 그 경로는 첫 플로우의 launch 이후로 **존재하지
않는 디렉터리**를 가리키고, 셀마다 pull하도록 고친 러너조차 매번 빈 곳에서 복사한다. 이때의
증상은 위와 한 글자도 다르지 않다: 플로우는 전부 통과하고 리시트는 하나도 없다. 즉 이
함정은 **두 겹**이고, 바깥쪽(pull 시점)만 고치면 안쪽(경로 조회 시점)이 그대로 남는다.

증거가 사라지는 것이지 검사가 무는 것이 아니므로, 읽는 사람은 리시트 경로·파일 공유
설정·앱의 쓰기 코드를 의심하며 시간을 쓴다. 정작 지운 것은 **플로우 자신의 첫 줄**이다.

**탐지.** 두 줄이면 갈린다. 플로우가 셀마다 wipe하는가, 러너가 pull을 루프 밖에서
하는가 — 둘 다 참이면 적중이다.

```
grep -l "clearState: true" examples/ui-spec/generated/flows/*.yaml | wc -l
grep -n "maestro .* test" examples/ui-spec/run-cells.sh
```

오늘 기준 첫 명령은 14(= 기기 러너를 도는 셀 전부)를 내고, 둘째는 2줄 — 워밍업 하나와
**셀당 하나**를 낸다. 둘째가 플로우 파일 **목록**을 한 번에 넘기는 한 줄로 보이는데
첫째가 0이 아니면, 그 러너는 이 함정 안에 있다. 일반형으로는 **"기기 위의 증거를
수집하는 시점이 그 증거를 지우는 시점보다 뒤인가"**를 묻는다.

안쪽 겹(iOS 컨테이너 재생성)은 시뮬레이터 하나와 플로우 하나로 직접 잰다. `clearState:
true` launch **한 번**을 사이에 두고 컨테이너를 두 번 조회한다.

```
xcrun simctl get_app_container <udid> xyz.superfunction.spfn.example data
maestro test examples/ui-spec/generated/flows/u1.yaml
xcrun simctl get_app_container <udid> xyz.superfunction.spfn.example data
```

두 출력의 uuid가 **다르면** 컨테이너는 비워진 것이 아니라 다시 만들어진 것이고, 루프 앞에서
읽어 둔 경로는 전부 stale이다. 러너 쪽 대응 질문은 `grep -n get_app_container
examples/ui-spec/run-cells.sh`이다 — 조회가 pull 함수 **안**에 있어야 하고, 루프 앞의
조회는 경로를 남기지 않는 "설치돼 있기는 한가" 거절이어야 한다. Android에는 이 겹이 없다:
`/sdcard/Android/data/<pkg>/files`는 플랫폼이 고정한 경로라 조회할 것이 없고, 그래서 이쪽을
iOS와 대칭으로 만들 이유도 없다.

**처방.** 둘 중 하나다. (1) **플로우마다 pull한다** — 한 플로우에 `maestro test` 한 번,
그 셀의 리시트를 다음 플로우의 launch가 지우기 전에 host로 옮긴다. 드라이버 재설치를
셀 수만큼 무는 대신 증거를 산다. (2) **host 쪽에서 파생한다** — 리시트를 앱에 쓰게 하지
말고 JUnit 리포트에서 케이스별로 만든다. `tools/harness/run-harness.sh` 4절이 이쪽이고,
그래서 하네스는 플로우 하나를 한 maestro에 몰아 넣고도 이 함정에 걸리지 않는다.
(1)은 셀별 리시트가 fixture가 실제로 전달됐음까지 증명해야 할 때, (2)는 속도가 먼저일
때 고른다. **섞으면 안 된다** — 앱이 쓰는 리시트에 (2)의 수집 시점을 붙인 것이 이 항목
자체다.

(1)을 고르면 iOS에서는 **경로도 pull마다 다시 조회한다.** 루프 앞의 조회는 "앱이 이 기기에
있기는 한가"라는 거절로만 남기고 그 답(경로)은 버린다 — 다음 launch가 그 컨테이너를 이미
갈아치웠기 때문이다. 그리고 그 조회가 빈 값을 내면 **조용히 건너뛰지 않고 그 자리에서 붉게**
실패한다. 컨테이너가 없다는 것은 "앱이 기기에 없다"는 신호이지 "이 셀이 리시트를 안 썼다"가
아니고, 둘은 고치는 곳이 다르다. 조회가 어깨를 으쓱하면 게이트는 첫째 사실에 대해 둘째
진단을 내놓는다([P6](#p6), [P7](#p7)).

어느 쪽이든 **수집이 곧 판정이 되게 두지 않는다.** 수집은 조용히 실패해도 되지만
(`cp ... || true`), 세고 하한을 두고 붉게 실패하는 것은 게이트 몫이다([P7](#p7)).
`sh examples/ui-spec/run-cells.sh --probe`가 두 수집 순서를 같은 fixture에 돌려
per-flow pull은 13/14, 끝에 한 번은 0/14를 내는 것으로 이 차이를 증명한다 — fixture가
wipe를 모형화하지 않게 만들면 그 케이스가 붉어진다. 안쪽 겹도 같은 probe가 잡는다:
fixture의 "기기"가 launch마다 컨테이너를 **새 이름으로 다시 만들고 앞의 것을 지우며**,
셀마다 조회하는 pull은 13/14, 루프 앞에서 한 번 조회한 경로는 0/14를 낸다. 조회를 다시
캐시하도록 러너를 바꾸면 첫 케이스가 13에서 1로 떨어져 붉어진다.

**나온 곳.** w-w823n의 Mac 라운드, 2026-09-03. iPhone 17 Pro 시뮬레이터에서
`run-cells.sh ios --device <udid>`가 14 플로우를 한 maestro로 돌려 9통과 5실패를 냈고,
그 뒤 게이트가 9개 초록 셀에 대해서도 `0 of 14 cells left a receipt`를 냈다.
`xcrun simctl get_app_container <udid> xyz.superfunction.spfn.example data`로 본 컨테이너에
`Documents/`는 있고 `Documents/receipts`는 없었다 — 컨테이너는 살아 있고 그 안이 비어
있었다는 뜻이므로, 앱이 못 쓴 것이 아니라 **쓴 것이 지워진** 것이다.

**두 번째 측정.** 같은 w-w823n의 Mac, 2026-09-03. 셀마다 pull하도록 고친 러너가 같은
시뮬레이터에서 **14 flows run, 14 passed, 0 receipts pulled**를 냈다 — 열네 셀 전부가
"flow passed and left NO receipt"였다. 손으로 `maestro test u1.yaml`을 돌린 뒤 컨테이너를
다시 조회하니 id가 달랐다: run 전 `FDE707DA…`, run 중 `961ED257…`, run 후 `3D0787B5…`이고
`Documents/receipts/receipt-u1-1788366409229.json`은 **마지막 것** 안에 있었다. 앱은 README가
말하는 곳에 쓰고 있었고 틀린 것은 러너가 루프 앞에서 한 번 읽어 둔 경로였다. 첫 측정에서
"`Documents/`는 있고 `Documents/receipts`는 없다"고 본 컨테이너도 같은 이유로 **그때 막
만들어진 새 컨테이너**였다 — 살아남은 컨테이너가 비어 있었던 것이 아니다.

([P22](#p22)와 헷갈리지 않는다: 저쪽은 명령이 아무 일도 하지 않고 성공하는 것이고,
이쪽은 명령이 제대로 일하고 그 일이 앞 셀의 증거를 지우는 것이다.)

## 원장

change set마다 라운드 수와, **이미 항목으로 있던 것을 놓쳐서 나온 finding 수**를 적는다.
뒤 칸이 0이 아닌 것만이 이 문서의 실패다.

| change set | 라운드 | finding | 이미 항목이던 것 |
| --- | --- | --- | --- |
| cs-6jcny (w-hfc9g) | 8 | 8 | — (등록부 이전) |
| cs-mzv14 (w-0r0ya) | 5 | 10 | 0 |
| PR #11 (w-6s7yg) | 2 | 4 | 0 |
| PR #12 (w-9phsb) | 2 | 8 | 0 |
| PR #13 (w-9phsb, 관측성) | 1 | 1 | 0 |
| w-6m8dz (네이티브 소셜) | 0 — 케이스 표로 닫음 | 1 (probe가 스스로 잡음) | 0 |
| PR #24 잔여 fresh 리뷰 | 1 | 6 (5 확정, 1 기각) | 0 |

**PR #24 읽는 법.** 케이스 표가 닫은 표면에는 리뷰를 두지 않고, 표로 못 닫는 잔여
3가지(플랫폼 암호·인증 API 의미론, 언어 간 분류 갈림, 외부 통합)만 리뷰에 넘겼다.
확정 5건이 전부 그 잔여에서 나왔고 표가 덮은 셀에서는 하나도 나오지 않았다 — 범위
분리가 의도대로 작동했다. 기각 1건은 SwiftPM 트레이트가 의존성 해석을 막지 못한다는
주장으로, 리뷰어가 문서만 읽고 낸 결론이었다. 소비자 패키지 2개로 실측해 반증했다
(트레이트 OFF: 원격 패키지 0개·`Package.resolved` 없음 / ON: 8개 체크아웃).
**리뷰어의 문서 추론은 실측 앞에서 진다** — 이것이 6번째 항목이 아니라 이 줄인 이유는,
등록부가 담는 것은 코드 함정이지 리뷰 운영이 아니기 때문이다.

**cs-mzv14 읽는 법.** 등록부를 도입한 change set 자신이다. 브리프에 인용한 항목은
[P4](#p4)–[P7](#p7)이었고 리뷰가 넷 다 지켜졌다고 확인했다. finding 10건 중 9건은
등록부에 붙였던 라우팅 검사와 그 검사에 관한 항목들에서 나왔고, 그 검사는 이 change
set에서 제거했다 — SDK와 무관한 것을 지키느라 리뷰 라운드를 썼다. 남은 것은 P1–P13,
실제 SDK 작업에서 나온 항목들이다.
