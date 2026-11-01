package dev.aegis.adapter.out.persistence.repository;

import dev.aegis.adapter.out.persistence.entity.PaymentEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    @Query("select p.validationId from PaymentEntity p where p.provider = :provider and p.paymentRef = :paymentRef")
    Optional<Long> findValidationId(@Param("provider") String provider, @Param("paymentRef") String paymentRef);

    @Query(value = """
                    SELECT p.* FROM payments p LEFT JOIN validations v ON v.id = p.validation_id
                    WHERE p.payment_ref = :ref OR v.merchant_ref = :ref OR v.provider_ref = :ref
                    ORDER BY p.id DESC LIMIT 100""", nativeQuery = true)
    List<PaymentEntity> findByRef(@Param("ref") String ref);

    /** {@code @>} is JSON containment: some message in the row has verdict BLOCKED. */
    @Query(value = """
                    SELECT * FROM payments
                    WHERE updated_at >= :since AND messages @> '[{"verdict":"BLOCKED"}]'
                    ORDER BY updated_at DESC LIMIT :limit""", nativeQuery = true)
    List<PaymentEntity> findBlockedSince(@Param("since") Instant since, @Param("limit") int limit);

    @Modifying
    @Query(value = """
                    DELETE FROM payments WHERE id IN
                      (SELECT id FROM payments WHERE created_at < :cutoff ORDER BY id LIMIT :batch)""", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    @Modifying
    @Query(value = """
                    DELETE FROM payments WHERE id IN
                      (SELECT id FROM payments
                       WHERE payment_ref IS NULL AND validation_id IS NULL AND created_at < :cutoff
                       ORDER BY id LIMIT :batch)""", nativeQuery = true)
    int deleteUntrustedOlderThan(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> lockById(@Param("id") Long id);

    /**
     * Due payments for the Forwarder. {@code SKIP LOCKED} lets several instances take different rows
     * at once without waiting on each other (Competing Consumers).
     */
    @Query(value = """
                    SELECT * FROM payments
                    WHERE forward_due_at <= :now
                    ORDER BY forward_due_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<PaymentEntity> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
