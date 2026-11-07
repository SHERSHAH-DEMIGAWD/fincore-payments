package com.fincore.payments.config;

import com.fincore.payments.settlement.SettlementWire;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

@Validated
@ConfigurationProperties(prefix="fincore.risk")
public record RiskProperties(@DefaultValue("false") boolean enabled,@DefaultValue("INR") String currency,
        @DefaultValue("100000.00") BigDecimal maximumAmount,@DefaultValue("20") int maxAdmissions,
        @DefaultValue("60") int windowSeconds,@DefaultValue("local") String namespace,
        @DefaultValue("127.0.0.1") String redisHost,@DefaultValue("6379") int redisPort,
        @DefaultValue("0") int redisDatabase,@DefaultValue("250") int timeoutMillis,
        @DefaultValue("") String redisUsername,@DefaultValue("") String redisPassword) {
    @AssertTrue(message="Enabled risk controls require bounded policy values and a loopback Redis endpoint.")
    public boolean isValidConfiguration(){
        if(!enabled)return true;
        try{
            var n=maximumAmount.stripTrailingZeros();
            return currency!=null && currency.matches("[A-Z]{3}") && n.signum()>0 && n.scale()<=2 && n.precision()-(long)n.scale()<=17
                && maxAdmissions>=1 && maxAdmissions<=1000 && windowSeconds>=1 && windowSeconds<=3600
                && namespace!=null && namespace.matches("[A-Za-z0-9_-]{1,40}") && "127.0.0.1".equals(redisHost)
                && redisPort>=1 && redisPort<=65535 && redisDatabase>=0 && redisDatabase<=15 && timeoutMillis>=50 && timeoutMillis<=1000
                && redisUsername!=null && redisPassword!=null && redisUsername.length()<=128 && redisPassword.length()<=256
                && redisUsername.chars().noneMatch(Character::isISOControl) && redisPassword.chars().noneMatch(Character::isISOControl)
                && (redisUsername.isEmpty() || !redisPassword.isEmpty());
        }catch(NullPointerException|ArithmeticException invalid){return false;}
    }
    public String policyHash(){return SettlementWire.sha256(("risk-v1|"+namespace+"|"+currency+"|"+maximumAmount.setScale(2).toPlainString()+"|"+maxAdmissions+"|"+windowSeconds).getBytes(StandardCharsets.UTF_8));}
    @Override public String toString(){return "RiskProperties[enabled="+enabled+",credentials=REDACTED]";}
}
