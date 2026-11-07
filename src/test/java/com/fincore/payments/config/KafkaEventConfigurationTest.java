package com.fincore.payments.config;

import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.service.DatabasePaymentEventPublisher;
import com.fincore.payments.service.KafkaPaymentEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaEventConfigurationTest {
    @Test
    void defaultLocalTransportDoesNotCreateKafkaResources() {
        new ApplicationContextRunner().withUserConfiguration(KafkaEventConfiguration.class,
                        DatabasePaymentEventPublisher.class, KafkaPaymentEventPublisher.class)
                .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DatabasePaymentEventPublisher.class);
                    assertThat(context).doesNotHaveBean(KafkaPaymentEventPublisher.class);
                    assertThat(context).doesNotHaveBean("paymentEventsTopic");
                    assertThat(context).doesNotHaveBean("paymentEventKafkaListenerContainerFactory");
                });
    }

    @Test
    void kafkaTransportRequiresBrokerAcknowledgementAndDatabaseCommitBeforeOffsetCommit() {
        new ApplicationContextRunner().withUserConfiguration(KafkaEventConfiguration.class)
                .withPropertyValues("fincore.events.transport=kafka", "spring.kafka.bootstrap-servers=localhost:9092")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer = context.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties();
                    assertThat(producer).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                            .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
                            .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000)
                            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
                    var consumer = context.getBean(DefaultKafkaConsumerFactory.class).getConfigurationProperties();
                    assertThat(consumer).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                    var factory = context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
                });
    }

    @Test
    void unknownTransportFailsAtStartup() {
        new ApplicationContextRunner().withUserConfiguration(OutboxConfiguration.class)
                .withPropertyValues("fincore.events.transport=typo")
                .run(context -> assertThat(context).hasFailed());
    }
}
