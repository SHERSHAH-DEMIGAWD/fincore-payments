# Milestone 12 learning guide — Retained evidence review and operating limits

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

Implementation and full verification completed 2026-10-23. Documentation and handoff completed 2026-10-24 after the interrupted session resumed. This continues the [Milestone 11 guide](MILESTONE_11_LEARNING_GUIDE.md) in the independent `fincore-payments` project. See the [operator runbook](PROJECTION_EVIDENCE_RUNBOOK.md) for commands and the [summary](MILESTONE_12_SUMMARY.md) for test evidence.

## 1. Why another milestone was needed

Milestones 9–11 let us build, promote, replace and retire read models without replaying financial operations. Retirement deliberately preserves receipts, projections, frozen manifests and immutable decisions. Repeated replacement therefore creates retained data that needs explanation and review.

The earlier routing fence also compares current source and target views while briefly excluding writes. Correct comparisons can become operationally expensive as those views grow. We need to see what is retained, understand which commands are being accepted or refused, and refuse an oversized new comparison before running its larger queries.

Milestone 12 adds two authenticated GET endpoints and a configurable comparison guard. It **does not delete evidence or grant permission to delete it**. It introduces review information and process-local measurements, not a production monitoring platform or a proven throughput limit.

## 2. Retention review is a separate decision from deletion

`ProjectionEvidenceService.review` reports rebuild targets in the configured source namespace and topic. Each target includes its run identity, current generation, lifecycle, creation time, retirement decision/time, artifact counts and review state. Neither payloads nor merchant references, operator labels or free-text reasons are exported.

The configured `reviewAfterDays` defaults to 30. This is a project review window, not an automatic expiry or a financial/legal retention rule. It starts at **retirement**, not at run creation. A long-lived target retired yesterday should not immediately be treated as old retired evidence.

| Review state | Meaning in this implementation |
| --- | --- |
| `RETAIN_MAINTAINED` | The target still receives live updates, including when baseline serves reads. |
| `RETAIN_ISOLATED` | The target has never been enrolled; this workflow does not cancel isolated builds. |
| `RETAIN_MISSING_RETIREMENT_EVIDENCE` | A retired lifecycle lacks its departing decision/time. |
| `RETAIN_UNCERTAIN_RETIREMENT_TIME` | Retirement appears before creation or after the database observation time. |
| `RETAIN_REVIEW_WINDOW` | A valid retirement has not yet reached the configured review age. |
| `REVIEW_RETIRED_EVIDENCE` | The age threshold has been reached; evidence can be considered in a separate review. |

Every target has `deletionPermitted=false`. The report's assessment is `REVIEW_ONLY_NO_DELETION_AUTHORITY`. Crossing the age boundary does not weaken immutability triggers, remove receipts, reset deduplication, authorize SQL deletion or certify complete event history.

Preservation requirements explicitly include run identities/routing decisions, frozen manifests/checkpoints, target receipts/projections, and baseline/source/recovery evidence. A future purge must define which facts survive and how historical accepted-request replay continues to work.

**Example:** A was replaced by B at revision 3; B was retired at revision 5. A's retirement is the revision-3 `REPLACE` that names A through `previous_run_id`. B's retirement is its own revision-5 `RETIRE`. Using the latest global audit timestamp for both would misstate A's age. `ProjectionEvidenceRepository.page` looks up each departing decision and orders by revision, rather than assuming timestamps establish logical order.

## 3. One report needs one observation snapshot

`ProjectionEvidenceService.review` owns a read-only `REQUIRES_NEW` transaction at Repeatable Read, with a 30-second transaction timeout. The database observation time, route revision, target lifecycle and inventory queries belong to that one committed snapshot. A caller's older or weaker transaction is suspended.

The report takes no routing advisory fence and does not freeze the application tables. A retirement can commit while the report is running; that report continues to describe the earlier consistent state. A later invocation observes the new state. The database test explicitly verifies Repeatable Read and transaction read-only mode while a concurrent retirement completes.

