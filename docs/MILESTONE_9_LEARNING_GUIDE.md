# Milestone 9 — Isolated shadow projection rebuild and catch-up generations

This guide explains the rebuild block in independent **fincore-payments**. Use the [complete guide](COMPLETE_LEARNING_GUIDE.md) for earlier milestones, the [runbook](SHADOW_PROJECTION_REBUILD_RUNBOOK.md) for commands and the [session summary](MILESTONE_9_SUMMARY.md) for actual verification results.

**Continuation note:** promotion/rollback limitations below describe the Milestone 9 boundary. Milestone 10 subsequently added a separate authenticated [routing workflow](PROJECTION_ROUTING_RUNBOOK.md). Current validation promotion metadata and enrolled-run ownership are documented in the updated rebuild runbook and [M10 guide](MILESTONE_10_LEARNING_GUIDE.md); frozen rebuild manifests remain historical evidence.

## Why rebuilding needed a new boundary

Milestone 8 demonstrated that a missing projection cannot always be repaired by resending an accepted event. `PaymentEventConsumer.consume` finds its existing receipt and returns without applying the projection again. That behavior is necessary for ordinary redelivery safety.

A rebuild therefore gets a new logical identity and isolated target rows. It reuses the established consumer receipt/projection tables under `shadow-<run UUID>`, leaving `payment-projection-v1` and its API reads intact. It does not delete the original receipt or call capture/refund again.

This is a **latest-state rebuild from retained source snapshots**, not replay of a complete event-sourced financial history. One current source event per payment is the planned input. Older missing versions and live-consumer quarantine obligations remain visible. Retained version-sequence counts help identify coverage gaps, but cannot certify that every historical effect is recoverable.

## The workflow and commit boundaries

```text
rebuild-create
  → consistent local source snapshot
  → run + generation 0 + frozen per-payment manifest COMMIT

rebuild-step
  → lock run → read pending items
  → validate original contract
  → shadow receipt + version-guarded projection + APPLIED markers COMMIT

rebuild-validate
  → read-only comparison of manifest, shadow and current source

rebuild-catch-up
  → expected-generation / exact request check
  → fresh source snapshot + immutable generation N+1 COMMIT
  → subsequent bounded steps advance the same shadow identity
```

Planning and batch application are separate transactions. A CLI process can stop between them without losing the plan. The manifest establishes which inputs a generation promised to process; item progress establishes what committed. An operator can inspect both independently.

The implementation entry point is `ProjectionRebuildService`: `create`/`catchUp` materialize and classify the plan through `ProjectionRebuildRepository`; `step` uses `PaymentEventRecordDecoder.decode` and the existing receipt/projection repository under the generated consumer name. It does not invoke a payment transition or route through the live consumer's fixed identity. PostgreSQL deferred checks also require a matching receipt and projection when an item becomes APPLIED.

## Concepts, practical impact and alternatives

