# Event operations runbook — Milestones 6–9

This runbook covers the implemented local operations commands. Read the [learning guide](MILESTONE_6_LEARNING_GUIDE.md) for the reasoning behind them and the [complete project guide](COMPLETE_LEARNING_GUIDE.md) for the payment and ledger foundations.

Milestone 8 adds read-only `reconcile` using the same launcher; see [PROJECTION_RECONCILIATION_RUNBOOK.md](PROJECTION_RECONCILIATION_RUNBOOK.md). It compares source/consumer data and coverage independently of broker lag and source recovery.

Milestone 9 adds isolated `rebuild-create`, `rebuild-step`, `rebuild-status`, `rebuild-validate` and `rebuild-catch-up`; see [SHADOW_PROJECTION_REBUILD_RUNBOOK.md](SHADOW_PROJECTION_REBUILD_RUNBOOK.md). These build a separate latest-state candidate without switching live readers.

## 1. What this block makes possible

Inspect source-event delivery, identify ordering blockers, inspect consumer failure evidence, measure broker/group lag, and requeue one DEAD source event with an immutable audit. Recovery preserves the original event ID and payload. These commands do not capture/refund payments or post ledger entries.

Outbox recovery and consumer failure recovery are different. `requeue` repairs exhausted **source delivery**. It cannot remove a malformed Kafka record, skip a consumer offset, or rebuild a projection.

## 2. Run the local CLI

Use Java 21 and the same database configuration as the API. Build the executable JAR from the `fincore-payments` directory:

```powershell
.\mvnw.cmd verify

function Invoke-EventOps {
    & java '-Dloader.main=com.fincore.payments.operations.EventOperationsCli' `
        '-cp' 'target/fincore-payments-0.0.1-SNAPSHOT.jar' `
        'org.springframework.boot.loader.launch.PropertiesLauncher' @args
}

Invoke-EventOps help
```

Database environment variables are `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Kafka observation additionally uses `FINCORE_EVENT_TRANSPORT=kafka`, `KAFKA_BOOTSTRAP_SERVERS`, `FINCORE_KAFKA_TOPIC`, and `FINCORE_KAFKA_CONSUMER_GROUP`.

Run the updated Milestone 6 application before using requeue; older relay builds do not maintain the new cycle counters. Keep the CLI and normal dispatcher on this build's retry policy.

The CLI starts the Spring application without HTTP, disables the dispatcher schedule and Kafka listeners, and disables Kafka topic auto-creation. It initializes the configured database, including pending Flyway migrations. Read commands do not update business or recovery state after initialization. They do not start background workers.

Results are JSON on stdout; diagnostics and errors go to stderr. All options use `--key=value`; quote the entire argument when a value contains spaces. Commands accept a maximum list limit of 100, default 50. No arbitrary Spring command-line properties pass through this parser.

| Exit code | Meaning |
| --- | --- |
| 0 | Command completed; inspect the returned report status |
| 2 | Invalid/missing argument |
| 3 | Requeue state, generation, or request-identity conflict |
| 4 | Outbox event not found |
| 5 | Operation/startup failed; use local diagnostics |

`lag` can return exit code 0 with `UNAVAILABLE` or another non-`KNOWN` status: it successfully reported that lag could not be established.

## 3. Inspect the source outbox

```powershell
Invoke-EventOps overview
Invoke-EventOps events --status=DEAD --limit=50
Invoke-EventOps events --status=PENDING --limit=50
Invoke-EventOps events --status=ALL --limit=100
```

`events` defaults to `DEAD`. It outputs metadata, including retry generation, lifetime/cycle attempts, oldest earlier undelivered event ID, and latest consumer projection version when one exists. It excludes event payloads.

`overview` obtains all counts in one PostgreSQL snapshot:

| Field | Meaning |
| --- | --- |
| `pending`, `dead`, `delivered` | Source delivery states |
| `ready` | PENDING, due, with no earlier undelivered version of that payment |
| `blocked` | PENDING with an earlier undelivered version, including DEAD predecessors |
| `backedOff` | PENDING, no earlier blocker, but retry time is still in the future |
| `oldestPendingAt`, `oldestReadyAt` | Original occurrence time of the oldest matching event, or null |
| `observedAt` | Database timestamp for this snapshot |

`ready + blocked + backedOff = pending`. A dispatcher may already hold a row counted as ready; this is a state snapshot, not a promise that every ready row is immediately claimable. `DELIVERED` means the selected transport acknowledged it. In Kafka mode it does not establish consumer projection completion.

## 4. Requeue a DEAD source event

First repair the cause, such as broker connectivity. Inspect the DEAD event and copy its **current** `retryGeneration`. Generate one UUID for this recovery request and retain the exact operator/reason values:

```powershell
$recoveryRequestId = [guid]::NewGuid().ToString()

Invoke-EventOps requeue `
    --event-id=11111111-1111-4111-8111-111111111111 `
    --expected-generation=0 `
    "--request-id=$recoveryRequestId" `
    --operator=local-developer `
    '--reason=Broker connectivity repaired; retry original event'

Invoke-EventOps requeues --event-id=11111111-1111-4111-8111-111111111111 --limit=50
```