```text
GET /api/v1/operations/projection-routing/evidence?limit=50
→ ProjectionEvidenceController.review
→ ProjectionEvidenceService.review
→ ProjectionEvidenceRepository.observationTime / page / inventory
→ one read-only PostgreSQL snapshot
→ ProjectionEvidenceReport
```

UUID keyset pagination accepts `afterRunId` and a limit from 1 to 100, default 50. The repository reads one extra target to establish `hasMore`; the service inventories only the returned page. A continuation cursor is returned only when another row exists.

Each page is a separate transaction and observation. UUID order is not creation or commit order. New runs sorting before an already-used cursor can be missed in a multi-call traversal, and lifecycle state can change between calls. This is an investigation interface, not a frozen multi-page export. Bounded output also does not imply constant database work: each target's artifact aggregates can inspect its retained history.

## 4. What the inventory measures

For each selected target, `ProjectionEvidenceRepository.inventory` counts all retained generations, manifest items, consumer receipts, projection rows and routing decisions involving that run. A replacement contributes a decision to both the arriving and departing target's inventory, while a decision referring to the same run twice is counted once.

`retainedTextBytes` sums the database-encoded text lengths of:

1. The target consumer's stored receipt payloads.
2. Every retained manifest item's original payload.
3. Every retained manifest item's canonical payload.

It measures these retained text copies, not unique logical events. PostgreSQL `NUMERIC` aggregation and Java `BigInteger` avoid narrowing the sum to a small integer. The UTF-8 integration case uses currency/emoji characters to verify that bytes and Java string length differ.

This value excludes row headers, indexes, source-outbox storage, source snapshots in JSONB, projection fields, audit text, TOAST allocation and free-space behavior. It is **not table size, free disk or reclaimable storage**. A shared relation-size query would answer a different question and cannot directly attribute physical disk use to one run.

V12 adds a partial index for `REPLACE` decisions by departing target/revision and a source/topic/run index for target pages. It changes no evidence rows or lifecycle rules. V1–V11 migrations remain unchanged.

## 5. A comparison budget protects new routing work

`ProjectionOperationsProperties.maxComparisonRows` defaults to 100000, with validated bounds of 1 to 1000000. It is a **per-dataset row budget**, not a total across all datasets.

After acquiring the existing exclusive routing fence and table freeze, each new promote, rollback, replacement or retirement calls `ProjectionRoutingRepository.requireComparisonBudget`. It checks source payments, baseline projection rows, selected target projection rows and selected targets' current-generation manifest items. It counts at most budget + 1 visible rows in each check, then refuses if that dataset exceeds the budget.

Only after the guard passes do the larger manifest/equivalence/source/gap comparisons run. The fence prevents concurrent application writes from changing these datasets between the guard and decision. A refusal returns HTTP 409 with `PROJECTION_COMPARISON_BUDGET_EXCEEDED` and commits no enrollment, route change or audit.

An exact accepted-request replay is recognized before this new-work check. If a promotion was accepted when the data was smaller, retrying that same accepted command later still returns its historical result. It does not authorize a fresh comparison or repeat the promotion. The replay still needs the existing fence and can be temporarily busy.

**Example:** a one-row budget permits a one-payment promotion. A second payment is later created and consumed. Retrying the original accepted promotion returns its original result; a new rollback under that one-row budget is refused. After inspecting capacity and configuring an appropriate larger budget, a new checked decision can proceed. Increasing the budget does not waive any consistency or gap check.

The guard does not cap historical receipt volume, gap-query cost, tuple visibility work, I/O, bytes, all query-plan behavior or total response latency. The 30-second transaction timeout is not a complete HTTP wall-clock SLA. Production measurements remain necessary before choosing an operating budget.

## 6. Measure the operation through transaction completion

`ProjectionRoutingController` wraps the proxied routing service with `ProjectionRoutingMetrics.measure`. The service proxy finishes its transaction before returning. Measurements therefore include deferred commit checks; a method returning internally is not prematurely counted as an accepted decision.

Four fixed action labels are used: `PROMOTE`, `ROLLBACK`, `REPLACE`, `RETIRE`. For each action the snapshot exposes attempts, in-flight calls, accepted decisions, accepted replays, ordinary conflicts, budget refusals, other failures, total elapsed nanoseconds and maximum elapsed nanoseconds.

