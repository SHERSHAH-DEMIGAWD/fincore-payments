package com.fincore.payments.integration;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers(disabledWithoutDocker=true)
class PaymentAccountingIntegrationIT extends PaymentAccountingDatabaseTests {
    @Container static PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",database::getJdbcUrl); r.add("spring.datasource.username",database::getUsername); r.add("spring.datasource.password",database::getPassword);
    }
}
