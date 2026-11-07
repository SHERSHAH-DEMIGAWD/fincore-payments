package com.fincore.payments.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql:.*")
class LocalPostgresProviderOperationIT extends ProviderOperationDatabaseTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("FINCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));
    }
}
