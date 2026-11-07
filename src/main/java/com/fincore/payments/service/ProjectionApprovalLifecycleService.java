package com.fincore.payments.service;

import com.fincore.payments.dto.ProjectionApprovalLifecycle.*;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import com.fincore.payments.exception.ProjectionPurgePlanNotFoundException;
import com.fincore.payments.repository.ProjectionApprovalLifecycleRepository;
import com.fincore.payments.repository.ProjectionPreservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class ProjectionApprovalLifecycleService {
    private final ProjectionApprovalLifecycleRepository lifecycle;
    private final ProjectionPreservationRepository approvals;
    private final ProjectionPurgePlanService plans;
    public ProjectionApprovalLifecycleService(ProjectionApprovalLifecycleRepository lifecycle,
            ProjectionPreservationRepository approvals,ProjectionPurgePlanService plans) {
        this.lifecycle=lifecycle; this.approvals=approvals; this.plans=plans;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Result revoke(UUID approvalId,RevocationCommand command,String actor) {
        if (approvalId==null || command==null || command.requestId()==null) throw new IllegalArgumentException("Revocation identity is required.");
        ProjectionPurgePlanService.text(actor,100); ProjectionPurgePlanService.text(command.reason(),500);
        lifecycle.fence();
        scoped(approvalId);
        var saved=lifecycle.request(command.requestId());
        if (saved.isPresent()) {
            var audit=saved.get();
            if (!audit.approvalId().equals(approvalId) || !audit.actor().equals(actor) || !audit.reason().equals(command.reason()))
                throw new ProjectionPurgePlanConflictException("Request UUID already has different revocation intent.");
            return new Result(audit,true);
        }
        if (lifecycle.revocation(approvalId).isPresent()) throw new ProjectionPurgePlanConflictException("Approval was already revoked; retrieve its revocation record.");
        lifecycle.revoke(command.requestId(),approvalId,actor,command.reason());
        return new Result(lifecycle.request(command.requestId()).orElseThrow(),false);
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Revocation revocation(UUID approvalId) {
        scoped(approvalId);
        return lifecycle.revocation(approvalId).orElseThrow(ProjectionPurgePlanNotFoundException::new);
    }
    private void scoped(UUID approvalId) {
        var approval=approvals.approval(approvalId).orElseThrow(ProjectionPurgePlanNotFoundException::new);
        plans.scoped(approval.planId());
    }
}
