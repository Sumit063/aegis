package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The NPCI desk records what Aegis tells it, once per message, and only from Aegis. */
class NpciDeskControllerTest {

    private static final String AEGIS_SECRET = "aegis-secret-for-tests-only-0000001";

    private final NpciDeskController desk = new NpciDeskController(
            new ShopProperties(null, null, null, null, AEGIS_SECRET, Set.of(), Duration.ofDays(7)),
            JsonMapper.builder().build());

    private static String message(String id, String verdict, String outcome) {
        return """
                {"id":"%s","verdict":"%s","provider":"bbps","type":"ASYNC_RESPONSE","outcome":"%s","paymentRef":"T1"}""".formatted(id, verdict, outcome);
    }

    private String deliver(String body) {
        String now = String.valueOf(Instant.now().getEpochSecond());
        return desk.message(body, now, "v1=" + Hmac.sha256Hex(AEGIS_SECRET, now + "." + body))
                .getBody();
    }

    @Test
    void recordsEachPassedMessageOnceInOrder() {
        deliver(message("1-1", "PASSED", "PENDING"));
        deliver(message("1-1", "PASSED", "PENDING"));
        deliver(message("1-2", "PASSED", "SUCCESS"));
        deliver(message("1-3", "BLOCKED DOWNGRADE", "FAILED"));

        assertThat(desk.payment("T1")).containsExactly("PENDING", "SUCCESS");
    }

    @Test
    void refusesWhatAegisDidNotSign() {
        String now = String.valueOf(Instant.now().getEpochSecond());

        assertThat(desk.message(message("2-1", "PASSED", "SUCCESS"), now, "v1=forged")
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(desk.payment("T1")).isEmpty();
    }
}
