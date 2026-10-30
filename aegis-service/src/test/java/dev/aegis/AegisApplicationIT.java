package dev.aegis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.config.AegisProperties;
import dev.aegis.config.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots the whole service against real PostgreSQL and Kafka. */
@SpringBootTest
@Testcontainers
class AegisApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @Autowired
    AegisProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsWithAllRolesAndReachesTheDatabase() {
        assertThat(properties.roles()).containsExactlyInAnyOrder(Role.values());
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }
}
