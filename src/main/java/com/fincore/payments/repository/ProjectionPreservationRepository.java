package com.fincore.payments.repository;

import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPreservation.Approval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionPreservationRepository {
    private final JdbcTemplate jdbc;
    public ProjectionPreservationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Stored(ProjectionPreservation metadata,String json) { }

    public Optional<Stored> find(UUID id) {
        return jdbc.query("SELECT * FROM projection_preservations WHERE request_id=?",(r,n) -> new Stored(
                new ProjectionPreservation(r.getObject("request_id",UUID.class),r.getObject("plan_id",UUID.class),
                        r.getInt("format_version"),r.getLong("expected_revision"),r.getTimestamp("preserved_at").toInstant(),
                        r.getString("actor"),r.getString("reason"),r.getInt("artifact_count"),r.getLong("export_bytes"),
                        r.getString("sha256"),"PRESERVED_IN_DATABASE_REVIEW_REQUIRED",false),r.getString("export_json")),id)
                .stream().findFirst();
    }
    public void insert(ProjectionPreservation p,String json) {
        jdbc.update("""
                INSERT INTO projection_preservations(request_id,plan_id,format_version,expected_revision,preserved_at,
                    actor,reason,artifact_count,export_bytes,sha256,export_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,p.requestId(),p.planId(),p.formatVersion(),p.expectedRevision(),java.sql.Timestamp.from(p.preservedAt()),
                p.actor(),p.reason(),p.artifactCount(),p.exportBytes(),p.sha256(),json);
    }
    public Optional<Approval> approval(UUID id) {
        return jdbc.query("SELECT * FROM projection_preservation_approvals WHERE request_id=?",(r,n) -> new Approval(
                r.getObject("request_id",UUID.class),r.getObject("plan_id",UUID.class),r.getObject("preservation_id",UUID.class),
                r.getLong("expected_revision"),r.getString("export_sha256"),r.getBoolean("preservation_acknowledged"),
                r.getTimestamp("approved_at").toInstant(),r.getString("actor"),r.getString("reason"),
                "PRESERVATION_REVIEW_APPROVED_NO_DELETION_AUTHORITY",false,r.getObject("validity_minutes",Integer.class),
                r.getTimestamp("expires_at")==null ? null : r.getTimestamp("expires_at").toInstant()),id).stream().findFirst();
    }
    public void approve(UUID planId,ProjectionPreservation.ApprovalCommand command,String actor,
            java.time.Instant observed,int validityMinutes) {
        jdbc.update("""
                INSERT INTO projection_preservation_approvals(request_id,plan_id,preservation_id,expected_revision,
                    export_sha256,preservation_acknowledged,actor,reason,approved_at,validity_minutes,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,command.requestId(),planId,command.preservationId(),command.expectedRevision(),command.exportSha256(),
                command.preservationAcknowledged(),actor,command.reason(),java.sql.Timestamp.from(observed),validityMinutes,
                java.sql.Timestamp.from(observed.plus(validityMinutes,java.time.temporal.ChronoUnit.MINUTES)));
    }
    public boolean revoked(UUID approvalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM projection_approval_revocations WHERE approval_id=?)",
                Boolean.class,approvalId));
    }
}
