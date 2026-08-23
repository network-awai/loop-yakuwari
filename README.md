# loop-yakuwari

The operating roles of awai network's businesses — what each role is for,
what it may do alone, and which one gets the next slot.

```
fleet.edn                 global WIP, runner pool, weights, capability ceiling
businesses.edn            every business, its role set, and what it LACKS
workforce.edn             shared job/cadence/capability templates
yakuwari/<business>.edn   authored business-specific roles (templates fill declared gaps)
src/awai/registry.cljc    cross-file agreement + the fleet ceiling
src/awai/loop.cljc        observe -> evaluate -> decide -> act -> record-evidence
src/awai/dispatch.cljc    effects -> tamaki invocations (the acting half)
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

Design: superproject **ADR-2607300800**. The bounded Cloud Itonami sales
mailbox design is [ADR-0001](docs/adr/0001-j-cloud-itonami-sales-mailbox.md).

## The businesses

Two kinds, and they decompose differently.

### The eight startup businesses — decomposed by ROLE

| business | roles | deliberately absent |
|---|---|---|
| `network-isekai` | 8 | sales — an open creator platform has no named prospects |
| `club-shinshi` | 10 | — |
| `net-babiniku` | 9 | sales — consumer self-serve |
| `net-kotobase` | 9 | designer — the surface is an API and runbooks |
| `app-aozora` | 8 | sales — fleet infrastructure, not sold |
| `nexus-x402` | 8 | designer — comes from `x402-directory` |
| `cloud-itonami` | 9 | — |
| `cloud-murakumo` | 9 | — |

### The five creative -ka — decomposed by WORK

One steward per 作品, and no operating roles at all. Two titles in one catalog
share a pipeline but not an audience, a cadence or a reason to exist, so a
single "mangaka engineer" would have to hold five unrelated editorial states
at once — the thing `Act only inside this business` exists to prevent.

| business | stewards | unit | measured 2026-08-19 |
|---|---|---|---|
| `mangaka` | 5 | title | 36 installments live at `mangaka.itonami.cloud` |
| `animeka` | 8 | work | 1 放送中, 1 制作中, 6 企画 at `animeka.gftd.ai` |
| `gameka` | 2 | gameSpec | **host did not answer** (curl exit 000) |
| `dougaka` | 1 | the catalog itself | **host did not answer**; no published works |
| `yukkuri` | 4 | channel | 4 channels hold 123 series |

The role KIND is the published slug stem (`yamainu` → `/work/yamainu-12`), so
a bot name reaches its page without a mapping table.

### etzhayyim — the Tamaki actors, decomposed by ACTOR

One role per Tamaki ActorSpec (`orgs/etzhayyim/tamaki/actors/*.edn`), and the
first business that **names its organization** (`:business/organization
"etzhayyim"`). Cloud Itonami provisions a business with that key only into the
tenant whose slug matches; the thirteen above, which carry no key, keep landing
in whichever personal tenant provisions them.

| role | ActorSpec | admitted repo | cadence |
|---|---|---|---|
| `revenue-growth-supervisor` | `:revenue/growth-supervisor` | `etzhayyim/tamaki` | 720 min |
| `loop-gardener` | `:tamaki/loop-gardener` | `etzhayyim/tamaki` | 720 |
| `result-evaluator` | `:result/evidence-evaluator` | `etzhayyim/tamaki` | 720 (objective restated — says why) |
| `radicle-github-bridge` | `:bridge/radicle-github` | `etzhayyim/tamaki` | 1440 |
| `toshokan-maturity-curator` | `:toshokan/maturity-curator` | `kotoba-lang/toshokan` | 720 |
| `yabai-phishing-watch-curator` | `:yabai/phishing-watch-curator` | `cloud-itonami/yabai` (ActorSpec still names a retired path) | 720 |

Declined, with reasons in `businesses.edn`: `family-representative` (runner is
`:deterministic`) and `storage-curator` (deletes). The objective is copied
**verbatim** from the ActorSpec, and `test/awai/etzhayyim_parity_test.cljc`
fails when a copy drifts without a `:tamaki/objective-differs` reason, when an
actor is neither projected nor declined, or when the Tamaki checkout cannot be
found at all (not found is a failure, not a skip). All six run under the
`:tamaki-resident` profile — same provider and model as `:default`, named so
that the choice is visible and movable in one line.

**The unit is chosen, not inherited.** mangaka gets a steward per TITLE rather
than per installment, and yukkuri per CHANNEL rather than per series —
otherwise the bot count tracks chapter count and grows without a decision
(123 series would be 123 bots). `dougaka` gets ONE steward because it has no
published works; its five internal examples are renderer fixtures, and a
steward each would make this fleet report five works the public cannot reach.

**Two hosts are dark.** `gameka.gftd.ai` and `dougaka.gftd.ai` returned curl
exit 000 on 2026-08-19 — no TLS handshake, which is not the same as a 200
serving an empty catalog. Those stewards carry restoration as their first
bounded outcome and are told to report the measured status code, because a
steward reporting "no works found" against a dark host is a silent pass.

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
nbb bin/awai.cljs workforce    # complete Cloud Itonami Bot catalog, one EDN form
nbb bin/awai.cljs tick         # one cycle, dry-run
nbb bin/awai.cljs tick --apply # ... and record it

nbb bin/awai.cljs runs             # what currently occupies a slot
nbb bin/awai.cljs sync [--apply]   # refresh statuses; release finished trees
nbb bin/awai.cljs dispatch         # a cycle, showing the tamaki argv it would run
nbb bin/awai.cljs dispatch --apply # ... and actually submit and start them
```

`dispatch` needs two machine-local paths that are deliberately not in
`fleet.edn` — a checkout location is not fleet policy, and committing one
makes the file wrong on every other machine:

```sh
export AWAI_WORKSPACE=~/github/com-junkawasaki   # holds tamaki and the business repos
export AWAI_WORKTREE_ROOT=/var/tmp/awai-runs     # per-run trees, OUTSIDE the superproject
```

Current state:

```
$ nbb bin/awai.cljs check
businesses 14 | roles 98 | outward 21 | narrowed by ceiling 4
check: OK
```

`workforce` is the deterministic projection consumed by Cloud Itonami. It
contains responsibility and capability-policy metadata, not execution grants:
the resident app independently admits one business Git root and retains its
own approval governor. Missing source or an invalid partial catalog fails the
whole provisioning operation instead of creating half a company.

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
`project` every 900 s. The installer records the workspace, an external
`~/.cloud-itonami/awai-worktrees` root, and the qualified Codex binary in the
resident environment. The tick runs on murakumo's own node, the same shape
`cloud-murakumo/organism/` uses — a Cloudflare Worker cannot host a
JVM/Chicory runtime, so the only thing that should reach the edge is the
resulting static artifact.

Being resident **changes latency and cost, never authority**
(`kotoba-lang/yakuwari`). Nothing the installer does grants a role more than
`fleet.edn` allows.

For a dedicated resident checkout, set `AWAI_WORKSPACE` while installing.
The LaunchAgent then reads business repos and Tamaki from that workspace while
keeping this loop's mutable journals outside the shared checkout.

```sh
nbb deploy/install.cljs            # dry run
nbb deploy/install.cljs --apply    # write plists and bootstrap
```

The installer refuses if `../../kotoba-lang/{yakuwari,yakuwari-view}` are not
checked out — otherwise the agent fails every 300 seconds and only the log
says why.

## Test

```sh
npm test    # nbb; needs sibling kotoba-lang/yakuwari and the superproject (or AWAI_WORKSPACE)
```

56 tests, 181 assertions (measured 2026-08-23). The entry point is
`test/run_tests.cljs`; until 2026-08-23 `npm test` read the return value of
`clojure.test/run-tests`, which on nbb is nil, so it exited 0 on every failure.
The runner now takes the summary from `report` and exits 1 on a failure, 2 when
no test ran at all.

## Status

The admitted execution path is Tamaki's subscription-backed Codex runner.
`dispatch` turns a `:spawn` effect into a real Tamaki AgentRun — submit,
isolated worktree, detached start, `journal/runs.edn`, status refresh, and a
preserved proposal — and refuses before submission when `tamaki doctor` does
not report the selected runner ready.

A successful clean run must contain a commit beyond its recorded base. The
worktree is then released while its `awai/...` proposal branch and an
append-only `journal/proposals.edn` record remain for review. Any dirty run,
including one that timed out after producing a useful patch, keeps its
worktree so generated work is never erased. Clean failed and no-change runs
release both worktree and branch.

That refusal is the design, not a workaround. A run submitted into a runtime
that never starts it stays `:queued`, and `reconcile/stale-run?` only reaps
`:leased` runs — so it would hold its slot forever, and six of them would stop
the fleet silently. Checking first turns a permanent jam into one line of
output. `sync` names any that slip through anyway as `STUCK`.

Measured 2026-08-07, because both numbers change the design:

- `tamaki status` (all runs) is **5.2 MB / 20,804 lines / 3m04s** — longer
  than this loop's own 300 s tick. `refresh` therefore asks per run
  (`status <id>`, 1 KB / 1m26s), so the cost is bounded by `:global-wip`
  instead of by the size of tamaki's history, and the ordinary state — no
  live runs — costs nothing.
- tamaki's `:local` mode runs the agent **in `--project` itself**; only
  `actor reconcile` prepares a worktree. Pointing it at `orgs/<org>/<repo>`
  would let a parallel session's `git checkout` revert uncommitted work, so
  `dispatch` branches its own tree from the business repo's freshly fetched
  default branch and refuses outright when `AWAI_WORKTREE_ROOT` is unset.
  There is no fallback, because the fallback would be the shared checkout.

What that means concretely:

- `check`, `roles`, `ceiling`, `identities`, `project`, `tick`, `runs`,
  `sync`, `dispatch` all work today.
- Roles project as `:starved` until their first admitted run; the resident
  tick then advances them one bounded execution at a time.
- The 15 `person-*` identity repos are listed by `awai identities`; whether
  they exist yet is a separate step, and their mailboxes need Cloudflare Email
  Routing, which is an owner action with a zone token.
- Journal rollover to DataLad + B2 is specified in `fleet.edn` and not
  implemented — there is nothing to roll over, and moving a small file into
  annex today would only make it unqueryable. See `journal/README.md`.
