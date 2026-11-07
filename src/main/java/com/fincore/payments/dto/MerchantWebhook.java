package com.fincore.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fincore.payments.config.StrictRoutingCounterDeserializer;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MerchantWebhook {
    private MerchantWebhook() { }
    public enum SubscriptionState { ACTIVE, PAUSED }
    public enum DeliveryState { PENDING, IN_FLIGHT, DELIVERED, DEAD }
    public enum AttemptState { IN_FLIGHT, DELIVERED, FAILED, ABANDONED }
    public record Create(@NotNull UUID requestId,@NotBlank @Size(max=512) String destinationUrl) { }
    public record Change(@NotNull UUID requestId,@NotNull SubscriptionState state,
            @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class) @PositiveOrZero long expectedRevision,
            @NotBlank @Size(max=255) String reason) { }
    public record Retry(@NotNull UUID requestId,
            @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class) @PositiveOrZero long expectedGeneration,
            @NotBlank @Size(max=255) String reason) { }
    public record Subscription(UUID subscriptionId,String destinationUrl,String signingKeyId,SubscriptionState state,long revision,
            Instant createdAt,Instant updatedAt,int maxAttempts,long initialBackoffMillis,long maxBackoffMillis) { }
    public record Created(Subscription subscription,String signingSecret,boolean replayed) {
        @Override public String toString() { return "Created[subscription="+subscription.subscriptionId()+",secret=REDACTED,replayed="+replayed+"]"; }
    }
    public record Changed(UUID subscriptionId,UUID requestId,SubscriptionState state,long revision,boolean replayed) { }
    public record Retried(UUID deliveryId,UUID requestId,long generation,boolean replayed) { }
    public record Delivery(UUID deliveryId,UUID subscriptionId,UUID eventId,UUID paymentId,long paymentVersion,String eventType,
            DeliveryState state,int attempts,int cycleAttempts,int maxAttempts,long retryGeneration,Instant nextAttemptAt,
            Instant leaseUntil,Instant deliveredAt,Integer lastHttpStatus,String lastErrorCode,String blockedReason,
            String bodySha256,Instant createdAt) { }
    public record Attempt(UUID attemptId,int attemptNumber,long retryGeneration,AttemptState state,long signedAt,Instant startedAt,
            Instant leaseUntil,Instant finishedAt,Integer httpStatus,String errorCode) { }
    public record Subscriptions(List<Subscription> subscriptions,UUID nextAfterSubscriptionId) { }
    public record Deliveries(List<Delivery> deliveries,UUID nextAfterDeliveryId) { }
    public record Attempts(List<Attempt> attempts,Integer nextAfterAttemptNumber) { }
    public record Dispatch(int claimed,int acknowledged,int failed,int abandoned) { }
}
