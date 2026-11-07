package com.fincore.payments.provider;

import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.dto.ProviderOperation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SandboxProviderTest {
    @TempDir Path directory;
    final UUID instance=UUID.randomUUID(),payment=UUID.randomUUID(),merchant=UUID.randomUUID();
    static final String KEY="test-only-provider-secret-0123456789";
    Request request(Action action,Scenario scenario) { return new Request(1,instance,UUID.randomUUID(),payment,merchant,action,scenario,
            new BigDecimal("10.00"),"INR",action==Action.AUTHORIZE ? "" : "sandbox-pay-"+payment); }
    ProviderProperties properties(int port,String key) { return new ProviderProperties(true,URI.create("http://127.0.0.1:"+port),key,instance,500,5); }

    @Test void durableJournalReopensWithExactlyTheOriginalDecision() throws Exception {
        var request=request(Action.AUTHORIZE,Scenario.APPROVE); Response original;
        try (var store=new SandboxProviderStore(directory,instance)) {
            original=store.execute(request).response();
            assertThat(store.execute(request).replayed()).isTrue(); assertThat(store.decisionCount()).isEqualTo(1);
        }
        try (var reopened=new SandboxProviderStore(directory,instance)) {
            var replay=reopened.execute(request);
            assertThat(replay.replayed()).isTrue(); assertThat(replay.response()).isEqualTo(original); assertThat(reopened.decisionCount()).isEqualTo(1);
        }
    }
    @Test void sameOperationCannotAcquireDifferentIntent() throws Exception {
        var request=request(Action.AUTHORIZE,Scenario.APPROVE);
        try (var store=new SandboxProviderStore(directory,instance)) {
            store.execute(request);
            var changed=new Request(1,instance,request.operationId(),payment,merchant,Action.AUTHORIZE,Scenario.DECLINE,
                    request.amount(),"INR","");
            assertThatThrownBy(() -> store.execute(changed)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.decisionCount()).isEqualTo(1);
        }
    }
    @Test void fullLifecycleIsMonotonicAndDoesNotRepeatCaptureOrRefund() throws Exception {
        try (var store=new SandboxProviderStore(directory,instance)) {
            store.execute(request(Action.AUTHORIZE,Scenario.APPROVE));
            var capture=request(Action.CAPTURE,Scenario.APPROVE); store.execute(capture);
            assertThatThrownBy(() -> store.execute(request(Action.CAPTURE,Scenario.APPROVE))).isInstanceOf(IllegalArgumentException.class);
            var refund=request(Action.REFUND,Scenario.APPROVE); store.execute(refund);
            assertThat(store.execute(capture).replayed()).isTrue(); assertThat(store.execute(refund).replayed()).isTrue();
            assertThat(store.decisionCount()).isEqualTo(3);
        }
        try (var reopened=new SandboxProviderStore(directory,instance)) { assertThat(reopened.decisionCount()).isEqualTo(3); }
    }
    @Test void declinedCaptureLeavesTheAuthorizationAvailableForAnotherCommand() throws Exception {
        try (var store=new SandboxProviderStore(directory,instance)) {
            store.execute(request(Action.AUTHORIZE,Scenario.APPROVE));
            assertThat(store.execute(request(Action.CAPTURE,Scenario.DECLINE)).response().outcome()).isEqualTo(Outcome.DECLINED);
            assertThat(store.execute(request(Action.CAPTURE,Scenario.APPROVE)).response().outcome()).isEqualTo(Outcome.APPROVED);
        }
    }
    @Test void journalRejectsDifferentInstancesAndCorruptionWithoutReplacingEvidence() throws Exception {
        var request=request(Action.AUTHORIZE,Scenario.APPROVE);
        try (var store=new SandboxProviderStore(directory,instance)) { store.execute(request); }
        assertThatThrownBy(() -> new SandboxProviderStore(directory,UUID.randomUUID())).isInstanceOf(java.io.IOException.class);
        Path saved=directory.resolve(request.operationId()+".json"); Files.writeString(saved,"{}");
        assertThatThrownBy(() -> new SandboxProviderStore(directory,instance)).isInstanceOf(Exception.class);
        assertThat(Files.readString(saved)).isEqualTo("{}");
    }
    @Test void anotherStoreCannotOwnTheSameJournalAtTheSameTime() throws Exception {
        try (var store=new SandboxProviderStore(directory,instance)) {
            assertThatThrownBy(() -> new SandboxProviderStore(directory,instance)).isInstanceOf(Exception.class);
            assertThat(store.execute(request(Action.AUTHORIZE,Scenario.APPROVE)).replayed()).isFalse();
        }
    }
    @Test void realHttpAdapterConfirmsACompleteLifecycle() throws Exception {
        try (var server=new SandboxProviderServer(0,directory,instance,KEY);
             var adapter=new HttpSandboxPaymentProvider(properties(server.port(),KEY))) {
            assertThat(adapter.execute(request(Action.AUTHORIZE,Scenario.APPROVE)).outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(adapter.execute(request(Action.CAPTURE,Scenario.APPROVE)).outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(adapter.execute(request(Action.REFUND,Scenario.APPROVE)).outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(server.decisionCount()).isEqualTo(3);
        }
    }
    @Test void lostAcceptedReplyIsUnknownUntilTheSameOperationIsRetried() throws Exception {
        var request=request(Action.AUTHORIZE,Scenario.TIMEOUT_AFTER_ACCEPT);
        try (var server=new SandboxProviderServer(0,directory,instance,KEY);
             var adapter=new HttpSandboxPaymentProvider(properties(server.port(),KEY))) {
            assertThatThrownBy(() -> adapter.execute(request)).isInstanceOf(ProviderUncertainException.class);
            assertThat(server.decisionCount()).isEqualTo(1);
            assertThat(adapter.execute(request).outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(server.decisionCount()).isEqualTo(1);
        }
    }
    @Test void wrongApiKeyDoesNotCreateAProviderDecision() throws Exception {
        try (var server=new SandboxProviderServer(0,directory,instance,KEY);
             var adapter=new HttpSandboxPaymentProvider(properties(server.port(),"other-test-only-provider-secret-12345"))) {
            assertThatThrownBy(() -> adapter.execute(request(Action.AUTHORIZE,Scenario.APPROVE))).isInstanceOf(ProviderUncertainException.class);
            assertThat(server.decisionCount()).isZero();
        }
    }
    @Test void networkAdapterRefusesToRunInsideADatabaseTransaction() throws Exception {
        try (var adapter=new HttpSandboxPaymentProvider(properties(1,KEY))) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> adapter.execute(request(Action.AUTHORIZE,Scenario.APPROVE))).isInstanceOf(IllegalStateException.class); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","unknown","coercion","null","wrong-instance","wrong-amount"})
    void strictProtocolRejectsAmbiguousOrMismatchedDocuments(String mutation) throws Exception {
        var request=request(Action.AUTHORIZE,Scenario.APPROVE);
        var response=new Response(1,instance,request.operationId(),payment,merchant,Action.AUTHORIZE,new BigDecimal("10.00"),"INR",
                Outcome.APPROVED,"sandbox-pay-"+payment,"sandbox-op-"+request.operationId());
        String json=ProviderWire.mapper().writeValueAsString(response);
        json=switch (mutation) {
            case "duplicate" -> "{\"version\":1,"+json.substring(1);
            case "trailing" -> json+" {}";
            case "unknown" -> "{\"extra\":true,"+json.substring(1);
            case "coercion" -> json.replace("\"version\":1","\"version\":\"1\"");
            case "null" -> json.replace("\"version\":1","\"version\":null");
            case "wrong-instance" -> json.replace(instance.toString(),UUID.randomUUID().toString());
            default -> json.replace("10.00","11.00");
        };
        byte[] input=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ProviderWire.validate(ProviderWire.read(input,Response.class),request)).isInstanceOf(Exception.class);
    }
}
