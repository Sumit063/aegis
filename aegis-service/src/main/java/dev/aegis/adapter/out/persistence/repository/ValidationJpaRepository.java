package dev.aegis.adapter.out.persistence.repository;

import dev.aegis.adapter.out.persistence.entity.ValidationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ValidationJpaRepository extends JpaRepository<ValidationEntity, Long> {

    /** {@code SELECT … FOR UPDATE}: serialises every check about this validation's payments. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ValidationEntity> findByProviderAndProviderRef(String provider, String providerRef);
}
