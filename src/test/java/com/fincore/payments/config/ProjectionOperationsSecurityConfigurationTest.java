package com.fincore.payments.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionOperationsSecurityConfigurationTest {
    private static final String HASH = new BCryptPasswordEncoder(4).encode("test-only-password");

    @Test
    void acceptsAbsentCredentialsAndCreatesNoDefaultUser() {
        var configuration = new ProjectionOperationsSecurityConfiguration("", "");
        assertThatThrownBy(() -> configuration.projectionOperationsUsers().loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void requiresUsernameAndHashTogether() {
        assertThatThrownBy(() -> new ProjectionOperationsSecurityConfiguration("operator", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectionOperationsSecurityConfiguration("", HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "{noop}password", " ", "$2a$10$bad", "$2a$32$....................................................."})
    void rejectsPlaintextOrMalformedPasswordHashes(String hash) {
        assertThatThrownBy(() -> new ProjectionOperationsSecurityConfiguration("operator", hash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " operator", "operator ", "operator\nadmin", "operator\u2028admin", "operator\u2029admin", "operator:admin"})
    void rejectsBlankPaddedOrMultilineUsername(String username) {
        assertThatThrownBy(() -> new ProjectionOperationsSecurityConfiguration(username, HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedUsername() {
        assertThatThrownBy(() -> new ProjectionOperationsSecurityConfiguration("a".repeat(101), HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsOnlyConfiguredOperatorWithBcryptHash() {
        var configuration = new ProjectionOperationsSecurityConfiguration("operator", HASH);
        var operator = configuration.projectionOperationsUsers().loadUserByUsername("operator");
        assertThat(operator.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_PROJECTION_OPERATOR");
        assertThat(operator.getPassword()).isEqualTo(HASH);
        assertThat(configuration.projectionOperationsPasswordEncoder().matches("test-only-password", operator.getPassword())).isTrue();
        assertThatCode(() -> new ProjectionOperationsSecurityConfiguration("operator", HASH)).doesNotThrowAnyException();
    }
}
