package com.fincore.payments.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable command identity is distinct from its changing delivery/application progress. */
public final class ProviderOperation {
    private ProviderOperation() { }
    public enum Action { AUTHORIZE, CAPTURE, REFUND }
    public enum Scenario { APPROVE, DECLINE, TIMEOUT_AFTER_ACCEPT }
    public enum State { READY, IN_FLIGHT, UNKNOWN, APPLIED, DECLINED, REQUIRES_REVIEW }
    public enum Outcome { APPROVED, DECLINED }
    public record Command(@NotNull UUID requestId,@NotNull Action action,@NotNull Scenario sandboxScenario) { }
    public record View(UUID operationId,UUID requestId,UUID paymentId,String provider,UUID providerInstanceId,
            Action action,Scenario sandboxScenario,State state,Outcome providerOutcome,BigDecimal amount,String currency,int attempts,Instant createdAt,Instant updatedAt,
            Instant leaseUntil,String providerPaymentId,String providerOperationId,String errorCode,boolean localEffectApplied) { }
    public record Result(View operation,boolean replayed) { }
    public record Page(List<View> operations,UUID nextAfterOperationId) { }
    public record Request(int version,UUID instanceId,UUID operationId,UUID paymentId,UUID merchantId,
            Action action,Scenario sandboxScenario,BigDecimal amount,String currency,String providerPaymentId) { }
    public record Response(int version,UUID instanceId,UUID operationId,UUID paymentId,UUID merchantId,
            Action action,BigDecimal amount,String currency,Outcome outcome,String providerPaymentId,String providerOperationId) { }
}
