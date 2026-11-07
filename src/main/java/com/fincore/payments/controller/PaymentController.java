package com.fincore.payments.controller;

import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.service.MerchantPaymentService;
import com.fincore.payments.service.IdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final MerchantPaymentService paymentService;

    public PaymentController(MerchantPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader(value = IdempotencyKey.HEADER_NAME, required = false)
            @NotNull(message = IdempotencyKey.VALIDATION_MESSAGE)
            @Pattern(regexp = IdempotencyKey.FORMAT, message = IdempotencyKey.VALIDATION_MESSAGE)
            String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        var result = paymentService.createPayment(idempotencyKey, request);
        PaymentResponse response = result.payment();
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{paymentId}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable UUID paymentId) {
        return paymentService.getPayment(paymentId);
    }

    @PostMapping("/{paymentId}/authorize")
    public PaymentResponse authorizePayment(@PathVariable UUID paymentId) {
        return paymentService.authorizePayment(paymentId);
    }

    @PostMapping("/{paymentId}/capture")
    public PaymentResponse capturePayment(@PathVariable UUID paymentId) {
        return paymentService.capturePayment(paymentId);
    }

    @PostMapping("/{paymentId}/fail")
    public PaymentResponse failPayment(@PathVariable UUID paymentId) {
        return paymentService.failPayment(paymentId);
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResponse refundPayment(@PathVariable UUID paymentId) {
        return paymentService.refundPayment(paymentId);
    }
}