| Choice / concept | Where it is used and why | Alternative and tradeoff |
| --- | --- | --- |
| Shadow logical consumer identity | Generated from a run UUID; receipts/projections do not collide with the live consumer. | Reusing the live identity deduplicates instead of rebuilding; deleting live receipts removes a correctness boundary. |
| Materialized immutable manifest | A generation freezes source payment/version/event evidence for resumable processing. | Re-running a moving source query between batches can mix generations or miss newly inserted UUIDs before a cursor. |
| Latest-state source snapshots | One validated current event supplies the projection fields for each source payment. | Full historical replay would need complete coverage that V4 deliberately did not backfill. Snapshot rebuilding cannot reconstruct missing historical side effects. |
| Frozen blocked inputs | Missing/invalid source events remain represented with safe codes. | Dropping them from the plan makes a partial result look complete. Synthesizing an event from a current row invents history/identity. |
| Atomic checkpoint with data | Receipt, projection and per-item APPLIED marker commit together. | Writing a cursor first can skip unapplied work after a crash; writing effects first without transactional progress complicates resumption. |
| Run row lock | Concurrent steps and generation changes serialize for the same run. | Separate JVM locks do not coordinate processes. Leases permit more parallelism but add expiry, ownership and stale-worker fencing. |
| Catch-up generations | A new audited frozen manifest captures source changes without rewriting prior plans. | Mutating the original manifest erases the initial decision. Treating a UUID high-water mark as source commit order can miss concurrent inserts. |
| Generation CAS / request identity | Stale catch-up intent conflicts; identical accepted retries return their original plan result. | Blind refresh can race another operator, while always generating new request IDs obscures whether a lost response already committed. |
| Stable command response versus mutable status | Create/catch-up replay returns original generation/time; status shows current progress. | Returning today's status as an idempotent response changes the meaning of an earlier accepted command. |
| Shared strict decoder / canonical contract | Live processing and rebuilds use the same envelope validation and monetary precision rules. | A lenient rebuild-only parser could accept facts that the live consumer would reject, or reinterpret stored evidence after an upgrade. |
| Read-only validation | Agreement with current source is assessed separately from build completion. | Calling a frozen generation BUILT does not prove a source which continued changing is caught up. |
| Review-only promotion boundary | Reports explicitly say promotion is not implemented. | Immediately replacing the live view would require authenticated ownership, freshness fencing, routing semantics and rollback guarantees not established by a snapshot comparison. |

These comparisons explain fit for this learning application. They are not claims that other designs are universally inferior or that this workflow has been benchmarked for production volume.

Planning records `NO_CURRENT_SOURCE_EVENT`, `INVALID_SOURCE_EVENT` or `SOURCE_SNAPSHOT_MISMATCH` privately and exposes aggregate blocked counts. Blocked evidence is frozen with that generation. Repairing the cause does not reclassify old rows; catch-up makes a new decision from a fresh snapshot. A step compares the newly decoded canonical representation using JSONB content equality, so JSON formatting alone does not change identity, while changed interpreted content is rejected.

## Why generations matter

Consider two source payments:

1. Create freezes payment A at version 0 in generation 0.
2. The live API authorizes A to version 1 and creates payment B.
3. Building generation 0 correctly produces A version 0. Validation observes SOURCE_CHANGED.
4. Catch-up generation 1 freezes A version 1 and B version 0.
5. Steps advance A and add B under the same shadow identity. Unchanged events for other payments deduplicate.
6. Validation can now observe latest-state agreement, while still stating historical completeness is NOT_CERTIFIED.

Generation 0 remains durable evidence of the first plan. Generation 1 is a new reviewed decision. If an earlier generation contained a blocked legacy payment, a later valid source event can make its current snapshot eligible; that does not retroactively reconstruct the missing history or resolve an unrelated quarantined record.

## Transaction isolation and complete-transaction retry

A manifest must describe a consistent set of source rows/events. A Repeatable Read planning transaction supplies that snapshot. Batches use explicit run-row ownership and normal committed reads to make progress against the frozen input. Validation uses a stable read-only observation so its own comparisons do not mix source versions.

Repeatable Read writers can receive PostgreSQL serialization failure `40001` when a concurrent transaction changes a row they need to lock. The correct response is to roll back and retry the complete transaction, including its request/state decisions, with a new snapshot. Retrying one SQL statement inside an already aborted transaction cannot repair it. Deadlock `40P01` is another retryable concurrency condition when the whole transaction is retried deliberately.

