package com.fincore.payments.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;
import java.util.UUID;

/** Issued by the merchant-only authentication provider, never from request body/header IDs. */
public final class MerchantPrincipal extends User {
    private final UUID merchantId;
    public MerchantPrincipal(UUID merchantId,String username,String hash,boolean enabled) {
        super(username,hash,enabled,true,true,true,List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        this.merchantId=java.util.Objects.requireNonNull(merchantId);
    }
    public UUID merchantId() { return merchantId; }
    @Override @JsonIgnore public String getPassword() { return super.getPassword(); }
}
