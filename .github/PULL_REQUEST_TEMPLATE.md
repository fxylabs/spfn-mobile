<!-- Write the body for a future reader: what changed and why.
     Review evidence lives in the project state; reference it by id below. -->

## What / why



## Gate checklist (all required before merge)

- Change set: `cs-_____` (registered in project state)
- Review receipt: `rr-_____` (fresh cross-model review, bound to this exact diff)
- Closes #_____
- [ ] `bash tools/validate/validate.sh` — RESULT: PASS
- [ ] `swift build && swift test` — pass
- [ ] `./gradlew build` — pass (needs `ANDROID_HOME`)

A green checklist is necessary, not sufficient: merge happens only after the owner's
approval recorded in the project state. Squash merge only.
