package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsDocumentedDefaultsWithSchedulingDisabled() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(OutboxProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.fixedDelayMs()).isEqualTo(1000);
            assertThat(properties.batchSize()).isEqualTo(50);
            assertThat(properties.maxAttempts()).isEqualTo(5);
            assertThat(properties.initialBackoffMs()).isEqualTo(1000);
            assertThat(properties.maxBackoffMs()).isEqualTo(60000);
        });
    }

    @Test
    void acceptsExplicitConfigurationOverrides() {
        context.withPropertyValues(
                "fincore.outbox.enabled=true",
                "fincore.outbox.fixed-delay-ms=250",
                "fincore.outbox.batch-size=8",
                "fincore.outbox.max-attempts=3",
                "fincore.outbox.initial-backoff-ms=200",
                "fincore.outbox.max-backoff-ms=5000")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(OutboxProperties.class))
                            .isEqualTo(new OutboxProperties(true, 250, 8, 3, 200, 5000));
                });
    }

    @ParameterizedTest
    @CsvSource({"1, 1000", "2, 2000", "3, 4000", "6, 32000", "7, 60000", "100, 60000"})
    void exponentialBackoffStopsAtConfiguredMaximum(int failedAttempt, long expectedDelay) {
        var properties = new OutboxProperties(false, 1000, 50, 5, 1000, 60000);
        assertThat(properties.retryDelayMs(failedAttempt)).isEqualTo(expectedDelay);
    }

    @Test
    void capsAtMaximumThatIsNotAnExactDoubling() {
        var properties = new OutboxProperties(false, 1000, 50, 5, 1000, 1500);
        assertThat(properties.retryDelayMs(1)).isEqualTo(1000);
        assertThat(properties.retryDelayMs(2)).isEqualTo(1500);
        assertThat(properties.retryDelayMs(Integer.MAX_VALUE)).isEqualTo(1500);
    }

    @Test
    void rejectsInitialBackoffAboveMaximum() {
        assertThatThrownBy(() -> new OutboxProperties(false, 1000, 50, 5, 2000, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Initial outbox backoff must not exceed maximum backoff.");
    }

    @Test
    void rejectsInvalidBackoffRelationshipDuringConfigurationBinding() {
        context.withPropertyValues(
                "fincore.outbox.initial-backoff-ms=2000",
                "fincore.outbox.max-backoff-ms=1000")
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Initial outbox backoff must not exceed maximum backoff.");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class PropertiesConfiguration { }
}
