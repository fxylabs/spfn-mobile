<!-- superself:begin v0.5.1 -->
## Project state (superself)

Project state — goals, decisions, work units, reports — is version-controlled
by the `self` CLI, outside this repository. Skip this section if the `self`
command is unavailable.

- Session start: run `self context` and treat its output as current truth.
- Write for the reader by default: answers to the person in their language,
  records — events, decisions, reports, conventions — in English, so a record
  stays readable to whoever opens it next. A project that wants it otherwise
  records its own convention.
- Substantive work attaches to a work unit: `self work add "<required outcome>"`,
  then `self work start <id>` — which is how you read a unit's brief and report
  history, and records that this session picked it up. If another session holds
  it, you are told who and since when, and never refused; judge it and proceed.
  Report progress with `self report <id> "<summary>"` after committing — HEAD is
  attached as evidence automatically.
- Done is a judgment, and the claim must carry evidence: `self work done <id>`
  closes the unit only when a report carries a commit or an artifact, or the
  done itself states one — `self work done <id> --report "<what verifiably
  happened>"`. A bare claim is refused, and declared criteria gate it.
- A record's text is immutable once confirmed, so a correction restates it:
  `--supersedes <id>` on any add verb records the new wording and carries the
  lineage. `retract` withdraws a record with nothing replacing it, and `retire`
  is for an outcome given up or moved — neither is a wording fix.
- Record decisions the user confirmed: `self decide "<text>" --why "<reason>"`.
  Use `--proposed` when the user has not confirmed. One decision per event.
- Blocked? `self work block <id> --on decision|dependency|external --why "..."`.
  Superseded or moved? `self work retire <id> --why "..." [--successor <id>]` —
  never mark it done and never leave it falsely blocked.
- Found a gap between an objective and current state? Propose the work with
  `self work propose` and its full brief; the user accepts or declines it.
- Proposed next work, or suggested continuing in the next session, and the
  user approved? Register it with `self work add` right then, with the
  context behind the proposal — an approved plan that is never registered is lost.
- Deferring work for later? Attach a scoping brief the moment you create it:
  `self report <id> --file <path>` covering scope, design anchors, and known
  pitfalls — a bare outcome line loses the context that created the work.
- A branch reaches main through a GitHub pull request: PR review and CI own
  merge control. superself owns context and the work graph, not the merge gate.
- Never hand-edit generated state files or anything under `.superself/`.

This block is the short form. The installed CLI carries the rest — what each
concept is, when to reach for it, and the order the verbs go in:

- `self help agents` — how a session drives this CLI, start to finish
- `self help context` — what `self context` renders, and why something is missing from it
- `self help records` — one entity behind every record kind, and how a record is corrected
- `self help placement` — scope, priority and exposure — how a record earns its place in context
- `self help work` — the work graph: outcomes, evidence, criteria, and proposals
- `self help goals` — long-term goals, objectives, milestones, and what reaching one takes
- `self help workspace` — the store, the projects in it, and moving it between machines

### Conventions

- 위임 구현-리뷰 규율: 모든 구현 브리프는 결함 예상 섹션(엣지 케이스·보안/성능 표면·실패 모드)을 담고, fix 브리프는 fix가 건드리는 표면의 인접 케이스를 열거한다. 기계 게이트(validate.sh·swift build/test·gradlew build)는 리뷰 라운드 전에 통과하고, 구현자는 리뷰 루브릭 기준 self-adversarial pass 결과를 보고한다. 리뷰 finding은 predictable/novel로 분류해 predictable은 즉시 자동 검사로 승격하고, 리뷰어 probe는 영구 테스트 케이스로 fix와 함께 남긴다. 리뷰 라운드는 세션 재사용(구현자 resume fix, 리뷰어 resume delta 재리뷰)을 기본으로 하되 semantic rework·2회 초과·컨텍스트 비대 시 cold review로 교체한다.
- 구현 주의 패턴 등록부(docs/IMPLEMENTATION-PITFALLS.md): SDK 작업을 디스패치하기 전에 트리거 표에서 건드리는 표면의 행을 찾아 해당 항목을 브리프에 같이 넣는다. 리뷰가 재발 가능한 결함을 찾으면 항목으로 등록하되, 이미 있는 항목이면 새로 만들지 말고 그 항목의 탐지 절을 강화한다 — 중복이 이 문서를 죽인다. 세는 값은 하나다: 이미 항목으로 있던 것을 구현자가 놓쳐 리뷰어가 지적한 횟수. 그것만이 실패이고 0이 목표다. 리뷰가 등록부에 없던 것을 찾는 것은 정상 동작이다. 범용 패턴은 coding-context로 보내고 여기는 spfn-mobile 고유의 것만 담는다.
- 리뷰는 열린 탐색 위임이 아니다. 구현을 맡긴 설계에서 유한한 경우의 수 표(상태 변수 × 연산 × 기대 결과)를 착수 전에 닫고, 테스트는 표의 셀과 1:1로 대응시키며, 디스패처가 셀-테스트 대응을 기계적으로 확인한다. 표로 닫은 표면에는 리뷰 라운드를 두지 않는다. 유한 열거로 탐지할 수 없는 잔여(플랫폼 암호 API 의미론, 언어 간 분류 갈림, 외부 통합 등)만 Codex GPT-5.6 Luna fresh 리뷰에 맡긴다.
- 위임 설계 산출물은 통짜로 승인받지 않는다. 수용 시 산출물에 임베디드된 결정을 추출해 항목별로 사용자 승인을 받으며, 암호 프리미티브·인증 모델·데이터 소유권·저장 방식 클래스는 반드시 개별 항목이다. 각 항목에는 기존 구현 대조를 붙인다: upstream/기존 시스템이 이미 이 문제의 메커니즘을 소유하는지, 다르게 가면 왜인지. clientProofV1 대칭 사건의 재발 방지 — 리뷰 강화가 아니라 승인 단위 교정이 처방이다.
<!-- superself:end -->
