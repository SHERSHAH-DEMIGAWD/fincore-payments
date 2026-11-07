package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.PaymentEventTransportProperties;
import com.fincore.payments.dto.KafkaLagReport;
import com.fincore.payments.dto.KafkaLagStatus;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KafkaLagServiceTest {
    private static final String TOPIC = "payment-events.v1";
    private static final String GROUP = "projection-test";
    private final Admin admin = mock(Admin.class);
    private final KafkaProperties kafka = new KafkaProperties();
    private final KafkaEventProperties events = new KafkaEventProperties(TOPIC, GROUP, 15000);
    private final AtomicLong now = new AtomicLong();

    @Test
    void localTransportIsExplicitlyDisabledAndNeverCreatesAnAdminClient() {
        AtomicInteger creations = new AtomicInteger();
        KafkaLagService service = new KafkaLagService(
                new PaymentEventTransportProperties(PaymentEventTransportProperties.Transport.LOCAL),
                events, kafka, properties -> {
                    creations.incrementAndGet();
                    return admin;
                }, Duration.ofSeconds(5), now::get);

        KafkaLagReport report = service.report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.DISABLED);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions()).isEmpty();
        assertThat(report.errorCode()).isNull();
        assertThat(creations).hasValue(0);
        verifyNoInteractions(admin);
    }

    @Test
    void knownOffsetsYieldPerPartitionLagAndAnAccurateTotal() {
        describe(2);
        endOffsets(Map.of(0, completedOffset(14), 1, completedOffset(7)));
        committed(KafkaFuture.completedFuture(Map.of(partition(0), new OffsetAndMetadata(10),
                partition(1), new OffsetAndMetadata(7))));

        KafkaLagReport report = service().report();

        assertThat(report.topic()).isEqualTo(TOPIC);
        assertThat(report.consumerGroup()).isEqualTo(GROUP);
        assertThat(report.observedAt()).isNotNull();
        assertThat(report.status()).isEqualTo(KafkaLagStatus.KNOWN);
        assertThat(report.totalLag()).isEqualTo(4);
        assertThat(report.partitions()).extracting(p -> p.lag()).containsExactly(4L, 0L);
        assertThat(report.errorCode()).isNull();
        verify(admin).close(Duration.ofMillis(100));
    }

    @Test
    void absentCommittedOffsetsAreUninitializedEvenWhenTheTopicIsEmpty() {
        describe(1);
        endOffsets(Map.of(0, completedOffset(0)));
        committed(KafkaFuture.completedFuture(Map.of()));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNINITIALIZED);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions().getFirst().endOffset()).isZero();
        assertThat(report.partitions().getFirst().committedOffset()).isNull();
        assertThat(report.partitions().getFirst().lag()).isNull();
    }

    @Test
    void aMissingConsumerGroupIsUninitializedRatherThanAZeroLagConsumer() {
        describe(1);
        endOffsets(Map.of(0, completedOffset(9)));
        committed(failedFuture(new GroupIdNotFoundException("not initialized")));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNINITIALIZED);
        assertThat(report.totalLag()).isNull();
        assertThat(report.errorCode()).isNull();
        assertThat(report.partitions().getFirst().endOffset()).isEqualTo(9);
    }

    @Test
    void partiallyInitializedGroupsPreserveKnownPartitionLagButDoNotInventATotal() {
        describe(2);
        endOffsets(Map.of(0, completedOffset(10), 1, completedOffset(7)));
        committed(KafkaFuture.completedFuture(Map.of(partition(0), new OffsetAndMetadata(8))));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNINITIALIZED);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions().getFirst().status()).isEqualTo(KafkaLagStatus.KNOWN);
        assertThat(report.partitions().getFirst().lag()).isEqualTo(2);
        assertThat(report.partitions().getLast().status()).isEqualTo(KafkaLagStatus.UNINITIALIZED);
        assertThat(report.partitions().getLast().lag()).isNull();
    }

    @Test
    void committedOffsetBeyondTheObservedEndIsInconsistentRatherThanClampedToZero() {
        describe(1);
        endOffsets(Map.of(0, completedOffset(10)));
        committed(KafkaFuture.completedFuture(Map.of(partition(0), new OffsetAndMetadata(12))));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.INCONSISTENT);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions().getFirst().committedOffset()).isEqualTo(12);
        assertThat(report.partitions().getFirst().lag()).isNull();
    }

    @Test
    void negativeOffsetsAreInconsistentRatherThanReportedAsAvailableLag() {
        describe(1);
        endOffsets(Map.of(0, completedOffset(10)));
        OffsetAndMetadata invalidOffset = mock(OffsetAndMetadata.class);
        when(invalidOffset.offset()).thenReturn(-1L);
        committed(KafkaFuture.completedFuture(Map.of(partition(0), invalidOffset)));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.INCONSISTENT);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions().getFirst().committedOffset()).isEqualTo(-1);
    }

    @Test
    void unavailablePartitionsDoNotEraseKnownPartitionsOrProduceAFakeTotal() {
        describe(2);
        endOffsets(Map.of(0, completedOffset(10), 1,
                failedFuture(new IllegalStateException("secret bootstrap credentials"))));
        committed(KafkaFuture.completedFuture(Map.of(partition(0), new OffsetAndMetadata(8),
                partition(1), new OffsetAndMetadata(5))));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions().getFirst().lag()).isEqualTo(2);
        assertThat(report.partitions().getLast().status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
        assertThat(report.errorCode()).isEqualTo("KAFKA_LAG_UNAVAILABLE");
        assertThat(report.toString()).doesNotContain("secret", "bootstrap", "credentials");
    }

    @Test
    void missingTopicReturnsExplicitUnavailableWithoutPretendingThatNoPartitionsMeansZeroLag() {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        when(result.allTopicNames()).thenReturn(failedFuture(new UnknownTopicOrPartitionException("missing")));

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
        assertThat(report.totalLag()).isNull();
        assertThat(report.partitions()).isEmpty();
        assertThat(report.errorCode()).isEqualTo("KAFKA_TOPIC_UNAVAILABLE");
    }

    @Test
    void aSingleDeadlineBoundsAllPartitionsAndCleanupWhileCompletedEndOffsetsRemainVisible() throws Exception {
        describe(2);
        endOffsets(Map.of(0, completedOffset(10), 1, completedOffset(7)));
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future = mock(KafkaFuture.class);
        doAnswer(invocation -> {
            assertThat((Long) invocation.getArgument(0)).isEqualTo(TimeUnit.SECONDS.toNanos(5));
            now.set(TimeUnit.SECONDS.toNanos(5));
            throw new TimeoutException("sensitive endpoint");
        }).when(future).get(anyLong(), eq(TimeUnit.NANOSECONDS));
        committed(future);

        KafkaLagReport report = service().report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
        assertThat(report.totalLag()).isNull();
        assertThat(report.errorCode()).isEqualTo("KAFKA_LAG_TIMEOUT");
        assertThat(report.partitions()).extracting(p -> p.endOffset()).containsExactly(10L, 7L);
        assertThat(report.partitions()).allSatisfy(p -> assertThat(p.lag()).isNull());
        verify(admin).close(Duration.ZERO);
    }

    @Test
    void clientCreationFailureIsSanitized() {
        KafkaLagService service = new KafkaLagService(
                new PaymentEventTransportProperties(PaymentEventTransportProperties.Transport.KAFKA),
                events, kafka, properties -> {
                    throw new IllegalArgumentException("password=my-password");
                }, Duration.ofSeconds(5), now::get);

        KafkaLagReport report = service.report();

        assertThat(report.status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
        assertThat(report.totalLag()).isNull();
        assertThat(report.errorCode()).isEqualTo("KAFKA_LAG_UNAVAILABLE");
        assertThat(report.toString()).doesNotContain("password");
    }

    @Test
    void interruptionIsRestoredAndReportedAsUnavailable() throws Exception {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicDescription>> future = mock(KafkaFuture.class);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        when(result.allTopicNames()).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        try {
            KafkaLagReport report = service().report();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(report.status()).isEqualTo(KafkaLagStatus.UNAVAILABLE);
            assertThat(report.errorCode()).isEqualTo("KAFKA_LAG_INTERRUPTED");
        } finally {
            Thread.interrupted();
        }
    }

    private KafkaLagService service() {
        return new KafkaLagService(
                new PaymentEventTransportProperties(PaymentEventTransportProperties.Transport.KAFKA),
                events, kafka, properties -> admin, Duration.ofSeconds(5), now::get);
    }

    private void describe(int count) {
        Node leader = new Node(0, "localhost", 9092);
        List<TopicPartitionInfo> partitions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            partitions.add(new TopicPartitionInfo(index, leader, List.of(leader), List.of(leader)));
        }
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
                Map.of(TOPIC, new TopicDescription(TOPIC, false, partitions))));
    }

    private void endOffsets(Map<Integer, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> offsets) {
        Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> futures = new HashMap<>();
        offsets.forEach((partition, future) -> futures.put(partition(partition), future));
        when(admin.listOffsets(anyMap(), any(ListOffsetsOptions.class))).thenReturn(new ListOffsetsResult(futures));
    }

    private void committed(KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future) {
        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(admin.listConsumerGroupOffsets(eq(GROUP), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(result);
        when(result.partitionsToOffsetAndMetadata()).thenReturn(future);
    }

    private static TopicPartition partition(int partition) {
        return new TopicPartition(TOPIC, partition);
    }

    private static KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo> completedOffset(long offset) {
        return KafkaFuture.completedFuture(new ListOffsetsResult.ListOffsetsResultInfo(offset, -1, Optional.empty()));
    }

    private static <T> KafkaFuture<T> failedFuture(Throwable failure) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(failure);
        return future;
    }
}
