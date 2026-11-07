# Milestone 20 learning guide — Partial payment operations and explicit fees

**M21 continuation (2026-10-27):** [Independent settlement reconciliation](MILESTONE_21_LEARNING_GUIDE.md) now compares signed provider-journal statements with full-provider commands and LIVE journals. It keeps this local partial/fee mode separate. See the [new runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md); earlier next-step statements below record the M20 boundary. The two consolidated Word volumes now cover M1–10 and M11–20 respectively.

Implemented 2026-10-26 in the independent Java `fincore-payments` project. Continue from [Milestone 19](MILESTONE_19_LEARNING_GUIDE.md). Use the [accounting runbook](PAYMENT_ACCOUNTING_RUNBOOK.md), [verification summary](MILESTONE_20_SUMMARY.md), and [completion roadmap](ROADMAP.md). The Word edition remains the Milestones 1–10 snapshot.

## 1. Why this changes the financial model

The earlier ledger represented one full capture and one full reversal per payment. That model cannot describe two shipments charged separately, several customer refunds, or an explicit processing fee. A single status and the original payment amount no longer describe the complete financial position.

Milestone 20 introduces an amount-command mode for **manually authorized local payments before their first capture**. The first accepted capture permanently enrolls that payment in this mode. The existing full-payment API and provider integration remain available for payments using those workflows. A payment cannot mix their journals with amount commands.

The new API models local accounting decisions. It does not send partial commands to the HTTP provider: that protocol and its verified callbacks still support full authorization/capture/refund amounts. There is no live money movement, remote authorization release, or bank settlement implied by these local entries.

## 2. Concrete policy implemented in this project

| Rule | Behavior |
| --- | --- |
| Authorization ceiling | The payment's original `amount` is immutable; total captures cannot exceed it. |
| Partial captures | More than one capture is allowed while capture capacity remains and no refund has happened. |
| Refund identity | Each refund names a capture operation from the same merchant/payment. |
| Refund ceiling | Total refunds against a capture cannot exceed that capture's principal. |
| Capture closure | The first refund closes all remaining capture capacity on the payment. |
| Full refund | Refunding all captured principal makes the payment REFUNDED, even if some original authorization was unused. |
| Fees | Each capture explicitly supplies a fixed fee from zero through its principal amount. |
| Refund fee policy | Refund commands require zero fee. Original capture fees are retained; no fee refund or proration is performed. |
| Work bound | At most 100 accepted amount commands per payment, including refunds. |
| Money | Positive principal, nonnegative fee, exact two-decimal NUMERIC(19,2), one immutable payment currency. |

These are deliberate sandbox business rules. Other products can choose different fee refund and capture-closure policies; changing them requires new rules and verification rather than an implicit arithmetic tweak. Fees are supplied as explicit command intent, not calculated from a production merchant tariff.

## 3. The totals and status now mean different things

`PaymentResponse.amount` and the v1 payment-event snapshot continue to mean the original authorization amount. The new owned GET `/api/v1/payments/{paymentId}/accounting` returns:

```text
capturedAmount       = sum of capture principal
refundedAmount       = sum of refund principal
feeAmount            = sum of capture fees
refundableAmount     = capturedAmount - refundedAmount
merchantPayable      = capturedAmount - refundedAmount - feeAmount
remainingCaptureAmount = authorization - capturedAmount, only while capture is open
```

`PARTIAL_CAPTURE` means captured principal is positive but below authorization, with no refunds. `CAPTURED` means authorization has been captured in full. `PARTIAL_REFUND` means some captured principal has been refunded. `REFUNDED` means all captured principal has been refunded. Payment version advances once for every accepted command, including two consecutive operations that retain the same lifecycle status.

The accounting read obtains one Repeatable Read observation. Current totals come from committed journal headers. An accepted-request replay reconstructs the historical accounting snapshot from the immutable command prefix ending at that request's source version. A late replay therefore returns its original result rather than today's larger totals.

## 4. Worked accounting example

Authorize INR 100, capture 60 with a fee of 3, then capture 40 with a fee of 2:

| Journal | Debit | Credit |
| --- | --- | --- |
| First capture principal, 60 | PROCESSOR_CLEARING 60 | MERCHANT_PAYABLE 60 |
| First capture fee, 3 | MERCHANT_PAYABLE 3 | PLATFORM_FEE_REVENUE 3 |
| Second capture principal, 40 | PROCESSOR_CLEARING 40 | MERCHANT_PAYABLE 40 |
| Second capture fee, 2 | MERCHANT_PAYABLE 2 | PLATFORM_FEE_REVENUE 2 |

Clearing is 100, merchant payable is 95, and platform fee revenue is 5. Each journal has two equal opposing entries in one currency. Fees are distinct journals linked to the same amount operation as their principal, preserving gross capture visibility.

