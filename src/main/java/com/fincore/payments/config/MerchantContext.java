package com.fincore.payments.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;

public final class MerchantContext {
    private MerchantContext() { }
    public static MerchantPrincipal principal() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof MerchantPrincipal principal)
                || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_MERCHANT")))
            throw new AccessDeniedException("Merchant authentication is required.");
        return principal;
    }
    public static UUID merchantId() { return principal().merchantId(); }
}
