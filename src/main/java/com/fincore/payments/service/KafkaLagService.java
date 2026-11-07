package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.PaymentEventTransportProperties;
import com.fincore.payments.dto.KafkaLagReport;
import com.fincore.payments.dto.KafkaLagStatus;
import com.fincore.payments.dto.KafkaPartitionLag;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Service
public class KafkaLagService {
    private static final Duration REPORT_TIMEOUT = Duration.ofSeconds(5);
    private final PaymentEventTransportProperties transport;
    private final KafkaEventProperties events;
    private final KafkaProperties kafka;
    private final Function<Map<String, Object>, Admin> adminFactory;
    private final Duration timeout;
    private final LongSupplier ticker;

    @Autowired
    public KafkaLagService(PaymentEventTransportProperties transport, KafkaEventProperties events,
                           KafkaProperties kafka) {
        this(transport, events, kafka, Admin::create, REPORT_TIMEOUT, System::nanoTime);
    }

    KafkaLagService(PaymentEventTransportProperties transport, KafkaEventProperties events,
                    KafkaProperties kafka, Function<Map<String, Object>, Admin> adminFactory,
                    Duration timeout, LongSupplier ticker) {
        this.transport = transport;
        this.events = events;
        this.kafka = kafka;
        this.adminFactory = adminFactory;
        this.timeout = timeout;
        this.ticker = ticker;
    }

    public KafkaLagReport report() {
        if (transport.transport() != PaymentEventTransportProperties.Transport.KAFKA) {
            return report(KafkaLagStatus.DISABLED, null, List.of(), null);
        }
        long deadline = ticker.getAsLong() + timeout.toNanos();
        Admin admin = null;
        try {
            Map<String, Object> properties = new HashMap<>(kafka.buildAdminProperties(null));
            properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Math.toIntExact(timeout.toMillis()));
            properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    (int) Math.min(2000, timeout.toMillis()));
            admin = adminFactory.apply(properties);
            return query(admin, deadline);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return report(KafkaLagStatus.UNAVAILABLE, null, List.of(), "KAFKA_LAG_INTERRUPTED");
        } catch (Exception exception) {
            return report(KafkaLagStatus.UNAVAILABLE, null, List.of(), errorCode(exception));
        } finally {
            if (admin != null) {
                // Resource cleanup shares the report deadline rather than adding another blocking timeout.
                try {
                    admin.close(Duration.ofNanos(Math.min(remaining(deadline), TimeUnit.MILLISECONDS.toNanos(100))));
                } catch (RuntimeException ignored) {
                    // Diagnostic output must not expose a broker or client exception from cleanup.
                }
            }
        }
    }

    private KafkaLagReport query(Admin admin, long deadline) throws Exception {
        TopicDescription description = await(admin.describeTopics(List.of(events.topic()),
                        new DescribeTopicsOptions().timeoutMs(remainingMillis(deadline)))
                .allTopicNames(), deadline).get(events.topic());
        if (description == null || description.partitions().isEmpty()) {
            return report(KafkaLagStatus.UNAVAILABLE, null, List.of(), "KAFKA_TOPIC_UNAVAILABLE");
        }
        List<TopicPartition> partitions = description.partitions().stream()
                .map(info -> new TopicPartition(events.topic(), info.partition()))
                .sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
        Map<TopicPartition, OffsetSpec> requestedOffsets = new LinkedHashMap<>();
        partitions.forEach(partition -> requestedOffsets.put(partition, OffsetSpec.latest()));
        // Submit both requests before awaiting either: the five-second budget covers the whole report.
        ListOffsetsResult endOffsets = admin.listOffsets(requestedOffsets,
                new ListOffsetsOptions().timeoutMs(remainingMillis(deadline)));
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = admin.listConsumerGroupOffsets(
                        events.consumerGroup(),
                        new ListConsumerGroupOffsetsOptions().timeoutMs(remainingMillis(deadline)))
                .partitionsToOffsetAndMetadata();
        Map<TopicPartition, OffsetAndMetadata> committed = null;
        String failure = null;
        try {
            committed = await(committedFuture, deadline);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof GroupIdNotFoundException) {
                committed = Map.of();
            } else {
                failure = errorCode(exception);
            }
        } catch (TimeoutException exception) {
            failure = errorCode(exception);
        }
        List<KafkaPartitionLag> result = new ArrayList<>();
        for (TopicPartition partition : partitions) {
            Long endOffset = null;
            Long committedOffset = committed == null || committed.get(partition) == null
                    ? null : committed.get(partition).offset();
            try {
                endOffset = await(endOffsets.partitionResult(partition), deadline).offset();
            } catch (ExecutionException | TimeoutException exception) {
                if (failure == null) {
                    failure = errorCode(exception);
                }
            }
            KafkaLagStatus status;
            Long lag = null;
            if (endOffset == null || committed == null) {
                status = KafkaLagStatus.UNAVAILABLE;
            } else if (endOffset < 0 || committedOffset != null
                    && (committedOffset < 0 || committedOffset > endOffset)) {
                status = KafkaLagStatus.INCONSISTENT;
            } else if (committedOffset == null) {
                status = KafkaLagStatus.UNINITIALIZED;
            } else {
                status = KafkaLagStatus.KNOWN;
                lag = endOffset - committedOffset;
            }
            result.add(new KafkaPartitionLag(partition.partition(), endOffset, committedOffset, lag, status));
        }
        KafkaLagStatus status = result.stream().map(KafkaPartitionLag::status)
                .max(Comparator.comparingInt(KafkaLagService::priority)).orElse(KafkaLagStatus.UNAVAILABLE);
        Long total = null;
        if (status == KafkaLagStatus.KNOWN) {
            try {
                long sum = 0;
                for (KafkaPartitionLag partition : result) {
                    sum = Math.addExact(sum, partition.lag());
                }
                total = sum;
            } catch (ArithmeticException exception) {
                status = KafkaLagStatus.INCONSISTENT;
                failure = "KAFKA_LAG_OFFSET_RANGE";
            }
        }
        return report(status, total, result, failure);
    }

    private <T> T await(KafkaFuture<T> future, long deadline)
            throws ExecutionException, InterruptedException, TimeoutException {
        // A completed partition is still readable after another partition exhausts the budget.
        return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
    }

    private long remaining(long deadline) {
        return Math.max(0, deadline - ticker.getAsLong());
    }

    private int remainingMillis(long deadline) {
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline)));
    }

    private KafkaLagReport report(KafkaLagStatus status, Long total, List<KafkaPartitionLag> partitions,
                                  String errorCode) {
        return new KafkaLagReport(events.topic(), events.consumerGroup(), Instant.now(), status,
                total, partitions, errorCode);
    }

    private static int priority(KafkaLagStatus status) {
        return switch (status) {
            case DISABLED -> 0;
            case KNOWN -> 1;
            case UNINITIALIZED -> 2;
            case INCONSISTENT -> 3;
            case UNAVAILABLE -> 4;
        };
    }

    private static String errorCode(Throwable exception) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause() : exception;
        if (cause instanceof TimeoutException || cause instanceof org.apache.kafka.common.errors.TimeoutException) {
            return "KAFKA_LAG_TIMEOUT";
        }
        if (cause instanceof UnknownTopicOrPartitionException) {
            return "KAFKA_TOPIC_UNAVAILABLE";
        }
        return "KAFKA_LAG_UNAVAILABLE";
    }
}
