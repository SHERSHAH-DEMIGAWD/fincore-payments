package com.fincore.payments.repository;

import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.MerchantWebhook.*;
import com.fincore.payments.exception.MerchantWebhookException;
import com.fincore.payments.merchantwebhook.MerchantWebhookDestinationPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class MerchantWebhookRepository {
    private final JdbcTemplate jdbc;
    private final MerchantWebhookProperties properties;
    public MerchantWebhookRepository(JdbcTemplate jdbc,MerchantWebhookProperties properties) { this.jdbc=jdbc; this.properties=properties; }
    public record Sub(Subscription view,UUID merchantId,UUID createRequest,String masterKeyId,byte[] nonce,byte[] ciphertext,int slot) { }
    public record Stored(Delivery view,UUID merchantId,String masterKeyId,String destinationUrl,byte[] nonce,byte[] ciphertext,byte[] body,UUID token,long initialBackoff,long maxBackoff) { }
    public record Lease(UUID token,long signedAt,Instant startedAt,Instant until) { }
    public record ChangeAudit(UUID subscription,UUID request,long expected,SubscriptionState state,String reason,long revision) { }
    public record RetryAudit(UUID delivery,UUID request,long expected,String reason,long generation) { }
    private static final String DELIVERY_SELECT="""
        SELECT d.*,s.state AS subscription_state,s.master_key_id,s.secret_nonce,s.secret_ciphertext,m.enabled AS merchant_enabled,
            (d.next_attempt_at>clock_timestamp()) AS backed_off,(d.lease_until>clock_timestamp()) AS lease_current,
            EXISTS(SELECT 1 FROM merchant_webhook_deliveries e WHERE e.subscription_id=d.subscription_id AND e.payment_id=d.payment_id
                AND e.payment_version<d.payment_version AND e.state<>'DELIVERED') AS earlier_unconfirmed
        FROM merchant_webhook_deliveries d JOIN merchant_webhook_subscriptions s ON s.subscription_id=d.subscription_id
        JOIN merchants m ON m.id=d.merchant_id
        """;

    public void lockMerchant(UUID merchant) {
        var enabled=jdbc.query("SELECT enabled FROM merchants WHERE id=? FOR UPDATE NOWAIT",(r,n) -> r.getBoolean(1),merchant);
        if (enabled.isEmpty()) missing();
        if (!enabled.getFirst()) conflict();
    }
    public Optional<Sub> byCreate(UUID merchant,UUID request) {
        return jdbc.query("SELECT * FROM merchant_webhook_subscriptions WHERE merchant_id=? AND create_request_id=?",(r,n) -> sub(r),merchant,request).stream().findFirst();
    }
    public Sub subscription(UUID merchant,UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM merchant_webhook_subscriptions WHERE merchant_id=? AND subscription_id=?"+(lock ? " FOR UPDATE NOWAIT" : ""),
                (r,n) -> sub(r),merchant,id).stream().findFirst().orElseThrow(() -> new MerchantWebhookException(404,"MERCHANT_WEBHOOK_NOT_FOUND"));
    }
    private Sub sub(ResultSet r) throws SQLException {
        UUID id=r.getObject("subscription_id",UUID.class);
        var view=new Subscription(id,r.getString("destination_url"),id.toString(),SubscriptionState.valueOf(r.getString("state")),r.getLong("revision"),
                instant(r,"created_at"),instant(r,"updated_at"),r.getInt("max_attempts"),r.getLong("initial_backoff_ms"),r.getLong("max_backoff_ms"));
        return new Sub(view,r.getObject("merchant_id",UUID.class),r.getObject("create_request_id",UUID.class),r.getString("master_key_id"),r.getBytes("secret_nonce"),r.getBytes("secret_ciphertext"),r.getInt("subscription_slot"));
    }
    public int nextSlot(UUID merchant,int maximum) {
        var slots=new HashSet<>(jdbc.queryForList("SELECT subscription_slot FROM merchant_webhook_subscriptions WHERE merchant_id=?",Integer.class,merchant));
        for (int slot=1;slot<=maximum;slot++) if (!slots.contains(slot)) return slot;
        conflict(); return 0;
    }
    public void insertSubscription(UUID merchant,UUID id,Create command,String url,int slot,byte[] nonce,byte[] ciphertext) {
        jdbc.update("""
            INSERT INTO merchant_webhook_subscriptions(subscription_id,merchant_id,create_request_id,subscription_slot,destination_url,
                master_key_id,secret_nonce,secret_ciphertext,max_attempts,initial_backoff_ms,max_backoff_ms)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,merchant,command.requestId(),slot,url,properties.masterKeyId(),nonce,ciphertext,properties.maxAttempts(),properties.initialBackoffMillis(),properties.maxBackoffMillis());
    }
    public Subscriptions subscriptions(UUID merchant,UUID after,int limit) {
        var rows=jdbc.query("SELECT * FROM merchant_webhook_subscriptions WHERE merchant_id=?"+(after==null ? "" : " AND subscription_id>?")+" ORDER BY subscription_id LIMIT ?",
                (r,n) -> sub(r).view(),after==null ? new Object[]{merchant,limit+1} : new Object[]{merchant,after,limit+1});
        var page=rows.stream().limit(limit).toList(); return new Subscriptions(page,rows.size()>limit ? page.getLast().subscriptionId() : null);
    }
    public Optional<ChangeAudit> changeAudit(UUID merchant,UUID request) {
        return jdbc.query("SELECT * FROM merchant_webhook_subscription_changes WHERE merchant_id=? AND request_id=?",(r,n) -> new ChangeAudit(
                r.getObject("subscription_id",UUID.class),request,r.getLong("expected_revision"),SubscriptionState.valueOf(r.getString("state")),r.getString("reason"),r.getLong("resulting_revision")),merchant,request).stream().findFirst();
    }
    public void change(UUID merchant,UUID subscription,Change command,String reason) {
        jdbc.update("INSERT INTO merchant_webhook_subscription_changes(merchant_id,request_id,subscription_id,expected_revision,resulting_revision,state,reason) VALUES (?,?,?,?,?,?,?)",
                merchant,command.requestId(),subscription,command.expectedRevision(),command.expectedRevision()+1,command.state().name(),reason);
        jdbc.update("UPDATE merchant_webhook_subscriptions SET state=?,revision=revision+1,updated_at=clock_timestamp() WHERE merchant_id=? AND subscription_id=?",
                command.state().name(),merchant,subscription);
    }
    public void requirePayment(UUID merchant,UUID payment) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE merchant_id=? AND id=?)",Boolean.class,merchant,payment))) missing();
    }
    public Stored delivery(UUID merchant,UUID id,boolean lock) {
        return jdbc.query(DELIVERY_SELECT+" WHERE d.merchant_id=? AND d.delivery_id=?"+(lock ? " FOR UPDATE OF d NOWAIT" : ""),(r,n) -> stored(r),merchant,id)
                .stream().findFirst().orElseThrow(() -> new MerchantWebhookException(404,"MERCHANT_WEBHOOK_NOT_FOUND"));
    }
    public Deliveries deliveries(UUID merchant,UUID subscription,UUID payment,DeliveryState state,UUID after,int limit) {
        var sql=new StringBuilder(DELIVERY_SELECT+" WHERE d.merchant_id=?"); var args=new ArrayList<Object>(); args.add(merchant);
        if (subscription!=null) { sql.append(" AND d.subscription_id=?"); args.add(subscription); }
        if (payment!=null) { sql.append(" AND d.payment_id=?"); args.add(payment); }
        if (state!=null) { sql.append(" AND d.state=?"); args.add(state.name()); }
        if (after!=null) { sql.append(" AND d.delivery_id>?"); args.add(after); }
        sql.append(" ORDER BY d.delivery_id LIMIT ?"); args.add(limit+1);
        var rows=jdbc.query(sql.toString(),(r,n) -> stored(r).view(),args.toArray());
        var page=rows.stream().limit(limit).toList(); return new Deliveries(page,rows.size()>limit ? page.getLast().deliveryId() : null);
    }
    private Stored stored(ResultSet r) throws SQLException {
        var state=DeliveryState.valueOf(r.getString("state")); String blocked=null;
        if (state!=DeliveryState.DELIVERED) {
            if (state==DeliveryState.DEAD) blocked="DEAD_REQUIRES_RETRY";
            else if (!properties.enabled()) blocked="FEATURE_DISABLED";
            else if (!r.getBoolean("merchant_enabled")) blocked="MERCHANT_DISABLED";
            else if (!r.getString("subscription_state").equals("ACTIVE")) blocked="SUBSCRIPTION_PAUSED";
            else if (!r.getString("master_key_id").equals(properties.masterKeyId())) blocked="MASTER_KEY_UNAVAILABLE";
            else if (!MerchantWebhookDestinationPolicy.allowed(r.getString("destination_url"),properties.allowedDestinations())) blocked="DESTINATION_NOT_ALLOWED";
            else if (r.getBoolean("earlier_unconfirmed")) blocked="EARLIER_DELIVERY_UNCONFIRMED";
            else if (state==DeliveryState.IN_FLIGHT) blocked=r.getBoolean("lease_current") ? "LEASED" : "LEASE_EXPIRED";
            else if (r.getBoolean("backed_off")) blocked="BACKOFF";
        }
        var view=new Delivery(r.getObject("delivery_id",UUID.class),r.getObject("subscription_id",UUID.class),r.getObject("event_id",UUID.class),
                r.getObject("payment_id",UUID.class),r.getLong("payment_version"),r.getString("event_type"),state,r.getInt("attempts"),r.getInt("cycle_attempts"),
                r.getInt("max_attempts"),r.getLong("retry_generation"),instant(r,"next_attempt_at"),instant(r,"lease_until"),instant(r,"delivered_at"),
                (Integer)r.getObject("last_http_status"),r.getString("last_error_code"),blocked,r.getString("body_sha256"),instant(r,"created_at"));
        return new Stored(view,r.getObject("merchant_id",UUID.class),r.getString("master_key_id"),r.getString("destination_url"),r.getBytes("secret_nonce"),r.getBytes("secret_ciphertext"),
                r.getBytes("body"),r.getObject("lease_token",UUID.class),r.getLong("initial_backoff_ms"),r.getLong("max_backoff_ms"));
    }
    public Optional<Stored> candidate() {
        if (!properties.enabled() || properties.allowedDestinations()==null || properties.allowedDestinations().isEmpty()) return Optional.empty();
        String urls=String.join(",",Collections.nCopies(properties.allowedDestinations().size(),"?"));
        var args=new ArrayList<Object>(); args.add(properties.masterKeyId()); args.addAll(properties.allowedDestinations());
        return jdbc.query(DELIVERY_SELECT+"""
            WHERE s.state='ACTIVE' AND m.enabled AND s.master_key_id=? AND d.destination_url IN (
            """+urls+")"+"""
              AND ((d.state='PENDING' AND d.next_attempt_at<=clock_timestamp()) OR (d.state='IN_FLIGHT' AND d.lease_until<=clock_timestamp()))
              AND NOT EXISTS(SELECT 1 FROM merchant_webhook_deliveries e WHERE e.subscription_id=d.subscription_id
                  AND e.payment_id=d.payment_id AND e.payment_version<d.payment_version AND e.state<>'DELIVERED')
            ORDER BY d.created_at,d.delivery_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED FOR SHARE OF s,m SKIP LOCKED
            """,(r,n) -> stored(r),args.toArray()).stream().findFirst();
    }
    public void abandon(Stored delivery) {
        jdbc.update("UPDATE merchant_webhook_attempts SET state='ABANDONED',finished_at=clock_timestamp(),error_code='LEASE_EXPIRED' WHERE delivery_id=? AND attempt_number=? AND state='IN_FLIGHT'",
                delivery.view().deliveryId(),delivery.view().attempts());
    }
    public void exhausted(Stored delivery) {
        jdbc.update("UPDATE merchant_webhook_deliveries SET state='DEAD',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,last_http_status=NULL,last_error_code='LEASE_EXPIRED',updated_at=clock_timestamp() WHERE delivery_id=?",delivery.view().deliveryId());
    }
    public Lease claim(Stored delivery,int seconds) {
        var timing=jdbc.query("SELECT t,extract(epoch from t)::bigint AS signed_at,t+(?*INTERVAL '1 second') AS lease_until FROM (SELECT clock_timestamp() AS t) observed",
                (r,n) -> new Lease(UUID.randomUUID(),r.getLong("signed_at"),instant(r,"t"),instant(r,"lease_until")),seconds).getFirst();
        jdbc.update("""
            UPDATE merchant_webhook_deliveries SET state='IN_FLIGHT',attempts=attempts+1,cycle_attempts=cycle_attempts+1,
                lease_token=?,lease_until=?,next_attempt_at=NULL,last_http_status=NULL,last_error_code=NULL,updated_at=clock_timestamp() WHERE delivery_id=?
            """,timing.token(),Timestamp.from(timing.until()),delivery.view().deliveryId());
        jdbc.update("""
            INSERT INTO merchant_webhook_attempts(attempt_id,merchant_id,delivery_id,attempt_number,retry_generation,lease_token,signed_at,started_at,lease_until)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),delivery.merchantId(),delivery.view().deliveryId(),delivery.view().attempts()+1,delivery.view().retryGeneration(),
                timing.token(),timing.signedAt(),Timestamp.from(timing.startedAt()),Timestamp.from(timing.until()));
        return timing;
    }
    public void complete(Stored current,boolean acknowledged,Integer status,String code,long delayMillis) {
        jdbc.update("UPDATE merchant_webhook_attempts SET state=?,finished_at=clock_timestamp(),http_status=?,error_code=? WHERE delivery_id=? AND attempt_number=? AND state='IN_FLIGHT'",
                acknowledged ? "DELIVERED" : "FAILED",status,acknowledged ? null : code,current.view().deliveryId(),current.view().attempts());
        boolean dead=!acknowledged && (current.view().cycleAttempts()>=current.view().maxAttempts() || current.view().attempts()>=1000);
        jdbc.update("""
            UPDATE merchant_webhook_deliveries SET state=?,lease_token=NULL,lease_until=NULL,
                next_attempt_at=CASE WHEN ? THEN clock_timestamp()+(?*INTERVAL '1 millisecond') ELSE NULL END,
                delivered_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,last_http_status=?,last_error_code=?,updated_at=clock_timestamp() WHERE delivery_id=?
            """,acknowledged ? "DELIVERED" : dead ? "DEAD" : "PENDING",!acknowledged && !dead,delayMillis,acknowledged,status,acknowledged ? null : code,current.view().deliveryId());
    }
    public Attempts attempts(UUID merchant,UUID delivery,int after,int limit) {
        var rows=jdbc.query("SELECT * FROM merchant_webhook_attempts WHERE merchant_id=? AND delivery_id=? AND attempt_number>? ORDER BY attempt_number LIMIT ?",(r,n) ->
                new Attempt(r.getObject("attempt_id",UUID.class),r.getInt("attempt_number"),r.getLong("retry_generation"),AttemptState.valueOf(r.getString("state")),r.getLong("signed_at"),
                        instant(r,"started_at"),instant(r,"lease_until"),instant(r,"finished_at"),(Integer)r.getObject("http_status"),r.getString("error_code")),merchant,delivery,after,limit+1);
        var page=rows.stream().limit(limit).toList(); return new Attempts(page,rows.size()>limit ? page.getLast().attemptNumber() : null);
    }
    public Optional<RetryAudit> retryAudit(UUID merchant,UUID request) {
        return jdbc.query("SELECT * FROM merchant_webhook_retries WHERE merchant_id=? AND request_id=?",(r,n) -> new RetryAudit(
                r.getObject("delivery_id",UUID.class),request,r.getLong("expected_generation"),r.getString("reason"),r.getLong("resulting_generation")),merchant,request).stream().findFirst();
    }
    public void retry(UUID merchant,UUID delivery,Retry command,String reason) {
        jdbc.update("INSERT INTO merchant_webhook_retries(merchant_id,request_id,delivery_id,expected_generation,resulting_generation,reason) VALUES (?,?,?,?,?,?)",
                merchant,command.requestId(),delivery,command.expectedGeneration(),command.expectedGeneration()+1,reason);
        jdbc.update("UPDATE merchant_webhook_deliveries SET state='PENDING',cycle_attempts=0,retry_generation=retry_generation+1,next_attempt_at=clock_timestamp(),last_http_status=NULL,last_error_code=NULL,updated_at=clock_timestamp() WHERE delivery_id=? AND merchant_id=?",delivery,merchant);
    }
    private static Instant instant(ResultSet r,String field) throws SQLException { var time=r.getTimestamp(field); return time==null ? null : time.toInstant(); }
    private static void missing() { throw new MerchantWebhookException(404,"MERCHANT_WEBHOOK_NOT_FOUND"); }
    private static void conflict() { throw new MerchantWebhookException(409,"MERCHANT_WEBHOOK_CONFLICT"); }
}
