# Observability runbook — Milestone 23

Use the [teaching guide](MILESTONE_23_LEARNING_GUIDE.md) for concepts/alternatives and the [summary](MILESTONE_23_SUMMARY.md) for executed evidence. Commands run from `fincore-payments`, with Java21 and **PowerShell 7**. Use a disposable local sandbox with the intended database/merchant; keep its normal `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

The implementation was verified using native PostgreSQL, portable Prometheus and a local OTLP receiver. Docker/Jaeger UI execution was unavailable. The Compose demonstration below is supplied for a Docker-enabled machine; it is not a claim that those containers were run here.

## 1. Provision a separate observer and start the API

Choose a username matching `[A-Za-z0-9][A-Za-z0-9._-]{0,99}` and a distinct password. Generate its **raw BCrypt verifier** using a trusted implementation such as Spring Security `BCryptPasswordEncoder.encode`, as described in the [credential runbook](MERCHANT_ACCESS_RUNBOOK.md). Use a measured cost of at least 10 for ordinary use; supported costs are 4–16. Never pass the verifier as an HTTP password. Do not reuse merchant/operator credentials.

In terminal A, prompt for the observer's username and verifier without putting them in command history:

```powershell
$observerVerifier = Get-Credential -Message 'Observer username; paste its raw BCrypt verifier in the password field'
$env:FINCORE_OBSERVABILITY_ENABLED = 'true'
$env:FINCORE_OBSERVABILITY_USERNAME = $observerVerifier.UserName
$env:FINCORE_OBSERVABILITY_PASSWORD_HASH = $observerVerifier.GetNetworkCredential().Password
$env:FINCORE_OBSERVABILITY_COLLECTOR_ENABLED = 'true'
$env:FINCORE_OBSERVABILITY_SNAPSHOT_INTERVAL_MILLIS = '15000'
$env:FINCORE_TRACE_SAMPLING_PROBABILITY = '1.0'
$env:FINCORE_TRACE_EXPORT_ENABLED = 'true'
$env:FINCORE_TRACE_ENDPOINT = 'http://127.0.0.1:4318/v1/traces'
.\mvnw.cmd package -DskipTests
java -jar .\target\fincore-payments-0.0.1-SNAPSHOT.jar
```

Trace export can remain false if no collector is wanted. Export failure does not refuse financial calls. Observability itself defaults false. An enabled blank username/hash pair starts with all observation access denied; an incomplete pair or invalid interval/endpoint fails configuration. The exporter supports only local IPv4 HTTP, a positive explicit port and the exact `/v1/traces` path.

This is a private local demo. The observer chain does not impose a separate listener or network firewall. Keep the API on a trusted local network; production ingress/TLS is part of deployment work. Health/Prometheus require the observer even when a network probe is local.

## 2. Prepare monitoring secret files and optional containers

In terminal B, prompt for the **same observer username and original password**. Prometheus uses the original password; the API uses its BCrypt verifier. Files below are intentionally ignored by Git. They are plaintext local Compose source files, not an encrypted secret manager; limit access to the local account and retain them only as needed.

```powershell
$apiBase = 'http://localhost:8080'
$observerCredential = Get-Credential -Message 'Observer username and original password'
$observerBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $observerCredential.UserName + ':' + $observerCredential.GetNetworkCredential().Password))
$observerHeaders = @{ Authorization = 'Basic ' + $observerBasic }
$secretDirectory = Join-Path $PWD '.observability-secrets'
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $secretDirectory 'metrics-username'), $observerCredential.UserName, $utf8NoBom)
[IO.File]::WriteAllText((Join-Path $secretDirectory 'metrics-password'), $observerCredential.GetNetworkCredential().Password, $utf8NoBom)
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile observability up -d prometheus jaeger
```

The separate overlay keeps default Compose usage free of monitoring secret requirements. The API runs on the host; Prometheus targets `host.docker.internal:8080`. If `SERVER_PORT` differs, edit that target in `observability/prometheus.yml` accordingly. The overlay adds:

- Prometheus3.5.0 UI at `http://localhost:19090`, 15-second scrape/evaluation and five-second scrape timeout.
- Jaeger2.22.0 UI at `http://localhost:16686`, local OTLP HTTP at `127.0.0.1:4318`.
- Loopback-published monitoring ports, read-only config/rules and file-based Basic credentials.
- Prometheus retention of seven days/256MB of storage blocks; WAL/head overhead means this is not an absolute disk cap. Jaeger demo storage is transient memory.

