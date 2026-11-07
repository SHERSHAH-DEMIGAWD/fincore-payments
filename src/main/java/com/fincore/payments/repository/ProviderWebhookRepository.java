package com.fincore.payments.repository;

import com.fincore.payments.dto.ProviderWebhook.*;
import com.fincore.payments.provider.ProviderWebhookWire;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ProviderWebhookRepository {
    private final JdbcTemplate jdbc;
    public ProviderWebhookRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Stored(Receipt receipt,byte[] body) { }
    public boolean claim(UUID receiptId,Verified verified,UUID matchedOperation) {
        var e=verified.event(); var r=e.result(); byte[] body=verified.body();
        return jdbc.update("""
            INSERT INTO provider_webhook_receipts(receipt_id,provider_instance_id,event_id,operation_id,payment_id,merchant_id,
                action,outcome,amount,currency,provider_payment_id,provider_operation_id,matched_operation_id,raw_body,body_sha256,key_id,signed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (provider_instance_id,event_id) DO NOTHING
            """,receiptId,r.instanceId(),e.eventId(),r.operationId(),r.paymentId(),r.merchantId(),r.action().name(),r.outcome().name(),
                r.amount(),r.currency(),r.providerPaymentId(),r.providerOperationId(),matchedOperation,body,ProviderWebhookWire.sha256(body),verified.keyId(),verified.signedAt())==1;
    }
    public Stored find(UUID instance,UUID event) {
        return jdbc.query("SELECT * FROM provider_webhook_receipts WHERE provider_instance_id=? AND event_id=?",
                (r,n) -> new Stored(map(r),r.getBytes("raw_body")),instance,event).stream().findFirst().orElseThrow();
    }
    private Receipt map(java.sql.ResultSet r) throws java.sql.SQLException {
        String disposition=r.getString("disposition");
        return new Receipt(r.getObject("receipt_id",UUID.class),r.getObject("event_id",UUID.class),r.getObject("operation_id",UUID.class),
                r.getObject("provider_instance_id",UUID.class),disposition.equals("RECEIVED") ? null : Disposition.valueOf(disposition),
                r.getString("rejection_code"),r.getString("body_sha256"),r.getString("key_id"),r.getTimestamp("received_at").toInstant());
    }
    public void finish(UUID receipt,Application result) {
        jdbc.update("UPDATE provider_webhook_receipts SET disposition=?,rejection_code=? WHERE receipt_id=? AND disposition='RECEIVED'",
                result.disposition().name(),result.rejectionCode(),receipt);
    }
    public Page page(UUID merchant,UUID payment,UUID operation,UUID after,int limit) {
        var rows=jdbc.query("SELECT * FROM provider_webhook_receipts WHERE merchant_id=? AND payment_id=? AND matched_operation_id=?"
                +(after==null ? "" : " AND receipt_id>?")+" ORDER BY receipt_id LIMIT ?",(r,n) -> map(r),
                after==null ? new Object[]{merchant,payment,operation,limit+1} : new Object[]{merchant,payment,operation,after,limit+1});
        var items=rows.stream().limit(limit).toList();
        return new Page(items,rows.size()>limit ? items.getLast().receiptId() : null);
    }
}
