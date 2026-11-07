package com.fincore.payments.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fincore.events", name = "transport", havingValue = "kafka")
@EnableConfigurationProperties({KafkaEventProperties.class, KafkaProperties.class})
@EnableKafka
public class KafkaEventConfiguration {
    @Bean
    NewTopic paymentEventsTopic(KafkaEventProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    ProducerFactory<String, String> paymentEventProducerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> paymentEventKafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    ConsumerFactory<String, String> paymentEventConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    DefaultErrorHandler paymentEventErrorHandler() {
        // Failed records block indefinitely. Only an explicit durable quarantine can make the
        // listener return without processing, with an unresolved gap retained for operations.
        var handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(), true);
        handler.setAckAfterHandle(false);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> paymentEventKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler paymentEventErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(paymentEventErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
