package com.fincore.payments.operations;

import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.*;
import com.sun.net.httpserver.HttpServer;
import com.fincore.payments.dto.ProviderWebhook.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PackagedProviderSandboxIT {
    @TempDir Path directory;
    static final String KEY="packaged-test-only-sandbox-secret-12345";
    static final String WEBHOOK_KEY="packaged-test-only-separate-webhook-key-12345";
    static final String SETTLEMENT_KEY="packaged-test-only-separate-settlement-key-12345";
    record Running(Process process,Path out,Path err,int port) implements AutoCloseable {
        @Override public void close() throws Exception {
            assertThat(process.destroyForcibly().waitFor(10,TimeUnit.SECONDS)).isTrue();
            // Windows can briefly retain redirected-file handles after process exit.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->{
                Files.deleteIfExists(out);Files.deleteIfExists(err);
            });
        }
    }
    @Test void shippedSandboxRunsWithoutSpringAndRetainsTheOriginalDecisionAcrossProcessRestart() throws Exception {
        UUID instance=UUID.randomUUID(); Path data=directory.resolve("journal");
        var request=new Request(1,instance,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Action.AUTHORIZE,Scenario.TIMEOUT_AFTER_ACCEPT,
                new BigDecimal("10.00"),"INR","");
        try (var first=start(instance,data);
             var client=client(instance,first.port())) {
            assertThatThrownBy(() -> client.execute(request)).isInstanceOf(ProviderUncertainException.class);
            assertThat(Files.exists(data.resolve(request.operationId()+".json"))).isTrue();
        }
        try (var second=start(instance,data);
             var client=client(instance,second.port())) {
            var confirmed=client.execute(request); assertThat(confirmed.outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(client.execute(request)).isEqualTo(confirmed);
            try (var entries=Files.list(data)) { assertThat(entries.filter(p -> p.toString().endsWith(".json")).count()).isEqualTo(1); }
            assertThat(Files.readString(second.out())+Files.readString(second.err())).doesNotContain(KEY,"Spring Boot","Hikari","Exception");
        }
    }
    @Test void invalidOptionsFailBeforeSpringWithoutLeakingPathsOrCredentials() throws Exception {
        Path out=directory.resolve("failure.out"),err=directory.resolve("failure.err");
        var process=builder(out,err,List.of("--data-dir="+directory.resolve("private-path"))).start();
        assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isEqualTo(2);
        assertThat(Files.readString(out)).contains("SANDBOX_START_FAILED").doesNotContain("private-path",KEY,"Exception","Spring Boot");
    }
    @Test void shippedSandboxRedeliversTheSameSignedEventAfterAForcedProcessRestart() throws Exception {
        var delivered=java.util.Collections.synchronizedList(new ArrayList<byte[]>());
        var receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        receiver.createContext(ProviderWebhookWire.PATH,e -> {
            try (e) {
                byte[] body=e.getRequestBody().readNBytes(ProviderWire.MAX_BYTES+1);
                String time=e.getRequestHeaders().getFirst(ProviderWebhookWire.TIMESTAMP);
                assertThat(e.getRequestHeaders().getFirst(ProviderWebhookWire.SIGNATURE)).isEqualTo(ProviderWebhookWire.signature(WEBHOOK_KEY,"sandbox-v1",time,body));
                var event=ProviderWebhookWire.read(body); delivered.add(body);
                byte[] ack=ProviderWire.mapper().writeValueAsBytes(new Acknowledgement(UUID.randomUUID(),event.eventId(),Disposition.ALREADY_RECORDED,false));
                e.sendResponseHeaders(200,ack.length); e.getResponseBody().write(ack);
            }
        }); receiver.start();
        URI callback=URI.create("http://127.0.0.1:"+receiver.getAddress().getPort()+ProviderWebhookWire.PATH);
        UUID instance=UUID.randomUUID(); Path data=directory.resolve("callback-journal");
        var request=new Request(1,instance,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Action.AUTHORIZE,Scenario.APPROVE,new BigDecimal("10.00"),"INR","");
        try {
            try (var first=start(instance,data,callback); var adapter=client(instance,first.port())) {
                adapter.execute(request); await().atMost(Duration.ofSeconds(5)).until(() -> delivered.size()==1);
            }
            try (var second=start(instance,data,callback); var http=HttpClient.newHttpClient()) {
                var delivery=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+second.port()+"/v1/webhooks/"+request.operationId()+"/deliver"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+KEY).POST(HttpRequest.BodyPublishers.noBody()).build();
                var result=http.send(delivery,HttpResponse.BodyHandlers.ofByteArray()); assertThat(result.statusCode()).isEqualTo(200);
                assertThat(ProviderWire.read(result.body(),Delivery.class).acknowledged()).isTrue();
                assertThat(delivered).hasSize(2); assertThat(delivered.getFirst()).isEqualTo(delivered.getLast());
                assertThat(Files.readString(second.out())+Files.readString(second.err())).doesNotContain(KEY,WEBHOOK_KEY,"Spring Boot","Exception");
            }
        } finally { receiver.stop(0); }
    }
    private HttpSandboxPaymentProvider client(UUID instance,int port) { return new HttpSandboxPaymentProvider(
            new ProviderProperties(true,URI.create("http://127.0.0.1:"+port),KEY,instance,2000,5)); }
    @Test void packagedSettlementExportRetainsExactSignedBytesAcrossProcessRestart()throws Exception {
        UUID instance=UUID.randomUUID(),merchant=UUID.randomUUID(),payment=UUID.randomUUID();Path data=directory.resolve("settlement-journal");byte[] original;
        try(var first=start(instance,data);var adapter=client(instance,first.port());var http=HttpClient.newHttpClient()){
            var authorization=adapter.execute(new Request(1,instance,UUID.randomUUID(),payment,merchant,Action.AUTHORIZE,Scenario.APPROVE,new BigDecimal("10.00"),"INR",""));
            adapter.execute(new Request(1,instance,UUID.randomUUID(),payment,merchant,Action.CAPTURE,Scenario.APPROVE,new BigDecimal("10.00"),"INR",authorization.providerPaymentId()));
            original=statement(http,first.port(),merchant);
            assertThat(com.fincore.payments.settlement.SettlementWire.read(original).captured()).isEqualByComparingTo("10");
        }
        try(var second=start(instance,data);var http=HttpClient.newHttpClient()){
            assertThat(statement(http,second.port(),merchant)).isEqualTo(original);
            assertThat(Files.readString(second.out())+Files.readString(second.err())).doesNotContain(KEY,SETTLEMENT_KEY,"Spring Boot","Exception");
        }
    }
    private byte[] statement(HttpClient http,int port,UUID merchant)throws Exception{
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/settlements/"+merchant+"/INR"))
            .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+KEY).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(com.fincore.payments.settlement.SettlementWire.authentic(response.body(),"sandbox-v1",
            response.headers().firstValue("Fincore-Settlement-Signature").orElseThrow(),SETTLEMENT_KEY)).isTrue();return response.body();
    }
    private Running start(UUID instance,Path data) throws Exception {
        return start(instance,data,null);
    }
    private Running start(UUID instance,Path data,URI callback) throws Exception {
        Path out=directory.resolve("stdout-"+UUID.randomUUID()),err=directory.resolve("stderr-"+UUID.randomUUID());
        var builder=builder(out,err,List.of("--port=0","--data-dir="+data,"--instance-id="+instance));
        if (callback!=null) { builder.environment().put("FINCORE_SANDBOX_WEBHOOK_URL",callback.toString()); builder.environment().put("FINCORE_SANDBOX_WEBHOOK_KEY",WEBHOOK_KEY); }
        var process=builder.start();
        try {
            await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(out) && Files.readString(out).contains("SANDBOX_READY"));
            int port=ProviderWire.mapper().readTree(Files.readString(out)).path("port").asInt();
            return new Running(process,out,err,port);
        } catch (Exception failure) { process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); throw failure; }
    }
    private ProcessBuilder builder(Path out,Path err,List<String> options) {
        String executable=System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Dspring.datasource.url=jdbc:offline:must-not-connect","-Dfincore.provider.enabled=true",
                "-Dfincore.provider.api-key=invalid","-jar",Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"provider-sandbox"));
        command.addAll(options);
        var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().put("FINCORE_SANDBOX_API_KEY",KEY);
        builder.environment().put("FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY",SETTLEMENT_KEY);
        builder.environment().put("FINCORE_SANDBOX_SETTLEMENT_KEY_ID","sandbox-v1");
        for (String name:List.of("FINCORE_SANDBOX_WEBHOOK_URL","FINCORE_SANDBOX_WEBHOOK_KEY","FINCORE_SANDBOX_WEBHOOK_KEY_ID")) builder.environment().remove(name);
        return builder;
    }
}
