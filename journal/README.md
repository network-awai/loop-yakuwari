# journal

Append-only evidence, one EDN map per line.

```
current.edn   the current window — committed to git, always queryable
runs.edn      live agent runs, when a dispatcher writes them (absent today)
```

## Why the current window lives in git

The owner asked whether this data could live in GitHub and DataLad as EDN,
Datomic-queryable. The answer for **definitions** is no, and for the
**journal archive** yes — the split is deliberate and was measured rather
than assumed.

`manifest/west.yml`'s `group-filter` carries `-datalad`, so a project in the
`datalad` group is **absent from the working tree** after a default
`west update`. Verified 2026-07-30:

```
ABSENT  orgs/cloud-itonami/cloud-itonami-gtm-data
ABSENT  orgs/cloud-itonami/gftd-audio-actor
ABSENT  orgs/cloud-itonami/gftd-avatar-actor
```

`manifest/edn-query.cljs` builds its DataScript database by slurping the
working tree and skipping absent files. So anything in git-annex yields
**zero entities** for a reader who has not run `annex-get` — while `count`
still reports a healthy-looking number. Small EDN in annex costs
queryability and buys nothing; git handles 31 role files and a bounded
journal window without help.

## What DataLad is actually for here

The journal is the only thing that grows without bound. `fleet.edn` sets
`:journal/rollover-at-bytes`; past it, `current.edn` rolls over and the
archive moves to a DataLad dataset with a Backblaze B2 special remote.

| tier | where | queryable |
|---|---|---|
| definitions (`fleet.edn`, `businesses.edn`, `yakuwari/*.edn`) | plain git | always |
| current journal window | plain git, append-only | always |
| rolled-over archive | DataLad + B2, `datalad` group | after `annex-get` |

The current window stays in git **on purpose**, so "what is happening now"
never requires a fetch. Losing the archive costs history, and that cost is
stated here rather than discovered.

**Rollover is not implemented yet**, and deliberately so: there is nothing
to roll over, and moving a small file into annex today would only make it
unqueryable. The threshold is recorded so the decision is already made when
it matters.

## Format

One EDN map per line — the same pseudo-JSONL shape
`manifest/edn-query.cljs`'s `slurp-edn-lines` already reads, chosen so the
superproject query plane needs no new reader for it. Blank lines and
`;` comments are skipped.

```clojure
#:tick{:at 1785382475531
       :of :awai/yakuwari-fleet
       :active 0 :free 6
       :dispatched [:app-aozora/director]
       :spawned 1 :reaped 0
       :withheld-summary {:tick-allowance-spent 30}
       :invalid []}
```

An entry is appended on **every** tick, including ticks that dispatched
nothing. A journal recording only the ticks that acted gives no way to tell
"nothing needed doing" from "the loop stopped running", and those have
opposite fixes.

`:withheld-summary` is counted so a quiet tick stays one line, but
`:invalid` names roles in full — a count would not say which file a human
must fix.

## It feeds the loop back

`last-dispatch` in `bin/awai.cljs` folds this file to find when each role
last ran, which is what makes `fleet.edn`'s `:tie-break :oldest-dispatch`
real. Without it every role scores equal on a cold start, the tie-break key
is uniformly zero, and ordering falls through to alphabetical — measured:
the first dry-run picked `:app-aozora/director`, and would have picked it
every tick forever while five businesses never ran.
