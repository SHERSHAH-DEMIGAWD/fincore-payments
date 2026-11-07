package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.observability.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "fincore.observability.enabled=true","fincore.observability.collector-enabled=false","fincore.outbox.enabled=false",
    "fincore.risk.enabled=false","management.tracing.sampling.probability=1.0","fincore.observability.trace-export-enabled=false"})
@AutoConfigureObservability @Import(ObservabilityDatabaseTests.TraceTestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ObservabilityDatabaseTests {
    static final String OBSERVER="test-observer",OBSERVER_PASSWORD="test-observer-password",MERCHANT_PASSWORD="test-merchant-password",OPERATOR="test-operator";
    static final String HASH=new BCryptPasswordEncoder(4).encode(OBSERVER_PASSWORD),MERCHANT_HASH=new BCryptPasswordEncoder(4).encode(MERCHANT_PASSWORD);
    static final String ROOT="/internal/observability";
    @TestConfiguration(proxyBeanMethods=false) static class TraceTestConfig {
        @Bean InMemorySpanExporter capturedSpans(){return InMemorySpanExporter.create();}
        @Bean SdkTracerProvider testTracerProvider(InMemorySpanExporter exporter){return SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();}
    }
    @DynamicPropertySource static void observer(DynamicPropertyRegistry r){
        r.add("fincore.observability.username",()->OBSERVER);r.add("fincore.observability.password-hash",()->HASH);
        r.add("fincore.operations.username",()->OPERATOR);r.add("fincore.operations.password-hash",()->HASH);
    }
    @Autowired JdbcTemplate jdbc;@Autowired MeterRegistry meters;@Autowired ObjectMapper mapper;@Autowired InMemorySpanExporter spans;
    @Autowired PaymentRepository payments;@Autowired OperationalSnapshot snapshot;@Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OperationalSnapshotRepository snapshots;@MockitoSpyBean OutboxService outbox;
    @LocalServerPort int port;
    UUID merchant;String user;HttpClient client;
    @BeforeEach void setup(){
        merchant=UUID.randomUUID();user="obs-merchant-"+merchant;jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)",merchant,user,MERCHANT_HASH);
        client=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(2)).build();spans.reset();
    }
    @AfterEach void cleanup(){reset((Object)AopTestUtils.getUltimateTargetObject(snapshots));reset((Object)AopTestUtils.getUltimateTargetObject(outbox));client.close();}
    String base(){return "http://127.0.0.1:"+port;}
    String auth(String username,String password){return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));}
    HttpResponse<String> get(String path,String authorization)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(5));if(authorization!=null)request.header("Authorization",authorization);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> create(String key,String reference,String trace)throws Exception{
        String authorization=auth(user,MERCHANT_PASSWORD);var csrf=mapper.readTree(get("/api/v1/merchant/csrf",authorization).body());
        var request=HttpRequest.newBuilder(URI.create(base()+"/api/v1/payments?private_query=should-not-be-traced"))
            .timeout(Duration.ofSeconds(5)).header("Authorization",authorization).header(csrf.path("headerName").asText(),csrf.path("token").asText())
            .header("Idempotency-Key",key).header("Content-Type","application/json");
        if(trace!=null)request.header("traceparent",trace).header("baggage","private-baggage=secret").header("tracestate","private=secret");
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new CreatePaymentRequest(reference,new BigDecimal("10.00"),"INR")))).build(),HttpResponse.BodyHandlers.ofString());
    }
    double calls(String flow,String outcome){var counter=meters.find("fincore.flow.calls").tags("flow",flow,"outcome",outcome).counter();return counter==null?0:counter.count();}
    OperationalSnapshotCollector collector(){return new OperationalSnapshotCollector(snapshots,snapshot,meters);}
    int aggregateHealthStatus(){return 200;}
    @Test void onlyDedicatedObserverCanReadTheFourOperationalEndpoints()throws Exception{
        for(String path:List.of("/prometheus","/health","/health/readiness","/health/liveness")){
            assertThat(get(ROOT+path,null).statusCode()).isEqualTo(401);
            assertThat(get(ROOT+path,auth(OBSERVER,OBSERVER_PASSWORD)).statusCode()).isEqualTo(path.equals("/health")?aggregateHealthStatus():200);
        }
        assertThat(get(ROOT+"/prometheus",auth(user,MERCHANT_PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(get(ROOT+"/prometheus",auth(OPERATOR,OBSERVER_PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/merchant/me",auth(OBSERVER,OBSERVER_PASSWORD)).statusCode()).isEqualTo(401);
        String health=get(ROOT+"/health",auth(OBSERVER,OBSERVER_PASSWORD)).body();assertThat(health).doesNotContain("components","details","jdbc:","password");
    }
    @Test void observerCannotReadConfigurationOrInvokeWrites()throws Exception{
        for(String path:List.of("","/env","/configprops","/heapdump","/metrics","/health/db"))
            assertThat(get(ROOT+path,auth(OBSERVER,OBSERVER_PASSWORD)).statusCode()).isEqualTo(403);
        var request=HttpRequest.newBuilder(URI.create(base()+ROOT+"/prometheus")).header("Authorization",auth(OBSERVER,OBSERVER_PASSWORD))
            .POST(HttpRequest.BodyPublishers.noBody()).build();assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void createAndReplayProduceCorrelatedSanitizedNestedSpansAndMetrics()throws Exception{
        String key="private-key-"+UUID.randomUUID(),reference="private-reference-"+UUID.randomUUID();
        String traceId="11112222333344445555666677778888",parent="00-"+traceId+"-0123456789abcdef-01";
        double before=calls("payment_create","success");var first=create(key,reference,parent);assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("X-Fincore-Trace-Id")).contains(traceId);
        assertThat(create(key,reference,null).statusCode()).isEqualTo(200);assertThat(calls("payment_create","success")).isEqualTo(before+1);
        assertThat(calls("payment_create","replay")).isPositive();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(spans.getFinishedSpanItems().stream().filter(s->s.getTraceId().equals(traceId)).map(s->s.getName()).toList())
            .contains("payment_create","payment_persist"));
        var trace=spans.getFinishedSpanItems().stream().filter(s->s.getTraceId().equals(traceId)).toList();
        assertThat(trace.toString()).doesNotContain(key,reference,user,merchant.toString(),"private_query","private-baggage","secret");
        assertThat(trace.stream().map(s->s.getParentSpanId()).toList()).contains("0123456789abcdef");
        String scrape=get(ROOT+"/prometheus",auth(OBSERVER,OBSERVER_PASSWORD)).body();
        assertThat(scrape).contains("fincore_flow_calls_total","fincore_flow_seconds_bucket").doesNotContain(key,reference,user,merchant.toString());
    }
    @Test void commitFailureIsAnErrorAndNeverASuccessfulFinancialEffect()throws Exception{
        String secret="private-commit-"+UUID.randomUUID();OutboxService target=AopTestUtils.getUltimateTargetObject(outbox);
        doAnswer(inv->{var result=inv.callRealMethod();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){throw new org.springframework.dao.DataAccessResourceFailureException(secret);}});return result;}).when(target).record(any());
        double errors=calls("payment_persist","error"),success=calls("payment_persist","success");
        var response=create("commit-"+UUID.randomUUID(),"ROLLBACK",null);assertThat(response.statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id=?",Long.class,merchant)).isZero();
        assertThat(calls("payment_persist","error")).isEqualTo(errors+1);assertThat(calls("payment_persist","success")).isEqualTo(success);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(spans.getFinishedSpanItems().stream().filter(s->s.getName().equals("payment_persist")).toList()).isNotEmpty());
        assertThat(spans.getFinishedSpanItems().toString()).doesNotContain(secret);
    }
    @Test void backgroundSnapshotIsReadOnlyAndScrapesDoNotQuerySourceTables()throws Exception{
        var target=(OperationalSnapshotRepository)AopTestUtils.getUltimateTargetObject(snapshots);
        doAnswer(inv->{assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");return inv.callRealMethod();})
            .when(target).snapshot();
        long before=jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class);collector().refresh();assertThat(snapshot.up()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(before);clearInvocations(target);
        var response=get(ROOT+"/prometheus",auth(OBSERVER,OBSERVER_PASSWORD));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("fincore_ops_snapshot_up","fincore_ops_snapshot_age_seconds","fincore_backlog_count");verify(target,never()).snapshot();
    }
    @Test void pendingAgesComeFromSourceTimeAndNoFinancialRowsAreChanged(){
        var old=Payment.create(merchant,"OLD-EVENT",BigDecimal.TEN,"INR");Instant past=Instant.now().minusSeconds(600);
        ReflectionTestUtils.setField(old,"createdAt",past);ReflectionTestUtils.setField(old,"updatedAt",past);
        new TransactionTemplate(transactions).execute(tx->{payments.saveAndFlush(old);outbox.record(old);return null;});
        long paymentsBefore=jdbc.queryForObject("SELECT count(*) FROM payments",Long.class),eventsBefore=jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class);
        collector().refresh();assertThat(snapshot.up()).isTrue();assertThat(snapshot.point(Backlog.OUTBOX_PENDING).count()).isPositive();
        assertThat(snapshot.point(Backlog.OUTBOX_PENDING).oldestAgeSeconds()).isGreaterThanOrEqualTo(599);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(paymentsBefore);assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(eventsBefore);
    }
    @Test void failedRefreshKeepsLastValuesButMarksThemUnavailable(){
        collector().refresh();var old=snapshot.point(Backlog.OUTBOX_PENDING);var target=(OperationalSnapshotRepository)AopTestUtils.getUltimateTargetObject(snapshots);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-source-detail")).when(target).snapshot();collector().refresh();
        assertThat(snapshot.up()).isFalse();assertThat(snapshot.available()).isTrue();assertThat(snapshot.point(Backlog.OUTBOX_PENDING)).isEqualTo(old);
    }
    @Test void prometheusCanActuallyScrapeTheAuthenticatedApplication()throws Exception{
        String binary=System.getenv("FINCORE_TEST_PROMETHEUS_BIN");org.junit.jupiter.api.Assumptions.assumeTrue(binary!=null,"Portable Prometheus not selected");
        collector().refresh();Path directory=Files.createDirectories(Path.of("target","observability-prometheus-"+UUID.randomUUID()).toAbsolutePath());
        Path username=directory.resolve("username"),password=directory.resolve("password"),config=directory.resolve("prometheus.yml");
        Files.writeString(username,OBSERVER,StandardCharsets.UTF_8);Files.writeString(password,OBSERVER_PASSWORD,StandardCharsets.UTF_8);
        int probePort;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){probePort=socket.getLocalPort();}
        String alerts=Path.of("observability","alerts.yml").toAbsolutePath().toString().replace('\\','/');
        Files.writeString(config,"global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\nrule_files: ['"+alerts+"']\nscrape_configs:\n  - job_name: fincore-payments\n    metrics_path: "+ROOT+"/prometheus\n    basic_auth:\n      username_file: '"+username.toString().replace('\\','/')+"'\n      password_file: '"+password.toString().replace('\\','/')+"'\n    static_configs:\n      - targets: ['127.0.0.1:"+port+"']\n",StandardCharsets.UTF_8);
        var process=new ProcessBuilder(binary,"--config.file="+config,"--storage.tsdb.path="+directory.resolve("data"),"--web.listen-address=127.0.0.1:"+probePort)
            .redirectOutput(directory.resolve("stdout.log").toFile()).redirectError(directory.resolve("stderr.log").toFile()).start();
        try{
            await().atMost(Duration.ofSeconds(30)).ignoreExceptionsInstanceOf(java.io.IOException.class).untilAsserted(()->{
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+probePort+"/api/v1/query?query="+URLEncoder.encode("up{job=\"fincore-payments\"}",StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);var body=mapper.readTree(response.body());var result=body.path("data").path("result");
                assertThat(result.size()).isPositive();assertThat(result.get(0).path("value").get(1).asText()).isEqualTo("1");
            });
        }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
}
