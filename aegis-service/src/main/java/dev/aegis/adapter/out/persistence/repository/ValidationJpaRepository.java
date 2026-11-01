package dev.aegis.adapter.out.persistence.repository;

import dev.aegis.adapter.out.persistence.entity.ValidationEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ValidationJpaRepository extends JpaRepository<ValidationEntity, Long> {

    /** {@code SELECT … FOR UPDATE}: serialises every check about this validation's payments. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ValidationEntity> findByProviderAndProviderRef(String provider, String providerRef);

    List<ValidationEntity> findTop100ByMerchantRefOrProviderRefOrderByIdDesc(String merchantRef, String providerRef);

    /** Old validations no payment refers to any more (payments are purged first). */
    @Modifying
    @Query(value = """
                    DELETE FROM validations WHERE id IN
                      (SELECT v.id FROM validations v
                       WHERE v.created_at < :cutoff
                         AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.validation_id = v.id)
                       ORDER BY v.id LIMIT :batch)""", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batch") int batch);
}
