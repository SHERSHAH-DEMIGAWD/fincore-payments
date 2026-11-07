# Payment admission and risk runbook — Milestone 22

Use the [learning guide](MILESTONE_22_LEARNING_GUIDE.md) for concepts/alternatives and the [summary](MILESTONE_22_SUMMARY.md) for executed checks and limitations. The demo uses Java21, PostgreSQL, a loopback Redis instance and a provisioned merchant. PowerShell examples below require **PowerShell 7** for `-SkipHttpErrorCheck`.

Real Redis/Docker execution was unavailable on the implementation machine. These are runnable instructions for a Docker-enabled environment; the summary distinguishes static/unit/PostgreSQL verification from unexecuted Redis integration tests.

## 1. Build and start local infrastructure

Run from `fincore-payments`:

```powershell
.\mvnw.cmd package -DskipTests
docker compose --profile risk up -d --wait postgres redis
```

The optional `risk` profile adds `redis:7.4.2-alpine`, bound to `127.0.0.1:6379` (override `REDIS_PORT` in Compose if needed). It uses no persistence, a 64 MiB limit and `noeviction`. It has no volume to erase. An out-of-memory refusal must not be handled by clearing live keys automatically. This is a local sandbox setup, not a public Redis deployment.

Retain the intended PostgreSQL data and account ownership. Apply V22 by starting the current application after stopping older API instances. V1–21 remain historical migrations. See the [merchant access runbook](MERCHANT_ACCESS_RUNBOOK.md) to provision a merchant; none is enabled automatically.

## 2. Terminal A — configure a small demonstration policy

Keep the usual `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` for your intended database. All application instances sharing a namespace must use matching policy settings:

```powershell
$env:FINCORE_RISK_ENABLED = 'true'
$env:FINCORE_RISK_CURRENCY = 'INR'
$env:FINCORE_RISK_MAXIMUM_AMOUNT = '100.00'
$env:FINCORE_RISK_MAX_ADMISSIONS = '2'
$env:FINCORE_RISK_WINDOW_SECONDS = '60'
$env:FINCORE_RISK_NAMESPACE = 'demo22'
$env:FINCORE_RISK_REDIS_HOST = '127.0.0.1'
$env:FINCORE_RISK_REDIS_PORT = '6379'
$env:FINCORE_RISK_REDIS_DATABASE = '0'
$env:FINCORE_RISK_TIMEOUT_MILLIS = '250'
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar
```

The application may start while Redis is unavailable; new eligible creation then fails closed. Enabled configuration rejects an invalid amount ceiling, unsupported host, out-of-range port/database/window/quota/timeout or invalid credential combination before serving requests. Redis host is deliberately restricted to `127.0.0.1`; Sentinel, Cluster and remote/TLS setup are not implemented here.

For an independently configured local ACL-enabled Redis, optionally set `FINCORE_RISK_REDIS_USERNAME` and `FINCORE_RISK_REDIS_PASSWORD`. Use a secure local prompt for the password rather than literal source/history. The Compose demo above has no ACL credentials; leave those variables absent/empty for it. This client needs script execution and the hash/expiry commands used by `redis/payment-admission.lua`.

Defaults when not overridden: disabled, INR100000.00, 20 admissions/60 seconds, namespace `local`, port6379/database0/250ms. No Redis connection factory is created when disabled. Explicitly disabling the feature bypasses risk checks; it is not the automatic response to an outage. Existing evidence remains readable.

## 3. Terminal B — authenticate and define the request helper

