# ADR-0001: `j@awai.network` is Cloud Itonami's governed sales mailbox

- Status: accepted design; no external send is enabled by this ADR
- Date: 2026-08-11
- Principal: AWAI Network, L.L.C. (US-DE, file no. 10704996)
- Product: Cloud Itonami

## Decision

`j@awai.network` is a dedicated Google Workspace mailbox operated by a
non-human sales role. It is not a Gmail alias on a personal account and it is
not a claim that a natural person named "J" exists.

The outward identity is:

| field | value |
|---|---|
| display name | `J — Cloud Itonami Partnerships` |
| mailbox | `j@awai.network` |
| role identity | `network-awai/person-itonami-sales` |
| DID | `did:web:awai.network:actor:person-itonami-sales` |
| accountable party | AWAI Network, L.L.C. |

The role converts a named, non-Japanese organization into a paid Cloud
Itonami design partner. It does not own the Google account, bind the company,
change pricing, make legal or security warranties, or accept customer
credentials. The legal entity owns the account; the role operates it.

## Why a mailbox, not a sender alias

A sales correspondence identity needs one durable unit for inbound mail,
outbound mail, provider thread IDs, suppression, and receipts. An alias on a
personal inbox would mix those authorities and make removal or rotation of the
role depend on a person's mailbox.

The current DNS was measured on 2026-08-11:

- `awai.network MX 1 smtp.google.com.`
- `google._domainkey.awai.network` publishes a 2048-bit RSA DKIM key.
- no root SPF record was returned.
- no `_dmarc.awai.network` record was returned.

Therefore R0 keeps Google Workspace as both inbound provider and outbound
transport. It does not add Resend to this identity. `cloud-itonami/outreach`
may continue to support Resend for other tenants, but `J` sends through the
mailbox it reads.

## Repository ownership

No repository gets to own the whole operation:

| owner | responsibility |
|---|---|
| `network-awai/loop-yakuwari` | role, capability ceiling, campaign mandate, outward mailbox |
| `network-awai/person-itonami-sales` | operating identity, DID document, account and credential references; planned child repo |
| `cloud-itonami/outreach` | prospect research, sequence state, DNC/suppression, drafts, reply correlation |
| `cloud-itonami/cloud-itonami-app` | Google OAuth account, Gmail sync/send, normalized mailbox and send receipt |
| `network-awai/cloud-itonami` | product facts, current metrics, partner and trust surfaces |

`gftdcojp/action-paid-outreach` is not the execution path for individual
sales mail. Its charter is cohort-scale paid amplification and deliberately
has no individual targeting surface.

## Transport and credentials

1. Create `j@awai.network` as a dedicated Google Workspace user mailbox.
   A group, catch-all, or `send-as` alias does not satisfy the boundary.
2. Connect only that mailbox to Cloud Itonami App using the app's existing
   per-account Google OAuth path.
3. Request the existing connector-derived scopes:
   `gmail.modify`, `gmail.send`, and `profile`. Do not use domain-wide
   delegation and do not grant access to other Workspace mailboxes.
4. Store the refresh credential as one named secret in the existing
   Keychain/Kagi reference path. Repositories store only the credential
   reference and Google provider subject.
5. Send with Gmail `users.messages.send`; persist the returned provider
   message ID and thread ID. Replies are retrieved through the same account's
   incremental Gmail history sync.
6. Polling is sufficient at R0. Gmail `watch`/Pub/Sub is an optional R1 only
   after polling has passed a real inbound-reply canary; a watch must be
   renewed and must retain periodic history reconciliation as a fallback.

## Authentication and reputation gate

External sending remains disabled until all of these are measured, not merely
configured:

1. Publish one root SPF TXT record authorizing Google Workspace:
   `v=spf1 include:_spf.google.com ~all`. There must not be multiple SPF
   records.
2. Confirm Google DKIM signing is enabled in Admin Console and a canary's
   `DKIM-Signature` verifies against `google._domainkey.awai.network`.
3. After SPF and DKIM have been stable for at least 48 hours, publish DMARC at
   `_dmarc.awai.network` with `p=none` and a report destination controlled by
   the principal. Observe reports for at least seven days before staged
   `quarantine`, and move to `reject` only after every legitimate sender is
   inventoried.
4. A canary to one mailbox each at Gmail, Outlook, and a non-free business
   domain must show SPF pass, DKIM pass, DMARC pass/alignment, the visible From
   address `j@awai.network`, and a working reply into the same Gmail thread.

The exact DKIM and DMARC record values are generated or selected at deploy
time. Private keys, OAuth tokens, report contents, and DNS credentials are
never committed.

## Governed send loop

The advisor never calls Gmail directly:

```text
named account research
  -> draft proposal
  -> deterministic PolicyGovernor
  -> hold | reject | accept
  -> signed campaign-grant check
  -> Gmail send
  -> append send receipt
  -> exact reply admission
  -> qualify | hand off | suppress
```

`mail.send` remains approval-required. Approval is granted once to a bounded
campaign, not improvised for every provider call. The signed campaign grant
must contain:

