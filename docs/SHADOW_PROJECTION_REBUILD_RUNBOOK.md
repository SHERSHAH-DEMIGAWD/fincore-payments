# Shadow projection rebuild runbook — Milestone 9

**Current continuation (2026-10-25):** [Milestone 15](MILESTONE_15_LEARNING_GUIDE.md) adds fixed approval expiry, immutable authenticated withdrawal and independent downloaded-file verification. Follow the [lifecycle/verification runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md). Earlier milestone descriptions retain their historical scope; deletion remains unavailable.

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

Use the same headless `Invoke-EventOps` launcher and database configuration as [event operations](EVENT_OPERATIONS_RUNBOOK.md). Run [reconciliation](PROJECTION_RECONCILIATION_RUNBOOK.md) first to understand source coverage and existing incidents. The rebuild reads committed local source snapshots and writes only rebuild metadata and a separate logical consumer's receipts/projections. It does not replay financial commands, publish Kafka records or change which projection the HTTP API serves.

## 1. Create a frozen plan

```powershell
$rebuildRunId = [guid]::NewGuid().ToString()

Invoke-EventOps rebuild-create "--run-id=$rebuildRunId" --operator=local-developer `
    '--reason=Build an isolated latest-payment view for comparison'

Invoke-EventOps rebuild-status "--run-id=$rebuildRunId"
```

Keep the run UUID and exact operator/reason. Repeating the same accepted create command returns its original plan identity/time with `replayed=true`, including after later progress. Changed arguments under that run ID conflict. The generated consumer is `shadow-<run UUID>`; callers cannot supply the live consumer name or an arbitrary target.

Generation 0 uses the run UUID as its request identity. Catch-up request UUIDs must be fresh across all rebuild generations and runs; keep them separate from run UUIDs.

The initial generation is 0. Its manifest freezes one item per local source payment, using that payment's current-version outbox event. Original evidence and the validated canonical event representation are retained privately. Missing/invalid/mismatching current source events become BLOCKED inputs; they are not fabricated or silently treated as successfully processed. Legacy/partial historical coverage remains explicit.

The stored block codes are `NO_CURRENT_SOURCE_EVENT`, `INVALID_SOURCE_EVENT` and `SOURCE_SNAPSHOT_MISMATCH`. CLI reports expose aggregate blocked counts, rather than an item or payload export. `historyIncompleteItems` counts incomplete retained version sequences; even a zero count does not certify historical completeness.

Planning captures a consistent source snapshot and may scan all source payments; it is not a constant-time operation. Status/output shows counts rather than record payloads. The current source namespace/topic is bound into the run and must remain the intended configuration for its commands.

## 2. Apply bounded, resumable batches

```powershell
Invoke-EventOps rebuild-step "--run-id=$rebuildRunId" --limit=50
Invoke-EventOps rebuild-status "--run-id=$rebuildRunId"
```

Repeat until `pendingItems` is zero. Limits default to 50 and permit 1–100. A step locks the run, consumes eligible items into its generated consumer identity, and commits receipt/projection writes and item progress together. Concurrent steps serialize. A failed transaction leaves its batch pending, so it can be retried after the cause is fixed.

Each step decodes the preserved original payload again and requires its canonical JSON content to match the representation frozen during planning. It also verifies the resulting shadow projection before advancing the item. A decoder change that reinterprets the frozen contract causes a conflict rather than silently changing the plan.

A repeated step continues remaining work; it does not replay the previous CLI response. Applied event receipts and item markers prevent duplicate database effects. Accepted create/catch-up request replay and resumable batch execution are different guarantees.

`BUILT` describes a completed frozen generation with no blocked inputs. It does not establish that the source has stopped changing or authorize a live cutover. `BLOCKED` retains input problems after eligible work is exhausted. A frozen blocked item stays blocked; after addressing the cause, use catch-up to classify a new generation. Do not edit immutable manifest rows or delete receipts to force progress.

## 3. Validate independently

```powershell
Invoke-EventOps rebuild-validate "--run-id=$rebuildRunId"
```

Validation compares the current manifest, shadow receipts/projections and current committed source, including source additions/version/content drift and unexpected/missing shadow artifacts. It also reports current live-consumer recovery gaps, including other source scopes. No validation command mutates data or closes an incident.

| Status | Meaning |
| --- | --- |
| `BUILD_INCOMPLETE` | Pending manifest work remains. |
| `BLOCKED_INPUTS` | Current generation retains ineligible source inputs. |
| `SHADOW_MISMATCH` | Stored shadow artifacts do not agree with the planned inputs. |
| `SOURCE_CHANGED` | Current source no longer agrees with this frozen generation. |
| `MATCHES_CURRENT_SOURCE_WITH_OPEN_GAPS` | Latest-state agreement was observed, while live-consumer recovery incidents remain. |
| `MATCHES_CURRENT_SOURCE` | Latest-state agreement was observed for this database snapshot. |

Read individual counts as well as status. `historicalCompleteness` remains `NOT_CERTIFIED`. An isolated run reports `promotion=REQUIRES_AUTHENTICATED_PROMOTION`; an enrolled run reports `ENROLLED_LIVE_FANOUT`. A later source write can make a just-validated run stale. These observations are not a transactional authorization to switch readers; Milestone 10's [routing API](PROJECTION_ROUTING_RUNBOOK.md) performs fresh fenced comparisons.

The table lists status precedence from top to bottom: pending work can coexist with blocked inputs or source drift. `rebuild-status` returns the recovery-gap counts captured when that generation was planned; `rebuild-validate` reads the current recovery-gap counts independently.

## 4. Catch up through a new audited generation

Once the current generation has no pending work, copy its current `generation` from status and create one request UUID:

```powershell
$catchUpRequestId = [guid]::NewGuid().ToString()

