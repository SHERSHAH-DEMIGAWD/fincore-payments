# Final project validation — Milestone25

Date:28 October 2026, Asia/Kolkata. Scope: the independent `fincore-payments` learning backend, roadmap1–25. No live funds, external deployment, commit or push. [Runbook](DEPLOYMENT_RUNBOOK.md) · [Project map](FINAL_PROJECT_GUIDE.md) · [Learning guide](MILESTONE_25_LEARNING_GUIDE.md) · [Summary](MILESTONE_25_SUMMARY.md).

## Verification record

Fresh clean verify completed **2026-10-28T19:19:09+05:30** in **19:27 min**. **1,269 executed passes**, zero failures/errors, **454 Docker-dependent skips**.

| Group | Passed | Skipped |
| --- | ---: | ---: |
| Unit/MVC/configuration/CLI | 761 | 0 |
| Native PostgreSQL | 483 | 0 |
| Real native Kafka broker | 7 | 0 |
| Packaged CLI/tools | 18 | 0 |
| Docker-dependent integration | 0 | 454 |

The completed release is [fincore-local-release.zip](../release-artifacts/milestone25-final/fincore-local-release.zip). Its manifest and checksums are inside the ZIP. A second **clean package with tests skipped**, using the same source, Windows JDK21/Maven toolchain and cached dependencies, produced the identical JAR. Fresh first-run reports/native evidence were copied privately before that second clean.

- Verified JAR SHA-256: `9f52c756c5a6b3d44efffc0a9c6a2f14397dbd3613e0d2fabee79120162227d7`.
- Release ZIP SHA-256: `ce868e2aeee2247e7e41003258029df41d47197bce9e0c44f73714f310fbdbbb`.
- Evidence scope: matching bytes and the recorded local test run; no signature, external attestation or production certification.

Final native smoke evidence at `2026-10-28T13:33:30.072762500Z`:24 same-intent and24 distinct requests,4 workers,36 verified notification receipts. Distinct creation workflow p50 **281.285ms**, p95 **320.084ms**, max **367.412ms**; the whole load stage including both phases and delivery waits took **10.333s**. These are observations from one bounded local smoke run, not production capacity. Risk disabled is explicit; real Kafka projection validated is true.


The full release script runs Java21 `clean verify`, including selected native PostgreSQL/Kafka, packaged CLI and available Docker integration suites. Counts above come from fresh JUnit XML belonging to that run; focused attempts are not added again. Standalone shell/verifier/configuration checks are reported separately.

The first broad run was interrupted before completion on28 October; its packaged acceptance passed, but no release was completed. Its reports/logs are retained privately and excluded from final totals. Resumption restarts the retained native test services and uses a fresh disposable shared database and a new release output directory. PostgreSQL performs its normal crash recovery before accepting new work; source data is neither deleted nor replaced to accelerate that recovery.

## Environment and provenance

- Windows host; Java21.0.12.1 portable JDK; Maven3.9.11 with cached dependencies.
- PostgreSQL18.6, dedicated workspace cluster `127.0.0.1:55432`, disposable M25 database and fresh per-drill databases. PostgreSQL18 emits Flyway's newer-than-tested-range warning; this is not verification of the pinned PostgreSQL17.6 container.
- Real native Kafka3.9.1 broker on19092/controller19093 with fresh M25 KRaft data; the application uses its existing Kafka3.9.2 client. Neither dependency version is changed by M25.
- Native provider/API/receiver child processes execute the packaged JAR. Prometheus uses the existing portable3.5.0 binary.
- No Docker daemon, Linux/WSL environment or real Redis runtime available. Official standalone Compose2.39.4, verified against its release checksum, supplies configuration validation only.
- Fresh shared fixtures disable autovacuum only on their disposable M25 public tables to avoid automatic maintenance colliding with NOWAIT concurrency tests. Financial/runtime constraints and assertions remain enabled. Per-drill source/restore databases remain isolated.
- No Git HEAD is available in this uncommitted workspace; the manifest records null rather than inventing a revision. Private `.tools` holds tool provenance, test logs, QA and cleanup evidence.

