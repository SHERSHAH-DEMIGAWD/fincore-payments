package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.dto.ProviderWebhook.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class ProviderWebhookService {
    private final ProviderWebhookRepository receipts;
    private final ProviderOperationRepository operations;
    private final ProviderOperationTransactions transactions;
    public ProviderWebhookService(ProviderWebhookRepository receipts,ProviderOperationRepository operations,ProviderOperationTransactions transactions) {
        this.receipts=receipts; this.operations=operations; this.transactions=transactions;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Acknowledgement accept(Verified verified) {
        var e=verified.event(); var r=e.result();
        var known=operations.byId(r.merchantId(),r.operationId(),false)
                .filter(o -> o.view().paymentId().equals(r.paymentId()) && o.view().providerInstanceId().equals(r.instanceId()));
        UUID receipt=UUID.randomUUID();
        if (!receipts.claim(receipt,verified,known.isPresent() ? r.operationId() : null)) {
            var saved=receipts.find(r.instanceId(),e.eventId());
            if (!Arrays.equals(saved.body(),verified.body())) throw new ProviderWebhookException(409,"WEBHOOK_EVENT_CONFLICT");
            return new Acknowledgement(saved.receipt().receiptId(),e.eventId(),saved.receipt().disposition(),true);
        }
        Application result=known.isEmpty() ? new Application(Disposition.UNMATCHED,"OPERATION_NOT_FOUND")
                : transactions.finishWebhook(r.merchantId(),r.paymentId(),r.operationId(),r);
        receipts.finish(receipt,result);
        return new Acknowledgement(receipt,e.eventId(),result.disposition(),false);
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Page page(UUID payment,UUID operation,UUID after,int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Webhook page limit must be 1–100.");
        UUID merchant=MerchantContext.merchantId();
        operations.source(merchant,payment,false);
        var found=operations.byId(merchant,operation,false).orElseThrow(ProviderOperationNotFoundException::new);
        if (!found.view().paymentId().equals(payment)) throw new ProviderOperationNotFoundException();
        return receipts.page(merchant,payment,operation,after,limit);
    }
}
