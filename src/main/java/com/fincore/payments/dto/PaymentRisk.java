package com.fincore.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentRisk {
    private PaymentRisk() { }
    public enum Outcome { ALLOW,DENY,THROTTLED,UNAVAILABLE }
    public enum Reason { VELOCITY_ALLOWED,AMOUNT_LIMIT,CURRENCY_UNSUPPORTED,VELOCITY_LIMIT,REDIS_UNAVAILABLE }
    public record Intent(UUID id,String fingerprint,BigDecimal amount,String currency) { }
    public record Decision(UUID id,UUID intentId,String requestKeyHash,BigDecimal amount,String currency,Outcome outcome,Reason reason,
        String policyHash,String policyCurrency,BigDecimal maximumAmount,int maxAdmissions,int windowSeconds,
        Long usedCount,Long remainingMillis,Instant observedAt) { }
    public record Page(List<Decision> decisions,UUID nextAfterDecisionId) { }
}
