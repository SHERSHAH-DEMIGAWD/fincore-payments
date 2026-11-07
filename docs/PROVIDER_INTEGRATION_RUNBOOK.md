# Sandbox provider integration runbook — Milestone 17

**Milestone 18 continuation:** the optional [incoming webhook receiver and sandbox sender](PROVIDER_WEBHOOK_RUNBOOK.md) can now confirm these existing commands. Keep callbacks disabled for the original M17-only lost-reply demonstration below. With callbacks enabled, the first response may already reflect a concurrently confirmed result.

This runs two independent local processes: the existing API backed by PostgreSQL and a small provider sandbox backed by a local decision journal. No real funds, cards or vendor credentials are used. Read the [learning guide](MILESTONE_17_LEARNING_GUIDE.md) for concepts/tradeoffs and [summary](MILESTONE_17_SUMMARY.md) for test evidence.

## 1. Build and choose a durable sandbox identity

Run commands from the `fincore-payments` directory using Java 21:

```powershell
.\mvnw.cmd package -DskipTests
$sandboxInstance = [guid]::NewGuid().ToString()
$sandboxInstance
```

Record that nonsecret UUID for both processes. Use one strong shared sandbox key, 32–256 visible ASCII characters; a randomly generated 64-character hexadecimal secret is suitable. Supply the same original key to the credential prompts below. Do not put the key in source, command arguments, screenshots or logs. These prompts keep it out of literal command history; environment variables remain accessible to appropriately privileged local processes.

Stop older API instances before upgrading; start the current application so Flyway applies V17 after V16. Old migrations and financial/retry rows remain unchanged. Mixed-version operation is unsupported.

## 2. Terminal A — start the provider sandbox

The credential username is a local prompt label; the sandbox uses only its password field as the shared key. Replace the example instance UUID with the recorded value, identically in both terminals.

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$sandboxKey = Get-Credential -UserName 'local-sandbox' -Message 'Shared sandbox key in the password field'
$env:FINCORE_SANDBOX_API_KEY = $sandboxKey.GetNetworkCredential().Password
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar provider-sandbox `
    --port=18081 --data-dir=.sandbox-provider --instance-id=$sandboxInstance
```

Wait for `SANDBOX_READY`, port 18081 and `realFunds:false`. This entry point runs before Spring: it opens neither PostgreSQL nor Kafka and ignores API startup settings. It binds `127.0.0.1`; use that host in Terminal B. The directory is ignored by Git and belongs to this sandbox namespace. Do not run two sandbox owners against one directory. Stop it with Ctrl+C; restart with the **same directory, instance UUID and matching configured key** to replay earlier decisions.

`SANDBOX_START_FAILED` with exit 2 means startup was refused. Check arguments, key format, port use, directory permissions/lock, original instance UUID and journal integrity. Failure output intentionally omits paths/credentials. Preserve a corrupt journal for investigation; do not erase it to make a pending command look new.

## 3. Terminal B — enable the application adapter

Keep the usual `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` settings for the intended application database. Configure only the local sandbox:

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$sandboxKey = Get-Credential -UserName 'local-sandbox' -Message 'Same shared sandbox key in the password field'
$env:FINCORE_PROVIDER_ENABLED = 'true'
$env:FINCORE_PROVIDER_BASE_URL = 'http://127.0.0.1:18081'
$env:FINCORE_PROVIDER_API_KEY = $sandboxKey.GetNetworkCredential().Password
$env:FINCORE_PROVIDER_INSTANCE_ID = $sandboxInstance
$env:FINCORE_PROVIDER_TIMEOUT_MILLIS = '2000'
$env:FINCORE_PROVIDER_LEASE_SECONDS = '30'
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar
```

Defaults disable integration; no key or nonzero instance is supplied automatically. Enabled configuration requires a loopback base URL without user info/query/fragment/path, a valid key and nonzero instance UUID. Timeout bounds are 100–10,000 ms; lease bounds 5–120 seconds, with lease at least timeout plus 2,000 ms. Timing validation still applies while disabled.

The API can start while the sandbox is unavailable; attempted commands then become UNKNOWN. A disabled adapter rejects new/unfinished execution with 409, while owned GETs and terminal historical retries remain available. Operator/event CLI commands explicitly disable provider integration even if their inherited environment enables it.

## 4. Terminal C — authenticate and create a new owned payment

First [provision a merchant](MERCHANT_ACCESS_RUNBOOK.md#1-upgrade-and-provision-an-account). No account is enabled by default. These commands acquire a real CSRF token/session and create a payment with a fresh creation idempotency key:

```powershell
$apiBase = 'http://localhost:8080'
$merchantCredential = Get-Credential -Message 'Provisioned merchant and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$merchantCsrf = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/csrf" -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$merchantCsrf.headerName] = $merchantCsrf.token
$createHeaders = $merchantHeaders.Clone()
$createHeaders['Idempotency-Key'] = 'provider-demo-' + [guid]::NewGuid().ToString()
$payment = Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments" `
    -Headers $createHeaders -WebSession $merchantSession -ContentType 'application/json' `
    -Body '{"merchantReference":"PROVIDER-DEMO","amount":100.00,"currency":"INR"}'
$providerUrl = "$apiBase/api/v1/payments/$($payment.id)/provider-operations"
```

Do not manually authorize this payment first. Provider AUTHORIZE requires CREATED and commits its binding before calling the sandbox. A payment already manually authorized cannot be adopted for provider capture/refund.

## 5. Authorize and exercise a lost capture reply

