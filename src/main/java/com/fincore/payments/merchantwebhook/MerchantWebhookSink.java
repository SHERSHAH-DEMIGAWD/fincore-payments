package com.fincore.payments.merchantwebhook;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fincore.payments.domain.PaymentEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** Local demonstration receiver: verifies current signatures and durably deduplicates delivery IDs.
 * Its file journal demonstrates process restart, not independent backup or certified power-loss durability.
 */
public final class MerchantWebhookSink implements AutoCloseable {
    public static final String PATH="/webhooks";
    public static final int MAX_BODY=16384,MAX_RECEIPTS=10000;
    private static final ObjectMapper MAPPER=JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final String secret,keyId;
    private final Clock clock;
    private final Journal journal;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(4);

    public record Delivery(int version,UUID deliveryId,UUID subscriptionId,PaymentEvent event) { }

    public MerchantWebhookSink(int port,Path directory,String secret,String keyId) throws IOException {
        this(port,directory,secret,keyId,Clock.systemUTC());
    }
    MerchantWebhookSink(int port,Path directory,String secret,String keyId,Clock clock) throws IOException {
        if (port<0 || port>65535 || secret==null || !secret.matches("[!-~]{32,256}") || keyId==null || clock==null)
            throw new IllegalArgumentException("Merchant webhook sink configuration is invalid.");
        UUID subscription;
        try { subscription=UUID.fromString(keyId); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Merchant webhook sink configuration is invalid."); }
        if (!subscription.toString().equals(keyId) || subscription.equals(new UUID(0,0)))
            throw new IllegalArgumentException("Merchant webhook sink configuration is invalid.");
        this.secret=secret; this.keyId=keyId; this.clock=clock;
        journal=new Journal(directory,subscription);
        try { server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16); }
        catch (IOException | RuntimeException failed) { journal.close(); executor.shutdownNow(); throw failed; }
        server.createContext(PATH,this::handle); server.setExecutor(executor); server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public int receiptCount() { return journal.size(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals(PATH)
                    || exchange.getRequestURI().getRawQuery()!=null) { respond(exchange,404); return; }
            String id=single(exchange,MerchantWebhookHttpClient.KEY_ID),timestamp=single(exchange,MerchantWebhookHttpClient.TIMESTAMP),signature=single(exchange,MerchantWebhookHttpClient.SIGNATURE);
            if (id==null || !id.equals(keyId) || timestamp==null || !timestamp.matches("[1-9][0-9]{0,11}")
                    || signature==null || !signature.matches("v1=[0-9a-f]{64}")) { respond(exchange,401); return; }
            long signedAt=Long.parseLong(timestamp);
            if (!fresh(signedAt)) { respond(exchange,401); return; }
            byte[] body=exchange.getRequestBody().readNBytes(MAX_BODY+1);
            if (body.length>MAX_BODY) { respond(exchange,413); return; }
            String expected;
            try { expected=MerchantWebhookCrypto.signature(secret,keyId,signedAt,body); }
            catch (IllegalArgumentException invalid) { respond(exchange,400); return; }
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII))
                    || !fresh(signedAt)) { respond(exchange,401); return; }
            int status;
            try {
                var delivery=read(body);
                if (!delivery.subscriptionId().toString().equals(keyId)) status=401;
                else status=journal.accept(delivery,body) ? 204 : 409;
            } catch (IllegalArgumentException malformed) { status=400; }
            catch (IOException unavailable) { status=503; }
            respond(exchange,status);
        }
    }
    private boolean fresh(long signedAt) {
        long now=clock.instant().getEpochSecond(); return signedAt>=now-300 && signedAt<=now+300;
    }
    private static String single(HttpExchange exchange,String header) {
        var values=exchange.getRequestHeaders().get(header); return values!=null && values.size()==1 ? values.getFirst() : null;
    }
    private static Delivery read(byte[] body) {
        try {
            if (body.length<1 || body.length>MAX_BODY) throw new IllegalArgumentException();
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var d=MAPPER.readValue(json,Delivery.class);
            if (d==null || d.version()!=1 || !identity(d.deliveryId()) || !identity(d.subscriptionId()) || d.event()==null)
                throw new IllegalArgumentException();
            var e=d.event(); var p=e.data();
            if (e.schemaVersion()!=1 || !identity(e.eventId()) || !identity(e.paymentId()) || e.eventType()==null
                    || e.paymentVersion()<0 || e.occurredAt()==null || p==null || !e.paymentId().equals(p.id())
                    || p.status()==null || !e.eventType().name().equals("PAYMENT_"+p.status().name())
                    || !money(p.amount()) || p.currency()==null || !p.currency().matches("[A-Z]{3}")
                    || p.merchantReference()==null || p.merchantReference().isBlank() || p.merchantReference().length()>255
                    || p.createdAt()==null || p.updatedAt()==null || p.createdAt().isAfter(p.updatedAt())
                    || !e.occurredAt().equals(p.updatedAt().truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException();
            return d;
        } catch (IOException | ArithmeticException malformed) { throw new IllegalArgumentException("Merchant webhook body is invalid."); }
    }
    private static boolean identity(UUID id) { return id!=null && !id.equals(new UUID(0,0)); }
    private static boolean money(BigDecimal amount) {
        if (amount==null || amount.signum()<=0) return false;
        try { var normalized=amount.stripTrailingZeros(); return normalized.scale()<=2 && normalized.precision()-(long)normalized.scale()<=17; }
        catch (ArithmeticException invalidScale) { return false; }
    }
    private static void respond(HttpExchange exchange,int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        if (status==204) { exchange.sendResponseHeaders(204,-1); return; }
        byte[] body="{\"error\":\"MERCHANT_WEBHOOK_REFUSED\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body);
    }
    @Override public void close() throws IOException { server.stop(0); executor.shutdownNow(); journal.close(); }

    public static int run(String[] args,java.io.PrintStream output) {
        try {
            if (args.length!=2) throw new IllegalArgumentException();
            var options=new HashMap<String,String>();
            for (String arg:args) {
                int separator=arg.indexOf('=');
                if (!arg.startsWith("--") || separator<3 || separator==arg.length()-1
                        || options.putIfAbsent(arg.substring(2,separator),arg.substring(separator+1))!=null) throw new IllegalArgumentException();
            }
            if (!options.keySet().equals(Set.of("port","data-dir"))) throw new IllegalArgumentException();
            var sink=new MerchantWebhookSink(Integer.parseInt(options.get("port")),Path.of(options.get("data-dir")),
                    System.getenv("FINCORE_MERCHANT_SINK_SIGNING_KEY"),System.getenv("FINCORE_MERCHANT_SINK_KEY_ID"));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { sink.close(); } catch (IOException ignored) { } }));
            output.println("{\"status\":\"MERCHANT_WEBHOOK_SINK_READY\",\"port\":"+sink.port()+"}"); output.flush();
            new CountDownLatch(1).await(); return 0;
        } catch (Exception refused) { output.println("{\"status\":\"MERCHANT_WEBHOOK_SINK_START_FAILED\"}"); return 2; }
    }

    private static final class Journal implements AutoCloseable {
        private final Path directory;
        private final UUID subscription;
        private final FileChannel channel;
        private final FileLock lock;
        private final Map<UUID,byte[]> bodies=new HashMap<>();
        private boolean closed;
        Journal(Path directory,UUID subscription) throws IOException {
            this.directory=directory.toAbsolutePath().normalize(); this.subscription=subscription;
            Files.createDirectories(this.directory);
            channel=FileChannel.open(this.directory.resolve(".sink.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock acquired=null;
            try {
                acquired=channel.tryLock(); if (acquired==null) throw new IOException("Merchant webhook sink journal is busy."); lock=acquired;
                Path identity=this.directory.resolve("subscription-id"); byte[] expected=subscription.toString().getBytes(StandardCharsets.US_ASCII);
                if (Files.exists(identity)) {
                    byte[] found; try (var input=Files.newInputStream(identity)) { found=input.readNBytes(37); }
                    if (!Arrays.equals(found,expected)) throw new IOException("Merchant webhook sink subscription differs.");
                } else publish(identity,expected);
                try (var files=Files.newDirectoryStream(this.directory,"*.json")) {
                    for (Path file:files) {
                        if (bodies.size()>=MAX_RECEIPTS) throw new IOException("Merchant webhook sink journal exceeds capacity.");
                        byte[] body; try (var input=Files.newInputStream(file)) { body=input.readNBytes(MAX_BODY+1); }
                        var delivery=read(body);
                        if (!delivery.subscriptionId().equals(subscription) || !file.getFileName().toString().equals(delivery.deliveryId()+".json")
                                || bodies.putIfAbsent(delivery.deliveryId(),body)!=null) throw new IOException("Merchant webhook sink receipt identity differs.");
                    }
                }
            } catch (IOException | RuntimeException failed) {
                if (acquired!=null) acquired.release(); channel.close(); throw failed;
            }
        }
        synchronized boolean accept(Delivery delivery,byte[] body) throws IOException {
            if (closed) throw new IOException("Merchant webhook sink journal is closed.");
            var previous=bodies.get(delivery.deliveryId());
            if (previous!=null) return Arrays.equals(previous,body);
            if (bodies.size()>=MAX_RECEIPTS) throw new IOException("Merchant webhook sink journal is full.");
            if (!delivery.subscriptionId().equals(subscription)) throw new IllegalArgumentException();
            publish(directory.resolve(delivery.deliveryId()+".json"),body); bodies.put(delivery.deliveryId(),body.clone()); return true;
        }
        synchronized int size() { return bodies.size(); }
        private static void publish(Path destination,byte[] body) throws IOException {
            Path temporary=Files.createTempFile(destination.getParent(),".sink-pending-",".tmp"); boolean published=false;
            try {
                try (var output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    var buffer=ByteBuffer.wrap(body); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                }
                Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE); published=true;
            } finally { if (!published) Files.deleteIfExists(temporary); }
        }
        @Override public synchronized void close() throws IOException {
            if (!closed) { closed=true; try { lock.release(); } finally { channel.close(); } }
        }
    }
}
