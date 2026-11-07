# Local sandbox release and acceptance runbook

Milestone 25, 28 October 2026. This deploys the independent `fincore-payments` learning system with simulated funds. Read the [final project guide](FINAL_PROJECT_GUIDE.md), [M25 concepts](MILESTONE_25_LEARNING_GUIDE.md), [final validation evidence](FINAL_VALIDATION_REPORT.md), [retention policy](RETENTION_POLICY.md) and [database recovery runbook](DATABASE_RECOVERY_RUNBOOK.md).

## 1. Prerequisites and release creation

Use Java **21**, PowerShell **7**, and Maven 3.9.11 (or the checked-in wrapper). Building the container sandbox additionally requires a working Linux-container Docker Engine and Compose v2.39.4 or compatible newer release. Allow several GB of RAM for PostgreSQL, Kafka, Redis, API and acceptance processes. Choose unused host port18080. The native Windows validation used PostgreSQL18.6 and Kafka3.9.1; the Compose recipe pins PostgreSQL17.6, Redis7.4.2, Kafka3.9.1 and the Java21 runtime by immutable digest. Container execution on this Windows host remains unverified.

From the **project directory**:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
pwsh -File scripts/Build-LocalRelease.ps1 -JavaHome $env:JAVA_HOME
```

The script always runs `clean verify`, including the selected native, packaged and container integration suites. Unavailable native resources and Docker suites may skip: inspect `release-manifest.json`, not just BUILD SUCCESS. To include native checks set `FINCORE_TEST_DB_URL`, `FINCORE_TEST_DB_USERNAME`, `FINCORE_TEST_DB_PASSWORD`, `FINCORE_TEST_PG_BIN`, `FINCORE_TEST_KAFKA_BOOTSTRAP` and `FINCORE_TEST_PROMETHEUS_BIN` to dedicated local test resources. Never point test fixtures at an operational database. `-MavenCommand`, `-MavenRepository` and `-Offline` support an already populated portable toolchain. The output directory must be new and inside the project.

The resulting `release-artifacts/run-*/bundle` contains a verified executable JAR, deployment recipe, four operating guides, verifier, manifest and `SHA256SUMS`. Its sibling `fincore-local-release.zip` is created before any demo secrets. The minimal ZIP includes this runbook, the final project map, retention policy and database recovery runbook; links to other milestone guides/reports refer to the full source checkout. `verify.log` stays outside the ZIP and can contain private diagnostic evidence. The manifest reports actual executed/skipped tests and explicitly denies production certification. No source checkout revision is invented when Git has no HEAD.

## 2. Verify and initialize a dedicated bundle

Extract the ZIP to a private directory, enter it, then:

```powershell
pwsh -File scripts/Verify-LocalRelease.ps1 -Directory .
$env:FINCORE_JAR_SHA256 = (Get-FileHash target/fincore-payments-0.0.1-SNAPSHOT.jar -Algorithm SHA256).Hash.ToLowerInvariant()
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar init-local-sandbox --directory=deployment/.secrets
docker compose -f deployment/compose.yml --profile acceptance config --quiet
```

The verifier checks the exact allowlisted file digests and manifest-to-JAR binding. Matching hashes detect changed bytes relative to the supplied reference; keep the reference through trusted custody. This is not a signature or independent authenticity attestation. Do not execute an untrusted bundled verifier merely because it is present.

Initialization generates thirteen inputs: database password, two merchant passwords, observer/operator originals and BCrypt verifiers, provider API/callback/statement keys, webhook AES master key, provider instance UUID and subscription request UUID. Values never appear in console output. Initialization refuses an existing directory; **do not regenerate inputs while retaining old volumes**. POSIX host directories are0700; files are0444 so the non-root container can read Compose file secrets, whose ownership remapping is limited. Windows uses inherited ACLs: keep the parent private. Files are local secrets, not an encrypted secret vault. No keys/passwords belong in Git, ZIPs, CI uploads or command arguments.

## 3. Start the sandbox

```powershell
docker compose -f deployment/compose.yml up -d --build --wait api provider redis
docker compose -f deployment/compose.yml --profile acceptance run --rm acceptance
docker compose -f deployment/compose.yml exec -T api cat /evidence/acceptance.json
```

The Dockerfile accepts only the specified JAR SHA-256, verifies the copied JAR, and runs as uid10001. Application containers have a read-only root, writable bounded temporary space, dropped capabilities, memory/process limits and bounded Docker log files. The build context allowlist excludes secrets, source/test trees and tools. Defaults are demonstration limits, not measured capacity sizing.

PostgreSQL readiness precedes one-shot schema/merchant bootstrap; Kafka readiness precedes the API. Provider and Redis start after the API network namespace exists. API readiness and Redis health precede acceptance. The provider's signed-statement request is retried until it responds. Bootstrap is atomic for the two merchant accounts; retries require existing enabled accounts to match the original passwords and never rotate/adopt an account. Schema remains V22.

Only `127.0.0.1:18080` is published. Kafka/PostgreSQL use the private Compose network. Provider, Redis and the one-shot receiver share the API's network namespace so existing literal-loopback adapter restrictions remain valid. The API does not receive original merchant/operator passwords; its readiness probe has the observer original and no payment authority. The acceptance service owns the browser credentials. The plain HTTP/Basic topology is a local sandbox, not an internet exposure recipe. [Compose service configuration](https://docs.docker.com/reference/compose-file/services/), [Dockerfile reference](https://docs.docker.com/reference/dockerfile/).

The provider persists a separate journal, and automatic callbacks are disabled in this recipe. Acceptance deliberately obtains UNKNOWN from a timeout-after-accept capture and explicitly redelivers a signed callback twice. The callback path remains `/api/v1/provider-webhooks/sandbox`; signature verification and duplicate safety remain enabled.

## 4. Acceptance evidence and reruns

The six command options are `--url`, `--provider-url`, `--sink-port`, `--report`, `--requests` and `--concurrency`. HTTP URLs must be `http://127.0.0.1:<explicit-port>` with no path/query/credentials. Workloads are bounded to1–100 distinct requests and1–8 workers. A new report is reserved before HTTP writes; existing reports refuse overwrite. Reports contain fixed check codes, elapsed/workflow latency summaries, receipt count and explicit risk/Kafka flags, without credentials or business payloads.

