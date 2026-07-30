# loop-yakuwari

The operating roles of awai network's six businesses — what each role is for,
what it may do alone, and which one gets the next slot.

```
fleet.edn                 global WIP, runner pool, weights, capability ceiling
businesses.edn            the six businesses, their role sets, and what they LACK
yakuwari/<business>.edn   the roles themselves (31 across 6 files)
src/awai/registry.cljc    cross-file agreement + the fleet ceiling
src/awai/loop.cljc        observe -> evaluate -> decide -> act -> record-evidence
bin/awai.cljs             the CLI (nbb; all I/O lives here)
journal/                  append-only evidence; current window in git
deploy/                   LaunchAgent residency on murakumo
```

## Where it sits

```
kotoba-lang/yakuwari        the role model — validation, HIL vocabulary, capacity
kotoba-lang/yakuwari-view   the display projection, shared by both surfaces
loop-yakuwari              ← this repo: awai's instances, the loop, residency
network-awai/person-*       the 15 outward roles' operating identities
```

A `loop-*` repo is a **continuous orchestrator** and
`:must-not [:own-domain-scoring-truth]` (workspace authority
`manifest/repository-rules.edn`, ADR-2607299000). So nothing here computes
whether a role is well-formed, what a capability decision means, or how many
runs a role should have — `kotoba-lang/yakuwari` owns all three. This repo
owns which role goes next, and the data saying what the roles are.

Design: superproject **ADR-2607300800**.

## The six businesses

| business | roles | deliberately absent |
|---|---|---|
| `network-isekai` | director engineer designer marketer supporter | sales — an open creator platform has no named prospects |
| `club-shinshi` | all six | — |
| `net-babiniku` | director engineer designer marketer supporter | sales — consumer self-serve |
| `net-kotobase` | director engineer sales marketer supporter | designer — the surface is an API and runbooks |
| `app-aozora` | director engineer designer marketer supporter | sales — fleet infrastructure, not sold |
| `nexus-x402` | director engineer sales marketer supporter | designer — comes from `x402-directory` |

**Absence is declared, not implied.** `yakuwari.spec` refuses a role with no
objective, because a role nobody can state the purpose of is not reviewable.
The same argument applies to a missing role: a business that decided against
`sales` and one that never considered it look identical unless the omission is
written down. `businesses.edn` carries `:roles-absent` with a reason, so a
reviewer disagrees with a sentence instead of guessing at a gap — and
`awai check` fails if a documented absence turns out to be present.

## The fleet ceiling can only narrow

`fleet.edn`'s `:capability-ceiling` is resolved against each role with
`yakuwari.policy/strictest-of`, so listing a capability there can only
**reduce** a role's autonomy. A business owner reviewing their own roles
cannot escape a fleet bound, and the fleet cannot hand out a permission a
business never asked for.

It is a bound, **not a whitelist**: a capability the ceiling omits is left as
the role wrote it. A whitelist would mean every new capability needed a
fleet-level edit before any business could use it.

`awai ceiling` prints wherever the two disagree. Today it prints nothing —
every role was written at or inside the ceiling — which means the ceiling is
currently doing no work on real data. That is worth knowing rather than
assuming it is load-bearing.

## Use

```sh
nbb bin/awai.cljs check        # cross-file agreement; exit 1 on drift
nbb bin/awai.cljs roles        # every role, one line
nbb bin/awai.cljs ceiling      # where fleet.edn narrows a role
nbb bin/awai.cljs identities   # the person-* repos the registry expects
nbb bin/awai.cljs project      # the display model, as EDN
nbb bin/awai.cljs tick         # one cycle, dry-run
nbb bin/awai.cljs tick --apply # ... and record it
```

Current state:

```
$ nbb bin/awai.cljs check
businesses 6 | roles 31 | outward 15 | narrowed by ceiling 0
check: OK
```

## The decisions worth knowing

**One file per business, not per role.** tamaki keeps one file per role, but
it has eight roles in one flat namespace with no business boundary. Here "does
this business have the right roles?" is the question a reviewer actually asks,
and it cannot be answered from one role's file.

**Role files carry no `:db/id`.** They are plain authored maps, so
`yakuwari.spec/validate` reads them directly and the superproject query-plane
loader adds `:db/id` and `:source/dataset` — the same convention `fleet-db`,
`innen` and `market-intel` follow. Only `90-docs/adr/*.edn` is pre-wrapped
tx-data.

**A global slot budget, not per-business.** Six businesses each given a
comfortable ceiling sum to a number nobody chose, and the real constraint —
runner subscriptions on one machine — binds globally.

**Two bounds on dispatch, not one.** `:global-wip` caps what is live;
`:dispatch-per-tick` caps how fast the fleet accelerates. The first alone lets
one tick fill every free slot with whatever scored highest at that instant.

**The journal feeds the tie-break back.** Without it `:oldest-dispatch` is
decoration: on a cold start every role scores the same, the tie-break key is
uniformly zero, and ordering falls through to alphabetical. Measured — the
first dry-run picked `:app-aozora/director`, and would have picked it every
tick forever while five businesses never ran.

**Every tick is recorded, including the ones that did nothing.** A journal of
only the ticks that acted gives no way to tell "nothing needed doing" from
"the loop stopped running", and those have opposite fixes. Withheld roles
carry a named reason for the same purpose.

**Spawn effects carry the ceilinged policy, not the role file.** Whoever
performs an effect cannot widen a grant by re-reading the role.

**Reaping precedes spawning.** A stale lease occupies a slot, so freeing it
first can make a spawn fit that would otherwise be withheld.

**An invalid role is observed with an error, never dropped.** A role that
vanishes from the observation reads as a role with nothing to do — the one
reading that guarantees nobody fixes it. `:tick/invalid` names such roles in
full, because a count would not say which file to fix.

## Residency

`deploy/install.cljs` installs two LaunchAgents: `tick` every 300 s and
`project` every 900 s. The tick runs on murakumo's own node, the same shape
`cloud-murakumo/organism/` uses — a Cloudflare Worker cannot host a
JVM/Chicory runtime, so the only thing that should reach the edge is the
resulting static artifact.

Being resident **changes latency and cost, never authority**
(`kotoba-lang/yakuwari`). Nothing the installer does grants a role more than
`fleet.edn` allows.

```sh
nbb deploy/install.cljs            # dry run
nbb deploy/install.cljs --apply    # write plists and bootstrap
```

The installer refuses if `../../kotoba-lang/{yakuwari,yakuwari-view}` are not
checked out — otherwise the agent fails every 300 seconds and only the log
says why.

## Test

```sh
npm test    # nbb; needs sibling kotoba-lang/yakuwari
```

29 tests, 52 assertions.

## Status

**The deciding half is real; the acting half is not wired.** `tick` observes,
scores, decides and records evidence against the actual 31 roles. But no
dispatcher turns a `:spawn` effect into an `agent.run`, so `journal/runs.edn`
does not exist and every role projects as `:starved` — desired 1, nothing
running. That label is correct, not a bug: nothing is executing.

What that means concretely:

- `check`, `roles`, `ceiling`, `identities`, `project`, `tick` all work today.
- The 15 `person-*` identity repos are listed by `awai identities`; whether
  they exist yet is a separate step, and their mailboxes need Cloudflare Email
  Routing, which is an owner action with a zone token.
- Journal rollover to DataLad + B2 is specified in `fleet.edn` and not
  implemented — there is nothing to roll over, and moving a small file into
  annex today would only make it unqueryable. See `journal/README.md`.
