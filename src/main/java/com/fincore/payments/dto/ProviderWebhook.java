package com.fincore.payments.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProviderWebhook {
    private ProviderWebhook() { }
    public enum Disposition { CONFIRMED, ALREADY_RECORDED, REQUIRES_REVIEW, UNMATCHED, REJECTED }
    public record Event(int version,UUID eventId,ProviderOperation.Response result) { }
    public record Verified(Event event,byte[] body,String keyId,long signedAt) {
        public Verified { body=body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    public record Application(Disposition disposition,String rejectionCode) { }
    public record Acknowledgement(UUID receiptId,UUID eventId,Disposition disposition,boolean duplicate) { }
    public record Receipt(UUID receiptId,UUID eventId,UUID operationId,UUID providerInstanceId,
            Disposition disposition,String rejectionCode,String bodySha256,String keyId,Instant receivedAt) { }
    public record Page(List<Receipt> receipts,UUID nextAfterReceiptId) { }
    public record Delivery(UUID eventId,boolean acknowledged,Disposition disposition) { }
}
