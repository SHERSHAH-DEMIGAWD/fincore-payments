# Milestone 21 — Independent settlement statements and reconciliation

**M22 continuation (2026-10-27):** [Redis admission controls and risk rules](MILESTONE_22_LEARNING_GUIDE.md) screen new HTTP payment creations while retaining PostgreSQL financial authority and recovery access. Read its [runbook](PAYMENT_RISK_RUNBOOK.md) and [verification summary](MILESTONE_22_SUMMARY.md). Next-step statements below preserve the M21 boundary.

Implemented 27 October 2026. Continue from the [Milestones 11–20 guide](COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.md). Use the [runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) for commands, the [summary](MILESTONE_21_SUMMARY.md) for verification, and the [roadmap](ROADMAP.md) for remaining scope.

## 1. The problem this milestone addresses

The API can correctly record its own payment and ledger rows while still disagreeing with the provider. A capture reply may be lost after the remote process commits. The provider then knows about a capture that the local ledger has not posted. Comparing two local database views cannot independently discover what the provider accepted.

M21 adds a second source of evidence: a cumulative statement generated solely from the sandbox provider's durable decision journal, in its separate process and directory. The merchant imports its exact signed bytes and creates immutable reports comparing provider lines, local provider commands and LIVE capture/refund journal headers. A report records individual issues and gross/net totals. It does not apply a provider result.

This is settlement **reconciliation evidence** for the full-amount sandbox workflow. It does not initiate payouts, transfer bank funds, model clearing dates or prove that a bank settled money. M20's local partial operations and retained fees remain a separate mode. The provider protocol still has full captures/refunds and zero fees.

## 2. How it fits into the whole project

| Existing layer | What it establishes | What M21 adds |
| --- | --- | --- |
| M1–3 payment + ledger | Local lifecycle and balanced financial obligations | Comparison with independently retained provider decisions |
| M4–5 outbox + consumers | Durable delivery and derived read models | Reconciliation does not depend on a consumer projection |
| M6–15 operations evidence | Explicit recovery, historical records and controlled operational decisions | The same evidence-first discipline applied to external financial observations |
| M16 merchant boundary | Typed authenticated ownership and scoped reads/retries | Statements, reports and resolutions owned by that merchant |
| M17 provider commands | Durable intent, uncertainty, exact retry and confirmed application | Detects disagreement without inventing a second financial application path |
| M18 incoming callbacks | Authenticated delivery can confirm an existing command atomically | Archived statements remain usable without delivery-time expiry |
| M19 merchant notifications | Reliable notification of committed local changes | Evidence commands generate no new payment events or notifications |
| M20 partial accounting | Capture-specific refunds and explicit retained local fees | Keeps that different accounting mode outside the full-provider comparison |

We are building this backend to understand the consequences of failures, not merely produce successful HTTP responses. M21 makes the distinction between a payment instruction, a committed journal and an independent observation visible in code and in the recovery workflow.

## 3. What a statement actually covers

`SandboxProviderStore.settlement` filters its decision journal by merchant and currency. It includes approved CAPTURE/REFUND decisions, in strictly increasing provider sequence, and excludes authorizations and declines. Each line carries provider operation/payment identity, principal and fee. Gross captured, refunded, fees and net must agree with the lines:

```text
net = captured - refunded - fees
```

The scope also includes the pinned sandbox instance UUID. `throughSequence` is the provider's **global decision count**, including decisions outside this merchant/currency. It is not a date range or a local payment version. An unrelated provider decision can therefore produce a new statement identity with unchanged financial lines.

`SettlementWire.id` derives a deterministic UUID from instance, merchant, currency and that horizon. Re-exporting the same journal horizon produces the same identity and bytes, including after restart. The name-based UUID is an identifier, not the authentication mechanism.

