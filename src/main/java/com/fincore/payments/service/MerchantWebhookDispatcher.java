package com.fincore.payments.service;

import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.MerchantWebhook.Dispatch;
import com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MerchantWebhookDispatcher {
    private final MerchantWebhookTransactions transactions;
    private final MerchantWebhookHttpClient http;
    private final MerchantWebhookProperties properties;
    public MerchantWebhookDispatcher(MerchantWebhookTransactions transactions,MerchantWebhookHttpClient http,MerchantWebhookProperties properties) {
        this.transactions=transactions; this.http=http; this.properties=properties;
    }
    public Dispatch dispatchBatch() {
        if (!properties.dispatchEnabled()) return new Dispatch(0,0,0,0);
        int claimed=0,acknowledged=0,failed=0,abandoned=0;
        for (int i=0;i<properties.batchSize();i++) {
            var next=transactions.claimNext(); if (next.isEmpty()) break;
            var claim=next.get(); claimed++;
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Merchant webhook network call must run outside database transactions.");
            var delivery=claim.delivery();
            var outcome=http.send(delivery.destinationUrl(),properties.allowedDestinations(),claim.secret(),
                    delivery.view().subscriptionId().toString(),claim.lease().signedAt(),delivery.body(),properties.timeoutMillis());
            boolean completed=transactions.finish(claim,outcome);
            if (completed && outcome.acknowledged()) acknowledged++;
            else if (completed) failed++;
            else abandoned++;
        }
        return new Dispatch(claimed,acknowledged,failed,abandoned);
    }
}