No Alertmanager or contact notification destination is configured. Use Prometheus's Targets, Rules and Alerts pages to inspect current state.

## 3. Verify access, health and freshness

```powershell
$opsRoot = "$apiBase/internal/observability"
foreach ($path in @('health','health/readiness','health/liveness')) {
    Invoke-WebRequest -Uri "$opsRoot/$path" -Headers $observerHeaders -SkipHttpErrorCheck |
        Select-Object StatusCode, Content
}
$metrics = (Invoke-WebRequest -Uri "$opsRoot/prometheus" -Headers $observerHeaders).Content
$metrics -split "`n" | Where-Object { $_ -match '^fincore_(ops_snapshot|backlog|telemetry)' }
```

Only these four GET paths are permitted. Anonymous, merchant and operator credentials receive 401 here; observer credentials cannot perform merchant/operator operations. Unlisted management routes and writes are denied. Health hides component details. Readiness depends on PostgreSQL, not Redis. Aggregate `/health` can report 503 for an unavailable enabled Redis while readiness/liveness and metrics remain usable.

Wait for `fincore_ops_snapshot_available=1`, `up=1` and a recent `age_seconds`. Before the first successful sample, count zero means unknown. After a refresh fails, old counts/oldest ages remain frozen; snapshot age keeps increasing and up becomes zero. A scrape returning 200 is not proof of fresh source data.

The collector has its own daemon worker and runs even when payment-event/merchant-webhook dispatch is disabled. It reads aggregates without changing financial rows. Query/transaction timeouts limit execution but do not establish a production IO/capacity budget. Each collecting instance repeats the same global DB backlog; do not sum them across replicas.

## 4. Create and replay a correlated payment

Provision a merchant using the [merchant runbook](MERCHANT_ACCESS_RUNBOOK.md). This demonstrates existing financial creation with new telemetry; it does not enable risk or provider workflows automatically.

```powershell
$merchantCredential = Get-Credential -Message 'Provisioned merchant username and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/csrf" -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$csrf.headerName] = $csrf.token
$merchantHeaders['Idempotency-Key'] = 'observe-' + [guid]::NewGuid().ToString()
$body = @{ merchantReference='OBSERVABILITY-DEMO'; amount=10.00; currency='INR' } | ConvertTo-Json -Compress
$first = Invoke-WebRequest -Method Post -Uri "$apiBase/api/v1/payments" -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
$replay = Invoke-WebRequest -Method Post -Uri "$apiBase/api/v1/payments" -Headers $merchantHeaders `
    -WebSession $merchantSession -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
$first | Select-Object StatusCode, Content
$replay | Select-Object StatusCode, Content
$first.Headers['X-Fincore-Trace-Id']
$replay.Headers['X-Fincore-Trace-Id']
```

Successful original creation/replay returns 201/200 with the same saved payment snapshot. Compare their trace IDs in Jaeger using service `fincore-payments`. Trace IDs correlate diagnostics only; they are not durable request identities. With sampling below 1.0 or an exporter outage, a returned ID may have no retained trace. Do not regenerate a payment key just because a trace is missing.

Optional `LOGGING_LEVEL_COM_FINCORE_PAYMENTS_OBSERVABILITY=DEBUG` enables the new fixed flow/outcome debug messages. Boot's tracing MDC provides trace/span correlation inside their scope. Do not log credentials, raw headers, payloads or all client traffic to diagnose this demo.

In Prometheus, useful queries are:

```promql
sum by (flow,outcome) (rate(fincore_flow_calls_total[5m]))
fincore_ops_snapshot_up
fincore_ops_snapshot_age_seconds
fincore_backlog_count
histogram_quantile(0.95, sum by (le,job,instance) (rate(fincore_flow_seconds_bucket{flow="payment_create"}[5m])))
```

Timers have 10ms, 100ms, 500ms, 1s, 5s and 30s SLO buckets. A two-request demo does not validate latency capacity. Calls/replays/cached admissions are process outcomes, not counts of newly committed financial effects. Parent/child durations overlap. Oldest backlog ages use source creation/event/lease/failure/report times, not a universal time-in-state.

## 5. Alert triage

Eleven rules are supplied in `observability/alerts.yml`. Backlog alarms require snapshot up=1 and age below three times `interval_seconds`. Error/latency rules include minimum traffic. A quiet alert list alone does not prove health: inspect target and snapshot freshness first. Thresholds are local demonstration policy, not contractual SLOs.

