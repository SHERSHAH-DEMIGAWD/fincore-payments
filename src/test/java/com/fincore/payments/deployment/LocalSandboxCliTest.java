package com.fincore.payments.deployment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.fincore.payments.deployment.SandboxConfiguration.*;

class LocalSandboxCliTest {
    @TempDir Path directory;
    @Test void initializationProducesDistinctPrivateInputsAndMatchingVerifiers()throws Exception {
        Path config=directory.resolve("config");var out=new ByteArrayOutputStream();
        assertThat(LocalSandboxCli.run("init-local-sandbox",new String[]{"--directory="+config},Map.of(),new PrintStream(out))).isZero();
        var values=new HashSet<String>();for(String key:SECRET_NAMES){String value=Files.readString(config.resolve(key));assertThat(value).isNotBlank();values.add(value);assertThat(out.toString()).doesNotContain(value);}
        assertThat(values).hasSize(SECRET_NAMES.size());var encoder=new BCryptPasswordEncoder();
        assertThat(encoder.matches(Files.readString(config.resolve("FINCORE_DEMO_OBSERVER_PASSWORD")),Files.readString(config.resolve("FINCORE_OBSERVABILITY_PASSWORD_HASH")))).isTrue();
        assertThat(encoder.matches(Files.readString(config.resolve("FINCORE_DEMO_OPERATOR_PASSWORD")),Files.readString(config.resolve("FINCORE_OPERATIONS_PASSWORD_HASH")))).isTrue();
        assertThat(Base64.getDecoder().decode(Files.readString(config.resolve("FINCORE_MERCHANT_WEBHOOK_MASTER_KEY")))).hasSize(32);
        assertThat(out.toString()).doesNotContain(config.toString());byte[] original=Files.readAllBytes(config.resolve("FINCORE_PROVIDER_API_KEY"));
        assertThat(LocalSandboxCli.run("init-local-sandbox",new String[]{"--directory="+config},Map.of(),new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
        assertThat(Files.readAllBytes(config.resolve("FINCORE_PROVIDER_API_KEY"))).isEqualTo(original);
    }
    @Test void fileInputsAreBoundedStrictUtf8AndUnambiguous()throws Exception {
        Path secret=directory.resolve("secret");Files.writeString(secret,"a".repeat(64));
        var input=Map.of("FINCORE_DEMO_A_PASSWORD_FILE",secret.toString());assertThat(SandboxConfiguration.secret(input,"FINCORE_DEMO_A_PASSWORD")).isEqualTo("a".repeat(64));
        assertThatThrownBy(()->SandboxConfiguration.secret(Map.of("FINCORE_DEMO_A_PASSWORD_FILE",secret.toString(),"FINCORE_DEMO_A_PASSWORD","other"),"FINCORE_DEMO_A_PASSWORD")).isInstanceOf(IllegalArgumentException.class);
        Files.write(secret,new byte[]{(byte)0xff});assertThatThrownBy(()->SandboxConfiguration.secret(input,"FINCORE_DEMO_A_PASSWORD")).isInstanceOf(java.nio.charset.CharacterCodingException.class);
        Files.write(secret,new byte[4097]);assertThatThrownBy(()->SandboxConfiguration.secret(input,"FINCORE_DEMO_A_PASSWORD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SandboxConfiguration.secret(input,"ARBITRARY_KEY")).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:8080","http://remote:8080","https://127.0.0.1:8080","http://127.0.0.1:8080/path","http://user@127.0.0.1:8080","http://127.0.0.1:8080?x=1"})
    void networkInputsStayInTheLiteralLocalSandbox(String uri){assertThatThrownBy(()->loopback(uri)).isInstanceOf(IllegalArgumentException.class);}
    @Test void bootstrapRefusesAnOperationalDatabaseBeforeAnyConnection() {
        assertThatThrownBy(()->LocalSandboxCli.bootstrap(Map.of("DB_URL","jdbc:postgresql://127.0.0.1:1/payments","DB_USERNAME","private"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void workloadBoundsAreEnforcedBeforeHttpWrites()throws Exception {
        var env=inputs();
        for(String[] args:List.of(arguments("101","4"),arguments("24","9"),arguments("0","1")))
            assertThatThrownBy(()->SandboxAcceptanceCli.Settings.parse(args,env)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SandboxAcceptanceCli.Settings.parse(arguments("24","4"),env).toString()).doesNotContain("a".repeat(64),"b".repeat(64));
    }
    @Test void readinessUsesAuthenticatedGetAndNeverFollowsRedirects()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);var status=new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext("/internal/observability/health/readiness",exchange->{try(exchange){assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");byte[] body="{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            if(status.get()==302)exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/elsewhere");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);}});server.start();
        try{URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());assertThat(LocalSandboxCli.probe(base,inputs()).get("status")).isEqualTo("READY");
            status.set(302);assertThatThrownBy(()->LocalSandboxCli.probe(base,inputs())).isInstanceOf(IllegalArgumentException.class);}
        finally{server.stop(0);}
    }
    private Map<String,String> inputs(){return Map.of("FINCORE_DEMO_A_PASSWORD","a".repeat(64),"FINCORE_DEMO_B_PASSWORD","b".repeat(64),
            "FINCORE_DEMO_OBSERVER_PASSWORD","c".repeat(64),"FINCORE_DEMO_OPERATOR_PASSWORD","d".repeat(64),"FINCORE_PROVIDER_API_KEY","e".repeat(64),"FINCORE_DEMO_SUBSCRIPTION_REQUEST_ID",UUID.randomUUID().toString());}
    private String[] arguments(String requests,String concurrency){return new String[]{"--url=http://127.0.0.1:1","--provider-url=http://127.0.0.1:2","--sink-port=3","--report="+directory.resolve("report.json"),"--requests="+requests,"--concurrency="+concurrency};}
}
