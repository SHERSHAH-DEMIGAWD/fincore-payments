package com.fincore.payments.repository;

import com.fincore.payments.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    boolean existsByIdAndMerchantId(UUID id, UUID merchantId);

    @org.springframework.data.jpa.repository.Query(value="SELECT EXISTS (SELECT 1 FROM payment_amount_bindings WHERE payment_id=:id)",nativeQuery=true)
    boolean isAmountManaged(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(value="SELECT EXISTS (SELECT 1 FROM payment_provider_bindings WHERE payment_id=:id)",nativeQuery=true)
    boolean isProviderManaged(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(value="""
            SELECT EXISTS (SELECT 1 FROM payment_provider_operations WHERE payment_id=:payment AND operation_id=:operation
                AND state IN ('APPLIED','DECLINED') AND application_xid=CAST(pg_current_xact_id() AS text))
            """,nativeQuery=true)
    boolean providerApplicationAllowed(@org.springframework.data.repository.query.Param("payment") UUID payment,
            @org.springframework.data.repository.query.Param("operation") UUID operation);
}
