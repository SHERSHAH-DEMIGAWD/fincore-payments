package com.fincore.payments.merchantwebhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MerchantWebhookDestinationPolicyTest {
    @ParameterizedTest @ValueSource(strings={"http://127.0.0.1:18082/webhooks","https://[::1]:443/hooks/merchant_a-1","http://127.0.0.1:1/"})
    void acceptsOnlyExactCanonicalLiteralLoopbackDestinations(String url) {
        assertThat(MerchantWebhookDestinationPolicy.canonical(url)).isEqualTo(url);
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,List.of(url))).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:18082/webhooks","http://example.com:18082/webhooks","http://127.0.0.2:18082/webhooks",
            "http://2130706433:18082/webhooks","http://127.1:18082/webhooks","http://[::ffff:127.0.0.1]:18082/webhooks",
            "http://127.0.0.1/webhooks","http://127.0.0.1:0/webhooks","http://127.0.0.1:65536/webhooks","http://127.0.0.1:018082/webhooks",
            "http://user@127.0.0.1:18082/webhooks","http://127.0.0.1:18082/webhooks?secret=private","http://127.0.0.1:18082/webhooks#fragment",
            "http://127.0.0.1:18082/a/../webhooks","http://127.0.0.1:18082/a/./webhooks","http://127.0.0.1:18082/%77ebhooks",
            "http://127.0.0.1:18082//webhooks","http://127.0.0.1:18082","HTTP://127.0.0.1:18082/webhooks","file://127.0.0.1:18082/webhooks",
            " http://127.0.0.1:18082/webhooks","http://127.0.0.1:18082/webhooks ","http://127.0.0.1:18082/../private"})
    void refusesHostPortEncodingAndPathBypasses(String url) {
        assertThatThrownBy(() -> MerchantWebhookDestinationPolicy.canonical(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Merchant webhook destination is not a canonical approved sandbox URL.");
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,List.of(url))).isFalse();
    }
    @Test void allowlistBindsTheWholeDestinationRatherThanItsHostOrPort() {
        String url="http://127.0.0.1:18082/webhooks";
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,List.of("http://127.0.0.1:18083/webhooks"))).isFalse();
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,List.of("http://127.0.0.1:18082/other"))).isFalse();
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,List.of())).isFalse();
        assertThat(MerchantWebhookDestinationPolicy.allowed(url,null)).isFalse();
        assertThat(MerchantWebhookDestinationPolicy.allowed(null,List.of(url))).isFalse();
    }
}
