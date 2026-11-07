package com.fincore.payments.controller;

import com.fincore.payments.dto.MerchantWebhook.*;
import com.fincore.payments.service.MerchantWebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/merchant/webhooks")
public class MerchantWebhookController {
    private final MerchantWebhookService service;
    public MerchantWebhookController(MerchantWebhookService service) { this.service=service; }

    @PostMapping("/subscriptions") public Created create(@Valid @RequestBody Create command) { return service.create(command); }
    @GetMapping("/subscriptions") public Subscriptions subscriptions(@RequestParam(required=false) UUID after,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) { return service.subscriptions(after,limit); }
    @PostMapping("/subscriptions/{subscriptionId}/state") public Changed change(@PathVariable UUID subscriptionId,@Valid @RequestBody Change command) {
        return service.change(subscriptionId,command);
    }
    @GetMapping("/deliveries") public Deliveries deliveries(@RequestParam(required=false) UUID subscriptionId,
            @RequestParam(required=false) UUID paymentId,@RequestParam(required=false) DeliveryState state,
            @RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.deliveries(subscriptionId,paymentId,state,after,limit);
    }
    @GetMapping("/deliveries/{deliveryId}/attempts") public Attempts attempts(@PathVariable UUID deliveryId,
            @RequestParam(defaultValue="0") @Min(0) int after,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.attempts(deliveryId,after,limit);
    }
    @PostMapping("/deliveries/{deliveryId}/retry") public Retried retry(@PathVariable UUID deliveryId,@Valid @RequestBody Retry command) {
        return service.retry(deliveryId,command);
    }
}
