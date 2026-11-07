package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.exception.InvalidPaymentEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** Shared strict wire contract for live consumption and replay of preserved evidence. */
@Component
public class PaymentEventRecordDecoder {
    private final ObjectMapper mapper;
    private final PaymentEventValidator validator;

    public PaymentEventRecordDecoder(ObjectMapper mapper, PaymentEventValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public PaymentEvent decode(ConsumerRecord<String, String> record) {
        PaymentEvent event;
        if (record.value() == null) throw new InvalidPaymentEventException("Payment event JSON is required.");
        try {
            JsonNode payload = mapper.readTree(record.value());
            if (payload == null || !payload.isObject()
                    || !payload.path("paymentVersion").isIntegralNumber()
                    || !payload.path("paymentVersion").canConvertToLong()
                    || !payload.path("schemaVersion").isIntegralNumber()
                    || !payload.path("schemaVersion").canConvertToInt()
                    || !payload.path("data").path("amount").isNumber()) {
                throw new InvalidPaymentEventException("Payment event JSON has invalid envelope fields.");
            }
            event = mapper.readerFor(PaymentEvent.class)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    // Bind the original JSON to preserve every cent of large monetary values.
                    .readValue(record.value());
        } catch (JsonProcessingException exception) {
            throw new InvalidPaymentEventException("Payment event JSON is invalid.");
        }
        validator.validate(event);
        if (!event.paymentId().toString().equals(record.key())) {
            throw new InvalidPaymentEventException("Kafka record key must match the payment ID.");
        }
        return event;
    }
}