Each JSON body requires all three fields. Keep each original UUID and body for retries. A merchant request UUID is unique across that merchant's payments; changing its payment/action/scenario conflicts.

```powershell
$authorizeBody = @{ requestId=[guid]::NewGuid().ToString(); action='AUTHORIZE'; sandboxScenario='APPROVE' } | ConvertTo-Json -Compress
$authorization = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $authorizeBody
$authorization.operation

$captureBody = @{ requestId=[guid]::NewGuid().ToString(); action='CAPTURE'; sandboxScenario='TIMEOUT_AFTER_ACCEPT' } | ConvertTo-Json -Compress
$capture = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureBody
$capture.operation

# Reuse the exact saved body. Do not generate another UUID for this uncertain operation.
$captureRetry = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureBody
$captureRetry.operation
```

Authorization should be APPLIED/AUTHORIZED. The first capture records UNKNOWN because the sandbox accepts and persists it but returns an incomplete response. Payment remains AUTHORIZED with no capture journal. The exact retry normally becomes APPLIED/CAPTURED, retaining the same operation UUID and adding exactly one balanced capture journal and one transition event. If another attempt is still IN_FLIGHT, inspect progress and retry after its displayed lease expires.

Normal `APPROVE` and deliberate `DECLINE` are also supported. A declined authorization makes the payment FAILED without a journal. A declined capture/refund preserves AUTHORIZED/CAPTURED and posts no financial effect; a new logical request can then be attempted. `TIMEOUT_AFTER_ACCEPT` also works for authorization and refund.

HTTP 200 can carry UNKNOWN or IN_FLIGHT. Always inspect the operation fields; `replayed:true` only means the original request identity already existed. It does not mean a new remote approval occurred or that every replay is already terminal.

## 6. Refund and inspect owned records

After capture is confirmed:

```powershell
$refundBody = @{ requestId=[guid]::NewGuid().ToString(); action='REFUND'; sandboxScenario='APPROVE' } | ConvertTo-Json -Compress
$refund = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $refundBody
Invoke-RestMethod -Uri "$providerUrl/$($refund.operation.operationId)" -Headers $merchantHeaders
$page = Invoke-RestMethod -Uri "${providerUrl}?limit=20" -Headers $merchantHeaders
$page
Invoke-RestMethod -Uri "$apiBase/api/v1/payments/$($payment.id)" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$apiBase/api/v1/payments/$($payment.id)/ledger" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$apiBase/api/v1/payments/$($payment.id)/events" -Headers $merchantHeaders
```

The refund is full amount and creates a reversal journal. Existing event delivery can update projections asynchronously when its dispatcher/consumer is configured. Provider completion itself does not wait for Kafka delivery.

The list is ordered by operation UUID, not creation time. Use `afterOperationId=$page.nextAfterOperationId` with the same owned payment to continue; default limit is 20, maximum 100. Each GET has a read-only Repeatable Read snapshot; multiple page requests are separate snapshots. Foreign and missing payment/operation IDs both produce 404. POST retains merchant authentication and CSRF requirements. Safe views omit lease tokens, secrets and raw provider payloads.

## 7. Recovery and refusal meanings

| Observation | Action |
| --- | --- |
| UNKNOWN | Retain original body; restore provider connectivity/history/key; resubmit exactly. Never translate uncertainty into a decline. |
| IN_FLIGHT | Another attempt may own the lease. Inspect `leaseUntil`; retry original body after expiry if needed. |
| READY | Intent exists but no claimed attempt; original POST can claim it. |
| APPLIED / DECLINED | Saved terminal decision; exact retry returns it without another provider call. |
| REQUIRES_REVIEW | Preserve operation and source evidence. No force application/reset endpoint exists. |
| 409 busy | Exact retry after contention; new operation keys are not a lock-recovery mechanism. |
| 409 active command | Resolve the existing unfinished operation using its original UUID/body. |
| 409 provider-managed manual transition | Use the provider-operation flow; manual authorize/capture/fail/refund cannot bypass binding. |
| 409 disabled/changed instance | Restore enabled configuration and original journal namespace before executing unfinished work. |
| 409 retry budget exhausted | Maximum 1,000 claims reached; retain evidence for controlled review. |

If the sandbox approved but local completion fails, payment, ledger, outbox and completion all roll back together. The earlier lease survives. After it expires, replaying the original command retrieves the same remote decision and retries local application. The API may return 500 for this local infrastructure failure; it must not be treated as proof of provider decline.

The sandbox journal is capped at 10,000 decisions and cannot be pruned/reset safely while its operations may be retried. Stop a sandbox only after recording its instance/directory. Keep the original journal for all pending/history-retry obligations. Same UUID plus empty storage is not the same provider history. Atomic file publication is required; corruption and unsupported filesystem behavior refuse startup/work. Process-restart recovery is tested; power-loss durability, independent backup/restore and production load are unverified.

After stopping local processes, remove secrets from that terminal's environment when no longer needed:

```powershell
Remove-Item Env:FINCORE_SANDBOX_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:FINCORE_PROVIDER_API_KEY -ErrorAction SilentlyContinue
```

## 8. Scope and next milestone

No automatic provider retry worker, live vendor, incoming webhook, merchant notification, partial operation, fee, void/cancel or settlement is included. There is no API to unbind a managed payment or force a reviewed result. The next block is [Milestone 18 incoming provider webhooks](ROADMAP.md), with authenticated callbacks and durable duplicate/out-of-order handling.