## Packaged acceptance observations

The real native acceptance requires ten fixed checks: authentication/CSRF/tenant separation; creation retry/conflict; lost-reply signed callback recovery; full refund journals; signed settlement match; partial captures/retained fees; signed notification retry; concurrent creation; private telemetry; and outbox delivery. It additionally waits for real Kafka REFUNDED projections. `riskValidated=false` is intentional for this host; `kafkaProjectionValidated=true` is required when the broker is selected.

The default smoke workload uses24 same-intent requests,24 distinct creations and4 workers. It verifies28 total payment rows,3 provider operations,8 ledger headers/16 entries,1 settlement report and1 manual delivery retry. Signed receiver evidence records at least36 receipts. Partial captures40+60 and fees2+3, followed by principal refunds, retain fee5 and payable-5. One same-key creation succeeds logically; distinct requests produce unique payment IDs. Exact report rerun refuses overwrite before further writes. Altered bootstrap passwords refuse account adoption/rotation. Bad merchant credentials refuse before financial work.

Smoke percentiles include fresh CSRF work; they do not measure sustained capacity, saturation, live-provider latency or a service-level objective. `productionCapacityValidated=false` remains explicit.

## Other validation boundaries

- Shell syntax and three Git Bash cases exercise literal command-substitution-looking secret input, conflicting plain/file values and missing files. This is native Windows Git Bash, not Linux-container execution.
- Six controlled verifier cases establish valid evidence acceptance and refusal of tampering, path traversal, missing/duplicate entries and false production certification. The actual release is checked separately; fixtures are not counted as Maven tests.
- Compose2.39.4 parses the acceptance profile with the real JAR hash. Four runtime images are digest-pinned; Kafka uses its official image-owned data directory. Parsing does not build or run any image.
- Repository-root CI workflow uses pinned official action commits and uploads only the ZIP created before secret initialization plus the bounded acceptance JSON. The workflow is implemented locally and has not executed or been published here.
- Checksum-verified official actionlint1.7.7 validates the workflow syntax/contexts locally. This does not execute GitHub Actions or the Docker job.
- The release ZIP allowlist excludes credentials, raw test logs, source/test/tool trees, runtime journals and historical Word artifacts. Hashes establish agreement with the supplied reference, not independent authenticity.
- V1–V22 Flyway checksums are compared with M24. No M25 migration is introduced. Source/resource coverage and absence of test-only nested libraries are inspected in the tested JAR.

## Unverified or additional scope

Real Redis Lua/combined containers, actual Docker build/startup, Linux permissions, PostgreSQL17.6 container behavior and Jaeger UI remain unexecuted on this host. Docker-enabled CI/acceptance is the next validation action. Multi-broker/HA, mixed-version rollout, long soak/representative load, TLS/managed secrets, live vendors/payouts and regulatory readiness are outside this proof.

M24 checks still cover supported same-host logical reconstruction with trusted manifest fingerprints and recovered API behavior. They do not reconstruct provider/Kafka/receiver state, external keys/grants, off-site durability or WAL/PITR. Retention keeps authoritative data; no deletion or automatic cutover is implemented. The two projects and historical Word volumes remain separate/unchanged.

## Cleanup and retained evidence

Checked `2026-10-28T19:28:00.9707390+05:30`: the workspace PostgreSQL cluster on55432 completed fast clean shutdown; its PID file is absent and pg_isready returns2. The verified native Kafka PID28736 was stopped through Windows process termination, with its data retained; this is not a claim of graceful Kafka shutdown. No listeners remain on55432/19092/19093, and no workspace Java, dump/restore or Prometheus child remains. Private native evidence includes four39-table restore verification reports and the passing acceptance report. Final document links/fences, source/resource coverage, release file hashes and migration agreement are checked in `.tools/milestone25-qa.json`.

Only session-owned broker/cluster/processes are stopped. Databases, native Kafka data, provider/receiver journals, test logs, release artifacts and failure evidence are retained privately. No unrelated PostgreSQL service on5432 or `ledger-mvp` is modified.
