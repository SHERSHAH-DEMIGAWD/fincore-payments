package com.fincore.payments.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectionOperationsSecurityConfiguration {
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");

    private final String username;
    private final String passwordHash;
    private final boolean configured;

    public ProjectionOperationsSecurityConfiguration(
            @Value("${fincore.operations.username:}") String username,
            @Value("${fincore.operations.password-hash:}") String passwordHash) {
        boolean hasUsername = !username.isEmpty();
        boolean hasPasswordHash = !passwordHash.isEmpty();
        if (hasUsername != hasPasswordHash) {
            throw new IllegalArgumentException("Projection operations require both username and BCrypt password hash");
        }
        if (hasUsername && (username.isBlank() || !username.equals(username.strip()) || username.length() > 100
                || username.indexOf(':') >= 0
                || username.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR))) {
            throw new IllegalArgumentException("Projection operations username must be a single nonblank line of at most 100 characters");
        }
        if (hasPasswordHash && !BCRYPT_HASH.matcher(passwordHash).matches()) {
            throw new IllegalArgumentException("Projection operations password hash must be a BCrypt hash");
        }
        this.username = username;
        this.passwordHash = passwordHash;
        this.configured = hasUsername;
    }

    @Bean
    UserDetailsService projectionOperationsUsers() {
        if (!configured) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(username).password(passwordHash)
                .roles("PROJECTION_OPERATOR").build());
    }

    @Bean
    PasswordEncoder projectionOperationsPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain projectionOperationsSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/operations/projection-routing", "/api/v1/operations/projection-routing/**")
                .authorizeHttpRequests(authorize -> {
                    if (configured) {
                        authorize.anyRequest().hasRole("PROJECTION_OPERATOR");
                    } else {
                        authorize.anyRequest().denyAll();
                    }
                })
                .httpBasic(basic -> basic.realmName("fincore-projection-operations"))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        // CSRF stays enabled: clients send the token and session cookie obtained from GET /csrf.
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain unmatchedApiSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