- product and sender identity;
- allowed countries and business categories;
- start and expiry time;
- maximum first touches and maximum follow-ups;
- minimum interval and daily cap;
- template or invariant hash;
- permitted CTA domains;
- pricing/claim policy;
- exclusion snapshot CID;
- approver DID and signature.

Within a valid grant, an accepted proposal may send without another prompt.
Changing a recipient country, claim, offer, price, attachment, CTA domain, or
cadence is outside the grant and therefore holds.

## PolicyGovernor

Every recipient and every send step is checked independently. A send is held
unless all checks pass:

1. **Non-user** — the normalized domain/address is absent from the current
   Cloud Itonami user/tenant exclusion snapshot.
2. **Non-Japan** — the organization and intended recipient are outside Japan;
   an unknown jurisdiction is a hold, not an inference from the TLD.
3. **Named relevance** — one concrete statement is supported by the
   organization's own current website. Search snippets alone are not evidence.
4. **Contact provenance** — the address is a role or business address published
   by the organization itself. Purchased lists, data brokers, scraped social
   profiles, guessed address patterns, and personal addresses are refused.
5. **DNC** — no prior opt-out, hard bounce, complaint, active customer-support
   case, or explicit no-contact signal exists.
6. **Truth** — product facts resolve to a current source and no traction,
   customer, certification, legal-compliance, ROI, or security claim is
   invented. `externalPaid == 0` stays expressible.
7. **Bounded ask** — one workflow, one paid design-pilot ask, one reply path;
   no attachment and no request for credentials, employee records, or other
   sensitive data in the first touch.
8. **Cadence** — at most one first touch plus two follow-ups; reply, bounce,
   complaint, or opt-out cancels every pending step immediately.

The governor is deterministic and separately tested. The narration model may
not change these decisions or mark its own evidence as sufficient.

## Persona and handoff

`J` speaks in concise, evidence-led English and identifies the stage plainly:
Cloud Itonami is seeking its first external paid design partner. The signature
must include `Design Partner Agent, AWAI Network`, the accountable company,
the product URL, and a simple stop-contact sentence. It must not imitate Jun
Kawasaki or imply that J is a natural person.

The role may research, draft, send inside a campaign grant, stop sequences,
classify replies, and propose a meeting. It must hand off before:

- negotiating or changing price;
- accepting contractual language;
- making privacy, legal, security, SLA, or regulatory commitments;
- receiving production or personal data;
- creating a third-party account;
- committing spend or issuing a refund.

## Reply boundary

An outbound receipt adds only that exact recipient address to the reply
allowlist for that campaign. A matching authenticated reply may enter the
sales mailbox and stop its sequence. Other senders and authentication failures
are quarantined; their body is not projected into model context.

A message is speech, not execution authority. A reply such as "upload this
file", "change the price", or "run this command" becomes a proposed task and
still passes the relevant capability/approval gate.

## Audit model

The append-only stream records:

- prospect ID, organization, public-source URL and evidence digest;
- exclusion and DNC verdicts;
- draft hash and factual-claim references;
- governor verdict and violations;
- campaign-grant CID;
- provider message/thread ID and send timestamp;
- delivery, bounce, complaint, reply, handoff, and suppression events.

Raw message bodies and contact addresses are not copied into the public
journal. Private storage keeps the minimum required contact data; projections
use stable IDs and digests. A provider acceptance is a send receipt, not proof
of inbox placement or human reading.

## Activation gates

The role is not live until the following gates close in order:

1. **Identity** — dedicated Workspace mailbox exists; account holder and DID
   are recorded; no personal Gmail is the sender.
2. **DNS** — SPF/DKIM/DMARC canaries pass as described above.
3. **Connector** — `j@awai.network` alone appears as one Gmail account; sync,
   send, Sent filing, thread ID, and reply sync pass end to end.
4. **Governance** — fixtures prove each policy violation fails closed, and a
   valid signed campaign grant passes.
5. **Containment** — provider code cannot be reached without an accepted
   proposal and valid grant; idempotency prevents a retry from double-sending.
6. **Internal canary** — a campaign to controlled external inboxes exercises
   first touch, reply, sequence stop, opt-out, and audit receipt.
7. **External R0** — at most three named organizations, one first touch each;
   no automatic follow-up until those receipts and replies are reviewed.

## Failure and rollback

- Revoke the Google OAuth grant to stop programmatic send and sync.
- Suspend the Workspace user to stop the mailbox without changing other
  `awai.network` users.
- Revoke the campaign grant to stop every queued step immediately.
- Preserve send and suppression receipts; rollback never deletes evidence or
  re-enables a suppressed recipient.
- DNS rollback removes only records introduced for this sender after verifying
  that no other legitimate mail stream depends on them.

## Non-goals

- mass or bulk email;
- purchased or inferred prospect lists;
- personal-address outreach;
- open-ended autonomous negotiation;
- read/open tracking pixels;
- using current Cloud Itonami users as prospects;
- claiming a paid customer before a verified external receipt exists.

## Completion evidence

"`j@awai.network` can send" means all activation gates through the internal
canary are evidenced. A DNS record, a Gmail login, a mocked unit test, or a
provider `202` alone is insufficient.