### Scrape down

Check Prometheus Targets, the API process/listener, target port, credential file contents and observer verifier. Use an authenticated manual GET. Check that observability is enabled. Repair reachability/authentication; do not restart or rewrite payments merely to clear `up=0`.

### Snapshot unavailable

Check up/available/age/interval together, database connectivity/pool capacity and the fixed collector warning. Confirm the collector is enabled. Stale data needs a successful refresh before backlog assessment. Investigate query timeout/size; do not treat retained zero counts as current proof of an empty backlog.

### Outbox stalled

Inspect sink/broker availability, dispatcher configuration, pending events and retries with the [event operations runbook](EVENT_OPERATIONS_RUNBOOK.md). Oldest age originates at event occurrence. Use the existing audited recovery commands after understanding delivery state.

### Dead deliveries

For outbox DEAD use [event operations](EVENT_OPERATIONS_RUNBOOK.md); for merchant notification DEAD use [webhook attempts and retry](MERCHANT_WEBHOOK_RUNBOOK.md). Confirm the cause is fixed and understand duplicate delivery semantics before an authorized retry. Never clear source rows directly.

### Provider uncertainty

Inspect the owned command and original request in the [provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md) and [verified callback runbook](PROVIDER_WEBHOOK_RUNBOOK.md). UNKNOWN is not a decline. Preserve the original operation identity; do not fabricate success or issue another capture to make the graph green.

### Consumer gap

Inspect retained failure evidence, quarantine and replay using the [consumer recovery runbook](CONSUMER_RECOVERY_RUNBOOK.md), then [reconcile projections](PROJECTION_RECONCILIATION_RUNBOOK.md). Quarantined unresolved evidence remains in this gauge. A lower count is not a substitute for verified coverage.

### Settlement discrepancy

Use the [settlement runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) to inspect the specific report and independent statement. Resolve only through fresh matching evidence and the established command. These counts represent reports; no alert authorizes a journal edit or bank payout.

### Risk unavailable

Check Redis reachability, script/client errors and saved decisions using the [risk runbook](PAYMENT_RISK_RUNBOOK.md). Eligible new creation remains fail closed. Accepted financial retries and recovery retain their existing paths. Disabling controls is an explicit policy choice, not an automatic alarm response.

### Payment errors

Compare `payment_create` outcomes with HTTP status, risk/provider signals and sampled trace IDs. REJECTED/THROTTLED/UNAVAILABLE are separate from unexpected ERROR. Check `fincore_telemetry_failures` if diagnostics themselves fail. For latency, inspect overlapping child spans and DB/client pressure; reproduce with representative traffic before changing thresholds.

## 6. Verification commands and evidence limits

Build/test against an isolated native PostgreSQL database with `FINCORE_TEST_DB_URL`, `FINCORE_TEST_DB_USERNAME` and `FINCORE_TEST_DB_PASSWORD`. Set `FINCORE_TEST_PROMETHEUS_BIN` to an absolute trusted Prometheus executable to enable the real native scrape test. Without it that particular test is skipped, not a Prometheus pass.

```powershell
.\mvnw.cmd '-Dit.test=LocalPostgres*IT,LocalEventOperationsCliIT,Packaged*IT,*IntegrationIT' clean verify
promtool check rules .\observability\alerts.yml
promtool test rules .\observability\alerts.test.yml
```

The recorded M23 run passed 1,216 executed tests and skipped 454 Docker-dependent cases. Separate promtool checks passed eleven rules/seven temporal scenarios. Native HTTP tests verified real scraping with the production alert file; OTLP transport tests used a real local HTTP receiver, not Jaeger. Risk-enabled native checks mock the Redis gateway. Native Kafka broker delivery, real Redis, Docker/Jaeger UI, Linux/load and independent restore were not run this session. See the [summary](MILESTONE_23_SUMMARY.md) for dated evidence.

## 7. Stop only this demonstration

Stop the terminal-A API normally, then the two optional monitoring services:

```powershell
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile observability stop prometheus jaeger
```

Prometheus's named volume is retained; Jaeger's memory data is lost when its process exits. Remove demo credential environment variables from the terminal session when finished and protect/remove only the specifically created secret files when no longer needed. Do not use a volume deletion command as routine cleanup. M24 will address authoritative backup/restore; monitoring storage is not a financial backup.
