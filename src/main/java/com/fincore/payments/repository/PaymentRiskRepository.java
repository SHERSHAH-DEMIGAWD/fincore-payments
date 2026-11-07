package com.fincore.payments.repository;

import com.fincore.payments.config.RiskProperties;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentRisk.*;
import com.fincore.payments.exception.PaymentRiskException;
import com.fincore.payments.risk.RiskVelocity.Reservation;
import com.fincore.payments.settlement.SettlementWire;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class PaymentRiskRepository {
    private final JdbcTemplate jdbc;
    public PaymentRiskRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void fence(UUID merchant,String key){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,22))",Boolean.class,merchant+"\n"+key)))
            throw new PaymentRiskException(409,"PAYMENT_RISK_BUSY","This creation intent is being assessed; retry the same request.",null,null);
    }
    public Optional<String> existingPayment(UUID merchant,String key){return jdbc.query("SELECT request_fingerprint FROM idempotency_records WHERE merchant_id=? AND idempotency_key=?",
        (r,n)->r.getString(1),merchant,key).stream().findFirst();}
    public Optional<Intent> intent(UUID merchant,String key){return jdbc.query("SELECT id,request_fingerprint,amount,currency FROM payment_risk_intents WHERE merchant_id=? AND idempotency_key=?",
        (r,n)->new Intent(r.getObject(1,UUID.class),r.getString(2),r.getBigDecimal(3),r.getString(4)),merchant,key).stream().findFirst();}
    public Intent insertIntent(UUID merchant,String key,String fingerprint,CreatePaymentRequest request){
        var result=new Intent(UUID.randomUUID(),fingerprint,request.amount(),request.currency());
        jdbc.update("INSERT INTO payment_risk_intents(merchant_id,id,idempotency_key,request_key_hash,request_fingerprint,amount,currency) VALUES(?,?,?,?,?,?,?)",
            merchant,result.id(),key,keyHash(key),fingerprint,request.amount(),request.currency());return result;
    }
    private static final String SELECT="SELECT d.*,i.request_key_hash,i.amount AS request_amount,i.currency AS request_currency FROM payment_risk_decisions d JOIN payment_risk_intents i ON i.merchant_id=d.merchant_id AND i.id=d.intent_id ";
    private static final RowMapper<Decision> ROW=(r,n)->new Decision(r.getObject("id",UUID.class),r.getObject("intent_id",UUID.class),r.getString("request_key_hash"),
        r.getBigDecimal("request_amount"),r.getString("request_currency"),Outcome.valueOf(r.getString("outcome")),Reason.valueOf(r.getString("reason")),r.getString("policy_hash"),r.getString("policy_currency"),r.getBigDecimal("maximum_amount"),
        r.getInt("max_admissions"),r.getInt("window_seconds"),r.getObject("used_count",Long.class),r.getObject("remaining_millis",Long.class),r.getTimestamp("observed_at").toInstant());
    public Optional<Decision> terminal(UUID merchant,UUID intent){return jdbc.query(SELECT+"WHERE d.merchant_id=? AND d.intent_id=? AND d.outcome IN ('ALLOW','DENY')",ROW,merchant,intent).stream().findFirst();}
    public Decision insertDecision(UUID merchant,Intent intent,Outcome outcome,Reason reason,RiskProperties p,Reservation reservation){
        UUID id=UUID.randomUUID();jdbc.update("""
            INSERT INTO payment_risk_decisions(merchant_id,id,intent_id,outcome,reason,policy_hash,policy_currency,maximum_amount,max_admissions,window_seconds,used_count,remaining_millis)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,merchant,id,intent.id(),outcome.name(),reason.name(),p.policyHash(),p.currency(),p.maximumAmount().setScale(2),p.maxAdmissions(),p.windowSeconds(),
            reservation==null?null:reservation.usedCount(),reservation==null?null:reservation.remainingMillis());return get(merchant,id).orElseThrow();
    }
    public Optional<Decision> get(UUID merchant,UUID id){return jdbc.query(SELECT+"WHERE d.merchant_id=? AND d.id=?",ROW,merchant,id).stream().findFirst();}
    public List<Decision> page(UUID merchant,UUID after,int limit){return jdbc.query(SELECT+"WHERE d.merchant_id=? AND (?::uuid IS NULL OR d.id>?) ORDER BY d.id LIMIT ?",ROW,merchant,after,after,limit+1);}
    public static String keyHash(String key){return SettlementWire.sha256(key.getBytes(StandardCharsets.UTF_8));}
}
