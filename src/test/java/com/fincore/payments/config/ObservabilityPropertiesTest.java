package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ObservabilityPropertiesTest {
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ObservabilityProperties.class) static class Config { }
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test void disabledDefaultsNeedNoCredentials(){context.run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(ObservabilityProperties.class).configured()).isFalse();});}
    @ParameterizedTest @ValueSource(strings={"username=unpaired","password-hash=invalid","snapshot-interval-millis=1","trace-endpoint=http://example.com:4318/v1/traces",
        "trace-endpoint=http://127.0.0.1:4318/v1/traces?secret=x","trace-endpoint=http://user:password@127.0.0.1:4318/v1/traces","trace-endpoint=http://127.0.0.1:4318/other"})
    void unsafeEnabledSettingsCannotStart(String setting){context.withPropertyValues("fincore.observability.enabled=true","fincore.observability."+setting).run(c->assertThat(c).hasFailed());}
}
