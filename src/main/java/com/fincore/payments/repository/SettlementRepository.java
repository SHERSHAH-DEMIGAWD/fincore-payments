package com.fincore.payments.repository;

import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.exception.SettlementException;
import com.fincore.payments.settlement.SettlementWire;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class SettlementRepository {
    private final JdbcTemplate jdbc;
    public SettlementRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Stored(Metadata metadata,String body,String keyId,String signature) { }
    public record Local(UUID operationId,UUID paymentId,Action action,BigDecimal amount,String currency,long sourceVersion,
            String state,String outcome,UUID journalId,BigDecimal journalAmount,String journalCurrency) { }
    public void fence(UUID merchant) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?::text,21))",Boolean.class,merchant.toString())))
            throw new SettlementException(409,"Another settlement evidence command is in progress; retry the same intent.");
    }
    public Instant observationTime(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Metadata metadata(ResultSet r,int n)throws SQLException {
        return new Metadata(r.getObject("statement_id",UUID.class),r.getObject("provider_instance_id",UUID.class),r.getString("currency"),
                r.getLong("through_sequence"),r.getInt("line_count"),r.getBigDecimal("captured"),r.getBigDecimal("refunded"),r.getBigDecimal("fees"),
                r.getBigDecimal("net"),r.getString("body_sha256"),r.getTimestamp("imported_at").toInstant());
    }
    public Optional<Stored> stored(UUID merchant,UUID id){return jdbc.query("SELECT * FROM settlement_statements WHERE merchant_id=? AND statement_id=?",
            (r,n)->new Stored(metadata(r,n),r.getString("body"),r.getString("key_id"),r.getString("signature")),merchant,id).stream().findFirst();}
    public void insert(UUID merchant,Statement s,byte[] bytes,String keyId,String signature){jdbc.update("""
        INSERT INTO settlement_statements(statement_id,merchant_id,provider_instance_id,currency,through_sequence,line_count,
            captured,refunded,fees,net,body,body_sha256,key_id,signature) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,s.statementId(),merchant,s.instanceId(),s.currency(),s.throughSequence(),s.lines().size(),s.captured().setScale(2),s.refunded().setScale(2),
                s.fees().setScale(2),s.net().setScale(2),new String(bytes,java.nio.charset.StandardCharsets.UTF_8),SettlementWire.sha256(bytes),keyId,signature);}
    public List<Metadata> statements(UUID merchant,UUID after,int limit){return jdbc.query("""
        SELECT statement_id,provider_instance_id,currency,through_sequence,line_count,captured,refunded,fees,net,body_sha256,imported_at
        FROM settlement_statements WHERE merchant_id=? AND (?::uuid IS NULL OR statement_id>?) ORDER BY statement_id LIMIT ?
        """,SettlementRepository::metadata,merchant,after,after,limit+1);}
    public List<Local> local(UUID merchant,UUID instance,String currency){return jdbc.query("""
        SELECT o.operation_id,o.payment_id,o.action,o.amount,o.currency,o.source_version,o.state,o.outcome,
            j.id AS journal_id,j.amount AS journal_amount,j.currency AS journal_currency
        FROM payment_provider_operations o LEFT JOIN ledger_transactions j ON j.payment_id=o.payment_id
            AND j.operation=o.action AND j.source='LIVE' AND o.state='APPLIED' AND o.outcome='APPROVED'
        WHERE o.merchant_id=? AND o.provider_instance_id=? AND o.currency=? AND o.action IN ('CAPTURE','REFUND')
        ORDER BY o.operation_id LIMIT 1001
        """,(r,n)->new Local(r.getObject("operation_id",UUID.class),r.getObject("payment_id",UUID.class),Action.valueOf(r.getString("action")),
            r.getBigDecimal("amount"),r.getString("currency"),r.getLong("source_version"),r.getString("state"),r.getString("outcome"),
            r.getObject("journal_id",UUID.class),r.getBigDecimal("journal_amount"),r.getString("journal_currency")),merchant,instance,currency);}
    public Optional<Report> report(UUID merchant,UUID id){return jdbc.query("SELECT report::text FROM settlement_reports WHERE merchant_id=? AND report_id=?",
            (r,n)->read(r.getString(1),Report.class),merchant,id).stream().findFirst();}
    public void insertReport(UUID merchant,Report report){String json=encode(report);
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2097152)throw new SettlementException(409,"Report byte budget exceeded.");
        jdbc.update("INSERT INTO settlement_reports(merchant_id,report_id,statement_id,observed_at,local_fingerprint,matched,discrepancy_count,report) VALUES(?,?,?,?,?,?,?,?::jsonb)",
            merchant,report.reportId(),report.statementId(),Timestamp.from(report.observedAt()),report.localFingerprint(),report.comparison().matched(),report.comparison().discrepancyCount(),json);
    }
    public List<ReportSummary> reports(UUID merchant,UUID statement,UUID after,int limit){return jdbc.query("""
        SELECT report_id,statement_id,observed_at,matched,discrepancy_count FROM settlement_reports
        WHERE merchant_id=? AND statement_id=? AND (?::uuid IS NULL OR report_id>?) ORDER BY report_id LIMIT ?
        """,(r,n)->new ReportSummary(r.getObject("report_id",UUID.class),statement,r.getTimestamp("observed_at").toInstant(),r.getBoolean("matched"),r.getInt("discrepancy_count")),merchant,statement,after,after,limit+1);}
    private static final RowMapper<Resolution> RESOLUTION=(r,n)->new Resolution(r.getObject("request_id",UUID.class),r.getObject("statement_id",UUID.class),
        r.getObject("discrepant_report_id",UUID.class),r.getObject("matched_report_id",UUID.class),r.getTimestamp("observed_at").toInstant(),
        r.getString("actor"),r.getString("reason"),r.getString("local_fingerprint"));
    public Optional<Resolution> resolutionRequest(UUID merchant,UUID request){return jdbc.query("SELECT * FROM settlement_resolutions WHERE merchant_id=? AND request_id=?",RESOLUTION,merchant,request).stream().findFirst();}
    public Optional<Resolution> resolution(UUID merchant,UUID report){return jdbc.query("SELECT * FROM settlement_resolutions WHERE merchant_id=? AND discrepant_report_id=?",RESOLUTION,merchant,report).stream().findFirst();}
    public void insertResolution(UUID merchant,Resolution r){jdbc.update("""
        INSERT INTO settlement_resolutions(merchant_id,request_id,statement_id,discrepant_report_id,matched_report_id,observed_at,actor,reason,local_fingerprint)
        VALUES(?,?,?,?,?,?,?,?,?)
        """,merchant,r.requestId(),r.statementId(),r.discrepantReportId(),r.matchedReportId(),Timestamp.from(r.observedAt()),r.actor(),r.reason(),r.localFingerprint());}
    public static String encode(Object value){try{return SettlementWire.mapper().writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("Settlement evidence cannot be encoded.",failure);}}
    private static <T>T read(String value,Class<T> type){try{return SettlementWire.mapper().readValue(value,type);}catch(Exception failure){throw new IllegalStateException("Settlement evidence cannot be read.",failure);}}
}
