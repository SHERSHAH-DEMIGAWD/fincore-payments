package com.fincore.payments.integration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers(disabledWithoutDocker=true)
class RedisRiskIntegrationIT extends RedisRiskTests {
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    @Override int port(){return redis.getMappedPort(6379);}
}
