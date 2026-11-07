package com.fincore.payments.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Both services must be dedicated and disposable; tests truncate tables and publish broker records. */
@EnabledIfEnvironmentVariable(named = "FINCORE_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@EnabledIfEnvironmentVariable(named = "FINCORE_TEST_KAFKA_BOOTSTRAP", matches = ".+:[0-9]+.*")
class LocalKafkaBrokerIT extends KafkaBrokerTests {
    @DynamicPropertySource
    static void localServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("FINCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD", ""));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("FINCORE_TEST_KAFKA_BOOTSTRAP"));
    }
}
