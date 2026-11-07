package com.fincore.payments.deployment;

import com.fasterxml.jackson.databind.*;
import com.fincore.payments.merchantwebhook.MerchantWebhookSink;
import com.fincore.payments.provider.ProviderWebhookWire;
import java.io.PrintStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.fincore.payments.deployment.SandboxConfiguration.*;

/** Bounded, write-producing acceptance exercise for provisioned local sandbox accounts only. */
public final class SandboxAcceptanceCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SandboxAcceptanceCli() { }
    public static int run(String[] args,PrintStream output){return run(args,System.getenv(),output);}
    static int run(String[] args,Map<String,String> environment,PrintStream output) {
        String stage="configuration";
        try {
            Settings settings=Settings.parse(args,environment);
            // Reserve a new evidence file before any HTTP write. Failed runs retain a failure record.
            try(var file=FileChannel.open(settings.report(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                try(var exercise=new Exercise(settings)) {
                    try {
                        Map<String,Object> result=exercise.execute();exercise.close();write(file,result);output.println(JSON.writeValueAsString(result));return 0;
                    } catch(Exception failure) {
                        stage=exercise.stage;if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                        var result=new LinkedHashMap<String,Object>();result.put("status","SANDBOX_ACCEPTANCE_FAILED");result.put("stage",stage);result.put("realFunds",false);
                        if(failure instanceof HttpMismatch mismatch){result.put("httpStatus",mismatch.actual);result.put("expectedStatus",mismatch.expected);}
                        write(file,result);output.println(JSON.writeValueAsString(result));return 2;
                    }
                }
            }
        }catch(Exception refused){if(refused instanceof InterruptedException)Thread.currentThread().interrupt();
            output.println("{\"status\":\"SANDBOX_ACCEPTANCE_REFUSED\",\"stage\":\""+stage+"\",\"realFunds\":false}");return 2;}
    }
    private static void write(FileChannel file,Object value)throws Exception {
        byte[] bytes=JSON.writeValueAsBytes(value);file.position(0);file.truncate(0);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
    }
    record Settings(URI api,URI provider,int sinkPort,Path report,int requests,int concurrency,String a,String b,
                    String observer,String operator,String providerKey,UUID subscriptionRequest,boolean requireRisk,boolean expectKafka) {
        static Settings parse(String[] args,Map<String,String> env)throws Exception {
            var options=options(args,Set.of("url","provider-url","sink-port","report","requests","concurrency"));
            int sink=Integer.parseInt(options.get("sink-port")),requests=Integer.parseInt(options.get("requests")),concurrency=Integer.parseInt(options.get("concurrency"));
            if(sink<1 || sink>65535 || requests<1 || requests>100 || concurrency<1 || concurrency>8)throw new IllegalArgumentException();
            URI api=loopback(options.get("url")),provider=loopback(options.get("provider-url"));
            if(api.getPort()==provider.getPort() || sink==api.getPort() || sink==provider.getPort())throw new IllegalArgumentException();
            String require=env.getOrDefault("FINCORE_DEMO_REQUIRE_RISK","true");if(!Set.of("true","false").contains(require))throw new IllegalArgumentException();
            String kafka=env.getOrDefault("FINCORE_DEMO_EXPECT_KAFKA","false");if(!Set.of("true","false").contains(kafka))throw new IllegalArgumentException();
            return new Settings(api,provider,sink,Path.of(options.get("report")).toAbsolutePath(),requests,concurrency,
                    password(env,"FINCORE_DEMO_A_PASSWORD"),password(env,"FINCORE_DEMO_B_PASSWORD"),password(env,"FINCORE_DEMO_OBSERVER_PASSWORD"),
                    password(env,"FINCORE_DEMO_OPERATOR_PASSWORD"),password(env,"FINCORE_PROVIDER_API_KEY"),identity(secret(env,"FINCORE_DEMO_SUBSCRIPTION_REQUEST_ID")),Boolean.parseBoolean(require),Boolean.parseBoolean(kafka));
        }
        @Override public String toString(){return "SandboxAcceptanceSettings[REDACTED]";}
    }
    private record Reply(int status,byte[] bytes,HttpHeaders headers) {
        JsonNode json()throws Exception{return JSON.readTree(bytes);}
        String text(){return new String(bytes,StandardCharsets.UTF_8);}
    }
    private static final class Exercise implements AutoCloseable {
        private final Settings settings;
        private final long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
        private final Set<HttpClient> merchantClients=ConcurrentHashMap.newKeySet();
        private final ThreadLocal<HttpClient> aClient=ThreadLocal.withInitial(()->{var client=browser();merchantClients.add(client);return client;});
        private final HttpClient bClient=browser();
        private final HttpClient machine=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        private final Map<String,String> aHeaders=new HashMap<>(),bHeaders=new HashMap<>();
        private String stage="readiness";private UUID merchant,subscription;private MerchantWebhookSink sink;
        private String signingSecret,csrfHeader;private Path sinkDirectory;private long priorReceipts;private boolean subscriptionActive;
        Exercise(Settings settings){this.settings=settings;}
        Map<String,Object> execute()throws Exception {
            String aAuth=basic(MERCHANT_A,settings.a()),bAuth=basic(MERCHANT_B,settings.b());
            aHeaders.put("Authorization",aAuth);bHeaders.put("Authorization",bAuth);
            await(()->get(settings.api(),"/internal/observability/health/readiness",observer()).status()==200);
            stage="identity";
            var me=expect(get(settings.api(),"/api/v1/merchant/me",aHeaders),200).json();merchant=identity(me.path("merchantId").asText());
            require(me.path("username").asText().equals(MERCHANT_A));
            var other=expect(get(settings.api(),"/api/v1/merchant/me",bHeaders),200).json();
            require(other.path("username").asText().equals(MERCHANT_B) && !other.path("merchantId").asText().equals(merchant.toString()));
            expect(get(settings.api(),"/api/v1/merchant/me",Map.of()),401);
            expect(get(settings.api(),"/api/v1/merchant/me",observer()),401);
            expect(get(settings.api(),"/internal/observability/prometheus",aHeaders),401);
            expect(get(settings.api(),"/api/v1/merchant/me",Map.of("Authorization",basic(OPERATOR,settings.operator()))),401);
            expect(post("/api/v1/payments",Map.of("merchantReference","M25-NO-CSRF","amount",1,"currency","INR"),with(aHeaders,"Idempotency-Key",key())),403);
            var csrf=expect(get(settings.api(),"/api/v1/merchant/csrf",aHeaders),200).json();csrfHeader=csrf.path("headerName").asText();aHeaders.put(csrfHeader,csrf.path("token").asText());
            expect(post(ProviderWebhookWire.PATH,Map.of(),Map.of()),401);
            await(()->get(settings.provider(),"/v1/settlements/"+merchant+"/INR",provider()).status()==200);
            stage="subscription";String destination="http://127.0.0.1:"+settings.sinkPort()+"/webhooks";
            var created=expect(post("/api/v1/merchant/webhooks/subscriptions",Map.of("requestId",settings.subscriptionRequest(),"destinationUrl",destination),aHeaders),200).json();
            subscription=identity(created.path("subscription").path("subscriptionId").asText());signingSecret=created.path("signingSecret").asText();
            var subscriptions=expect(get(settings.api(),"/api/v1/merchant/webhooks/subscriptions",aHeaders),200).json().path("subscriptions");
            JsonNode current=null;for(var entry:subscriptions)if(entry.path("subscriptionId").asText().equals(subscription.toString()))current=entry;
            require(current!=null);if(!current.path("state").asText().equals("ACTIVE"))changeSubscription("ACTIVE",current.path("revision").asLong());subscriptionActive=true;
            sinkDirectory=settings.report().getParent().resolve("receiver-"+subscription);sink=new MerchantWebhookSink(settings.sinkPort(),sinkDirectory,signingSecret,subscription.toString());priorReceipts=sink.receiptCount();
            stage="creation";String creationKey=key();var request=paymentBody("M25-PROVIDER",100);
            var original=expect(post("/api/v1/payments",request,with(aHeaders,"Idempotency-Key",creationKey)),201).json();UUID full=identity(original.path("id").asText());
            stage="creation_replay";require(original.equals(expect(post("/api/v1/payments",request,with(aHeaders,"Idempotency-Key",creationKey)),200).json()));
            expect(post("/api/v1/payments",paymentBody("M25-PROVIDER",101),with(aHeaders,"Idempotency-Key",creationKey)),409);
            expect(get(settings.api(),"/api/v1/payments/"+full,bHeaders),404);
            boolean risk=false;
            if(settings.requireRisk()) {
                String hash=com.fincore.payments.settlement.SettlementWire.sha256(creationKey.getBytes(StandardCharsets.UTF_8));
                var decisions=expect(get(settings.api(),"/api/v1/merchant/risk-decisions?limit=100",aHeaders),200).json().path("decisions");
                for(var decision:decisions)if(decision.path("requestKeyHash").asText().equals(hash) && decision.path("outcome").asText().equals("ALLOW"))risk=true;
                require(risk);
            }
            stage="provider_authorize";var authorize=command("AUTHORIZE","APPROVE");require(providerOperation(full,authorize).path("operation").path("state").asText().equals("APPLIED"));
            stage="provider_uncertainty";var capture=command("CAPTURE","TIMEOUT_AFTER_ACCEPT");var uncertain=providerOperation(full,capture);
            require(uncertain.path("operation").path("state").asText().equals("UNKNOWN"));UUID operation=identity(uncertain.path("operation").path("operationId").asText());
            stage="provider_callback";String deliver="/v1/webhooks/"+operation+"/deliver";
            require(expect(send(settings.provider(),deliver,"POST",new byte[0],provider()),200).json().path("acknowledged").asBoolean());
            require(expect(send(settings.provider(),deliver,"POST",new byte[0],provider()),200).json().path("acknowledged").asBoolean());
            require(providerOperation(full,capture).path("operation").path("state").asText().equals("APPLIED"));
            stage="provider_refund";var refund=command("REFUND","APPROVE");providerOperation(full,refund);providerOperation(full,refund);
            require(expect(get(settings.api(),"/api/v1/payments/"+full,aHeaders),200).json().path("status").asText().equals("REFUNDED"));
            stage="provider_journal";require(expect(get(settings.api(),"/api/v1/payments/"+full+"/ledger",aHeaders),200).json().size()==2);
            stage="settlement";
            var statement=expect(get(settings.provider(),"/v1/settlements/"+merchant+"/INR",provider()),200);
            var importHeaders=with(aHeaders,"Fincore-Settlement-Key-Id",statement.headers().firstValue("Fincore-Settlement-Key-Id").orElseThrow());
            importHeaders.put("Fincore-Settlement-Signature",statement.headers().firstValue("Fincore-Settlement-Signature").orElseThrow());
            var imported=expect(send(settings.api(),"/api/v1/merchant/settlement-statements","POST",statement.bytes(),importHeaders),200).json();
            UUID statementId=identity(imported.path("statement").path("statementId").asText());
            var comparison=expect(post("/api/v1/merchant/settlement-statements/"+statementId+"/reports",Map.of("requestId",UUID.randomUUID()),aHeaders),200).json();
            require(comparison.path("report").path("comparison").path("matched").asBoolean());
            expect(get(settings.api(),"/api/v1/merchant/settlement-statements/"+statementId,bHeaders),404);
            stage="amounts";UUID partial=create("M25-AMOUNTS",100);expect(post("/api/v1/payments/"+partial+"/authorize",null,aHeaders),200);
            var c1=amount(partial,1,"CAPTURE",40,2,null);var c2=amount(partial,2,"CAPTURE",60,3,null);
            amount(partial,3,"REFUND",40,0,identity(c1.path("operation").path("operationId").asText()));
            var end=amount(partial,4,"REFUND",60,0,identity(c2.path("operation").path("operationId").asText())).path("accounting");
            require(end.path("status").asText().equals("REFUNDED") && end.path("feeAmount").decimalValue().compareTo(new java.math.BigDecimal("5"))==0
                    && end.path("merchantPayable").decimalValue().compareTo(new java.math.BigDecimal("-5"))==0);
            stage="notifications";delivered(full);delivered(partial);
            stage="delivery_recovery";sink.close();sink=null;UUID failure=create("M25-SINK-OUTAGE",1);
            final JsonNode[] dead={null};await(()->{for(var d:deliveries(failure))if(d.path("state").asText().equals("DEAD")){dead[0]=d;return true;}return false;});
            sink=new MerchantWebhookSink(settings.sinkPort(),sinkDirectory,signingSecret,subscription.toString());
            expect(post("/api/v1/merchant/webhooks/deliveries/"+dead[0].path("deliveryId").asText()+"/retry",
                    Map.of("requestId",UUID.randomUUID(),"expectedGeneration",dead[0].path("retryGeneration").asLong(),"reason","Local sandbox receiver recovered"),aHeaders),200);
            delivered(failure);
            stage="load";var load=load();
            stage="telemetry";String metrics=expect(get(settings.api(),"/internal/observability/prometheus",observer()),200).text();
            require(metrics.contains("fincore_flow_calls_total") && !metrics.contains(merchant.toString()) && !metrics.contains(full.toString()) && !metrics.contains(creationKey));
            stage="outbox";await(()->eventsSent(full) && eventsSent(partial) && eventsSent(failure));
            if(settings.expectKafka()) {stage="kafka_projection";await(()->projection(full) && projection(partial));}
            long receipts=sink.receiptCount()-priorReceipts;require(receipts>=11);
            stage="cleanup";pauseSubscription();
            return Map.of("status","LOCAL_SANDBOX_ACCEPTANCE_PASSED","version",1,"verifiedAt",Instant.now().toString(),"realFunds",false,
                    "checks",List.of("AUTHENTICATION_CSRF_TENANT_ISOLATION","CREATION_REPLAY_AND_CONFLICT","LOST_REPLY_SIGNED_CALLBACK_RECOVERY","FULL_REFUND_JOURNALS",
                    "SIGNED_SETTLEMENT_MATCH","PARTIAL_CAPTURES_RETAINED_FEES","SIGNED_NOTIFICATION_RETRY","CONCURRENT_CREATION","PRIVATE_TELEMETRY","OUTBOX_DELIVERY"),
                    "riskValidated",risk,"kafkaProjectionValidated",settings.expectKafka(),"notificationReceipts",receipts,"load",load,"productionCapacityValidated",false);
        }
        private Map<String,Object> load()throws Exception {
            var pool=Executors.newFixedThreadPool(settings.concurrency());long started=System.nanoTime();
            try {
                String same=key();var body=paymentBody("M25-SAME-INTENT",1);var calls=new ArrayList<Future<Reply>>();
                for(int i=0;i<settings.requests();i++)calls.add(pool.submit(()->post("/api/v1/payments",body,with(aHeaders,"Idempotency-Key",same))));
                int created=0;var ids=new HashSet<String>();
                for(var future:calls){var result=future.get(remaining(),TimeUnit.NANOSECONDS);if(result.status()==201)created++;
                    if(result.status()==409)result=post("/api/v1/payments",body,with(aHeaders,"Idempotency-Key",same));
                    require(result.status()==200 || result.status()==201);ids.add(result.json().path("id").asText());}
                require(created==1 && ids.size()==1);UUID single=identity(ids.iterator().next());require(expect(get(settings.api(),"/api/v1/payments/"+single+"/events",aHeaders),200).json().size()==1);
                var timings=new ArrayList<Future<Long>>();var distinct=ConcurrentHashMap.<UUID>newKeySet();
                for(int i=0;i<settings.requests();i++)timings.add(pool.submit(()->{long begin=System.nanoTime();distinct.add(create("M25-BOUNDED-LOAD",1));return System.nanoTime()-begin;}));
                var values=new ArrayList<Long>();for(var result:timings)values.add(result.get(remaining(),TimeUnit.NANOSECONDS));Collections.sort(values);require(distinct.size()==settings.requests());
                delivered(single);for(UUID id:distinct)delivered(id);
                double elapsed=(System.nanoTime()-started)/1_000_000.0;
                return Map.of("distinctRequests",settings.requests(),"sameIntentRequests",settings.requests(),"concurrency",settings.concurrency(),
                        "elapsedMillis",elapsed,"p50Millis",values.get((int)Math.ceil(values.size()*0.5)-1)/1_000_000.0,
                        "p95Millis",values.get((int)Math.ceil(values.size()*0.95)-1)/1_000_000.0,"maxMillis",values.getLast()/1_000_000.0);
            }finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }
        private UUID create(String reference,int amount)throws Exception{return identity(expect(post("/api/v1/payments",paymentBody(reference,amount),with(aHeaders,"Idempotency-Key",key())),201).json().path("id").asText());}
        private JsonNode providerOperation(UUID payment,Object body)throws Exception{return expect(post("/api/v1/payments/"+payment+"/provider-operations",body,aHeaders),200).json();}
        private JsonNode amount(UUID payment,int version,String action,int amount,int fee,UUID capture)throws Exception {
            var body=new HashMap<String,Object>();body.put("requestId",UUID.randomUUID());body.put("expectedVersion",version);body.put("action",action);body.put("amount",amount);body.put("fee",fee);body.put("captureOperationId",capture);
            return expect(post("/api/v1/payments/"+payment+"/amount-operations",body,aHeaders),200).json();
        }
        private List<JsonNode> deliveries(UUID payment)throws Exception {
            var result=new ArrayList<JsonNode>();for(var value:expect(get(settings.api(),"/api/v1/merchant/webhooks/deliveries?paymentId="+payment,aHeaders),200).json().path("deliveries"))
                if(value.path("subscriptionId").asText().equals(subscription.toString()))result.add(value);return result;
        }
        private void delivered(UUID payment)throws Exception {
            int expected=expect(get(settings.api(),"/api/v1/payments/"+payment+"/events",aHeaders),200).json().size();
            await(()->{var rows=deliveries(payment);return rows.size()==expected && rows.stream().allMatch(row->row.path("state").asText().equals("DELIVERED"));});
        }
        private boolean eventsSent(UUID payment)throws Exception {for(var value:expect(get(settings.api(),"/api/v1/payments/"+payment+"/events",aHeaders),200).json())if(!value.path("status").asText().equals("DELIVERED"))return false;return true;}
        private boolean projection(UUID payment)throws Exception {var reply=get(settings.api(),"/api/v1/payments/"+payment+"/projection",aHeaders);return reply.status()==200 && reply.json().path("status").asText().equals("REFUNDED");}
        private void pauseSubscription()throws Exception {
            if(!subscriptionActive)return;
            for(var value:expect(get(settings.api(),"/api/v1/merchant/webhooks/subscriptions",aHeaders),200).json().path("subscriptions"))
                if(value.path("subscriptionId").asText().equals(subscription.toString())){changeSubscription("PAUSED",value.path("revision").asLong());subscriptionActive=false;return;}
            throw new IllegalArgumentException();
        }
        private void changeSubscription(String state,long revision)throws Exception {expect(post("/api/v1/merchant/webhooks/subscriptions/"+subscription+"/state",
                Map.of("requestId",UUID.randomUUID(),"state",state,"expectedRevision",revision,"reason","Local sandbox acceptance lifecycle"),aHeaders),200);}
        private Reply get(URI base,String path,Map<String,String> headers)throws Exception{return send(base,path,"GET",new byte[0],headers);}
        private Reply post(String path,Object body,Map<String,String> headers)throws Exception{return send(settings.api(),path,"POST",body==null?new byte[0]:JSON.writeValueAsBytes(body),headers);}
        private Reply send(URI base,String path,String method,byte[] body,Map<String,String> headers)throws Exception {
            // Browser identities and machine traffic never share cookies. A write obtains its
            // own current masked token rather than reusing a token across authentication probes.
            if(method.equals("POST") && base.equals(settings.api()) && csrfHeader!=null && headers.containsKey(csrfHeader)) {
                var token=expect(get(settings.api(),"/api/v1/merchant/csrf",aHeaders),200).json();headers=with(headers,csrfHeader,token.path("token").asText());
            }
            long time=remaining();var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofNanos(Math.min(time,Duration.ofSeconds(5).toNanos())));
            headers.forEach(request::header);if(method.equals("POST"))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));else request.GET();
            String authorization=headers.get("Authorization");HttpClient client=authorization!=null && authorization.equals(basic(MERCHANT_A,settings.a()))?aClient.get()
                    :authorization!=null && authorization.equals(basic(MERCHANT_B,settings.b()))?bClient:machine;
            var response=SandboxHttp.send(client,request.build(),2*1024*1024,Duration.ofNanos(Math.min(time,Duration.ofSeconds(5).toNanos())));
            return new Reply(response.statusCode(),response.body(),response.headers());
        }
        private Map<String,String> observer(){return Map.of("Authorization",basic(OBSERVER,settings.observer()));}
        private Map<String,String> provider(){return Map.of("Authorization","Bearer "+settings.providerKey());}
        private long remaining(){long left=deadline-System.nanoTime();if(left<=0)throw new IllegalArgumentException();return left;}
        private interface Check {boolean ready()throws Exception;}
        private void await(Check check)throws Exception {while(true){remaining();try{if(check.ready())return;}catch(java.io.IOException unavailable){ }Thread.sleep(100);}}
        @Override public void close(){
            try{pauseSubscription();}catch(Exception ignored){ }
            if(sink!=null){try{sink.close();}catch(Exception ignored){ }sink=null;}merchantClients.forEach(HttpClient::shutdownNow);aClient.remove();bClient.shutdownNow();machine.shutdownNow();
        }
        private static HttpClient browser(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).cookieHandler(new CookieManager()).build();}
    }
    private static final class HttpMismatch extends RuntimeException {final int actual,expected;HttpMismatch(int actual,int expected){super(null,null,false,false);this.actual=actual;this.expected=expected;}}
    private static Reply expect(Reply reply,int status){if(reply.status()!=status)throw new HttpMismatch(reply.status(),status);return reply;}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException();}
    private static String key(){return "m25-"+UUID.randomUUID();}
    private static Map<String,Object> paymentBody(String reference,int amount){return Map.of("merchantReference",reference,"amount",amount,"currency","INR");}
    private static Map<String,Object> command(String action,String scenario){return Map.of("requestId",UUID.randomUUID(),"action",action,"sandboxScenario",scenario);}
    private static String basic(String username,String password){return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));}
    private static Map<String,String> with(Map<String,String> input,String key,String value){var copy=new HashMap<>(input);copy.put(key,value);return copy;}
}
