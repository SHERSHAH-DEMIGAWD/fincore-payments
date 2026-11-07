# Milestone 23 — Payment observability

Implemented 2026-10-27. [Learning guide](MILESTONE_23_LEARNING_GUIDE.md) · [Runbook](OBSERVABILITY_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Opt-in private Prometheus and health endpoints with a separate read-only observer identity.
- Fixed-category call counters, timers and histogram buckets across payment, risk, provider, delivery and recovery flows.
- Sampled nested traces, safe trace-ID response correlation and optional local OTLP HTTP export.
- Background read-only backlog counts/ages with explicit unavailable/stale-snapshot signals.
- Eleven Prometheus alerts with freshness/traffic gates, temporal rule tests and linked triage instructions.
- Optional Prometheus/Jaeger Compose overlay and file-based scrape credentials.

## 2. Concepts / technologies used

- Spring Boot Actuator / Spring Security — private management access and health scopes.
- Micrometer Observation / Spring AOP — bounded flow outcomes and transaction-aware timing.
- OpenTelemetry / W3C trace context / OTLP — sampling, correlation and bounded asynchronous export.
- PostgreSQL Repeatable Read / scheduled executor — independent read-only source snapshots.
- Prometheus / PromQL / histograms / promtool — scraping, percentiles and timed alerts.
- JUnit / Mockito / native HTTP/PostgreSQL — failure, privacy and identity-boundary verification.

## 3. Important files

- `ObservabilityProperties.java`, `ObservabilityConfiguration.java` — opt-in settings, exporter and telemetry lifecycle.
- `ObservabilitySecurityConfiguration.java` — isolated observer authentication and four GET routes.
- `FlowObservationAspect.java`, `FlowTelemetry.java` — explicit flow boundaries, outcomes and safe instrumentation.
- `SafeServerObservationConvention.java`, `SafeObservationErrorHandler.java`, `TraceCorrelationFilter.java` — bounded HTTP data, sanitized trace errors and trace IDs.
- `OperationalSnapshotRepository.java`, `OperationalSnapshotCollector.java`, `OperationalSnapshot.java` — sampled global backlogs and freshness.
- `application.yml`, `EventOperationsCli.java` — access/export defaults and headless disabling.
- `observability/`, `docker-compose.observability.yml` — scrape/rule configuration, temporal tests and local services.
- Observability unit/integration tests, guide and runbook — verification, concepts and operational handoff.

## 4. Request flow

```text
POST /api/v1/payments → PaymentController.createPayment
→ FlowObservationAspect.observe → FlowTelemetry.observe
→ MerchantPaymentService.createPayment → PaymentService.createPayment
→ PostgreSQL payment/idempotency/outbox commit → PaymentResponse
→ Prometheus counters/timers + sampled optional OTLP spans
```

## 5. Important design decisions

- Observability defaults off; observer, merchant and operator authentication stay separate.
- Fixed dimensions, sanitized errors and traceparent-only propagation limit copied private data.
- Observation advice surrounds ordinary transaction completion; metrics still represent calls, not financial authority.
- Scrapes read cached gauges; a dedicated worker performs read-only source sampling.
- Failed samples retain prior values and expose freshness; global counts must not be summed across replicas.
- Export queues/timeouts are bounded; ordinary telemetry failure preserves business return/exception behavior.
- Alerts guide existing authorized recovery; no automated financial mutation is introduced.
- Schema remains V22; no new migration or event-envelope change.

## 6. Test status

One Java21 Maven **clean verify** finished **BUILD SUCCESS** at **2026-10-27T18:54:53+05:30**, in 16:41 min: **1,216 executed passes**, zero failures/errors. Focused reruns are not added.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / configuration / CLI | 731 | 0 |
| Real PostgreSQL | 473 | 0 |
| Packaged CLI / sandbox | 12 | 0 |
| Docker-dependent | 0 | 454 |

M23 adds 20 unit and 17 native PostgreSQL/HTTP checks. Coverage includes dedicated observer credentials, genuine merchant CSRF, forbidden routes/writes, trace privacy/correlation, original/replayed creation, before-commit failure/rollback, read-only source sampling, stale-value retention, independent collector lifecycle and the enabled-risk outage/health matrix. Packaged headless commands force observability/tracing off despite invalid enabled inherited settings.

A real portable Prometheus scraped the actual random-port API with credential files, loaded the production rule file and reported `up=1`. The check runs in both native observability configurations. A real local HTTP receiver accepted an OTLP protobuf span. **Separate promtool validation passed eleven rules and seven temporal scenarios**; those are not included in JUnit totals. V1–22 migration checksums match the prior M22 database; the final packaged JAR covers application source/resources and excludes test-only LuaJ/OpenTelemetry testing libraries.

Initial focused runs exposed mixed legacy/new Actuator endpoint settings, a 401 rewritten by servlet error dispatch, a Prometheus startup connection race and an incorrect aggregate-health expectation when Redis is unavailable. Configuration/entrypoint/wait/health assertions were corrected before the successful broad run. The independent final QA validates links/fences, PowerShell syntax, packaged source coverage and result aggregation. Interactive Docker runbook execution is unverified.

Actual environment: Windows / Java21 / PostgreSQL18.6 / Prometheus3.5.0. Flyway warns about PG18 being newer than its tested support range. **Not run:** Docker/PG17.6, Jaeger UI/Compose services, real Redis, native Kafka broker delivery this session, Linux, representative load and independent restore. Risk-enabled native checks mock the Redis gateway. No live funds/provider or notification-routing claim is made.

Ignored verification artifacts live under workspace `.tools`: `milestone23-verify.log`, promtool logs, migration checksum files, QA/cleanup JSON and PostgreSQL log. Native Prometheus evidence remains under ignored `target/observability-prometheus-*`. Test data/logs are retained; the workspace-owned PostgreSQL instance is stopped after checks. No unrelated project or port5432 is changed; no commit/push.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–23 functionality, including private metrics/traces, sampled backlog visibility and alert policy.

**CURRENTLY POSSIBLE:** ✓ Inspect observed call outcomes/latency, correlate sampled requests, scrape authenticated metrics and triage retained operational work through existing recovery tools.

**NOT IMPLEMENTED YET:** ✗ M24 independent restore/retention closure; M25 deployment/final validation; durable asynchronous trace propagation, alert notifications and production monitoring storage are additional scope. Earlier real Redis verification remains outstanding.

**NEXT LOGICAL MILESTONE:** M24 — backup/restore verification and operational closure. Two planned blocks remain through M25; this is not a readiness percentage.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Micrometer Observation and OpenTelemetry
- Counters, timers, gauges and histogram percentiles
- Cardinality and telemetry privacy
- Trace context, sampling and log correlation
- Spring AOP and transaction completion
- Isolated Spring Security authentication managers
- Read-only snapshots and freshness
- PromQL alerts and promtool
- Bounded export queues and scheduler lifecycle
