package com.fincore.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fincore.payments.config.StrictRoutingCounterDeserializer;
import com.fincore.payments.domain.PaymentStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class PaymentAccounting {
    private PaymentAccounting() { }
    public enum Action { CAPTURE, REFUND }
    public record Command(@NotNull UUID requestId,
            @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class) @PositiveOrZero long expectedVersion,
            @NotNull Action action,@NotNull BigDecimal amount,@NotNull BigDecimal fee,UUID captureOperationId) { }
    public record Operation(UUID operationId,UUID requestId,UUID paymentId,Action action,BigDecimal amount,BigDecimal fee,
            UUID captureOperationId,long sourceVersion,long resultingVersion,PaymentStatus resultingStatus,
            UUID principalJournalId,UUID feeJournalId,Instant createdAt) { }
    public record Snapshot(UUID paymentId,String currency,BigDecimal authorizedAmount,BigDecimal capturedAmount,
            BigDecimal refundedAmount,BigDecimal feeAmount,BigDecimal merchantPayable,BigDecimal remainingCaptureAmount,
            BigDecimal refundableAmount,PaymentStatus status,long version,boolean amountManaged) { }
    public record Result(Operation operation,Snapshot accounting,boolean replayed) { }
    public record History(List<Operation> operations) { }
}
