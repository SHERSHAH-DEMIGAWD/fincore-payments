package com.fincore.payments.controller;

import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.service.ProviderOperationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/provider-operations")
public class ProviderOperationController {
    private final ProviderOperationService operations;
    public ProviderOperationController(ProviderOperationService operations) { this.operations=operations; }
    @PostMapping public Result submit(@PathVariable UUID paymentId,@Valid @RequestBody Command command) {
        return operations.submit(paymentId,command);
    }
    @GetMapping("/{operationId}") public View get(@PathVariable UUID paymentId,@PathVariable UUID operationId) {
        return operations.get(paymentId,operationId);
    }
    @GetMapping public Page page(@PathVariable UUID paymentId,@RequestParam(required=false) UUID afterOperationId,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) {
        return operations.page(paymentId,afterOperationId,limit);
    }
}
