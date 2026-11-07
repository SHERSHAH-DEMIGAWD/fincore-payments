package com.fincore.payments.controller;

import com.fincore.payments.dto.ProviderWebhook.*;
import com.fincore.payments.exception.ProviderWebhookException;
import com.fincore.payments.provider.*;
import com.fincore.payments.service.ProviderWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class ProviderWebhookController {
    private final ProviderWebhookVerifier verifier;
    private final ProviderWebhookService service;
    public ProviderWebhookController(ProviderWebhookVerifier verifier,ProviderWebhookService service) { this.verifier=verifier; this.service=service; }
    @PostMapping(value=ProviderWebhookWire.PATH,consumes="application/json")
    public Acknowledgement receive(HttpServletRequest request) {
        var verified=verifier.verify(request);
        try { return service.accept(verified); }
        catch (DataAccessException retryable) { throw new ProviderWebhookException(503,"WEBHOOK_UNAVAILABLE"); }
    }
    @GetMapping("/api/v1/payments/{paymentId}/provider-operations/{operationId}/webhooks")
    public Page page(@PathVariable UUID paymentId,@PathVariable UUID operationId,
            @RequestParam(required=false) UUID afterReceiptId,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) {
        return service.page(paymentId,operationId,afterReceiptId,limit);
    }
}