Refund all 60 against the first capture and all 40 against the second. Each refund debits merchant payable and credits clearing; its journal names the original principal journal. Clearing becomes zero, fee revenue stays 5, and merchant payable becomes **-5**. This negative amount is visible rather than clamped: under the chosen retained-fee policy, the merchant owes the fee after all principal has been returned. There is no automatic collection, credit-limit facility, bank movement or completed settlement in this milestone.

## 5. Why a request UUID and expected version are both required

`requestId` identifies one immutable financial intent. An exact accepted repeat returns the same operation and historical accounting snapshot with `replayed=true`, even after later captures/refunds. Reusing that UUID for a different payment, amount, fee, action, capture reference or source version conflicts.

`expectedVersion` states which payment observation the merchant intended to change. It is an explicit JSON integer; omission, null, strings and fractional values are rejected. An unaccepted command using an outdated version returns a conflict. The caller first resolves a possibly lost response using the original command; changing version and UUID creates a different financial intent and must be deliberate.

Numerically equal inputs such as `40`, `40.00` and `40.000` normalize to the same exact two-decimal intent. Nonzero excess decimal places are refused. Precision and scale are checked before rescaling so extreme decimal exponents do not trigger huge allocations. Java's decimal comparison and representation rules explain why numeric equality must be distinguished from scale-sensitive object equality. [Java 21 BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html).

## 6. Concurrency and transaction ownership

`PaymentAccountingService.submit` owns a new Read Committed transaction. It derives the merchant from `MerchantContext`, locks the owned source payment with `FOR UPDATE NOWAIT`, then checks prior request identity and current capacity. The lock serializes all capture/refund decisions for that payment. The expected version also rejects a stale new intent, while JPA's existing optimistic version update remains in place.

Consecutive partial commands can keep the same status. `Payment.applyAmounts` therefore advances `updatedAt` by at least one microsecond, even if the wall clock repeats or moves backward, ensuring the managed entity is dirty and Hibernate advances its version. A clock-regression test verifies that behavior.

Two concurrent INR 70 captures against INR 100 cannot both reserve the same capacity. One wins; the other sees contention or a changed version. The same applies to two refunds against one capture. NOWAIT provides a prompt conflict instead of waiting while holding arbitrary caller resources. PostgreSQL describes how these row locks interact with competing updates. [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html).

Inside the transaction, the service inserts the immutable accepted command, changes and flushes the payment, appends principal/optional fee journals, and records the outbox event. The existing M19 enrollment trigger creates merchant notification intent in that transaction. A late outbox or missing-journal failure rolls all of it back. No HTTP call is performed in this path.

## 7. Database defenses beyond Java checks

`payment_amount_bindings` records the first source version and command for the payment's permanent amount mode. `payment_amount_operations` stores tenant/request identity, amount, fee, source version, capture reference, resulting status, principal/fee journal IDs, transaction identity and creation time. Both tables reject ordinary UPDATE/DELETE.

V20 extends `ledger_transactions` with `amount_operation_id`. Legacy capture/refund uniqueness remains enforced by partial indexes. Amount journals are unique by command and journal kind. Multiple amount refunds may reference one principal journal, while the operation-insert guard enforces the remaining principal under the payment row lock. The original immutable ledger-entry and balance checks remain.