Invoke-EventOps rebuild-catch-up "--run-id=$rebuildRunId" --expected-generation=0 `
    "--request-id=$catchUpRequestId" --operator=local-developer `
    '--reason=Capture source changes since the initial plan'

Invoke-EventOps rebuild-step "--run-id=$rebuildRunId" --limit=50
Invoke-EventOps rebuild-status "--run-id=$rebuildRunId"
Invoke-EventOps rebuild-validate "--run-id=$rebuildRunId"
```

Replace the sample expected generation with the actually observed value. Catch-up freezes a new full source manifest at generation +1 while retaining earlier generation evidence. It uses the same shadow identity, so unchanged event receipts deduplicate and newer snapshots advance the shadow version. A new payment enters the new manifest even if its UUID would sort before an old batch cursor.

Reuse the identical request ID and arguments after a lost response. Its accepted result retains the original generation/time, even after later catch-up requests; it is recognized before enforcing current-generation preconditions. Reusing that ID with different arguments, supplying a stale generation for a new request, or requesting catch-up with pending work conflicts. A current generation containing only applied/blocked items can catch up. A previously blocked source payment can become eligible in a new generation after its current event passes validation; historical coverage and live quarantine obligations are still not erased.

## 5. Errors and remaining boundaries

- Exit 2: invalid UUID, generation, audit text, option or limit; rejected before application startup.
- Exit 3: state/request/identity conflict; inspect status and preserve the intended command identity.
- Exit 4: run not found, including a run bound to a different source namespace/topic.
- Exit 5: processing, persistence or startup failure; inspect diagnostics/state and retry only after understanding the cause.
- Exit 0: command/report succeeded; inspect its counts and validation status.

The CLI still disables HTTP, listeners, scheduling and topic creation. Database startup applies pending Flyway migrations. Local execution/database credentials remain its access boundary, and CLI operator labels are self-declared audit values. The separate servlet routing API uses an authenticated principal. Do not put credentials in reasons.

Planning (`rebuild-create` and `rebuild-catch-up`) uses a 60-second transaction timeout and allows at most three complete attempts, retrying PostgreSQL `40001` serialization failures or `40P01` deadlocks. Each attempt gets a new Repeatable Read snapshot. Steps use a 30-second Read Committed transaction; status/validation use 30-second read-only Repeatable Read transactions. Those commands do not use the planning retry loop. These are transaction settings, not a total CLI wall-clock deadline: startup and multiple planning attempts add time. A timeout or exhausted retry rolls back the attempt; inspect status, then reuse the original command identity after correcting the cause.

Existing live receipts, projections, physical-position markers, failures, quarantine audits, payment/ledger records and source delivery metadata are preserved. If a shadow projection is damaged after its receipt exists, ordinary duplicate processing does not reconstruct it; validation must expose the disagreement. A separate run with a fresh identity is the recovery boundary for such a rebuild.

There is no local CLI routing switch. Milestones 10–11 supply a separate authenticated [HTTP routing workflow](PROJECTION_ROUTING_RUNBOOK.md) with fresh comparisons, fencing, immutable audit and atomic reader/maintenance decisions. Enrollment permanently freezes a run's rebuild metadata; further step/catch-up mutations conflict, even after retirement. Valid normal consumption maintains baseline and one current shadow together, including after rollback. Its projection can advance beyond its frozen manifest, so later rebuild validation may report historical drift. Replacement transfers maintenance to a fresh isolated run after explicit baseline rollback; retirement clears maintenance while retaining all target data. Retired targets report `RETIRED_EVIDENCE_RETAINED` and cannot be enrolled again. Route status identifies current ownership; authenticated target inspection distinguishes isolated, maintained and retired runs. See the [Milestone 11 guide](MILESTONE_11_LEARNING_GUIDE.md).

Cancellation/deletion, automatic scheduling, retention and raw-payload export remain outside this CLI. Keep immutable generations for review. Source, ledger, receipt, offset and incident evidence must not be altered to force a promotion.

Milestone 12 adds authenticated [HTTP evidence review and operating limits](PROJECTION_EVIDENCE_RUNBOOK.md). The inventory reports all retained generations for each selected run and never grants deletion authority. A configurable row budget now protects new routing decisions; it does not limit rebuild planning/steps or turn bounded report output into a fixed total database cost. There are no new CLI commands in that milestone.
