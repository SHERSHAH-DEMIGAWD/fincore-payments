package com.fincore.payments.risk;
import java.util.UUID;
public interface RiskVelocity {
    record Reservation(boolean allowed,long usedCount,long remainingMillis) { }
    Reservation reserve(UUID merchant,String intentHash);
    static String intentHash(String key,String fingerprint){return com.fincore.payments.settlement.SettlementWire.sha256(
        ("payment-risk-intent-v1\n"+key+"\n"+fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
