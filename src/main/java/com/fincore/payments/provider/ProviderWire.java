package com.fincore.payments.provider;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fincore.payments.dto.ProviderOperation.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Objects;

/** A closed, bounded v1 sandbox contract; it carries no card details or arbitrary provider payloads. */
public final class ProviderWire {
    public static final int MAX_BYTES=8192;
    private ProviderWire() { }
    public static ObjectMapper mapper() {
        return JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    }
    public static <T> T read(byte[] bytes,Class<T> type) throws java.io.IOException {
        if (bytes.length<1 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Invalid sandbox document.");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return mapper().readValue(text,type);
    }
    public static void validate(Request r) {
        if (r==null || r.version()!=1 || r.instanceId()==null || r.operationId()==null || r.paymentId()==null
                || r.merchantId()==null || r.action()==null || r.sandboxScenario()==null || !validAmount(r.amount())
                || r.currency()==null || !r.currency().matches("[A-Z]{3}") || r.providerPaymentId()==null
                || (!r.providerPaymentId().isEmpty() && !r.providerPaymentId().matches("sandbox-pay-[0-9a-f-]{36}")))
            throw new IllegalArgumentException("Invalid sandbox request.");
    }
    static boolean validAmount(BigDecimal amount) {
        if (amount==null || amount.signum()<=0) return false;
        try {
            var normalized=amount.stripTrailingZeros();
            return normalized.scale()<=2 && normalized.precision()-(long)normalized.scale()<=17;
        } catch (ArithmeticException invalidScale) { return false; }
    }
    public static void validate(Response response,Request request) {
        validate(request);
        if (response==null || response.version()!=1 || !Objects.equals(response.instanceId(),request.instanceId())
                || !Objects.equals(response.operationId(),request.operationId()) || !Objects.equals(response.paymentId(),request.paymentId())
                || !Objects.equals(response.merchantId(),request.merchantId()) || response.action()!=request.action()
                || response.amount()==null || response.amount().compareTo(request.amount())!=0
                || !Objects.equals(response.currency(),request.currency()) || response.outcome()==null
                || !Objects.equals(response.providerOperationId(),"sandbox-op-"+request.operationId())
                || response.providerPaymentId()==null
                || (response.outcome()==Outcome.APPROVED && !response.providerPaymentId().equals("sandbox-pay-"+request.paymentId()))
                || (response.outcome()==Outcome.DECLINED && !response.providerPaymentId().equals(request.providerPaymentId())))
            throw new IllegalArgumentException("Sandbox response binding failed.");
    }
}
