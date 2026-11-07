package com.fincore.payments.deployment;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** The shipped JAR runs bootstrap, API, provider, readiness and acceptance over real local HTTP/PG. */
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalPostgresSandboxAcceptanceIT {
    final ObjectMapper json=new ObjectMapper();
    final List<Process> children=new ArrayList<>();
    Map<String,String> environment;Path evidence;String url,username,password;int apiPort,providerPort,sinkPort;boolean kafka;
    @BeforeAll void start()throws Exception {
        evidence=Files.createDirectories(Path.of("target","sandbox-acceptance-"+UUID.randomUUID()).toAbsolutePath());
        String supplied=System.getenv("FINCORE_TEST_DB_URL");var cluster=URI.create(supplied.substring(5));username=System.getenv("FINCORE_TEST_DB_USERNAME");password=System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD","");
        String database="fincore_demo_"+UUID.randomUUID().toString().replace("-","");url="jdbc:postgresql://127.0.0.1:"+cluster.getPort()+"/"+database;
        try(var connection=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+cluster.getPort()+"/postgres",username,password);var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE \""+database+"\" WITH TEMPLATE template0 ENCODING 'UTF8'");
        }
        Path secrets=evidence.resolve("private-config");assertThat(invoke(Map.of(),"init-local-sandbox","--directory="+secrets).exit()).isZero();
        var env=new HashMap<String,String>();for(String key:SandboxConfiguration.SECRET_NAMES)env.put(key,Files.readString(secrets.resolve(key)));
        env.put("DB_URL",url);env.put("DB_USERNAME",username);env.put("DB_PASSWORD",password);environment=Map.copyOf(env);
        assertThat(invoke(environment,"bootstrap-local-sandbox").exit()).isZero();
        apiPort=freePort();providerPort=freePort();sinkPort=freePort();
        var provider=new HashMap<>(environment);provider.putAll(Map.of("FINCORE_SANDBOX_API_KEY",environment.get("FINCORE_PROVIDER_API_KEY"),
                "FINCORE_SANDBOX_WEBHOOK_URL","http://127.0.0.1:"+apiPort+"/api/v1/provider-webhooks/sandbox",
                "FINCORE_SANDBOX_WEBHOOK_KEY",environment.get("FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY"),"FINCORE_SANDBOX_WEBHOOK_AUTO_SEND","false",
                "FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY",environment.get("FINCORE_SETTLEMENT_SIGNING_KEY")));
        launch(provider,"provider-sandbox","--port="+providerPort,"--data-dir="+evidence.resolve("provider"),"--instance-id="+environment.get("FINCORE_PROVIDER_INSTANCE_ID"));
        var api=new HashMap<>(environment);
        api.putAll(Map.of("FINCORE_PROVIDER_ENABLED","true","FINCORE_PROVIDER_BASE_URL","http://127.0.0.1:"+providerPort,
                "FINCORE_PROVIDER_WEBHOOK_ENABLED","true","FINCORE_PROVIDER_WEBHOOK_INSTANCE_ID",environment.get("FINCORE_PROVIDER_INSTANCE_ID"),
                "FINCORE_SETTLEMENT_ENABLED","true","FINCORE_SETTLEMENT_INSTANCE_ID",environment.get("FINCORE_PROVIDER_INSTANCE_ID"),
                "FINCORE_OBSERVABILITY_ENABLED","true","FINCORE_OBSERVABILITY_USERNAME",SandboxConfiguration.OBSERVER,
                "FINCORE_OPERATIONS_USERNAME",SandboxConfiguration.OPERATOR,"FINCORE_OUTBOX_ENABLED","true"));
        kafka=System.getenv("FINCORE_TEST_KAFKA_BOOTSTRAP")!=null;
        if(kafka)api.putAll(Map.of("FINCORE_EVENT_TRANSPORT","kafka","KAFKA_BOOTSTRAP_SERVERS",System.getenv("FINCORE_TEST_KAFKA_BOOTSTRAP"),"FINCORE_KAFKA_TOPIC",database,"FINCORE_KAFKA_CONSUMER_GROUP",database));
        api.putAll(Map.of("FINCORE_MERCHANT_WEBHOOKS_ENABLED","true","FINCORE_MERCHANT_WEBHOOK_DISPATCH_ENABLED","true",
                "FINCORE_MERCHANT_WEBHOOK_ALLOWED_DESTINATIONS","http://127.0.0.1:"+sinkPort+"/webhooks","FINCORE_MERCHANT_WEBHOOK_MAX_ATTEMPTS","2",
                "FINCORE_MERCHANT_WEBHOOK_INITIAL_BACKOFF_MILLIS","100","FINCORE_MERCHANT_WEBHOOK_MAX_BACKOFF_MILLIS","500","FINCORE_MERCHANT_WEBHOOK_FIXED_DELAY_MS","200",
                "FINCORE_RISK_ENABLED","false","FINCORE_TRACE_EXPORT_ENABLED","false"));
        launch(api,"--server.address=127.0.0.1","--server.port="+apiPort,"--fincore.outbox.fixed-delay-ms=200");
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            while(true) {
                try{var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+apiPort+"/api/v1/merchant/me")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()==401)break;}
                catch(java.io.IOException notStarted){ }
                if(System.nanoTime()>deadline || children.stream().anyMatch(p->!p.isAlive()))throw new AssertionError("Packaged sandbox API failed to become ready; inspect protected test logs.");Thread.sleep(200);
            }
        }
    }
    @AfterAll void stop()throws Exception {
        for(var child:children.reversed())if(child.isAlive()){child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    @Test void completePackagedSandboxPreservesSecurityRecoveryAccountingAndBoundedConcurrency()throws Exception {
        Path report=evidence.resolve("acceptance.json");var env=new HashMap<>(environment);env.put("FINCORE_DEMO_REQUIRE_RISK","false");
        env.put("FINCORE_DEMO_EXPECT_KAFKA",Boolean.toString(kafka));
        var result=invoke(env,"sandbox-acceptance",acceptanceArgs(report));
        assertThat(result.exit()).as(result.output()).isZero();var saved=json.readTree(Files.readString(report));
        assertThat(saved.path("status").asText()).isEqualTo("LOCAL_SANDBOX_ACCEPTANCE_PASSED");assertThat(saved.path("checks").size()).isEqualTo(10);
        assertThat(saved.path("riskValidated").asBoolean()).isFalse();assertThat(saved.path("productionCapacityValidated").asBoolean()).isFalse();
        assertThat(saved.path("kafkaProjectionValidated").asBoolean()).isEqualTo(kafka);
        assertThat(saved.path("load").path("distinctRequests").asInt()).isEqualTo(24);assertThat(saved.path("notificationReceipts").asInt()).isGreaterThanOrEqualTo(36);
        for(String secret:environment.values())if(secret.length()>=32)assertThat(result.output()).doesNotContain(secret);
        assertThat(count("payment_provider_operations")).isEqualTo(3);assertThat(count("ledger_transactions")).isEqualTo(8);assertThat(count("ledger_entries")).isEqualTo(16);
        assertThat(count("payments")).isEqualTo(28);assertThat(count("merchant_webhook_retries")).isEqualTo(1);
        assertThat(count("settlement_reports")).isEqualTo(1);assertThat(invoke(environment,"bootstrap-local-sandbox").exit()).isZero();assertThat(count("merchants")).isEqualTo(3);
        long payments=count("payments");byte[] original=Files.readAllBytes(report);
        assertThat(invoke(env,"sandbox-acceptance",acceptanceArgs(report)).exit()).isEqualTo(2);assertThat(count("payments")).isEqualTo(payments);assertThat(Files.readAllBytes(report)).isEqualTo(original);
        var changed=new HashMap<>(environment);changed.put("FINCORE_DEMO_A_PASSWORD","z".repeat(64));assertThat(invoke(changed,"bootstrap-local-sandbox").exit()).isEqualTo(2);assertThat(count("merchants")).isEqualTo(3);
    }
    @Test void packagedReadinessAuthenticatesAndInvalidCredentialsRefuseBeforeFinancialWork()throws Exception {
        assertThat(invoke(environment,"probe-readiness","--url=http://127.0.0.1:"+apiPort).exit()).isZero();long before=count("payments");
        var bad=new HashMap<>(environment);bad.put("FINCORE_DEMO_A_PASSWORD","z".repeat(64));bad.put("FINCORE_DEMO_REQUIRE_RISK","false");
        var result=invoke(bad,"sandbox-acceptance",acceptanceArgs(evidence.resolve("refused.json")));
        assertThat(result.exit()).isEqualTo(2);assertThat(result.output()).doesNotContain("z".repeat(64));assertThat(count("payments")).isEqualTo(before);
    }
    private String[] acceptanceArgs(Path report){return new String[]{"--url=http://127.0.0.1:"+apiPort,"--provider-url=http://127.0.0.1:"+providerPort,"--sink-port="+sinkPort,"--report="+report,"--requests=24","--concurrency=4"};}
    private record Result(int exit,String output) { }
    private Result invoke(Map<String,String> env,String command,String... args)throws Exception {
        var list=new ArrayList<String>();list.add(command);list.addAll(List.of(args));Path out=evidence.resolve("command-"+UUID.randomUUID()+".json"),err=evidence.resolve("command-"+UUID.randomUUID()+".err");
        var builder=builder(env,list.toArray(String[]::new));var child=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try{assertThat(child.waitFor(210,TimeUnit.SECONDS)).isTrue();String output=Files.readString(out);assertThat(Files.readString(err)).doesNotContain("Exception","Spring Boot","Hikari");return new Result(child.exitValue(),output);}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
    private void launch(Map<String,String> env,String... args)throws Exception {Path log=evidence.resolve("service-"+UUID.randomUUID()+".log");children.add(builder(env,args).redirectErrorStream(true).redirectOutput(log.toFile()).start());}
    private ProcessBuilder builder(Map<String,String> env,String... args) {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var list=new ArrayList<String>(List.of(java,"-jar",Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString()));list.addAll(List.of(args));
        var builder=new ProcessBuilder(list);builder.environment().putAll(env);return builder;
    }
    private long count(String table)throws Exception {if(!Set.of("payments","merchants","payment_provider_operations","ledger_transactions","ledger_entries","merchant_webhook_retries","settlement_reports").contains(table))throw new IllegalArgumentException();
        try(var connection=DriverManager.getConnection(url,username,password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM public."+table)){rows.next();return rows.getLong(1);}}
    private int freePort()throws Exception {try(var server=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return server.getLocalPort();}}
}
