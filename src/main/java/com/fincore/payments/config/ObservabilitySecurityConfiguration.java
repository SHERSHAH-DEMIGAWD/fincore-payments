package com.fincore.payments.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import java.util.List;

/** A separate read-only credential domain; it cannot authenticate as a merchant/operator. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilitySecurityConfiguration {
    @Bean @Order(-1)
    SecurityFilterChain observabilitySecurity(HttpSecurity http,ObservabilityProperties p)throws Exception{
        var users=new InMemoryUserDetailsManager();
        if(p.configured())users.createUser(User.withUsername(p.username()).password(p.passwordHash()).roles("OBSERVABILITY").build());
        var provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(new BCryptPasswordEncoder());
        AuthenticationEntryPoint entry=(request,response,error)->{
            response.setStatus(401);response.setHeader("WWW-Authenticate","Basic realm=\"fincore-observability\"");
            response.setContentType("application/json");response.getWriter().write("{\"error\":\"OBSERVABILITY_AUTHENTICATION_REQUIRED\"}");
        };
        http.securityMatcher("/internal/observability","/internal/observability/**")
            .authenticationManager(new ProviderManager(List.of(provider)))
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(s->s.securityContextRepository(new NullSecurityContextRepository()))
            .authorizeHttpRequests(a->{
                if(p.configured())a.requestMatchers(HttpMethod.GET,"/internal/observability/prometheus","/internal/observability/health",
                    "/internal/observability/health/readiness","/internal/observability/health/liveness").hasRole("OBSERVABILITY");
                a.anyRequest().denyAll();
            })
            .httpBasic(b->b.realmName("fincore-observability").authenticationEntryPoint(entry))
            .exceptionHandling(e->e.authenticationEntryPoint(entry).accessDeniedHandler((request,response,error)->response.setStatus(403)))
            .csrf(AbstractHttpConfigurer::disable) // This chain authorizes GET only.
            .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
