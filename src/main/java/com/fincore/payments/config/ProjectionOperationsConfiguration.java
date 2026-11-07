package com.fincore.payments.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({ProjectionOperationsProperties.class,ProjectionApprovalProperties.class,ProviderProperties.class,ProviderWebhookProperties.class,SettlementProperties.class})
public class ProjectionOperationsConfiguration { }
