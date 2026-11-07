package com.fincore.payments.domain;

import java.util.UUID;

/** Historical/local scope only; no credential can authenticate this identity. */
public final class MerchantIdentity {
    public static final UUID LEGACY_ID=UUID.fromString("00000000-0000-0000-0000-000000000000");
    private MerchantIdentity() { }
}
