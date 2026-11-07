package com.fincore.payments.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt in against a dedicated disposable database; inherited tests truncate every application table. */
@EnabledIfEnvironmentVariable(named = "FINCORE_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class LocalPostgresKafkaConsumerIT extends KafkaEventDatabaseTests {
    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("FINCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD", ""));
    }
}
