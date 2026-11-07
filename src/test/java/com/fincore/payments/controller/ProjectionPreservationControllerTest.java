package com.fincore.payments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProjectionOperationsSecurityConfiguration;
import com.fincore.payments.dto.ProjectionPreservation.*;
import com.fincore.payments.service.ProjectionPreservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectionPreservationController.class)
@Import(ProjectionOperationsSecurityConfiguration.class)
@TestPropertySource(properties={"fincore.operations.username=operator",
        "fincore.operations.password-hash=$2a$04$zVsrbiBAywuIg9pJz99INe27Ii.5bSqj5.VQegRiGdJTYcIJjJzPW"})
class ProjectionPreservationControllerTest {
    private static final String BASE="/api/v1/operations/projection-routing";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProjectionPreservationService service;

    @ParameterizedTest @ValueSource(strings={"preservations","approvals"})
    void mutationsRequireAuthenticationOperatorRoleAndCsrf(String suffix) throws Exception {
        String url=BASE+"/purge-plans/"+UUID.randomUUID()+"/"+suffix;
        mvc.perform(post(url).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user("reader").roles("READER")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("operator").roles("PROJECTION_OPERATOR")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings={"preservations/ID","preservations/ID/export","approvals/ID","approvals/ID/review"})
    void evidenceAndSensitiveDownloadsRequireTheOperator(String suffix) throws Exception {
        String url=BASE+"/"+suffix.replace("ID",UUID.randomUUID().toString());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void principalSuppliesBothActorsEvenWithForgedBodyActor() throws Exception {
        UUID plan=UUID.randomUUID();
        var command=new Command(UUID.randomUUID(),3,"preserve");
        var approval=new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,"a".repeat(64),true,"review");
        when(service.preserve(plan,command,"operator")).thenReturn(new Result(null,false));
        when(service.approve(plan,approval,"operator")).thenReturn(new ApprovalResult(null,false));
        mvc.perform(post(BASE+"/purge-plans/"+plan+"/preservations").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command).replace("\"reason\":","\"actor\":\"forged\",\"reason\":")))
                .andExpect(status().isOk());
        mvc.perform(post(BASE+"/purge-plans/"+plan+"/approvals").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(approval).replace("\"reason\":","\"actor\":\"forged\",\"reason\":")))
                .andExpect(status().isOk());
        verify(service).preserve(plan,command,"operator"); verify(service).approve(plan,approval,"operator");
    }

    @Test
    void downloadReturnsExactBytesDigestAttachmentAndNoStore() throws Exception {
        UUID id=UUID.randomUUID(); byte[] bytes="{\"evidence\":\"\u20b9\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.download(id)).thenReturn(new Download(id,"a".repeat(64),bytes));
        mvc.perform(get(BASE+"/preservations/"+id+"/export").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-SHA256","a".repeat(64)))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Length",Integer.toString(bytes.length)))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(id+".json")));
    }

    @ParameterizedTest @ValueSource(strings={"null","\"3\"","3.0","3e0","-1","9223372036854775808"})
    void bothCommandsRejectNonintegralOrInvalidRevisions(String revision) throws Exception {
        UUID plan=UUID.randomUUID();
        var approval=new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,"a".repeat(64),true,"review");
        String body=mapper.writeValueAsString(approval).replace("\"expectedRevision\":3","\"expectedRevision\":"+revision);
        for (String suffix:java.util.List.of("preservations","approvals"))
            mvc.perform(post(BASE+"/purge-plans/"+plan+"/"+suffix).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings={"false","null","\"missing\"","\"true\"","1","{}"})
    void acknowledgementMustBeExplicitlyTrue(String acknowledged) throws Exception {
        var approval=new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,"a".repeat(64),true,"review");
        String body=mapper.writeValueAsString(approval);
        body=acknowledged.equals("\"missing\"") ? body.replace("\"preservationAcknowledged\":true,","")
                : body.replace("\"preservationAcknowledged\":true","\"preservationAcknowledged\":"+acknowledged);
        mvc.perform(post(BASE+"/purge-plans/"+UUID.randomUUID()+"/approvals").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings={"short","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void digestMustBeExactLowercaseHex(String digest) throws Exception {
        var approval=new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,digest,true,"review");
        mvc.perform(post(BASE+"/purge-plans/"+UUID.randomUUID()+"/approvals").with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(approval))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void neitherCommandMayOmitTheExpectedRevision() throws Exception {
        var command=new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,"a".repeat(64),true,"review");
        String body=mapper.writeValueAsString(command).replace("\"expectedRevision\":3,","");
        for (String suffix:java.util.List.of("preservations","approvals"))
            mvc.perform(post(BASE+"/purge-plans/"+UUID.randomUUID()+"/"+suffix).with(user("operator").roles("PROJECTION_OPERATOR")).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
