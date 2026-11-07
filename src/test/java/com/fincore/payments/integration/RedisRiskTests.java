package com.fincore.payments.integration;

import com.fincore.payments.config.*;
import com.fincore.payments.risk.*;
import com.fincore.payments.repository.PaymentRiskRepository;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Redis script/client checks; namespace-isolated, with no FLUSHDB or global cleanup. */
abstract class RedisRiskTests {
    abstract int port();int database(){return 0;}
    AnnotationConfigApplicationContext context;RedisRiskVelocity velocity;StringRedisTemplate redis;RiskProperties properties;UUID merchant;
    final List<String> ownedKeys=new ArrayList<>();
    @BeforeEach void start(){context=new AnnotationConfigApplicationContext();merchant=UUID.randomUUID();
        TestPropertyValues.of("fincore.risk.enabled=true","fincore.risk.redis-port="+port(),"fincore.risk.redis-database="+database(),
            "fincore.risk.max-admissions=2","fincore.risk.window-seconds=10","fincore.risk.namespace=t"+UUID.randomUUID().toString().replace("-",""))
            .applyTo(context);context.register(RiskConfiguration.class);context.refresh();velocity=context.getBean(RedisRiskVelocity.class);
        redis=context.getBean(StringRedisTemplate.class);properties=context.getBean(RiskProperties.class);ownedKeys.add(velocity.key(merchant));}
    @AfterEach void stop(){try{if(redis!=null)redis.delete(ownedKeys);}finally{if(context!=null)context.close();}}
    String key(int i){return PaymentRiskRepository.keyHash("request-"+i);}
    @Test void sharedServerCapacityAndExactReservationRetry(){
        assertThat(velocity.reserve(merchant,key(1)).usedCount()).isEqualTo(1);
        var secondGateway=new RedisRiskVelocity(redis,properties);assertThat(secondGateway.reserve(merchant,key(1)).usedCount()).isEqualTo(1);
        assertThat(secondGateway.reserve(merchant,key(2)).allowed()).isTrue();assertThat(velocity.reserve(merchant,key(3)).allowed()).isFalse();
        assertThat(redis.opsForHash().size(velocity.key(merchant))).isEqualTo(3);
    }
    @Test void concurrentDistinctIntentsCannotExceedCapacity()throws Exception{
        var pool=Executors.newFixedThreadPool(6);try{var start=new CountDownLatch(1);var futures=new ArrayList<Future<RiskVelocity.Reservation>>();
            for(int i=0;i<6;i++){int n=i;futures.add(pool.submit(()->{start.await();return velocity.reserve(merchant,key(n));}));}start.countDown();
            int allowed=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS).allowed())allowed++;assertThat(allowed).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }
    @Test void expiryAllowsTheSamePreviouslyThrottledIntent(){
        velocity.reserve(merchant,key(1));velocity.reserve(merchant,key(2));assertThat(velocity.reserve(merchant,key(3)).allowed()).isFalse();
        redis.expire(velocity.key(merchant),Duration.ofMillis(20));await().atMost(Duration.ofSeconds(3)).until(()->!Boolean.TRUE.equals(redis.hasKey(velocity.key(merchant))));
        assertThat(velocity.reserve(merchant,key(3)).usedCount()).isEqualTo(1);
    }
    @Test void persistentOrWrongTypeCounterIsRefusedWithoutAutomaticDeletion(){
        redis.opsForHash().put(velocity.key(merchant),"used","1");assertThatThrownBy(()->velocity.reserve(merchant,key(1))).isInstanceOf(RuntimeException.class);
        assertThat(redis.hasKey(velocity.key(merchant))).isTrue();
        UUID other=UUID.randomUUID();ownedKeys.add(velocity.key(other));redis.opsForValue().set(velocity.key(other),"corrupt",Duration.ofSeconds(10));
        assertThatThrownBy(()->velocity.reserve(other,key(1))).isInstanceOf(RuntimeException.class);assertThat(redis.opsForValue().get(velocity.key(other))).isEqualTo("corrupt");
    }
}
