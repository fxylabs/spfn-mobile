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
| No seed script in the app | Exits: no account exists for the suite to sign in as |
| The seed script exports no readable credentials | Exits: the suite signs in with those constants |
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

The cases use only operations a deployed SPFN server serves — enrolment, the key
lifecycle, and the device-code flow contract 0.10.0 added:

| Case | What it proves |
|---|---|
| r1 | `/_auth/login` with the seeded account enrolls a freshly generated key |
| r2 | a proven `auth.keys.list` under the enrolled key names it, active |
| r3 | `auth.keys.rotate` under the old key registers the candidate |
| r4 | the new key proves a call while the replaced key is refused with `SESSION_REVOKED` |
| r5 | `auth.keys.revoke` removes a named key and `auth.keys.revokeAll` spares the caller |
| r6 | a device waiting on a code is approved from a device already signed in, and enrolls |
| r7 | a denial ends the wait and leaves the waiting device holding nothing |
| r8 | a second approval of one code is refused and the first one still stands |
| r9 | an approval nobody proved is refused and applies nothing |

The four device-code cases each run two SDKs against the one server — a waiting device
and an approver — and each spends one real five-second poll interval, because the waiting
side obeys the interval the server names and this server names five seconds. Every key
they put on the seeded account is revoked when the class ends, after the receipts are
written, so a run repeated all day does not leave a key behind each time.

Three things are deliberately absent.

**No `/control` surface.** The reference server has one because it must be able to revoke
and expire on demand. A real server has no such hooks, so the cases are written to need
none.

**No expired code.** The reference suite's case i moves a clock fifteen minutes forward.
A real server has no clock to move and the code's TTL is ten minutes, so the only way to
write that case here would be to sit out the TTL — a hang, not a case. It stays a
reference-server cell.

**No social enrolment.** `/_auth/oauth/{provider}/native` verifies a real id_token
against the provider's keys, so a headless runner cannot enrol through it — the reference
server accepts a shaped test token, and a real server correctly does not. Proving that a
real Apple or Google sign-in reaches a real server is device work, and it is work unit
`w-9jqtj`. A suite here that faked it would be another reference server.

## The login budget

The seeded account allows ten `/_auth/login` calls a minute, which is the server's own
auth-login rate limit and not a setting of this runner. One run spends seven: six for
r1–r5, and one for the single approver the four device cells share. A device-code
enrolment costs no login at all, so every waiting device is free. That is why the approver
is enrolled once for the whole class rather than per case — and why a second full run
started inside the same minute can meet a 429 on its later cases, which shows up as an
unknown-code failure rather than as a silent skip.

## Creating the app

From the directory that should hold it:

```sh
npx spfn create spfn-verify-app
```

Then pin the packages `publishedPackages` in `Contracts/upstream.lock.json` names and run
the migrations. `run.sh` prints the expected version when the installed one disagrees, so
the pin never has to be looked up by hand.

The seeded account the suite signs in as belongs to the app: `scripts/seed-verify-user.ts`
creates it through `@spfn/auth`'s own `hashPassword` and repositories, exports
`VERIFY_EMAIL`/`VERIFY_PASSWORD` as constants, and is idempotent. `run.sh` reads the
credentials from that script, runs it before the suite, and hands them to the suite as
environment variables — the password is never printed.

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
