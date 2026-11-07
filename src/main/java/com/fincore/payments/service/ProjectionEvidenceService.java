package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.dto.ProjectionEvidenceReport;
import com.fincore.payments.dto.ProjectionEvidenceReport.TargetEvidence;
import com.fincore.payments.repository.ProjectionEvidenceRepository;
import com.fincore.payments.repository.ProjectionRoutingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectionEvidenceService {
    public static final int MAX_PAGE_SIZE=100;
    private final ProjectionEvidenceRepository evidence;
    private final ProjectionRoutingRepository routes;
    private final ProjectionOperationsProperties properties;
    private final String source,topic;

    public ProjectionEvidenceService(ProjectionEvidenceRepository evidence,ProjectionRoutingRepository routes,
            ProjectionOperationsProperties properties,KafkaEventProperties kafka,
            @Value("${fincore.kafka.source-name:primary}") String source) {
        this.evidence=evidence; this.routes=routes; this.properties=properties;
        this.source=source; this.topic=kafka.topic();
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public ProjectionEvidenceReport review(UUID afterRunId,int limit) {
        if (limit<1 || limit>MAX_PAGE_SIZE) throw new IllegalArgumentException("Evidence limit must be from 1 to 100.");
        Instant observed=evidence.observationTime();
        var route=routes.view();
        var page=evidence.page(source,topic,afterRunId,limit+1);
        boolean hasMore=page.size()>limit;
        var targets=new ArrayList<TargetEvidence>();
        for (var target:page.subList(0,Math.min(page.size(),limit))) {
            targets.add(new TargetEvidence(target.runId(),target.consumer(),target.generation(),target.lifecycle(),
                    target.createdAt(),target.retiredAt(),target.retirementRevision(),reviewState(target,observed),
                    false,evidence.inventory(target.runId(),target.consumer())));
        }
        return new ProjectionEvidenceReport(observed,source,topic,route.revision(),properties.reviewAfterDays(),
                "REVIEW_ONLY_NO_DELETION_AUTHORITY",List.of("RUN_IDENTITIES_AND_ROUTING_DECISIONS",
                    "FROZEN_MANIFESTS_AND_CHECKPOINTS","TARGET_RECEIPTS_AND_PROJECTIONS",
                    "BASELINE_SOURCE_AND_RECOVERY_EVIDENCE"),List.copyOf(targets),hasMore,
                hasMore ? targets.getLast().runId() : null);
    }

    String reviewState(ProjectionEvidenceRepository.Target target,Instant observed) {
        if (target.lifecycle().equals("MAINTAINED")) return "RETAIN_MAINTAINED";
        if (target.lifecycle().equals("ISOLATED")) return "RETAIN_ISOLATED";
        if (target.retiredAt()==null || target.retirementRevision()==null) return "RETAIN_MISSING_RETIREMENT_EVIDENCE";
        if (target.retiredAt().isBefore(target.createdAt()) || target.retiredAt().isAfter(observed))
            return "RETAIN_UNCERTAIN_RETIREMENT_TIME";
        return target.retiredAt().plus(properties.reviewAfterDays(),ChronoUnit.DAYS).isAfter(observed)
                ? "RETAIN_REVIEW_WINDOW" : "REVIEW_RETIRED_EVIDENCE";
    }
}
