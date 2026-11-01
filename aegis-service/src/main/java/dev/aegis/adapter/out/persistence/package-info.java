/**
 * PostgreSQL through Spring Data JPA; the only package allowed to use {@code jakarta.persistence}.
 * {@code entity}: table mappings; {@code repository}: Spring Data interfaces with the locking queries;
 * this package: {@code *PersistenceAdapter} classes that implement the repository ports and map
 * between entities and domain records.
 */
package dev.aegis.adapter.out.persistence;
