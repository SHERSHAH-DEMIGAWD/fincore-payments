package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.service.OutboxDispatcher;
import com.fincore.payments.service.OutboxFailureRecorder;
import com.fincore.payments.service.PaymentService;
import com.fincore.payments.service.PaymentEventConsumer;
import com.fincore.payments.domain.PaymentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the shipped JAR entry point, including its forced headless/no-dispatch settings. */
@SpringBootTest(properties = {"fincore.events.transport=local", "fincore.outbox.enabled=false"})
@EnabledIfEnvironmentVariable(named = "FINCORE_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class LocalEventOperationsCliIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentService payments;
    @Autowired OutboxFailureRecorder failures;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired com.fincore.payments.service.ConsumerFailureService consumerFailures;
    @Autowired PaymentEventConsumer consumer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("FINCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD", ""));
    }

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs,
                    consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events, local_payment_event_inbox,
                    payment_outbox, ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void packagedOverviewDoesNotStartDispatchOrConnectToTheUnreachableBroker() throws Exception {
        createEvent();

        JsonNode result = invoke("overview");

        assertThat(result.path("pending").asLong()).isEqualTo(1);
        assertThat(result.path("ready").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_payment_event_inbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_event_positions", Long.class)).isZero();
    }

    @Test
    void packagedRecoveryCanBeReplayedAfterDeliveryWithoutChangingHistory() throws Exception {
        UUID eventId = createEvent();
        for (int attempt = 0; attempt < 5; attempt++) assertThat(failures.recordFailure(eventId, attempt)).isTrue();
        UUID requestId = UUID.randomUUID();
        String[] command = {"requeue", "--event-id=" + eventId, "--request-id=" + requestId,
                "--expected-generation=0", "--operator=cli-integration-test", "--reason=broker-restored"};

        JsonNode accepted = invoke(command);
        assertThat(accepted.path("replayed").asBoolean()).isFalse();
        assertThat(accepted.path("generation").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM payment_outbox", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM payment_outbox", Integer.class)).isZero();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        JsonNode replay = invoke(command);

        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("requeuedAt")).isEqualTo(accepted.path("requeuedAt"));
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM payment_outbox", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_requeue_audit", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(1);
    }

    @Test
    void packagedConsumerQuarantineAndOriginalReplayPersistAuditAndCloseOnlyTheRecoveredGap() throws Exception {
        UUID eventId = createEvent();
        var row = jdbc.queryForMap("SELECT payment_id, payload FROM payment_outbox WHERE event_id = ?", eventId);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("payment-events.v1", 0, 0,
                row.get("payment_id").toString(), row.get("payload").toString());
        consumerFailures.failed(record, new com.fincore.payments.exception.InvalidPaymentEventException("older validator"));
        UUID failureId = jdbc.queryForObject("SELECT failure_id FROM consumer_event_failures", UUID.class);
        String fingerprint = com.fincore.payments.repository.ConsumerFailureRepository.fingerprint(record.key(), record.value());
        String[] quarantine = {"quarantine", "--failure-id=" + failureId, "--expected-fingerprint=" + fingerprint,
                "--request-id=" + UUID.randomUUID(), "--operator=cli-test", "--reason=reviewed-original",
                "--accept-projection-gap=true"};

        assertThat(invoke(quarantine).path("action").asText()).isEqualTo("QUARANTINE");
        assertThat(invoke("gaps").path("status").asText()).isEqualTo("DEGRADED");
        String[] replay = {"replay", "--failure-id=" + failureId, "--expected-fingerprint=" + fingerprint,
                "--request-id=" + UUID.randomUUID(), "--operator=cli-test", "--reason=compatible-fix-deployed"};
        var replayed = invoke(replay);
        assertThat(replayed.path("action").asText()).isEqualTo("REPLAY");
        assertThat(invoke(replay).path("replayed").asBoolean()).isTrue();
        assertThat(invoke("gaps").path("status").asText()).isEqualTo("NO_KNOWN_GAPS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_recovery_audit", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("PENDING");
    }

    @Test
    void packagedReconciliationIsReadOnlyAndDoesNotRequireABroker() throws Exception {
        UUID eventId = createEvent();
        var before = jdbc.queryForMap("SELECT * FROM payment_outbox WHERE event_id = ?", eventId);

        var report = invoke("reconcile", "--limit=1");

        assertThat(report.path("historicalCompleteness").asText()).isEqualTo("NOT_CERTIFIED");
        assertThat(report.path("totalCandidates").asLong()).isEqualTo(1);
        assertThat(report.path("hasMore").asBoolean()).isFalse();
        assertThat(report.path("payments").get(0).path("latestSourceReplayCandidate").asBoolean()).isTrue();
        assertThat(report.path("payments").get(0).path("missingReceipts").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_outbox WHERE event_id = ?", eventId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_event_positions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_recovery_audit", Long.class)).isZero();
    }

    @Test
    void packagedShadowRebuildUsesIsolatedIdentityAndKeepsLiveDataUnchanged() throws Exception {
        UUID eventId = createEvent();
        String payload = jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE event_id = ?", String.class, eventId);
        consumer.consume(mapper.readValue(payload, PaymentEvent.class));
        var liveProjection = jdbc.queryForMap("SELECT * FROM payment_event_projections WHERE consumer_name = ?",
                PaymentEventConsumer.CONSUMER_NAME);
        var liveReceipt = jdbc.queryForMap("SELECT * FROM consumed_payment_events WHERE consumer_name = ?",
                PaymentEventConsumer.CONSUMER_NAME);
        var sourceEvent = jdbc.queryForMap("SELECT * FROM payment_outbox WHERE event_id = ?", eventId);
        var payment = jdbc.queryForMap("SELECT * FROM payments");
        UUID runId = UUID.randomUUID();
        String[] creation = {"rebuild-create", "--run-id=" + runId,
                "--operator=cli-test", "--reason=reviewed current source snapshot"};

        var planned = invoke(creation);
        var replayed = invoke(creation);
        var stepped = invoke("rebuild-step", "--run-id=" + runId, "--limit=1");
        var validation = invoke("rebuild-validate", "--run-id=" + runId);

        String shadow = "shadow-" + runId;
        assertThat(planned.path("runId").asText()).isEqualTo(runId.toString());
        assertThat(planned.path("consumerName").asText()).isEqualTo(shadow);
        assertThat(planned.path("replayed").asBoolean()).isFalse();
        assertThat(replayed.path("replayed").asBoolean()).isTrue();
        assertThat(replayed.path("plannedAt")).isEqualTo(planned.path("plannedAt"));
        assertThat(stepped.path("appliedThisStep").asLong()).isEqualTo(1);
        assertThat(stepped.path("run").path("pendingItems").asLong()).isZero();
        assertThat(validation.path("status").asText()).isEqualTo("MATCHES_CURRENT_SOURCE");
        assertThat(validation.path("historicalCompleteness").asText()).isEqualTo("NOT_CERTIFIED");
        assertThat(validation.path("promotion").asText()).isEqualTo("REQUIRES_AUTHENTICATED_PROMOTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE consumer_name = ?", Long.class, shadow))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE consumer_name = ?", Long.class, shadow))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections WHERE consumer_name = ?",
                PaymentEventConsumer.CONSUMER_NAME)).isEqualTo(liveProjection);
        assertThat(jdbc.queryForMap("SELECT * FROM consumed_payment_events WHERE consumer_name = ?",
                PaymentEventConsumer.CONSUMER_NAME)).isEqualTo(liveReceipt);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_outbox WHERE event_id = ?", eventId)).isEqualTo(sourceEvent);
        assertThat(jdbc.queryForMap("SELECT * FROM payments")).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_event_positions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_recovery_audit", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isZero();
    }

    private UUID createEvent() {
        var payment = payments.createPayment("cli-" + UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-CLI-REFERENCE", new BigDecimal("10.00"), "INR")).payment();
        return jdbc.queryForObject("SELECT event_id FROM payment_outbox WHERE payment_id = ?", UUID.class, payment.id());
    }

    private JsonNode invoke(String... arguments) throws Exception {
        Path jar = Path.of("target", "fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(jar).isRegularFile();
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dloader.main=com.fincore.payments.operations.EventOperationsCli", "-cp", jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
        command.addAll(List.of(arguments));
        Path stdout = Files.createTempFile(Path.of("target"), "operations-cli-", ".out");
        Path stderr = Files.createTempFile(Path.of("target"), "operations-cli-", ".err");
        var builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        var environment = builder.environment();
        environment.put("DB_URL", System.getenv("FINCORE_TEST_DB_URL"));
        environment.put("DB_USERNAME", System.getenv("FINCORE_TEST_DB_USERNAME"));
        environment.put("DB_PASSWORD", System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD", ""));
        // The CLI must override these inherited API settings before any infrastructure starts.
        environment.put("FINCORE_OUTBOX_ENABLED", "true");
        environment.put("FINCORE_PROVIDER_ENABLED", "true");
        environment.put("FINCORE_PROVIDER_API_KEY", "invalid");
        environment.put("FINCORE_PROVIDER_WEBHOOK_ENABLED", "true");
        environment.put("FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY", "invalid");
        environment.put("FINCORE_MERCHANT_WEBHOOKS_ENABLED", "true");
        environment.put("FINCORE_MERCHANT_WEBHOOK_DISPATCH_ENABLED", "true");
        environment.put("FINCORE_MERCHANT_WEBHOOK_MASTER_KEY", "invalid");
        environment.put("FINCORE_SETTLEMENT_ENABLED", "true");
        environment.put("FINCORE_SETTLEMENT_SIGNING_KEY", "invalid");
        environment.put("FINCORE_RISK_ENABLED", "true");
        environment.put("FINCORE_RISK_MAX_ADMISSIONS", "-1");
        environment.put("FINCORE_OBSERVABILITY_ENABLED", "true");
        environment.put("FINCORE_OBSERVABILITY_USERNAME", "invalid:observer");
        environment.put("FINCORE_OBSERVABILITY_PASSWORD_HASH", "invalid");
        environment.put("FINCORE_TRACE_EXPORT_ENABLED", "true");
        environment.put("FINCORE_TRACE_ENDPOINT", "http://unreachable.invalid:4318/v1/traces");
        environment.put("FINCORE_EVENT_TRANSPORT", "kafka");
        environment.put("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:1");
        var process = builder.start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("Operator command must finish without a broker").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(stderr)).isZero();
            String json = Files.readString(stdout);
            assertThat(json).doesNotContain("PRIVATE-CLI-REFERENCE", "Spring Boot", "Tomcat");
            return mapper.readTree(json);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