A report compares this saved cumulative statement with all currently visible local full-provider CAPTURE/REFUND commands in the same scope. It does not quietly discard newer local commands to make an old statement match. A capture/refund after export can make the old statement discrepant; export a new statement to inspect the newer horizon. Old files and reports remain historical evidence.

## 4. Exact bytes, HMAC and strict parsing

The sandbox returns `Fincore-Settlement-Key-Id` and `Fincore-Settlement-Signature`. `SettlementWire.signature` computes HMAC-SHA256 over:

```text
ASCII("fincore-provider-settlement-v1\n" + keyId + "\n") || original UTF-8 body
```

The result is `v1=` followed by 64 lowercase hexadecimal characters. The domain string separates this protocol from webhook signatures, and the signing key is configured separately from provider bearer authentication. Java's `Mac` API supplies the keyed integrity mechanism. [Java 21 Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html).

`SettlementImportVerifier.verify` checks the switch, exactly one of each signature header, byte limits, key ID and MAC before parsing the document or calling the transactional evidence service. Merchant Basic authentication happens earlier and can query the credential database; “before the evidence transaction” does not mean the entire HTTP request makes no database access.

Strict UTF-8/JSON parsing rejects ambiguous duplicate fields, trailing data, unknown/missing fields and scalar coercion. Wire validation checks version, nonzero canonical lowercase UUID strings, canonical sandbox references, currency syntax, sequence order, duplicate operations and exact decimal totals. This rejects alternate UUID spellings before the SQL metadata guard. Principal uses at most 17 integer digits and two meaningful fractional places; aggregate values allow 21 integer digits. Decimal magnitude is checked before rescaling, avoiding huge allocations for extreme exponents.

Unlike incoming webhooks, statements have no freshness timestamp. An archived signed file is intentionally importable later. Exact identity/byte replay and immutable storage handle duplicates; a signature does not establish that the archive is the latest horizon. HMAC also gives no public-verification or nonrepudiation guarantee: holders of the shared secret can generate valid files. Key rings, rotation and independent custody are future work.

## 5. Immutable evidence and retry identity

V21 creates three append-only tables:

| Table | Durable meaning |
| --- | --- |
| `settlement_statements` | Exact authenticated UTF-8 body, SHA-256, original signature/key ID, scope and totals |
| `settlement_reports` | One comparison observation, timestamp, source fingerprint and detailed issues |
| `settlement_resolutions` | Attributed closure linking a discrepant report to matching evidence for the same statement |

The import stores text without reformatting; strict UTF-8 round trips preserve the original bytes. GET `/document` checks the saved hash and wire contract and downloads those bytes with the original signature headers. PostgreSQL checks the body hash and binds normalized metadata to the JSON. UPDATE/DELETE triggers reject ordinary mutation.

Importing the same statement bytes returns `replayed=true`. Changed bytes under that identity conflict even if the JSON would mean the same thing. Reformatting a downloaded file therefore creates a different byte intent.

For reports, the merchant's `requestId` is the report UUID. Reusing it for the same statement returns the original historical report before reading today's local sources. Reusing it for a different statement conflicts. A new UUID requests a new observation. Resolution has its own scoped request UUID and exact intent check, including authenticated actor and reason.

Database constraints reinforce application behavior: owned composite foreign keys, issue-count checks, matching/discrepant report requirements, unique closure and append-only rows. They do not authenticate HMAC or stop a database owner from changing schema/triggers. Operational access remains trusted.

## 6. Matching more than net totals

Two wrong lines can cancel out to the correct net. `SettlementService.comparison` therefore joins by operation UUID and records issues per operation:

| Issue | Meaning |
| --- | --- |
| `UNMATCHED_EXTERNAL` | Provider financial line has no local command |
| `MISSING_EXTERNAL` | Applied approved local command has no provider line |
| `LOCAL_UNCONFIRMED` | Local execution is READY, IN_FLIGHT or UNKNOWN |
| `LOCAL_REQUIRES_REVIEW` | Local command already requires investigation |
| `OUTCOME_MISMATCH` | Provider financial line contradicts a confirmed local decline |
| `IDENTITY_MISMATCH` / `AMOUNT_MISMATCH` | Payment/action or principal differs |
| `FEE_MISMATCH` | Provider fee is nonzero in this zero-fee full-provider contract |
| `JOURNAL_MISSING` / `JOURNAL_MISMATCH` | Applied command lacks the expected LIVE journal or its principal/currency differs |

