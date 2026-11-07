package com.fincore.payments.config;

import com.fincore.payments.provider.ProviderWebhookWire;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ProviderWebhookSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain providerWebhookSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(ProviderWebhookWire.PATH)
                .authorizeHttpRequests(authorize -> authorize.requestMatchers(HttpMethod.POST,ProviderWebhookWire.PATH).permitAll().anyRequest().denyAll())
                // This exact machine-to-machine route uses raw-body HMAC, not browser credentials.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
