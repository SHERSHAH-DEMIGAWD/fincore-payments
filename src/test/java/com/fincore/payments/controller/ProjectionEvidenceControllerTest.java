package com.fincore.payments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProjectionOperationsConfiguration;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.dto.*;
import com.fincore.payments.exception.ProjectionComparisonBudgetException;
import com.fincore.payments.service.ProjectionEvidenceService;
import com.fincore.payments.service.ProjectionRoutingMetrics;
import com.fincore.payments.service.ProjectionRoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ProjectionEvidenceController.class,ProjectionRoutingController.class})
@Import({ProjectionOperationsSecurityConfiguration.class,ProjectionOperationsConfiguration.class,ProjectionRoutingMetrics.class})
class ProjectionEvidenceControllerTest {
    private static final String BASE="/api/v1/operations/projection-routing";
    private static final String PASSWORD="test-only-evidence-password";
    private static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProjectionRoutingMetrics metrics;
    @MockitoBean ProjectionEvidenceService evidence;
    @MockitoBean ProjectionRoutingService routing;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("fincore.operations.username",() -> "operator");
        properties.add("fincore.operations.password-hash",() -> HASH);
        properties.add("fincore.projection-operations.max-comparison-rows",() -> "5000");
        properties.add("fincore.projection-operations.review-after-days",() -> "45");
    }

    @ParameterizedTest
    @ValueSource(strings={"evidence","operating-limits"})
    void diagnosticsRequireAuthenticationAndTheOperatorRole(String path) throws Exception {
        mvc.perform(get(BASE + "/" + path)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + path).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        verifyNoInteractions(evidence,routing);
    }

    @Test
    void evidenceDefaultsAndExplicitCursorArePassedToTheReadOnlyService() throws Exception {
        var report=new ProjectionEvidenceReport(Instant.parse("2026-10-23T12:00:00Z"),"primary","payment-events.v1",3,
                45,"REVIEW_ONLY_NO_DELETION_AUTHORITY",List.of("RUN_IDENTITIES_AND_ROUTING_DECISIONS"),List.of(),false,null);
        when(evidence.review(null,50)).thenReturn(report);
        mvc.perform(get(BASE + "/evidence").with(httpBasic("operator",PASSWORD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("REVIEW_ONLY_NO_DELETION_AUTHORITY"));
        UUID cursor=UUID.randomUUID();
        when(evidence.review(cursor,10)).thenReturn(report);
        mvc.perform(get(BASE + "/evidence").param("afterRunId",cursor.toString()).param("limit","10")
                .with(httpBasic("operator",PASSWORD))).andExpect(status().isOk());
        verify(evidence).review(null,50);
        verify(evidence).review(cursor,10);
    }

    @ParameterizedTest
    @ValueSource(strings={"0","101","-1","1.5","oops","2147483648"})
    void invalidPageLimitsNeverReachTheReport(String limit) throws Exception {
        mvc.perform(get(BASE + "/evidence").param("limit",limit).with(httpBasic("operator",PASSWORD)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Test
    void invalidCursorReturnsAnIdentifierError() throws Exception {
        mvc.perform(get(BASE + "/evidence").param("afterRunId","not-a-uuid").with(httpBasic("operator",PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Identifier must be a valid UUID."));
        verifyNoInteractions(evidence);
    }

    @Test
    void limitsExposeConfiguredBoundsAndBoundedProcessLocalCommandStatistics() throws Exception {
        mvc.perform(get(BASE + "/operating-limits").with(httpBasic("operator",PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxComparisonRowsPerDataset").value(5000))
                .andExpect(jsonPath("$.reviewAfterDays").value(45)).andExpect(jsonPath("$.maxEvidencePageSize").value(100))
                .andExpect(jsonPath("$.httpCommands.scope").value("THIS_PROCESS_HTTP_ROUTING_COMMANDS"))
                .andExpect(jsonPath("$.httpCommands.operations.length()").value(4));
        verifyNoInteractions(evidence,routing);
    }

    @Test
    void budgetRefusalHasASpecific409CodeAndDoesNotCountAsSuccess() throws Exception {
        var command=new ProjectionPromotionCommand(UUID.randomUUID(),UUID.randomUUID(),0,0,"reviewed");
        when(routing.promote(command,"operator")).thenThrow(new ProjectionComparisonBudgetException());
        var before=metrics.snapshot().operations().getFirst();
        mvc.perform(post(BASE + "/promote").with(httpBasic("operator",PASSWORD)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROJECTION_COMPARISON_BUDGET_EXCEEDED"));
        var after=metrics.snapshot().operations().getFirst();
        assertThat(after.attempts()).isEqualTo(before.attempts()+1);
        assertThat(after.budgetRefused()).isEqualTo(before.budgetRefused()+1);
        assertThat(after.accepted()).isEqualTo(before.accepted());
    }

    @Test
    void acceptedAndReplayedCommandsAppearThroughTheAuthenticatedLimitsEndpoint() throws Exception {
        var command=new ProjectionPromotionCommand(UUID.randomUUID(),UUID.randomUUID(),0,0,"reviewed");
        var accepted=new ProjectionRoutingResult(command.requestId(),"PROMOTE",1,"shadow-" + command.runId(),
                "operator",command.reason(),Instant.now(),false);
        var replay=new ProjectionRoutingResult(accepted.requestId(),accepted.action(),accepted.revision(),accepted.activeConsumer(),
                accepted.actor(),accepted.reason(),accepted.occurredAt(),true);
        var before=metrics.snapshot().operations().getFirst();
        when(routing.promote(command,"operator")).thenReturn(accepted,replay);
        for (int i=0;i<2;i++) mvc.perform(post(BASE + "/promote").with(httpBasic("operator",PASSWORD)).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/operating-limits").with(httpBasic("operator",PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.httpCommands.operations[0].accepted").value(before.accepted()+1))
                .andExpect(jsonPath("$.httpCommands.operations[0].replayed").value(before.replayed()+1));
    }

    @Test
    void authenticationCsrfAndBindingRefusalsAreOutsideTheCommandMeasurementScope() throws Exception {
        var before=metrics.snapshot();
        mvc.perform(post(BASE + "/promote").with(httpBasic("operator",PASSWORD)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/promote").with(httpBasic("operator",PASSWORD)).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(metrics.snapshot()).isEqualTo(before);
        verifyNoInteractions(routing);
    }
}
