package com.fincore.payments.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static com.fincore.payments.deployment.SandboxConfiguration.*;

/** Separate, explicit sandbox lifecycle commands. No public provisioning API or default credentials. */
public final class LocalSandboxCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private LocalSandboxCli() { }
    public static int run(String command,String[] args,PrintStream output){return run(command,args,System.getenv(),output);}
    static int run(String command,String[] args,Map<String,String> env,PrintStream output) {
        try {
            if(command.equals("bootstrap-local-sandbox"))System.setProperty("logback.configurationFile",LocalSandboxCli.class.getResource("/logback-sandbox-tools.xml").toExternalForm());
            Object result=switch(command) {
                case "init-local-sandbox"->initialize(Path.of(options(args,Set.of("directory")).get("directory")));
                case "bootstrap-local-sandbox"->{options(args,Set.of());yield bootstrap(env);}
                case "probe-readiness"->probe(loopback(options(args,Set.of("url")).get("url")),env);
                default->throw new IllegalArgumentException();
            };
            output.println(JSON.writeValueAsString(result));return 0;
        }catch(Exception refused){if(refused instanceof InterruptedException)Thread.currentThread().interrupt();
            output.println("{\"status\":\"LOCAL_SANDBOX_COMMAND_REFUSED\",\"realFunds\":false}");return 2;}
    }
    static Map<String,Object> initialize(Path directory)throws Exception {
        Files.createDirectory(directory);var values=new TreeMap<String,String>();var encoder=new BCryptPasswordEncoder(10);
        for(String key:SECRET_NAMES)values.put(key,random());
        values.put("FINCORE_OBSERVABILITY_PASSWORD_HASH",encoder.encode(values.get("FINCORE_DEMO_OBSERVER_PASSWORD")));
        values.put("FINCORE_OPERATIONS_PASSWORD_HASH",encoder.encode(values.get("FINCORE_DEMO_OPERATOR_PASSWORD")));
        byte[] master=new byte[32];new SecureRandom().nextBytes(master);values.put("FINCORE_MERCHANT_WEBHOOK_MASTER_KEY",Base64.getEncoder().encodeToString(master));
        values.put("FINCORE_PROVIDER_INSTANCE_ID",UUID.randomUUID().toString());values.put("FINCORE_DEMO_SUBSCRIPTION_REQUEST_ID",UUID.randomUUID().toString());
        ownerOnly(directory,true);
        for(var entry:values.entrySet()){Path file=directory.resolve(entry.getKey());Files.writeString(file,entry.getValue(),StandardOpenOption.CREATE_NEW);ownerOnly(file,false);}
        Files.writeString(directory.resolve("sandbox.json"),"{\"version\":1,\"scope\":\"LOCAL_SANDBOX\",\"realFunds\":false}",StandardOpenOption.CREATE_NEW);
        ownerOnly(directory.resolve("sandbox.json"),false);
        return Map.of("status","LOCAL_SANDBOX_CONFIG_CREATED","files",values.size(),"realFunds",false);
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private static void ownerOnly(Path path,boolean directory)throws Exception {
        // File-backed Compose secrets retain host ownership. The private directory protects
        // host traversal; readable individual bind-mounted files work for container UID10001.
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"r--r--r--"));
    }
    static Map<String,Object> bootstrap(Map<String,String> env)throws Exception {
        String url=env.getOrDefault("DB_URL","");
        if(!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|postgres):[0-9]{1,5}/fincore_demo(?:_[a-z0-9_]{1,40})?"))throw new IllegalArgumentException();
        int port=Integer.parseInt(URI.create(url.substring(5)).getPort()+"");if(port<1 || port>65535)throw new IllegalArgumentException();
        String username=env.getOrDefault("DB_USERNAME","");if(!username.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException();
        String password=secret(env,"DB_PASSWORD"),a=password(env,"FINCORE_DEMO_A_PASSWORD"),b=password(env,"FINCORE_DEMO_B_PASSWORD");
        if(a.equals(b))throw new IllegalArgumentException();
        // Flyway owns the same V1-V22 schema; only explicitly named local demo databases are eligible.
        Flyway.configure().dataSource(url+"?connectTimeout=5&socketTimeout=30",username,password).locations("classpath:db/migration").load().migrate();
        var properties=new Properties();properties.setProperty("user",username);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","30");
        try(var connection=DriverManager.getConnection(url,properties)) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement();var lock=statement.executeQuery("SELECT pg_try_advisory_xact_lock(250028102026::bigint)")){if(!lock.next() || !lock.getBoolean(1))throw new IllegalArgumentException();}
            try{account(connection,MERCHANT_A,a);account(connection,MERCHANT_B,b);connection.commit();}
            catch(Exception error){connection.rollback();throw error;}
        }
        return Map.of("status","LOCAL_SANDBOX_ACCOUNTS_READY","accounts",2,"realFunds",false);
    }
    private static void account(Connection connection,String username,String password)throws Exception {
        var encoder=new BCryptPasswordEncoder(10);
        try(var select=connection.prepareStatement("SELECT password_hash,enabled FROM public.merchants WHERE username=? FOR UPDATE NOWAIT")) {
            select.setString(1,username);try(var result=select.executeQuery()) {
                if(result.next()){if(!result.getBoolean(2) || !encoder.matches(password,result.getString(1)))throw new IllegalArgumentException();return;}
            }
        }
        try(var insert=connection.prepareStatement("INSERT INTO public.merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)")) {
            insert.setObject(1,UUID.randomUUID());insert.setString(2,username);insert.setString(3,encoder.encode(password));insert.executeUpdate();
        }
    }
    static Map<String,Object> probe(URI base,Map<String,String> env)throws Exception {
        String auth=Base64.getEncoder().encodeToString((OBSERVER+":"+password(env,"FINCORE_DEMO_OBSERVER_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request=HttpRequest.newBuilder(base.resolve("/internal/observability/health/readiness")).timeout(Duration.ofSeconds(4)).header("Authorization","Basic "+auth).GET().build();
            var response=SandboxHttp.send(client,request,16384,Duration.ofSeconds(4));
            if(response.statusCode()!=200 || !JSON.readTree(response.body()).path("status").asText().equals("UP"))throw new IllegalArgumentException();
        }
        return Map.of("status","READY","realFunds",false);
    }
}