The normal Compose run requires both risk and Kafka validation. The native Windows drill explicitly turns risk off because no real Redis is available and records `riskValidated=false`; do not treat that as the full container result. Projection is asynchronous: acceptance waits for owned REFUNDED projections after Kafka consumption. A passing creation response alone cannot prove that boundary.

Acceptance checks real credentials/CSRF, cross-merchant404, role separation, idempotent creation/conflict, provider recovery, independent statement reconciliation, partial captures/refunds with retained fees, signed outgoing notifications, dead-delivery retry, same-key races, distinct creations and private metrics. It pauses its subscription on successful completion. Worker clients have separate browser sessions and fresh masked CSRF tokens; machine traffic has no browser cookie jar. Whole HTTP responses have deadlines and size caps, including stalled bodies. Latency includes CSRF retrieval and represents this small workflow smoke test, not sustainable throughput or a production SLO.

For another run keep the same secrets, provider journal, database and evidence volume, but choose a **new** report path:

```powershell
docker compose -f deployment/compose.yml --profile acceptance run --rm acceptance sandbox-acceptance --url=http://127.0.0.1:8080 --provider-url=http://127.0.0.1:18081 --sink-port=18082 --report=/evidence/acceptance-2.json --requests=24 --concurrency=4
```

Run only one acceptance owner per sandbox namespace at a time. Its receiver journal is keyed by the stable subscription UUID and records verified delivery identities. The receiver intentionally stops during recovery checks. A failed/interrupted run retains a failure report and may leave its subscription active; inspect owned subscription/delivery state and use the [webhook runbook](MERCHANT_WEBHOOK_RUNBOOK.md) before retrying. Never delete durable delivery evidence to force a pass. Reports and volumes are private local artifacts even though report fields are deliberately limited.

## 5. Stop, retain and recover

```powershell
docker compose -f deployment/compose.yml down
```

This stops the namespace but retains PostgreSQL, provider, Kafka and acceptance evidence volumes. Do not add `-v`. Keep the original secrets with retained encrypted subscriptions and original provider identity/journal. Redis is intentionally ephemeral: loss can replenish its soft admission quota; PostgreSQL remains financial authority. Bound Docker log rotation does not delete business journals, receiver receipts or database evidence. See the [retention policy](RETENTION_POLICY.md) for unresolved lifecycle obligations.

Readiness is a current probe, not a recovery guarantee. A database backup does not reconstruct provider journal state, Kafka history, receiver receipts, external keys or access grants. Follow the isolated M24 restore drill; do not restore over source or silently mix restored PostgreSQL with unrelated provider data. The release does not implement TLS ingress, live-bank payouts, a managed secret vault, HA, cross-system cutover, PITR or deletion.

## 6. CI and current validation boundary

The repository-root `.github/workflows/fincore-payments.yml` provides Java21 verification, release creation, container build and the full Compose acceptance job on an Ubuntu Docker runner. Its upload allowlist contains only the pre-initialization release ZIP and bounded acceptance report. It never uploads local secret directories, raw bundle directories after initialization or Maven diagnostic logs. Cleanup uses `down` without volume deletion. This recipe has not been executed or published from the current host. Consult [FINAL_VALIDATION_REPORT.md](FINAL_VALIDATION_REPORT.md) for actual local evidence and outstanding environment checks.
