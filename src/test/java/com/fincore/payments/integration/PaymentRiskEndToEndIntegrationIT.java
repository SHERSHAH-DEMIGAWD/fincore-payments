package com.fincore.payments.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"fincore.risk.enabled=true","fincore.risk.max-admissions=2","fincore.risk.window-seconds=60","fincore.outbox.enabled=false"})
@AutoConfigureMockMvc @Testcontainers(disabledWithoutDocker=true)
class PaymentRiskEndToEndIntegrationIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6");
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);r.add("fincore.risk.redis-port",()->redis.getMappedPort(6379));}
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Test void genuineCreationBoundaryUsesRedisAndPostgresWithoutMockedAdmission()throws Exception{
        UUID merchant=UUID.randomUUID();String user="risk-e2e-"+merchant,password="risk-e2e-password";
        jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)",merchant,user,new BCryptPasswordEncoder(4).encode(password));
        for(int i=1;i<=3;i++)mvc.perform(post("/api/v1/payments").with(httpBasic(user,password)).with(csrf()).header("Idempotency-Key","e2e-"+i)
            .contentType("application/json").content("{\"merchantReference\":\"R\",\"amount\":10,\"currency\":\"INR\"}"))
            .andExpect(i<=2?status().isCreated():status().isTooManyRequests());
        mvc.perform(post("/api/v1/payments").with(httpBasic(user,password)).with(csrf()).header("Idempotency-Key","e2e-1")
            .contentType("application/json").content("{\"merchantReference\":\"R\",\"amount\":10,\"currency\":\"INR\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id=?",Long.class,merchant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_risk_decisions WHERE merchant_id=?",Long.class,merchant)).isEqualTo(3);
    }
}
