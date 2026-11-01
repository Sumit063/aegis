package dev.aegis.adapter.out.persistence.repository;

import dev.aegis.adapter.out.persistence.entity.ConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigJpaRepository extends JpaRepository<ConfigEntity, String> {}
