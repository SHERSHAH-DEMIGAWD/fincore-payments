# Partial capture, refunds and fee accounting runbook

Use the independent Java application and its database. Start the API as described in [README setup](../README.md#setup), then provision a merchant and obtain `$merchantBase`, `$merchantHeaders` and `$merchantSession` using the PowerShell flow in the [merchant access runbook](MERCHANT_ACCESS_RUNBOOK.md). Its headers include Basic authentication and the current CSRF token; its session preserves the CSRF cookie.

This demo uses manually authorized local payments. Provider-bound payments retain the full-amount HTTP/callback workflow and cannot use amount commands.

## 1. Create INR 100 and authorize locally

```powershell
$createHeaders = $merchantHeaders.Clone()
$createHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
$created = Invoke-RestMethod -Method Post -Uri "$merchantBase/api/v1/payments" `
    -Headers $createHeaders -WebSession $merchantSession -ContentType 'application/json' `
    -Body '{"merchantReference":"PARTIAL-DEMO","amount":100.00,"currency":"INR"}'
$paymentId = $created.id
Invoke-RestMethod -Method Post -Uri "$merchantBase/api/v1/payments/$paymentId/authorize" `
    -Headers $merchantHeaders -WebSession $merchantSession
$accountingUri = "$merchantBase/api/v1/payments/$paymentId/accounting"
$operationsUri = "$merchantBase/api/v1/payments/$paymentId/amount-operations"
$current = Invoke-RestMethod -Uri $accountingUri -Headers $merchantHeaders -WebSession $merchantSession
```

The current version is normally 1. Use the returned value rather than assuming it in a client.

## 2. Capture 60 with a fee of 3, then 40 with a fee of 2

```powershell
$captureAJson = [ordered]@{
    requestId = [guid]::NewGuid().ToString(); expectedVersion = $current.version
    action = 'CAPTURE'; amount = [decimal]60; fee = [decimal]3
} | ConvertTo-Json -Compress
$captureA = Invoke-RestMethod -Method Post -Uri $operationsUri -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureAJson
$captureBJson = [ordered]@{
    requestId = [guid]::NewGuid().ToString(); expectedVersion = $captureA.accounting.version
    action = 'CAPTURE'; amount = [decimal]40; fee = [decimal]2
} | ConvertTo-Json -Compress
$captureB = Invoke-RestMethod -Method Post -Uri $operationsUri -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureBJson
```

After the first capture: PARTIAL_CAPTURE, captured 60, fee 3, payable 57, remaining capture 40. After the second: CAPTURED, captured 100, fees 5, payable 95, remaining capture zero. The first accepted command permanently enrolls amount mode; legacy `/capture`, `/refund` and `/fail` no longer apply to this payment.

Repeat `$captureAJson` exactly to recover a lost response. Even after capture B it returns A's original operation and accounting snapshot, with `replayed=true`. A different fee/amount/version under that UUID returns a conflict.

## 3. Refund principal against each named capture

```powershell
$refundAJson = [ordered]@{
    requestId = [guid]::NewGuid().ToString(); expectedVersion = $captureB.accounting.version
    action = 'REFUND'; amount = [decimal]60; fee = [decimal]0
    captureOperationId = $captureA.operation.operationId
} | ConvertTo-Json -Compress
$refundA = Invoke-RestMethod -Method Post -Uri $operationsUri -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $refundAJson
$refundBJson = [ordered]@{
    requestId = [guid]::NewGuid().ToString(); expectedVersion = $refundA.accounting.version
    action = 'REFUND'; amount = [decimal]40; fee = [decimal]0
    captureOperationId = $captureB.operation.operationId
} | ConvertTo-Json -Compress
$refundB = Invoke-RestMethod -Method Post -Uri $operationsUri -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $refundBJson
```

Final state: REFUNDED, captured/refunded 100, retained fees 5, refundable zero, payable **-5**. Clearing is zero and platform fee revenue is 5. The negative payable represents the merchant's retained-fee debt in this sandbox; nothing collects or settles it automatically.

## 4. Inspect committed evidence

```powershell
Invoke-RestMethod -Uri $accountingUri -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri $operationsUri -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$paymentId/ledger" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$paymentId/events" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/ledger/accounts/INR/balances" -Headers $merchantHeaders -WebSession $merchantSession
```

The four amount commands above produce six two-leg journals: two capture principals, two fees, two refund principals. Together with creation and authorization, this payment has six lifecycle events. If a merchant webhook subscription was active before the amount commands, those four new events enroll four reliable delivery rows. Webhook snapshots retain the original authorized `amount`; read accounting/history for partial amounts and fees. Consumer projections require the existing Kafka transport/consumer setup; local outbox delivery alone does not build a projection.

## 5. Recover without duplicating intent

| Result | Action |
| --- | --- |
| Response timeout / 5xx | Preserve and retry the exact original request UUID, expected version, action, money and reference. |
| 409 changed request intent | Find the original command; do not change its body under the same UUID. |
| 409 version or capacity conflict | Resolve the original request first, read accounting, then decide whether a different intent is valid. |
| 409 busy payment | Retry the exact command after the competing transaction finishes. |
| 404 payment/capture | Check authenticated merchant and the capture operation ID, not just the payment UUID. |
| 400 invalid request | Supply explicit integer `expectedVersion`, positive exact principal, explicit valid fee and the required refund reference. |
| 403 | Renew/use the merchant CSRF session/token and Basic credentials. |

No configuration switch enables amount mode: the first successful local amount capture does. No endpoint deletes history, changes a fee after capture, reopens capture after refund, bypasses the 100-command cap or converts an existing provider/full-capture payment.

## 6. Deployment and scope

Apply V20 with a coordinated application/consumer rollout. Existing rows and V1–V19 files are retained. New statuses and ledger operations require readers that know the new enum values. There is no mixed-version compatibility claim or new accounting schema version in the event JSON. Keep the database and master keys backed up according to the existing operational procedures; this milestone does not implement independent restore verification.

The [learning guide](MILESTONE_20_LEARNING_GUIDE.md) explains the accounting equations, transaction guarantees, alternatives, failure windows and the next settlement block. The [summary](MILESTONE_20_SUMMARY.md) records actual final tests and environment limits.
