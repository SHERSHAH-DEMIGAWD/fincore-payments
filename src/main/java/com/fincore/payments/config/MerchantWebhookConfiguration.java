package com.fincore.payments.config;

import com.fincore.payments.service.MerchantWebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MerchantWebhookProperties.class)
public class MerchantWebhookConfiguration {
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="fincore.merchant-webhooks",name={"enabled","dispatch-enabled"},havingValue="true")
    @EnableScheduling
    static class Scheduling {
        @Bean ScheduledDispatch scheduledDispatch(MerchantWebhookDispatcher dispatcher) { return new ScheduledDispatch(dispatcher); }
    }
    static class ScheduledDispatch {
        private static final Logger log=LoggerFactory.getLogger(ScheduledDispatch.class);
        private final MerchantWebhookDispatcher dispatcher;
        ScheduledDispatch(MerchantWebhookDispatcher dispatcher) { this.dispatcher=dispatcher; }
        @Scheduled(fixedDelayString="${fincore.merchant-webhooks.fixed-delay-ms:1000}")
        public void dispatch() {
            try {
                var result=dispatcher.dispatchBatch();
                if (result.claimed()>0) log.debug("Merchant webhook batch completed: claimed={}, acknowledged={}, failed={}, fenced={}",
                        result.claimed(),result.acknowledged(),result.failed(),result.abandoned());
            } catch (RuntimeException failure) {
                log.warn("Merchant webhook dispatch could not finish; failure type: {}",failure.getClass().getSimpleName());
            }
        }
    }
}
