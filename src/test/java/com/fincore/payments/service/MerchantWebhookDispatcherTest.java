package com.fincore.payments.service;

import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.MerchantWebhook.DeliveryState;
import com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient;
import com.fincore.payments.repository.MerchantWebhookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantWebhookDispatcherTest {
    static final String DESTINATION="http://127.0.0.1:18082/webhooks",KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    @Mock MerchantWebhookTransactions transactions;
    @Mock MerchantWebhookHttpClient http;

    @Test void disabledDispatcherDoesNotClaimOrSend() {
        var properties=new MerchantWebhookProperties(false,false,"","local-master-v1",List.of(),5,20,2000,30,5,1000,60000);
        var dispatcher=new MerchantWebhookDispatcher(transactions,http,properties);
        assertThat(dispatcher.dispatchBatch().claimed()).isZero(); verifyNoInteractions(transactions,http);
    }

    @Test void dispatchSendsOutsideTransactionsAndFinishesUnderTheClaim() {
        var properties=new MerchantWebhookProperties(true,true,KEY,"local-master-v1",List.of(DESTINATION),5,20,2000,30,5,1000,60000);
        var claim=claim(); when(transactions.claimNext()).thenReturn(Optional.of(claim),Optional.empty());
        when(http.send(eq(DESTINATION),eq(properties.allowedDestinations()),eq(claim.secret()),eq(claim.delivery().view().subscriptionId().toString()),
                eq(claim.lease().signedAt()),eq(claim.delivery().body()),eq(properties.timeoutMillis())))
                .thenReturn(new MerchantWebhookHttpClient.Outcome(true,204,null));
        when(transactions.finish(claim,new MerchantWebhookHttpClient.Outcome(true,204,null))).thenReturn(true);
        // Outcome is a record; Mockito's value matcher accepts the equal instance at invocation.
        var dispatcher=new MerchantWebhookDispatcher(transactions,http,properties);
        var result=dispatcher.dispatchBatch();
        assertThat(result.claimed()).isEqualTo(1); assertThat(result.acknowledged()).isEqualTo(1);
        verify(transactions).finish(eq(claim),argThat(outcome -> outcome.acknowledged() && outcome.httpStatus()==204));
    }

    @Test void fencedLateCompletionIsCountedWithoutCallingItAnAcknowledgement() {
        var properties=new MerchantWebhookProperties(true,true,KEY,"local-master-v1",List.of(DESTINATION),5,1,2000,30,5,1000,60000);
        var claim=claim(); when(transactions.claimNext()).thenReturn(Optional.of(claim));
        when(http.send(anyString(),anyList(),anyString(),anyString(),anyLong(),any(byte[].class),anyInt()))
                .thenReturn(new MerchantWebhookHttpClient.Outcome(false,503,"HTTP_REJECTED"));
        when(transactions.finish(eq(claim),any())).thenReturn(false);
        var result=new MerchantWebhookDispatcher(transactions,http,properties).dispatchBatch();
        assertThat(result.claimed()).isEqualTo(1); assertThat(result.acknowledged()).isZero(); assertThat(result.abandoned()).isEqualTo(1);
    }

    private static MerchantWebhookTransactions.Claim claim() {
        UUID delivery=UUID.randomUUID(),subscription=UUID.randomUUID(),event=UUID.randomUUID(),payment=UUID.randomUUID(),merchant=UUID.randomUUID(),token=UUID.randomUUID();
        Instant now=Instant.now(); byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var view=new com.fincore.payments.dto.MerchantWebhook.Delivery(delivery,subscription,event,payment,1,"PAYMENT_AUTHORIZED",DeliveryState.IN_FLIGHT,
                1,1,5,0,now,now.plusSeconds(30),null,null,null,null,"00".repeat(32),now);
        var stored=new MerchantWebhookRepository.Stored(view,merchant,"local-master-v1",DESTINATION,new byte[12],new byte[48],body,token,1000,60000);
        var lease=new MerchantWebhookRepository.Lease(token,now.getEpochSecond(),now,now.plusSeconds(30));
        return new MerchantWebhookTransactions.Claim(stored,lease,"test-only-merchant-webhook-signing-secret");
    }
}
