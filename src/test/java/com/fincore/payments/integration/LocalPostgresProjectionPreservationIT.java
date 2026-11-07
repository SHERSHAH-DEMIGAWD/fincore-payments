package com.fincore.payments.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql:.*")
class LocalPostgresProjectionPreservationIT extends ProjectionPreservationDatabaseTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",() -> System.getenv("FINCORE_TEST_DB_URL"));
        p.add("spring.datasource.username",() -> System.getenv("FINCORE_TEST_DB_USERNAME"));
        p.add("spring.datasource.password",() -> System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));
    }
}
