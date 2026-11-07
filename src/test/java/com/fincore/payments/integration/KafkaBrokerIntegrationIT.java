package com.fincore.payments.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
class KafkaBrokerIntegrationIT extends KafkaBrokerTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
