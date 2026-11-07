package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.MerchantWebhook.*;
import com.fincore.payments.exception.MerchantWebhookException;
import com.fincore.payments.merchantwebhook.MerchantWebhookCrypto;
import com.fincore.payments.merchantwebhook.MerchantWebhookDestinationPolicy;
import com.fincore.payments.repository.MerchantWebhookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Authenticated tenant-facing management and inspection of merchant webhook delivery. */
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class MerchantWebhookService {
    private static final UUID ZERO=new UUID(0,0);
    private final MerchantWebhookRepository repository;
    private final MerchantWebhookProperties properties;

    public MerchantWebhookService(MerchantWebhookRepository repository,MerchantWebhookProperties properties) {
        this.repository=repository; this.properties=properties;
    }

    public Created create(Create command) {
        UUID merchant=MerchantContext.merchantId(); requireEnabled(); repository.lockMerchant(merchant);
        var prior=repository.byCreate(merchant,command.requestId());
        if (prior.isPresent()) {
            var saved=prior.get();
            if (!saved.view().destinationUrl().equals(canonical(command.destinationUrl()))) conflict();
            return new Created(saved.view(),decrypt(saved,merchant),true);
        }
        String destination=canonical(command.destinationUrl());
        if (!MerchantWebhookDestinationPolicy.allowed(destination,properties.allowedDestinations()))
            throw new MerchantWebhookException(400,"MERCHANT_WEBHOOK_DESTINATION_NOT_ALLOWED");
        var secret=MerchantWebhookCrypto.generateSecret(); UUID id=UUID.randomUUID();
        var sealed=MerchantWebhookCrypto.encrypt(properties.masterKey(),properties.masterKeyId(),merchant,id,secret);
        int slot=repository.nextSlot(merchant,properties.maxSubscriptions());
        repository.insertSubscription(merchant,id,command,destination,slot,sealed.nonce(),sealed.ciphertext());
        return new Created(repository.subscription(merchant,id,false).view(),secret,false);
    }

    @Transactional(readOnly=true)
    public Subscriptions subscriptions(UUID after,int limit) {
        validatePage(limit); return repository.subscriptions(MerchantContext.merchantId(),after,limit);
    }

    public Changed change(UUID id,Change command) {
        UUID merchant=MerchantContext.merchantId(); repository.lockMerchant(merchant);
        var prior=repository.changeAudit(merchant,command.requestId());
        if (prior.isPresent()) {
            var saved=prior.get();
            if (!saved.subscription().equals(id) || saved.expected()!=command.expectedRevision() || saved.state()!=command.state()
                    || !saved.reason().equals(command.reason().trim())) conflict();
            return new Changed(id,command.requestId(),saved.state(),saved.revision(),true);
        }
        var current=repository.subscription(merchant,id,true).view();
        if (current.revision()!=command.expectedRevision()) conflict();
        if (current.state()==command.state()) conflict();
        repository.change(merchant,id,command,command.reason().trim());
        return new Changed(id,command.requestId(),command.state(),command.expectedRevision()+1,false);
    }

    @Transactional(readOnly=true)
    public Deliveries deliveries(UUID subscription,UUID payment,DeliveryState state,UUID after,int limit) {
        validatePage(limit); UUID merchant=MerchantContext.merchantId();
        if (payment!=null) repository.requirePayment(merchant,payment);
        if (subscription!=null) repository.subscription(merchant,subscription,false);
        return repository.deliveries(merchant,subscription,payment,state,after,limit);
    }

    @Transactional(readOnly=true)
    public Attempts attempts(UUID id,int after,int limit) {
        if (after<0 || limit<1 || limit>100) throw new MerchantWebhookException(400,"INVALID_PAGE");
        UUID merchant=MerchantContext.merchantId(); repository.delivery(merchant,id,false);
        return repository.attempts(merchant,id,after,limit);
    }

    public Retried retry(UUID id,Retry command) {
        UUID merchant=MerchantContext.merchantId(); repository.lockMerchant(merchant);
        var prior=repository.retryAudit(merchant,command.requestId());
        if (prior.isPresent()) {
            var saved=prior.get();
            if (!saved.delivery().equals(id) || saved.expected()!=command.expectedGeneration()
                    || !saved.reason().equals(command.reason().trim())) conflict();
            return new Retried(id,command.requestId(),saved.generation(),true);
        }
        var current=repository.delivery(merchant,id,true).view();
        if (current.state()!=DeliveryState.DEAD || current.retryGeneration()!=command.expectedGeneration()) conflict();
        repository.retry(merchant,id,command,command.reason().trim());
        return new Retried(id,command.requestId(),command.expectedGeneration()+1,false);
    }

    private String decrypt(MerchantWebhookRepository.Sub sub,UUID merchant) {
        if (!sub.masterKeyId().equals(properties.masterKeyId()))
            throw new MerchantWebhookException(503,"MERCHANT_WEBHOOK_KEY_UNAVAILABLE");
        try { return MerchantWebhookCrypto.decrypt(properties.masterKey(),properties.masterKeyId(),merchant,
                sub.view().subscriptionId(),sub.nonce(),sub.ciphertext()); }
        catch (IllegalStateException unavailable) { throw new MerchantWebhookException(503,"MERCHANT_WEBHOOK_KEY_UNAVAILABLE"); }
    }
    private String canonical(String url) {
        try { return MerchantWebhookDestinationPolicy.canonical(url); }
        catch (IllegalArgumentException invalid) { throw new MerchantWebhookException(400,"INVALID_MERCHANT_WEBHOOK_DESTINATION"); }
    }
    private void requireEnabled() { if (!properties.enabled()) throw new MerchantWebhookException(503,"MERCHANT_WEBHOOKS_DISABLED"); }
    private static void validatePage(int limit) { if (limit<1 || limit>100) throw new MerchantWebhookException(400,"INVALID_PAGE"); }
    private static void conflict() { throw new MerchantWebhookException(409,"MERCHANT_WEBHOOK_CONFLICT"); }
}
