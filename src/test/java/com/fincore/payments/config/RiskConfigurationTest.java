package com.fincore.payments.config;

import com.fincore.payments.risk.RiskVelocity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import static org.assertj.core.api.Assertions.*;

class RiskConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(RiskConfiguration.class);
    @Test void disabledFeatureCreatesNoRedisConnectionFactory(){context.run(c->{assertThat(c).hasNotFailed().hasSingleBean(RiskVelocity.class).doesNotHaveBean(RedisConnectionFactory.class);});}
    @ParameterizedTest @ValueSource(strings={"max-admissions=0","max-admissions=1001","window-seconds=0","redis-host=example.com","redis-port=0","timeout-millis=1","maximum-amount=1e1000000","maximum-amount=0","currency=inr"})
    void enabledInvalidPolicyCannotStart(String property){context.withPropertyValues("fincore.risk.enabled=true","fincore.risk."+property).run(c->assertThat(c).hasFailed());}
    @Test void validFeatureStartsLazilyWithoutAReachableRedis(){context.withPropertyValues("fincore.risk.enabled=true","fincore.risk.redis-port=1").run(c->{
        assertThat(c).hasNotFailed().hasSingleBean(RedisConnectionFactory.class).hasSingleBean(RiskVelocity.class);
        assertThatThrownBy(()->c.getBean(RiskVelocity.class).reserve(java.util.UUID.randomUUID(),"a".repeat(64))).isInstanceOf(RuntimeException.class);
    });}
}
