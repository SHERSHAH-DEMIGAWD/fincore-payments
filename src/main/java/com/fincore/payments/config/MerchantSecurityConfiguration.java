package com.fincore.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.exception.ApiError;
import com.fincore.payments.repository.MerchantCredentialRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class MerchantSecurityConfiguration {
    @Bean @Order(2)
    SecurityFilterChain merchantSecurity(HttpSecurity http,MerchantCredentialRepository credentials,ObjectMapper mapper) throws Exception {
        var provider=new DaoAuthenticationProvider(credentials::load);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        http.securityMatcher("/api/v1/payments","/api/v1/payments/**","/api/v1/ledger/**","/api/v1/merchant/**")
                .authenticationManager(new ProviderManager(List.of(provider)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication,context) -> {
                    var auth=authentication.get();
                    return new AuthorizationDecision(auth.isAuthenticated() && auth.getPrincipal() instanceof MerchantPrincipal
                            && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT")));
                }))
                // Credentials are checked every request. The session cookie is for CSRF only.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .httpBasic(basic -> basic.realmName("fincore-merchants").authenticationEntryPoint((request,response,error) -> {
                    response.setStatus(401); response.setHeader("WWW-Authenticate","Basic realm=\"fincore-merchants\"");
                    response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(),new ApiError(Instant.now(),401,"AUTHENTICATION_REQUIRED",
                            "Merchant credentials are required.",request.getRequestURI(),Map.of()));
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request,response,error) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(),new ApiError(Instant.now(),403,"ACCESS_DENIED",
                            "Access was denied.",request.getRequestURI(),Map.of()));
                }))
                .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        // CSRF remains enabled for Basic-authenticated browser clients.
        return http.build();
    }
}