The event UUID above is a placeholder; use an existing DEAD event. Operator is nonblank and at most 100 characters; reason is nonblank and at most 500. Both must be on one line without control characters. Use factual explanations without credentials.

One successful transaction:

1. Locks the event, verifies DEAD and the expected generation.
2. Sets PENDING, clears the last error, makes it due, resets cycle attempts to 0, and increments generation once.
3. Preserves event ID, payment/version, original payload, and lifetime attempts.
4. Inserts the immutable request/operator/reason/previous-counter audit.

Reuse the **same request ID and identical arguments** if the CLI response is lost. An accepted request replays its original result with `replayed=true`, even if the dispatcher has since delivered the event. A new recovery cycle requires a new request ID and the newly observed generation. Changing arguments under an existing request ID is a conflict.

Do not solve a conflict by repeatedly guessing generations: inspect current state and audit history. Another operator may already have recovered the event. Requeuing does not publish immediately because this CLI never starts the dispatcher. The normal API process must have `FINCORE_OUTBOX_ENABLED=true` and the intended transport configuration.

## 5. Inspect Kafka lag separately

```powershell
Invoke-EventOps lag
```

The report reads the configured topic's end offsets and configured consumer group's committed offsets. A known partition lag is `endOffset - committedOffset`. The group commit represents the next record to process. Report fields include per-partition status and a total only when every partition is known.

| Status | Interpretation |
| --- | --- |
| `DISABLED` | Transport is local; Kafka observation was not attempted |
| `KNOWN` | Required offsets were observed and can be compared |
| `UNINITIALIZED` | At least one partition has no committed group offset |
| `INCONSISTENT` | Offset values/ranges cannot be treated as valid lag |
| `UNAVAILABLE` | Broker/topic/permissions/timeout prevented a complete observation |

Unknown lag is null, never silently zero. The broker and PostgreSQL reports are separate observations; they are not a distributed atomic snapshot. Zero known lag does not prove historical completeness, correct accounting, or that a recreated topic contains old events. Investigation needs source state, projection versions, and failure evidence as well.

## 6. Inspect consumer failures

```powershell
Invoke-EventOps failures --state=OPEN --limit=50
Invoke-EventOps failures --state=ALL --limit=100
```

The default is OPEN. Each failure is identified by source namespace, logical consumer, topic, partition, and offset. Repeated failures update attempts/timestamps without replacing the original key/value evidence. CLI output contains stable error codes and positions, not raw record keys, payloads, or exception messages. Raw diagnostic evidence remains in the database for investigation.

Evidence contains the StringDeserializer-decoded key/value text. It is not a byte-for-byte wire capture; a future byte-preserving quarantine/export protocol needs its own contract.

`INVALID_EVENT` means the event contract failed; `EVENT_CONFLICT` means a durable event identity conflicted; `CONSUMER_FAILED` covers other processing failures. When the same record later processes successfully, failure resolution commits before the listener returns for offset acknowledgement. Resolution means this record processed successfully, not that a retention or archive workflow ran.

Every observed source position also has a compact fingerprint marker, including healthy records. Failure and success bookkeeping lock the same marker, and successful processing cannot be reversed. A late failing attempt from an old partition owner is retained as resolved evidence if another owner already processed that position. This prevents stale OPEN diagnostics during rebalances, at the cost of a stored marker per physical record. Marker/evidence retention is not automated.

The listener retries and blocks by default. Milestone 7 adds explicitly audited quarantine and preserved-original replay for this projection; see [CONSUMER_RECOVERY_RUNBOOK.md](CONSUMER_RECOVERY_RUNBOOK.md). A quarantine permits acknowledgement while retaining a visible gap, with no success marker. Live consumption now commits receipt/projection and position resolution together. There is no manual Admin offset skip, arbitrary replacement JSON, dead-letter topic export or raw-payload export command. Source `requeue` remains separate from consumer recovery.

Set `FINCORE_KAFKA_SOURCE_NAME` to an explicit stable namespace for a broker/topic history; default is `primary`. After replacing the cluster or deleting/recreating the topic, choose a **new namespace** before consuming that new history. Reusing an old position with different raw bytes is rejected rather than overwriting evidence. Namespace rotation separates failure histories; it does not reset the durable event-ID receipt identity.

The group retained in a failure row is its original observed group. Changing the configured Kafka group does not erase logical-consumer receipts, position markers, or old evidence; compare the recorded identity with the group currently used by the lag report.

## 7. Completion checklist for a repaired outage

- The requeue result and audit agree on request ID, event ID, and resulting generation.
- Source delivery eventually becomes DELIVERED and later payment versions become eligible.
- Consumer projection version catches up where Kafka processing is enabled.
- Lag is observed rather than assumed; any OPEN failure is understood.
- Payment and ledger history are unchanged by recovery itself.

For this session's exact verification results, see [MILESTONE_6_SUMMARY.md](MILESTONE_6_SUMMARY.md).
