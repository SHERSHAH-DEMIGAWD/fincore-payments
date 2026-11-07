# Settlement reconciliation runbook — Milestone 21

Read the [guide](MILESTONE_21_LEARNING_GUIDE.md) for concepts/alternatives, [summary](MILESTONE_21_SUMMARY.md) for checks and [roadmap](ROADMAP.md) for remaining work. This demo uses independent local provider evidence. It creates no bank payout and uses no real funds.

## 1. Build and preserve identities

Use Java 21, PostgreSQL and a provisioned merchant from the [merchant access runbook](MERCHANT_ACCESS_RUNBOOK.md). Run commands from `fincore-payments`:

```powershell
.\mvnw.cmd package -DskipTests
```

Stop older API instances before upgrading; Flyway applies V21 without changing V1–20. Mixed-version operation is unsupported. If reusing a sandbox, retain its original instance UUID and decision directory. Do not reset the directory to recover an uncertain command.

The examples use `11111111-1111-4111-8111-111111111111` as a placeholder for that recorded instance. Replace it consistently in both terminals. Use separate strong 32–256 visible ASCII secrets for provider bearer access, settlement signing, and any webhook signing. Enter secrets at prompts; do not commit them.

## 2. Terminal A — start the signing provider

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$providerCredential = Get-Credential -UserName 'sandbox-api' -Message 'Provider bearer secret in password field'
$settlementCredential = Get-Credential -UserName 'settlement-signing' -Message 'Separate settlement signing secret in password field'
$env:FINCORE_SANDBOX_API_KEY = $providerCredential.GetNetworkCredential().Password
$env:FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY = $settlementCredential.GetNetworkCredential().Password
$env:FINCORE_SANDBOX_SETTLEMENT_KEY_ID = 'sandbox-v1'
# Keep automatic callbacks disabled for the explicit UNKNOWN recovery demonstration.
Remove-Item Env:FINCORE_SANDBOX_WEBHOOK_URL -ErrorAction SilentlyContinue
Remove-Item Env:FINCORE_SANDBOX_WEBHOOK_KEY -ErrorAction SilentlyContinue
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar provider-sandbox `
    --port=18081 --data-dir=.sandbox-provider --instance-id=$sandboxInstance
```

Wait for `SANDBOX_READY`. The provider binds loopback and uses its own decision files, with no application database. Without the settlement signing key, the export endpoint returns 503. Restart with the same directory, instance and signing settings to retain exact export bytes at an unchanged horizon.

## 3. Terminal B — configure the API

Retain the intended application's `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. Enter the same two original secrets used in terminal A:

```powershell
$sandboxInstance = '11111111-1111-4111-8111-111111111111'
$providerCredential = Get-Credential -UserName 'sandbox-api' -Message 'Same provider bearer secret'
$settlementCredential = Get-Credential -UserName 'settlement-signing' -Message 'Same separate settlement signing secret'
$env:FINCORE_PROVIDER_ENABLED = 'true'
$env:FINCORE_PROVIDER_BASE_URL = 'http://127.0.0.1:18081'
$env:FINCORE_PROVIDER_API_KEY = $providerCredential.GetNetworkCredential().Password
$env:FINCORE_PROVIDER_INSTANCE_ID = $sandboxInstance
$env:FINCORE_PROVIDER_TIMEOUT_MILLIS = '2000'
$env:FINCORE_PROVIDER_LEASE_SECONDS = '30'
$env:FINCORE_SETTLEMENT_ENABLED = 'true'
$env:FINCORE_SETTLEMENT_SIGNING_KEY = $settlementCredential.GetNetworkCredential().Password
$env:FINCORE_SETTLEMENT_KEY_ID = 'sandbox-v1'
$env:FINCORE_SETTLEMENT_INSTANCE_ID = $sandboxInstance
$env:FINCORE_PROVIDER_WEBHOOK_ENABLED = 'false'
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar
```

Imports default off. Enabled configuration requires a valid key/key ID and nonzero pinned instance. One configured signing key is supported; no rotation/key ring is implemented. Disabling imports later does not delete existing evidence or disable its owned reads/reporting. Headless event CLI explicitly disables this API credential requirement.

## 4. Terminal C — merchant credentials and a lost capture reply

