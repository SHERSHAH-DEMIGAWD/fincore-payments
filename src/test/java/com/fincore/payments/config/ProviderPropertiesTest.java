package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ProviderPropertiesTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ProviderProperties.class) static class Config { }
    @Test void defaultsDisableAllProviderWorkWithoutASecret() {
        context.run(c -> { assertThat(c).hasNotFailed(); var p=c.getBean(ProviderProperties.class);
            assertThat(p.enabled()).isFalse(); assertThat(p.timeoutMillis()).isEqualTo(2000); assertThat(p.leaseSeconds()).isEqualTo(30); });
    }
    @Test void explicitLoopbackConfigurationBindsAndRedactsCredentials() {
        enabled().run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(ProviderProperties.class).toString()).doesNotContain("test-only-provider-secret"); });
    }
    @ParameterizedTest @ValueSource(strings={"http://example.com:18081","http://user:secret@127.0.0.1:18081",
            "http://127.0.0.1:18081/path","http://127.0.0.1:18081?key=x","http://127.0.0.1:18081#x","file:/tmp/sandbox","relative"})
    void rejectsNonSandboxAndAmbiguousUrls(String url) { enabled().withPropertyValues("fincore.provider.base-url="+url).run(c -> assertThat(c).hasFailed()); }
    @ParameterizedTest @ValueSource(strings={"fincore.provider.api-key=short","fincore.provider.instance-id=00000000-0000-0000-0000-000000000000",
            "fincore.provider.timeout-millis=99","fincore.provider.timeout-millis=10001","fincore.provider.lease-seconds=4",
            "fincore.provider.lease-seconds=121","fincore.provider.lease-seconds=5,fincore.provider.timeout-millis=10000"})
    void rejectsInvalidIdentityCredentialsAndTiming(String properties) { enabled().withPropertyValues(properties.split(",")).run(c -> assertThat(c).hasFailed()); }
    private ApplicationContextRunner enabled() { return context.withPropertyValues("fincore.provider.enabled=true",
            "fincore.provider.api-key=test-only-provider-secret-0123456789","fincore.provider.instance-id=11111111-1111-4111-8111-111111111117"); }
}
