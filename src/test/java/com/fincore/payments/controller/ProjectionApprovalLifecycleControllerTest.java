package com.fincore.payments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.dto.ProjectionApprovalLifecycle.*;
import com.fincore.payments.exception.ProjectionPurgePlanConflictException;
import com.fincore.payments.exception.ProjectionPurgePlanNotFoundException;
import com.fincore.payments.service.ProjectionApprovalLifecycleService;
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
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectionApprovalLifecycleController.class)
@Import(ProjectionOperationsSecurityConfiguration.class)
@TestPropertySource(properties={"fincore.operations.username=operator",
        "fincore.operations.password-hash=$2a$04$zVsrbiBAywuIg9pJz99INe27Ii.5bSqj5.VQegRiGdJTYcIJjJzPW"})
class ProjectionApprovalLifecycleControllerTest {
    private static final String BASE="/api/v1/operations/projection-routing/approvals/";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProjectionApprovalLifecycleService lifecycle;

    @Test
    void revocationRequiresAuthenticationOperatorRoleAndCsrf() throws Exception {
        String url=BASE+UUID.randomUUID()+"/revoke";
        String body=mapper.writeValueAsString(command());
        mvc.perform(post(url).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user("reader").roles("READER")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("operator").roles("PROJECTION_OPERATOR")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void principalSuppliesTheActorEvenIfTheBodyAttemptsToOverrideIt() throws Exception {
        UUID approval=UUID.randomUUID();
        var command=command();
        var revocation=revocation(command.requestId(),approval,command.reason());
        when(lifecycle.revoke(approval,command,"operator")).thenReturn(new Result(revocation,false));
        String body=mapper.writeValueAsString(command).replace("\"reason\":","\"actor\":\"forged\",\"reason\":");
        mvc.perform(post(BASE+approval+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revocation.actor").value("operator"))
                .andExpect(jsonPath("$.revocation.deletionPermitted").value(false)).andExpect(jsonPath("$.replayed").value(false));
        verify(lifecycle).revoke(approval,command,"operator");
    }

    @Test
    void exactReplayResultIsReturnedWithoutInventingAFreshDecision() throws Exception {
        UUID approval=UUID.randomUUID();
        var command=command();
        var revoked=revocation(command.requestId(),approval,command.reason());
        when(lifecycle.revoke(approval,command,"operator")).thenReturn(new Result(revoked,true));
        mvc.perform(post(BASE+approval+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.revocation.requestId").value(command.requestId().toString()));
    }

    @Test
    void savedRevocationRequiresTheOperatorRole() throws Exception {
        String url=BASE+UUID.randomUUID()+"/revocation";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void savedRevocationGetReturnsImmutableEvidenceWithoutCsrf() throws Exception {
        UUID approval=UUID.randomUUID();
        var result=revocation(UUID.randomUUID(),approval,"Preservation review withdrawn");
        when(lifecycle.revocation(approval)).thenReturn(result);
        mvc.perform(get(BASE+approval+"/revocation").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(result.requestId().toString()))
                .andExpect(jsonPath("$.approvalId").value(approval.toString()))
                .andExpect(jsonPath("$.deletionPermitted").value(false));
        verify(lifecycle).revocation(approval);
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"reason\":\"withdraw\"}","{\"requestId\":null,\"reason\":\"withdraw\"}",
            "{\"requestId\":\"not-a-uuid\",\"reason\":\"withdraw\"}"})
    void missingOrInvalidRequestIdentityDoesNotReachTheService(String body) throws Exception {
        mvc.perform(post(BASE+UUID.randomUUID()+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","null","empty","blank","too-long"})
    void reasonMustBeExplicitNonblankAndBounded(String invalid) throws Exception {
        String reason=switch (invalid) {
            case "null","missing" -> null;
            case "empty" -> "";
            case "blank" -> "   ";
            default -> "x".repeat(501);
        };
        String body=mapper.writeValueAsString(new RevocationCommand(UUID.randomUUID(),reason));
        if (invalid.equals("missing")) body=body.replace(",\"reason\":null","");
        mvc.perform(post(BASE+UUID.randomUUID()+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void maximumReasonLengthIsAcceptedWithoutARevisionRequirement() throws Exception {
        UUID approval=UUID.randomUUID();
        var command=new RevocationCommand(UUID.randomUUID(),"x".repeat(500));
        when(lifecycle.revoke(approval,command,"operator")).thenReturn(new Result(revocation(command.requestId(),approval,command.reason()),false));
        mvc.perform(post(BASE+approval+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command))).andExpect(status().isOk());
        verify(lifecycle).revoke(approval,command,"operator");
    }

    @ParameterizedTest
    @ValueSource(strings={"revoke","revocation"})
    void malformedApprovalIdsAreRefusedBeforeCallingTheService(String suffix) throws Exception {
        if (suffix.equals("revoke")) {
            mvc.perform(post(BASE+"not-a-uuid/"+suffix).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                    .contentType("application/json").content(mapper.writeValueAsString(command())))
                    .andExpect(status().isBadRequest());
        } else {
            mvc.perform(get(BASE+"not-a-uuid/"+suffix).with(user("operator").roles("PROJECTION_OPERATOR")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(lifecycle);
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post(BASE+UUID.randomUUID()+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content("{")) .andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void serviceConflictAndMissingEvidenceHaveExistingSafeErrorCodes() throws Exception {
        UUID approval=UUID.randomUUID();
        var command=command();
        when(lifecycle.revoke(approval,command,"operator"))
                .thenThrow(new ProjectionPurgePlanConflictException("Approval was already revoked."));
        mvc.perform(post(BASE+approval+"/revoke").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROJECTION_PURGE_PLAN_CONFLICT"));
        when(lifecycle.revocation(approval)).thenThrow(new ProjectionPurgePlanNotFoundException());
        mvc.perform(get(BASE+approval+"/revocation").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PROJECTION_PURGE_PLAN_NOT_FOUND"));
    }

    private RevocationCommand command() { return new RevocationCommand(UUID.randomUUID(),"Withdraw preservation review approval"); }
    private Revocation revocation(UUID requestId,UUID approval,String reason) {
        return new Revocation(requestId,approval,Instant.parse("2026-10-24T12:00:00Z"),"operator",reason,
                "APPROVAL_REVOKED_NO_DELETION_AUTHORITY",false);
    }
}
