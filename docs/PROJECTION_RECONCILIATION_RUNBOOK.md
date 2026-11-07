# Projection reconciliation runbook — Milestone 8

Use the existing headless `Invoke-EventOps` launcher from [event operations setup](EVENT_OPERATIONS_RUNBOOK.md). This command reads the co-located PostgreSQL source/consumer tables. It requires no live broker, publishes nothing and performs no repair. Normal application/CLI startup still runs pending Flyway migrations; the reconciliation transaction itself is read-only.

## 1. Run a report

```powershell
Invoke-EventOps reconcile --limit=50
Invoke-EventOps reconcile --payment-id=11111111-1111-4111-8111-111111111111
```

Replace the sample UUID with a real payment ID. An unknown ID returns zero candidates and an empty page. `--limit` defaults to 50 and permits 1–100. Arbitrary Spring options and repair/mutation flags are rejected before application startup.

For the next page, copy `nextAfterPaymentId` from a response with `hasMore=true`:

```powershell
Invoke-EventOps reconcile --after-payment-id=22222222-2222-4222-8222-222222222222 --limit=50
```

Payment filter and pagination cursor are mutually exclusive. Pages include matching rows as well as rows with findings; do not assume the first page contains every discrepancy. `totalCandidates` counts all candidates under the optional payment filter, ignoring the cursor. Follow `hasMore` until false. Each invocation uses a new database snapshot, so concurrent inserts with IDs before an already-used cursor require a fresh scan. The cursor is not a frozen export/session token.

## 2. Understand scope before interpreting results

| Report field | Meaning |
| --- | --- |
| `observedAt` | Timestamp of the first report query; all report queries use one REPEATABLE READ snapshot. |
| `consumerName` | Durable logical consumer `payment-projection-v1`. |
| `sourceName`, `topic` | Configured scope used to separate current diagnostic gaps from other scopes. |
| `consumerStorageScope` | LOGICAL_CONSUMER_ACROSS_SOURCE_NAMESPACES: receipts/projections have no broker namespace provenance. |
| `configuredTransport` | Current LOCAL/KAFKA setting. LOCAL delivery does not build this projection. |
| `historicalCompleteness` | Always NOT_CERTIFIED; no source/broker-history completeness claim. |
| `pageStatus` | OBSERVATIONS_FOUND or NO_OBSERVATIONS_ON_PAGE; applies only to the returned payment rows. |
| `recoveryGaps` | All open incidents for this logical consumer, split into configured source/topic and other scopes. |

Candidate payment IDs are the union of source `payments`, this consumer's projections and this consumer's receipts. Consumer-only artifacts remain visible. Source outbox rows already reference a source payment through a foreign key. Malformed failure keys are not assumed to identify a payment; diagnostic gaps remain report-level context.

Gap context is global to the logical consumer, including with `--payment-id`. It is not attributed to the filtered payment. `NO_OBSERVATIONS_ON_PAGE` may coexist with open quarantine gaps, pending source delivery or observations on later pages.

## 3. Interpret a payment row

- `sourceVersion` and `projectionVersion` show source/consumer progress independently.
- `sourceEventCount`, first/last source versions and `retainedHistoryContiguous` describe retained local versions from zero through the current source version. Contiguity proves neither broker delivery nor historical event correctness.
- Source events are partitioned into `matchingReceipts`, `missingReceipts` and `conflictingReceipts`. Their sum equals `sourceEventCount`.
- Matching requires event/payment/version identity and JSONB logical-envelope equality. JSON object order and insignificant numeric scale do not manufacture conflicts.
- `unmatchedReceipts` counts this payment's logical consumer receipts without an identical local source event. They may reflect independent source history or a discrepancy; investigate their origin.
- `pendingSourceEvents` and `deadSourceEvents` provide source delivery context. Missing receipts during asynchronous processing are observations, not automatic proof of corruption. DELIVERED acknowledges the selected transport, which may have been LOCAL.
- `latestSourceEventId` identifies the retained current-version event, if present. `latestSourceReplayCandidate=true` means its contract is valid, its projection business fields/time agree with the current source, and no retained source identity conflicts with this consumer's receipts.

Candidate means a possible input to a separately designed replay/rebuild. It grants no mutation permission and does not close quarantine gaps. The comparison uses status, merchant reference, amount, currency, event identity/version and relevant occurrence time; it is not a byte archive or a comparison of every source-only field. Source/event time allows one microsecond for existing event truncation versus JDBC timestamp rounding; projection/receipt occurrence times compare exactly.

Without a retained current-version source event, full projection/source-event agreement is unavailable and CURRENT_SOURCE_EVENT_MISSING is reported. Projection-to-receipt comparison still runs; an absent source event never becomes a positive rebuild-input claim.

## 4. Findings and appropriate next action

| Finding | Investigate |
| --- | --- |
| SOURCE_PAYMENT_MISSING | Consumer-only payment; verify source/database ownership before attempting any correction. |
| NO_SOURCE_EVENTS / CURRENT_SOURCE_EVENT_MISSING | Legacy/event coverage gap; no synthetic event is created by the report. |
| HISTORY_STARTS_AFTER_ZERO / HISTORY_VERSION_GAPS | Retained history cannot establish a full version sequence. |
| SOURCE_EVENT_AHEAD | Retained outbox version exceeds authoritative current source version. |
| CURRENT_SOURCE_EVENT_INVALID / CURRENT_SOURCE_SNAPSHOT_MISMATCH | Current retained event cannot be accepted as a trustworthy current projection input. |
| SOURCE_RECEIPTS_MISSING | Check transport, delivery backlog, consumer lag/failures and subscription history. |
| SOURCE_RECEIPT_CONFLICT / UNMATCHED_CONSUMER_RECEIPTS | Compare immutable identities/history; never overwrite receipts as a shortcut. |
| PROJECTION_MISSING / BEHIND / AHEAD | Compare source events, received versions and pending work. |
| PROJECTION_SNAPSHOT_MISMATCH / PROJECTION_RECEIPT_MISMATCH | Equal version alone did not establish matching business data/receipt. |
| LATEST_RECEIPT_NOT_PROJECTED | A retained accepted receipt has no corresponding latest projection; ordinary deduplicated replay may not rebuild it. |

Use [source operations](EVENT_OPERATIONS_RUNBOOK.md) for exhausted delivery and [consumer recovery](CONSUMER_RECOVERY_RUNBOOK.md) for observed quarantined originals. Reconciliation itself has no requeue, offset mutation, correction, deletion, rebuild or automatic gap resolution path.

## 5. Rebuild planning checklist

1. Export/review all pages for the intended source database and logical consumer; include global recovery context and other namespaces.
2. Decide whether the objective is a latest-state read model or complete historical processing. Full snapshots may support the first with partial history; they do not establish the second.
3. Establish candidate current source events, missing/invalid inputs and identity conflicts. Preserve original histories and unresolved quarantine obligations.
4. Design an isolated shadow projection and distinct logical processing identity. Existing receipts make normal repeated consume a no-op and are not disposable rebuild cursors.
5. Define validation, catch-up and an audited promotion/rollback protocol before writing or replacing a live view.

Only the read-only assessment is implemented in this milestone. Execution of this rebuild plan remains future work. The report's local comparisons must be redesigned if source and consumer storage are deployed into separate databases.
