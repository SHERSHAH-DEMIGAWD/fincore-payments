# fincore-payments — bounded completion roadmap

Agreed direction after Milestone 15, recorded 2026-10-25. The target is the original **Distributed Payment Processing Platform learning project**, demonstrated through an end-to-end sandbox payment flow. Approximately 25 milestones is a planning estimate; splitting a large block must be explicit rather than silently extending projection-maintenance work.

## Scope already implemented

Milestones 1–15 cover payment lifecycle, PostgreSQL idempotency, double-entry journals, outbox/Kafka delivery, recovery/quarantine, reconciliation, isolated rebuilds, authenticated projection routing/replacement/retirement, evidence planning/preservation and approval lifecycle/offline verification. [Milestone 16](MILESTONE_16_LEARNING_GUIDE.md) adds merchant authentication, owned HTTP operations, scoped retry keys and scoped ledger reads. Its final test evidence is in the [summary](MILESTONE_16_SUMMARY.md).

Milestone 17 adds the [local HTTP sandbox provider flow](MILESTONE_17_LEARNING_GUIDE.md): durable commands, exact retries, uncertainty, lease fencing and confirmed financial application. See the [runbook](PROVIDER_INTEGRATION_RUNBOOK.md) and [final verification summary](MILESTONE_17_SUMMARY.md).

## Milestone blocks and status

Milestone 18 adds [verified incoming provider callbacks](MILESTONE_18_LEARNING_GUIDE.md), durable receipts, duplicate/out-of-order protection and atomic confirmed application, with [sandbox redelivery instructions](PROVIDER_WEBHOOK_RUNBOOK.md) and a [verification summary](MILESTONE_18_SUMMARY.md).

Milestone 19 adds [reliable outgoing merchant webhooks](MILESTONE_19_LEARNING_GUIDE.md), tenant-owned subscriptions, same-transaction event enrollment, signed durable deliveries, bounded retry/fencing and a local receiver; see the [runbook](MERCHANT_WEBHOOK_RUNBOOK.md) and [verification summary](MILESTONE_19_SUMMARY.md).

Milestone 20 adds [partial local payment operations and explicit fees](MILESTONE_20_LEARNING_GUIDE.md): capture-specific refunds, immutable commands, exact totals and atomic operation-linked journals/events. See the [accounting runbook](PAYMENT_ACCOUNTING_RUNBOOK.md) and [verification summary](MILESTONE_20_SUMMARY.md). The provider HTTP/callback protocol remains full amount.

| Milestone | Block | Intended completion evidence |
| --- | --- | --- |
| 16 — complete | Merchant authentication and tenant isolation | Real-credential cross-merchant refusal, scoped retries/balances and compatible legacy migration. |
| 17 — complete | Sandbox payment-provider integration | Real local HTTP, durable request identity, uncertainty/retry/fencing and atomic confirmed financial effects; no live vendor. |
| 18 — complete | Incoming provider webhooks | HMAC/freshness verification, durable exact receipts, duplicate/out-of-order handling and atomic safe state updates. |
| 19 — complete | Reliable outgoing merchant webhooks | Tenant-owned subscriptions, signed notifications, durable delivery/retry and observable failure. |
| 20 — complete | Expanded ledger/payment flows | Explicit accounting for local partial operations and retained capture fees, with balances and atomicity tests. |
| 21 — complete | Settlement and external reconciliation | Independent signed sandbox statements, operation-level discrepancies and fresh-evidence closure without financial mutation. |
| 22 — implemented | Redis-backed controls and fraud/risk processing | Creation amount/currency rules, shared Lua admission quota, durable decisions and explicit outage/retry behavior; real Redis validation remains unexecuted here. |
| 23 — implemented | Observability | Private observer access, bounded flow metrics/traces, sampled backlog freshness and eleven tested alert rules; native Prometheus/OTLP verified, Docker/Jaeger UI unexecuted. |
| 24 — implemented | Backup/restore verification and operational closure | Consistent logical archive, trusted manifest, new-database restore/HTTP drill, read-only comparison and explicit retain-data decision; no purge or full cross-system recovery claim. |
| 25 — implemented | Deployment and final validation | Verified release/JAR binding, isolated Compose recipe, packaged end-to-end sandbox/security/failure/concurrency checks and consolidated documentation; Docker/real Redis execution remains unverified on this host. |

