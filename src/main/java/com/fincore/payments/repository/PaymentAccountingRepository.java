package com.fincore.payments.repository;

import com.fincore.payments.dto.PaymentAccounting.*;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.exception.PaymentNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class PaymentAccountingRepository {
    private final JdbcTemplate jdbc;
    public PaymentAccountingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Totals(BigDecimal captured,BigDecimal refunded,BigDecimal fees) { }
    public void lock(UUID merchant,UUID payment) {
        if (jdbc.query("SELECT id FROM payments WHERE merchant_id=? AND id=? FOR UPDATE NOWAIT",(r,n)->r.getObject(1,UUID.class),merchant,payment).isEmpty())
            throw new PaymentNotFoundException(payment);
    }
    public Optional<Operation> byRequest(UUID merchant,UUID request) {
        return jdbc.query("SELECT * FROM payment_amount_operations WHERE merchant_id=? AND request_id=?",(r,n)->operation(r),merchant,request).stream().findFirst();
    }
    public Optional<Operation> byId(UUID merchant,UUID payment,UUID id) {
        return jdbc.query("SELECT * FROM payment_amount_operations WHERE merchant_id=? AND payment_id=? AND operation_id=?",(r,n)->operation(r),merchant,payment,id).stream().findFirst();
    }
    public List<Operation> history(UUID merchant,UUID payment) {
        return jdbc.query("SELECT * FROM payment_amount_operations WHERE merchant_id=? AND payment_id=? ORDER BY source_version LIMIT 100",(r,n)->operation(r),merchant,payment);
    }
    public Totals totals(UUID payment) {
        return jdbc.query("""
            SELECT COALESCE(sum(amount) FILTER(WHERE operation IN ('CAPTURE','AMOUNT_CAPTURE')),0) c,
                   COALESCE(sum(amount) FILTER(WHERE operation IN ('REFUND','AMOUNT_REFUND')),0) r,
                   COALESCE(sum(amount) FILTER(WHERE operation='FEE'),0) f FROM ledger_transactions WHERE payment_id=?
            """,(r,n)->totals(r),payment).getFirst();
    }
    public Totals historical(UUID payment,long version) {
        return jdbc.query("""
            SELECT COALESCE(sum(amount) FILTER(WHERE action='CAPTURE'),0) c,
                   COALESCE(sum(amount) FILTER(WHERE action='REFUND'),0) r,COALESCE(sum(fee),0) f
            FROM payment_amount_operations WHERE payment_id=? AND source_version<=?
            """,(r,n)->totals(r),payment,version).getFirst();
    }
    public BigDecimal refundedFor(UUID capture) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM payment_amount_operations WHERE capture_operation_id=?",BigDecimal.class,capture);
    }
    public int count(UUID payment) { return jdbc.queryForObject("SELECT count(*) FROM payment_amount_operations WHERE payment_id=?",Integer.class,payment); }
    public void bind(UUID merchant,UUID payment,long version,UUID operation) {
        jdbc.update("INSERT INTO payment_amount_bindings(payment_id,merchant_id,source_version,first_operation_id) VALUES(?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",payment,merchant,version,operation);
    }
    public boolean insert(UUID merchant,UUID payment,UUID id,Command command,PaymentStatus result,UUID principal,UUID fee) {
        return jdbc.update("""
            INSERT INTO payment_amount_operations(operation_id,merchant_id,payment_id,request_id,source_version,action,amount,fee,
                capture_operation_id,resulting_status,principal_journal_id,fee_journal_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(merchant_id,request_id) DO NOTHING
            """,id,merchant,payment,command.requestId(),command.expectedVersion(),command.action().name(),command.amount(),command.fee(),
                command.captureOperationId(),result.name(),principal,fee)==1;
    }
    private Totals totals(ResultSet r) throws SQLException { return new Totals(r.getBigDecimal("c").setScale(2),r.getBigDecimal("r").setScale(2),r.getBigDecimal("f").setScale(2)); }
    private Operation operation(ResultSet r) throws SQLException {
        long version=r.getLong("source_version");
        return new Operation(r.getObject("operation_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("payment_id",UUID.class),Action.valueOf(r.getString("action")),
                r.getBigDecimal("amount"),r.getBigDecimal("fee"),r.getObject("capture_operation_id",UUID.class),version,version+1,
                PaymentStatus.valueOf(r.getString("resulting_status")),r.getObject("principal_journal_id",UUID.class),r.getObject("fee_journal_id",UUID.class),r.getTimestamp("created_at").toInstant());
    }
}
