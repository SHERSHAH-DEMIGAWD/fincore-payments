package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.repository.ProjectionEvidenceRepository;
import com.fincore.payments.repository.ProjectionRoutingRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectionEvidenceServiceTest {
    private final ProjectionEvidenceRepository evidence=mock(ProjectionEvidenceRepository.class);
    private final ProjectionRoutingRepository routes=mock(ProjectionRoutingRepository.class);
    private final ProjectionEvidenceService service=new ProjectionEvidenceService(evidence,routes,
            new ProjectionOperationsProperties(30,100000),new KafkaEventProperties("payment-events.v1","test-group",15000),"primary");
    private final Instant observed=Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void reviewAgeStartsAtRetirementAndIncludesTheExactBoundary() {
        var created=observed.minus(100,ChronoUnit.DAYS);
        assertThat(service.reviewState(target("RETIRED",created,observed.minus(30,ChronoUnit.DAYS).plusNanos(1),3L),observed))
                .isEqualTo("RETAIN_REVIEW_WINDOW");
        assertThat(service.reviewState(target("RETIRED",created,observed.minus(30,ChronoUnit.DAYS),3L),observed))
                .isEqualTo("REVIEW_RETIRED_EVIDENCE");
    }

    @Test
    void oldCreationTimeDoesNotMakeActiveOrIsolatedEvidenceAReviewCandidate() {
        var old=observed.minus(100,ChronoUnit.DAYS);
        assertThat(service.reviewState(target("MAINTAINED",old,null,null),observed)).isEqualTo("RETAIN_MAINTAINED");
        assertThat(service.reviewState(target("ISOLATED",old,null,null),observed)).isEqualTo("RETAIN_ISOLATED");
        assertThat(service.reviewState(target("RETIRED",old,observed.minus(1,ChronoUnit.DAYS),3L),observed))
                .isEqualTo("RETAIN_REVIEW_WINDOW");
    }

    @Test
    void missingOrUncertainRetirementTimesAreRetainedConservatively() {
        var created=observed.minus(50,ChronoUnit.DAYS);
        assertThat(service.reviewState(target("RETIRED",created,null,null),observed))
                .isEqualTo("RETAIN_MISSING_RETIREMENT_EVIDENCE");
        assertThat(service.reviewState(target("RETIRED",created,observed.plusSeconds(1),3L),observed))
                .isEqualTo("RETAIN_UNCERTAIN_RETIREMENT_TIME");
        assertThat(service.reviewState(target("RETIRED",created,created.minusSeconds(1),3L),observed))
                .isEqualTo("RETAIN_UNCERTAIN_RETIREMENT_TIME");
    }

    @Test
    void invalidPageLimitsFailBeforeDatabaseWork() {
        assertThatThrownBy(() -> service.review(null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.review(null,101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evidence,routes);
    }

    private ProjectionEvidenceRepository.Target target(String lifecycle,Instant created,Instant retired,Long revision) {
        UUID run=UUID.randomUUID();
        return new ProjectionEvidenceRepository.Target(run,"shadow-" + run,0,lifecycle,created,retired,revision);
    }
}
