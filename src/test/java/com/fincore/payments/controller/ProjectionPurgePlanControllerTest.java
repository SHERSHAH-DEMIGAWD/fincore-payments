package com.fincore.payments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.dto.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.service.ProjectionPurgePlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectionPurgePlanController.class)
@Import(ProjectionOperationsSecurityConfiguration.class)
@TestPropertySource(properties={"fincore.operations.username=operator",
        "fincore.operations.password-hash=$2a$04$zVsrbiBAywuIg9pJz99INe27Ii.5bSqj5.VQegRiGdJTYcIJjJzPW"})
class ProjectionPurgePlanControllerTest {
    private static final String BASE="/api/v1/operations/projection-routing/purge-plans";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProjectionPurgePlanService plans;

    @Test
    void creationRequiresAuthenticationRoleAndCsrf() throws Exception {
        String body=mapper.writeValueAsString(command());
        mvc.perform(post(BASE).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).with(user("reader").roles("READER")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(plans);
    }

    @Test
    void principalSuppliesTheActorAndTheServiceResultIsReturned() throws Exception {
        var command=command();
        when(plans.create(command,"operator")).thenReturn(new ProjectionPurgePlan.Result(null,false));
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command).replace("\"reason\":", "\"actor\":\"forged\",\"reason\":")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        verify(plans).create(command,"operator");
    }

    @ParameterizedTest
    @ValueSource(strings={"null","\"3\"","3.0","3e0","-1","9223372036854775808"})
    void revisionMustBeAnExplicitNonnegativeInteger(String revision) throws Exception {
        String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"runId\":\""+UUID.randomUUID()+"\",\"expectedRevision\":"+revision+",\"reason\":\"review\"}";
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(plans);
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"reason\":\"review\"}","{\"expectedRevision\":0}"})
    void requiredIntentFieldsCannotBeOmitted(String body) throws Exception {
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(plans);
    }

    @Test
    void omittedRevisionCannotDefaultToZero() throws Exception {
        String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"runId\":\""+UUID.randomUUID()+"\",\"reason\":\"review\"}";
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(plans);
    }

    @ParameterizedTest
    @ValueSource(strings={"","/review"})
    void savedEvidenceAndFreshReviewRequireTheOperatorRole(String suffix) throws Exception {
        String url=BASE+"/"+UUID.randomUUID()+suffix;
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        verifyNoInteractions(plans);
    }

    @Test
    void freshReviewIsReadOnlyAndNeverADeletionCapability() throws Exception {
        UUID id=UUID.randomUUID();
        when(plans.review(id)).thenReturn(new ProjectionPurgePlan.Review(id,Instant.now(),"CURRENT_REVIEW_ONLY",false,List.of(),List.of("KEEP_EVIDENCE")));
        mvc.perform(get(BASE+"/"+id+"/review").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletionPermitted").value(false));
        verify(plans).review(id);
    }

    @Test
    void conflictsAndMissingPlansHaveSpecificSafeErrorCodes() throws Exception {
        var command=command();
        when(plans.create(command,"operator")).thenThrow(new ProjectionPurgePlanConflictException("Review window has not elapsed."));
        mvc.perform(post(BASE).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROJECTION_PURGE_PLAN_CONFLICT"));
        when(plans.get(command.requestId())).thenThrow(new ProjectionPurgePlanNotFoundException());
        mvc.perform(get(BASE+"/"+command.requestId()).with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PROJECTION_PURGE_PLAN_NOT_FOUND"));
    }
    private ProjectionPurgePlanCommand command() { return new ProjectionPurgePlanCommand(UUID.randomUUID(),UUID.randomUUID(),3,"Review retired evidence"); }
}
