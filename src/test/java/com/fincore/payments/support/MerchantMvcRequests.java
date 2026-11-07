package com.fincore.payments.support;

import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.domain.MerchantIdentity;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Explicit trusted legacy principal for earlier HTTP regressions, not an authenticatable account.
 * Real Basic authentication, CSRF refusal and merchant isolation use ordinary builders in MerchantSecurityDatabaseTests.
 */
public final class MerchantMvcRequests {
    private MerchantMvcRequests() { }
    public static MockHttpServletRequestBuilder get(String uri,Object... variables) {
        return MockMvcRequestBuilders.get(uri,variables).with(user(principal()));
    }
    public static MockHttpServletRequestBuilder post(String uri,Object... variables) {
        return MockMvcRequestBuilders.post(uri,variables).with(user(principal())).with(csrf());
    }
    private static MerchantPrincipal principal() {
        return new MerchantPrincipal(MerchantIdentity.LEGACY_ID,"legacy-fixture","unused-test-only",true);
    }
}
