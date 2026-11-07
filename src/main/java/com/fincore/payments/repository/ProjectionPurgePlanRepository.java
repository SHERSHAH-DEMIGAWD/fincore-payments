package com.fincore.payments.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.dto.ProjectionPreservation.ExportRecord;
import com.fincore.payments.dto.ProjectionPurgePlan.Artifact;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionPurgePlanRepository {
    public static final int MAX_ARTIFACTS=1000;
    private static final String EVIDENCE_SQL="""
                WITH evidence AS MATERIALIZED (
                    (SELECT 'RUN' AS kind,run_id::text AS identity,to_jsonb(t)::text AS body
                        FROM projection_rebuild_runs t WHERE run_id=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'GENERATION',generation::text,to_jsonb(t)::text
                        FROM projection_rebuild_generations t WHERE run_id=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'MANIFEST_ITEM',generation::text || '/' || payment_id::text,to_jsonb(t)::text
                        FROM projection_rebuild_items t WHERE run_id=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'RECEIPT',event_id::text,to_jsonb(t)::text
                        FROM consumed_payment_events t WHERE consumer_name=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'PROJECTION',payment_id::text,to_jsonb(t)::text
                        FROM payment_event_projections t WHERE consumer_name=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'ROUTING_DECISION',revision::text,to_jsonb(t)::text
                        FROM projection_route_audit t WHERE run_id=? LIMIT 1001)
                    UNION ALL
                    (SELECT 'DEPARTING_DECISION',revision::text,to_jsonb(t)::text
                        FROM projection_route_audit t WHERE previous_run_id=? AND action='REPLACE' LIMIT 1001)
                    LIMIT 1001
                )
                """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProjectionPurgePlanRepository(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc; this.mapper=mapper;
    }

    /** Each indexed branch and the combined result stop at cap+1. Hash only admitted, bounded rows. */
    public List<Artifact> artifacts(UUID runId,String consumer) {
        jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
        var rows=jdbc.query(EVIDENCE_SQL + """
                SELECT kind,identity,encode(sha256(convert_to(body,'UTF8')),'hex') AS fingerprint FROM evidence
                """,(r,n) -> new Artifact(r.getString("kind"),r.getString("identity"),r.getString("fingerprint")),
                runId,runId,runId,consumer,consumer,runId,runId);
        if (rows.size()>MAX_ARTIFACTS) throw new ProjectionPurgePlanConflictException(
                "Target exceeds the 1000-artifact planning budget; no partial plan was recorded.");
        return rows.stream().sorted(Comparator.comparing(Artifact::kind).thenComparing(Artifact::identity)).toList();
    }

    /** Check bounded raw bytes before transferring the artifact bodies to the application. */
    public List<ExportRecord> exportRecords(UUID runId,String consumer,int maxBytes) {
        jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
        var sizes=jdbc.queryForList(EVIDENCE_SQL + "SELECT octet_length(convert_to(body,'UTF8')) FROM evidence",
                Long.class,runId,runId,runId,consumer,consumer,runId,runId);
        if (sizes.size()>MAX_ARTIFACTS || sizes.stream().mapToLong(Long::longValue).sum()>maxBytes)
            throw new ProjectionPurgePlanConflictException("Preservation exceeds its artifact or raw-byte budget.");
        return jdbc.query(EVIDENCE_SQL + "SELECT kind,identity,body,encode(sha256(convert_to(body,'UTF8')),'hex') AS fingerprint FROM evidence",
                (r,n) -> new ExportRecord(r.getString("kind"),
                        r.getString("identity"),r.getString("fingerprint"),r.getString("body")),
                runId,runId,runId,consumer,consumer,runId,runId).stream()
                .sorted(Comparator.comparing(ExportRecord::kind)
                        .thenComparing(ExportRecord::identity)).toList();
    }

    public Optional<ProjectionPurgePlan> find(UUID requestId) {
        return jdbc.query("SELECT * FROM projection_purge_plans WHERE request_id=?",(r,n) ->
                new ProjectionPurgePlan(r.getObject("request_id",UUID.class),r.getInt("plan_version"),
                        r.getObject("run_id",UUID.class),r.getString("source_name"),r.getString("topic"),
                        r.getLong("build_generation"),r.getLong("expected_revision"),r.getLong("retirement_revision"),
                        r.getTimestamp("retired_at").toInstant(),r.getTimestamp("observed_at").toInstant(),
                        r.getInt("review_after_days"),r.getString("actor"),r.getString("reason"),
                        "PLANNED_REVIEW_REQUIRED",false,read(r.getString("preservation_requirements"),new TypeReference<List<String>>() {}),
                        read(r.getString("artifacts"),new TypeReference<List<Artifact>>() {})),requestId).stream().findFirst();
    }

    public void insert(ProjectionPurgePlan plan) {
        jdbc.update("""
                INSERT INTO projection_purge_plans(request_id,plan_version,run_id,source_name,topic,build_generation,
                    expected_revision,retirement_revision,retired_at,observed_at,review_after_days,actor,reason,
                    artifacts,preservation_requirements)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """,plan.requestId(),plan.planVersion(),plan.runId(),plan.sourceName(),plan.topic(),plan.buildGeneration(),
                plan.expectedRevision(),plan.retirementRevision(),java.sql.Timestamp.from(plan.retiredAt()),
                java.sql.Timestamp.from(plan.observedAt()),plan.reviewAfterDays(),plan.actor(),plan.reason(),
                write(plan.artifacts()),write(plan.preservationRequirements()));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot serialize planning evidence.",e); }
    }
    private <T> T read(String json,TypeReference<T> type) {
        try { return mapper.readValue(json,type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot read planning evidence.",e); }
    }
}
