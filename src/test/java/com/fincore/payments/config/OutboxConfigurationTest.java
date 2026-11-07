package com.fincore.payments.config;

import com.fincore.payments.service.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfiguration.class)
            .withBean(OutboxDispatcher.class, () -> mock(OutboxDispatcher.class));

    @Test
    void schedulerIsAbsentByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxProperties.class);
            assertThat(context).doesNotHaveBean(OutboxConfiguration.OutboxScheduledTask.class);
        });
    }

    @Test
    void schedulerIsEnabledExplicitly() {
        runner.withPropertyValues("fincore.outbox.enabled=true").run(context ->
                assertThat(context).hasSingleBean(OutboxConfiguration.OutboxScheduledTask.class));
    }
}
