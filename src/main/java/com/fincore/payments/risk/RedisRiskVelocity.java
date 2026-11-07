package com.fincore.payments.risk;

import com.fincore.payments.config.RiskProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;
import java.util.UUID;

public class RedisRiskVelocity implements RiskVelocity {
    private static final DefaultRedisScript<List> SCRIPT=script();
    private static DefaultRedisScript<List> script(){var script=new DefaultRedisScript<List>();
        script.setLocation(new ClassPathResource("redis/payment-admission.lua"));script.setResultType(List.class);return script;}
    private final StringRedisTemplate redis;
    private final RiskProperties settings;
    public RedisRiskVelocity(StringRedisTemplate redis,RiskProperties settings){this.redis=redis;this.settings=settings;}
    public String key(UUID merchant){return "fincore:risk:v1:{"+merchant+"}:"+settings.policyHash();}
    @Override public Reservation reserve(UUID merchant,String intentHash){
        List<?> result=redis.execute(SCRIPT,List.of(key(merchant)),Integer.toString(settings.maxAdmissions()),
            Long.toString(settings.windowSeconds()*1000L),intentHash);
        if(result==null || result.size()!=3 || !(result.get(0) instanceof Long allowed) || !(result.get(1) instanceof Long count)
                || !(result.get(2) instanceof Long ttl) || (allowed!=0 && allowed!=1) || count<1 || count>settings.maxAdmissions()
                || ttl<1 || ttl>settings.windowSeconds()*1000L || (allowed==0 && count!=settings.maxAdmissions()))
            throw new IllegalStateException("Invalid Redis admission response.");
        return new Reservation(allowed==1,count,ttl);
    }
}
