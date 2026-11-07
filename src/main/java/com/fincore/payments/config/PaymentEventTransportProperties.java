package com.fincore.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "fincore.events")
public record PaymentEventTransportProperties(@DefaultValue("LOCAL") Transport transport) {
    public enum Transport { LOCAL, KAFKA }
}
