package com.fincore.payments.config;

import com.fincore.payments.service.OutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxProperties.class, PaymentEventTransportProperties.class,
        KafkaEventProperties.class, KafkaProperties.class})
public class OutboxConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "fincore.outbox", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class Scheduling {
        @Bean
        OutboxScheduledTask outboxScheduledTask(OutboxDispatcher dispatcher) {
            return new OutboxScheduledTask(dispatcher);
        }
    }

    static class OutboxScheduledTask {
        private static final Logger log = LoggerFactory.getLogger(OutboxScheduledTask.class);
        private final OutboxDispatcher dispatcher;

        OutboxScheduledTask(OutboxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${fincore.outbox.fixed-delay-ms:1000}")
        public void dispatch() {
            try {
                int delivered = dispatcher.dispatchBatch();
                if (delivered > 0) log.debug("Delivered {} payment events to the configured transport", delivered);
            } catch (RuntimeException exception) {
                // The next scheduled run can retry; avoid logging event payloads or transport messages.
                log.warn("Outbox dispatch could not finish; failure type: {}", exception.getClass().getSimpleName());
            }
        }
    }
}
