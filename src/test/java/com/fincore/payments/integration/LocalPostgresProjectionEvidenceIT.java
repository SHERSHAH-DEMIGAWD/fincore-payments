package com.fincore.payments.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql:.*")
class LocalPostgresProjectionEvidenceIT extends ProjectionEvidenceDatabaseTests {
    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",() -> System.getenv("FINCORE_TEST_DB_URL"));
        properties.add("spring.datasource.username",() -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password",() -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));
    }
}
