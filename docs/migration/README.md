# Migration

Nothing to migrate from. No version has been released, so no upgrade path exists yet.

## Policy the first release must follow

- Breaking API or contract changes take a minor bump before 1.0 and a written migration
  note. "Pre-1.0, so anything can break" is not the policy here.
- A contract outside the SDK's declared supported range produces an explicit upgrade
  error. It never silently degrades and never falls back to another auth profile.
- Primitives publishes a new contract major with an overlap window first; mobile adds
  support for it; removing the old contract from the server is a separate approval.
- Published versions are immutable. A mistake becomes a new version.

## When the repository splits

If `spfn-mobile` is ever split into `spfn-ios` and `spfn-android`, the contract bundle
and fixtures are not promoted to canonical in either one. The primitives-owns-canonical,
mobile-imports-by-digest relationship survives the split, and each repository continues
its version from the shared version at the split point.

The split requires two or more of the objective triggers in the topology artifact §10 to
hold for two consecutive release cycles or 90 days, plus an approved plan for preserving
fixture parity automatically.
