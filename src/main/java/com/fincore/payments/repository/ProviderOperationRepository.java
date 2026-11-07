package com.fincore.payments.repository;

import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Repository
public class ProviderOperationRepository {
    public static final String PROVIDER="SANDBOX_HTTP_V1";
    private final JdbcTemplate jdbc;
    public ProviderOperationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Source(UUID id,UUID merchantId,long version,String status,BigDecimal amount,String currency) { }
    public record Binding(UUID instanceId,String providerPaymentId) { }
    public record Stored(View view,UUID merchantId,long sourceVersion,String sourceStatus,BigDecimal amount,
            String currency,String providerPaymentIdBefore,UUID leaseToken) {
        public Request request() { return new Request(1,view.providerInstanceId(),view.operationId(),view.paymentId(),merchantId,
                view.action(),view.sandboxScenario(),amount,currency,providerPaymentIdBefore); }
    }
    public Source source(UUID merchantId,UUID paymentId,boolean lock) {
        return jdbc.query("SELECT id,merchant_id,version,status,amount,currency FROM payments WHERE merchant_id=? AND id=?"
                +(lock ? " FOR UPDATE NOWAIT" : ""),(r,n) -> new Source(r.getObject("id",UUID.class),r.getObject("merchant_id",UUID.class),
                r.getLong("version"),r.getString("status"),r.getBigDecimal("amount"),r.getString("currency")),merchantId,paymentId)
                .stream().findFirst().orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
    public Optional<Binding> binding(UUID paymentId) {
        return jdbc.query("SELECT provider_instance_id,provider_payment_id FROM payment_provider_bindings WHERE payment_id=?",
                (r,n) -> new Binding(r.getObject("provider_instance_id",UUID.class),r.getString("provider_payment_id")),paymentId).stream().findFirst();
    }
    public void bind(Source source,UUID instanceId) {
        jdbc.update("INSERT INTO payment_provider_bindings(payment_id,merchant_id,provider_name,provider_instance_id) VALUES (?,?,?,?)",
                source.id(),source.merchantId(),PROVIDER,instanceId);
    }
    public Optional<Stored> byRequest(UUID merchantId,UUID requestId) {
        return find("merchant_id=? AND request_id=?",false,merchantId,requestId);
    }
    public Optional<Stored> byId(UUID merchantId,UUID operationId,boolean lock) {
        return find("merchant_id=? AND operation_id=?",lock,merchantId,operationId);
    }
    private Optional<Stored> find(String predicate,boolean lock,Object... parameters) {
        return jdbc.query("SELECT * FROM payment_provider_operations WHERE "+predicate+(lock ? " FOR UPDATE NOWAIT" : ""),
                (r,n) -> map(r),parameters).stream().findFirst();
    }
    private Stored map(java.sql.ResultSet r) throws java.sql.SQLException {
        State state=State.valueOf(r.getString("state")); Action action=Action.valueOf(r.getString("action"));
        var lease=r.getTimestamp("lease_until");
        var view=new View(r.getObject("operation_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("payment_id",UUID.class),
                PROVIDER,r.getObject("provider_instance_id",UUID.class),action,Scenario.valueOf(r.getString("sandbox_scenario")),state,
                r.getString("outcome")==null ? null : Outcome.valueOf(r.getString("outcome")),r.getBigDecimal("amount"),r.getString("currency"),
                r.getInt("attempts"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),
                lease==null ? null : lease.toInstant(),r.getString("provider_payment_id"),r.getString("provider_operation_id"),
                r.getString("last_error_code"),state==State.APPLIED || (state==State.DECLINED && action==Action.AUTHORIZE));
        return new Stored(view,r.getObject("merchant_id",UUID.class),r.getLong("source_version"),r.getString("source_status"),
                r.getBigDecimal("amount"),r.getString("currency"),r.getString("provider_payment_id_before"),r.getObject("lease_token",UUID.class));
    }
    public boolean unfinished(UUID paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM payment_provider_operations WHERE payment_id=? AND state IN ('READY','IN_FLIGHT','UNKNOWN','REQUIRES_REVIEW'))",Boolean.class,paymentId));
    }
    public boolean insert(UUID operationId,Source source,UUID instanceId,Command command,String providerPaymentId) {
        return jdbc.update("""
                INSERT INTO payment_provider_operations(operation_id,merchant_id,request_id,payment_id,provider_instance_id,
                    action,sandbox_scenario,source_version,source_status,amount,currency,provider_payment_id_before)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (merchant_id,request_id) DO NOTHING
                """,operationId,source.merchantId(),command.requestId(),source.id(),instanceId,command.action().name(),
                command.sandboxScenario().name(),source.version(),source.status(),source.amount(),source.currency(),providerPaymentId)==1;
    }
    public boolean lease(Stored operation,UUID token,int seconds) {
        return jdbc.update("""
                UPDATE payment_provider_operations SET state='IN_FLIGHT',attempts=attempts+1,lease_token=?,
                    lease_until=clock_timestamp()+(?*INTERVAL '1 second'),last_error_code=NULL,updated_at=clock_timestamp()
                WHERE operation_id=? AND attempts<1000 AND (state IN ('READY','UNKNOWN') OR (state='IN_FLIGHT' AND lease_until<=clock_timestamp()))
                """,token,seconds,operation.view().operationId())==1;
    }
    public void unknown(UUID operationId,UUID token) {
        jdbc.update("""
                UPDATE payment_provider_operations SET state='UNKNOWN',lease_token=NULL,lease_until=NULL,
                    last_error_code='PROVIDER_OUTCOME_UNCONFIRMED',updated_at=clock_timestamp()
                WHERE operation_id=? AND state='IN_FLIGHT' AND lease_token=?
                """,operationId,token);
    }
    public void complete(Stored operation,Response response,boolean conflict) {
        boolean localEffect=response.outcome()==Outcome.APPROVED || operation.view().action()==Action.AUTHORIZE;
        String state=conflict ? "REQUIRES_REVIEW" : response.outcome()==Outcome.APPROVED ? "APPLIED" : "DECLINED";
        String error=conflict ? "LOCAL_STATE_CONFLICT" : response.outcome()==Outcome.DECLINED ? "PROVIDER_DECLINED" : null;
        jdbc.update("""
                UPDATE payment_provider_operations SET state=?,outcome=?,provider_payment_id=?,provider_operation_id=?,
                    application_xid=CASE WHEN ? THEN pg_current_xact_id()::text ELSE NULL END,
                    lease_token=NULL,lease_until=NULL,last_error_code=?,updated_at=clock_timestamp() WHERE operation_id=?
                """,state,response.outcome().name(),response.providerPaymentId(),response.providerOperationId(),
                localEffect && !conflict,error,operation.view().operationId());
        if (!conflict && response.outcome()==Outcome.APPROVED && operation.view().action()==Action.AUTHORIZE)
            jdbc.update("UPDATE payment_provider_bindings SET provider_payment_id=? WHERE payment_id=?",response.providerPaymentId(),operation.view().paymentId());
    }
    public Page page(UUID merchantId,UUID paymentId,UUID after,int limit) {
        List<Stored> rows=jdbc.query("SELECT * FROM payment_provider_operations WHERE merchant_id=? AND payment_id=?"
                +(after==null ? "" : " AND operation_id>?")+" ORDER BY operation_id LIMIT ?",(r,n) -> map(r),
                after==null ? new Object[]{merchantId,paymentId,limit+1} : new Object[]{merchantId,paymentId,after,limit+1});
        var views=rows.stream().limit(limit).map(Stored::view).toList();
        return new Page(views,rows.size()>limit ? views.getLast().operationId() : null);
    }
}
