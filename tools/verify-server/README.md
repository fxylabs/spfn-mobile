# The real-server verification target

Everything else this repository proves, it proves against `tools/reference-server`: a
Kotlin server built from one reading of the contract, checked by two SDKs built from the
same reading. That agreement is real, and it is also self-verification. It cannot catch a
contract this repository reads correctly and no SPFN server implements.

This directory points the SDK at a **scaffolded SPFN app** instead — the published
`@spfn/auth` on a real PostgreSQL, which is the server an SPFN application deploys.

## Where the app is

`workspaces/spfn-verify-app`, beside this repository and outside it.

```
workspaces/
  spfn-mobile/        <- this repository
  spfn-verify-app/    <- the target
```

Somewhere else is fine; say so rather than editing a tracked file:

```sh
SPFN_VERIFY_APP=/path/to/app sh tools/verify-server/run.sh
```

### Why it is not in this repository

Decision `01kz6nq4ga` records this in full. The short form is two reasons.

A gitignored in-repo scaffold answers no discovery question. A fresh clone does not have
it either way, and something visible only on one machine reads as though it were
committed, which is worse than absent.

It also breaks three whole-tree checks in `tools/validate/validate.sh` — the binary ban,
the credential-file ban, and the provenance scan — because it carries `node_modules` and
a `.env`. The only fix for those failures is teaching a check to look away from a
directory, and a check with an exception is most of the way to no check.

There is no path convenience to give up, because a device reaches a server by URL.

## Running it

```sh
sh tools/verify-server/run.sh
```

The script resolves the app, checks that it is the right one, starts it, runs the
real-server suite against it, and stops it. It reports `RESULT: PASS` only when every
case in the suite left a receipt.

## What it refuses, and why every one is an exit

`run.sh` never falls back to the reference server. A run that checked the local fake
while reporting real-server coverage would be the most expensive kind of green there is.

| Situation | What happens |
|---|---|
| No app at the resolved path | Prints the scaffold command, exits non-zero |
| The path holds no `package.json` | Exits: a stray directory is not an app |
| The lock names no published version | Exits: there is nothing to compare against |
| `@spfn/auth` is not installed | Exits, naming the version to install |
| The installed version is not the pinned one | Exits: two pins that drift make a pass meaningless |
| No `.env.server` | Exits: no database is configured |
| `.env.server` names no `DATABASE_URL` | Exits |
| Nothing listening on the database port | Exits, naming the host and port |
| A case in the suite did not run | Exits: a skipped XCTest is a passing XCTest |

`tools/verify-server/probe-refusals.sh` proves each of those refusals bites, and proves
that a correct setup still passes — without the last part, a runner that refused
everything would satisfy the probe while blocking every real run.

The database password is never printed. `DATABASE_URL` is read into a variable and only
its host and port are shown, which is what a reader needs in order to act.

## What the suite covers, and what it does not

The cases use only operations a deployed SPFN server serves: enrolment through
`/_auth/login` with a freshly generated key, a proven `auth.keys.list` under it,
`auth.keys.rotate`, a proven call under the new key while the replaced one is refused,
and revocation.

Two things are deliberately absent.

**No `/control` surface.** The reference server has one because it must be able to revoke
and expire on demand. A real server has no such hooks, so the cases are written to need
none.

**No social enrolment.** `/_auth/oauth/{provider}/native` verifies a real id_token
against the provider's keys, so a headless runner cannot enrol through it — the reference
server accepts a shaped test token, and a real server correctly does not. Proving that a
real Apple or Google sign-in reaches a real server is device work, and it is work unit
`w-9jqtj`. A suite here that faked it would be another reference server.

## Creating the app

From the directory that should hold it:

```sh
npx spfn create spfn-verify-app
```

Then pin the packages `publishedPackages` in `Contracts/upstream.lock.json` names, run the
migrations, and seed the test account the suite signs in as. `run.sh` prints the expected
version when the installed one disagrees, so the pin never has to be looked up by hand.

Two things about the pin are worth knowing before `npm install` runs.

**Pin `@spfn/core` explicitly.** `@spfn/auth` declares `@spfn/core >=0.2.0-beta.54`, which
a much older core satisfies while lacking the error envelope this contract's codes ride
in. Resolution is free to pick one; the app should not let it.

**Check which registry answers.** `@spfn` is published to both npmjs and the Gitea registry
at `git.superfunction.xyz`, and they run at different versions. A machine whose npmrc
scopes `@spfn` to Gitea installs the lagging one. npmjs is the registry of record, and
`--registry` does **not** override a scope — only `--@spfn:registry` does.
`sh tools/verify-server/spfn-versions.sh` asks both and exits non-zero when they disagree.

## Why `publishedPackages` exists at all

The lock names a primitives commit, which npm cannot install. Nothing in an installed
`@spfn/auth` says which contract it implements either: the bundle is not shipped in the
package and neither is the code that builds it. So the mapping from a package version to a
contract version lives in the lock, written by hand from the commit history, and `run.sh`
compares the app's installed tree against it.

That mapping is the weakest link here, and it is stated rather than hidden. `run.sh` fails
closed when the field is absent — an uncomparable pin is not treated as a matching one —
but it cannot tell a correct mapping from a mistaken one. Contract 0.6.0 is what eventually
removes the need: the server announces its own contract version on every response, so a
future runner can ask instead of consulting a table.
