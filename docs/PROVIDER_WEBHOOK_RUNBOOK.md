# Incoming provider webhook runbook — Milestone 18

This builds on the [M17 provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md). The same local sandbox now sends signed confirmations to an explicitly enabled API receiver. Read the [learning guide](MILESTONE_18_LEARNING_GUIDE.md) for concepts/tradeoffs and [summary](MILESTONE_18_SUMMARY.md) for actual tests. No real funds or live vendor is used.

## 1. Build and retain the original sandbox namespace

Use Java 21 and run from `fincore-payments`:

```powershell
.\mvnw.cmd package -DskipTests
```

Stop older application instances for a coordinated upgrade. Flyway applies V18 without rewriting original merchant/payment/retry/outbox/provider rows. Mixed-version operation is unsupported. Retain the M17 sandbox's original data directory and instance UUID for existing commands; never reuse that UUID with empty replacement history.

Use two separate strong secrets: the existing sandbox API key and a new webhook signing key, each 32–256 visible ASCII characters. The credential prompt password fields below take those original values; prompt usernames are local labels. Keep secrets out of command arguments/source/logs. Environment variables are still visible to appropriately privileged local processes.

## 2. Terminal A — configure and start the API receiver

Keep the usual intended `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. Replace the instance example with the original sandbox UUID, identically in both terminals:

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$sandboxApiKey = Get-Credential -UserName 'local-sandbox-api' -Message 'Existing sandbox API key in password field'
$webhookSigningKey = Get-Credential -UserName 'local-sandbox-webhooks' -Message 'Separate webhook signing key in password field'
$env:FINCORE_PROVIDER_ENABLED = 'true'
$env:FINCORE_PROVIDER_BASE_URL = 'http://127.0.0.1:18081'
$env:FINCORE_PROVIDER_API_KEY = $sandboxApiKey.GetNetworkCredential().Password
$env:FINCORE_PROVIDER_INSTANCE_ID = $sandboxInstance
$env:FINCORE_PROVIDER_WEBHOOK_ENABLED = 'true'
$env:FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY = $webhookSigningKey.GetNetworkCredential().Password
$env:FINCORE_PROVIDER_WEBHOOK_KEY_ID = 'sandbox-v1'
$env:FINCORE_PROVIDER_WEBHOOK_INSTANCE_ID = $sandboxInstance
$env:FINCORE_PROVIDER_WEBHOOK_TOLERANCE_SECONDS = '300'
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar
```

The receiver defaults off. Its instance/key/key ID are independent configuration from outbound execution; disabling outbound requests does not disable a correctly configured receiver. Receiver tolerance must be 30–900 seconds; default ±300 seconds is inclusive. Keep local clocks synchronized.

The exact route is POST `http://127.0.0.1:8080/api/v1/provider-webhooks/sandbox` with JSON. It uses HMAC rather than merchant Basic/CSRF. Existing merchant and operator write endpoints keep their CSRF requirements. Unsupported callback methods/paths are denied.

## 3. Terminal B — enable sandbox callbacks

Supply the same original API key and the same separate signing key:

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$sandboxApiKey = Get-Credential -UserName 'local-sandbox-api' -Message 'Same existing sandbox API key'
$webhookSigningKey = Get-Credential -UserName 'local-sandbox-webhooks' -Message 'Same separate webhook signing key'
$env:FINCORE_SANDBOX_API_KEY = $sandboxApiKey.GetNetworkCredential().Password
$env:FINCORE_SANDBOX_WEBHOOK_URL = 'http://127.0.0.1:8080/api/v1/provider-webhooks/sandbox'
$env:FINCORE_SANDBOX_WEBHOOK_KEY = $webhookSigningKey.GetNetworkCredential().Password
$env:FINCORE_SANDBOX_WEBHOOK_KEY_ID = 'sandbox-v1'
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar provider-sandbox `
    --port=18081 --data-dir=.sandbox-provider --instance-id=$sandboxInstance
```

The optional sender requires both URL and signing key; key ID defaults to `sandbox-v1` if omitted. Partial/invalid settings refuse startup. Callback destinations are loopback HTTP(S) and the exact receiver path, without user info/query/fragment. Redirects/proxies are disabled. The standalone sandbox still starts before Spring and needs no PostgreSQL/Kafka.

After each persisted decision, the sandbox queues a callback, including after deliberate reply loss. One worker, a queue of 64 and a two-second complete-response deadline bound local delivery effort. Queue saturation/network failure prints `SANDBOX_WEBHOOK_UNCONFIRMED` with an event UUID; it never erases or reverses the original decision. There is no automatic retry scheduler or restart scan.

## 4. Terminal C — create and exercise a provider payment

