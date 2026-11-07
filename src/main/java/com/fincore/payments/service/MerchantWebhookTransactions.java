package com.fincore.payments.service;

import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.MerchantWebhook.DeliveryState;
import com.fincore.payments.exception.MerchantWebhookException;
import com.fincore.payments.merchantwebhook.MerchantWebhookCrypto;
import com.fincore.payments.repository.MerchantWebhookRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Optional;
import java.util.UUID;

/** Owns short database-only lease and completion transactions; never performs network I/O. */
@Service
public class MerchantWebhookTransactions {
    private final MerchantWebhookRepository repository;
    private final MerchantWebhookProperties properties;
    public MerchantWebhookTransactions(MerchantWebhookRepository repository,MerchantWebhookProperties properties) {
        this.repository=repository; this.properties=properties;
    }

    public record Claim(MerchantWebhookRepository.Stored delivery,MerchantWebhookRepository.Lease lease,String secret) { }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=10)
    public Optional<Claim> claimNext() {
        var candidate=repository.candidate();
        if (candidate.isEmpty()) return Optional.empty();
        var delivery=candidate.get();
        if (delivery.view().state()==DeliveryState.IN_FLIGHT
                && (delivery.view().cycleAttempts()>=delivery.view().maxAttempts() || delivery.view().attempts()>=1000)) {
            repository.abandon(delivery); repository.exhausted(delivery); return Optional.empty();
        }
        // An expired claim is evidence of an unconfirmed network attempt. Close that attempt
        // before creating its replacement; the partial unique index permits only one live row.
        if (delivery.view().state()==DeliveryState.IN_FLIGHT) repository.abandon(delivery);
        String secret;
        try { secret=MerchantWebhookCrypto.decrypt(properties.masterKey(),delivery.masterKeyId(),delivery.merchantId(),
                delivery.view().subscriptionId(),delivery.nonce(),delivery.ciphertext()); }
        catch (RuntimeException unavailable) {
            // Claim an auditable attempt and finish it through the normal failure path so operators
            // can see key loss; delivery remains retryable after configuration repair.
            var lease=repository.claim(delivery,properties.leaseSeconds());
            var claimed=repository.delivery(delivery.merchantId(),delivery.view().deliveryId(),false);
            repository.complete(claimed,false,null,"SIGNING_KEY_UNAVAILABLE",backoff(claimed));
            return Optional.empty();
        }
        var lease=repository.claim(delivery,properties.leaseSeconds());
        return Optional.of(new Claim(delivery,lease,secret));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=10)
    public boolean finish(Claim claim,com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient.Outcome outcome) {
        var id=claim.delivery().view().deliveryId(); var merchant=claim.delivery().merchantId();
        var current=repository.delivery(merchant,id,true);
        if (current.view().state()!=DeliveryState.IN_FLIGHT || !claim.lease().token().equals(current.token())) return false;
        long delay=backoff(current);
        repository.complete(current,outcome.acknowledged(),outcome.httpStatus(),outcome.errorCode(),delay);
        return true;
    }

    private static long backoff(MerchantWebhookRepository.Stored delivery) {
        long delay=delivery.initialBackoff();
        for (int i=1;i<delivery.view().cycleAttempts() && delay<delivery.maxBackoff();i++)
            delay=delay>delivery.maxBackoff()/2 ? delivery.maxBackoff() : Math.min(delivery.maxBackoff(),delay*2);
        return Math.min(delay,delivery.maxBackoff());
    }
}
