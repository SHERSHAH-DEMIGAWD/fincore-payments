package com.fincore.payments.service;

import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantPaymentServiceTest {
    final UUID merchant=UUID.randomUUID(),payment=UUID.randomUUID();
    final PaymentService payments=mock(PaymentService.class);
    final PaymentRepository ownership=mock(PaymentRepository.class);
    final LedgerService ledger=mock(LedgerService.class);
    final LedgerRepository journals=mock(LedgerRepository.class);
    final OutboxService outbox=mock(OutboxService.class);
    final PaymentEventConsumer consumer=mock(PaymentEventConsumer.class);
    final PaymentRiskService risk=mock(PaymentRiskService.class);
    final MerchantPaymentService service=new MerchantPaymentService(payments,ownership,ledger,journals,outbox,consumer,risk);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void authenticate() {
        var principal=new MerchantPrincipal(merchant,"merchant-test","test-hash",true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    @Test void noAuthenticationCannotEnterTheMerchantService() {
        assertThatThrownBy(() -> service.getPayment(payment)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(payments,ownership,ledger,journals,outbox,consumer);
    }
    @Test void aRoleLabelWithoutATrustedMerchantPrincipalIsInsufficient() {
        var principal=User.withUsername("forged").password("x").roles("MERCHANT").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        assertThatThrownBy(() -> service.getBalances("INR")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(journals);
    }
    @Test void creationAlwaysUsesTheAuthenticatedScope() {
        authenticate(); var request=new CreatePaymentRequest("ORDER",BigDecimal.TEN,"INR");
        service.createPayment("key",request);
        verify(payments).createPayment(merchant,"key",request);
        verify(payments,never()).createPayment(anyString(),any());
    }
    @Test void missingOrForeignOwnershipStopsAllReadsAndCommandsBeforeDelegation() {
        authenticate();
        List<Runnable> calls=List.of(() -> service.getPayment(payment),() -> service.authorizePayment(payment),
                () -> service.capturePayment(payment),() -> service.failPayment(payment),() -> service.refundPayment(payment),
                () -> service.getPaymentLedger(payment),() -> service.getPaymentEvents(payment),() -> service.getProjection(payment));
        for (var call:calls) assertThatThrownBy(call::run).isInstanceOf(PaymentNotFoundException.class);
        verify(ownership,times(8)).existsByIdAndMerchantId(payment,merchant);
        verifyNoInteractions(payments,ledger,outbox,consumer);
    }
    @Test void ownedCommandsRetainTheExistingFinancialTransactionPath() {
        authenticate(); when(ownership.existsByIdAndMerchantId(payment,merchant)).thenReturn(true);
        service.capturePayment(payment); service.refundPayment(payment);
        verify(payments).capturePayment(payment); verify(payments).refundPayment(payment);
        verifyNoInteractions(ledger,journals,outbox,consumer);
    }
    @Test void balancesNeverUseTheGlobalAggregate() {
        authenticate(); when(journals.merchantBalances(merchant,"INR")).thenReturn(List.of());
        assertThat(service.getBalances("INR")).isEmpty();
        verify(journals).merchantBalances(merchant,"INR"); verify(journals,never()).balances(anyString());
    }
    @Test void invalidCurrencyDoesNotQueryLedgerState() {
        authenticate(); assertThatThrownBy(() -> service.getBalances("inr")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(journals);
    }
}