```powershell
$apiBase = 'http://localhost:8080'
$merchantCredential = Get-Credential -Message 'Provisioned merchant username and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$merchantCsrf = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/csrf" `
    -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$merchantCsrf.headerName] = $merchantCsrf.token
$me = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/me" -Headers $merchantHeaders
$merchantId = $me.merchantId
$statementRoot = "$apiBase/api/v1/merchant/settlement-statements"

$createHeaders = $merchantHeaders.Clone()
$createHeaders['Idempotency-Key'] = 'settlement-demo-' + [guid]::NewGuid().ToString()
$payment = Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments" `
    -Headers $createHeaders -WebSession $merchantSession -ContentType 'application/json' `
    -Body '{"merchantReference":"SETTLEMENT-DEMO","amount":100.00,"currency":"INR"}'
$providerUrl = "$apiBase/api/v1/payments/$($payment.id)/provider-operations"
$authorizeBody = @{ requestId=[guid]::NewGuid().ToString(); action='AUTHORIZE'; sandboxScenario='APPROVE' } | ConvertTo-Json -Compress
$authorization = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $authorizeBody
$captureBody = @{ requestId=[guid]::NewGuid().ToString(); action='CAPTURE'; sandboxScenario='TIMEOUT_AFTER_ACCEPT' } | ConvertTo-Json -Compress
$capture = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureBody
$capture.operation
```

Inspect state: this demonstration expects UNKNOWN after the sandbox persists its capture. Keep `$captureBody` unchanged for recovery. Do not manually authorize/capture or use amount operations on this provider-bound payment. If automatic callbacks are enabled elsewhere, they may confirm before you obtain the first report; that is a different valid flow.

## 5. Export exact bytes and import them

The export bearer secret is privileged sandbox access, distinct from merchant Basic credentials. The imported statement's merchant must match the authenticated merchant. Use `Invoke-WebRequest -OutFile` to preserve bytes, without `ConvertFrom-Json`/`ConvertTo-Json` or text encoding conversions:

```powershell
$providerCredential = Get-Credential -UserName 'sandbox-api' -Message 'Same provider bearer secret for export'
$exportHeaders = @{ Authorization='Bearer ' + $providerCredential.GetNetworkCredential().Password }
$evidenceDirectory = Join-Path $env:TEMP ('fincore-settlement-demo-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $evidenceDirectory | Out-Null
$statementFile = Join-Path $evidenceDirectory 'statement.json'
$headerFile = Join-Path $evidenceDirectory 'statement-headers.json'
$export = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:18081/v1/settlements/$merchantId/INR" `
    -Headers $exportHeaders -OutFile $statementFile -PassThru
