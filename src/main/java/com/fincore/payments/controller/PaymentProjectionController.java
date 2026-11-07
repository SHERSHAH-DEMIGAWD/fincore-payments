package com.fincore.payments.controller;

import com.fincore.payments.dto.PaymentProjectionResponse;
import com.fincore.payments.service.MerchantPaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentProjectionController {
    private final MerchantPaymentService consumer;

    public PaymentProjectionController(MerchantPaymentService consumer) {
        this.consumer = consumer;
    }

    @GetMapping("/api/v1/payments/{paymentId}/projection")
    public PaymentProjectionResponse getProjection(@PathVariable UUID paymentId) {
        return consumer.getProjection(paymentId);
    }
}
