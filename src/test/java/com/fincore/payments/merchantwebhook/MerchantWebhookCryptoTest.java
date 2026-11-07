package com.fincore.payments.merchantwebhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MerchantWebhookCryptoTest {
    static final String MASTER=Base64.getEncoder().encodeToString(new byte[32]);
    static final String MASTER_ID="test-master-v1";
    final UUID merchant=UUID.randomUUID(),subscription=UUID.randomUUID();

    @Test void freshSecretsAndNoncesAreIndependentAndRecoverable() {
        String secret=MerchantWebhookCrypto.generateSecret();
        assertThat(secret).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(MerchantWebhookCrypto.generateSecret());
        var one=MerchantWebhookCrypto.encrypt(MASTER,MASTER_ID,merchant,subscription,secret);
        var two=MerchantWebhookCrypto.encrypt(MASTER,MASTER_ID,merchant,subscription,secret);
        assertThat(one.nonce()).hasSize(12).isNotEqualTo(two.nonce());
        assertThat(one.ciphertext()).hasSize(secret.length()+16).isNotEqualTo(two.ciphertext());
        assertThat(MerchantWebhookCrypto.decrypt(MASTER,MASTER_ID,merchant,subscription,one.nonce(),one.ciphertext())).isEqualTo(secret);
        assertThat(one.toString()).isEqualTo("Sealed[REDACTED]");
        byte[] copy=one.nonce(); copy[0]^=1;
        assertThat(MerchantWebhookCrypto.decrypt(MASTER,MASTER_ID,merchant,subscription,one.nonce(),one.ciphertext())).isEqualTo(secret);
    }
    @ParameterizedTest @ValueSource(strings={"key","master-id","merchant","subscription","nonce","ciphertext","nonce-length","ciphertext-length"})
    void aeadRefusesTamperingAndCrossSubscriptionKeySubstitution(String change) {
        String secret=MerchantWebhookCrypto.generateSecret(); var sealed=MerchantWebhookCrypto.encrypt(MASTER,MASTER_ID,merchant,subscription,secret);
        String key=MASTER,id=MASTER_ID; UUID owner=merchant,target=subscription; byte[] nonce=sealed.nonce(),ciphertext=sealed.ciphertext();
        switch(change) {
            case "key" -> { byte[] other=new byte[32]; other[0]=1; key=Base64.getEncoder().encodeToString(other); }
            case "master-id" -> id="other-master";
            case "merchant" -> owner=UUID.randomUUID();
            case "subscription" -> target=UUID.randomUUID();
            case "nonce" -> nonce[0]^=1;
            case "ciphertext" -> ciphertext[0]^=1;
            case "nonce-length" -> nonce=new byte[11];
            case "ciphertext-length" -> ciphertext=new byte[10];
        }
        String k=key,i=id; UUID m=owner,s=target; byte[] n=nonce,c=ciphertext;
        assertThatThrownBy(() -> MerchantWebhookCrypto.decrypt(k,i,m,s,n,c)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Merchant webhook signing key is unavailable.").hasNoCause();
    }
    @ParameterizedTest @ValueSource(strings={"","not-base64","AAAAAAAAAAAAAAAAAAAAAA==","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="})
    void masterKeyMustBeCanonicalStandardBase64ForExactly256Bits(String key) {
        assertThatThrownBy(() -> MerchantWebhookCrypto.encrypt(key,MASTER_ID,merchant,subscription,MerchantWebhookCrypto.generateSecret()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Merchant webhook encryption configuration is invalid.").hasNoCause();
    }
    @Test void signingHasAnIndependentFixedHmacFixtureAndDomainSeparation() {
        String key="merchant-webhook-signing-fixture-key-12345",keyId="11111111-1111-4111-8111-111111111119";
        byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        // Generated independently with Python hmac.new(key, documented frame, hashlib.sha256).
        String expected="v1=7e059581dd51d77a7ca4da37b5302e343a7f164e1cc350f23287ff4c3b81d42b";
        assertThat(MerchantWebhookCrypto.signature(key,keyId,1893456000,body)).isEqualTo(expected);
        assertThat(MerchantWebhookCrypto.signature(key,keyId,1893456001,body)).isNotEqualTo(expected);
        assertThat(MerchantWebhookCrypto.signature(key,"another-key",1893456000,body)).isNotEqualTo(expected);
        assertThat(MerchantWebhookCrypto.signature(key,keyId,1893456000,"{} ".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(expected);
        assertThat(MerchantWebhookCrypto.sha256(body)).isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
    }
}
