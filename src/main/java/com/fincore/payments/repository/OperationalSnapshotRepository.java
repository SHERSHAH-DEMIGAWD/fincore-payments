package com.fincore.payments.repository;

import com.fincore.payments.observability.Backlog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Repository
@ConditionalOnProperty(name="fincore.observability.enabled",havingValue="true")
public class OperationalSnapshotRepository {
    private final JdbcTemplate jdbc;
    public OperationalSnapshotRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** Global counts only; never reads financial principal, payloads, credentials or raw event data. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=5)
    public Map<Backlog,Backlog.Point> snapshot(){
        var result=new EnumMap<Backlog,Backlog.Point>(Backlog.class);
        jdbc.query(connection->{var statement=connection.prepareStatement("""
            WITH facts AS (
                SELECT CASE status WHEN 'PENDING' THEN 'OUTBOX_PENDING' ELSE 'OUTBOX_DEAD' END AS kind,occurred_at AS since
                    FROM payment_outbox WHERE status IN ('PENDING','DEAD')
                UNION ALL SELECT CASE state WHEN 'UNKNOWN' THEN 'PROVIDER_UNKNOWN' ELSE 'PROVIDER_REVIEW' END,created_at
                    FROM payment_provider_operations WHERE state IN ('UNKNOWN','REQUIRES_REVIEW')
                UNION ALL SELECT 'PROVIDER_EXPIRED',lease_until FROM payment_provider_operations WHERE state='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP
                UNION ALL SELECT CASE state WHEN 'PENDING' THEN 'WEBHOOK_PENDING' ELSE 'WEBHOOK_DEAD' END,created_at
                    FROM merchant_webhook_deliveries WHERE state IN ('PENDING','DEAD')
                UNION ALL SELECT 'WEBHOOK_EXPIRED',lease_until FROM merchant_webhook_deliveries WHERE state='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP
                UNION ALL SELECT 'CONSUMER_UNRESOLVED',first_failed_at FROM consumer_event_failures WHERE resolved_at IS NULL
                UNION ALL SELECT 'SETTLEMENT_UNRESOLVED',r.observed_at FROM settlement_reports r WHERE NOT r.matched
                    AND NOT EXISTS(SELECT 1 FROM settlement_resolutions z WHERE z.merchant_id=r.merchant_id AND z.discrepant_report_id=r.report_id)
            )
            SELECT kind,count(*) AS items,greatest(0,extract(epoch FROM CURRENT_TIMESTAMP-min(since)))::double precision AS age
            FROM facts GROUP BY kind
            """);statement.setQueryTimeout(2);return statement;},rs->{
                while(rs.next())result.put(Backlog.valueOf(rs.getString("kind")),new Backlog.Point(rs.getLong("items"),rs.getDouble("age")));
                return null;
            });
        for(var kind:Backlog.values())result.putIfAbsent(kind,new Backlog.Point(0,0));
        return Map.copyOf(result);
    }
}