$keyId = [string]$export.Headers['Fincore-Settlement-Key-Id']
$signature = [string]$export.Headers['Fincore-Settlement-Signature']
@{ keyId=$keyId; signature=$signature } | ConvertTo-Json | Set-Content -Encoding UTF8 $headerFile
$importHeaders = $merchantHeaders.Clone()
$importHeaders['Fincore-Settlement-Key-Id'] = $keyId
$importHeaders['Fincore-Settlement-Signature'] = $signature
$imported = Invoke-RestMethod -Method Post -Uri $statementRoot -Headers $importHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -InFile $statementFile
$statementId = $imported.statement.statementId
$imported
```

The signature sidecar contains verification metadata, not the signing secret. Preserve it with the original file. An exact reimport returns `replayed=true`. Validly signed changed bytes under the same statement identity return 409. Wrong signature/key ID returns 401, malformed authenticated document 400, pinned-instance mismatch 409 and oversize body 413. Missing/foreign owned resources return 404; absent Basic credentials 401 and missing POST CSRF 403. A disabled import returns 503.

There is no expiry window on an archived statement's HMAC. Its horizon and report observation determine coverage, not how recently the file was delivered.

## 6. Record the discrepancy before recovery

```powershell
$reportBody = @{ requestId=[guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
$before = Invoke-RestMethod -Method Post -Uri "$statementRoot/$statementId/reports" `
    -Headers $merchantHeaders -WebSession $merchantSession -ContentType 'application/json' -Body $reportBody
$before.report.comparison
$oldReportId = $before.report.reportId
```

For an otherwise clean merchant scope, expect external captured/net 100, local net zero and `LOCAL_UNCONFIRMED`. Previous merchant activity can add other items or discrepancies. The comparison covers that entire merchant/instance/currency, not just `$payment.id`.

Repeating `$reportBody` returns the original report even after recovery. Use a new UUID when requesting today's observation. A report with `matched=true` is historical evidence, not payout authorization.

## 7. Recover the original command, compare again, then resolve

```powershell
$recovered = Invoke-RestMethod -Method Post -Uri $providerUrl -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $captureBody
$recovered.operation
$matchedBody = @{ requestId=[guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
$after = Invoke-RestMethod -Method Post -Uri "$statementRoot/$statementId/reports" `
    -Headers $merchantHeaders -WebSession $merchantSession -ContentType 'application/json' -Body $matchedBody
$after.report.comparison
# Continue only after inspecting matched=true and the expected lines/totals.
$resolveBody = @{
    requestId=[guid]::NewGuid().ToString()
    matchedReportId=$after.report.reportId
    reason='Recovered original provider capture; same statement now matches'
} | ConvertTo-Json -Compress
$resolved = Invoke-RestMethod -Method Post -Uri "$statementRoot/$statementId/reports/$oldReportId/resolve" `
    -Headers $merchantHeaders -WebSession $merchantSession -ContentType 'application/json' -Body $resolveBody
$resolved
Invoke-RestMethod -Uri "$statementRoot/$statementId/reports/$oldReportId/resolution" -Headers $merchantHeaders
```

A still-IN_FLIGHT command may require waiting for its displayed lease before the original retry. M17/M18 completion is the only path in this demonstration that posts money/event effects. The reason is a nonblank single line, at most 300 characters; the actor comes from the authenticated merchant principal.

Closure requires an unresolved discrepant report and a matching report for the same statement, plus a fresh matching comparison/fingerprint inside the resolution transaction. Changed source, wrong statement, bad evidence or a second distinct closure returns 409. Exact accepted resolution retry returns its original result, even after later source changes. Reusing a request UUID with different intent conflicts.

Do not change financial rows, edit signed files or invent a new capture UUID to make a discrepancy disappear. Amount/fee/identity discrepancies and unknown external operations remain investigation cases. There is no override, issuer correction or cross-horizon closure endpoint.

## 8. Inspect archives and pagination

```powershell
Invoke-RestMethod -Uri "$statementRoot`?limit=10" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$statementRoot/$statementId" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$statementRoot/$statementId/reports?limit=10" -Headers $merchantHeaders
Invoke-RestMethod -Uri "$statementRoot/$statementId/reports/$oldReportId" -Headers $merchantHeaders
$downloadFile = Join-Path $evidenceDirectory 'downloaded-statement.json'
Invoke-WebRequest -UseBasicParsing -Uri "$statementRoot/$statementId/document" `
    -Headers $merchantHeaders -OutFile $downloadFile | Out-Null
Get-FileHash -Algorithm SHA256 $statementFile,$downloadFile
```

The two SHA-256 digests should equal each other and the imported metadata hash. Downloads also return `X-Content-SHA256` and original signing headers. Follow `nextAfterStatementId` as `afterStatementId`, or `nextAfterReportId` as `afterReportId`; UUID order is not chronological order. Limits are 1–100 and list responses contain metadata, not every report body.

After a new capture/refund, an old cumulative statement may report `MISSING_EXTERNAL`. Export/import a new file for a new horizon and use a new report UUID. Keep old files/reports. A new file cannot close discrepancies belonging to a different statement under the current contract.

## 9. Operating limits and stopping

Imports: 1 MiB/1,000 lines. Comparison: 1,000 local commands, with a 1,001-row overflow probe. Reports: 2,000 items/2 MiB. Sandbox: 10,000 decisions. Excess is refused rather than silently truncated. These are sandbox limits, not performance benchmarks or an archive/retention strategy.

Contention on another merchant evidence command returns 409 without waiting; retry the same intent. Source payment writes can continue concurrently. Reports and closure describe one coherent Repeatable Read observation and may become historical immediately.

Use Ctrl+C to stop only the API/provider processes you started. Preserve the provider directory and intended PostgreSQL data. Remove the demo secret environment variables from their terminals when finished. The statement files contain merchant financial identifiers; preserve/delete your demo directory deliberately according to your local needs. This runbook does not execute database deletion or bank settlement.