Deferred checks require the final payment version/status, command count and amount totals to agree, both named journals to exist and match their command, and the outbox event to represent the same resulting payment version. They inspect the completed transaction, which allows the command and journals to reference each other through deferred foreign keys. PostgreSQL supports deferring constraint-trigger execution until transaction end. [PostgreSQL CREATE TRIGGER](https://www.postgresql.org/docs/17/sql-createtrigger.html).

These rules prevent a supported SQL writer from committing half a financial operation. They do not protect against an administrator who can disable/replace schema guards. V1–V19 files and existing rows are preserved; the V19→V20 test checks a prior payment before-image and empty initial amount enrollment.

## 8. APIs and actual code flow

| Endpoint | Result |
| --- | --- |
| POST `/api/v1/payments/{paymentId}/amount-operations` | Accept or replay CAPTURE/REFUND amount intent. |
| GET `/api/v1/payments/{paymentId}/amount-operations` | Up to 100 immutable operations in source-version order. |
| GET `/api/v1/payments/{paymentId}/accounting` | Current principal/fee totals, capacity, status, version and mode. |

They use the existing merchant Basic principal and CSRF protection. Missing and foreign payment IDs return 404. Capture references are resolved within the authenticated merchant and payment. Existing ledger and merchant balance reads include the new journals/account, and ledger responses include `amountOperationId` for grouping.

```text
PaymentAccountingController.submit
→ PaymentAccountingService.submit
→ PaymentAccountingRepository.lock + accepted-request lookup
→ binding/command insert
→ Payment.applyAmounts + PaymentRepository.flush
→ LedgerTransaction.amountPosting + LedgerRepository.save
→ OutboxService.record → merchant delivery enrollment
→ PostgreSQL deferred commit checks
→ PaymentAccounting.Result
```

## 9. Event, projection and provider compatibility

The event JSON shape and schema version remain v1, with two new lifecycle enum values. Consumers, projections and the local merchant sink recognize these values; the newest payment version wins even if older events arrive later. The payload's `amount` remains authorization, so a notification signals a new lifecycle snapshot and the merchant reads accounting/history for partial amounts and fees. The event does not become a complete accounting report.

Deploy the new application and consumers together before emitting these enum values. Older enum readers can reject them; mixed-version interoperability is not claimed. Earlier event bytes, provider intent/result protocols and incoming callback verification are unchanged. Full provider-managed payments cannot enter local amount mode, and amount-managed payments cannot use the legacy full capture/refund/fail endpoints.

The headless event-operations CLI also explicitly disables both merchant-webhook switches, preserving operator commands when inherited API webhook credentials are invalid or scheduling is enabled.

## 10. Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| Separate principal and fee journals | A three-leg capture is valid accounting but requires a new journal-shape contract. Two-leg journals preserve the established invariant and show gross principal and retained fee separately, at the cost of more rows. |
| Immutable commands plus journal-derived current totals | Mutable captured/refunded/fee counters can make reads cheaper but introduce another value that must be kept consistent. The 100-command limit makes exact aggregation practical here. |
| Refund names one capture | A payment-wide refund pool is easier for callers but hides which charge is being reversed. Explicit allocation prevents one capture consuming another's remaining principal; multi-capture refunds require multiple commands. |
| Fixed explicit retained capture fee | Percentage fees require a tariff, rounding, allocation and fee-refund policy. Those choices are not invented in this block; callers supply an exact approved sandbox fee. |
| Close capture at first refund | Interleaving new captures after refunds is possible but needs an authorization-release/reuse contract. The chosen closure gives a small, testable state machine. |
| New transaction, source row lock and expected version | Optimistic versioning alone can protect a flush, but decisions also read aggregate capacity across command rows. Serializing that aggregate boundary simplifies correctness; contention is returned promptly. |
| Add a permanent amount-mode binding | Silently converting an existing provider or legacy full journal would invent unsupported capture allocation. Enrollment before first local capture keeps prior history interpretable. |
| Extend lifecycle enums while retaining the snapshot shape | A new full event schema could carry accounting totals but would require coordinated versioned consumer/rebuild semantics. This block exposes full totals through the accounting API and explicitly requires enum-aware deployment. |

## 11. Failure cases to understand

- Response lost after commit: repeat the same command to recover its accepted result; no duplicate journals or event.
- New request with an old version: conflict; observe current state before deciding on another intent.
- Over-capture/over-refund: refused before posting; PostgreSQL independently checks the limits.
- Fee or outbox write fails: payment, enrollment, command, journals and event all roll back.
- Full principal refund with retained fees: negative merchant payable is reported and remains unresolved debt for future settlement policy.
- 100 commands reached: no further amount operations are accepted, including refunds. The cap is a teaching workload bound and would require a carefully designed extension before production use; do not deploy it as a customer-facing refund restriction.
- Provider-bound payment: amount API refuses it; complete the established full provider workflow.

## 12. Holistic impact and next implementation

Milestone 20 converts the ledger from one full-payment pair of journals into explicit operation-level accounting without weakening the older workflows. Merchant isolation, exact retries, payment versioning, durable events, consumer ordering and reliable outgoing notifications now apply to repeated local financial operations. Fees become a separate visible financial fact instead of an unexplained subtraction.

The next block is **Milestone 21 — settlement and external reconciliation**: compare expected clearing/principal/fees against independent sandbox records, expose discrepancies and define controlled resolution. Actual settlement, provider partial HTTP/callback contracts, authorization void/release/expiry, fee tariffs/refunds, FX, a currency-specific scale catalogue, production debt recovery and retention remain outside the implemented scope. Five proposed milestone blocks remain, 21–25.

## TOPICS TO LEARN FROM THIS SESSION

- Partial capture and capture-specific refunds
- Gross principal, fees, net payable and negative liabilities
- Immutable journal groups and reversal references
- Request idempotency versus optimistic version intent
- Row locks around aggregate financial invariants
- REQUIRES_NEW and transaction boundaries
- Deferred foreign keys and constraint triggers
- Exact BigDecimal normalization and exponent bounds
- Lifecycle enums and event compatibility
- Historical snapshots reconstructed from immutable commands
- Settlement reconciliation and fee-debt policy
