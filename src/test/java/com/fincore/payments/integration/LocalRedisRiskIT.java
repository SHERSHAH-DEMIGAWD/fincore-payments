package com.fincore.payments.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="FINCORE_TEST_REDIS_PORT",matches="[0-9]+")
class LocalRedisRiskIT extends RedisRiskTests {
    @Override int port(){return Integer.parseInt(System.getenv("FINCORE_TEST_REDIS_PORT"));}
    @Override int database(){return Integer.parseInt(System.getenv().getOrDefault("FINCORE_TEST_REDIS_DATABASE","15"));}
}
