package com.fincore.payments.observability;

import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** One coherent publication; a scrape may straddle publications and is not financial evidence. */
public class OperationalSnapshot {
    private record State(Map<Backlog,Backlog.Point> points,boolean available,boolean up,long sampledNanos) { }
    private final LongSupplier nanos;
    private final long started;
    private final AtomicReference<State> state;
    public OperationalSnapshot(MeterRegistry registry){this(registry,System::nanoTime,15000);}
    public OperationalSnapshot(MeterRegistry registry,long intervalMillis){this(registry,System::nanoTime,intervalMillis);}
    OperationalSnapshot(MeterRegistry registry,LongSupplier nanos){this(registry,nanos,15000);}
    private OperationalSnapshot(MeterRegistry registry,LongSupplier nanos,long intervalMillis){
        if(intervalMillis<=0)throw new IllegalArgumentException("Invalid sampling interval.");
        this.nanos=nanos;started=nanos.getAsLong();var empty=new EnumMap<Backlog,Backlog.Point>(Backlog.class);
        for(var backlog:Backlog.values())empty.put(backlog,new Backlog.Point(0,0));state=new AtomicReference<>(new State(Map.copyOf(empty),false,false,started));
        Gauge.builder("fincore.ops.snapshot.up",this,s->s.state.get().up()?1:0).register(registry);
        Gauge.builder("fincore.ops.snapshot.available",this,s->s.state.get().available()?1:0).register(registry);
        Gauge.builder("fincore.ops.snapshot.age",this,OperationalSnapshot::age).baseUnit("seconds").register(registry);
        Gauge.builder("fincore.ops.snapshot.interval",()->intervalMillis/1000.0).baseUnit("seconds").register(registry);
        for(var kind:Backlog.values()){
            Gauge.builder("fincore.backlog.count",this,s->s.point(kind).count()).tags("component",kind.component,"state",kind.state).register(registry);
            Gauge.builder("fincore.backlog.oldest.age",this,s->s.point(kind).oldestAgeSeconds()).baseUnit("seconds")
                .tags("component",kind.component,"state",kind.state).register(registry);
        }
    }
    public void publish(Map<Backlog,Backlog.Point> points){
        if(points.size()!=Backlog.values().length || Arrays.stream(Backlog.values()).anyMatch(k->points.get(k)==null))throw new IllegalArgumentException("Incomplete operational snapshot.");
        state.set(new State(Map.copyOf(points),true,true,nanos.getAsLong()));
    }
    public void failed(){state.updateAndGet(s->new State(s.points(),s.available(),false,s.sampledNanos()));}
    public boolean up(){return state.get().up();}
    public boolean available(){return state.get().available();}
    public double age(){return Math.max(0,nanos.getAsLong()-state.get().sampledNanos())/1_000_000_000.0;}
    public Backlog.Point point(Backlog kind){return state.get().points().get(kind);}
}
