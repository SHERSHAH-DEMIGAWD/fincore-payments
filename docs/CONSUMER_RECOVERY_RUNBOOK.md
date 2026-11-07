# Consumer recovery runbook — Milestone 7

Read [event operations setup](EVENT_OPERATIONS_RUNBOOK.md) for the headless `Invoke-EventOps` launcher and database variables. This runbook covers the current payment projection consumer only. See [the learning guide](MILESTONE_7_LEARNING_GUIDE.md) for the model and tradeoffs.

## 1. Inspect and choose

```powershell
Invoke-EventOps failures --state=OPEN --limit=50
Invoke-EventOps gaps
Invoke-EventOps quarantines --state=OPEN --limit=50
```

`failures` includes `wireFingerprint` but excludes record key/value. Investigate preserved evidence through your existing database access. Recovery and gap reports use the configured `FINCORE_KAFKA_SOURCE_NAME`, `FINCORE_KAFKA_TOPIC`, and logical `payment-projection-v1` consumer. Broker consumer-group changes do not erase these identities. Do not rotate namespaces to hide an unresolved incident.

First fix transient dependencies or compatible code errors and allow ordinary retry. `CONSUMER_FAILED` is deliberately ineligible for quarantine. An existing open INVALID_EVENT or EVENT_CONFLICT may be quarantined when you accept an unresolved input gap for this projection. Event-conflict quarantine does not repair or overwrite the previously accepted logical identity.

## 2. Accept one projection gap

Copy the actual failure UUID and `wireFingerprint` from inspection; placeholders below must be replaced. Preserve the request UUID and exact arguments for retries.

```powershell
$failureId = '11111111-1111-4111-8111-111111111111'
$fingerprint = 'COPY_THE_ACTUAL_64_CHARACTER_LOWERCASE_WIRE_FINGERPRINT'
$quarantineRequestId = [guid]::NewGuid().ToString()

Invoke-EventOps quarantine "--failure-id=$failureId" "--expected-fingerprint=$fingerprint" `
    "--request-id=$quarantineRequestId" --operator=local-developer `
    '--reason=Reviewed invalid input; accept a tracked projection gap while investigating' `
    --accept-projection-gap=true

Invoke-EventOps gaps
Invoke-EventOps quarantines --state=OPEN
```

The transaction locks the physical position, checks scope/fingerprint/open permanent failure and inserts an immutable audit. Original evidence and successful-processing markers remain unchanged. The normal running Kafka listener observes this disposition on retry and may acknowledge that position. The CLI itself does not connect a consumer or move an offset. Later records can then progress.

`gaps` remains `DEGRADED` until original replay succeeds. `NO_KNOWN_GAPS` describes this configured scope's tracked incidents, not historical completeness. Lag and gap reports answer different questions; zero broker lag can coexist with a quarantined gap.

## 3. Replay the preserved original after a compatible fix

Use replay when current decoder/consumer code can now accept the original unchanged record. The command reads preserved decoded key/value internally, applies the live validation contract and idempotent projection service, and commits success plus replay audit together. It does not publish a Kafka record or execute payment/ledger operations.

```powershell
$replayRequestId = [guid]::NewGuid().ToString()

Invoke-EventOps replay "--failure-id=$failureId" "--expected-fingerprint=$fingerprint" `
    "--request-id=$replayRequestId" --operator=local-developer `
    '--reason=Compatible decoder fix deployed; reprocess the preserved original'

Invoke-EventOps gaps
Invoke-EventOps quarantines --state=ALL
```

If original validation or processing still fails, the command exits 5, its transaction rolls back, and no successful REPLAY decision is saved. The existing gap remains. There is no replacement JSON, forced resolution, manual offset skip or discard command. Genuinely malformed records can therefore remain open until a separate correction/coverage protocol is designed.

Accepted commands are idempotent: repeat the exact request/action/failure/fingerprint/operator/reason to retrieve the original result, including after success. `replayed=true` means an existing accepted **operator request** was returned; action `REPLAY` identifies the **original-record processing** operation. They are separate meanings.

## 4. Conflicts and operational boundaries

| Result | Response |
| --- | --- |
| Exit 2 INVALID_ARGUMENTS | Fix missing/unknown/duplicate options, UUIDs, fingerprint or bounded single-line text. |
| Exit 3 RECOVERY_CONFLICT | Reinspect: request ID changed intent, decision already exists, record succeeded, or failure is ineligible. |
| Exit 4 FAILURE_NOT_FOUND | Check failure UUID and configured source/topic scope. |
| Exit 5 OPERATIONS_FAILED | No successful command result established; inspect local diagnostics/state before an identical retry. |
| Exit 0 | Accepted command or successful metadata report; inspect action/status/replayed fields. |

Use the updated build for both CLI and listener. Recovery relies on local execution/database credentials; `--operator` is self-declared audit text, not authenticated identity. Do not place secrets in reasons. Audit reasons are visible in quarantine metadata; original payloads remain excluded from CLI output. Pending migrations still run at CLI startup.

After recovery, check the audit, gap report, expected projection version, ordinary failure list and broker lag. Retain evidence; no automatic retention/archive policy exists. These commands are specific to the read projection and do not authorize skipping future accounting consumers.