Confirmed declines legitimately have no financial line or journal. The repository joins journals only for APPLIED/APPROVED commands, preventing a declined attempt from borrowing the journal of a later successful capture on the same payment. Existing full-workflow uniqueness supplies the one LIVE journal per payment/action contract. This query is not a general reconciliation implementation for arbitrary future provider partial operations.

Local totals use approved applied journal headers. External totals use validated statement lines. `discrepancyCount` counts items with issues, not the number of issue codes. A matched report needs zero discrepant items; a matching net alone is insufficient.

## 7. A complete lost-reply recovery example

For an INR 100 provider-authorized payment:

1. Submit CAPTURE with `TIMEOUT_AFTER_ACCEPT`. The provider persists approval; the API records UNKNOWN and has no capture journal.
2. Export/import the statement. It has captured/net 100. Report A shows `LOCAL_UNCONFIRMED`; local net is zero.
3. Retry the **original provider request UUID and body** through M17. The provider returns its saved decision. The existing guarded completion posts exactly one capture journal/event.
4. Reconcile the **same statement** with a new request UUID. Report B matches and has local/external net 100.
5. Resolve report A using B and a reason. The service recomputes a comparison and fingerprint in its transaction before saving the closure.

Report A stays discrepant forever as an observation of that earlier state. The resolution explains why that discrepancy was closed; it does not rewrite A. Import/report/resolve never create a capture journal themselves.

If a refund commits before step 5, B no longer represents the current visible source. Closure is refused. A new statement may match the new refund horizon, but it cannot be used to close A: closure requires the same statement. This conservative rule preserves scope and can leave an old discrepancy unresolved; there is no “force close,” correction-file or cross-horizon supersession facility.

## 8. Transaction ownership, snapshots and contention

Import owns a new Read Committed transaction. Report and resolution each own a new Repeatable Read transaction, with a 30-second transaction timeout. They acquire a nonwaiting transaction-level advisory lock hashed from the merchant UUID with a milestone namespace seed. Competing evidence commands get a conflict and retry the same intent.

The lock serializes participating evidence commands for that merchant, including different currencies. It does **not** stop payment/provider writers. Repeatable Read keeps database observations coherent within a transaction; a payment can advance concurrently without making the report a mixture of different read snapshots. It does not guarantee the source remains unchanged after the transaction. [PostgreSQL transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html).

The fingerprint hashes the statement digest plus the ordered local command/journal observation. Resolution must see both a fresh match and the same fingerprint as the proposed matching report. `observedAt` records the observation time, not a guarantee of financial quiescence at that instant. Advisory locks are cooperative and transaction-scoped here; direct SQL or unrelated writers need not honor them. Hash collisions can conservatively cause extra contention. [PostgreSQL advisory locks](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS).

## 9. Alternatives, selection and costs

