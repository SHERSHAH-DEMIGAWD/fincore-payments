package com.fincore.payments.repository;

import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.domain.MerchantIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class MerchantCredentialRepository {
    private final JdbcTemplate jdbc;
    public MerchantCredentialRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public MerchantPrincipal load(String username) {
        if (username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw missing();
        return jdbc.query("""
                SELECT id,username,password_hash,enabled FROM merchants
                WHERE username=? AND id<>? AND password_hash IS NOT NULL
                """,(row,n) -> new MerchantPrincipal(row.getObject("id",UUID.class),row.getString("username"),
                row.getString("password_hash"),row.getBoolean("enabled")),username,MerchantIdentity.LEGACY_ID)
                .stream().findFirst().orElseThrow(MerchantCredentialRepository::missing);
    }
    private static UsernameNotFoundException missing() { return new UsernameNotFoundException("Merchant credentials were not accepted."); }
}
