# Milestone 23 — Observability for payment and recovery flows

**Latest continuation (2026-10-27):** [Milestone 24 — logical database recovery](MILESTONE_24_LEARNING_GUIDE.md) adds consistent snapshot-backed archives, read-only isolated-restore verification and actual recovered API checks. Use the [runbook](DATABASE_RECOVERY_RUNBOOK.md), [retention decision](RETENTION_POLICY.md) and [summary](MILESTONE_24_SUMMARY.md). M25 deployment/final validation is the one remaining planned block. Older next-step notes below and Word volumes retain their historical scope.

Implemented 27 October 2026 in the independent `fincore-payments` learning backend. Continue from [Milestone 22](MILESTONE_22_LEARNING_GUIDE.md). Use the [operating runbook](OBSERVABILITY_RUNBOOK.md), [verification summary](MILESTONE_23_SUMMARY.md) and [roadmap](ROADMAP.md).

## 1. Why this block matters now

The platform already knows how to preserve payment identity, post balanced journals, retry uncertain provider commands and retain failure evidence. An operator still needs to notice when those mechanisms stop progressing. A correct outbox can accumulate undelivered events; a safely UNKNOWN provider operation can remain unresolved; immutable settlement reports can sit without investigation.

M23 makes that work visible through process metrics, sampled traces, read-only backlog snapshots and alert rules. It connects earlier milestones without adding a new financial workflow. PostgreSQL journals, commands and audit evidence remain the authority for what happened to money. Metrics can reset and traces can be dropped, so neither proves settlement or exactly-once execution.

There is **no new migration**. The schema remains V22. Observability defaults off and has its own optional credentials and local infrastructure.

## 2. Three complementary signals

| Signal | Question it answers | Implemented location | Important limit |
| --- | --- | --- | --- |
| Metrics | How often, how slow, how much outstanding work? | Micrometer counters/timers and cached gauges, scraped by Prometheus | Aggregates lose individual identities and reset on restart |
| Traces | Which observed calls participated in this request? | Micrometer Observation, OpenTelemetry and optional OTLP HTTP export | Sampled, bounded, synchronous scope; export may drop spans |
| Correlated logs | Which diagnostic message belongs to this trace? | Fixed flow/outcome debug messages inside the active observation scope | Existing application logging is not globally rewritten or redacted |

A counter increases for a completed observed call. A timer records its duration and outcome. A gauge represents a currently published observation, such as the last sampled pending count. A histogram places timings into fixed buckets so Prometheus can estimate percentiles across a time window.

`fincore_flow_calls_total{flow="payment_create",outcome="replay"}` measures returned creation retries. It is not another count of created payments. Likewise a risk ALLOW may reuse an existing admission, and a successful provider retry may newly apply an already recorded provider result. Interpret the outcome with its flow.

## 3. How a request becomes observable

```text
POST /api/v1/payments + merchant Basic/CSRF + Idempotency-Key
→ framework HTTP observation [method, route template, status]
→ PaymentController.createPayment
→ FlowObservationAspect.observe → FlowTelemetry.observe(payment_create)
→ MerchantPaymentService.createPayment
   → optional PaymentRiskTransactions.assess [risk_check]
      → RedisRiskVelocity.reserve [redis_reservation]
   → PaymentService.createPayment [payment_persist]
      → existing PostgreSQL payment/idempotency/outbox transaction commits
→ original PaymentResponse + X-Fincore-Trace-Id
→ process counters/timers; sampled spans optionally exported over local OTLP HTTP
```

The aspect selects explicit service/client methods rather than observing every repository call. Its fixed `Flow` enum covers creation/manual operations, amount operations, provider commands/HTTP/callbacks, risk, delivery, consumer application, recovery, settlement and routing mutations. Typed results map into fixed `Outcome` values; arguments and exception text never become dimensions.

Spring AOP ordering places this aspect outside ordinary transaction advice. A deferred commit failure therefore reaches the observation as a failure, rather than being counted as success before commit. A native PostgreSQL test registers a failing before-commit callback and checks rollback, error metrics and sanitized spans. With a caller-owned ambient transaction, final commit may occur later; these remain diagnostic call outcomes, not durable accounting receipts.

Nested timings overlap: `payment_create` includes work timed separately by `payment_persist` and possibly risk. Do not sum those durations. Creation service time excludes earlier HTTP authentication/validation. HTTP request timing covers that outer boundary.

## 4. Bounded dimensions and private data

