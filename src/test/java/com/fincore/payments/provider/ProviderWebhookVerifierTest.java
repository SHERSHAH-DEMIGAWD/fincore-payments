package com.fincore.payments.provider;

import com.fincore.payments.config.ProviderWebhookProperties;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.dto.ProviderWebhook.*;
import com.fincore.payments.exception.ProviderWebhookException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProviderWebhookVerifierTest {
    static final String KEY="unit-test-only-webhook-signing-key-12345",KEY_ID="sandbox-v1";
    static final Instant NOW=Instant.ofEpochSecond(1893456000);
    final UUID instance=UUID.randomUUID();
    ProviderWebhookVerifier verifier(boolean enabled) {
        return new ProviderWebhookVerifier(new ProviderWebhookProperties(enabled,KEY,KEY_ID,instance,300),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    Event event() {
        UUID operation=UUID.randomUUID(),payment=UUID.randomUUID();
        return new Event(1,UUID.randomUUID(),new Response(1,instance,operation,payment,UUID.randomUUID(),Action.AUTHORIZE,
                new BigDecimal("10.00"),"INR",Outcome.APPROVED,"sandbox-pay-"+payment,"sandbox-op-"+operation));
    }
    MockHttpServletRequest request(byte[] body,long signedAt) {
        var request=new MockHttpServletRequest("POST",ProviderWebhookWire.PATH);
        request.setContent(body); request.addHeader(ProviderWebhookWire.TIMESTAMP,Long.toString(signedAt));
        request.addHeader(ProviderWebhookWire.KEY_ID,KEY_ID);
        request.addHeader(ProviderWebhookWire.SIGNATURE,ProviderWebhookWire.signature(KEY,KEY_ID,Long.toString(signedAt),body));
        return request;
    }
    @Test void validExactBytesAreVerifiedAndCannotBeMutatedAfterVerification() throws Exception {
        byte[] body=ProviderWire.mapper().writeValueAsBytes(event());
        var result=verifier(true).verify(request(body,NOW.getEpochSecond()));
        assertThat(result.body()).isEqualTo(body); assertThat(result.keyId()).isEqualTo(KEY_ID);
        byte[] copy=result.body(); copy[0]='!'; assertThat(result.body()[0]).isEqualTo((byte)'{');
    }
    @ParameterizedTest @ValueSource(longs={-300,300})
    void replayWindowIsInclusiveAtBothBoundaries(long offset) throws Exception {
        byte[] body=ProviderWire.mapper().writeValueAsBytes(event());
        assertThat(verifier(true).verify(request(body,NOW.getEpochSecond()+offset)).event()).isNotNull();
    }
    @Test void disabledReceiverDoesNotAcceptAValidSignature() throws Exception {
        byte[] body=ProviderWire.mapper().writeValueAsBytes(event());
        assertThatThrownBy(() -> verifier(false).verify(request(body,NOW.getEpochSecond())))
                .isInstanceOfSatisfying(ProviderWebhookException.class,e -> assertThat(e.status()).isEqualTo(503));
    }
    @ParameterizedTest @ValueSource(strings={"missing-signature","bad-signature","wrong-key-id","duplicate-header","stale","future","bad-timestamp","wrong-instance",
            "duplicate-json","trailing","unknown","version","coercion","null","invalid-utf8","oversized","whitespace-tamper","wrong-remote-ref","scale","precision","negative-amount","exponent","fraction-exponent","scale-overflow"})
    void rejectsUnverifiedOrAmbiguousInputs(String mutation) throws Exception {
        String json=ProviderWire.mapper().writeValueAsString(event()); int expected=400;
        if (mutation.equals("wrong-instance")) { json=json.replace(instance.toString(),UUID.randomUUID().toString()); expected=401; }
        if (mutation.equals("duplicate-json")) json=json.replaceFirst("\\{","{\"version\":1,");
        if (mutation.equals("trailing")) json+=" {}";
        if (mutation.equals("unknown")) json=json.replaceFirst("\\{","{\"unexpected\":true,");
        if (mutation.equals("version")) json=json.replaceFirst("\"version\":1","\"version\":2");
        if (mutation.equals("coercion")) json=json.replaceFirst("\"version\":1","\"version\":\"1\"");
        if (mutation.equals("null")) json="null";
        if (mutation.equals("wrong-remote-ref")) json=json.replace("sandbox-op-","foreign-op-");
        if (mutation.equals("scale")) json=json.replace("10.00","10.001");
        if (mutation.equals("precision")) json=json.replace("10.00","999999999999999999.00");
        if (mutation.equals("negative-amount")) json=json.replace("10.00","-10.00");
        if (mutation.equals("exponent")) json=json.replace("10.00","1e1000000");
        if (mutation.equals("fraction-exponent")) json=json.replace("10.00","1e-1000000");
        if (mutation.equals("scale-overflow")) json=json.replace("10.00","100e2147483647");
        byte[] body=mutation.equals("invalid-utf8") ? new byte[]{(byte)0xc3,(byte)0x28} : json.getBytes(StandardCharsets.UTF_8);
        if (mutation.equals("oversized")) { body=new byte[ProviderWire.MAX_BYTES+1]; expected=413; }
        long timestamp=NOW.getEpochSecond()+(mutation.equals("stale") ? -301 : mutation.equals("future") ? 301 : 0);
        var request=request(body,timestamp);
        switch(mutation) {
            case "missing-signature" -> { request.removeHeader(ProviderWebhookWire.SIGNATURE); expected=401; }
            case "bad-signature" -> { request.removeHeader(ProviderWebhookWire.SIGNATURE); request.addHeader(ProviderWebhookWire.SIGNATURE,"v1="+"0".repeat(64)); expected=401; }
            case "wrong-key-id" -> { request.removeHeader(ProviderWebhookWire.KEY_ID); request.addHeader(ProviderWebhookWire.KEY_ID,"other-key"); expected=401; }
            case "duplicate-header" -> { request.addHeader(ProviderWebhookWire.TIMESTAMP,Long.toString(timestamp)); expected=401; }
            case "bad-timestamp" -> { request.removeHeader(ProviderWebhookWire.TIMESTAMP); request.addHeader(ProviderWebhookWire.TIMESTAMP,"+1893456000"); expected=401; }
            case "stale","future" -> expected=401;
            case "whitespace-tamper" -> { request.setContent((json+" ").getBytes(StandardCharsets.UTF_8)); expected=401; }
        }
        int status=expected;
        String prefix=json.substring(0,Math.min(8,json.length()));
        assertThatThrownBy(() -> verifier(true).verify(request)).isInstanceOfSatisfying(ProviderWebhookException.class,e -> {
            assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).doesNotContain(KEY,prefix);
        });
    }
    @Test void signatureSeparatesKeyIdentityTimestampAndExactPayload() {
        byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        var signature=ProviderWebhookWire.signature(KEY,KEY_ID,"1893456000",body);
        // Independent Python hmac/SHA-256 fixture for the documented framing and exact bytes.
        assertThat(signature).isEqualTo("v1=a87e76dfb6235718a1b3bc4087862581331b280807104db3e6f516777f4c7b89");
        assertThat(signature).isNotEqualTo(ProviderWebhookWire.signature(KEY,"other-key","1893456000",body))
                .isNotEqualTo(ProviderWebhookWire.signature(KEY,KEY_ID,"1893456001",body))
                .isNotEqualTo(ProviderWebhookWire.signature(KEY,KEY_ID,"1893456000","{} ".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void aDeliveryThatExpiresWhileItsBodyIsReadIsRefused() throws Exception {
        var clock=org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW,NOW.plusSeconds(301));
        var verifier=new ProviderWebhookVerifier(new ProviderWebhookProperties(true,KEY,KEY_ID,instance,300),clock);
        byte[] body=ProviderWire.mapper().writeValueAsBytes(event());
        assertThatThrownBy(() -> verifier.verify(request(body,NOW.getEpochSecond())))
                .isInstanceOfSatisfying(ProviderWebhookException.class,e -> assertThat(e.status()).isEqualTo(401));
    }
}
