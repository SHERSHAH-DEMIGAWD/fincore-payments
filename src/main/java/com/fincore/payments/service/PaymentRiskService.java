package com.fincore.payments.service;

import com.fincore.payments.config.*;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentRisk.*;
import com.fincore.payments.exception.PaymentRiskException;
import com.fincore.payments.repository.PaymentRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class PaymentRiskService {
    private final RiskProperties settings;
    private final PaymentRiskTransactions transactions;
    private final PaymentRiskRepository repository;
    public PaymentRiskService(RiskProperties settings,PaymentRiskTransactions transactions,PaymentRiskRepository repository){this.settings=settings;this.transactions=transactions;this.repository=repository;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void requireAdmission(String key,CreatePaymentRequest request){
        if(!settings.enabled())return;
        IdempotencyKey.validate(key);
        if(request==null || request.amount()==null)throw new IllegalArgumentException("Payment input is required.");
        var n=request.amount().stripTrailingZeros();
        if(n.signum()<=0 || n.scale()>2 || n.precision()-(long)n.scale()>17)throw new IllegalArgumentException("Invalid payment amount.");
        // Domain validation is completed before persisting intent or reserving Redis capacity.
        var normalized=new CreatePaymentRequest(request.merchantReference(),n.setScale(2),request.currency());
        Payment.create(MerchantContext.merchantId(),normalized.merchantReference(),normalized.amount(),normalized.currency());
        var decision=transactions.assess(MerchantContext.merchantId(),key,PaymentRequestFingerprint.of(normalized),normalized);
        if(decision==null || decision.outcome()==Outcome.ALLOW)return;
        switch(decision.outcome()){
            case DENY -> throw new PaymentRiskException(422,"PAYMENT_RISK_DECLINED","Payment creation is outside the configured risk policy.",decision.id(),null);
            case THROTTLED -> throw new PaymentRiskException(429,"PAYMENT_RATE_LIMITED","Merchant payment admission capacity is exhausted; retry the same intent after the window.",decision.id(),(decision.remainingMillis()+999)/1000);
            case UNAVAILABLE -> throw new PaymentRiskException(503,"PAYMENT_RISK_UNAVAILABLE","Payment admission is unavailable; retry the same intent.",decision.id(),1L);
            default -> throw new IllegalStateException("Unknown risk decision.");
        }
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Decision get(UUID id){return repository.get(MerchantContext.merchantId(),id).orElseThrow(()->new PaymentRiskException(404,"PAYMENT_RISK_NOT_FOUND","Risk decision was not found.",null,null));}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Page page(UUID after,int limit){if(limit<1 || limit>100)throw new PaymentRiskException(400,"INVALID_RISK_PAGE","Page limit must be between 1 and 100.",null,null);
        var rows=repository.page(MerchantContext.merchantId(),after,limit);var selected=rows.stream().limit(limit).toList();
        return new Page(selected,rows.size()>limit?selected.getLast().id():null);}
}
