package com.fincore.payments.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.provider.ProviderWire;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Exact UTF8 signed file protocol. No timestamp expiry: archived statements are immutable evidence. */
public final class SettlementWire {
    public static final int MAX_BYTES=1048576,MAX_LINES=1000;
    private static final UUID ZERO=new UUID(0,0);
    private static final ObjectMapper JSON=ProviderWire.mapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private SettlementWire() { }
    public static ObjectMapper mapper() { return JSON; }
    public static UUID id(UUID instance,UUID merchant,String currency,long through) {
        return UUID.nameUUIDFromBytes(("fincore-settlement-v1|"+instance+"|"+merchant+"|"+currency+"|"+through).getBytes(StandardCharsets.UTF_8));
    }
    public static Statement read(byte[] bytes) throws IOException {
        if (bytes==null || bytes.length<1 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Statement byte limit exceeded.");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        // Bind money directly to BigDecimal; a generic JSON tree may normalize its scale.
        var statement=JSON.readValue(text,Statement.class); validate(statement);
        var document=JSON.readTree(text);
        // The exact body is also bound to canonical UUID metadata by PostgreSQL.
        canonical(document,"statementId",statement.statementId());canonical(document,"instanceId",statement.instanceId());
        canonical(document,"merchantId",statement.merchantId());
        for(int i=0;i<statement.lines().size();i++){
            var line=statement.lines().get(i);var node=document.path("lines").get(i);
            canonical(node,"operationId",line.operationId());canonical(node,"paymentId",line.paymentId());
        }
        return statement;
    }
    private static void canonical(com.fasterxml.jackson.databind.JsonNode document,String name,UUID id){
        if(!id.toString().equals(document.path(name).textValue()))invalid();
    }
    public static byte[] encode(Statement statement) throws IOException {
        validate(statement); byte[] bytes=JSON.writeValueAsBytes(statement);
        if (bytes.length>MAX_BYTES) throw new IllegalArgumentException("Statement byte limit exceeded."); return bytes;
    }
    public static void validate(Statement s) {
        if (s==null || s.version()!=1 || !uuid(s.statementId()) || !uuid(s.instanceId()) || !uuid(s.merchantId())
                || s.currency()==null || !s.currency().matches("[A-Z]{3}") || s.throughSequence()<0 || s.throughSequence()>10000
                || !s.statementId().equals(id(s.instanceId(),s.merchantId(),s.currency(),s.throughSequence()))
                || s.lines()==null || s.lines().size()>MAX_LINES) invalid();
        BigDecimal captured=BigDecimal.ZERO,refunded=BigDecimal.ZERO,fees=BigDecimal.ZERO;
        long previous=0;var operations=new HashSet<UUID>();
        for(var line:s.lines()) {
            if(line==null || line.sequence()<=previous || line.sequence()>s.throughSequence() || !uuid(line.operationId())
                    || !uuid(line.paymentId()) || !operations.add(line.operationId())
                    || (line.action()!=Action.CAPTURE && line.action()!=Action.REFUND)
                    || !money(line.amount(),true,17) || !money(line.fee(),false,17) || line.fee().compareTo(line.amount())>0
                    || (line.action()==Action.REFUND && line.fee().signum()!=0)
                    || !("sandbox-pay-"+line.paymentId()).equals(line.providerPaymentId())
                    || !("sandbox-op-"+line.operationId()).equals(line.providerOperationId())) invalid();
            previous=line.sequence();
            if(line.action()==Action.CAPTURE) captured=captured.add(line.amount()); else refunded=refunded.add(line.amount());
            fees=fees.add(line.fee());
        }
        if(!money(s.captured(),false,21) || !money(s.refunded(),false,21) || !money(s.fees(),false,21)
                || !signedMoney(s.net()) || captured.compareTo(s.captured())!=0 || refunded.compareTo(s.refunded())!=0
                || fees.compareTo(s.fees())!=0 || captured.subtract(refunded).subtract(fees).compareTo(s.net())!=0) invalid();
    }
    public static String signature(byte[] body,String keyId,String key) {
        if(key==null || !key.matches("[!-~]{32,256}") || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) invalid();
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
            mac.update(("fincore-provider-settlement-v1\n"+keyId+"\n").getBytes(StandardCharsets.US_ASCII));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(body));
        } catch(java.security.GeneralSecurityException impossible) {throw new IllegalStateException(impossible);}
    }
    public static boolean authentic(byte[] body,String keyId,String signature,String key) {
        return signature!=null && signature.matches("v1=[0-9a-f]{64}") && MessageDigest.isEqual(
                signature(body,keyId,key).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
    public static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static boolean uuid(UUID id){return id!=null && !id.equals(ZERO);}
    static boolean money(BigDecimal value,boolean positive,int digits) {
        if(value==null || value.signum()<0 || (positive && value.signum()==0))return false;
        try{var n=value.stripTrailingZeros();return n.scale()<=2 && n.precision()-(long)n.scale()<=digits;}
        catch(ArithmeticException invalid){return false;}
    }
    static boolean signedMoney(BigDecimal value){return value!=null && money(value.abs(),false,21);}
    static void invalid(){throw new IllegalArgumentException("Invalid settlement document.");}
}
