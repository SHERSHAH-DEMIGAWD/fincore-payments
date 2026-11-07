package com.fincore.payments.service;

import com.fincore.payments.domain.PaymentEvent;

/** Delivery boundary. This milestone supplies a local durable inbox, not an external broker. */
public interface PaymentEventPublisher {
    void publish(PaymentEvent event);
}
