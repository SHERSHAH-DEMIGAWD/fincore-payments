package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MerchantWebhookPropertiesTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(MerchantWebhookConfiguration.class);

    @Test void webhooksAreDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties=context.getBean(MerchantWebhookProperties.class);
            assertThat(properties.enabled()).isFalse(); assertThat(properties.dispatchEnabled()).isFalse();
        });
    }

    @Test void enabledConfigurationRequiresMasterKeyAndAllowlistedDestination() {
        runner.withPropertyValues("fincore.merchant-webhooks.enabled=true").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void enabledConfigurationAcceptsCanonicalLoopbackAndExactAesKey() {
        runner.withPropertyValues("fincore.merchant-webhooks.enabled=true",
                "fincore.merchant-webhooks.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "fincore.merchant-webhooks.allowed-destinations[0]=http://127.0.0.1:18082/webhooks")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void dispatchCannotStartWhenFeatureIsDisabled() {
        runner.withPropertyValues("fincore.merchant-webhooks.dispatch-enabled=true").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void leaseMustOutliveHttpDeadlineWithSafetyMargin() {
        runner.withPropertyValues("fincore.merchant-webhooks.timeout-millis=10000",
                "fincore.merchant-webhooks.lease-seconds=11").run(context -> assertThat(context).hasFailed());
    }
}
