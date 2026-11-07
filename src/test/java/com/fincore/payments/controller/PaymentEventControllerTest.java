package com.fincore.payments.controller;

import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import org.springframework.context.annotation.Import;

import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.service.MerchantPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentEventController.class)
@Import({ProjectionOperationsSecurityConfiguration.class,com.fincore.payments.config.MerchantSecurityConfiguration.class})
class PaymentEventControllerTest {
    @MockitoBean com.fincore.payments.repository.MerchantCredentialRepository credentials;
    @Autowired MockMvc mvc;
    @MockitoBean MerchantPaymentService outbox;

    @Test
    void returnsEventsForPayment() throws Exception {
        UUID id = UUID.randomUUID();
        when(outbox.getPaymentEvents(id)).thenReturn(List.of());
        mvc.perform(get("/api/v1/payments/{id}/events", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(outbox).getPaymentEvents(id);
    }

    @Test
    void missingPaymentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(outbox.getPaymentEvents(id)).thenThrow(new PaymentNotFoundException(id));
        mvc.perform(get("/api/v1/payments/{id}/events", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void invalidPaymentIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/payments/not-a-uuid/events")).andExpect(status().isBadRequest());
        verifyNoInteractions(outbox);
    }
}
