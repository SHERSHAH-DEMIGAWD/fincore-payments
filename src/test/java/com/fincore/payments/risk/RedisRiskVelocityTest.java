package com.fincore.payments.risk;

import com.fincore.payments.config.RiskProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisRiskVelocityTest {
    @Test void reservationTokenBindsTheKeyAndCanonicalRequestFingerprint(){
        assertThat(RiskVelocity.intentHash("key","a".repeat(64))).isNotEqualTo(RiskVelocity.intentHash("other","a".repeat(64)))
            .isNotEqualTo(RiskVelocity.intentHash("key","b".repeat(64)));
    }
    static RiskProperties settings(int limit){return new RiskProperties(true,"INR",new BigDecimal("1000"),limit,1,"test","127.0.0.1",6379,0,100,"","");}
    @Test void resultAndScopedKeyComeFromOneAtomicScript(){
        var redis=mock(StringRedisTemplate.class);var p=settings(2);var gateway=new RedisRiskVelocity(redis,p);var merchant=UUID.randomUUID();
        when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenReturn(List.of(1L,1L,1000L));
        assertThat(gateway.reserve(merchant,"a".repeat(64))).isEqualTo(new RiskVelocity.Reservation(true,1,1000));
        verify(redis).execute(any(RedisScript.class),eq(List.of(gateway.key(merchant))),eq("2"),eq("1000"),eq("a".repeat(64)));
        assertThat(gateway.key(merchant)).contains("{"+merchant+"}",p.policyHash()).doesNotContain("1000.00");
    }
    @ParameterizedTest @ValueSource(strings={"null","short","wrongtype","badflag","count","ttl","throttle"})
    void malformedScriptResultsCannotAuthorize(String kind){
        var redis=mock(StringRedisTemplate.class);List<?> result=switch(kind){case "null"->null;case "short"->List.of(1L);case "wrongtype"->List.of("1",1L,1L);
            case "badflag"->List.of(2L,1L,1L);case "count"->List.of(1L,3L,1L);case "ttl"->List.of(1L,1L,0L);default->List.of(0L,1L,1000L);};
        when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenReturn(result);
        assertThatThrownBy(()->new RedisRiskVelocity(redis,settings(2)).reserve(UUID.randomUUID(),"a".repeat(64))).isInstanceOf(IllegalStateException.class);
    }
}
