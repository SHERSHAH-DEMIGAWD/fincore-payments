package com.fincore.payments.service;

import com.fincore.payments.config.ProjectionApprovalProperties;
import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.dto.ProjectionPreservation.*;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import com.fincore.payments.exception.ProjectionPurgePlanNotFoundException;
import com.fincore.payments.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.fincore.payments.service.ProjectionPreservationVerifier.*;

@Service
public class ProjectionPreservationService {
    private final ProjectionPreservationRepository storage;
    private final ProjectionPurgePlanRepository inventory;
    private final ProjectionPurgePlanService plans;
    private final ProjectionEvidenceRepository evidence;
    private final ProjectionRoutingRepository routes;
    private final ProjectionPreservationVerifier verifier;
    private final ProjectionApprovalProperties approvalProperties;
    public ProjectionPreservationService(ProjectionPreservationRepository storage,ProjectionPurgePlanRepository inventory,
            ProjectionPurgePlanService plans,ProjectionEvidenceRepository evidence,ProjectionRoutingRepository routes,
            ProjectionPreservationVerifier verifier,ProjectionApprovalProperties approvalProperties) {
        this.storage=storage; this.inventory=inventory; this.plans=plans; this.evidence=evidence;
        this.routes=routes; this.verifier=verifier;
        this.approvalProperties=approvalProperties;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public Result preserve(UUID planId,Command command,String actor) {
        if (command==null) throw new IllegalArgumentException("Preservation command is required.");
        validate(planId,command.requestId(),command.expectedRevision(),actor,command.reason());
        routes.fence();
        var plan=plans.scoped(planId);
        var saved=storage.find(command.requestId());
        if (saved.isPresent()) {
            var p=saved.get().metadata();
            if (!p.planId().equals(planId) || p.expectedRevision()!=command.expectedRevision()
                    || !p.actor().equals(actor) || !p.reason().equals(command.reason())) conflict("Request UUID already has different preservation intent.");
            verifier.verify(saved.get(),plan);
            return new Result(p,true);
        }
        var observed=evidence.observationTime().truncatedTo(ChronoUnit.MICROS);
        requireCurrent(plan,command.expectedRevision(),observed);
        var records=inventory.exportRecords(plan.runId(),"shadow-"+plan.runId(),MAX_EXPORT_BYTES);
        String json=verifier.encode(new Bundle(1,command.requestId(),plan,observed,actor,command.reason(),records));
        byte[] bytes=bytes(json);
        var metadata=new ProjectionPreservation(command.requestId(),planId,1,command.expectedRevision(),observed,actor,
                command.reason(),records.size(),bytes.length,sha256(bytes),"PRESERVED_IN_DATABASE_REVIEW_REQUIRED",false);
        // Completeness and row hashes must agree with the original plan before writing any copy.
        verifier.verify(new ProjectionPreservationRepository.Stored(metadata,json),plan);
        storage.insert(metadata,json);
        return new Result(storage.find(command.requestId()).orElseThrow().metadata(),false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ApprovalResult approve(UUID planId,ApprovalCommand command,String actor) {
        if (command==null || command.preservationId()==null || command.exportSha256()==null
                || !command.exportSha256().matches("[0-9a-f]{64}") || !Boolean.TRUE.equals(command.preservationAcknowledged()))
            throw new IllegalArgumentException("Approval requires a preservation, exact digest and acknowledgement.");
        validate(planId,command.requestId(),command.expectedRevision(),actor,command.reason());
        routes.fence();
        var plan=plans.scoped(planId);
        var saved=storage.approval(command.requestId());
        if (saved.isPresent()) {
            var a=saved.get();
            if (!a.planId().equals(planId) || !a.preservationId().equals(command.preservationId())
                    || a.expectedRevision()!=command.expectedRevision() || !a.exportSha256().equals(command.exportSha256())
                    || !a.actor().equals(actor) || !a.reason().equals(command.reason())) conflict("Request UUID already has different approval intent.");
            return new ApprovalResult(a,true);
        }
        var observed=evidence.observationTime().truncatedTo(ChronoUnit.MICROS);
        requireCurrent(plan,command.expectedRevision(),observed);
        var stored=scopedPreservation(command.preservationId());
        if (!stored.metadata().planId().equals(planId) || !stored.metadata().sha256().equals(command.exportSha256()))
            conflict("Approval must reference this plan's exact preserved export digest.");
        verifier.verify(stored,plan);
        storage.approve(planId,command,actor,observed,approvalProperties.validityMinutes());
        return new ApprovalResult(storage.approval(command.requestId()).orElseThrow(),false);
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ProjectionPreservation get(UUID id) { return scopedPreservation(id).metadata(); }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Download download(UUID id) {
        var stored=scopedPreservation(id);
        byte[] bytes=verifier.verify(stored,plans.scoped(stored.metadata().planId()));
        return new Download(id,stored.metadata().sha256(),bytes);
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Approval approval(UUID id) { return scopedApproval(id); }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ApprovalReview reviewApproval(UUID id) {
        var observed=evidence.observationTime();
        var approval=scopedApproval(id);
        var plan=plans.scoped(approval.planId());
        var blockers=new ArrayList<>(plans.reviewInTransaction(plan,observed).blockers());
        if (approval.validityMinutes()==null || approval.expiresAt()==null) blockers.add("APPROVAL_VALIDITY_UNSPECIFIED");
        if (observed.isBefore(approval.approvedAt())) blockers.add("APPROVAL_TIME_UNCERTAIN");
        if (approval.expiresAt()!=null && !observed.isBefore(approval.expiresAt())) blockers.add("APPROVAL_EXPIRED");
        if (storage.revoked(id)) blockers.add("APPROVAL_REVOKED");
        try {
            var stored=scopedPreservation(approval.preservationId());
            if (!stored.metadata().planId().equals(plan.requestId()) || !stored.metadata().sha256().equals(approval.exportSha256()))
                blockers.add("PRESERVATION_BINDING_CHANGED");
            verifier.verify(stored,plan);
        } catch (ProjectionPurgePlanConflictException | ProjectionPurgePlanNotFoundException e) { blockers.add("PRESERVATION_INTEGRITY_FAILED"); }
        return new ApprovalReview(id,observed,blockers.isEmpty() ? "CURRENT_APPROVAL_REVIEW_ONLY" : "STALE_APPROVAL_REPLAN_REQUIRED",
                false,List.copyOf(blockers));
    }

    private ProjectionPreservationRepository.Stored scopedPreservation(UUID id) {
        var stored=storage.find(id).orElseThrow(ProjectionPurgePlanNotFoundException::new);
        plans.scoped(stored.metadata().planId());
        return stored;
    }
    private Approval scopedApproval(UUID id) {
        var approval=storage.approval(id).orElseThrow(ProjectionPurgePlanNotFoundException::new);
        plans.scoped(approval.planId());
        return approval;
    }
    private void requireCurrent(ProjectionPurgePlan plan,long expected,java.time.Instant observed) {
        if (expected!=plan.expectedRevision() || !plans.reviewInTransaction(plan,observed).blockers().isEmpty())
            conflict("Plan is stale or its expected revision differs; create a fresh plan before new preservation or approval.");
    }
    private static void validate(UUID plan,UUID request,long revision,String actor,String reason) {
        if (plan==null || request==null || revision<0) throw new IllegalArgumentException("Bounded preservation intent is required.");
        ProjectionPurgePlanService.text(actor,100); ProjectionPurgePlanService.text(reason,500);
    }
    private static void conflict(String message) { throw new ProjectionPurgePlanConflictException(message); }
}
