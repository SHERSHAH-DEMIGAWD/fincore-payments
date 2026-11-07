package com.fincore.payments.controller;

import com.fincore.payments.config.MerchantContext;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant")
public class MerchantIdentityController {
    public record Identity(UUID merchantId,String username) { }
    @GetMapping("/me") public Identity me() {
        var principal=MerchantContext.principal();
        return new Identity(principal.merchantId(),principal.getUsername());
    }
    @GetMapping("/csrf") public CsrfToken csrf(CsrfToken token) { return token; }
}
