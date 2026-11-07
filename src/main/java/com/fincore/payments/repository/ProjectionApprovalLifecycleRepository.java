package com.fincore.payments.repository;

import com.fincore.payments.dto.ProjectionApprovalLifecycle.Revocation;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionApprovalLifecycleRepository {
    // Separate from the routing fence: withdrawing an approval requires no route/data health check.
    public static final long REVOCATION_FENCE=742190311L;
    private final JdbcTemplate jdbc;
    public ProjectionApprovalLifecycleRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void fence() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,REVOCATION_FENCE)))
            throw new ProjectionPurgePlanConflictException("Approval revocation is busy; retry the exact request.");
    }
    public Optional<Revocation> request(UUID requestId) { return find("request_id",requestId); }
    public Optional<Revocation> revocation(UUID approvalId) { return find("approval_id",approvalId); }
    private Optional<Revocation> find(String column,UUID id) {
        return jdbc.query("SELECT * FROM projection_approval_revocations WHERE "+column+"=?",(r,n) -> new Revocation(
                r.getObject("request_id",UUID.class),r.getObject("approval_id",UUID.class),r.getTimestamp("revoked_at").toInstant(),
                r.getString("actor"),r.getString("reason"),"REVOKED_NO_DELETION_AUTHORITY",false),id).stream().findFirst();
    }
    public void revoke(UUID requestId,UUID approvalId,String actor,String reason) {
        jdbc.update("INSERT INTO projection_approval_revocations(request_id,approval_id,actor,reason) VALUES (?,?,?,?)",
                requestId,approvalId,actor,reason);
    }
}
