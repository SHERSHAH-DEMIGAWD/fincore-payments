package com.fincore.payments.observability;

import com.fincore.payments.repository.OperationalSnapshotRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class OperationalSnapshotCollector implements AutoCloseable {
    private final OperationalSnapshotRepository repository;private final OperationalSnapshot snapshot;private final MeterRegistry meters;
    private boolean failureReported;
    private final long intervalMillis;private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"fincore-observability-snapshot");thread.setDaemon(true);return thread;});
    public OperationalSnapshotCollector(OperationalSnapshotRepository repository,OperationalSnapshot snapshot,MeterRegistry meters){this(repository,snapshot,meters,15000);}
    public OperationalSnapshotCollector(OperationalSnapshotRepository repository,OperationalSnapshot snapshot,MeterRegistry meters,long intervalMillis){
        this.repository=repository;this.snapshot=snapshot;this.meters=meters;this.intervalMillis=intervalMillis;}
    public void start(){if(started.compareAndSet(false,true))scheduler.scheduleWithFixedDelay(this::refresh,0,intervalMillis,TimeUnit.MILLISECONDS);}
    @Override public void close(){scheduler.shutdownNow();try{scheduler.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    public synchronized void refresh(){
        try{snapshot.publish(repository.snapshot());failureReported=false;}
        catch(RuntimeException failure){snapshot.failed();
            try{meters.counter("fincore.ops.snapshot.failures").increment();}catch(RuntimeException ignored){ }
            if(!failureReported){LoggerFactory.getLogger(getClass()).warn("Operational metrics snapshot unavailable");failureReported=true;}
        }
    }
}