Putting a payment UUID into a metric label creates another time series for every payment. At scale, that consumes memory, storage and query capacity. It also copies private identifiers into another system. This implementation uses fixed flow/outcome/component/state values and bounded HTTP method/status categories. HTTP routes use framework templates such as `/api/v1/payments/{paymentId}`, with a fixed fallback for unmatched routes and a 128-value URI meter cap.

`SafeServerObservationConvention` supplies no high-cardinality request attributes. Raw URLs, query strings, references, amounts, credentials, merchant identities and request bodies are not added by this instrumentation. Spring Security observations are omitted to avoid principal names in traces; authentication itself still executes. `SafeObservationErrorHandler` replaces observation errors with a stackless fixed failure before tracing handlers export them. It does not replace the actual business exception or change the HTTP error contract.

W3C `traceparent` supplies correlation. Propagation deliberately ignores `baggage` and `tracestate`, and `TraceCorrelationFilter` returns only a valid current trace ID in `X-Fincore-Trace-Id`. An incoming client can influence a valid trace ID. Treat it as a search key, never as authentication, ownership or trusted evidence.

These controls cover the new telemetry conventions. They do not promise that every pre-existing application log, third-party extension or future observation is automatically safe. New instrumented boundaries need the same review.

## 5. Keeping telemetry outside financial correctness

`FlowTelemetry.observe` executes the original work once and preserves its return value or thrown exception. Routine instrumentation failures during observation start, scope handling, result classification, completion or counter recording are contained. Classification failure records UNCLASSIFIED; it cannot turn a successful payment response into an error. A process gauge, `fincore_telemetry_failures`, makes these dropped diagnostics visible.

Trace export is separately opt-in. The local exporter uses HTTP on `127.0.0.1`, the exact `/v1/traces` path, a 500ms connection timeout and a two-second export timeout. OpenTelemetry's batch processor has a 256-span queue, 64-span batches and a one-second schedule. A missing collector can lose spans. It must not cause a payment retry or financial compensation. Default sampling is 0.1; the runbook uses 1.0 for a small local demonstration. Parent sampling decisions can influence tracing, so this setting is not a hard total resource ceiling.

No new asynchronous context is saved into outbox rows, Kafka messages or merchant-webhook envelopes. A synchronous provider HTTP call is timed within its caller's flow; separate background dispatch and consumer work starts its own observation. Durable cross-process asynchronous tracing remains future scope.

## 6. Backlog sampling instead of SQL on every scrape

```text
OperationalSnapshotCollector.refresh [dedicated daemon worker]
→ OperationalSnapshotRepository.snapshot
→ REQUIRES_NEW, read-only Repeatable Read; one aggregate query
→ OperationalSnapshot.publish [immutable map]
→ GET /internal/observability/prometheus reads registered gauges
```

Prometheus scrapes do not query financial source tables. The collector runs independently of the existing outbox/webhook scheduler, including when those dispatchers are disabled. Its default fixed delay is 15 seconds, configurable from 5 to 300 seconds. A statement timeout of two seconds and transaction timeout of five seconds limit database execution; they do not prove a physical scan/IO bound or eliminate pool/network delays.

| Component/state | Count and oldest-age origin |
| --- | --- |
| outbox pending/dead | Retained matching rows; event `occurred_at` |
| provider unknown/review | Matching operations; operation `created_at` |
| provider lease_expired | IN_FLIGHT with expired lease; `lease_until` |
| merchant_webhook pending/dead | Matching deliveries; delivery `created_at` |
| merchant_webhook lease_expired | Expired IN_FLIGHT deliveries; `lease_until` |
| consumer unresolved | Unresolved failure evidence, including quarantined evidence; `first_failed_at` |
| settlement unresolved_report | Discrepant reports without same-report resolution; report `observed_at` |

These origins are deliberately explicit. An old provider command newly entering review has an age measured from creation, not from its latest state change. Settlement counts reports, not unique payments. Consumer quarantine does not make unresolved evidence disappear.

`fincore_ops_snapshot_up` indicates whether the latest refresh succeeded. `available` indicates whether any successful sample exists. `age_seconds` measures monotonic time since that sample, or process startup before the first success. `interval_seconds` exposes the configured period. At startup, zero counts with `available=0` mean unknown. After failure, last counts/ages remain visible, `up=0`, and age continues increasing. Oldest ages themselves stay frozen until another successful refresh.

