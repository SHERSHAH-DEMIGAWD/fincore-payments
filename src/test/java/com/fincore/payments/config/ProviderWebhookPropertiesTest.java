package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ProviderWebhookPropertiesTest {
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ProviderWebhookProperties.class)
    static class Config { }
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test void defaultsAreDisabledAndSecretsAreRedacted() {
        context.run(c -> { assertThat(c).hasNotFailed(); var p=c.getBean(ProviderWebhookProperties.class);
            assertThat(p.enabled()).isFalse(); assertThat(p.toleranceSeconds()).isEqualTo(300); });
        var p=new ProviderWebhookProperties(true,"private-webhook-signing-key-12345678","private-key-id",java.util.UUID.randomUUID(),300);
        assertThat(p.toString()).doesNotContain(p.signingKey(),p.keyId());
    }
    @Test void enabledConfigurationBindsAllRequiredValues() {
        context.withPropertyValues("fincore.provider-webhook.enabled=true","fincore.provider-webhook.signing-key=test-only-webhook-signing-key-12345678",
                "fincore.provider-webhook.instance-id=11111111-1111-4111-8111-111111111111").run(c -> assertThat(c).hasNotFailed());
    }
    @ParameterizedTest @ValueSource(strings={"enabled=true","tolerance-seconds=29","tolerance-seconds=901"})
    void invalidSettingsFailClosed(String property) {
        context.withPropertyValues("fincore.provider-webhook."+property).run(c -> assertThat(c).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"signing-key=short","key-id=invalid space","instance-id=00000000-0000-0000-0000-000000000000"})
    void enabledSecretAndIdentityConstraintsAreEnforced(String property) {
        context.withPropertyValues("fincore.provider-webhook.enabled=true","fincore.provider-webhook.signing-key=test-only-webhook-signing-key-12345678",
                "fincore.provider-webhook.instance-id=11111111-1111-4111-8111-111111111111","fincore.provider-webhook."+property).run(c -> assertThat(c).hasFailed());
    }
}
