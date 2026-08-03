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
| Swift·Kotlin 대칭 로직 추가·수정 | [P9](#p9) [P10](#p10) |
| 공유 conformance 표·fixture 수정 | [P10](#p10) [P2](#p2) |
| 게이트·통합 매트릭스 실행 | [P11](#p11) [P12](#p12) |
| 버전 범위·호환성 규칙 수정 | [P3](#p3) [P13](#p13) |

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

## P2. digest는 11개 파일에 있고 역할이 넷으로 갈린다 {#p2}

**증상.** 번들을 교체했는데 어딘가가 옛 digest를 들고 있어 빌드나 검증이 깨진다.
또는 더 나쁘게, 한 곳만 갱신되어 검사끼리 서로 다른 파일을 가리킨다.

**탐지.** `git grep -l <digest>`로 세는 것이 유일하게 정확하다. 오늘 기준 11개다.
개수를 외우지 말고 **역할**을 기억한다 — 넷은 서로 다르게 갱신되고, **손으로 고치는
것은 하나뿐이다.**

| 역할 | 파일 | 어떻게 갱신되나 | 확인 |
| --- | --- | --- | --- |
| 손으로 고정 | `Contracts/upstream.lock.json` (`contract.manifestSha256`) | 유일하게 직접 편집하는 자리 | 번들 파일의 실제 sha256과 같은지 재계산해 대조. validator 5절이 강제한다 |
| fixture 파생물 | `Contracts/fixtures/MANIFEST.json` (`bundleSha256`) | `derive-expected-values.py` 재실행. **손으로 고치지 않는다** | 파일 안 `derivedBy` 필드가 스스로 밝힌다 |
| codegen 산출물 | 생성 파일 8개 헤더 (Swift 4 + Kotlin 4) | codegen 재생성. **손으로 고치지 않는다** | `:contract-codegen:spfnCodegenVerify` |
| upstream 제공 | `Contracts/upstream-provenance.json` (`bundleSha256`) | 새 번들과 함께 도착한다. 갱신 대상이 아니라 **대조 대상** | validator 5절이 lock과 필드 단위로 맞춰 본다 |

**처방.** 번들 교체는 lock 직접 편집 → `derive-expected-values.py` 재실행 →
codegen 재생성 → 결정성 확인이 한 묶음이다. 나머지 셋 중 둘(MANIFEST, 생성 파일 8개)은
이 저장소의 **파생물**이라 손으로 편집하면 자기 생성기와 어긋난다. 네 번째는 파생물이
아니라 **upstream이 발행한 증거**다 — 우리 도구 중 무엇도 그것을 쓰지 않는다. 그것을
"우리가 갱신할 것"으로 착각하면 evidence를 lock에 맞춰 편집하게 되는데, 그것은
provenance 게이트가 정확히 잡으려는 행위다([P1](#p1)).

**검증.** `git grep -l <digest> | wc -l`이 11이고, 그중 `upstream-provenance.json`이
포함돼 있으며 그 값이 lock과 같은지 본다. 개수가 늘었다면 새 소비처가 생긴 것이니
**그 파일이 스스로 파생물임을 밝히는지**(`derivedBy` 류 필드, 생성기 헤더) 먼저 보고
역할을 판정해 이 표에 추가한다.

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

## P13. 규칙을 조이면 그 규칙을 광고하는 문자열도 본다 {#p13}

**증상.** 라운드 1이 "검사가 출력 문자열보다 느슨하다"였고, 그 수정이 라운드 3에서
"검사가 문자열보다 엄격하다"가 됐다. 같은 필드를 두 번 고치며 반대 방향으로 두 번
틀렸다.

**탐지.** 비교 규칙을 바꾸는 diff에서 그 규칙을 인용하는 곳을 전부 나열한다 —
range 문자열, 에러 메시지, doc comment, COMPATIBILITY.md 행, 생성 코드.

**처방.** 규칙 변경과 그것을 서술하는 문자열 변경을 같은 커밋에 넣는다. 파생 불가면
[P1](#p1)의 파생 필드 처방을 쓴다.

**나온 곳.** cs-6jcny r1 → r3.

## 원장

change set마다 라운드 수와, **이미 항목으로 있던 것을 놓쳐서 나온 finding 수**를 적는다.
뒤 칸이 0이 아닌 것만이 이 문서의 실패다.

| change set | 라운드 | finding | 이미 항목이던 것 |
| --- | --- | --- | --- |
| cs-6jcny (w-hfc9g) | 8 | 8 | — (등록부 이전) |
| cs-mzv14 (w-0r0ya) | 5 | 10 | 0 |
| PR #11 (w-6s7yg) | 2 | 4 | 0 |

**cs-mzv14 읽는 법.** 등록부를 도입한 change set 자신이다. 브리프에 인용한 항목은
[P4](#p4)–[P7](#p7)이었고 리뷰가 넷 다 지켜졌다고 확인했다. finding 10건 중 9건은
등록부에 붙였던 라우팅 검사와 그 검사에 관한 항목들에서 나왔고, 그 검사는 이 change
set에서 제거했다 — SDK와 무관한 것을 지키느라 리뷰 라운드를 썼다. 남은 것은 P1–P13,
실제 SDK 작업에서 나온 항목들이다.