`System.nanoTime` measures elapsed time without relying on wall-clock adjustments. Short synchronized updates and immutable snapshots keep counters coherent under concurrent calls. The counters have no run IDs, actors, reasons or payload values as labels, so memory use does not grow with distinct requests. Totals saturate rather than wrapping on arithmetic overflow.

**Commit failure example:** a deferred database constraint rejects a promotion after its service body returns. The outer controller measurement records a failure, not an accepted decision. The transaction rolls back enrollment, route and audit together. The native PostgreSQL test injects this exact commit failure and verifies both outcomes.

`GET /api/v1/operations/projection-routing/operating-limits` exposes configuration and these measurements. Their scope is `THIS_PROCESS_HTTP_ROUTING_COMMANDS`:

- Authentication, authorization, CSRF and request-binding failures happen before measurement.
- Direct service calls, CLI commands and arbitrary SQL are outside this metric scope.
- Request parsing, response serialization and network transit are not included in operation timing.
- Counters reset on process restart; `since` identifies the measurement instance's start time.
- They are not durable audit history, cluster-wide totals, latency percentiles, alerts or a Prometheus export.

Accepted routing decisions remain durably recorded in PostgreSQL's immutable audit. Those records and process-local diagnostics have different jobs.

## 7. Alternatives and the tradeoffs retained

| Chosen approach | Alternative | Why it fits here and what it costs |
| --- | --- | --- |
| Review-only age classification | Delete automatically after N days | Preserves evidence until purge dependencies and approvals are defined; storage is not reclaimed. |
| Retirement age plus departing revision | Age from run creation or the latest global decision | Describes the target's own lifecycle and avoids treating a newly retired old build as expired; timestamps still need uncertainty handling. |
| One read-only Repeatable Read observation | Separate Read Committed queries | Keeps route/lifecycle/counts consistent while writers continue; maintains a snapshot and connection for the report. |
| UUID keyset pages | OFFSET or an unlimited export | Bounds returned targets and avoids walking earlier offsets; does not freeze a multi-call traversal. |
| Logical text-copy bytes | Shared-table relation size or string character counts | Gives a per-target logical inventory and handles multibyte text; physical capacity needs separate measurements. |
| Budget + 1 row probes under the fence | Full counts or only a timeout | Refuses oversized new comparisons early and makes the threshold inspectable; it does not bound every query cost. |
| Controller measurement around the service proxy | Count success inside the transactional service body | Includes actual commit failure; covers only controller-invoked routing commands. |
| Four fixed labels and local counters | Per-request tags or a complete monitoring stack | Keeps this authenticated diagnostic block small and bounded; cross-process aggregation, histograms and export remain future work. |

Micrometer/Actuator with an external monitoring backend is a useful future option for standardized export and cluster dashboards. It was not added merely to expose these four local operation summaries. Its wider production capabilities would require endpoint-security, persistence/aggregation and deployment decisions; it is not inherently a worse technology.

## 8. Holistic impact and next work

The platform can now explain retained read-model evidence and place an explicit guard on new routing comparisons. Combined with earlier milestones, it connects financial correctness, asynchronous delivery, recovery, reconciliation, rebuild ownership, target lifecycle and operational review.

It still cannot safely infer a purge from age or a byte total. The next proposed block is an **audited, bounded purge-plan workflow**: freeze explicit target/artifact candidates, state preservation obligations and preconditions, and make the plan reviewable before implementing any physical deletion. Production workload measurements and standard metrics export are separate follow-up work. Merchant identity/security, providers and settlement remain later business milestones.

## TOPICS TO LEARN FROM THIS SESSION

- Evidence retention versus deletion authorization
- Retirement age, revisions and clock uncertainty
- Read-only Repeatable Read snapshots
- Keyset pagination and observation scope
- Logical bytes versus physical database storage
- PostgreSQL numeric aggregation and Java BigInteger
- Partial and composite indexes
- Row budgets and transaction fences
- Transaction proxies and deferred commit failures
- Monotonic clocks and thread-safe metrics
- Metric label cardinality and process-local scope
- Configuration binding and startup validation