| Choice used here | Alternative considered | Why selected for this milestone / cost |
| --- | --- | --- |
| Independent provider journal | Build “external” evidence from local ledger rows | Local regeneration cannot reveal the lost provider-side decision; separate journal has its own durability/trust limits |
| Signed exact-byte JSON | Canonicalize JSON before signing, or unsigned CSV | Avoids designing canonicalization and authenticates the stored artifact; formatting changes break identity/signature |
| Cumulative scope/horizon | Daily windows and settlement batches | Avoids timezone, cutoff and carryover policy before those exist; old files diverge as local history grows |
| Detailed operation comparison | Compare only gross/net aggregates | Finds offsetting errors and identifies the source command; stores more evidence |
| Evidence-only import | Automatically apply missing captures from statements | Keeps M17/M18 as the financial completion authority; recovery remains explicit |
| Repeatable Read + evidence fence | Read Committed reads, global locks or Serializable retries | Gives coherent observations and simple evidence serialization; does not freeze financial writers or promise serializable financial decisions |
| Fresh same-statement closure | Mutable resolved flag or reason-only override | Preserves proof and audit history; cannot administratively close every bad-source case |
| Exact decimals | Binary floating point or implicit rounding | Keeps protocol amounts/totals exact; currency-specific minor units remain unmodeled |
| Shared-secret HMAC | Asymmetric signatures and key rings | Reuses a small standard primitive for local integration; both parties can forge evidence and rotation is absent |

These alternatives are not universally inferior. Date-window batches, asymmetric signatures, supersession and provider-specific partial matching may be appropriate with their own business and operational contracts.

## 10. Bounds and operational behavior

| Boundary | Behavior |
| --- | --- |
| Statement body | At most 1 MiB, checked from header and actual bounded read |
| Financial lines | At most 1,000; excess export/import refused |
| Local comparison | Probe up to 1,001 commands; refuse new report/closure above 1,000 |
| Report | At most 2,000 union items and 2 MiB serialized evidence |
| Listing | UUID keyset pagination, limit 1–100; metadata only |
| Sandbox journal | Existing 10,000-decision lifetime limit |

These caps bound admitted/returned data, not total index scan, sorting, database size, request ingress time or production throughput. There is no scheduled reconciler, provider polling worker, streaming large-file parser, retention executor or financial cutoff fence. Imports default off; signed sandbox exports also need explicit configuration. Turning imports off does not remove existing owned evidence reads or historical reconciliation services.

## 11. Actual code flow and file map

```text
GET sandbox /v1/settlements/{merchantId}/{currency}
→ SandboxProviderStore.settlement → SettlementWire.encode/signature
→ merchant saves exact bytes + headers
POST /api/v1/merchant/settlement-statements
→ SettlementController.importStatement → SettlementImportVerifier.verify
→ SettlementService.importStatement → SettlementRepository.insert
POST /{statementId}/reports
→ SettlementService.reconcile → SettlementRepository.local
→ SettlementService.comparison → SettlementRepository.insertReport → ReportResult
```

`Settlement.java` defines immutable wire/API records. `SettlementProperties` binds the import switch/key/instance. `SettlementService.resolve` owns closure policy. `V21__add_sandbox_settlement_reconciliation.sql` creates storage/guards without changing financial before-images. `EventOperationsCli` explicitly disables API settlement credentials in headless commands. The controller and repository contain the exact HTTP paths and SQL respectively.

## 12. Verification and what to learn next

Tests exercise strict bytes/JSON/money, HMAC tampering, independent real HTTP export/restart, real PostgreSQL reports/replay/ownership, concurrent recovery, contention, stale closure, late failures and migration preservation. The [summary](MILESTONE_21_SUMMARY.md) records final counts and environment limits; skips are not passes. No test establishes live bank settlement, production capacity, key rotation or restore durability.

Next is M22: concrete Redis velocity/rate/risk controls with explicit outage behavior, while PostgreSQL remains financial authority. M23–25 cover observability, backup/restore and operational closure, then deployment/end-to-end validation. Four planned blocks remain after M21; this is not an effort-based completion percentage.

### TOPICS TO LEARN FROM THIS SESSION

- Independent financial reconciliation
- Provider uncertainty and idempotent recovery
- Exact-byte protocols and strict JSON
- HMAC authentication versus SHA-256 integrity
- Cumulative horizons and historical evidence
- Repeatable Read and MVCC
- Cooperative advisory locks
- Immutable reports and attributed resolution
- Decimal precision and aggregate totals
- Database constraints, triggers and migration preservation
- Failure injection and concurrency testing
