package com.fincore.payments.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.dto.ProjectionPromotionCommand;
import com.fincore.payments.dto.ProjectionRollbackCommand;
import com.fincore.payments.dto.ProjectionRoutingResult;
import com.fincore.payments.dto.ProjectionRoutingView;
import com.fincore.payments.dto.ProjectionReplacementCommand;
import com.fincore.payments.dto.ProjectionRetirementCommand;
import com.fincore.payments.dto.ProjectionTargetView;
import com.fincore.payments.service.ProjectionRoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectionRoutingController.class)
@Import({ProjectionOperationsSecurityConfiguration.class,com.fincore.payments.service.ProjectionRoutingMetrics.class})
class ProjectionRoutingControllerTest {
    private static final String BASE = "/api/v1/operations/projection-routing";
    private static final String USERNAME = "routing-operator";
    private static final String PASSWORD = "test-only-password";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProjectionRoutingService routing;

    @DynamicPropertySource
    static void operatorCredentials(DynamicPropertyRegistry properties) {
        properties.add("fincore.operations.username", () -> USERNAME);
        properties.add("fincore.operations.password-hash", () -> HASH);
    }

    @Test
    void statusRequiresAuthentication() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"fincore-projection-operations\""));
        verifyNoInteractions(routing);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(get(BASE).with(httpBasic(USERNAME, "wrong-password"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(routing);
    }

    @Test
    void configuredBcryptCredentialsAuthorizeStatus() throws Exception {
        when(routing.status()).thenReturn(new ProjectionRoutingView(0, "payment-projection-v1", null, null));
        mvc.perform(get(BASE).with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.activeConsumer").value("payment-projection-v1"));
        verify(routing).status();
    }

    @Test
    void authenticatedUserWithoutOperatorRoleIsForbidden() throws Exception {
        mvc.perform(get(BASE).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @Test
    void promotionRequiresCsrfEvenWithValidCredentials() throws Exception {
        mvc.perform(post(BASE + "/promote").with(httpBasic(USERNAME, PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content(promotionBody(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @Test
    void rollbackRejectsInvalidCsrfToken() throws Exception {
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME, PASSWORD)).with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","expectedRevision":1,"reason":"Return to baseline"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @Test
    void promotionUsesAuthenticatedActorAndSessionCsrfToken() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String shadow = "shadow-" + runId;
        var command = new ProjectionPromotionCommand(requestId, runId, 0, 0, "Reviewed current source");
        when(routing.promote(command, USERNAME)).thenReturn(new ProjectionRoutingResult(requestId, "PROMOTE", 1,
                shadow, USERNAME, command.reason(), Instant.parse("2026-10-22T10:00:00Z"), false));

        var tokenResponse = mvc.perform(get(BASE + "/csrf").with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        JsonNode token = mapper.readTree(tokenResponse.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) tokenResponse.getRequest().getSession(false);
        mvc.perform(post(BASE + "/promote").with(httpBasic(USERNAME, PASSWORD)).session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(promotionBody(requestId, runId).replace("}", ",\"operator\":\"forged-actor\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value(USERNAME))
                .andExpect(jsonPath("$.activeConsumer").value(shadow));
        verify(routing).promote(command, USERNAME);
    }

    @Test
    void rollbackPassesOnlyAuthenticatedActor() throws Exception {
        UUID requestId = UUID.randomUUID();
        var command = new ProjectionRollbackCommand(requestId, 1, "Return to baseline");
        when(routing.rollback(command, USERNAME)).thenReturn(new ProjectionRoutingResult(requestId, "ROLLBACK", 2,
                "payment-projection-v1", USERNAME, command.reason(), Instant.parse("2026-10-22T10:00:00Z"), false));
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.actor").value(USERNAME));
        verify(routing).rollback(command, USERNAME);
    }

    @Test
    void validCsrfWithoutOperatorRoleDoesNotPermitMutation() throws Exception {
        mvc.perform(post(BASE + "/promote").with(user("reader").roles("READER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(promotionBody(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @Test
    void rejectsMissingCommandIdentity() throws Exception {
        mvc.perform(post(BASE + "/promote").with(httpBasic(USERNAME, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedBuildGeneration\":0,\"expectedRevision\":0,\"reason\":\"Reviewed\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @Test
    void requiresExplicitRevisionAndBuildGenerationRatherThanDefaultingToZero() throws Exception {
        String identity="\"requestId\":\"" + UUID.randomUUID() + "\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"Reviewed\"";
        mvc.perform(post(BASE + "/promote").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + identity + ",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + identity + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @ParameterizedTest
    @ValueSource(strings={"null","0.5","0e0","\"0\"","true","9223372036854775808"})
    void rejectsCoercedNullOrOverflowedOperatorCounters(String counter) throws Exception {
        String prefix="{\"requestId\":\"" + UUID.randomUUID() + "\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"Reviewed\",";
        mvc.perform(post(BASE + "/promote").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(prefix + "\"expectedBuildGeneration\":" + counter + ",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(prefix + "\"expectedRevision\":" + counter + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @Test
    void rejectsNegativeRevisionAndBlankReason() throws Exception {
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","expectedRevision":-1,"reason":" "}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @Test
    void malformedJsonCannotReachMutationService() throws Exception {
        mvc.perform(post(BASE + "/rollback").with(httpBasic(USERNAME, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @ParameterizedTest
    @ValueSource(strings={"replace","retire"})
    void lifecycleMutationsRequireOperatorAuthenticationAndCsrf(String action) throws Exception {
        String body="{\"requestId\":\"" + UUID.randomUUID() + "\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"expectedPreviousRunId\":\"" + UUID.randomUUID()
                + "\",\"expectedBuildGeneration\":0,\"expectedRevision\":2,\"reason\":\"Reviewed\"}";
        mvc.perform(post(BASE + "/" + action).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + action).with(httpBasic(USERNAME,PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + action).with(user("reader").roles("READER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @Test
    void replacementUsesAuthenticatedPrincipalAndExplicitPriorTarget() throws Exception {
        var command=new ProjectionReplacementCommand(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,2,"Reviewed replacement");
        when(routing.replace(command,USERNAME)).thenReturn(new ProjectionRoutingResult(command.requestId(),"REPLACE",3,
                "shadow-" + command.runId(),USERNAME,command.reason(),Instant.parse("2026-10-23T10:00:00Z"),false));
        String body=mapper.writeValueAsString(command).replace("}",",\"operator\":\"forged\"}");
        mvc.perform(post(BASE + "/replace").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value(USERNAME)).andExpect(jsonPath("$.revision").value(3));
        verify(routing).replace(command,USERNAME);
    }

    @Test
    void retirementUsesAuthenticatedPrincipal() throws Exception {
        var command=new ProjectionRetirementCommand(UUID.randomUUID(),UUID.randomUUID(),2,"Retire maintenance");
        when(routing.retire(command,USERNAME)).thenReturn(new ProjectionRoutingResult(command.requestId(),"RETIRE",3,
                "payment-projection-v1",USERNAME,command.reason(),Instant.parse("2026-10-23T10:00:00Z"),false));
        mvc.perform(post(BASE + "/retire").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("RETIRE"));
        verify(routing).retire(command,USERNAME);
    }

    @Test
    void targetLifecycleStatusRequiresAuthenticationAndReturnsRetainedState() throws Exception {
        UUID run=UUID.randomUUID();
        mvc.perform(get(BASE + "/targets/" + run)).andExpect(status().isUnauthorized());
        when(routing.target(run)).thenReturn(new ProjectionTargetView(run,"shadow-" + run,0,"RETIRED"));
        mvc.perform(get(BASE + "/targets/" + run).with(httpBasic(USERNAME,PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle").value("RETIRED"));
        verify(routing).target(run);
    }

    @ParameterizedTest
    @ValueSource(strings={"null","0.5","0e0","\"0\"","true","9223372036854775808"})
    void lifecycleCountersRejectCoercionNullAndOverflow(String counter) throws Exception {
        String prefix="{\"requestId\":\"" + UUID.randomUUID() + "\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"expectedPreviousRunId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Reviewed\",";
        mvc.perform(post(BASE + "/replace").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(prefix + "\"expectedRevision\":2,\"expectedBuildGeneration\":" + counter + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/replace").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(prefix + "\"expectedRevision\":" + counter + ",\"expectedBuildGeneration\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/retire").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(prefix + "\"expectedRevision\":" + counter + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    @Test
    void lifecycleCommandsRequirePreviousTargetAndExplicitCounters() throws Exception {
        String identities="{\"requestId\":\"" + UUID.randomUUID() + "\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"Reviewed\"";
        mvc.perform(post(BASE + "/replace").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(identities + ",\"expectedRevision\":2,\"expectedBuildGeneration\":0}"))
                .andExpect(status().isBadRequest());
        String previous=",\"expectedPreviousRunId\":\"" + UUID.randomUUID() + "\"";
        mvc.perform(post(BASE + "/replace").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(identities + previous + ",\"expectedRevision\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/retire").with(httpBasic(USERNAME,PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(identities + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routing);
    }

    private String promotionBody(UUID requestId, UUID runId) {
        return """
                {"requestId":"%s","runId":"%s","expectedBuildGeneration":0,"expectedRevision":0,"reason":"Reviewed current source"}
                """.formatted(requestId, runId);
    }
}
