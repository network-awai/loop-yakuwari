# Resident Bot readiness contract

Use this contract to review or implement one Bot vertical slice.

| Layer | Required evidence | Common false positive |
|---|---|---|
| Job | stable workforce key, bounded objective, cadence, non-goals | a display profile exists |
| Tool | concrete input/output schema and host implementation | capability prose names an effect |
| Authority | capability-to-tool mapping and enforced decision | the model promises restraint |
| Safety | bounded targets, revalidation, failure-closed cases | an allowlist accepts arbitrary paths |
| Provisioning | stable Bot ID and preserved operator delegation | catalog entry exists but was not projected |
| Scheduling | enabled durable job and observed submission | process health or a future timestamp alone |
| Execution | tool receipt, provider/model attribution, durable outcome | HTTP 200, tool listing, or a prose answer |
| Recovery | healthy no-op after the repair and remaining backlog | one successful mutation run |

## Tool and capability design

- Name capabilities by semantic effect, not implementation detail.
- Use separate read and write capabilities. Observation never implies mutation.
- A tool registry is the execution ceiling. Intersect it with capability
  policy; do not union the two surfaces.
- Refuse unknown, stale, duplicate, over-budget, cross-tenant, or unverified
  targets. Re-check mutable evidence immediately before an effect.
- Keep source repositories, credentials, identities, payments, deployments,
  and destructive storage outside a tool unless the job explicitly owns that
  exact effect and its authority path is enforced.
- If safe operation depends on the model choosing the right prose, the host
  contract is incomplete.

## Verification record

Record these as distinct facts:

1. designed: ADR and declared invariants;
2. implemented: code and negative tests;
3. wired: catalog, capability mapping, tool admission, Skill digest;
4. merged and pinned: upstream commits and generated-manifest agreement;
5. deployed: immutable release and running service;
6. live: actual resident run with durable tool/outcome evidence;
7. stable: follow-up no-op plus remaining backlog and capacity.

Never promote an earlier fact into a later one. A blocked provider path, empty
candidate set, review-required item, or unrelated suite failure remains named
as such.