```powershell
$apiBase = 'http://localhost:8080'
$merchantCredential = Get-Credential -Message 'Provisioned merchant username and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$csrf = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/csrf" -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$csrf.headerName] = $csrf.token
$me = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/me" -Headers $merchantHeaders
$riskRoot = "$apiBase/api/v1/merchant/risk-decisions"
$demoPrefix = 'risk-' + [guid]::NewGuid().ToString()

function Submit-PaymentDemo([string]$key, [decimal]$amount=10, [string]$currency='INR') {
    $headers = $merchantHeaders.Clone()
    $headers['Idempotency-Key'] = $key
    $body = @{ merchantReference='RISK-DEMO'; amount=$amount; currency=$currency } | ConvertTo-Json -Compress
    Invoke-WebRequest -Method Post -Uri "$apiBase/api/v1/payments" -Headers $headers `
        -WebSession $merchantSession -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
}
```

Keep the same body/reference when retrying a creation key. A changed reference, principal or currency is a different intent and conflicts once the key has durable creation/risk intent. Equivalent decimal scale does not change the canonical fingerprint.

## 4. Demonstrate admission, exact replay and throttling

Use a merchant with no other concurrent creation traffic in this namespace/policy:

```powershell
$firstKey = "$demoPrefix-first"
$secondKey = "$demoPrefix-second"
$throttledKey = "$demoPrefix-third"
$first = Submit-PaymentDemo $firstKey
$first.StatusCode
$first.Content
$again = Submit-PaymentDemo $firstKey
$again.StatusCode
$again.Content
$second = Submit-PaymentDemo $secondKey
$second.StatusCode
$third = Submit-PaymentDemo $throttledKey
$third.StatusCode
$third.Headers
$third.Content
```

Expect 201, 200, 201, then 429. First/replayed responses name the same payment. The exact accepted retry consumes no new quota; the third distinct intent receives `PAYMENT_RATE_LIMITED`, `Fincore-Risk-Decision-Id` and `Retry-After`. It creates no payment, financial idempotency record or outbox event.

After waiting at least the returned delay, retry **the same** `$throttledKey` and body:

```powershell
$retryAfter = [int]($third.Headers['Retry-After'] | Select-Object -First 1)
Start-Sleep -Seconds $retryAfter
$recoveredThrottle = Submit-PaymentDemo $throttledKey
$recoveredThrottle.StatusCode
$recoveredThrottle.Content
```

Expect 201 if capacity is available. The previous THROTTLED decision remains and a new ALLOW is appended to that same intent. Other concurrent creations can consume the renewed capacity before this request arrives.

This quota uses a TTL anchored at first admission, not a wall-clock minute or rolling sixty-second window. Rejections do not keep extending it. At most quota+1 fields are written in each merchant/policy hash through this code path.

## 5. Demonstrate static denials and changed intent

These rules run before Redis reservation:

```powershell
$largeKey = "$demoPrefix-large"
$large = Submit-PaymentDemo $largeKey 100.01
$large.StatusCode
$large.Headers
$large.Content
$unsupported = Submit-PaymentDemo "$demoPrefix-currency" 10 'USD'
$unsupported.StatusCode
$unsupported.Content
$changed = Submit-PaymentDemo $largeKey 10
$changed.StatusCode
$changed.Content
```

Expect 422 for the two static policy denials and 409 for changing the previously bound large request. Static DENY is terminal while controls are enabled; waiting for a Redis window does not make that exact request pass. A deliberate new logical payment uses a new key and is evaluated as new work. No manual override, risk-case review or grant-revocation endpoint exists.

## 6. Demonstrate outage and recovery

Stop only the demo Redis service, leaving PostgreSQL and the API running:

```powershell
docker compose --profile risk stop redis
$outageKey = "$demoPrefix-outage"
$outage = Submit-PaymentDemo $outageKey
$outage.StatusCode
$outage.Headers
$outage.Content
$acceptedRetry = Submit-PaymentDemo $firstKey
$acceptedRetry.StatusCode
$acceptedRetry.Content
```

Expect 503/`PAYMENT_RISK_UNAVAILABLE` for new eligible work and 200 for the already accepted original creation. The unavailable result commits as evidence; it creates no payment. Existing payment reads, provider recovery, callbacks, capture/refund and settlement operations do not require this Redis admission check.

Restart and retry the original outage intent:

```powershell
docker compose --profile risk up -d --wait redis
$outageRetry = Submit-PaymentDemo $outageKey
$outageRetry.StatusCode
$outageRetry.Content
```

The ephemeral Redis restart clears its capacity state. This can replenish quota before an older conceptual window ends; it is an explicit soft-control limitation. PostgreSQL retains request binding, decisions and financial idempotency. A missing key begins a new window; a corrupt existing key is refused. Do not use FLUSHDB, delete merchant keys or change policy/namespace as a request-retry technique.

## 7. Read owned history

```powershell
$page = Invoke-RestMethod -Uri "$riskRoot`?limit=10" -Headers $merchantHeaders
$page.decisions
if ($page.nextAfterDecisionId) {
    Invoke-RestMethod -Uri "$riskRoot`?limit=10&afterDecisionId=$($page.nextAfterDecisionId)" -Headers $merchantHeaders
}
$decisionId = [string]($outage.Headers['Fincore-Risk-Decision-Id'] | Select-Object -First 1)
Invoke-RestMethod -Uri "$riskRoot/$decisionId" -Headers $merchantHeaders
```

History shows original amount/currency, hashed request key, outcome/reason, policy snapshot, optional count/remaining TTL and observed time. It omits raw creation keys, credentials and transport errors. UUID page order is not chronological order. Foreign/missing decision UUIDs both return404; unauthenticated reads return401. POST creation keeps Basic/CSRF requirements; missing CSRF returns403 before admission.

An ALLOW grant can survive a later payment transaction rollback and policy change. It has no expiry/revocation in this milestone. A PostgreSQL failure may prevent evidence recording; it does not authorize Redis-only creation. Read the guide's failure cases before interpreting an ALLOW as a created or captured payment.

## 8. Run verification and stop owned services

```powershell
# Unit/script logic and configuration. LuaJ uses fake Redis commands.
.\mvnw.cmd test

# Docker-based real PostgreSQL, real Redis, and combined creation tests.
.\mvnw.cmd '-Dit.test=PaymentRiskIntegrationIT,RedisRiskIntegrationIT,PaymentRiskEndToEndIntegrationIT' verify

# Optional real Redis checks against a dedicated local test database.
$env:FINCORE_TEST_REDIS_PORT = '6379'
$env:FINCORE_TEST_REDIS_DATABASE = '15'
.\mvnw.cmd '-Dit.test=LocalRedisRiskIT' verify
Remove-Item Env:FINCORE_TEST_REDIS_PORT,Env:FINCORE_TEST_REDIS_DATABASE
```

The local-Redis suite uses unique namespaces and deletes only its own recorded keys. It does not FLUSH a shared database. Use a dedicated test Redis/database nonetheless; the tests intentionally create temporary wrong-type/persistent keys to verify refusal. Container skips are not passes. Native PostgreSQL risk tests use a mocked Redis gateway; they cannot establish server Lua behavior. See the dated summary for the actual checks executed here.

Stop the API with Ctrl+C. Stop only services started for this demo with `docker compose --profile risk stop redis postgres`; this retains PostgreSQL's volume. No `down -v`, database deletion, source correction or bank transfer is part of this runbook.
