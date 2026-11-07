package com.fincore.payments.operations;

import com.fasterxml.jackson.databind.*;
import com.fincore.payments.FincorePaymentsApplication;
import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.dto.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.fincore.payments.operations.DatabaseRecovery.*;
import static org.assertj.core.api.Assertions.*;

/** Actual pg_dump -> fresh database -> pg_restore -> shipped verifier, never the source as a target. */
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:.*")
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_PG_BIN",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalPostgresRecoveryIT {
    final ObjectMapper mapper=DatabaseRecoveryCli.mapper();
    final UUID merchant=UUID.randomUUID(),foreignMerchant=UUID.randomUUID();
    final String username="restore-"+merchant,password="PRIVATE-RESTORE-PASSWORD",key="restore-"+UUID.randomUUID();
    final CreatePaymentRequest request=new CreatePaymentRequest("PRIVATE-RESTORE-REFERENCE",new BigDecimal("100.00"),"INR");
    Map<String,String> sourceEnvironment;Target source;Path tools,evidence;ConfigurableApplicationContext sourceContext;UUID payment,partialPayment;

    @BeforeAll void sourceFixture()throws Exception {
        sourceEnvironment=Map.of("FINCORE_RECOVERY_DB_URL",System.getenv("FINCORE_TEST_DB_URL"),"FINCORE_RECOVERY_DB_USERNAME",System.getenv("FINCORE_TEST_DB_USERNAME"),
                "FINCORE_RECOVERY_DB_PASSWORD",System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));
        var cluster=Target.from(sourceEnvironment);tools=Path.of(System.getenv("FINCORE_TEST_PG_BIN"));
        // Older migration suites intentionally retain extra schemas. The supported recovery
        // source gets its own fresh database; do not delete their evidence or weaken scope checks.
        String sourceDatabase="fincore_recovery_source_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+cluster.port()+"/postgres",cluster.username(),cluster.password());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE \""+sourceDatabase+"\" WITH TEMPLATE template0 ENCODING 'UTF8'");
        }
        var isolated=new HashMap<>(sourceEnvironment);isolated.put("FINCORE_RECOVERY_DB_URL","jdbc:postgresql://127.0.0.1:"+cluster.port()+"/"+sourceDatabase);
        sourceEnvironment=Map.copyOf(isolated);source=Target.from(sourceEnvironment);
        evidence=Files.createDirectories(Path.of("target","recovery-drill-"+UUID.randomUUID()).toAbsolutePath());
        sourceContext=start(source,WebApplicationType.NONE);
        var jdbc=sourceContext.getBean(JdbcTemplate.class);String hash=new BCryptPasswordEncoder(4).encode(password);
        jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)",merchant,username,hash);
        jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)",foreignMerchant,"restore-"+foreignMerchant,hash);
        var payments=sourceContext.getBean(PaymentService.class);payment=payments.createPayment(merchant,key,request).payment().id();
        payments.authorizePayment(payment);payments.capturePayment(payment);payments.refundPayment(payment);
        partialPayment=payments.createPayment(merchant,"partial-"+key,request).payment().id();payments.authorizePayment(partialPayment);
        var principal=new MerchantPrincipal(merchant,username,hash,true);
        var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",principal.getAuthorities()));
        SecurityContextHolder.setContext(security);
        try{sourceContext.getBean(PaymentAccountingService.class).submit(partialPayment,new PaymentAccounting.Command(UUID.randomUUID(),1,
                PaymentAccounting.Action.CAPTURE,new BigDecimal("40.00"),new BigDecimal("2.00"),null));}
        finally{SecurityContextHolder.clearContext();}
    }
    @AfterAll void close(){if(sourceContext!=null)sourceContext.close();SecurityContextHolder.clearContext();}

    @Test void shippedCommandsRestoreHistoricalRetryOwnershipJournalsAndGuards()throws Exception {
        var before=sourceInventory();var restored=restore(backup(true,null));
        var verified=invokeJar("verify-recovery-restore",verifyArgs(restored,restored.directory().resolve("verified.json")),restored.environment());
        assertThat(verified.exit()).isZero();assertThat(verified.json().path("restoredDatabaseReadOnly").asBoolean()).isTrue();
        assertThat(verified.json().path("restoredIdentitySha256").asText()).isEqualTo(restored.target().identityHash()).isNotEqualTo(source.identityHash());
        assertThat(verified.json().path("externalResourcesRecovered").asBoolean()).isFalse();assertThat(verified.json().path("deletionPermitted").asBoolean()).isFalse();
        try(var app=start(restored.target(),WebApplicationType.SERVLET);var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            int port=((ServletWebServerApplicationContext)app).getWebServer().getPort();String root="http://127.0.0.1:"+port;
            String auth=basic(username,password);
            var csrf=mapper.readTree(client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/merchant/csrf")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
            var replay=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/payments")).header("Authorization",auth)
                    .header(csrf.path("headerName").asText(),csrf.path("token").asText()).header("Content-Type","application/json").header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(200);assertThat(mapper.readTree(replay.body()).path("id").asText()).isEqualTo(payment.toString());
            assertThat(mapper.readTree(replay.body()).path("status").asText()).isEqualTo("CREATED");
            var current=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/payments/"+payment)).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(mapper.readTree(current.body()).path("status").asText()).isEqualTo("REFUNDED");
            var accounting=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/payments/"+partialPayment+"/accounting")).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(accounting.statusCode()).isEqualTo(200);var amounts=mapper.readTree(accounting.body());
            assertThat(amounts.path("capturedAmount").decimalValue()).isEqualByComparingTo("40.00");assertThat(amounts.path("feeAmount").decimalValue()).isEqualByComparingTo("2.00");
            var foreign=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/payments/"+payment)).header("Authorization",basic("restore-"+foreignMerchant,password)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(404);
        }
        assertThat(verify(restored,restored.directory().resolve("verified-after-api.json"))).isZero();
        try(var connection=writable(restored.target());var statement=connection.createStatement()) {
            assertThatThrownBy(()->statement.executeUpdate("UPDATE public.ledger_entries SET amount=amount+1")).isInstanceOf(SQLException.class);
        }
        assertThat(sourceInventory()).isEqualTo(before);
    }
    @Test void concurrentSourceCommitIsExcludedByTheSharedExportedSnapshot()throws Exception {
        UUID marker=UUID.randomUUID();
        Dump concurrent=(binary,target,snapshot,path)->{
            try(var connection=writable(source);var insert=connection.prepareStatement("INSERT INTO merchants(id,username,enabled) VALUES(?,?,false)")) {
                insert.setObject(1,marker);insert.setString(2,"after-snapshot-"+marker);insert.executeUpdate();
            }
            DatabaseRecovery.dump(binary,target,snapshot,path);
        };
        var restored=restore(backup(false,concurrent));assertThat(verify(restored,restored.directory().resolve("verified.json"))).isZero();
        try(var connection=restored.target().connect()){assertThat(scalar(connection,"SELECT count(*) FROM merchants WHERE id='"+marker+"'")).isEqualTo("0");}
        try(var connection=source.connect()){assertThat(scalar(connection,"SELECT count(*) FROM merchants WHERE id='"+marker+"'")).isEqualTo("1");}
    }
    @Test void sameLengthRowChangeIsRefusedWithoutChangingSourceOrWritingSuccessEvidence()throws Exception {
        var restored=restore(backup(false,null));var before=sourceInventory();
        try(var connection=writable(restored.target());var update=connection.prepareStatement("UPDATE merchants SET enabled=false WHERE id=?")){update.setObject(1,merchant);update.executeUpdate();}
        Path report=restored.directory().resolve("must-not-exist.json");assertThat(verify(restored,report)).isEqualTo(2);assertThat(report).doesNotExist();
        assertThat(sourceInventory()).isEqualTo(before);
    }
    @Test void changedArchiveAndWrongTrustedReferenceAreRefused()throws Exception {
        var restored=restore(backup(false,null));Path archive=restored.directory().resolve("database.dump");byte[] bytes=Files.readAllBytes(archive);bytes[bytes.length/2]^=1;Files.write(archive,bytes);
        assertThat(verify(restored,restored.directory().resolve("bad-archive.json"))).isEqualTo(2);
        String[] args=verifyArgs(restored,restored.directory().resolve("bad-reference.json"));args[1]="--manifest-sha256="+"0".repeat(64);
        assertThat(run("verify-recovery-restore",args,restored.environment(),DatabaseRecovery::dump).exit()).isEqualTo(2);
    }
    @Test void restoredDisabledGuardsAreRefusedEvenWhenAllRowValuesMatch()throws Exception {
        var restored=restore(backup(false,null));
        try(var connection=writable(restored.target());var statement=connection.createStatement()){statement.execute("ALTER TABLE merchants DISABLE TRIGGER USER");}
        assertThat(verify(restored,restored.directory().resolve("bad-schema.json"))).isEqualTo(2);
    }
    @Test void missingRowsAndMissingIndexesAreRefused()throws Exception {
        var lostRows=restore(backup(false,null));
        try(var connection=writable(lostRows.target());var statement=connection.createStatement()){statement.execute("TRUNCATE public.idempotency_records");}
        assertThat(verify(lostRows,lostRows.directory().resolve("missing-rows.json"))).isEqualTo(2);
        var lostIndex=restore(backup(false,null));
        try(var connection=writable(lostIndex.target());var statement=connection.createStatement()){statement.execute("DROP INDEX public.idx_ledger_entries_account_currency");}
        assertThat(verify(lostIndex,lostIndex.directory().resolve("missing-index.json"))).isEqualTo(2);
    }
    @Test void sourceVerificationAndExistingReportOverwriteAreRefused()throws Exception {
        var restored=restore(backup(false,null));Path report=restored.directory().resolve("verified.json");
        assertThat(verify(restored,report)).isZero();byte[] original=Files.readAllBytes(report);assertThat(verify(restored,report)).isEqualTo(2);assertThat(Files.readAllBytes(report)).isEqualTo(original);
        assertThat(run("verify-recovery-restore",verifyArgs(restored,restored.directory().resolve("source.json")),sourceEnvironment,DatabaseRecovery::dump).exit()).isEqualTo(2);
    }
    @Test void failedBackupHasNoCompletedManifestAndExistingBundleCannotBeOverwritten()throws Exception {
        Path folder=evidence.resolve("failed-"+UUID.randomUUID());var before=sourceInventory();
        var failed=run("create-recovery-backup",backupArgs(folder),sourceEnvironment,(binary,target,snapshot,path)->{Files.writeString(path.resolve("database.dump"),"partial");throw new IOException("PRIVATE-DUMP-FAILURE");});
        assertThat(failed.exit()).isEqualTo(2);assertThat(folder.resolve("manifest.json")).doesNotExist();assertThat(failed.raw()).doesNotContain("PRIVATE",folder.toString());
        var bundle=backup(false,null);byte[] original=Files.readAllBytes(bundle.directory().resolve("manifest.json"));
        assertThat(run("create-recovery-backup",backupArgs(bundle.directory()),sourceEnvironment,DatabaseRecovery::dump).exit()).isEqualTo(2);
        assertThat(Files.readAllBytes(bundle.directory().resolve("manifest.json"))).isEqualTo(original);assertThat(sourceInventory()).isEqualTo(before);
    }
    private record Bundle(Path directory,String manifestHash) { }
    private record Restored(Path directory,String manifestHash,Map<String,String> environment,Target target) { }
    private record Result(int exit,JsonNode json,String raw) { }
    private String[] backupArgs(Path directory){return new String[]{"--directory="+directory,"--pg-dump="+tools.resolve(binary("pg_dump"))};}
    private String[] verifyArgs(Restored restored,Path report){return new String[]{"--directory="+restored.directory(),"--manifest-sha256="+restored.manifestHash(),"--report="+report};}
    private Bundle backup(boolean packaged,Dump dump)throws Exception {
        Path directory=evidence.resolve("bundle-"+UUID.randomUUID());
        Result result=packaged?invokeJar("create-recovery-backup",backupArgs(directory),sourceEnvironment):run("create-recovery-backup",backupArgs(directory),sourceEnvironment,dump==null?DatabaseRecovery::dump:dump);
        assertThat(result.exit()).as(result.raw()).isZero();return new Bundle(directory,result.json().path("manifestSha256").asText());
    }
    private Restored restore(Bundle bundle)throws Exception {
        String database="fincore_restore_"+UUID.randomUUID().toString().replace("-","");
        // New generated database only: no DROP, --clean or --create restore option.
        try(var connection=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+source.port()+"/postgres",source.username(),source.password());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE \""+database+"\" WITH TEMPLATE template0 ENCODING 'UTF8'");
        }
        var environment=new HashMap<>(sourceEnvironment);environment.put("FINCORE_RECOVERY_DB_URL","jdbc:postgresql://127.0.0.1:"+source.port()+"/"+database);Target target=Target.from(environment);
        var process=new ProcessBuilder(tools.resolve(binary("pg_restore")).toString(),"--no-password","--exit-on-error","--single-transaction","--no-owner","--no-privileges",
                "--host=127.0.0.1","--port="+source.port(),"--username="+source.username(),"--dbname="+database,bundle.directory().resolve("database.dump").toString());
        process.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("PG"));process.environment().put("PGPASSWORD",source.password());process.environment().put("PGCONNECT_TIMEOUT","5");
        Path log=bundle.directory().resolve("pg-restore.log");process.redirectErrorStream(true).redirectOutput(log.toFile());Process child=process.start();
        try{assertThat(child.waitFor(120,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).as(Files.readString(log)).isZero();}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
        return new Restored(bundle.directory(),bundle.manifestHash(),Map.copyOf(environment),target);
    }
    private int verify(Restored restored,Path report)throws Exception{return run("verify-recovery-restore",verifyArgs(restored,report),restored.environment(),DatabaseRecovery::dump).exit();}
    private Result run(String command,String[] args,Map<String,String> environment,Dump dump)throws Exception {
        var bytes=new ByteArrayOutputStream();int exit=DatabaseRecoveryCli.run(command,args,environment,new PrintStream(bytes),dump);String raw=bytes.toString(StandardCharsets.UTF_8);
        return new Result(exit,mapper.readTree(raw),raw);
    }
    private Result invokeJar(String command,String[] args,Map<String,String> environment)throws Exception {
        var arguments=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",binary("java")).toString(),"-jar",Path.of("target/fincore-payments-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),command));arguments.addAll(List.of(args));
        var builder=new ProcessBuilder(arguments);builder.environment().putAll(environment);builder.environment().putAll(Map.of("FINCORE_OBSERVABILITY_ENABLED","true","FINCORE_OBSERVABILITY_PASSWORD_HASH","invalid","FINCORE_RISK_ENABLED","true","FINCORE_RISK_REDIS_PORT","invalid"));
        Path out=evidence.resolve("cli-"+UUID.randomUUID()+".json"),err=evidence.resolve("cli-"+UUID.randomUUID()+".err");Process child=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try{assertThat(child.waitFor(150,TimeUnit.SECONDS)).isTrue();String raw=Files.readString(out);assertThat(Files.readString(err)).doesNotContain("Spring Boot","Hikari","Exception");return new Result(child.exitValue(),mapper.readTree(raw),raw);}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
    private Inventory sourceInventory()throws Exception{try(var connection=source.connect()){return inventory(connection);}}
    private Connection writable(Target target)throws SQLException{return DriverManager.getConnection(target.url(),target.username(),target.password());}
    private String binary(String name){return name+(System.getProperty("os.name").startsWith("Windows")?".exe":"");}
    private String basic(String user,String secret){return "Basic "+Base64.getEncoder().encodeToString((user+":"+secret).getBytes(StandardCharsets.UTF_8));}
    private ConfigurableApplicationContext start(Target target,WebApplicationType web) {
        return new SpringApplicationBuilder(FincorePaymentsApplication.class).web(web).run("--server.port=0","--spring.datasource.url="+target.url(),
                "--spring.datasource.username="+target.username(),"--spring.datasource.password="+target.password(),"--fincore.outbox.enabled=false",
                "--fincore.merchant-webhooks.enabled=false","--fincore.merchant-webhooks.dispatch-enabled=false","--fincore.risk.enabled=false",
                "--fincore.observability.enabled=false","--management.tracing.enabled=false","--fincore.provider.enabled=false","--fincore.provider-webhook.enabled=false",
                "--fincore.settlement.enabled=false","--fincore.events.transport=local","--fincore.kafka.listener-auto-startup=false","--spring.kafka.admin.auto-create=false");
    }
}
