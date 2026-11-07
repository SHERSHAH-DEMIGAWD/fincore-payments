package com.fincore.payments.service;

import com.fincore.payments.config.RiskProperties;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentRisk.*;
import com.fincore.payments.exception.IdempotencyConflictException;
import com.fincore.payments.repository.PaymentRiskRepository;
import com.fincore.payments.risk.RiskVelocity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class PaymentRiskTransactions {
    private final PaymentRiskRepository repository;
    private final RiskVelocity velocity;
    private final RiskProperties settings;
    public PaymentRiskTransactions(PaymentRiskRepository repository,RiskVelocity velocity,RiskProperties settings){this.repository=repository;this.velocity=velocity;this.settings=settings;}
    /** A separate nonfinancial transaction. Redis work never holds a payment/journal row lock. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5)
    public Decision assess(UUID merchant,String key,String fingerprint,CreatePaymentRequest request){
        repository.fence(merchant,key);
        var created=repository.existingPayment(merchant,key);
        if(created.isPresent()){
            if(!created.get().equals(fingerprint))throw new IdempotencyConflictException();
            return null; // Original financial retry remains available during Redis/policy outages.
        }
        var prior=repository.intent(merchant,key);
        if(prior.isPresent() && !prior.get().fingerprint().equals(fingerprint))throw new IdempotencyConflictException();
        var intent=prior.orElseGet(()->repository.insertIntent(merchant,key,fingerprint,request));
        var terminal=repository.terminal(merchant,intent.id());if(terminal.isPresent())return terminal.get();
        if(!request.currency().equals(settings.currency()))return repository.insertDecision(merchant,intent,Outcome.DENY,Reason.CURRENCY_UNSUPPORTED,settings,null);
        if(request.amount().compareTo(settings.maximumAmount())>0)return repository.insertDecision(merchant,intent,Outcome.DENY,Reason.AMOUNT_LIMIT,settings,null);
        RiskVelocity.Reservation reservation;
        try{reservation=java.util.Objects.requireNonNull(velocity.reserve(merchant,RiskVelocity.intentHash(key,fingerprint)));}
        catch(RuntimeException unavailable){return repository.insertDecision(merchant,intent,Outcome.UNAVAILABLE,Reason.REDIS_UNAVAILABLE,settings,null);}
        return repository.insertDecision(merchant,intent,reservation.allowed()?Outcome.ALLOW:Outcome.THROTTLED,
            reservation.allowed()?Reason.VELOCITY_ALLOWED:Reason.VELOCITY_LIMIT,settings,reservation);
    }
}
