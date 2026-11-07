package com.fincore.payments.config;

import com.fincore.payments.risk.*;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfiguration {
    @Bean @ConditionalOnProperty(name="fincore.risk.enabled",havingValue="false",matchIfMissing=true)
    RiskVelocity disabledRiskVelocity(){return (merchant,key)->{throw new IllegalStateException("Risk controls are disabled.");};}
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="fincore.risk.enabled",havingValue="true")
    static class Enabled {
        @Bean LettuceConnectionFactory riskRedisConnectionFactory(RiskProperties p){
            var server=new RedisStandaloneConfiguration(p.redisHost(),p.redisPort());server.setDatabase(p.redisDatabase());
            if(!p.redisUsername().isEmpty())server.setUsername(p.redisUsername());
            if(!p.redisPassword().isEmpty())server.setPassword(p.redisPassword());
            var duration=Duration.ofMillis(p.timeoutMillis());
            var options=ClientOptions.builder().disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(64).socketOptions(SocketOptions.builder().connectTimeout(duration).build()).build();
            return new LettuceConnectionFactory(server,LettuceClientConfiguration.builder().commandTimeout(duration)
                .shutdownTimeout(Duration.ZERO).clientOptions(options).build());
        }
        @Bean StringRedisTemplate riskRedisTemplate(LettuceConnectionFactory riskRedisConnectionFactory){return new StringRedisTemplate(riskRedisConnectionFactory);}
        @Bean RiskVelocity redisRiskVelocity(StringRedisTemplate riskRedisTemplate,RiskProperties p){return new RedisRiskVelocity(riskRedisTemplate,p);}
    }
}
