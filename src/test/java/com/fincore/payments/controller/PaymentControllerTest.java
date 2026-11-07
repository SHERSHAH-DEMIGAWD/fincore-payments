package com.fincore.payments.controller;

import com.fincore.payments.domain.Payment;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.CreatePaymentResult;
import com.fincore.payments.service.IdempotencyKey;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.exception.IllegalPaymentStateTransitionException;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.exception.IdempotencyConflictException;
import com.fincore.payments.service.MerchantPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({ProjectionOperationsSecurityConfiguration.class,com.fincore.payments.config.MerchantSecurityConfiguration.class})
class PaymentControllerTest {
    @MockitoBean com.fincore.payments.repository.MerchantCredentialRepository credentials;
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private MerchantPaymentService service;

    @Test
    void createsPaymentWithLocationAndDto() throws Exception {
        PaymentResponse response = response();
        when(service.createPayment(any(), any())).thenReturn(new CreatePaymentResult(response, true));

        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON).content("""
                {"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/payments/" + response.id()))
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").doesNotExist());
        verify(service).createPayment("test-key", new CreatePaymentRequest("ORDER-123", new BigDecimal("1499.99"), "INR"));
    }

    @Test
    void readsPayment() throws Exception {
        PaymentResponse response = response();
        when(service.getPayment(response.id())).thenReturn(response);
        mvc.perform(get("/api/v1/payments/{id}", response.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(response.id().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorize", "capture", "fail", "refund"})
    void delegatesTransitionEndpoints(String action) throws Exception {
        Payment payment = Payment.create("ORDER-123", BigDecimal.TEN, "INR");
        UUID id = payment.getId();
        switch (action) {
            case "authorize" -> payment.authorize();
            case "capture" -> { payment.authorize(); payment.capture(); }
            case "fail" -> payment.fail();
            case "refund" -> { payment.authorize(); payment.capture(); payment.refund(); }
        }
        PaymentResponse response = PaymentResponse.from(payment);
        switch (action) {
            case "authorize" -> when(service.authorizePayment(id)).thenReturn(response);
            case "capture" -> when(service.capturePayment(id)).thenReturn(response);
            case "fail" -> when(service.failPayment(id)).thenReturn(response);
            case "refund" -> when(service.refundPayment(id)).thenReturn(response);
        }
        mvc.perform(post("/api/v1/payments/{id}/{action}", id, action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(payment.getStatus().name()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null", "0.001", "100000000000000000"})
    void rejectsInvalidAmount(String amount) throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"ORDER-123\",\"amount\":" + amount + ",\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/payments"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inr", "IN", "INRR", "I1R", ""})
    void rejectsInvalidCurrency(String currency) throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"ORDER-123\",\"amount\":100,\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.currency").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsBlankMerchantReference() throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON).content("""
                {"merchantReference":"   ","amount":100,"currency":"INR"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.merchantReference").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizedMerchantReference() throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"" + "a".repeat(256) + "\",\"amount\":100,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.merchantReference").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.merchantReference").exists())
                .andExpect(jsonPath("$.fieldErrors.amount").exists())
                .andExpect(jsonPath("$.fieldErrors.currency").exists());
        verifyNoInteractions(service);
    }

    @Test
    void returns404ForMissingPayment() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getPayment(id)).thenThrow(new PaymentNotFoundException(id));
        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void returns409ForIllegalTransition() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.refundPayment(id)).thenThrow(
                new IllegalPaymentStateTransitionException(PaymentStatus.CREATED, PaymentStatus.REFUNDED));
        mvc.perform(post("/api/v1/payments/{id}/refund", id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ILLEGAL_PAYMENT_STATE_TRANSITION"));
    }

    @Test
    void returns409ForConcurrentUpdate() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.authorizePayment(id)).thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, id));
        mvc.perform(post("/api/v1/payments/{id}/authorize", id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONCURRENT_PAYMENT_UPDATE"))
                .andExpect(content().string(not(containsString("Hibernate"))));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedUuid() throws Exception {
        mvc.perform(get("/api/v1/payments/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotExposeDatabaseErrors() throws Exception {
        when(service.createPayment(any(), any())).thenThrow(new DataIntegrityViolationException("internal SQL constraint_name"));
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key").contentType(MediaType.APPLICATION_JSON).content("""
                {"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}
                """))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("constraint_name"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    private PaymentResponse response() {
        return PaymentResponse.from(Payment.create("ORDER-123", new BigDecimal("1499.99"), "INR"));
    }

    @Test
    void replayReturns200AndOriginalResponse() throws Exception {
        var response = response();
        when(service.createPayment(any(), any())).thenReturn(new CreatePaymentResult(response, false));
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void conflictingKeyReturnsStructured409() throws Exception {
        when(service.createPayment(any(), any())).thenThrow(new IdempotencyConflictException());
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));
    }

    @Test
    void missingKeyReturns400() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "contains space", "clé", "\t"})
    void invalidKeyReturns400(String key) throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
        verifyNoInteractions(service);
    }

    @Test
    void overlongKeyReturns400() throws Exception {
        mvc.perform(post("/api/v1/payments").header(IdempotencyKey.HEADER_NAME, "a".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
        verifyNoInteractions(service);
    }

    private String validBody() {
        return "{\"merchantReference\":\"ORDER-123\",\"amount\":1499.99,\"currency\":\"INR\"}";
    }
}
