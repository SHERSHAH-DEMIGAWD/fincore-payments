package com.fincore.payments.operations;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.dto.RequeueCommand;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.ConsumerRecoveryService;
import com.fincore.payments.service.ProjectionReconciliationService;
import com.fincore.payments.service.ProjectionRebuildService;
import com.fincore.payments.service.EventOperationsService;
import com.fincore.payments.service.KafkaLagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EventOperationsCliTest {
    private final EventOperationsService operations = mock(EventOperationsService.class);
    private final KafkaLagService lag = mock(KafkaLagService.class);
    private final ConsumerFailureService failures = mock(ConsumerFailureService.class);
    private final ConsumerRecoveryService recovery = mock(ConsumerRecoveryService.class);
    private final ProjectionReconciliationService reconciliation = mock(ProjectionReconciliationService.class);
    private final ProjectionRebuildService rebuild = mock(ProjectionRebuildService.class);

    @Test
    void helpDoesNotStartTheApplicationOrRequireADatabase() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exit = EventOperationsCli.run(new String[]{"--help"}, new PrintStream(output), new PrintStream(errors));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("expected-generation", "request-id");
        assertThat(errors.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--limit=0", "--limit=101", "--limit=words", "--status=PROCESSING",
            "--spring.datasource.url=jdbc:anything", "--limit", "--status="})
    void invalidOptionsAreRejectedBeforeDatabaseStartup(String invalidOption) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exit = EventOperationsCli.run(new String[]{"events", invalidOption},
                new PrintStream(output), new PrintStream(errors));

        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("INVALID_ARGUMENTS");
        assertThat(output.size()).isZero();
    }

    @Test
    void duplicateOptionsAreRejected() {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"events", "--limit=2", "--limit=3"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventListingDefaultsToDeadWithABoundedLimit() {
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"events"}), operations, lag, failures, recovery, reconciliation, rebuild);

        verify(operations).events(OutboxStatus.DEAD, 50);
        verifyNoInteractions(lag, failures);
    }

    @Test
    void allStatusesAndFailureHistoryAreExplicitChoices() {
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"events", "--status=ALL", "--limit=100"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"failures", "--state=ALL"}),
                operations, lag, failures, recovery, reconciliation, rebuild);

        verify(operations).events(null, 100);
        verify(failures).list(false, 50);
    }

    @Test
    void recoveryPreservesExactOperatorIntentAndRequestIdentity() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var invocation = EventOperationsCli.parse(recovery(eventId, requestId, "1", "broker restored=verified"));

        EventOperationsCli.execute(invocation, operations, lag, failures, recovery, reconciliation, rebuild);

        verify(operations).requeue(new RequeueCommand(requestId, eventId, 1, "operator-1", "broker restored=verified"));
        verifyNoInteractions(lag, failures);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "9223372036854775807", "no-version"})
    void recoveryRequiresAnIncrementableExpectedGeneration(String invalidGeneration) {
        assertThatThrownBy(() -> EventOperationsCli.parse(
                recovery(UUID.randomUUID(), UUID.randomUUID(), invalidGeneration, "restored")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nbreak", "line\u2028break", "line\u2029break"})
    void recoveryRequiresAnExplicitSingleLineReason(String reason) {
        assertThatThrownBy(() -> EventOperationsCli.parse(
                recovery(UUID.randomUUID(), UUID.randomUUID(), "0", reason)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoveryCannotOmitTheOperatorOrRequestId() {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"requeue", "--event-id=" + UUID.randomUUID(),
                "--expected-generation=0", "--reason=restored"})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumerRecoveryRoutesExactIntentWithoutExposingSpringOptions() {
        UUID failure = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var arguments = consumerCommand("quarantine", failure, request);
        EventOperationsCli.execute(EventOperationsCli.parse(arguments), operations, lag, failures, recovery, reconciliation, rebuild);
        verify(recovery).quarantine(new com.fincore.payments.dto.ConsumerRecoveryCommand(request, failure,
                "a".repeat(64), "operator-1", "reviewed gap"));
        EventOperationsCli.execute(EventOperationsCli.parse(consumerCommand("replay", failure, request)),
                operations, lag, failures, recovery, reconciliation, rebuild);
        verify(recovery).replay(new com.fincore.payments.dto.ConsumerRecoveryCommand(request, failure,
                "a".repeat(64), "operator-1", "reviewed gap"));
    }

    @Test
    void gapAndQuarantineViewsHaveExplicitBoundedScope() {
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"gaps"}), operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"quarantines"}), operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"quarantines", "--state=ALL", "--limit=100"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        verify(recovery).gaps();
        verify(recovery).quarantines(true, 50);
        verify(recovery).quarantines(false, 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "TRUE", "yes", "1"})
    void quarantineRequiresExactExplicitGapAcceptance(String acceptance) {
        var arguments = consumerCommand("quarantine", UUID.randomUUID(), UUID.randomUUID());
        arguments[arguments.length - 1] = "--accept-projection-gap=" + acceptance;
        assertThatThrownBy(() -> EventOperationsCli.parse(arguments)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quarantineCannotOmitGapAcceptanceAndReplayCannotAddIt() {
        var arguments = consumerCommand("replay", UUID.randomUUID(), UUID.randomUUID());
        arguments[0] = "quarantine";
        assertThatThrownBy(() -> EventOperationsCli.parse(arguments)).isInstanceOf(IllegalArgumentException.class);
        var withAcceptance = consumerCommand("quarantine", UUID.randomUUID(), UUID.randomUUID());
        withAcceptance[0] = "replay";
        assertThatThrownBy(() -> EventOperationsCli.parse(withAcceptance)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void fingerprintMustBeAnExactLowercaseSha256(String fingerprint) {
        var arguments = consumerCommand("replay", UUID.randomUUID(), UUID.randomUUID());
        arguments[3] = "--expected-fingerprint=" + fingerprint;
        assertThatThrownBy(() -> EventOperationsCli.parse(arguments)).isInstanceOf(IllegalArgumentException.class);
    }

    private String[] consumerCommand(String action, UUID failure, UUID request) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(action, "--failure-id=" + failure,
                "--request-id=" + request, "--expected-fingerprint=" + "a".repeat(64),
                "--operator=operator-1", "--reason=reviewed gap"));
        if (action.equals("quarantine")) arguments.add("--accept-projection-gap=true");
        return arguments.toArray(String[]::new);
    }

    @Test
    void reconciliationRoutesBoundedPageAndOptionalPaymentFilter() {
        UUID id = UUID.randomUUID();
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"reconcile"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"reconcile", "--payment-id=" + id}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"reconcile", "--after-payment-id=" + id, "--limit=10"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        verify(reconciliation).reconcile(null, null, 50);
        verify(reconciliation).reconcile(id, null, 50);
        verify(reconciliation).reconcile(null, id, 10);
        verifyNoInteractions(operations, lag, failures, recovery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--payment-id=invalid", "--after-payment-id=invalid", "--after-payment-id=1-1-1-1-1",
            "--limit=101", "--limit=0", "--repair=true", "--spring.datasource.url=jdbc:other"})
    void reconciliationRejectsInvalidOrMutatingOptionsBeforeStartup(String option) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int result = EventOperationsCli.run(new String[]{"reconcile", option}, new PrintStream(output), new PrintStream(errors));
        assertThat(result).isEqualTo(2);
        assertThat(output.size()).isZero();
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("INVALID_ARGUMENTS");
    }

    @Test
    void reconciliationRejectsCombinedPaymentFilterAndPagination() {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"reconcile",
                "--payment-id=" + UUID.randomUUID(), "--after-payment-id=" + UUID.randomUUID()}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowRebuildCommandsRouteToTheReservedWorkflow() {
        UUID run = UUID.randomUUID();

        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"rebuild-create", "--run-id=" + run,
                        "--operator=local-operator", "--reason=reviewed source coverage"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"rebuild-step", "--run-id=" + run}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"rebuild-step", "--run-id=" + run, "--limit=1"}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"rebuild-status", "--run-id=" + run}),
                operations, lag, failures, recovery, reconciliation, rebuild);
        EventOperationsCli.execute(EventOperationsCli.parse(new String[]{"rebuild-validate", "--run-id=" + run}),
                operations, lag, failures, recovery, reconciliation, rebuild);

        verify(rebuild).create(run, "local-operator", "reviewed source coverage");
        verify(rebuild).step(run, 50);
        verify(rebuild).step(run, 1);
        verify(rebuild).status(run);
        verify(rebuild).validate(run);
        verifyNoInteractions(operations, lag, failures, recovery, reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rebuild-create", "rebuild-step", "rebuild-status", "rebuild-validate", "rebuild-catch-up"})
    void shadowRebuildRequiresCanonicalRunIdentityBeforeStartup(String command) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exit = EventOperationsCli.run(new String[]{command, "--run-id=1-1-1-1-1"},
                new PrintStream(output), new PrintStream(errors));

        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("INVALID_ARGUMENTS");
        assertThat(output.size()).isZero();
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{command})).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--limit=0", "--limit=101", "--limit=words", "--consumer-name=payment-projection-v1",
            "--promote=true", "--spring.kafka.listener-auto-startup=true"})
    void shadowStepRejectsUnboundedOrLiveMutatingOptionsBeforeStartup(String option) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exit = EventOperationsCli.run(new String[]{"rebuild-step", "--run-id=" + UUID.randomUUID(), option},
                new PrintStream(output), new PrintStream(errors));

        assertThat(exit).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("INVALID_ARGUMENTS");
        assertThat(output.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nbreak", "line\u2028break", "line\u2029break"})
    void shadowCreationRequiresAnExplicitSingleLineReason(String reason) {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-create", "--run-id=" + UUID.randomUUID(),
                "--operator=operator", "--reason=" + reason})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowCreationCannotOmitOperatorOrUseUnboundedAuditText() {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-create", "--run-id=" + UUID.randomUUID(),
                "--reason=reviewed"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-create", "--run-id=" + UUID.randomUUID(),
                "--operator=" + "a".repeat(101), "--reason=reviewed"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-create", "--run-id=" + UUID.randomUUID(),
                "--operator=operator", "--reason=" + "a".repeat(501)})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowCatchUpRoutesExplicitGenerationAndExactRequestIdentity() {
        UUID run = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        EventOperationsCli.execute(EventOperationsCli.parse(catchUpCommand(run, request, "0")),
                operations, lag, failures, recovery, reconciliation, rebuild);
        verify(rebuild).catchUp(run, 0, request, "operator", "reviewed new source snapshot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "9223372036854775807", "words"})
    void shadowCatchUpRejectsInvalidExpectedGenerationsBeforeStartup(String generation) {
        assertThatThrownBy(() -> EventOperationsCli.parse(catchUpCommand(UUID.randomUUID(), UUID.randomUUID(), generation)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowCatchUpCannotOmitItsAuditOrReuseLiveSelectionOptions() {
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-catch-up", "--run-id=" + UUID.randomUUID(),
                "--expected-generation=0", "--operator=operator", "--reason=reviewed"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventOperationsCli.parse(new String[]{"rebuild-catch-up", "--run-id=" + UUID.randomUUID(),
                "--expected-generation=0", "--request-id=" + UUID.randomUUID(), "--operator=operator", "--reason=reviewed",
                "--consumer-name=payment-projection-v1"})).isInstanceOf(IllegalArgumentException.class);
    }

    private String[] catchUpCommand(UUID run, UUID request, String generation) {
        return new String[]{"rebuild-catch-up", "--run-id=" + run, "--expected-generation=" + generation,
                "--request-id=" + request, "--operator=operator", "--reason=reviewed new source snapshot"};
    }

    private String[] recovery(UUID eventId, UUID requestId, String generation, String reason) {
        return new String[]{"requeue", "--event-id=" + eventId, "--request-id=" + requestId,
                "--expected-generation=" + generation, "--operator=operator-1", "--reason=" + reason};
    }
}
