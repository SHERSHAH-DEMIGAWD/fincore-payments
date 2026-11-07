# Retention and recovery decision — Milestone 24

Decision recorded 27 October 2026 for the independent `fincore-payments` learning project. See the [recovery runbook](DATABASE_RECOVERY_RUNBOOK.md), [guide](MILESTONE_24_LEARNING_GUIDE.md) and [roadmap](ROADMAP.md).

## Current decision

**Retain authoritative financial, identity, delivery, recovery and audit/evidence records. No automatic expiry or physical purge is authorized or implemented.** This is an explicit engineering decision for this project, not a statement of statutory retention periods. Review windows, old age, successful file verification, a restore report and expired approval do not change it.

| Data/resource | Current treatment and recovery obligation |
| --- | --- |
| Payments, immutable retry responses, bindings and commands | Retain with owned identity and original accepted request intent; do not expire keys independently of effects |
| Ledger accounts, headers and entries | Retain balanced immutable postings, including fee/refund relationships |
| Outbox, inbox, consumer failures/recovery and merchant-webhook attempts | Retain delivery identity, uncertainty, leases and retry history; review external acknowledgements before resuming a clone |
| Provider receipts/commands and settlement/risk evidence | Retain exact bytes, identity, policy/report history and attribution; recovery is not a new approval |
| Projection routes, rebuild/evidence plans, copies, approvals/revocations | Retain protected history; existing plan/preservation review grants no deletion capability |
| Database recovery archives/manifests/reports | Protect as sensitive; retain the trusted manifest hash separately and verify independent copies periodically; no automatic expiration supplied |
| Credentials and encryption/HMAC keys | Keep required keys/key IDs and their custody outside the archive in protected storage; DB ciphertext alone cannot restore external signing/decryption capabilities |
| Sandbox provider journal and instance identity | Preserve coherently while stopped using its separate operating procedure; matching PostgreSQL rows cannot recreate missing provider decisions |
| Kafka state/configuration and consumer context | Separate recovery obligation; prevent duplicate processing through existing durable IDs and verify coverage before cutover |
| Redis admission windows | Ephemeral soft capacity; loss can reset quota, so restoration/re-enable requires an explicit consistent policy/namespace decision |
| Prometheus/Jaeger diagnostics | Operational history only; current local retention/transient storage does not replace authoritative backups |

## Closure required before a future deletion or live recovery decision

A future reviewed change must identify exact eligible records/dependencies, trusted independent copies, successful restore scope, required key/resource custody, concurrency fences, immutable authorization and failure/rollback behavior. It must define what happens to retries, ownership, historical evidence and foreign references after removal. The existing offline preservation verifier and M24 report remain `deletionPermitted=false`.

No deletion executor, scheduler, automatic retention job or merchant-facing erase capability is introduced. Do not use administrative SQL/truncation as an application retention mechanism. Disposable test databases/artifacts are separate from operational data and require an explicitly scoped cleanup decision; this milestone's verification retains them.

M24 proves a supported logical database reconstruction into isolated databases on the test host. External resource restoration, off-site custody, a second host, WAL/PITR, emergency cutover and measurable RPO/RTO remain outside that proof. M25 will document final deployment/demo and outstanding validation; it must not silently convert these limits into production-readiness claims.