Follow [merchant setup and authentication](MERCHANT_ACCESS_RUNBOOK.md) and the [M17 payment/demo commands](PROVIDER_INTEGRATION_RUNBOOK.md#4-terminal-c--authenticate-and-create-a-new-owned-payment). Keep `$payment`, `$providerUrl`, `$merchantHeaders` and `$merchantSession` from that flow.

Use an authorization scenario with a deliberately lost reply:

```powershell
$authorizeBody = @{ requestId=[guid]::NewGuid().ToString(); action='AUTHORIZE'; sandboxScenario='TIMEOUT_AFTER_ACCEPT' } | ConvertTo-Json -Compress
$authorization = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $authorizeBody
$authorization.operation
$operationId = $authorization.operation.operationId
Invoke-RestMethod -Uri "$providerUrl/$operationId" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$providerUrl/$operationId/webhooks" -Headers $merchantHeaders
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/$($payment.id)" -Headers $merchantHeaders
```

With callbacks enabled, the first command response may already be APPLIED, or may still show UNKNOWN/IN_FLIGHT until the callback commits. Refresh the operation/payment and receipt metadata. Once confirmation commits, authorization is AUTHORIZED with a receipt. Use new command UUIDs for subsequent CAPTURE and full REFUND, as in the M17 runbook.

If the callback completes while the original HTTP worker is still returning, both paths converge on the same command. There must be one capture journal and one reversal for the full lifecycle, not an extra journal for each callback.

Receipt lists use UUID order, default limit 20, maximum 100, and `afterReceiptId` from `nextAfterReceiptId`. They are merchant-owned and omit raw payloads/secrets. Each GET has its own Repeatable Read snapshot; pagination is not a chronological event stream.

## 5. Explicit redelivery, including after sandbox restart

If automatic delivery fails, keep the original provider decision directory/instance. Fix receiver connectivity/key/instance configuration, then deliver the stored result without creating another payment command:

```powershell
# In a terminal with the original sandbox API-key credential, not the merchant password:
$sandboxHeaders = @{ Authorization = 'Bearer ' + $sandboxApiKey.GetNetworkCredential().Password }
$delivery = Invoke-RestMethod -Method Post `
    -Uri "http://127.0.0.1:18081/v1/webhooks/$operationId/deliver" -Headers $sandboxHeaders
$delivery
```

The request body must be empty. A known operation and configured sender are required; otherwise the sandbox refuses it. The endpoint reads the existing decision and creates fresh signature/timestamp headers. It cannot accept an invented result body or change the provider outcome.

`acknowledged:true` means the receiver returned a validated durable acknowledgement. Inspect `disposition`: REJECTED, UNMATCHED or REQUIRES_REVIEW does not mean a payment was approved/repaired. A 200 sandbox response with `acknowledged:false` means delivery remains unconfirmed; the decision is still retained.

To exercise process recovery, stop Terminal B with Ctrl+C and restart the same command with its original directory, UUID and callback settings. Explicit redelivery still uses the same event/body. Losing the API acknowledgement after a committed receipt is safe: redelivery returns the original receipt with `duplicate:true` and no second financial effect.

## 6. Result and refusal meanings

| Result | Meaning / action |
| --- | --- |
| 200 CONFIRMED | Existing dispatched command was confirmed; inspect provider outcome, since it may be a decline. |
| 200 ALREADY_RECORDED | Matching result already committed; late delivery did not repeat or rewind it. |
| 200 REQUIRES_REVIEW | Preserve evidence; no force-apply tool exists. |
| 200 UNMATCHED | No matching command scope exists; investigate instance/history/identity. No payment is created. |
| 200 REJECTED | Inspect safe rejection code: INTENT_MISMATCH, OUTCOME_CONFLICT or NOT_DISPATCHED. No forced application. |
| 400 INVALID_WEBHOOK | Signed payload failed closed protocol/UTF-8/money validation; no receipt. |
| 401 WEBHOOK_AUTHENTICATION_FAILED | Missing/duplicate/invalid headers, key ID/MAC/instance mismatch, or stale/future delivery. No receipt. |
| 409 WEBHOOK_EVENT_CONFLICT | Same instance/event UUID was reused with different exact bytes; original receipt remains. |
| 413 WEBHOOK_TOO_LARGE | Request exceeds 8 KiB; no receipt. |
| 503 WEBHOOK_UNAVAILABLE | Receiver disabled or retryable database failure/contention. Restore prerequisites and redeliver the original event with fresh headers. |
| Other 5xx / missing acknowledgement | Processing may have rolled back, or acknowledgement may have been lost. Retry original event; do not infer a provider decline. |

Wrong content type can return 415 before controller processing. Rejected/unmatched receipts are final diagnostic evidence and are not automatically reprocessed if conditions later change. Same-body replay returns that historical disposition. There is no receipt rewrite/relink/reset endpoint.

Privileged operators can inspect safe metadata in the intended application database:

```sql
SELECT receipt_id,provider_instance_id,event_id,operation_id,matched_operation_id,
       disposition,rejection_code,body_sha256,key_id,signed_at,received_at
FROM provider_webhook_receipts
ORDER BY received_at DESC
LIMIT 20;
```

This is trusted cross-merchant database administration. Raw bodies remain private database evidence; do not publish them or secrets. Receipt digests and constraints are local consistency evidence, not independent cryptographic attestation or backup verification.

## 7. Recovery boundaries and cleanup

Do not create replacement provider request UUIDs to work around UNKNOWN. Use the original M17 command retry or a verified redelivery of its persisted decision. A callback does not bypass frozen money/merchant/instance checks, READY dispatch requirements or a terminal outcome. Older authorization/capture notifications cannot rewind a refunded payment.

Receiver and sender support one configured signing key ID at a time. Coordinate rotation; fresh headers can authenticate the same historical body without changing its receipt. No automatic key discovery/overlap, receiver retry worker, permanent-rejection repair, production rate limiting or live-vendor compatibility is included.

After stopping the processes, clear key environment variables from the relevant terminals:

```powershell
Remove-Item Env:FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY -ErrorAction SilentlyContinue
Remove-Item Env:FINCORE_SANDBOX_WEBHOOK_KEY -ErrorAction SilentlyContinue
Remove-Item Env:FINCORE_PROVIDER_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:FINCORE_SANDBOX_API_KEY -ErrorAction SilentlyContinue
```

The [next milestone](ROADMAP.md) is reliable outgoing merchant webhooks. The optional sandbox sender exercises incoming provider verification; it is not that tenant-facing delivery service.
