package com.fincore.payments.controller;

import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import org.springframework.context.annotation.Import;

import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.service.MerchantPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(LedgerController.class)
@Import({ProjectionOperationsSecurityConfiguration.class,com.fincore.payments.config.MerchantSecurityConfiguration.class})
class LedgerControllerTest {
    @MockitoBean com.fincore.payments.repository.MerchantCredentialRepository credentials;
    @Autowired MockMvc mvc;
    @MockitoBean MerchantPaymentService ledger;

    @Test
    void returnsPaymentLedgerAndCurrencyBalances() throws Exception {
        UUID id = UUID.randomUUID();
        when(ledger.getPaymentLedger(id)).thenReturn(List.of());
        when(ledger.getBalances("INR")).thenReturn(List.of());
        mvc.perform(get("/api/v1/payments/{id}/ledger", id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ledger/accounts/INR/balances")).andExpect(status().isOk());
        verify(ledger).getPaymentLedger(id);
        verify(ledger).getBalances("INR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"inr", "IN", "INRR", "I1R"})
    void invalidCurrencyReturns400(String currency) throws Exception {
        mvc.perform(get("/api/v1/ledger/accounts/{currency}/balances", currency))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.currency").exists());
        verifyNoInteractions(ledger);
    }

    @Test
    void missingPaymentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(ledger.getPaymentLedger(id)).thenThrow(new PaymentNotFoundException(id));
        mvc.perform(get("/api/v1/payments/{id}/ledger", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void malformedPaymentIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/payments/not-a-uuid/ledger")).andExpect(status().isBadRequest());
        verifyNoInteractions(ledger);
    }
}
