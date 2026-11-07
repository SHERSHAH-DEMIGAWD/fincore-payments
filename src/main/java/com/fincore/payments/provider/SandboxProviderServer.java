package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.*;

/** Loopback-only runnable sandbox; no Spring context, application database or card data. */
public final class SandboxProviderServer implements AutoCloseable {
    private final SandboxProviderStore store;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(4);
    private final byte[] expectedAuthorization;
    private final SandboxWebhookSender webhookSender;
    private final String settlementKeyId,settlementSigningKey;
    private final boolean automaticCallbacks;
    public SandboxProviderServer(int port,Path directory,UUID instance,String apiKey) throws IOException {
        this(port,directory,instance,apiKey,null);
    }
    public SandboxProviderServer(int port,Path directory,UUID instance,String apiKey,SandboxWebhookSender webhookSender) throws IOException {
        this(port,directory,instance,apiKey,webhookSender,"sandbox-v1",null);
    }
    public SandboxProviderServer(int port,Path directory,UUID instance,String apiKey,SandboxWebhookSender webhookSender,
            String settlementKeyId,String settlementSigningKey) throws IOException {
        this(port,directory,instance,apiKey,webhookSender,settlementKeyId,settlementSigningKey,true);
    }
    public SandboxProviderServer(int port,Path directory,UUID instance,String apiKey,SandboxWebhookSender webhookSender,
            String settlementKeyId,String settlementSigningKey,boolean automaticCallbacks) throws IOException {
        this.automaticCallbacks=automaticCallbacks;
        this.webhookSender=webhookSender;
        this.settlementKeyId=settlementKeyId; this.settlementSigningKey=settlementSigningKey;
        if(settlementSigningKey!=null) com.fincore.payments.settlement.SettlementWire.signature(new byte[0],settlementKeyId,settlementSigningKey);
        if (port<0 || port>65535 || apiKey==null || !apiKey.matches("[!-~]{32,256}")) throw new IllegalArgumentException("Sandbox configuration is invalid.");
        expectedAuthorization=("Bearer "+apiKey).getBytes(StandardCharsets.UTF_8);
        store=new SandboxProviderStore(directory,instance);
        try { server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16); }
        catch (IOException | RuntimeException failure) { store.close(); executor.shutdownNow(); throw failure; }
        server.createContext("/v1/operations",this::handle);
        server.createContext("/v1/webhooks/",this::deliverWebhook);
        server.createContext("/v1/settlements/",this::settlement);
        server.setExecutor(executor); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public int decisionCount() { return store.decisionCount(); }
    private void settlement(HttpExchange exchange) throws IOException {
        try(exchange) {
            var headers=exchange.getRequestHeaders().get("Authorization");
            if(headers==null || headers.size()!=1 || !MessageDigest.isEqual(expectedAuthorization,headers.getFirst().getBytes(StandardCharsets.UTF_8))) {error(exchange,401);return;}
            var path=exchange.getRequestURI().getPath().split("/",-1);
            if(!exchange.getRequestMethod().equals("GET") || exchange.getRequestURI().getQuery()!=null || path.length!=5
                    || exchange.getRequestBody().readNBytes(1).length!=0) {error(exchange,404);return;}
            if(settlementSigningKey==null) {error(exchange,503);return;}
            try {
                UUID merchant=UUID.fromString(path[3]);
                if(!merchant.toString().equals(path[3]) || !path[4].matches("[A-Z]{3}")) throw new IllegalArgumentException();
                var bytes=com.fincore.payments.settlement.SettlementWire.encode(store.settlement(merchant,path[4]));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.getResponseHeaders().set("Fincore-Settlement-Key-Id",settlementKeyId);
                exchange.getResponseHeaders().set("Fincore-Settlement-Signature",com.fincore.payments.settlement.SettlementWire.signature(bytes,settlementKeyId,settlementSigningKey));
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(IllegalArgumentException invalid){error(exchange,400);}
            catch(IOException unavailable){error(exchange,503);}
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        Response confirmation=null;
        try (exchange) {
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization==null || !MessageDigest.isEqual(expectedAuthorization,authorization.getBytes(StandardCharsets.UTF_8))) {
                error(exchange,401); return;
            }
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/v1/operations")
                    || exchange.getRequestURI().getQuery()!=null) { error(exchange,404); return; }
            try {
                byte[] bytes=exchange.getRequestBody().readNBytes(ProviderWire.MAX_BYTES+1);
                var request=ProviderWire.read(bytes,Request.class);
                ProviderWire.validate(request);
                if (!request.operationId().toString().equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))) {
                    error(exchange,409); return;
                }
                var result=store.execute(request);
                confirmation=result.response();
                byte[] response=ProviderWire.mapper().writeValueAsBytes(result.response());
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(200,response.length);
                if (!result.replayed() && request.sandboxScenario()==Scenario.TIMEOUT_AFTER_ACCEPT) {
                    exchange.getResponseBody().write(response,0,8); return; // Accepted result, deliberately incomplete reply.
                }
                exchange.getResponseBody().write(response);
            } catch (IllegalArgumentException conflict) { error(exchange,409); }
            catch (IOException refused) { error(exchange,503); }
        } finally { if (confirmation!=null && webhookSender!=null && automaticCallbacks) webhookSender.enqueue(confirmation); }
    }
    private void deliverWebhook(HttpExchange exchange) throws IOException {
        try (exchange) {
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization==null || !MessageDigest.isEqual(expectedAuthorization,authorization.getBytes(StandardCharsets.UTF_8))) { error(exchange,401); return; }
            String path=exchange.getRequestURI().getPath(),prefix="/v1/webhooks/",suffix="/deliver";
            if (!exchange.getRequestMethod().equals("POST") || exchange.getRequestURI().getQuery()!=null
                    || !path.endsWith(suffix) || path.length()<=prefix.length()+suffix.length()
                    || exchange.getRequestBody().readNBytes(1).length!=0) { error(exchange,404); return; }
            try {
                String identity=path.substring(prefix.length(),path.length()-suffix.length());
                UUID operation=UUID.fromString(identity);
                if (!operation.toString().equals(identity) || webhookSender==null) throw new IllegalArgumentException();
                var result=webhookSender.deliver(store.confirmation(operation));
                byte[] bytes=ProviderWire.mapper().writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } catch (IllegalArgumentException unavailable) { error(exchange,409); }
        }
    }
    private static void error(HttpExchange exchange,int status) throws IOException {
        byte[] body="{\"error\":\"SANDBOX_REQUEST_REFUSED\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body);
    }
    @Override public void close() throws IOException { server.stop(0); executor.shutdownNow(); if (webhookSender!=null) webhookSender.close(); store.close(); }
    public static int run(String[] args,java.io.PrintStream output) {
        try {
            if (args.length!=3) throw new IllegalArgumentException();
            var options=new java.util.HashMap<String,String>();
            for (String arg:args) {
                int separator=arg.indexOf('=');
                if (!arg.startsWith("--") || separator<3 || separator==arg.length()-1
                        || options.putIfAbsent(arg.substring(2,separator),arg.substring(separator+1))!=null) throw new IllegalArgumentException();
            }
            if (!options.keySet().equals(java.util.Set.of("port","data-dir","instance-id"))) throw new IllegalArgumentException();
            int port=Integer.parseInt(options.get("port")); if (port<0 || port>65535) throw new IllegalArgumentException();
            var sender=SandboxWebhookSender.fromEnvironment(System.getenv());
            String automatic=System.getenv().getOrDefault("FINCORE_SANDBOX_WEBHOOK_AUTO_SEND","true");
            if(!java.util.Set.of("true","false").contains(automatic)){if(sender!=null)sender.close();throw new IllegalArgumentException();}
            SandboxProviderServer sandbox;
            try {
                sandbox=new SandboxProviderServer(port,Path.of(options.get("data-dir")),UUID.fromString(options.get("instance-id")),
                        System.getenv("FINCORE_SANDBOX_API_KEY"),sender,
                        System.getenv().getOrDefault("FINCORE_SANDBOX_SETTLEMENT_KEY_ID","sandbox-v1"),System.getenv("FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY"),Boolean.parseBoolean(automatic));
            } catch (Exception failed) { if (sender!=null) sender.close(); throw failed; }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { sandbox.close(); } catch (IOException ignored) { } }));
            output.println("{\"status\":\"SANDBOX_READY\",\"port\":"+sandbox.port()+",\"realFunds\":false}"); output.flush();
            new CountDownLatch(1).await(); return 0;
        } catch (Exception refused) {
            output.println("{\"status\":\"SANDBOX_START_FAILED\",\"realFunds\":false}"); return 2;
        }
    }
}
