package com.fincore.payments.observability;

import com.fincore.payments.repository.OperationalSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationalSnapshotTest {
    @Test void dedicatedSamplerStartsWithoutAnyPaymentDispatchScheduler()throws Exception{
        var registry=new SimpleMeterRegistry();var repository=mock(OperationalSnapshotRepository.class);var snapshot=new OperationalSnapshot(registry);
        when(repository.snapshot()).thenReturn(points(1));try(var collector=new OperationalSnapshotCollector(repository,snapshot,registry)){
            collector.start();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(snapshot::up);
        }assertThat(snapshot.point(Backlog.OUTBOX_PENDING).count()).isEqualTo(1);
    }
    Map<Backlog,Backlog.Point> points(long count){var points=new EnumMap<Backlog,Backlog.Point>(Backlog.class);for(var b:Backlog.values())points.put(b,new Backlog.Point(count,30));return points;}
    @Test void unavailableStartupFreshPublishAndFailurePreserveSourceKnowledge(){
        var registry=new SimpleMeterRegistry();var clock=new AtomicLong();var snapshot=new OperationalSnapshot(registry,clock::get);
        assertThat(snapshot.up()).isFalse();assertThat(snapshot.available()).isFalse();clock.set(3_000_000_000L);assertThat(snapshot.age()).isEqualTo(3);
        var data=points(7);snapshot.publish(data);data.clear();assertThat(snapshot.point(Backlog.OUTBOX_PENDING).count()).isEqualTo(7);assertThat(snapshot.up()).isTrue();
        clock.addAndGet(5_000_000_000L);snapshot.failed();assertThat(snapshot.available()).isTrue();assertThat(snapshot.up()).isFalse();assertThat(snapshot.age()).isEqualTo(5);
        assertThat(snapshot.point(Backlog.OUTBOX_PENDING).count()).isEqualTo(7);
        assertThatThrownBy(()->snapshot.publish(Map.of())).isInstanceOf(IllegalArgumentException.class);assertThat(snapshot.point(Backlog.OUTBOX_PENDING).count()).isEqualTo(7);
    }
    @Test void collectorFailureDoesNotInventAnEmptyHealthyBacklog(){
        var registry=new SimpleMeterRegistry();var repository=mock(OperationalSnapshotRepository.class);var snapshot=new OperationalSnapshot(registry);
        var collector=new OperationalSnapshotCollector(repository,snapshot,registry);when(repository.snapshot()).thenReturn(points(2)).thenThrow(new IllegalStateException("private-sql"));
        collector.refresh();collector.refresh();assertThat(snapshot.up()).isFalse();assertThat(snapshot.point(Backlog.OUTBOX_DEAD).count()).isEqualTo(2);
        assertThat(registry.get("fincore.ops.snapshot.failures").counter().count()).isEqualTo(1);
    }
}