All twenty-five planned functionality blocks are implemented; zero planned implementation milestones remain. M25 now joins the packaged API, provider, signed callbacks, notifications, settlement, partial accounting and real Kafka projection in one bounded native acceptance drill, with a verified release and pinned deployment recipe. See the [final project guide](FINAL_PROJECT_GUIDE.md), [deployment runbook](DEPLOYMENT_RUNBOOK.md) and [final validation report](FINAL_VALIDATION_REPORT.md). The next action is Docker-enabled combined acceptance, especially real Redis and Linux-container behavior; this is an outstanding environment check, not an automatic Milestone26. M24 proves bounded same-host logical reconstruction, not external/off-site/physical recovery. M23 Docker/Jaeger UI remains unexecuted. Bank payouts, provider partial commands, fee tariffs/refunds, authorization release, advanced fraud/grant lifecycle, cross-system disaster recovery and physical deletion remain additional scope. Milestone count is not a production-readiness percentage.

Milestone 21 adds [independent settlement statements and reconciliation](MILESTONE_21_LEARNING_GUIDE.md): signed cumulative provider-journal exports, owned exact-byte imports/downloads, immutable operation-level reports and attributed closure after fresh same-statement matching evidence. See the [runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) and [verification summary](MILESTONE_21_SUMMARY.md). This full-provider sandbox comparison never posts journals or executes bank payouts.

Milestone 23 adds [payment observability](MILESTONE_23_LEARNING_GUIDE.md): isolated read-only observer credentials, fixed flow metrics, sampled traces, cached global backlogs and freshness-aware alerts. Use the [runbook](OBSERVABILITY_RUNBOOK.md) and [verification summary](MILESTONE_23_SUMMARY.md). Metrics/traces do not replace financial evidence, and alerts do not perform recovery.

Milestone 24 adds [logical database recovery](MILESTONE_24_LEARNING_GUIDE.md), an [isolated restore runbook](DATABASE_RECOVERY_RUNBOOK.md), [explicit retention decision](RETENTION_POLICY.md) and [verification evidence](MILESTONE_24_SUMMARY.md). Source/dump share one snapshot; the native drill reconstructs fresh databases and checks protected recovered payment behavior. Reports retain external-resource/deletion limits. No source replacement, purge executor or automatic cutover is introduced.

## Finish criteria

Milestone 22 adds [Redis admission controls and deterministic risk rules](MILESTONE_22_LEARNING_GUIDE.md), with a [runbook](PAYMENT_RISK_RUNBOOK.md) and [verification summary](MILESTONE_22_SUMMARY.md). PostgreSQL remains financial authority; Redis loss/expiry can reset the soft quota. Existing recovery operations and accepted creation retries do not depend on a new Redis decision.

- A merchant authenticates and can access only its own payments, retries, journals, notifications and relevant reports.
- A sandbox payment completes through provider interaction and verified callbacks without duplicate financial effects.
- Durable events, merchant notifications, ledger flows, risk decisions and settlement comparisons have explicit failure/retry behavior.
- A reproducible deployment and demonstration cover recovery and security boundaries; tests and unverified environment limits are reported honestly.
- Learning documentation explains the implemented concepts, choices, alternatives and remaining limitations.

This completion target does not claim certification or readiness to operate a live regulated payment business. Kubernetes, arbitrary consumer corrections, FX, every provider, every account model and every optional maintenance feature are not automatically required to reach the learning-project finish line. New requirements should amend this checklist explicitly.
