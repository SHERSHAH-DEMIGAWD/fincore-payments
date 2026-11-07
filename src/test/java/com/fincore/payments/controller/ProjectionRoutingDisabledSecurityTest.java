package com.fincore.payments.controller;

import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.service.ProjectionRoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ProjectionRoutingController.class,ProjectionEvidenceController.class,ProjectionPurgePlanController.class,
        ProjectionPreservationController.class,ProjectionApprovalLifecycleController.class})
@Import({ProjectionOperationsSecurityConfiguration.class,com.fincore.payments.service.ProjectionRoutingMetrics.class,
        com.fincore.payments.config.ProjectionOperationsConfiguration.class})
@TestPropertySource(properties = {"fincore.operations.username=", "fincore.operations.password-hash="})
class ProjectionRoutingDisabledSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProjectionRoutingService routing;
    @MockitoBean com.fincore.payments.service.ProjectionEvidenceService evidence;
    @MockitoBean com.fincore.payments.service.ProjectionPurgePlanService plans;
    @MockitoBean com.fincore.payments.service.ProjectionPreservationService preservation;
    @MockitoBean com.fincore.payments.service.ProjectionApprovalLifecycleService approvalLifecycle;

    @Test
    void approvalRevocationRemainsDisabledWithoutCredentials() throws Exception {
        String base="/api/v1/operations/projection-routing/approvals/"+java.util.UUID.randomUUID();
        var operator=user("operator").roles("PROJECTION_OPERATOR");
        mvc.perform(post(base+"/revoke").with(operator).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base+"/revocation").with(operator)).andExpect(status().isForbidden());
        verifyNoInteractions(approvalLifecycle);
    }

    @Test
    void preservationAndApprovalsRemainDisabledWithoutCredentials() throws Exception {
        String base="/api/v1/operations/projection-routing";
        String id=java.util.UUID.randomUUID().toString();
        var operator=user("operator").roles("PROJECTION_OPERATOR");
        for (String path:java.util.List.of("/preservations/"+id,"/preservations/"+id+"/export","/approvals/"+id,"/approvals/"+id+"/review"))
            mvc.perform(get(base+path).with(operator)).andExpect(status().isForbidden());
        for (String action:java.util.List.of("preservations","approvals"))
            mvc.perform(post(base+"/purge-plans/"+id+"/"+action).with(operator).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(preservation);
    }

    @Test
    void purgePlansRemainDisabledWithoutCredentials() throws Exception {
        String base="/api/v1/operations/projection-routing/purge-plans";
        var operator=user("operator").roles("PROJECTION_OPERATOR");
        mvc.perform(post(base).with(operator).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base+"/"+java.util.UUID.randomUUID()).with(operator)).andExpect(status().isForbidden());
        mvc.perform(get(base+"/"+java.util.UUID.randomUUID()+"/review").with(operator)).andExpect(status().isForbidden());
        verifyNoInteractions(plans);
    }

    @Test
    void unconfiguredOperationsRejectAnonymousAccess() throws Exception {
        mvc.perform(get("/api/v1/operations/projection-routing")).andExpect(status().isUnauthorized());
        verifyNoInteractions(routing);
    }

    @Test
    void noDefaultUserIsCreatedWhenCredentialsAreAbsent() throws Exception {
        mvc.perform(get("/api/v1/operations/projection-routing").with(httpBasic("user", "password")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(routing);
    }

    @Test
    void unconfiguredOperationsDenyEvenAnAlreadyAuthenticatedRole() throws Exception {
        mvc.perform(get("/api/v1/operations/projection-routing").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @ParameterizedTest
    @ValueSource(strings={"replace","retire"})
    void lifecycleMutationsRemainDisabledWhenCredentialsAreAbsent(String action) throws Exception {
        mvc.perform(post("/api/v1/operations/projection-routing/" + action)
                .with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routing);
    }

    @ParameterizedTest
    @ValueSource(strings={"evidence","operating-limits"})
    void diagnosticsRemainDisabledWithoutConfiguredCredentials(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/operations/projection-routing/" + endpoint)
                .with(user("operator").roles("PROJECTION_OPERATOR"))).andExpect(status().isForbidden());
        verifyNoInteractions(evidence,routing);
    }
}
