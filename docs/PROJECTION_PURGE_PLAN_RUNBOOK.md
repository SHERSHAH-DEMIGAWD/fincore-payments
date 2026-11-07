# Projection purge-plan runbook

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

Milestone 13 adds immutable **planning evidence only**. A plan/review always has `deletionPermitted=false`. See the [learning guide](MILESTONE_13_LEARNING_GUIDE.md) and [summary](MILESTONE_13_SUMMARY.md).

## Preconditions

Use the same credentials, `PROJECTION_OPERATOR` role and CSRF token/session as the [routing runbook](PROJECTION_ROUTING_RUNBOOK.md). Without configured credentials the endpoints deny access. Use TLS outside loopback. The authenticated principal supplies the actor.

Inspect `GET /api/v1/operations/projection-routing` and the [evidence report](PROJECTION_EVIDENCE_RUNBOOK.md). Pick exactly one retired target in the configured source/topic with `reviewState=REVIEW_RETIRED_EVIDENCE`. The window defaults to 30 days **after retirement**. Isolated builds, warm maintained shadows and younger retirements are refused. Do not change immutable timestamps to bypass the window.

## Create and retrieve

With an unused request UUID, POST `/api/v1/operations/projection-routing/purge-plans`:

```json
{
  "requestId": "11111111-1111-4111-8111-111111111111",
  "runId": "22222222-2222-4222-8222-222222222222",
  "expectedRevision": 3,
  "reason": "Review retained evidence for the retired target"
}
```

Replace the example IDs/revision with current values. Use a nonnegative integral JSON number for `expectedRevision`, not a string/decimal/exponent; it must be explicit. The reason must be nonblank, at most 500 characters and single-line. Normal success is HTTP 200 with `{ "plan": ..., "replayed": false }`. No CLI mutation command was added.

The saved plan contains metadata, retirement decision/time, observed route/policy, attributed reason, four preservation obligations and sorted artifact identities/SHA-256 hashes. It contains no artifact payload bodies. The combined cap is 1,000 records, including run, generations, manifests, receipts, projections and related routing decisions. No partial plan is saved on overflow.

`GET .../purge-plans/{requestId}` returns the frozen plan directly. An exact POST retry returns the same saved plan with `replayed=true`, even if later routing has changed. Reuse with changed run/revision/actor/reason/scope returns a conflict. Busy fencing can temporarily refuse even a retry.

## Fresh review

`GET .../purge-plans/{requestId}/review` compares the saved plan with one fresh read-only Repeatable Read snapshot. `CURRENT_REVIEW_ONLY` means its checked route, policy, retirement evidence and artifact identities/hashes still agree. `STALE_REPLAN_REQUIRED` includes blocker codes; inspect them and create a new plan under a new UUID if appropriate. Never edit an old plan.

Review does not block writers or approve future deletion. Its result describes only that observation, and can immediately become stale. It does not verify an archive or preservation acknowledgement.

It also does not rerun source/baseline reconciliation or certify complete event/recovery history. Use the existing reconciliation and gap tools for those separate observations; a current plan is not a system-health certificate.

## Refusals and preservation

| Response | Operator action |
| --- | --- |
| 400 | Correct missing fields, types, UUIDs or bounded attribution. |
| 401 / 403 | Check configured credentials, role and POST CSRF/session. |
| 404 `PROJECTION_PURGE_PLAN_NOT_FOUND` | Check plan/target identity and configured source/topic. |
| 409 `PROJECTION_PURGE_PLAN_CONFLICT` | Inspect age/revision/reused intent or the 1,000-artifact budget; no partial plan was saved. |
| 409 `PROJECTION_ROUTING_CONFLICT` | The shared routing fence is busy; inspect current activity and retry the same intent. |

Preserve run identities/routing decisions permanently in this workflow. Frozen manifests/checkpoints and target receipts/projections require preservation before any future purge. Baseline, source payments/outbox, ledger and recovery evidence remain outside scope. There is no archive/export, approval, executor, scheduler or physical storage reclamation in this release.

The cap bounds rows, not total payload bytes or all database work. Fingerprints use PostgreSQL JSON representation with UTC timestamp rendering; they are comparison values, not signatures or portable archives. Routing measurements in `/operating-limits` still cover only the four routing actions.