Create and catch-up use `TransactionTemplate` with REQUIRES_NEW, Repeatable Read and a 60-second transaction timeout. Their retry loop permits at most three complete attempts for SQL states `40001` and `40P01`, surrounding both transaction creation and commit. Steps use REQUIRES_NEW / Read Committed with a 30-second timeout; status and validation use independent read-only Repeatable Read transactions with a 30-second timeout. The planning retry loop does not apply to those other methods, and a transaction timeout is not a total CLI deadline. See [PostgreSQL serialization failure handling](https://www.postgresql.org/docs/17/mvcc-serialization-failure-handling.html) and [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html). Request identity makes an uncertain accepted operation safe to repeat; it does not make a changed command the same operation.

Generation 0 uses the run UUID as its request ID. Later request IDs are globally unique across rebuild generations. Catch-up checks an accepted request before checking today's generation/pending work, so an identical lost-response retry returns the original result even after further progress. A fresh request requires the observed expected generation and no pending items. A completed generation may still contain blocked inputs; catch-up preserves that evidence while creating a new manifest.

## Isolation includes diagnostics and financial state

Source state and outbox events already committed before the rebuild reads them. Copying a snapshot into a shadow does not cause another source transition. The rebuild writes no payment, ledger posting, source delivery acknowledgement, Kafka offset, live receipt, live projection or physical-position success marker.

It also does not resolve live failures or quarantine audits. A valid source snapshot may reconstruct the latest read view while a malformed original record remains an unresolved historical obligation. Validation therefore keeps live-consumer recovery context separate from shadow agreement, including incidents in other source namespaces.

A new Kafka consumer group would not provide this isolation: logical deduplication identity is stored in PostgreSQL and is separate from broker group progress. This rebuild does not create a Kafka group or wait for broker delivery; its approved inputs are local committed source facts.

## Failure cases to reason through

| Event | Required outcome |
| --- | --- |
| CLI response lost after create/catch-up commit | Identical accepted command returns the original plan identity/time. |
| Process stops before a batch commits | Its receipt/projection/progress changes roll back together. |
| Process stops after a batch commits | Next step continues remaining pending items. |
| Two steps race | Run lock serializes them; committed item effects are not doubled. |
| New catch-up request uses an old generation | Conflict; it cannot overwrite the newer approved plan. Identical accepted request replay still returns its saved result. |
| Original input is still invalid or current source event is absent | Item remains blocked; no invented receipt/event. |
| Source changes while an old generation builds | Old frozen input stays reproducible; current-source validation reports drift. |
| Shadow projection disappears under an existing receipt | Validation reports mismatch; duplicate processing must not silently claim repair. |
| Decoder changes a planned contract | Processing must reject reinterpretation rather than silently rewriting immutable plan evidence. |
| Recovery gap exists despite shadow agreement | Report agreement with open gaps, not historical completion or cutover permission. |

## Costs and limits

Manifest planning scans current source payments and retains copied event evidence. Each catch-up generation repeats that planning work; generations, items and new shadow receipts consume storage. Bounded batch size limits step work but does not make planning constant-time. There is no automated scheduling, pruning, cancellation or large-volume throughput claim.

The run binds local source namespace/topic and a generated target identity. This is a local-database workflow; separate physical source/consumer databases require another protocol. Local execution/database access remains the operator boundary, and the audit name is self-declared.

Current source snapshot agreement is checked at an observation time. A source write immediately afterward can invalidate it. No live routing change, promotion, rollback execution or automatic quarantine resolution is part of this block.

Validation's summary status has ordered precedence: BUILD_INCOMPLETE, BLOCKED_INPUTS, SHADOW_MISMATCH, SOURCE_CHANGED, then either agreement status depending on open live-consumer gaps. Counts can reveal additional issues hidden behind the first status. Status reports retain the gap context captured during planning, while validation reports current gaps. Neither establishes historical completeness or a fenced cutover decision.

## Holistic impact and next implementation

The project now has both a way to detect read-model disagreement and a way to construct a separate replacement candidate without weakening normal deduplication. This extends the learning sequence from write correctness and durable communication to controlled data reconstruction.

The next logical milestone is **audited projection promotion and rollback with freshness fencing and an authenticated operator boundary**. It must define which readers switch, how concurrent source changes are handled, whether the shadow keeps consuming, and how rollback preserves newer facts. A validated shadow is an input to that design, not permission to skip it.

## TOPICS TO LEARN FROM THIS SESSION

- Shadow read models and logical consumer identities
- Materialized manifests and frozen generations
- Atomic checkpoints and resumable batches
- Row locks and worker ownership
- Compare-and-set and idempotent operator commands
- Repeatable Read serialization failures
- Complete-transaction retry and TransactionTemplate
- Canonical event contracts and reproducibility
- Latest-state rebuild versus historical replay
- Catch-up validation, freshness fencing and promotion
