package dev.aegis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.config.AegisProperties;
import dev.aegis.config.Role;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boots the whole service against real PostgreSQL and Kafka. */
class AegisApplicationIT extends IntegrationTest {

    @Autowired
    AegisProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.management.port}")
    int managementPort;

    @Test
    void startsWithAllRolesAndReachesTheDatabase() {
        assertThat(properties.roles()).containsExactlyInAnyOrder(Role.values());
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }

    /** The endpoints deploy/check.sh reads after every deploy. */
    @Test
    void reportsHealthVersionAndRoles() throws Exception {
        assertThat(get("health/liveness")).contains("\"UP\"");
        assertThat(get("health/readiness")).contains("\"UP\"");
        assertThat(get("info"))
                .contains("\"environment\":\"local\"", "\"version\":\"local\"")
                .contains("\"roles\":\"ingress,checker,gateway,forwarder,admin,partner\"");
    }

    private String get(String path) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/" + path))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }
}
