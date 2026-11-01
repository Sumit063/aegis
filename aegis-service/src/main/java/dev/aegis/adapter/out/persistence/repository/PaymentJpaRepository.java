package dev.aegis.adapter.out.persistence.repository;

import dev.aegis.adapter.out.persistence.entity.PaymentEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {

    /**
     * Creates the payment's row unless it exists. Native SQL because JPA has no {@code ON CONFLICT}:
     * this is what makes "one row per payment" hold with any number of instances.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
                    INSERT INTO payments (validation_id, provider, payment_ref, messages, created_at, updated_at)
                    VALUES (:validationId, :provider, :paymentRef, '[]', now(), now())
                    ON CONFLICT (provider, payment_ref) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(
            @Param("validationId") Long validationId,
            @Param("provider") String provider,
            @Param("paymentRef") String paymentRef);

    /** {@code SELECT … FOR UPDATE} on the payment's row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentEntity> findByProviderAndPaymentRef(String provider, String paymentRef);

    List<PaymentEntity> findByValidationIdOrderById(Long validationId);
}
