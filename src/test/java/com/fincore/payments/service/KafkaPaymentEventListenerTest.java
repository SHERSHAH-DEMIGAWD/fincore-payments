package com.fincore.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.PaymentEventConflictException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.repository.ConsumerRecoveryRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class KafkaPaymentEventListenerTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final PaymentEventConsumer consumer = mock(PaymentEventConsumer.class);
    private final ConsumerFailureService failures = mock(ConsumerFailureService.class);
    private final ConsumerFailureRepository positions = mock(ConsumerFailureRepository.class);
    private final ConsumerRecoveryRepository recovery = mock(ConsumerRecoveryRepository.class);
    private final ConsumerRecordProcessor processor = new ConsumerRecordProcessor(
            new PaymentEventRecordDecoder(mapper, new PaymentEventValidator()), consumer, positions, recovery, "primary");
    private final KafkaPaymentEventListener listener = new KafkaPaymentEventListener(processor, failures);

    @Test
    void validEnvelopeIsConsumedBeforeListenerReturns() throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();

        var record = record(event.paymentId().toString(), mapper.writeValueAsString(event));
        listener.onMessage(record);

        var order = inOrder(positions, consumer);
        order.verify(positions).lockPosition("primary", PaymentEventConsumer.CONSUMER_NAME, record);
        order.verify(consumer).consume(event);
        order.verify(positions).resolve("primary", PaymentEventConsumer.CONSUMER_NAME, record);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740992.01", "99999999999999999.99"})
    void wireParsingPreservesEveryCentForLargeAmounts(String amount) throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();
        ObjectNode payload = mapper.valueToTree(event);
        ((ObjectNode) payload.get("data")).put("amount", new BigDecimal(amount));

        listener.onMessage(record(event.paymentId().toString(), payload.toString()));

        verify(consumer).consume(argThat(consumed -> consumed.data().amount().compareTo(new BigDecimal(amount)) == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{}", "{\"schemaVersion\":1}"})
    void malformedEnvelopeNeverReachesConsumer(String payload) {
        assertThatThrownBy(() -> listener.onMessage(record("key", payload)))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "schemaVersion", "paymentId", "paymentVersion", "occurredAt", "data"})
    void omittedRecordFieldsAreRejectedInsteadOfDefaultingPrimitives(String field) {
        ObjectNode payload = mapper.valueToTree(KafkaPaymentEventPublisherTest.event());
        payload.remove(field);

        assertThatThrownBy(() -> listener.onMessage(record("key", payload.toString())))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
    }

    @Test
    void fractionalVersionCannotBeTruncatedIntoAnExistingVersion() {
        ObjectNode payload = mapper.valueToTree(KafkaPaymentEventPublisherTest.event());
        payload.put("paymentVersion", 0.5);

        assertThatThrownBy(() -> listener.onMessage(record("key", payload.toString())))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
    }

    @Test
    void numericEnumOrdinalsAreNotAcceptedAsEventTypes() {
        ObjectNode payload = mapper.valueToTree(KafkaPaymentEventPublisherTest.event());
        payload.put("eventType", 0);
        ((ObjectNode) payload.get("data")).put("status", 0);

        assertThatThrownBy(() -> listener.onMessage(record("key", payload.toString())))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
    }

    @Test
    void extraJsonDocumentsAreRejected() throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();
        assertThatThrownBy(() -> listener.onMessage(record(event.paymentId().toString(),
                mapper.writeValueAsString(event) + " {}")))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
    }

    @Test
    void mismatchedPartitionKeyCannotDestroyPaymentOrdering() throws Exception {
        assertThatThrownBy(() -> listener.onMessage(record("different-payment",
                mapper.writeValueAsString(KafkaPaymentEventPublisherTest.event()))))
                .isInstanceOf(InvalidPaymentEventException.class).hasMessageContaining("record key");
        verifyNoInteractions(consumer);
    }

    @Test
    void tombstoneIsRejected() {
        var record = record("key", null);
        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(InvalidPaymentEventException.class);
        verifyNoInteractions(consumer);
        verify(failures).failed(eq(record), any(InvalidPaymentEventException.class));
    }

    @Test
    void malformedRecordFailureIsTrackedBeforeItIsRethrown() {
        var record = record("key", "private malformed value");
        assertThatThrownBy(() -> listener.onMessage(record)).isInstanceOf(InvalidPaymentEventException.class);
        verify(failures).failed(eq(record), any(InvalidPaymentEventException.class));
        verifyNoInteractions(consumer);
    }

    @Test
    void conflictingReceiptFailureIsTrackedWithoutChangingTheOriginalException() throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();
        var record = record(event.paymentId().toString(), mapper.writeValueAsString(event));
        var conflict = new PaymentEventConflictException();
        doThrow(conflict).when(consumer).consume(event);

        assertThatThrownBy(() -> listener.onMessage(record)).isSameAs(conflict);

        verify(failures).failed(record, conflict);
    }

    @Test
    void diagnosticDatabaseFailureCannotReplaceTheOriginalConsumerFailure() throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();
        var record = record(event.paymentId().toString(), mapper.writeValueAsString(event));
        var original = new IllegalStateException("private processing details");
        doThrow(original).when(consumer).consume(event);
        doThrow(new IllegalStateException("private DB details")).when(failures).failed(record, original);

        assertThatThrownBy(() -> listener.onMessage(record)).isSameAs(original);
    }

    @Test
    void resolutionFailureEscapesTheSharedProcessingTransactionBeforeListenerReturns() throws Exception {
        var event = KafkaPaymentEventPublisherTest.event();
        var record = record(event.paymentId().toString(), mapper.writeValueAsString(event));
        var resolutionFailure = new IllegalStateException("resolution DB unavailable");
        doThrow(resolutionFailure).when(positions).resolve("primary", PaymentEventConsumer.CONSUMER_NAME, record);

        assertThatThrownBy(() -> listener.onMessage(record)).isSameAs(resolutionFailure);

        var order = inOrder(consumer, positions, failures);
        order.verify(consumer).consume(event);
        order.verify(positions).resolve("primary", PaymentEventConsumer.CONSUMER_NAME, record);
        order.verify(failures).failed(record, resolutionFailure);
    }

    @Test
    void durableQuarantineAllowsReturnWithoutParsingOrClaimingSuccessfulProcessing() {
        var record = record("key", "malformed and preserved");
        org.mockito.Mockito.when(recovery.isQuarantined("primary", PaymentEventConsumer.CONSUMER_NAME, record))
                .thenReturn(true);

        listener.onMessage(record);

        verify(positions).lockPosition("primary", PaymentEventConsumer.CONSUMER_NAME, record);
        verifyNoInteractions(consumer, failures);
        org.mockito.Mockito.verify(positions, org.mockito.Mockito.never())
                .resolve("primary", PaymentEventConsumer.CONSUMER_NAME, record);
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("payment-events.v1", 0, 0, key, value);
    }
}
