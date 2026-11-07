package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.repository.PaymentRepository;
import com.fincore.payments.service.PaymentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared assertions exercise actual PostgreSQL constraints in both container and opt-in local runs. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class LedgerDatabaseTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentRepository payments;
    @Autowired PaymentService service;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @BeforeEach
    void resetDisposableDatabase() {
        // TRUNCATE is administrative test cleanup; ordinary row UPDATE/DELETE must remain forbidden.
        jdbc.execute("TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit, payment_event_projections, consumed_payment_events, local_payment_event_inbox, payment_outbox, ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments");
    }

    @Test
    void creationAuthorizationAndFailureDoNotPostMoney() throws Exception {
        UUID id = create("no-ledger", "10.00", "INR");
        mvc.perform(get("/api/v1/payments/{id}/ledger", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        action(id, "authorize", 200);
        assertCounts(0, 0);
        action(id, "fail", 200);
        assertCounts(0, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_accounts", Long.class)).isZero();
    }

    @Test
    void capturePostsBalancedEntriesAndDerivedBalances() throws Exception {
        UUID id = authorized("capture", "1499.99", "INR");
        action(id, "capture", 200);
        assertCounts(1, 2);

        JsonNode journals = ledger(id);
        assertThat(journals.size()).isEqualTo(1);
        JsonNode capture = journals.get(0);
        assertThat(capture.get("paymentId").asText()).isEqualTo(id.toString());
        assertThat(capture.get("operation").asText()).isEqualTo("CAPTURE");
        assertThat(capture.get("source").asText()).isEqualTo("LIVE");
        assertThat(capture.get("currency").asText()).isEqualTo("INR");
        assertThat(capture.get("amount").decimalValue()).isEqualByComparingTo("1499.99");
        assertThat(capture.path("reversesTransactionId").isNull()
                || capture.path("reversesTransactionId").isMissingNode()).isTrue();
        assertThat(capture.get("createdAt").asText()).isNotBlank();
        assertEntry(capture, "PROCESSOR_CLEARING", "DEBIT", "1499.99", "INR");
        assertEntry(capture, "MERCHANT_PAYABLE", "CREDIT", "1499.99", "INR");
        assertBalance("INR", "PROCESSOR_CLEARING", "DEBIT", "1499.99", "0", "1499.99");
        assertBalance("INR", "MERCHANT_PAYABLE", "CREDIT", "0", "1499.99", "1499.99");
        assertThat(jdbc.queryForObject("""
                SELECT sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) FROM ledger_entries
                """, BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void refundAppendsExactReversalAndLeavesOriginalCaptureUnchanged() throws Exception {
        UUID id = captured("refund", "99.95", "INR");
        JsonNode originalCapture = ledger(id).get(0);
        action(id, "refund", 200);
        assertCounts(2, 4);

        JsonNode journals = ledger(id);
        JsonNode capture = operation(journals, "CAPTURE");
        JsonNode refund = operation(journals, "REFUND");
        assertThat(capture).isEqualTo(originalCapture);
        assertThat(refund.get("reversesTransactionId").asText()).isEqualTo(capture.get("id").asText());
        assertThat(refund.get("amount").decimalValue()).isEqualByComparingTo("99.95");
        assertEntry(refund, "PROCESSOR_CLEARING", "CREDIT", "99.95", "INR");
        assertEntry(refund, "MERCHANT_PAYABLE", "DEBIT", "99.95", "INR");
        assertBalance("INR", "PROCESSOR_CLEARING", "DEBIT", "99.95", "99.95", "0");
        assertBalance("INR", "MERCHANT_PAYABLE", "CREDIT", "99.95", "99.95", "0");
    }

    @Test
    void balancesAccumulateByAccountAndNeverMixCurrencies() throws Exception {
        captured("inr-one", "10.00", "INR");
        captured("inr-two", "20.00", "INR");
        captured("usd-one", "7.50", "USD");
        assertCounts(3, 6);
        assertBalance("INR", "PROCESSOR_CLEARING", "DEBIT", "30", "0", "30");
        assertBalance("INR", "MERCHANT_PAYABLE", "CREDIT", "0", "30", "30");
        assertBalance("USD", "PROCESSOR_CLEARING", "DEBIT", "7.50", "0", "7.50");
        assertBalance("USD", "MERCHANT_PAYABLE", "CREDIT", "0", "7.50", "7.50");
    }

    @Test
    void duplicateCaptureAndRefundDoNotPostAgain() throws Exception {
        UUID id = captured("duplicate", "10", "INR");
        action(id, "capture", 409);
        assertCounts(1, 2);
        action(id, "refund", 200);
        action(id, "refund", 409);
        assertCounts(2, 4);
    }

    @Test
    void replayOfCreationAfterCaptureKeepsSnapshotAndDoesNotPostAgain() throws Exception {
        String body = body("snapshot", "10", "INR");
        var created = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "snapshot-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        action(id, "authorize", 200);
        action(id, "capture", 200);
        var replay = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "snapshot-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(created.getResponse().getContentAsString());
        assertCounts(1, 2);
    }

    @Test
    void failedTransactionRollsBackPaymentStateVersionAndAllLedgerWrites() throws Exception {
        UUID id = authorized("rollback", "10", "INR");
        var before = payments.findById(id).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            service.capturePayment(id);
            assertCounts(1, 2);
            throw new IllegalStateException("Intentional failure after capture and ledger insert");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Intentional failure");

        var after = payments.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertCounts(0, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_accounts", Long.class)).isZero();
        action(id, "capture", 200);
        assertCounts(1, 2);
    }

    @Test
    void concurrentCapturesCommitOneJournalOnly() throws Exception {
        UUID id = authorized("capture-race", "1000.00", "INR");
        int callers = 4;
        var ready = new CyclicBarrier(callers);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/payments/{id}/capture", id)).andReturn();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(results.stream().filter(code -> code == 409).count()).isEqualTo(callers - 1);
        }
        assertCounts(1, 2);
        assertThat(ledger(id).size()).isEqualTo(1);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE ledger_transactions SET amount = amount",
            "DELETE FROM ledger_transactions",
            "UPDATE ledger_entries SET amount = amount",
            "DELETE FROM ledger_entries",
            "UPDATE ledger_accounts SET normal_side = normal_side",
            "DELETE FROM ledger_accounts"
    })
    void postedLedgerRowsAreImmutable(String sql) throws Exception {
        captured("immutable", "10", "INR");
        assertDatabaseFailure(() -> jdbc.update(sql));
        assertCounts(1, 2);
    }

    @Test
    void databaseRejectsDuplicatePaymentOperation() throws Exception {
        UUID id = captured("db-duplicate", "10", "INR");
        assertDatabaseFailure(() -> insertJournal(UUID.randomUUID(), id, "CAPTURE", "INR", "10", null));
        assertCounts(1, 2);
    }

    @Test
    void databaseRejectsEmptyJournalAtCommit() throws Exception {
        UUID id = authorized("empty-journal", "10", "INR");
        assertDatabaseFailure(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            markState(id, "CAPTURED");
            insertJournal(UUID.randomUUID(), id, "CAPTURE", "INR", "10", null);
            return null;
        }));
        assertCounts(0, 0);
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void databaseRejectsUnbalancedJournalAtCommit() throws Exception {
        UUID id = authorized("unbalanced", "10", "INR");
        assertDatabaseFailure(() -> rawCapture(id, "INR", "10", "INR", "10", "9", false));
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsCrossCurrencyEntries() throws Exception {
        UUID id = authorized("cross-currency", "10", "INR");
        assertDatabaseFailure(() -> rawCapture(id, "INR", "10", "USD", "10", "10", false));
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsBalancedJournalWithWrongAccountDirections() throws Exception {
        UUID id = authorized("wrong-directions", "10", "INR");
        assertDatabaseFailure(() -> rawCapture(id, "INR", "10", "INR", "10", "10", true));
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsNegativeEntries() throws Exception {
        UUID id = authorized("negative-entry", "10", "INR");
        assertDatabaseFailure(() -> rawCapture(id, "INR", "10", "INR", "-10", "-10", false));
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsPaymentCaptureWithoutLedger() throws Exception {
        UUID id = authorized("state-without-ledger", "10", "INR");
        assertDatabaseFailure(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            markState(id, "CAPTURED");
            return null;
        }));
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsRefundThatReversesAnotherPayment() throws Exception {
        UUID first = captured("reversal-first", "10", "INR");
        UUID second = captured("reversal-second", "10", "INR");
        UUID firstCapture = UUID.fromString(operation(ledger(first), "CAPTURE").get("id").asText());
        assertDatabaseFailure(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            markState(second, "REFUNDED");
            UUID refund = UUID.randomUUID();
            insertJournal(refund, second, "REFUND", "INR", "10", firstCapture);
            insertEntry(refund, "PROCESSOR_CLEARING", "INR", "CREDIT", "10");
            insertEntry(refund, "MERCHANT_PAYABLE", "INR", "DEBIT", "10");
            return null;
        }));
        assertCounts(2, 4);
        assertThat(payments.findById(second).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void missingPaymentLedgerReturns404AndInvalidCurrencyReturns400() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}/ledger", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        mvc.perform(get("/api/v1/ledger/accounts/inr/balances")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ledger/accounts/IN/balances")).andExpect(status().isBadRequest());
        assertCounts(0, 0);
    }

    @Test
    void readingBalancesDoesNotProvisionAccounts() throws Exception {
        mvc.perform(get("/api/v1/ledger/accounts/INR/balances")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_accounts", Long.class)).isZero();
    }

    @Test
    void v3UpgradeBackfillsKnownLegacyCaptureAndRefundWithoutInventingHistoricalTimes() throws Exception {
        // A separate uniquely named schema exercises a genuine V1/V2 -> V3 upgrade without
        // cleaning or changing the application's schema. All statements use this one connection.
        String schema = "ledger_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("ledger_upgrade_[0-9a-f]{32}");
        try (Connection connection = dataSource.getConnection()) {
            String originalSchema = connection.getSchema();
            var scopedDataSource = new SingleConnectionDataSource(connection, true);
            var isolated = new JdbcTemplate(scopedDataSource);
            isolated.execute("CREATE SCHEMA \"" + schema + "\"");
            try {
                Flyway.configure().dataSource(scopedDataSource).schemas(schema).defaultSchema(schema)
                        .target("2").load().migrate();
                connection.setSchema(schema);
                UUID created = UUID.randomUUID();
                UUID captured = UUID.randomUUID();
                UUID refunded = UUID.randomUUID();
                Timestamp historicalTime = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
                for (var legacy : List.of(
                        new Object[]{created, "CREATED", "INR", new BigDecimal("5.00")},
                        new Object[]{captured, "CAPTURED", "INR", new BigDecimal("10.00")},
                        new Object[]{refunded, "REFUNDED", "USD", new BigDecimal("20.00")})) {
                    isolated.update("""
                            INSERT INTO payments
                                (id, merchant_reference, amount, currency, status, created_at, updated_at, version)
                            VALUES (?, 'LEGACY-ORDER', ?, ?, ?, ?, ?, 0)
                            """, legacy[0], legacy[3], legacy[2], legacy[1], historicalTime, historicalTime);
                }
                Timestamp beforeMigration = isolated.queryForObject("SELECT clock_timestamp()", Timestamp.class);
                Flyway.configure().dataSource(scopedDataSource).schemas(schema).defaultSchema(schema)
                        .load().migrate();
                connection.setSchema(schema);
                Timestamp afterMigration = isolated.queryForObject("SELECT clock_timestamp()", Timestamp.class);

                assertThat(isolated.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(3);
                assertThat(isolated.queryForObject("SELECT count(*) FROM ledger_entries", Long.class)).isEqualTo(6);
                assertThat(isolated.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id = ?",
                        Long.class, created)).isZero();
                assertThat(isolated.queryForObject("""
                        SELECT count(*) FROM ledger_transactions WHERE payment_id = ? AND operation = 'CAPTURE'
                        """, Long.class, captured)).isEqualTo(1);
                assertThat(isolated.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id = ?",
                        Long.class, refunded)).isEqualTo(2);
                assertThat(isolated.queryForObject("""
                        SELECT refund.reverses_transaction_id = capture.id
                        FROM ledger_transactions refund JOIN ledger_transactions capture
                            ON capture.payment_id = refund.payment_id AND capture.operation = 'CAPTURE'
                        WHERE refund.payment_id = ? AND refund.operation = 'REFUND'
                        """, Boolean.class, refunded)).isTrue();
                assertThat(isolated.queryForObject("""
                        SELECT count(*) FROM ledger_transactions WHERE source = 'LEGACY_BACKFILL'
                        """, Long.class)).isEqualTo(3);
                assertThat(isolated.queryForObject("""
                        SELECT count(*) FROM (
                            SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
                            HAVING count(*) = 2 AND sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) = 0
                        ) balanced
                        """, Long.class)).isEqualTo(3);
                for (Timestamp recordedAt : isolated.queryForList("SELECT created_at FROM ledger_transactions", Timestamp.class)) {
                    assertThat(recordedAt).isBetween(beforeMigration, afterMigration).isNotEqualTo(historicalTime);
                }
                assertThat(isolated.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(3);
            } finally {
                // Exact identifier was generated and validated above; never target an application schema.
                connection.setSchema(originalSchema);
                isolated.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private UUID create(String reference, String amount, String currency) throws Exception {
        var response = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON).content(body(reference, amount, currency)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID authorized(String reference, String amount, String currency) throws Exception {
        UUID id = create(reference, amount, currency);
        action(id, "authorize", 200);
        return id;
    }

    private UUID captured(String reference, String amount, String currency) throws Exception {
        UUID id = authorized(reference, amount, currency);
        action(id, "capture", 200);
        return id;
    }

    private void action(UUID id, String action, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/{action}", id, action)).andExpect(status().is(expectedStatus));
    }

    private JsonNode ledger(UUID id) throws Exception {
        var response = mvc.perform(get("/api/v1/payments/{id}/ledger", id)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode operation(JsonNode journals, String operation) {
        for (JsonNode journal : journals) {
            if (journal.get("operation").asText().equals(operation)) return journal;
        }
        throw new AssertionError("No " + operation + " journal in " + journals);
    }

    private void assertEntry(JsonNode journal, String account, String side, String amount, String currency) {
        JsonNode entries = journal.get("entries");
        assertThat(entries.size()).isEqualTo(2);
        for (JsonNode entry : entries) {
            if (entry.get("account").asText().equals(account)) {
                assertThat(entry.get("id").asText()).isNotBlank();
                assertThat(entry.get("side").asText()).isEqualTo(side);
                assertThat(entry.get("currency").asText()).isEqualTo(currency);
                assertThat(entry.get("amount").decimalValue()).isEqualByComparingTo(amount);
                return;
            }
        }
        throw new AssertionError("Missing account entry " + account);
    }

    private void assertBalance(String currency, String account, String normalSide,
            String debits, String credits, String balance) throws Exception {
        var response = mvc.perform(get("/api/v1/ledger/accounts/{currency}/balances", currency))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode row : mapper.readTree(response.getResponse().getContentAsString())) {
            if (row.get("account").asText().equals(account)) {
                assertThat(row.get("currency").asText()).isEqualTo(currency);
                assertThat(row.get("normalSide").asText()).isEqualTo(normalSide);
                assertThat(row.get("totalDebits").decimalValue()).isEqualByComparingTo(debits);
                assertThat(row.get("totalCredits").decimalValue()).isEqualByComparingTo(credits);
                assertThat(row.get("balance").decimalValue()).isEqualByComparingTo(balance);
                return;
            }
        }
        throw new AssertionError("Missing balance for " + account + "/" + currency);
    }

    private void assertCounts(long journals, long entries) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(journals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Long.class)).isEqualTo(entries);
    }

    private void assertDatabaseFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class);
    }

    private void markState(UUID id, String state) {
        jdbc.update("UPDATE payments SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                state, id);
    }

    private void insertJournal(UUID journalId, UUID paymentId, String operation, String currency,
            String amount, UUID reversalId) {
        jdbc.update("""
                INSERT INTO ledger_transactions
                    (id, payment_id, operation, currency, amount, reverses_transaction_id, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'LIVE', CURRENT_TIMESTAMP)
                """, journalId, paymentId, operation, currency, new BigDecimal(amount), reversalId);
    }

    private void insertEntry(UUID journalId, String account, String currency, String side, String amount) {
        jdbc.update("""
                INSERT INTO ledger_entries (id, transaction_id, account_code, currency, side, amount)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), journalId, account, currency, side, new BigDecimal(amount));
    }

    private void provisionAccounts(String currency) {
        jdbc.update("""
                INSERT INTO ledger_accounts (account_code, currency, normal_side, created_at)
                VALUES ('PROCESSOR_CLEARING', ?, 'DEBIT', CURRENT_TIMESTAMP),
                       ('MERCHANT_PAYABLE', ?, 'CREDIT', CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, currency, currency);
    }

    private void rawCapture(UUID paymentId, String journalCurrency, String journalAmount, String entryCurrency,
            String debit, String credit, boolean reverseDirections) {
        new TransactionTemplate(transactionManager).execute(transaction -> {
            markState(paymentId, "CAPTURED");
            provisionAccounts(entryCurrency);
            UUID journalId = UUID.randomUUID();
            insertJournal(journalId, paymentId, "CAPTURE", journalCurrency, journalAmount, null);
            insertEntry(journalId, reverseDirections ? "MERCHANT_PAYABLE" : "PROCESSOR_CLEARING",
                    entryCurrency, "DEBIT", debit);
            insertEntry(journalId, reverseDirections ? "PROCESSOR_CLEARING" : "MERCHANT_PAYABLE",
                    entryCurrency, "CREDIT", credit);
            return null;
        });
    }

    private String body(String reference, String amount, String currency) {
        return "{\"merchantReference\":\"" + reference + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }
}
