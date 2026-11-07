package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Single-process educational provider with a force-written, atomically published decision journal.
 * This demonstrates process-restart recovery, not certified power-loss/remote-storage durability.
 */
public final class SandboxProviderStore implements AutoCloseable {
    public static final int MAX_DECISIONS=10000;
    private final Path directory;
    private final UUID instance;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<UUID,Decision> decisions=new HashMap<>();
    private final Map<UUID,RemotePayment> payments=new HashMap<>();
    private boolean closed;
    public record RemotePayment(UUID paymentId,UUID merchantId,BigDecimal amount,String currency,String state) { }
    public record Decision(int version,long sequence,Request request,Response response,RemotePayment after) { }
    public record Accepted(Response response,boolean replayed) { }

    public SandboxProviderStore(Path directory,UUID instance) throws IOException {
        if (instance==null || instance.equals(new UUID(0,0))) throw new IllegalArgumentException("Sandbox instance is required.");
        this.directory=directory.toAbsolutePath().normalize(); this.instance=instance;
        Files.createDirectories(this.directory);
        lockChannel=FileChannel.open(this.directory.resolve(".provider.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired=null;
        try {
            acquired=lockChannel.tryLock();
            if (acquired==null) throw new IOException("Sandbox journal is busy.");
            lock=acquired;
            Path identity=this.directory.resolve("instance-id");
            if (Files.exists(identity)) {
                byte[] saved;
                try (var input=Files.newInputStream(identity)) { saved=input.readNBytes(37); }
                if (!Arrays.equals(saved,instance.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    throw new IOException("Sandbox journal instance differs.");
            } else publish(identity,instance.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var journal=new ArrayList<Decision>();
            try (var files=Files.newDirectoryStream(this.directory,"*.json")) {
                for (Path path:files) {
                    if (journal.size()>=MAX_DECISIONS) throw new IOException("Sandbox journal exceeds capacity.");
                    byte[] bytes;
                    try (var input=Files.newInputStream(path)) { bytes=input.readNBytes(ProviderWire.MAX_BYTES+1); }
                    var entry=ProviderWire.read(bytes,Decision.class);
                    if (!path.getFileName().toString().equals(entry.request().operationId()+".json")) throw new IOException("Invalid sandbox journal identity.");
                    journal.add(entry);
                }
            }
            journal.sort(Comparator.comparingLong(Decision::sequence));
            for (var entry:journal) {
                if (entry.version()!=1 || entry.sequence()!=decisions.size()+1 || decisions.containsKey(entry.request().operationId()))
                    throw new IOException("Invalid sandbox journal sequence.");
                var expected=decide(entry.request());
                if (!expected.equals(entry)) throw new IOException("Sandbox journal decision differs from its contract.");
                install(entry);
            }
        } catch (IOException | RuntimeException failure) {
            if (acquired!=null) acquired.release(); lockChannel.close(); throw failure;
        }
    }
    public synchronized Accepted execute(Request request) throws IOException {
        if (closed) throw new IOException("Sandbox journal is closed.");
        ProviderWire.validate(request);
        if (!request.instanceId().equals(instance)) throw new IllegalArgumentException("Wrong sandbox instance.");
        var saved=decisions.get(request.operationId());
        if (saved!=null) {
            if (!saved.request().equals(request)) throw new IllegalArgumentException("Operation identity has different intent.");
            return new Accepted(saved.response(),true);
        }
        if (decisions.size()>=MAX_DECISIONS) throw new IOException("Sandbox journal is full.");
        var next=decide(request);
        byte[] bytes=ProviderWire.mapper().writeValueAsBytes(next);
        if (bytes.length>ProviderWire.MAX_BYTES) throw new IOException("Sandbox journal entry exceeds budget.");
        publish(directory.resolve(request.operationId()+".json"),bytes);
        install(next);
        return new Accepted(next.response(),false);
    }
    private Decision decide(Request request) {
        ProviderWire.validate(request);
        if (!request.instanceId().equals(instance)) throw new IllegalArgumentException("Wrong sandbox instance.");
        var previous=payments.get(request.paymentId());
        if (request.action()==Action.AUTHORIZE) {
            if (previous!=null || !request.providerPaymentId().isEmpty()) throw new IllegalArgumentException("Sandbox authorization already exists.");
        } else if (previous==null || !previous.merchantId().equals(request.merchantId())
                || previous.amount().compareTo(request.amount())!=0 || !previous.currency().equals(request.currency())
                || !request.providerPaymentId().equals("sandbox-pay-"+request.paymentId())
                || !previous.state().equals(request.action()==Action.CAPTURE ? "AUTHORIZED" : "CAPTURED"))
            throw new IllegalArgumentException("Sandbox payment state or ownership does not match.");
        Outcome outcome=request.sandboxScenario()==Scenario.DECLINE ? Outcome.DECLINED : Outcome.APPROVED;
        RemotePayment after=outcome==Outcome.DECLINED ? previous : new RemotePayment(request.paymentId(),request.merchantId(),
                request.amount(),request.currency(),switch(request.action()) { case AUTHORIZE -> "AUTHORIZED"; case CAPTURE -> "CAPTURED"; case REFUND -> "REFUNDED"; });
        var response=new Response(1,instance,request.operationId(),request.paymentId(),request.merchantId(),request.action(),request.amount(),
                request.currency(),outcome,outcome==Outcome.APPROVED ? "sandbox-pay-"+request.paymentId() : request.providerPaymentId(),
                "sandbox-op-"+request.operationId());
        return new Decision(1,decisions.size()+1,request,response,after);
    }
    private void install(Decision decision) {
        decisions.put(decision.request().operationId(),decision);
        if (decision.after()!=null) payments.put(decision.request().paymentId(),decision.after());
    }
    private static void publish(Path destination,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(destination.getParent(),".pending-",".tmp");
        try {
            try (var output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(bytes); while (data.hasRemaining()) output.write(data); output.force(true);
            }
            // Fail closed when the filesystem cannot atomically publish a decision.
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized int decisionCount() { return decisions.size(); }
    /** A cumulative statement is derived solely from this independent provider journal. */
    public synchronized com.fincore.payments.dto.Settlement.Statement settlement(UUID merchant,String currency) throws IOException {
        if(closed || !com.fincore.payments.settlement.SettlementWire.uuid(merchant)
                || currency==null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid settlement scope.");
        var lines=decisions.values().stream().filter(d->d.response().merchantId().equals(merchant)
                && d.response().currency().equals(currency) && d.response().outcome()==Outcome.APPROVED
                && d.response().action()!=Action.AUTHORIZE).sorted(Comparator.comparingLong(Decision::sequence))
                .limit(com.fincore.payments.settlement.SettlementWire.MAX_LINES+1L).map(d->new com.fincore.payments.dto.Settlement.Line(
                    d.sequence(),d.response().operationId(),d.response().paymentId(),d.response().action(),d.response().amount(),
                    BigDecimal.ZERO.setScale(2),d.response().providerPaymentId(),d.response().providerOperationId())).toList();
        if(lines.size()>com.fincore.payments.settlement.SettlementWire.MAX_LINES) throw new IOException("Settlement line limit exceeded.");
        var captured=lines.stream().filter(l->l.action()==Action.CAPTURE).map(com.fincore.payments.dto.Settlement.Line::amount).reduce(BigDecimal.ZERO.setScale(2),BigDecimal::add);
        var refunded=lines.stream().filter(l->l.action()==Action.REFUND).map(com.fincore.payments.dto.Settlement.Line::amount).reduce(BigDecimal.ZERO.setScale(2),BigDecimal::add);
        return new com.fincore.payments.dto.Settlement.Statement(1,com.fincore.payments.settlement.SettlementWire.id(instance,merchant,currency,decisions.size()),
                instance,merchant,currency,decisions.size(),lines,captured,refunded,BigDecimal.ZERO.setScale(2),captured.subtract(refunded));
    }
    public synchronized Response confirmation(UUID operationId) {
        if (closed || !decisions.containsKey(operationId)) throw new IllegalArgumentException("Sandbox decision is unavailable.");
        return decisions.get(operationId).response();
    }
    @Override public synchronized void close() throws IOException {
        if (!closed) { closed=true; lock.release(); lockChannel.close(); }
    }
}
