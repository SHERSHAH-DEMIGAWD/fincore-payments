package com.fincore.payments.integration;

import com.fincore.payments.risk.RiskVelocity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the same real HTTP/PG/trace boundary with M22 enabled and a mocked Redis gateway. */
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_DB_URL",matches="jdbc:postgresql:.*")
class LocalPostgresRiskObservabilityIT extends ObservabilityDatabaseTests {
    @MockitoBean RiskVelocity velocity;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("FINCORE_TEST_DB_URL"));r.add("spring.datasource.username",()->System.getenv("FINCORE_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("FINCORE_TEST_DB_PASSWORD",""));r.add("fincore.risk.enabled",()->true);
        r.add("fincore.risk.redis-port",()->1);
    }
    @BeforeEach void riskCapacity(){when(velocity.reserve(any(),any())).thenReturn(new RiskVelocity.Reservation(true,1,1000));}
    @Override int aggregateHealthStatus(){return 503;} // Redis is unavailable; DB-only readiness must still be 200.
    @Test void unavailableRiskDecisionAndMerchantRefusalAreObservedSeparately()throws Exception{
        String secret="private-redis-error";when(velocity.reserve(any(),any())).thenThrow(new IllegalStateException(secret));
        double before=calls("risk_check","unavailable"),refusals=calls("payment_create","unavailable");
        var response=create("risk-unavailable-"+java.util.UUID.randomUUID(),"RISK",null);assertThat(response.statusCode()).isEqualTo(503);
        assertThat(calls("risk_check","unavailable")).isEqualTo(before+1);assertThat(calls("payment_create","unavailable")).isEqualTo(refusals+1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_risk_decisions WHERE merchant_id=? AND outcome='UNAVAILABLE'",Long.class,merchant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id=?",Long.class,merchant)).isZero();
        assertThat(spans.getFinishedSpanItems().toString()).doesNotContain(secret);
    }
}
