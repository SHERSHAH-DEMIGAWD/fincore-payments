package com.fincore.payments.repository;

import com.fincore.payments.dto.ProjectionEvidenceReport.ArtifactInventory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ProjectionEvidenceRepository {
    private final JdbcTemplate jdbc;
    public ProjectionEvidenceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Instant observationTime() {
        return jdbc.queryForObject("SELECT statement_timestamp()",java.sql.Timestamp.class).toInstant();
    }

    public List<Target> page(String source,String topic,UUID after,int limit) {
        return targets(source,topic,after,limit,null);
    }

    public java.util.Optional<Target> target(String source,String topic,UUID runId) {
        return targets(source,topic,null,1,runId).stream().findFirst();
    }

    private List<Target> targets(String source,String topic,UUID after,int limit,UUID runId) {
        return jdbc.query("""
                SELECT r.*,CASE WHEN NOT r.routing_enrolled THEN 'ISOLATED'
                    WHEN EXISTS (SELECT 1 FROM projection_routes p WHERE p.shadow_run_id=r.run_id)
                    THEN 'MAINTAINED' ELSE 'RETIRED' END AS lifecycle,
                    retired.occurred_at AS retired_at,retired.revision AS retirement_revision
                FROM projection_rebuild_runs r
                LEFT JOIN LATERAL (
                    SELECT occurred_at,revision FROM (
                        SELECT occurred_at,revision FROM projection_route_audit
                            WHERE run_id=r.run_id AND action='RETIRE'
                        UNION ALL
                        SELECT occurred_at,revision FROM projection_route_audit
                            WHERE previous_run_id=r.run_id AND action='REPLACE'
                    ) decisions ORDER BY revision LIMIT 1
                ) retired ON true
                WHERE r.source_name=? AND r.topic=? AND (CAST(? AS uuid) IS NULL OR r.run_id>?)
                    AND (CAST(? AS uuid) IS NULL OR r.run_id=?)
                ORDER BY r.run_id LIMIT ?
                """,(r,n) -> new Target(r.getObject("run_id",UUID.class),r.getString("consumer_name"),
                r.getLong("current_generation"),r.getString("lifecycle"),r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("retired_at")==null ? null : r.getTimestamp("retired_at").toInstant(),
                r.getObject("retirement_revision",Long.class)),source,topic,after,after,runId,runId,limit);
    }

    /** Logical retained text bytes, not relation size, free disk, or reclaimable storage. */
    public ArtifactInventory inventory(UUID runId,String consumer) {
        return jdbc.queryForObject("""
                SELECT g.total AS generations,i.total AS items,c.total AS receipts,p.total AS projections,
                    a.total AS decisions,i.text_bytes+c.text_bytes AS text_bytes
                FROM (SELECT count(*) AS total FROM projection_rebuild_generations WHERE run_id=?) g
                CROSS JOIN (SELECT count(*) AS total,
                    coalesce(sum(coalesce(octet_length(original_payload),0)::numeric
                        +coalesce(octet_length(canonical_payload),0)::numeric),0) AS text_bytes
                    FROM projection_rebuild_items WHERE run_id=?) i
                CROSS JOIN (SELECT count(*) AS total,coalesce(sum(octet_length(payload)::numeric),0) AS text_bytes
                    FROM consumed_payment_events WHERE consumer_name=?) c
                CROSS JOIN (SELECT count(*) AS total FROM payment_event_projections WHERE consumer_name=?) p
                CROSS JOIN (SELECT count(*) AS total FROM projection_route_audit
                    WHERE run_id=? OR (previous_run_id=? AND action='REPLACE')) a
                """,(r,n) -> new ArtifactInventory(r.getLong("generations"),r.getLong("items"),
                r.getLong("receipts"),r.getLong("projections"),r.getLong("decisions"),
                r.getBigDecimal("text_bytes").toBigIntegerExact()),runId,runId,consumer,consumer,runId,runId);
    }

    public record Target(UUID runId,String consumer,long generation,String lifecycle,Instant createdAt,
            Instant retiredAt,Long retirementRevision) { }
}
