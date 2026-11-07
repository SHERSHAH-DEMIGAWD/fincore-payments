# Milestone 8 — Read-only projection reconciliation and coverage assessment

Implemented 2026-10-22 in independent **fincore-payments**. Read the [complete project guide](COMPLETE_LEARNING_GUIDE.md) for all milestones, the [reconciliation runbook](PROJECTION_RECONCILIATION_RUNBOOK.md) for commands and the [session summary](MILESTONE_8_SUMMARY.md) for exact verification results.

## Why the platform needs reconciliation

The earlier milestones protect writes and retries, but operational counters answer different questions. An outbox row can be DELIVERED after LOCAL acceptance without any Kafka projection. Broker lag can be zero after an explicit quarantine. A projection can have the correct latest version while earlier event receipts are missing. A receipt can remain even when a replaceable projection row has been deleted.

Reconciliation compares these durable facts instead of treating one signal as proof of the others. `ProjectionReconciliationService.reconcile` now reports observed source/consumer discrepancies and retained coverage, without modifying the data it examines. This makes the next rebuilding decision reviewable.

## What is being compared

```text
EventOperationsCli reconcile
  → ProjectionReconciliationService.reconcile
  → read-only REQUIRES_NEW / REPEATABLE READ transaction
      ├─ candidate count: source ∪ consumer projections ∪ receipts
      ├─ bounded UUID page: source events ↔ logical receipts ↔ projection
      └─ configured + other-scope failure/quarantine context
  → metadata-only report + coverage limits + next-page cursor
```

Source `payments` remains authoritative. `payment_outbox` describes retained source facts. `consumed_payment_events` records accepted logical events for a consumer. `payment_event_projections` is its replaceable latest-state read model. Failure/position/audit tables describe physical input incidents and recovery decisions. Their identities and lifetimes differ.

The report joins co-located PostgreSQL tables. It does not read Kafka offsets, call a provider or reconcile actual bank settlement. A future split into separate databases requires a different observation protocol.

## Concepts, impacts and alternatives

| Concept / choice | Practical effect in this block | Alternative and why it fits less well here |
| --- | --- | --- |
| Reconciliation before repair | Exposes disagreements and coverage before any source-backed correction is designed. | Automatic overwrites would require decisions about source authority, identity conflicts, missing historical inputs and audit ownership that this phase has not established. |
| Repeatable Read / MVCC | Candidate totals, per-payment comparisons and global gap context see one stable committed snapshot while writers continue. | READ COMMITTED gives each query a fresh view; a capture between count and comparison could make one report describe different source versions. |
| Read-only REQUIRES_NEW | Report owns its isolation and read-only transaction even when invoked by a caller already doing other database work. | REQUIRED could join a caller's weaker isolation or write transaction. The separate transaction costs another connection when nested; it is not a general default for business services. |
| Bounded page and timeout | At most 100 output payment rows, one extra row for `hasMore`, and a 30-second transaction timeout. | Loading every payload into Java would grow memory with database size. Full counts/scans still cost work here; bounded output does not make total query cost constant. |
| UUID keyset pagination | Continuation uses `payment_id > afterPaymentId` and reports truncation explicitly. | OFFSET pagination repeatedly walks skipped rows and can shift as rows are inserted. Separate keyset invocations still use fresh snapshots; they are not one frozen export. |
| Set union of candidate IDs | Source-only, projection-only and receipt-only payments are visible. | Starting with an inner join from payments would hide consumer-only artifacts which most need investigation. |
| SQL lateral aggregates and EXISTS | Compute source delivery, receipt identity/content and coverage near the data using existing indexes. | One repository query per payment would require many round trips; a single large Java cache would increase memory and comparison complexity. SQL is more involved and needs real PostgreSQL tests. |
| JSONB logical equality | Event/payment/version identity plus full logical JSON content distinguishes matching, missing and conflicting receipts. | Byte comparison rejects harmless JSON field order/number-scale differences. Version-only comparison accepts changed content under the same identity. |
| Independent findings | Latest snapshot agreement, history gaps and global quarantine obligations can coexist. | One global green/red flag would hide which claim was actually checked and could label ordinary async delay as corruption. |
| Metadata-only output | IDs, versions, counts and stable findings support investigation without exporting references or payloads. | Dumping original records would create a separate sensitive export contract and is unnecessary for this report. |

These are scope and correctness choices, not benchmark claims. Other approaches can suit a different workload or a separately designed production reconciliation service.

