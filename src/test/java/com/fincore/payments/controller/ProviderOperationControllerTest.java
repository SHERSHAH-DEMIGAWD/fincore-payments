package com.fincore.payments.controller;

import com.fincore.payments.config.*;
import com.fincore.payments.domain.MerchantIdentity;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.MerchantCredentialRepository;
import com.fincore.payments.service.ProviderOperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProviderOperationController.class)
@Import({ProjectionOperationsSecurityConfiguration.class,MerchantSecurityConfiguration.class})
class ProviderOperationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProviderOperationService service;
    @MockitoBean MerchantCredentialRepository credentials;
    final UUID payment=UUID.randomUUID(),request=UUID.randomUUID(),operation=UUID.randomUUID();
    String base() { return "/api/v1/payments/"+payment+"/provider-operations"; }
    String body() { return "{\"requestId\":\""+request+"\",\"action\":\"AUTHORIZE\",\"sandboxScenario\":\"APPROVE\"}"; }
    View view() { return new View(operation,request,payment,"SANDBOX_HTTP_V1",UUID.randomUUID(),Action.AUTHORIZE,Scenario.APPROVE,
            State.APPLIED,Outcome.APPROVED,BigDecimal.TEN,"INR",1,Instant.EPOCH,Instant.EPOCH,null,"sandbox-pay-"+payment,
            "sandbox-op-"+operation,null,true); }
    @Test void submitsTheExactCommandAndReportsProviderAndLocalOutcome() throws Exception {
        when(service.submit(any(),any())).thenReturn(new Result(view(),false));
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.post(base()).contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.state").value("APPLIED"))
                .andExpect(jsonPath("$.operation.providerOutcome").value("APPROVED")).andExpect(jsonPath("$.operation.localEffectApplied").value(true));
        verify(service).submit(payment,new Command(request,Action.AUTHORIZE,Scenario.APPROVE));
    }
    @ParameterizedTest @ValueSource(strings={"requestId","action","sandboxScenario"})
    void missingCommandFieldsCannotCreateWork(String field) throws Exception {
        String input=body().replaceAll("\\\""+field+"\\\":\\\"[^\\\"]*\\\",?","").replace(",}","}");
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.post(base()).contentType(APPLICATION_JSON).content(input))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"AUTHORIZE:invalid","APPROVE:live-bank"})
    void unsupportedEnumsAreRefused(String replacement) throws Exception {
        var pair=replacement.split(":");
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.post(base()).contentType(APPLICATION_JSON).content(body().replace(pair[0],pair[1])))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test void readsOneOwnedOperation() throws Exception {
        when(service.get(payment,operation)).thenReturn(view());
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.get(base()+"/"+operation)).andExpect(status().isOk());
        verify(service).get(payment,operation);
    }
    @Test void listingUsesABoundedDefaultAndOptionalCursor() throws Exception {
        when(service.page(any(),any(),anyInt())).thenReturn(new Page(List.of(),null));
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.get(base())).andExpect(status().isOk());
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.get(base()).param("afterOperationId",operation.toString()).param("limit","100"))
                .andExpect(status().isOk());
        verify(service).page(payment,null,20); verify(service).page(payment,operation,100);
    }
    @ParameterizedTest @ValueSource(strings={"0","101","bad"})
    void invalidPageLimitsDoNotReachTheService(String limit) throws Exception {
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.get(base()).param("limit",limit)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void anonymousReadAndMissingCsrfWriteAreDenied() throws Exception {
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        var principal=new MerchantPrincipal(MerchantIdentity.LEGACY_ID,"fixture","unused",true);
        mvc.perform(post(base()).with(user(principal)).contentType(APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void unknownOrForeignPaymentUsesTheExistingMissingPaymentContract() throws Exception {
        when(service.submit(any(),any())).thenThrow(new PaymentNotFoundException(payment));
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.post(base()).contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }
    @Test void absentOperationHasItsOwnMissingOperationContract() throws Exception {
        when(service.get(payment,operation)).thenThrow(new ProviderOperationNotFoundException());
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.get(base()+"/"+operation))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PROVIDER_OPERATION_NOT_FOUND"));
    }
    @Test void disabledBusyAndConflictingIntentUseAConflictResponse() throws Exception {
        when(service.submit(any(),any())).thenThrow(new ProviderOperationConflictException("Retry the original provider request."));
        mvc.perform(com.fincore.payments.support.MerchantMvcRequests.post(base()).contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROVIDER_OPERATION_CONFLICT"));
    }
}
