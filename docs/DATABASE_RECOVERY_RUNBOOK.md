# Database backup and isolated restore runbook — Milestone 24

Use the [learning guide](MILESTONE_24_LEARNING_GUIDE.md), [retention decision](RETENTION_POLICY.md) and [verification summary](MILESTONE_24_SUMMARY.md). These commands implement a bounded **local logical database recovery drill**. They do not fail over a running platform, delete source data or restore every external dependency.

Use Java21, PowerShell7 and trusted PostgreSQL tools matching the source/restore major version. This format supports the current V22 schema on PostgreSQL17/18, UTF-8, ordinary persistent public tables and the project's existing functions/indexes/triggers. Same-major restoration and matching database collation are required. The executed environment is recorded in the summary; broader compatibility is not implied.

## 1. Recovery boundaries and preparation

Use a trusted source/backup operator. A dump contains executable SQL and sensitive database contents, including credential verifiers, encrypted webhook keys, evidence and payment data. Its manifest contains fingerprints, not encryption or a signature. Protect the source, PostgreSQL executables, artifact directory and independently retained manifest hash. Do not restore an archive from an untrusted source merely because it is internally self-consistent. [PostgreSQL restore behavior](https://www.postgresql.org/docs/18/app-pgrestore.html).

Keep DDL/migrations paused during capture. Ordinary payment writers may continue; source inventory and `pg_dump` share one exported snapshot. No source rows are updated by the command. Its read transaction can hold table locks and retain old row versions while running; schedule capacity deliberately.

This version refuses other user schemas, sequences/views/partitions/foreign or unlogged tables, large objects, custom public types/aggregates, extra extensions, RLS, invalid constraints and disabled triggers. Limits: 128 tables; one million rows/256MiB of serialized table data; 512MiB completed archive; 1MiB manifest; 30-second individual SQL statements, five-second lock wait and 120-second dump subprocess. These are teaching budgets, not an absolute disk/memory or whole-command latency guarantee.

Run from `fincore-payments` in a fresh terminal. Keep the source API separate. Choose an actual existing source database with migrations V1–22:

```powershell
.\mvnw.cmd package -DskipTests
$jar = (Resolve-Path '.\target\fincore-payments-0.0.1-SNAPSHOT.jar').Path
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$backupCredential = Get-Credential -Message 'Trusted source backup role and original password'
$env:FINCORE_RECOVERY_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/fincore_payments'
$env:FINCORE_RECOVERY_DB_USERNAME = $backupCredential.UserName
$env:FINCORE_RECOVERY_DB_PASSWORD = $backupCredential.GetNetworkCredential().Password
$sourceUrl = $env:FINCORE_RECOVERY_DB_URL
$sourceMatch = [regex]::Match($sourceUrl, '^jdbc:postgresql://127\.0\.0\.1:([0-9]{1,5})/([a-z][a-z0-9_]{0,62})$')
if (-not $sourceMatch.Success) { throw 'Use the supported explicit loopback URL.' }
$sourcePort = $sourceMatch.Groups[1].Value
$artifactRoot = Join-Path $PWD 'recovery-artifacts'
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$bundleDirectory = Join-Path $artifactRoot ('bundle-' + [guid]::NewGuid().ToString('N'))
```

Only dedicated `FINCORE_RECOVERY_DB_*` variables configure these commands. JDBC URLs cannot carry query parameters, credentials or multiple hosts. Passwords are passed through process environment, not command arguments or JSON output. Source read privileges must cover every supported table/catalog; use a dedicated trusted local drill role rather than assuming ordinary merchant privileges provide database access.

## 2. Capture one consistent backup

```powershell
$captureOutput = & java -jar $jar create-recovery-backup "--directory=$bundleDirectory" "--pg-dump=$pgBin\pg_dump.exe"
if ($LASTEXITCODE -ne 0) { throw 'Capture refused; retain partial files and inspect locally.' }
$capture = $captureOutput | ConvertFrom-Json
$capture
Get-FileHash -LiteralPath $jar -Algorithm SHA256
```

The new directory contains `database.dump`, `manifest.json` and `pg-dump.log`. A successful command emits `BACKUP_CAPTURED_RESTORE_NOT_YET_VERIFIED`, bundle UUID, exact manifest/archive SHA-256 and aggregate counts. **Capture success is not restore success.** Retain `manifestSha256` through a separate trusted channel, along with the tested application JAR/hash and tool versions. Keeping the only trusted hash alongside an untrusted replaceable bundle does not establish provenance.

The manifest is written after a successful dump and bounded file hashing. Existing directories are refused; no overwrite or automatic cleanup occurs. Failed runs can leave partial archives/logs without a completed manifest. Nonempty pg_dump stderr also refuses completion so warnings are investigated. Inspect logs in the protected local directory; public output deliberately omits paths, credentials and detailed errors.

The archive is a compressed PostgreSQL custom-format logical database dump with ownership/ACL restoration omitted. It is not a cluster-role backup, encrypted archive, filesystem backup, WAL/PITR chain or object-store upload. The manifest covers all supported public tables, Flyway rows, selected structural definitions and balanced journal pairs. See the guide for the narrow array-cast normalization used for PostgreSQL dump/reparse equivalence.

## 3. Verify custody before executing archive SQL

If restoring later, use the retained artifact location and enter the hash from its trusted separate record. Do not calculate a new “expected” hash from a suspect manifest.

```powershell
$trustedManifestHash = Read-Host 'Trusted lowercase manifest SHA-256 retained at capture'
if ($trustedManifestHash -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid trusted hash.' }
$manifestPath = Join-Path $bundleDirectory 'manifest.json'
if ((Get-Item -LiteralPath $manifestPath).Length -gt 1MB) { throw 'Manifest exceeds budget.' }
$actualManifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualManifestHash -cne $trustedManifestHash) { throw 'Manifest differs from trusted reference.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
$archivePath = Join-Path $bundleDirectory 'database.dump'
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archiveHash -cne $manifest.archiveSha256 -or (Get-Item -LiteralPath $archivePath).Length -ne $manifest.archiveBytes) {
    throw 'Archive differs from the authenticated-by-custody manifest.'
}
```

Keep the directory under trusted local control throughout inspection/restore; this is not a hostile-filesystem race defense. Separately copy artifacts to the chosen protected independent storage and verify them after copying. The implementation's local drill proves actual logical reconstruction; it does not prove off-site durability or future recoverability.

## 4. Create a fresh isolated target and restore

The restore operator needs local CREATEDB and appropriate target privileges. It may be a separate role from the source backup role. The generated target starts with `fincore_restore_`; this is also enforced by the read-only verifier. Neither source naming nor a report grants authority to replace a database.

```powershell
$restoreCredential = Get-Credential -Message 'Local isolated-restore operator and original password'
$restoreDatabase = 'fincore_restore_' + [guid]::NewGuid().ToString('N')
if ($restoreDatabase -cnotmatch '^fincore_restore_[a-z0-9_]{1,40}$') { throw 'Unexpected target name.' }
$env:PGPASSWORD = $restoreCredential.GetNetworkCredential().Password
$env:PGCONNECT_TIMEOUT = '5'
Remove-Item Env:PGSERVICE, Env:PGHOSTADDR, Env:PGOPTIONS -ErrorAction SilentlyContinue
try {
    & "$pgBin\createdb.exe" --no-password --host=127.0.0.1 "--port=$sourcePort" `
        "--username=$($restoreCredential.UserName)" --template=template0 --encoding=UTF8 $restoreDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Target creation failed; do not reuse another database.' }
    & "$pgBin\pg_restore.exe" --no-password --exit-on-error --single-transaction --no-owner --no-privileges `
        --host=127.0.0.1 "--port=$sourcePort" "--username=$($restoreCredential.UserName)" `
        "--dbname=$restoreDatabase" $archivePath
    if ($LASTEXITCODE -ne 0) { throw 'Restore failed; retain evidence and inspect the isolated target.' }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
$env:FINCORE_RECOVERY_DB_URL = "jdbc:postgresql://127.0.0.1:$sourcePort/$restoreDatabase"
$env:FINCORE_RECOVERY_DB_USERNAME = $restoreCredential.UserName
$env:FINCORE_RECOVERY_DB_PASSWORD = $restoreCredential.GetNetworkCredential().Password
```

No `--clean`, `--create`, source overwrite or database DROP is used. Single-transaction restore with exit-on-error prevents a successful partial SQL restore from being mistaken for completion; the newly created database still exists if restore fails. There is no automatic retry that deletes/recreates it. Match source collation/encoding when provisioning elsewhere; differing database settings are refused by fingerprint comparison.

## 5. Record read-only verification evidence

```powershell
$reportPath = Join-Path $bundleDirectory ('restore-verification-' + [guid]::NewGuid().ToString('N') + '.json')
& java -jar $jar verify-recovery-restore "--directory=$bundleDirectory" `
    "--manifest-sha256=$trustedManifestHash" "--report=$reportPath"
if ($LASTEXITCODE -ne 0) { throw 'Restore verification refused; do not route traffic or repair evidence to pass.' }
Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 | ConvertFrom-Json
```

Success is `VERIFIED_ISOLATED_DATABASE_RESTORE_NO_DELETION_AUTHORITY`. The report binds a new evidence UUID to bundle, manifest/archive hashes, configured target endpoint hash (`restoredIdentitySha256`) and observed aggregate counts. The hash identifies the configured endpoint without exposing its address; it is not a server attestation. The report records `restoredDatabaseReadOnly=true`, `externalResourcesRecovered=false` and `deletionPermitted=false`. The verifier checks the archive again, requires a different source identity, opens a read-only Repeatable Read connection and compares all table counts/digests plus structural fingerprint. It writes a new report only after agreement; existing report paths are refused.

The JSON report is an unsigned local observation. Secure its provenance and retain the successful command result; no authenticated server receipt is stored. It establishes one observed supported-database reconstruction, not the safety of live traffic, remote provider state, complete historical provenance or deletion eligibility.

## 6. Inspect the recovered API with side effects disabled

Keep the original API/traffic separate. In a dedicated terminal, copy the intended **restored** connection settings above, then start the matching JAR on an isolated port:

```powershell
$env:DB_URL = $env:FINCORE_RECOVERY_DB_URL
$env:DB_USERNAME = $env:FINCORE_RECOVERY_DB_USERNAME
$env:DB_PASSWORD = $env:FINCORE_RECOVERY_DB_PASSWORD
if ($env:DB_URL -notmatch '/fincore_restore_[a-z0-9_]{1,40}$') { throw 'Inspection requires the isolated target.' }
java -jar $jar --server.port=18082 --spring.flyway.enabled=false --fincore.events.transport=local `
    --fincore.outbox.enabled=false --fincore.kafka.listener-auto-startup=false --spring.kafka.admin.auto-create=false `
    --fincore.provider.enabled=false --fincore.provider-webhook.enabled=false `
    --fincore.merchant-webhooks.enabled=false --fincore.merchant-webhooks.dispatch-enabled=false `
    --fincore.risk.enabled=false --fincore.settlement.enabled=false --fincore.observability.enabled=false --management.tracing.enabled=false
```

Use the [merchant access runbook](MERCHANT_ACCESS_RUNBOOK.md) against port18082 with the original merchant password. Check owned payment/accounting/journal reads and an **exact original creation retry**, preserving its key/body and genuine CSRF token/session. A creation replay returns its original saved snapshot even when the current payment has since been refunded. Check a different merchant remains refused. Avoid new financial commands and do not connect the clone to original external delivery/provider endpoints.

The automated native drill performs these genuine HTTP checks, verifies retained partial-capture fee totals, confirms a forbidden journal update still fails and compares the source before/after. Stop this inspection API normally, then rerun the verifier with a new report filename if confirming no row changes. An original successful verification remains a historical observation; later mutations do not update that file.

## 7. Operational closure, failures and retention

| Observation | Required response |
| --- | --- |
| Generic `DATABASE_RECOVERY_REFUSED`, exit2 | Inspect trusted local configuration/logs; public output intentionally omits sensitive diagnostics |
| No completed manifest | Treat the directory as partial capture; retain it for investigation |
| Hash/row/schema disagreement | Preserve failed evidence; investigate custody, source snapshot, schema/version and target changes |
| Dump/restore warning or error | Inspect it; do not replace source or disable guards to make verification pass |
| Source changes after capture | Expected: restore compares the exported snapshot, not today's live database |
| Existing directory/report | Choose a genuinely new evidence destination; never overwrite prior results |
| Successful database verification | Complete the external-resource review before any deliberate cutover |

For a capture refusal before any dump log exists, these read-only queries in a trusted local psql session help inspect version/scope. Do not remove schemas or disable guards merely to pass the command:

```sql
SELECT current_database(), current_setting('server_version'), current_setting('server_encoding');
SELECT nspname FROM pg_namespace
WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema') ORDER BY nspname;
SELECT version, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT c.relname, t.tgname, t.tgenabled FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
WHERE c.relnamespace='public'::regnamespace AND t.tgenabled<>'O';
```

The [retention policy](RETENTION_POLICY.md) currently retains authoritative payment/audit/evidence data and grants no deletion authority. M14/M15 preservation approval remains review-only. Retain keys/key IDs, provider instance/journal, Kafka configuration/offset context, application version and trusted backup references independently. Redis quotas are ephemeral and may replenish after loss; re-enabling them is a coordinated policy decision. This milestone does not restore those external resources or execute purges.

Stop only the isolated inspection API and any test infrastructure you started. Retain the generated target, bundle/report and logs according to the decision above. No cleanup command deletes a database or recursively removes an artifact tree. Remove temporary credential variables when done; the runbook does not change persistent user settings.

## 8. Verification commands

Point the existing native-test variables at a **disposable loopback test DB/cluster**, not an operational database. `FINCORE_TEST_PG_BIN` must name the trusted PostgreSQL bin directory. The native drill uses those connection credentials/port to create its own fresh source and generated restore databases, and retains them; its role needs local CREATEDB. This avoids older regression tests' intentionally retained upgrade schemas. Set `FINCORE_TEST_PROMETHEUS_BIN` if rerunning M23's real scrape checks.

```powershell
$testCredential = Get-Credential -Message 'Operator for the disposable PostgreSQL test cluster'
$env:FINCORE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/fincore_milestone24_check'
$env:FINCORE_TEST_DB_USERNAME = $testCredential.UserName
$env:FINCORE_TEST_DB_PASSWORD = $testCredential.GetNetworkCredential().Password
$env:FINCORE_TEST_PG_BIN = $pgBin
.\mvnw.cmd '-Dtest=DatabaseRecoveryCliTest,RecoverySchemaTest' '-Dit.test=LocalPostgresRecoveryIT,PackagedDatabaseRecoveryIT' verify
```

The example port/database is the implementation workspace's test configuration; use the actual running disposable cluster and role in your environment. Without the enabling test variables, native recovery cases skip rather than prove a restore. See the [summary](MILESTONE_24_SUMMARY.md) for final counts, real-tool evidence and environment limits. The local same-cluster drill is independent logical reconstruction into new databases, not a second-host disaster recovery or measured RPO/RTO guarantee.