The collector publishes one immutable map coherently. Separate gauge callbacks during a scrape can straddle a publication; this is operational telemetry rather than a financial point-in-time report. These are also **global database facts repeated by every collecting API instance**. Use one designated collector or an appropriate maximum; summing replicas double-counts the same work.

## 7. A separate observation security boundary

Enabled observability accepts its own username/raw BCrypt verifier. Both may be empty, in which case all observation access is denied; a partially configured pair fails validation. The observer can GET exactly:

- `/internal/observability/prometheus`
- `/internal/observability/health`
- `/internal/observability/health/readiness`
- `/internal/observability/health/liveness`

The dedicated stateless security chain has an isolated authentication manager. Merchant and operator passwords do not authenticate here, and observer credentials do not grant merchant/operator operations. Discovery, configuration, environment, heap dump, log and mutation endpoints are unavailable. Responses hide health details/components. Only this read-only chain disables CSRF; merchant/operator POST protections stay in place.

Liveness checks application liveness state. Readiness checks readiness state and PostgreSQL. Aggregate health can also reflect enabled Redis: it may return 503 while readiness stays healthy. This lets existing payment recovery remain available during a risk dependency outage; eligible new creation still follows M22's fail-closed behavior.

Spring Boot 3.5's endpoint access model is used consistently. Combining legacy per-endpoint `enabled` configuration with `access` caused the initial context test to fail; the final configuration uses access plus an opt-in endpoint filter. [Spring Boot endpoint access](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).

## 8. Alerts: detect symptoms, retain judgment

The optional Prometheus configuration includes eleven alerts: failed scrape, unavailable/stale snapshots, old pending outbox, exhausted delivery retries, provider uncertainty, consumer gaps, unresolved settlement reports, repeated risk unavailability, payment creation error ratio and creation p95 latency.

Backlog rules require a successful sample fresher than three times its configured interval. Otherwise a stale count must not masquerade as current evidence. Snapshot-specific alerts expose the uncertainty instead. Error ratio requires at least ten creation calls in five minutes; latency requires twenty observations. Thresholds and `for` durations are demonstration policy, not measured production SLOs. Histogram p95 is an estimate from fixed buckets and requires representative traffic.

