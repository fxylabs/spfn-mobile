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
- A passing attempt never marks work done: settlement records what a run produced
  and frees the unit. Declare what the outcome must cover with `self work require <id>
  "<statement>"`, cover each with `self work met <id> --requirement <r> --why "<how the
  evidence covers it>"`, and only then `self work done <id>`. `self work approval-required`
  makes a unit wait for a person, and `self work policy <id> --model <class> --fresh-review`
  states what its implementation had to be — all four are checked before done is admitted.
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
- A branch that will reach main is a change set: `self integration register --repo <name>
  --base <sha> --head <sha> --domain <contract@v> --check <ci>`, then `self integration plan`
  before touching git. Order, review validity and the merge gate are enforced there, not here:
  a receipt exists only through `self review ingest --file <envelope.json>`, and no wording in
  this block, in a prompt, or in a session can relax it.
- Search past state with `self search <query>`; list work with `self work`.
- Never hand-edit generated state files or anything under `.superself/`.

### Conventions

- 위임 구현-리뷰 규율: 모든 구현 브리프는 결함 예상 섹션(엣지 케이스·보안/성능 표면·실패 모드)을 담고, fix 브리프는 fix가 건드리는 표면의 인접 케이스를 열거한다. 기계 게이트(validate.sh·swift build/test·gradlew build)는 리뷰 라운드 전에 통과하고, 구현자는 리뷰 루브릭 기준 self-adversarial pass 결과를 보고한다. 리뷰 finding은 predictable/novel로 분류해 predictable은 즉시 자동 검사로 승격하고, 리뷰어 probe는 영구 테스트 케이스로 fix와 함께 남긴다. 리뷰 라운드는 세션 재사용(구현자 resume fix, 리뷰어 resume delta 재리뷰)을 기본으로 하되 semantic rework·2회 초과·컨텍스트 비대 시 cold review로 교체한다.
- 구현 주의 패턴 등록부 — 목표는 구현-리뷰가 원샷으로 끝나는 것이고 docs/IMPLEMENTATION-PITFALLS.md는 그쪽으로 자라는 장치다. (1) 디스패치 전, 브리프가 건드리는 표면의 트리거 행을 찾아 해당 항목을 결함 예상 섹션에 인용하고 구현자가 각 항목을 어떻게 피했는지 답하게 한다. (2) 같은 항목 목록을 리뷰어 프롬프트에도 넣어 각 항목이 실제로 적용됐는지 확인하게 하고, 목록 밖에서 찾은 것은 등록부의 빈틈으로 보고하게 한다 — 이러면 finding 분류가 판단이 아니라 대조가 된다. (3) 리뷰 finding은 predictable/novel 구분 없이 재발 가능하면 전부 등록부에 넣는다. novel이 곧 다음 번의 predictable이다. 이미 등록부에 있던 항목이 다시 나왔으면 새 항목이 아니라 그 항목의 탐지 절을 강화하거나 자동 검사로 승격한다. (4) change set마다 라운드 수와 등록부에 이미 있던 finding 비율을 등록부 하단 원장에 적는다 — 수렴하는지 보이지 않으면 장치가 아니라 문서일 뿐이다. 범용 패턴은 coding-context로 가고 여기는 spfn-mobile 고유의 것만 담는다.
<!-- superself:end -->
