package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.dto.ProjectionPurgePlanCommand;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import com.fincore.payments.exception.ProjectionPurgePlanNotFoundException;
import com.fincore.payments.repository.ProjectionEvidenceRepository;
import com.fincore.payments.repository.ProjectionPurgePlanRepository;
import com.fincore.payments.repository.ProjectionRoutingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectionPurgePlanService {
    public static final List<String> PRESERVATION=List.of("KEEP_RUN_IDENTITIES_AND_ROUTING_DECISIONS",
            "PRESERVE_FROZEN_MANIFESTS_AND_CHECKPOINTS_BEFORE_ANY_FUTURE_PURGE",
            "PRESERVE_TARGET_RECEIPTS_AND_PROJECTIONS_BEFORE_ANY_FUTURE_PURGE",
            "KEEP_BASELINE_SOURCE_LEDGER_AND_RECOVERY_EVIDENCE_OUT_OF_SCOPE");
    private final ProjectionPurgePlanRepository plans;
    private final ProjectionEvidenceRepository evidence;
    private final ProjectionEvidenceService policy;
    private final ProjectionRoutingRepository routes;
    private final ProjectionOperationsProperties properties;
    private final String source,topic;

    public ProjectionPurgePlanService(ProjectionPurgePlanRepository plans,ProjectionEvidenceRepository evidence,
            ProjectionEvidenceService policy,ProjectionRoutingRepository routes,ProjectionOperationsProperties properties,
            KafkaEventProperties kafka,@Value("${fincore.kafka.source-name:primary}") String source) {
        this.plans=plans; this.evidence=evidence; this.policy=policy; this.routes=routes;
        this.properties=properties; this.source=source; this.topic=kafka.topic();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ProjectionPurgePlan.Result create(ProjectionPurgePlanCommand command,String actor) {
        if (command==null || command.requestId()==null || command.runId()==null || command.expectedRevision()<0)
            throw new IllegalArgumentException("Plan requires a request, target and revision.");
        text(actor,100); text(command.reason(),500);
        routes.fence();
        var saved=plans.find(command.requestId());
        if (saved.isPresent()) {
            var plan=saved.get();
            if (!plan.runId().equals(command.runId()) || plan.expectedRevision()!=command.expectedRevision()
                    || !plan.actor().equals(actor) || !plan.reason().equals(command.reason())
                    || !plan.sourceName().equals(source) || !plan.topic().equals(topic)) conflict("Request UUID already has different planning intent.");
            return new ProjectionPurgePlan.Result(plan,true);
        }
        var observed=evidence.observationTime();
        var target=evidence.target(source,topic,command.runId()).orElseThrow(ProjectionPurgePlanNotFoundException::new);
        if (routes.view().revision()!=command.expectedRevision()) conflict("Route revision changed; inspect the current route.");
        if (!policy.reviewState(target,observed).equals("REVIEW_RETIRED_EVIDENCE"))
            conflict("Planning requires a retired target past the configured review window.");
        var artifacts=plans.artifacts(target.runId(),target.consumer());
        var plan=new ProjectionPurgePlan(command.requestId(),1,target.runId(),source,topic,target.generation(),
                command.expectedRevision(),target.retirementRevision(),target.retiredAt(),observed,properties.reviewAfterDays(),
                actor,command.reason(),"PLANNED_REVIEW_REQUIRED",false,PRESERVATION,artifacts);
        plans.insert(plan);
        // Return database-normalized timestamps so a lost-response retry has the identical saved result.
        return new ProjectionPurgePlan.Result(plans.find(command.requestId()).orElseThrow(),false);
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ProjectionPurgePlan get(UUID requestId) { return scoped(requestId); }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ProjectionPurgePlan.Review review(UUID requestId) {
        var observed=evidence.observationTime();
        var plan=scoped(requestId);
        return reviewInTransaction(plan,observed);
    }

    ProjectionPurgePlan.Review reviewInTransaction(ProjectionPurgePlan plan,java.time.Instant observed) {
        var blockers=new ArrayList<String>();
        if (routes.view().revision()!=plan.expectedRevision()) blockers.add("ROUTE_REVISION_CHANGED");
        if (properties.reviewAfterDays()!=plan.reviewAfterDays()) blockers.add("REVIEW_POLICY_CHANGED");
        var target=evidence.target(source,topic,plan.runId());
        if (target.isEmpty()) blockers.add("TARGET_MISSING");
        else {
            var now=target.get();
            if (!policy.reviewState(now,observed).equals("REVIEW_RETIRED_EVIDENCE")) blockers.add("TARGET_NOT_REVIEWABLE");
            if (now.generation()!=plan.buildGeneration() || !java.util.Objects.equals(now.retirementRevision(),plan.retirementRevision())
                    || !java.util.Objects.equals(now.retiredAt(),plan.retiredAt())) blockers.add("RETIREMENT_EVIDENCE_CHANGED");
            try {
                if (!plans.artifacts(now.runId(),now.consumer()).equals(plan.artifacts())) blockers.add("ARTIFACTS_CHANGED");
            } catch (ProjectionPurgePlanConflictException oversized) { blockers.add("ARTIFACT_BUDGET_EXCEEDED"); }
        }
        return new ProjectionPurgePlan.Review(plan.requestId(),observed,blockers.isEmpty() ? "CURRENT_REVIEW_ONLY" : "STALE_REPLAN_REQUIRED",
                false,List.copyOf(blockers),plan.preservationRequirements());
    }

    ProjectionPurgePlan scoped(UUID id) {
        var plan=plans.find(id).orElseThrow(ProjectionPurgePlanNotFoundException::new);
        if (!plan.sourceName().equals(source) || !plan.topic().equals(topic)) throw new ProjectionPurgePlanNotFoundException();
        return plan;
    }
    static void text(String value,int max) {
        if (value==null || value.isBlank() || value.length()>max || value.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c)==Character.LINE_SEPARATOR
                        || Character.getType(c)==Character.PARAGRAPH_SEPARATOR)) throw new IllegalArgumentException("Plan requires bounded, single-line attribution.");
    }
    private static void conflict(String message) { throw new ProjectionPurgePlanConflictException(message); }
}