Seven promtool temporal scenarios test delayed firing, stale/unavailable/missing-snapshot suppression, low traffic and a slower configured interval. They do not exhaust every threshold/label combination. Rules link directly to the [triage runbook](OBSERVABILITY_RUNBOOK.md#5-alert-triage). There is no Alertmanager/contact routing and no automatic requeue, provider replay, financial repair or discrepancy closure.

## 9. Choices, alternatives and costs

| Chosen approach | Alternative considered | Why it fits here / cost accepted |
| --- | --- | --- |
| Micrometer Observation with Boot-managed OpenTelemetry bridge | Hand-written counters/spans in every service; vendor-specific agents | One explicit flow wrapper supplies timing/correlation; AOP order and proxy boundaries need tests |
| Fixed dimensions and sanitized failures | Raw IDs, URLs and exception stacks in telemetry | Predictable cardinality and less copied private data; investigate individual records through existing owned tools |
| Background read-only snapshots | SQL during scrape; event-maintained extra counter tables | Scrape cost stays small and no new financial writes; samples lag and aggregate queries still need capacity review |
| Separate observer identity | Reuse privileged operator or merchant credentials | Smaller read-only capability and isolated authentication; another secret must be provisioned |
| Local bounded batch export | Export synchronously inside a financial call; unbounded queue | Isolates routine exporter outages; sampled spans can disappear and abrupt shutdown can lose data |
| Explicit alerts with human triage | Automatic repair on a metric threshold | Preserves prior evidence/recovery contracts; humans must investigate and use authorized commands |
| Separate Compose overlay | Require monitoring secrets for every default Compose invocation | Existing setup stays usable; enabling monitoring requires an additional file and secret setup |

These alternatives are not universally worse. For larger deployments, a collector tier, richer storage, vendor tooling or event-maintained aggregates may be justified by measured workload and operating needs. The selected design keeps this milestone inspectable without changing payment semantics.

## 10. Worked failures and holistic impact

**The event sink stops:** outbox pending count and source age increase while the payment creation transaction remains durable. The stalled alert points to M6 recovery instructions. Restoring delivery or an audited requeue uses those commands; changing a gauge cannot fix the source.

**Provider response is lost:** the command remains UNKNOWN, while creation/request metrics alone might look successful. The provider backlog surfaces the retained uncertainty. M17 exact retry or M18 verified callbacks provide the actual completion path; M21 supplies independent comparison afterward.

**The source query fails:** cached values remain, snapshot up becomes zero, and backlog rules stop trusting them. A working metrics HTTP response therefore does not prove current DB evidence. Repair the observation path and wait for freshness before assessing the backlog.

**Redis is unavailable:** M22 records UNAVAILABLE and refuses eligible new creation. M23 shows risk and creation outcomes separately, while DB readiness allows existing recovery paths. This is visibility into the established contract, not automatic bypass.

**The trace collector stops:** export may time out/drop spans; ordinary payment work continues. Audit rows remain the durable evidence. A missing trace cannot establish that a payment never ran.

Across M1–23, the platform now combines safe financial state, explicit delivery/recovery evidence and operational detection. The next missing layer is proving that retained authoritative data can actually be restored independently.

## 11. Verification, remaining scope and next milestone

One Java21 Maven clean verify completed at **2026-10-27T18:54:53+05:30**: **1,216 executed passes**, zero failures/errors; **454 Docker-dependent cases skipped**. This includes 731 unit/MVC/configuration/CLI, 473 native PostgreSQL and 12 packaged CLI/sandbox checks. M23 adds 20 unit and 17 native PostgreSQL/HTTP cases; focused reruns are not added again.

Native checks exercised real observer Basic auth, genuine merchant CSRF, identity separation, trace correlation/privacy, committed creation/replay, rollback on commit failure, read-only snapshot collection, frozen failed snapshots and the risk-enabled outage matrix. A real portable Prometheus scraped the random-port application, loaded the production rules and returned `up=1`. A real local OTLP HTTP receiver accepted a protobuf span export. Promtool checked eleven rules and passed seven temporal scenarios separately from JUnit counts.

Actual environment: Windows, Java21, PostgreSQL18.6, Prometheus3.5.0; Flyway warns that PG18 is newer than its tested support range. V1–22 checksums match the prior milestone database. Docker/PG17.6, Jaeger UI/Compose execution, real Redis, native Kafka broker delivery this session, Linux, representative load and independent restore remain unverified. The risk-enabled native suite mocks the Redis gateway, not PostgreSQL. Static runbook checks do not imply its interactive demo was executed.

**Next: M24 — backup/restore verification and operational closure.** Establish explicit recovery scope, trusted backup/preservation evidence, an isolated restore and integrity checks, then document retention decisions. M25 remains reproducible deployment and final sandbox/failure/load/security validation. Two planned blocks remain; milestones completed are not a production-readiness percentage.

## 12. Source reading order and references

1. `config/ObservabilityProperties.java`, `ObservabilitySecurityConfiguration.java` — opt-in settings and observer capability.
2. `config/ObservabilityConfiguration.java` — meters, observations, exporter, propagation and lifecycle wiring.
3. `observability/FlowObservationAspect.java`, `FlowTelemetry.java`, `Flow.java` — explicit boundaries and classified diagnostics.
4. `SafeServerObservationConvention.java`, `SafeObservationErrorHandler.java`, `TraceCorrelationFilter.java` — bounded data and correlation.
5. `repository/OperationalSnapshotRepository.java`, `OperationalSnapshotCollector.java`, `OperationalSnapshot.java` — sampled global source facts.
6. `observability/alerts.yml`, `alerts.test.yml`, `prometheus.yml` and `docker-compose.observability.yml` — local monitoring and alert policy.
7. `integration/ObservabilityDatabaseTests.java`, `LocalPostgresRiskObservabilityIT.java`, observability unit tests — evidence and failure boundaries.

Primary references: [Spring Boot tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html), [Boot configuration properties](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html), [Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/) and [Jaeger 2.22 getting started](https://www.jaegertracing.io/docs/2.22/getting-started/). Project behavior is established by the source/tests above; upstream defaults are overridden where specified here.

## TOPICS TO LEARN FROM THIS SESSION

- Metrics, counters, timers, gauges and histogram percentiles
- Metric cardinality and sensitive-data boundaries
- Micrometer Observation and OpenTelemetry
- W3C trace context, sampling and log correlation
- Spring AOP ordering and transaction completion
- Separate Spring Security authentication managers
- Read-only Repeatable Read snapshots and freshness
- Scheduled executor lifecycle and failure isolation
- Prometheus scraping, PromQL, alert timing and promtool
- OTLP batch export and bounded queues
