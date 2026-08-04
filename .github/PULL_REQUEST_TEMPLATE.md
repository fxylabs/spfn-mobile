<!-- Write the body for a future reader: what changed and why.
     Design and approval evidence live in the project state; reference them by id below. -->

## What / why



## Gate checklist (all required before merge)

- Work unit: `w-_____`
- Approved decisions, one id per item the design settled (never one approval for the
  whole artifact): `_____`
- Closes #_____
- How this diff was reviewed — one of:
  - **Case table**: the surface is closed by a finite table whose cells correspond 1:1
    to tests, and the correspondence was checked mechanically. State the row count and
    that no row is missing a test. Table-closed surfaces get no review round.
  - **Fresh cross-model review**: for a surface no table can close (platform crypto
    semantics, cross-language classification, external integrations). Name the reviewing
    model and session.
- [ ] `sh tools/validate/validate.sh` — RESULT: PASS
- [ ] `swift build && swift test` — pass
- [ ] `./gradlew build` — pass (needs `ANDROID_HOME`)
- [ ] `sh tools/validate/probe-social-adapter-rules.sh` — RESULT: PASS
- [ ] `sh tools/reference-server/run-integration.sh` — RESULT: PASS, or state why the
      wire path is untouched and put that judgment to the reviewer explicitly

A green checklist is necessary, not sufficient: merge happens only after the owner's
approval. Squash merge only.
