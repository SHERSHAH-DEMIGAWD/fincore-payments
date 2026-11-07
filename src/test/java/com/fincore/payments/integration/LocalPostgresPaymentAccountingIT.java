package com.fincore.payments.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.*;
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql:.*")
class LocalPostgresPaymentAccountingIT extends PaymentAccountingDatabaseTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("FINCORE_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("FINCORE_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));
        r.add("spring.datasource.hikari.maximum-pool-size",()->5);
    }
}
