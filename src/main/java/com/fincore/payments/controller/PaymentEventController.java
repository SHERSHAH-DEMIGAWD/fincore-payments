package com.fincore.payments.controller;

import com.fincore.payments.dto.OutboxEventResponse;
import com.fincore.payments.service.MerchantPaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class PaymentEventController {
    private final MerchantPaymentService outbox;

    public PaymentEventController(MerchantPaymentService outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/api/v1/payments/{paymentId}/events")
    public List<OutboxEventResponse> getPaymentEvents(@PathVariable UUID paymentId) {
        return outbox.getPaymentEvents(paymentId);
    }
}
