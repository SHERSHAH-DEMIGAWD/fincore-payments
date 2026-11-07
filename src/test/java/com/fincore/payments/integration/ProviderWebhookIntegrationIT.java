package com.fincore.payments.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class ProviderWebhookIntegrationIT extends ProviderWebhookDatabaseTests {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl); r.add("spring.datasource.username",postgres::getUsername); r.add("spring.datasource.password",postgres::getPassword);
    }
}
