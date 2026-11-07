# Projection evidence review and operating limits runbook — Milestone 12

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

These authenticated reports inspect retained target evidence and routing-command diagnostics. They do not remove data or authorize deletion. Read the [learning guide](MILESTONE_12_LEARNING_GUIDE.md) for the concepts and tradeoffs and the [summary](MILESTONE_12_SUMMARY.md) for verification.

## 1. Configure the application

Use the existing [projection operator credential setup](PROJECTION_ROUTING_RUNBOOK.md#1-configure-the-servlet-operator-boundary). The configured account and `PROJECTION_OPERATOR` role protect all endpoints below. With neither credential configured, access is denied. Partial/invalid credential configuration fails servlet startup. Payment endpoints retain their existing public learning behavior.

Optional application environment variables:

| Variable | Default | Valid values | Effect |
| --- | --- | --- | --- |
| `FINCORE_PROJECTION_REVIEW_AFTER_DAYS` | 30 | 1–3650 | Age after a target's retirement before it becomes a review candidate. |
| `FINCORE_PROJECTION_MAX_COMPARISON_ROWS` | 100000 | 1–1000000 | Maximum rows in each protected comparison dataset for a new routing decision. |

Invalid values fail application startup, including headless application contexts. These are application environment settings; Spring does not automatically load the Compose `.env` file. The review window is a review policy, not an expiry or regulatory retention period. Apply changes by restarting the application with the intended configuration. No HTTP endpoint changes these limits.

## 2. Open an authenticated reporting session

```powershell
$routingBase = 'http://localhost:8080/api/v1/operations/projection-routing'
$routingCredential = Get-Credential -Message 'Configured projection operator'
$routingBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $routingCredential.UserName + ':' + $routingCredential.GetNetworkCredential().Password))
$routingHeaders = @{ Authorization = 'Basic ' + $routingBasic }

$operatingLimits = Invoke-RestMethod -Uri "$routingBase/operating-limits" -Headers $routingHeaders
$evidencePage = Invoke-RestMethod -Uri "$routingBase/evidence?limit=50" -Headers $routingHeaders
$operatingLimits
$evidencePage
```

These GET requests do not require a CSRF token. Mutating routing commands still require the token/session described in the routing runbook. Keep credentials and headers out of logs and use HTTPS outside loopback.

## 3. Interpret and continue an evidence page

The response contains database `observedAt`, configured source/topic, snapshot route revision, review-window setting, preservation requirements, target metadata/inventory, `hasMore` and `nextAfterRunId`.

```powershell
if ($evidencePage.hasMore) {
    $cursor = [Uri]::EscapeDataString($evidencePage.nextAfterRunId)
    $evidencePage = Invoke-RestMethod -Uri "$routingBase/evidence?limit=50&afterRunId=$cursor" `
        -Headers $routingHeaders
}
$evidencePage.targets | Select-Object runId, lifecycle, retiredAt, reviewState, deletionPermitted
```

The default limit is 50; permitted limits are 1–100. Follow the returned cursor only when `hasMore=true`. A final page has no continuation cursor. Each invocation has its own Repeatable Read snapshot; separate pages are not one frozen export, and UUID order is not commit order.

Only runs matching the application's configured source namespace/topic are listed. Artifact counts cover each selected target's retained generations, manifest items, receipts, projections and routing decisions. `retainedTextBytes` sums receipt text plus original/canonical manifest payload text. It excludes other fields and physical storage overhead and is not a reclaimable-disk estimate. No raw payload, merchant reference, actor or reason is exported.

Maintain the following interpretation:

- `RETAIN_MAINTAINED` / `RETAIN_ISOLATED`: not a retired review candidate.
- `RETAIN_REVIEW_WINDOW`: the target has not reached the review age after retirement.
- `RETAIN_MISSING_RETIREMENT_EVIDENCE` / `RETAIN_UNCERTAIN_RETIREMENT_TIME`: preserve evidence and investigate the metadata/time uncertainty.
- `REVIEW_RETIRED_EVIDENCE`: review age reached; still `deletionPermitted=false`.

All responses have `assessment=REVIEW_ONLY_NO_DELETION_AUTHORITY`. Existing immutability and lifecycle rules remain enforced. Do not turn this report into DELETE/TRUNCATE commands; there is no supported purge workflow in this milestone.

## 4. Interpret operating limits and command measurements

`maxComparisonRowsPerDataset` applies independently to source payments, baseline projections, selected target projections and their current manifests. Under the existing routing fence, a new promote/rollback/replace/retire counts at most budget + 1 visible rows per check. A dataset exceeding the limit causes HTTP 409 with `PROJECTION_COMPARISON_BUDGET_EXCEEDED` before the larger comparisons and before any route/audit/enrollment writes.

An exact accepted-request replay returns its historical result without repeating the size/comparison checks. It still takes the fence and can be temporarily busy. A new request cannot use replay as permission to bypass current checks.

On a budget refusal, inspect current data and operating capacity. If a larger supported limit is appropriate, configure it deliberately and restart; then submit the intended new decision with fresh observed state. Raising the limit does not bypass source freshness, target agreement, known gaps or authentication. The row limit is not a bytes/I/O/query-cost bound or a production latency guarantee. Evidence aggregation is not subject to this routing comparison budget; its page/output bound and 30-second read-only transaction timeout still apply.

`httpCommands` contains four action rows with attempts, in-flight count, accepted, replayed, conflicts, budget refusals, failures, total elapsed nanoseconds and maximum elapsed nanoseconds. Scope is **this process's HTTP routing commands**, measured from handler invocation around the transactional service through its commit/rollback outcome. Authentication, CSRF and binding refusals, direct service calls, CLI work, network transit and response serialization are excluded.

Counters reset on restart; use `since` to recognize a new measurement instance. Do not add snapshots as though they were event batches. These are cumulative local counters, not cluster totals, percentiles or durable audit records. The immutable routing audit remains the accepted-decision history.

## 5. Errors and boundaries

| Result | Meaning / next action |
| --- | --- |
| 401 / 403 | Check configured credentials and operator role; POST also needs CSRF/session. |
| 400 | Correct invalid cursor, parameter type or limit; supported page size is 1–100. |
| 409 `PROJECTION_COMPARISON_BUDGET_EXCEEDED` | A new routing comparison exceeds the configured per-dataset row budget. |
| Other routing 409 | Existing contention, freshness, intent or consistency rules refused the decision. |
| 500 | Report/command could not complete; inspect server diagnostics. No partial report is returned as a successful snapshot. |

Reporting is read-only and does not need a broker connection. No new CLI command, scheduler, deletion API, persistent metric history, monitoring exporter, full historical-coverage certificate or production benchmark was added. V12 creates lookup indexes while preserving V1–V11 migrations and evidence. Use the updated application when relying on the new guards; mixed-version operation is not validated.

Milestone 13 implements the [bounded audited planning step](PROJECTION_PURGE_PLAN_RUNBOOK.md), with explicit preservation obligations and fresh review. Verified preservation/export and authenticated approval are the next steps; physical deletion remains a later implementation decision.