PostgreSQL freezes a Repeatable Read snapshot at the first data query and reuses it for later reads in that transaction. It avoids blocking ordinary writers through row locks for this report. See [PostgreSQL transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html) and [application-level consistency checks](https://www.postgresql.org/docs/17/applevel-consistency.html). A stable observation does not make a later correction safe automatically; mutation still needs fresh preconditions.

## Three separate forms of coverage

### 1. Retained source version coverage

`retainedHistoryContiguous=true` requires stored local source versions from zero through the current source version, with no holes. The outbox's unique payment/version constraint makes min/max/count useful for this check without generating every missing version in memory. Arithmetic uses PostgreSQL numeric values for range calculations so a very large BIGINT version cannot overflow `version + 1`.

Legacy payments may have no events, or begin emitting at a later version because V4 deliberately did not synthesize history. Such rows remain visible and cannot be called complete. Even contiguous versions do not prove their original occurrence correctness or broker retention. Historical completeness is always `NOT_CERTIFIED`.

### 2. Logical receipt coverage

Every retained source event belongs to exactly one report bucket: matching logical receipt, no identity claim, or conflicting identity claim. A duplicate claiming the same payment/version under a different event ID conflicts. A reused event ID with changed content conflicts. JSONB equality tolerates object order and insignificant numeric scale.

Consumer receipts have only logical consumer identity; they are not keyed by Kafka source namespace or topic. Changing those settings cannot scope old receipts out of the projection. The report says `LOGICAL_CONSUMER_ACROSS_SOURCE_NAMESPACES` and includes unresolved diagnostics from other scopes alongside configured-scope counts.

An unmatched receipt can come from independent producer history in this learning application. It is a finding to investigate, not permission to delete it or assume its source payment was lost.

### 3. Latest projection agreement

The report checks source and projection versions, event identity, status, merchant reference, amount, currency and occurrence time. It separately checks the projection against its referenced receipt, since the existing foreign key identifies the receipt but does not by itself require every projection field to match it.

The current source event must also pass `PaymentEventRecordDecoder` before it can be a replay/rebuild candidate. A V4 envelope can satisfy its older SQL checks while lacking a newer required DTO field; that is reported as CURRENT_SOURCE_EVENT_INVALID. Candidate validation stays inside the report; the payload is never returned.

Money is compared as numeric JSON/NUMERIC, preserving decimal meaning. Source/event timestamps allow one microsecond because existing event creation truncates Instant precision while JDBC may round the stored source time. Projection-to-event/receipt times compare exactly. This tolerance is explicit and limited; it does not permit an arbitrary old timestamp.

## Example: a matching latest state with incomplete history

Suppose the current payment and projection both have version 2. The local outbox retained only version 2, and that event has a matching receipt. The latest projection can be useful and the current source event can be a candidate input for a new latest-state view. Yet versions 0 and 1 are absent.

The report therefore shows HISTORY_STARTS_AFTER_ZERO, `retainedHistoryContiguous=false`, one matching receipt and `historicalCompleteness=NOT_CERTIFIED`. A history-dependent settlement/notification consumer cannot infer that processing only version 2 reconstructs the missing business effects.

## Example: a receipt exists but the projection is missing

Suppose receipt E was already accepted, then the replaceable projection row was lost. Calling `PaymentEventConsumer.consume(E)` again finds the existing receipt and returns false. That is correct normal deduplication, but it does not reconstruct the missing view.

Reconciliation reports PROJECTION_MISSING and LATEST_RECEIPT_NOT_PROJECTED while preserving the receipt. A future rebuild needs isolated storage/processing identity and explicit replay logic. Deleting the receipt to force ordinary processing would discard the very identity boundary that makes normal redelivery safe.

## Why pagination and statuses are explicit

`totalCandidates` describes all candidates under an optional payment filter. The returned page can contain only a subset. `hasMore` and `nextAfterPaymentId` prevent treating a limit as a complete scan. `pageStatus` says only whether returned payment rows have findings; global recovery counts and later pages may still contain problems.

Each page invocation has its own stable transaction, not a shared transaction across CLI processes. A concurrently inserted UUID may sort before the cursor already used. For a frozen export, a future job would need an explicit snapshot/export identity and durable result lifecycle. This interactive diagnostic command makes no such claim.

## Holistic impact and the next implementation

The platform now has a path from safe payment writes, through durable financial/event facts, to idempotent consumer effects, explicit recovery and comparison of persisted outcomes. Milestone 8 matters because operational progress and data agreement can now be examined together without silently changing disputed facts.

The next logical milestone is an **isolated shadow-projection rebuild** with explicit logical identity, validated source coverage, catch-up checks and an audited promotion/rollback design. Reconciliation findings should become its reviewed input. Arbitrary replacement events, forced quarantine resolution, source synthesis and live overwrite remain outside the current implementation.

Retention/archive planning, production operator authentication, provider integration and actual settlement reconciliation remain separate future work. A positive replay-candidate flag is metadata for planning, not an implemented rebuild executor.

## TOPICS TO LEARN FROM THIS SESSION

- Projection reconciliation versus payment/settlement reconciliation
- MVCC and Repeatable Read snapshots
- Read-only transactions and REQUIRES_NEW
- Keyset pagination and snapshot lifetime
- SQL UNION, lateral aggregates and EXISTS
- JSONB logical equality and event identity
- Event-history coverage versus latest-state agreement
- Logical consumer identity and source namespaces
- Deduplication versus projection rebuilding
- Shadow projections and audited promotion
