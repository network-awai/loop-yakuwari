---
name: itonami-bot-readiness
description: Design, repair, or qualify a Cloud Itonami resident Bot by aligning its role, tools, capability policy, deterministic paths, provisioning, scheduling, and live evidence. Use when a Bot is missing, underpowered, over-authorized, or only apparently healthy.
---

# Itonami Bot Readiness

Treat a Bot as ready only when its declared job, executable tools, authority,
resident scheduling, and observed outcome agree. A profile or prompt is not an
execution grant, and a healthy process is not a successful Bot run.

## Work the smallest coherent slice

1. Observe the current catalog entry, provisioned Bot, admitted tools, durable
   resident job, recent runs, provider attribution, and deployment revision.
2. Write one explicit job objective with measurable completion and non-goals.
3. Split each effect into a semantic capability. Map every capability to one
   concrete host tool; a capability without a runnable tool is descriptive
   only, while a tool without a capability is authority drift.
4. Keep discovery separate from mutation. Mutation inputs should be opaque,
   short-lived evidence receipts rather than paths, commands, selectors, or
   model prose whenever the target can change between inspection and effect.
5. Admit the narrowest decision for each capability: `autonomous`,
   `approval-required`, `voice-required`, or `blocked`. The host must enforce
   the same answer; wording in this Skill cannot widen it.
6. Provide a deterministic, provider-independent path for safety or recovery
   work that must proceed while inference is unavailable.
7. Re-provision without replacing Bot identity, delegation, conversation,
   cadence state, or pending repair triggers.
8. Verify the real chain: focused tests and negative controls, merge, pin,
   deploy, exact release, admitted-tool set, scheduled resident run, durable
   outcome, and the next healthy no-op. Report each boundary separately.

Read [references/readiness-contract.md](references/readiness-contract.md) when
designing a new tool/capability set or deciding whether live evidence is
sufficient. For disk or other destructive maintenance, also use the relevant
domain safety Skill; this Skill never supplies deletion authority.
