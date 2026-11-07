package com.fincore.payments.controller;

import com.fincore.payments.dto.PaymentAccounting.*;
import com.fincore.payments.service.PaymentAccountingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}")
public class PaymentAccountingController {
    private final PaymentAccountingService service;
    public PaymentAccountingController(PaymentAccountingService service) { this.service=service; }
    @PostMapping("/amount-operations") public Result submit(@PathVariable UUID paymentId,@Valid @RequestBody Command command) { return service.submit(paymentId,command); }
    @GetMapping("/amount-operations") public History history(@PathVariable UUID paymentId) { return service.history(paymentId); }
    @GetMapping("/accounting") public Snapshot accounting(@PathVariable UUID paymentId) { return service.get(paymentId); }
}
