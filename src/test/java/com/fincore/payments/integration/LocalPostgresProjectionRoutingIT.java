package com.fincore.payments.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt in only with a disposable database: the shared suite truncates all project tables. */
@EnabledIfEnvironmentVariable(named = "FINCORE_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class LocalPostgresProjectionRoutingIT extends ProjectionRoutingDatabaseTests {
    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("FINCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD", ""));
    }
}
