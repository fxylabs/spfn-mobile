<!-- superself:begin -->
## Project state (superself)

Project state — goals, decisions, work units, reports — is version-controlled
by the `self` CLI, outside this repository. Skip this section if the `self`
command is unavailable.

- Session start: run `self context` and treat its output as current truth.
- Substantive work attaches to a work unit: `self work add "<required outcome>"`,
  then `self work start <id>`. Report progress with `self report <id> "<summary>"`
  after committing — HEAD is attached as evidence automatically.
- The long-term goal and time-boxed objectives are separate state: `self goal set`
  keeps the goal, `self objective add "<outcome>" --horizon week --target <date>`
  adds an objective, and `self milestone add "<outcome>" --objective <id> --exit "<criterion>"`
  adds a checkpoint under it. `self objective` lists both with the reason for each state.
- State what work contributes to: `self work link <id> --milestone <id>`. A milestone
  is reached only when every exit criterion is covered — `self milestone met <id>
  --criterion <c> --why "<how the evidence covers it>"`, then `self milestone reach <id>`.
  Finishing work never reaches a milestone on its own, and progress is never a percentage.
- Revising an objective or a milestone leaves what it already settled stale. Re-judge it
  at the current revision with `self milestone recheck <id> [--criterion <c>] --why "<what
  you re-judged>"` — a reach still needs every live criterion covered first.
- Done is a judgment: `self work done <id>` closes the unit when its outcome
  is reached — the evidence lives in the reports the unit already carries.
- Found a gap between an objective and current state? Propose the work with
  `self work propose` and its full brief; the user accepts or declines it.
- Record decisions the user confirmed: `self decide "<text>" --why "<reason>"`.
  Use `--proposed` when the user has not confirmed. One decision per event.
- Blocked? `self work block <id> --on decision|dependency|external --why "..."`.
- Superseded or moved to another unit or project? `self work retire <id> --why "..."
  [--successor <work-id>]` — never mark it done and never leave it falsely blocked.
- Picking up existing work? `self work show <id>` prints its full brief and
  report history. Leave a brief for the next session with `self report <id> --file <path>`.
- Proposed next work, or suggested continuing in the next session, and the
  user approved? Register it with `self work add` right then, with the
  context behind the proposal — an approved plan that is never registered is lost.
- Deferring work for later? Attach a scoping brief the moment you create it:
  `self report <id> --file <path>` covering scope, design anchors, and known
  pitfalls — a bare outcome line loses the context that created the work.
- A branch reaches main through a GitHub pull request: PR review and CI own
  merge control. superself owns context and the work graph, not the merge gate.
- Search past state with `self search <query>`; list work with `self work`.
- Never hand-edit generated state files or anything under `.superself/`.

### Conventions

- 위임 구현-리뷰 규율: 모든 구현 브리프는 결함 예상 섹션(엣지 케이스·보안/성능 표면·실패 모드)을 담고, fix 브리프는 fix가 건드리는 표면의 인접 케이스를 열거한다. 기계 게이트(validate.sh·swift build/test·gradlew build)는 리뷰 라운드 전에 통과하고, 구현자는 리뷰 루브릭 기준 self-adversarial pass 결과를 보고한다. 리뷰 finding은 predictable/novel로 분류해 predictable은 즉시 자동 검사로 승격하고, 리뷰어 probe는 영구 테스트 케이스로 fix와 함께 남긴다. 리뷰 라운드는 세션 재사용(구현자 resume fix, 리뷰어 resume delta 재리뷰)을 기본으로 하되 semantic rework·2회 초과·컨텍스트 비대 시 cold review로 교체한다.
- 구현 주의 패턴 등록부(docs/IMPLEMENTATION-PITFALLS.md): SDK 작업을 디스패치하기 전에 트리거 표에서 건드리는 표면의 행을 찾아 해당 항목을 브리프에 같이 넣는다. 리뷰가 재발 가능한 결함을 찾으면 항목으로 등록하되, 이미 있는 항목이면 새로 만들지 말고 그 항목의 탐지 절을 강화한다 — 중복이 이 문서를 죽인다. 세는 값은 하나다: 이미 항목으로 있던 것을 구현자가 놓쳐 리뷰어가 지적한 횟수. 그것만이 실패이고 0이 목표다. 리뷰가 등록부에 없던 것을 찾는 것은 정상 동작이다. 범용 패턴은 coding-context로 보내고 여기는 spfn-mobile 고유의 것만 담는다.
<!-- superself:end -->
